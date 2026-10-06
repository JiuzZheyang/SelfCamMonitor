package com.hpu.selfcammonitor.utils

import java.io.ByteArrayOutputStream

/**
 * 极简 MPEG-TS 封装器：把 H.264 Annex-B 访问单元封装为 MPEG-TS 分片，
 * 供 HLS 播放（hls.js 会在浏览器里把 TS 再转封装给 MSE）。
 *
 * 结构：PAT(PID 0) + PMT(PID 0x1000) + 视频 PES(PID 0x100, stream_type 0x1B)。
 * 时间戳统一用 90kHz（PTS=DTS，编码无 B 帧）。
 */
class TsMuxer {
    companion object {
        const val PID_PAT = 0x0000
        const val PID_PMT = 0x1000
        const val PID_VIDEO = 0x0100
        const val STREAM_TYPE_H264 = 0x1B
        private const val CC_MASK = 0x0F
    }

    private var ccPat = 0
    private var ccPmt = 0
    private var ccVideo = 0
    private var patPmtWritten = 0

    /** 每个分片开头写入 PAT + PMT */
    fun writeHeaders(out: ByteArrayOutputStream) {
        writeSection(out, PID_PAT, buildPat(), ccPat)
        ccPat = (ccPat + 1) and CC_MASK
        writeSection(out, PID_PMT, buildPmt(), ccPmt)
        ccPmt = (ccPmt + 1) and CC_MASK
        patPmtWritten++
    }

    /** 写入一个视频访问单元（Annex-B），pts90 为 90kHz 时间戳 */
    fun writeAccessUnit(out: ByteArrayOutputStream, es: ByteArray, pts90: Long) {
        val pes = buildPes(es, pts90)
        val pcr = (pts90 - 9000).coerceAtLeast(0)  // PCR 略超前于 PTS
        ccVideo = writeTsPackets(out, PID_VIDEO, pes, start = true, cc = ccVideo, pcr90 = pcr)
    }

    // ── 内部实现 ─────────────────────────────────────────────

    private fun writeSection(out: ByteArrayOutputStream, pid: Int, section: ByteArray, cc: Int) {
        val buf = ByteArray(184)
        System.arraycopy(section, 0, buf, 0, section.size)
        java.util.Arrays.fill(buf, section.size, 184, 0xFF.toByte())
        writeTsPackets(out, pid, buf, start = true, cc = cc, pcr90 = null)
    }

    /** 写入 TS 包，返回更新后的连续计数器 */
    private fun writeTsPackets(
        out: ByteArrayOutputStream,
        pid: Int,
        payload: ByteArray,
        start: Boolean,
        cc: Int,
        pcr90: Long?
    ): Int {
        var pos = 0
        var first = true
        var counter = cc
        val n = payload.size
        do {
            val remaining = n - pos
            val pcrPresent = first && pcr90 != null
            val minAf = if (pcrPresent) 7 else 0

            var afc: Int
            var afLen: Int
            var take: Int

            if (!pcrPresent && remaining >= 184) {
                afc = 1
                afLen = 0
                take = 184
            } else {
                afc = 3
                val maxPayload = 184 - 1 - minAf
                take = if (remaining < maxPayload) remaining else maxPayload
                afLen = 183 - take
                if (afLen < minAf || afLen < 1) {
                    afLen = if (minAf > 1) minAf else 1
                    take = 183 - afLen
                }
            }

            out.write(0x47)
            val b1 = (if (first && start) 0x40 else 0x00) or ((pid ushr 8) and 0x1F)
            out.write(b1)
            out.write(((afc shl 4) and 0x30) or (counter and CC_MASK))

            if (afc == 3) {
                out.write(afLen)
                var flags = 0
                if (pcrPresent) flags = flags or 0x10
                out.write(flags)
                var body = afLen - 1
                if (pcrPresent) {
                    writePcr(out, pcr90!!)
                    body -= 6
                }
                while (body > 0) {
                    out.write(0xFF)
                    body--
                }
            }

            out.write(payload, pos, take)
            pos += take
            counter = (counter + 1) and CC_MASK
            first = false
        } while (pos < n)
        return counter
    }

    /** 把 PES 包内容（含 PES 头）当作 TS payload 承载；PCR 用 pcr90 */
    private fun writePcr(out: ByteArrayOutputStream, pcr90: Long) {
        val base = pcr90 and 0x1FFFFFFFFL
        val ext = 0
        out.write(((base ushr 25) and 0xFF).toInt())
        out.write(((base ushr 17) and 0xFF).toInt())
        out.write(((base ushr 9) and 0xFF).toInt())
        out.write(((base ushr 1) and 0xFF).toInt())
        out.write((((base and 1L) shl 7) or 0x7EL or (((ext ushr 8) and 1).toLong())).toInt())
        out.write(ext and 0xFF)
    }

