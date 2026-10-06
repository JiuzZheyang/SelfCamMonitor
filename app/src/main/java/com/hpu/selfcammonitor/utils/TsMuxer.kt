package com.hpu.selfcammonitor.utils

import java.io.ByteArrayOutputStream

/**
 * 极简 MPEG-TS 封装器：把 H.264 Annex-B 访问单元（可选 AAC 音频）封装为 MPEG-TS，
 * 供 HLS 播放（hls.js 在浏览器里把 TS 转封装给 MSE）。
 *
 * 结构：PAT(PID 0) + PMT(PID 0x1000) + 视频 PES(PID 0x100, stream_type 0x1B)
 *       + 可选音频 PES(PID 0x101, stream_type 0x0F, ADTS-AAC)。
 * 时间戳统一 90kHz（PTS=DTS，编码无 B 帧）。
 *
 * 关键点：PMT 的视频 ES 里带 **AVC video descriptor(tag 0x28)**，
 * 显式给出 profile/compat/level —— hls.js 才能确定 codec 串(avc1.PPCCLL)，
 * 否则可能建不出 SourceBuffer 而报致命错误。
 */
class TsMuxer {
    companion object {
        const val PID_PAT = 0x0000
        const val PID_PMT = 0x1000
        const val PID_VIDEO = 0x0100
        const val PID_AUDIO = 0x0101
        const val STREAM_TYPE_H264 = 0x1B
        const val STREAM_TYPE_AAC = 0x0F
        private const val CC_MASK = 0x0F
    }

    private var ccPat = 0
    private var ccPmt = 0
    private var ccVideo = 0
    private var ccAudio = 0

    /**
     * 每个分片开头写入 PAT + PMT。
     * @param sps 关键帧里的 SPS（用于 PMT 的 AVC 描述符，可为 null → 用默认 profile/level）
     * @param withAudio 是否声明音频流（PMT 里加 AAC ES）
     */
    fun writeHeaders(out: ByteArrayOutputStream, sps: ByteArray?, withAudio: Boolean) {
        writeSection(out, PID_PAT, buildPat(), ccPat)
        ccPat = (ccPat + 1) and CC_MASK
        writeSection(out, PID_PMT, buildPmt(sps, withAudio), ccPmt)
        ccPmt = (ccPmt + 1) and CC_MASK
    }

    /** 写入一个视频访问单元（Annex-B），pts90 为 90kHz 时间戳 */
    fun writeAccessUnit(out: ByteArrayOutputStream, es: ByteArray, pts90: Long) {
        val pes = buildPes(es, pts90, 0xE0)
        val pcr = (pts90 - 9000).coerceAtLeast(0)  // PCR 略超前于 PTS
        ccVideo = writeTsPackets(out, PID_VIDEO, pes, start = true, cc = ccVideo, pcr90 = pcr)
    }

    /** 写入一个 AAC(ADTS) 音频帧 */
    fun writeAudioAccessUnit(out: ByteArrayOutputStream, adts: ByteArray, pts90: Long) {
        if (adts.isEmpty()) return
        val pes = buildPes(adts, pts90, 0xC0)
        ccAudio = writeTsPackets(out, PID_AUDIO, pes, start = true, cc = ccAudio, pcr90 = null)
    }

