package com.hpu.selfcammonitor.utils

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.util.Log

/**
 * 采集麦克风音频并编码为 AAC(ADTS)，逐帧回调给上层封装进 MPEG-TS（HLS）。
 *
 * - AudioRecord 44100Hz 单声道 PCM16 → MediaCodec(audio/mp4a-latm) AAC-LC 64kbps
 * - 输出裸 AAC 帧用 ADTS 头包好（TS 的 stream_type 0x0F 就是 ADTS-AAC）
 * - 时间戳用与视频相同的 epoch（纳秒时钟），保证音视频对齐
 * - 任何一步失败都只记录日志并停用音频，不影响视频推流
 */
class AudioStreamer(
    private val epochUs: Long,
    private val onFrame: (adts: ByteArray, ptsUs: Long) -> Unit
) {
    companion object {
        private const val TAG = "AudioStreamer"
        private const val SAMPLE_RATE = 44100
        private const val CHANNELS = 1
        private const val BITRATE = 64000
        private const val MIME = "audio/mp4a-latm"
        private const val FRAME_TIMEOUT_US = 10_000L
        private const val ADTS_PROFILE = 1   // AAC-LC(objectType 2) → ADTS profile = objectType-1
        private const val SF_INDEX = 4       // 44100Hz 的 sampling_frequency_index
    }

    private var record: AudioRecord? = null
    private var codec: MediaCodec? = null
    private var thread: Thread? = null

    @Volatile private var running = false

    private val bufferInfo = MediaCodec.BufferInfo()

    val isActive: Boolean get() = running

    fun start(): Boolean {
        if (running) return true
        return try {
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf <= 0) {
                Log.w(TAG, "AudioRecord 参数不受支持 minBuf=$minBuf")
                return false
            }
            val bufSize = maxOf(minBuf * 2, SAMPLE_RATE * 2)
            val ar = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize
            )
            if (ar.state != AudioRecord.STATE_INITIALIZED) {
                ar.release()
                Log.e(TAG, "AudioRecord 初始化失败")
                return false
            }
            val fmt = MediaFormat.createAudioFormat(MIME, SAMPLE_RATE, CHANNELS)
            fmt.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
            fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            val c = MediaCodec.createEncoderByType(MIME)
            c.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            c.start()

            record = ar
            codec = c
            running = true
            ar.startRecording()
            thread = Thread({ loop() }, "audio-streamer").also { it.start() }
            Log.d(TAG, "音频采集已启动 ${SAMPLE_RATE}Hz ${CHANNELS}ch")
            true
        } catch (e: Exception) {
            Log.e(TAG, "音频启动失败", e)
            stop()
            false
        }
    }

    private fun loop() {
        val ar = record ?: return
        val c = codec ?: return
        val pcm = ByteArray(4096)
        while (running) {
            val n = try {
                ar.read(pcm, 0, pcm.size)
            } catch (e: Exception) {
                -1
            }
            if (n > 0) {
                try {
                    val inIdx = c.dequeueInputBuffer(FRAME_TIMEOUT_US)
                    if (inIdx >= 0) {
                        val buf = c.getInputBuffer(inIdx)
                        if (buf != null) {
                            buf.clear()
                            val w = minOf(n, buf.remaining())
                            buf.put(pcm, 0, w)
                            val ptsUs = System.nanoTime() / 1000 - epochUs
                            c.queueInputBuffer(inIdx, 0, w, ptsUs, 0)
                        } else {
                            c.queueInputBuffer(inIdx, 0, 0, 0, 0)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "音频入队失败", e)
                }
            }
            drain(c)
        }
    }

    private fun drain(c: MediaCodec) {
        while (running) {
            val outIdx = try {
                c.dequeueOutputBuffer(bufferInfo, 0)
            } catch (e: Exception) {
                -1
            }
            if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) break
            if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue
            if (outIdx < 0) continue
            try {
                val ob = c.getOutputBuffer(outIdx)
                if (ob != null && bufferInfo.size > 0 &&
                    (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                ) {
                    ob.position(bufferInfo.offset)
                    ob.limit(bufferInfo.offset + bufferInfo.size)
                    val aac = ByteArray(bufferInfo.size)
                    ob.get(aac)
                    onFrame(addAdts(aac), bufferInfo.presentationTimeUs)
                }
            } catch (e: Exception) {
                Log.w(TAG, "音频取帧失败", e)
            } finally {
                try { c.releaseOutputBuffer(outIdx, false) } catch (_: Exception) {}
            }
        }
    }

    /** 给裸 AAC 帧加 7 字节 ADTS 头（无 CRC） */
    private fun addAdts(aac: ByteArray): ByteArray {
        val frameLen = aac.size + 7
        val out = ByteArray(frameLen)
        out[0] = 0xFF.toByte()
        out[1] = 0xF1.toByte()  // MPEG-4 / layer 0 / no CRC
        out[2] = (((ADTS_PROFILE and 0x03) shl 6) or ((SF_INDEX and 0x0F) shl 2) or ((CHANNELS shr 2) and 1)).toByte()
        out[3] = (((CHANNELS and 0x03) shl 6) or ((frameLen shr 11) and 0x03)).toByte()
        out[4] = ((frameLen shr 3) and 0xFF).toByte()
        out[5] = (((frameLen and 0x07) shl 5) or 0x1F).toByte()
        out[6] = 0xFC.toByte()
        System.arraycopy(aac, 0, out, 7, aac.size)
        return out
    }

    fun stop() {
        running = false
        try { record?.stop() } catch (_: Exception) {}
        try { record?.release() } catch (_: Exception) {}
        record = null
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        codec = null
        try { thread?.join(500) } catch (_: Exception) {}
        thread = null
    }
}