    private fun buildPes(es: ByteArray, pts90: Long): ByteArray {
        val out = ByteArrayOutputStream(es.size + 32)
        // PES start code + stream_id
        out.write(0x00); out.write(0x00); out.write(0x01); out.write(0xE0)
        val hdrDataLen = 5  // PTS only
        val pesLen = 3 + hdrDataLen + es.size
        if (pesLen > 0xFFFF) {
            out.write(0x00); out.write(0x00)
        } else {
            out.write((pesLen ushr 8) and 0xFF); out.write(pesLen and 0xFF)
        }
        out.write(0x80)               // '10' + flags
        out.write(0x80)               // PTS_DTS_flags = 10 (仅 PTS)
        out.write(hdrDataLen)
        writePts(out, 0x2, pts90)
        out.write(es, 0, es.size)
        return out.toByteArray()
    }

    private fun writePts(out: ByteArrayOutputStream, prefix: Int, v: Long) {
        out.write(((prefix shl 4) or (((v ushr 30) and 0x07).toInt() shl 1) or 1))
        out.write(((v ushr 22) and 0xFF).toInt())
        out.write(((((v ushr 15) and 0x7F).toInt()) shl 1) or 1)
        out.write(((v ushr 7) and 0xFF).toInt())
        out.write((((v and 0x7F).toInt()) shl 1) or 1)
    }

    private fun buildPat(): ByteArray {
        val body = ByteArray(13)  // 5 header-after-length + 4 loop + 4 crc
        var i = 0
        body[i++] = 0x00                                   // table_id
        body[i++] = (0xB0 or ((13 ushr 8) and 0x0F)).toByte()
        body[i++] = (13 and 0xFF).toByte()                 // section_length = 13
        body[i++] = 0x00; body[i++] = 0x01                 // transport_stream_id
        body[i++] = 0xC1                                   // version/current_next
        body[i++] = 0x00                                   // section_number
        body[i++] = 0x00                                   // last_section_number
        body[i++] = 0x00; body[i++] = 0x01                 // program_number = 1
        body[i++] = (0xE0 or ((PID_PMT ushr 8) and 0x1F)).toByte()
        body[i++] = (PID_PMT and 0xFF).toByte()
        // CRC
        val crc = crc32(body, 0, 9)
        body[i++] = ((crc ushr 24) and 0xFF).toByte()
        body[i++] = ((crc ushr 16) and 0xFF).toByte()
        body[i++] = ((crc ushr 8) and 0xFF).toByte()
        body[i++] = (crc and 0xFF).toByte()
        return body
    }

    private fun buildPmt(): ByteArray {
        val body = ByteArray(18)
        var i = 0
        body[i++] = 0x02
        body[i++] = (0xB0 or ((18 ushr 8) and 0x0F)).toByte()
        body[i++] = (18 and 0xFF).toByte()                 // section_length = 18
        body[i++] = 0x00; body[i++] = 0x01                 // program_number = 1
        body[i++] = 0xC1
        body[i++] = 0x00; body[i++] = 0x00
        body[i++] = (0xE0 or ((PID_VIDEO ushr 8) and 0x1F)).toByte()
        body[i++] = (PID_VIDEO and 0xFF).toByte()          // PCR_PID = video
        body[i++] = 0xF0; body[i++] = 0x00                 // program_info_length = 0
        body[i++] = STREAM_TYPE_H264.toByte()
        body[i++] = (0xE0 or ((PID_VIDEO ushr 8) and 0x1F)).toByte()
        body[i++] = (PID_VIDEO and 0xFF).toByte()
        body[i++] = 0xF0; body[i++] = 0x00                 // ES_info_length = 0
        val crc = crc32(body, 0, 14)
        body[i++] = ((crc ushr 24) and 0xFF).toByte()
        body[i++] = ((crc ushr 16) and 0xFF).toByte()
        body[i++] = ((crc ushr 8) and 0xFF).toByte()
        body[i++] = (crc and 0xFF).toByte()
        return body
    }

    private fun crc32(data: ByteArray, off: Int, len: Int): Long {
        var crc = 0xFFFFFFFFL
        for (idx in off until off + len) {
            crc = crc xor ((data[idx].toLong() and 0xFF) shl 24)
            for (b in 0 until 8) {
                crc = if ((crc and 0x80000000L) != 0L) {
                    ((crc shl 1) xor 0x04C11DB7L) and 0xFFFFFFFFL
                } else {
                    (crc shl 1) and 0xFFFFFFFFL
                }
            }
        }
        return crc and 0xFFFFFFFFL
    }
}