    /** 每个新分片重新从 0 开始统计（不影响 PAT/PMT 的连续计数器结构） */
    fun resetContinuity() {
        ccVideo = 0
        ccAudio = 0
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
            out.write(pid and 0xFF)
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

    private fun buildPes(es: ByteArray, pts90: Long, streamId: Int): ByteArray {
        val out = ByteArrayOutputStream(es.size + 32)
        out.write(0x00); out.write(0x00); out.write(0x01); out.write(streamId)
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
        val body = ByteArray(16)
        var i = 0
        body[i++] = 0x00                                   // table_id
        body[i++] = (0xB0 or ((13 ushr 8) and 0x0F)).toByte()
        body[i++] = (13 and 0xFF).toByte()                 // section_length = 13
        body[i++] = 0x00; body[i++] = 0x01                 // transport_stream_id
        body[i++] = 0xC1.toByte()                          // version/current_next
        body[i++] = 0x00                                   // section_number
        body[i++] = 0x00                                   // last_section_number
        body[i++] = 0x00; body[i++] = 0x01                 // program_number = 1
        body[i++] = (0xE0 or ((PID_PMT ushr 8) and 0x1F)).toByte()
        body[i++] = (PID_PMT and 0xFF).toByte()
        val crc = crc32(body, 0, 12)
        body[i++] = ((crc ushr 24) and 0xFF).toByte()
        body[i++] = ((crc ushr 16) and 0xFF).toByte()
        body[i++] = ((crc ushr 8) and 0xFF).toByte()
        body[i++] = (crc and 0xFF).toByte()
        return body
    }

    private fun buildPmt(sps: ByteArray?, withAudio: Boolean): ByteArray {
        val es = ByteArrayOutputStream()
        // ── 视频 ES（带 AVC video descriptor tag 0x28）
        val desc = buildAvcDescriptor(sps)
        es.write(STREAM_TYPE_H264)
        es.write((0xE0 or ((PID_VIDEO ushr 8) and 0x1F)))
        es.write(PID_VIDEO and 0xFF)
        es.write(0xF0 or ((desc.size ushr 8) and 0x0F))
        es.write(desc.size and 0xFF)
        es.write(desc, 0, desc.size)
        // ── 音频 ES（AAC/ADTS，无描述符）
        if (withAudio) {
            es.write(STREAM_TYPE_AAC)
            es.write(0xE0 or ((PID_AUDIO ushr 8) and 0x1F))
            es.write(PID_AUDIO and 0xFF)
            es.write(0xF0); es.write(0x00)
        }
        val esBytes = es.toByteArray()
        // section_length = 9(固定头) + ES 字节 + 4(CRC)
        val sectionLength = 9 + esBytes.size + 4
        val body = ByteArray(3 + sectionLength)
        var i = 0
        body[i++] = 0x02
        body[i++] = (0xB0 or ((sectionLength ushr 8) and 0x0F)).toByte()
        body[i++] = (sectionLength and 0xFF).toByte()
        body[i++] = 0x00; body[i++] = 0x01                 // program_number = 1
        body[i++] = 0xC1.toByte()
        body[i++] = 0x00; body[i++] = 0x00
        body[i++] = (0xE0 or ((PID_VIDEO ushr 8) and 0x1F)).toByte()
        body[i++] = (PID_VIDEO and 0xFF).toByte()          // PCR_PID = video
        body[i++] = 0xF0.toByte(); body[i++] = 0x00        // program_info_length = 0
        System.arraycopy(esBytes, 0, body, i, esBytes.size); i += esBytes.size
        val crc = crc32(body, 0, i)
        body[i++] = ((crc ushr 24) and 0xFF).toByte()
        body[i++] = ((crc ushr 16) and 0xFF).toByte()
        body[i++] = ((crc ushr 8) and 0xFF).toByte()
        body[i++] = (crc and 0xFF).toByte()
        return body
    }

    /** AVC video descriptor (tag 0x28)：profile / compat / level（从 SPS 解析，缺省 100/0/31） */
    private fun buildAvcDescriptor(sps: ByteArray?): ByteArray {
        var profile = 0x64
        var compat = 0x00
        var level = 0x1F
        if (sps != null) {
            var i = 0
            while (i + 6 < sps.size) {
                if (sps[i].toInt() == 0 && sps[i + 1].toInt() == 0 && sps[i + 2].toInt() == 1) {
                    if ((sps[i + 3].toInt() and 0x1F) == 7) {
                        profile = sps[i + 4].toInt() and 0xFF
                        compat = sps[i + 5].toInt() and 0xFF
                        level = sps[i + 6].toInt() and 0xFF
                        break
                    }
                    i += 4
                } else {
                    i++
                }
            }
        }
        return byteArrayOf(0x28, 4, profile.toByte(), compat.toByte(), level.toByte(), 0xFF.toByte())
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
