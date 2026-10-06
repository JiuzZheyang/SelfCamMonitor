package com.hpu.selfcammonitor.utils

import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 硬件 H.264 编码器：把相机 YUV(NV12) 帧编码为 Annex-B 码流，逐帧回调给上层
 * （供网页 WebCodecs 低延迟播放）。
 *
 * 关键点：
 *  - 使用设备硬件编码器（MediaCodec），CPU 占用远低于 JPEG 软编码
 *  - 请求在 IDR 前附带 SPS/PPS（prepend-sps-pps-to-idr-frames），新客户端可随时加入解码
 *  - 提供 requestKeyFrame()：新客户端接入时立即产生关键帧，避免等待
 */
class H264Encoder(
    private val onFrame: (annexb: ByteArray, keyframe: Boolean, ptsUs: Long) -> Unit
) {
    companion object {
        private const val TAG = "H264Encoder"
        private const val MIME = "video/avc"
        private const val CF_SEMI_PLANAR = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
        private const val CF_FLEXIBLE = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
        private const val CF_PACKED_SEMI = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedSemiPlanar
    }

    private var codec: MediaCodec? = null
    private var width = 0
    private var height = 0
    private var fps = 15
    private var frameIndex = 0L
    private var colorFormat = CF_SEMI_PLANAR
    private var useInputImage = false

    private var spsPps: ByteArray? = null

    @Volatile var isRunning = false; private set

    /** Annex-B 首个关键帧解析出的 codec 字符串（如 avc1.42E01E），供网页 VideoDecoder 配置 */
    @Volatile var codecString: String? = null; private set

    private val keyframeReq = AtomicBoolean(false)

    private val bufferInfo = MediaCodec.BufferInfo()

    /** 请求编码器尽快产出一个关键帧（IDR） */
    fun requestKeyFrame() {
        keyframeReq.set(true)
    }

    /** 当前是否已就绪并编码中 */
    fun ready(): Boolean = isRunning && codecString != null

    fun start(w: Int, h: Int, targetFps: Int, bitrate: Int): Boolean {
        stop()
        return try {
            val (cf, imgMode) = pickColorFormat()
            colorFormat = cf
            useInputImage = imgMode
            val c = MediaCodec.createEncoderByType(MIME)
            try {
                c.configure(buildFormat(w, h, targetFps, bitrate, cf, true), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            } catch (e: Exception) {
                // 某些设备的 CBR 等高级参数不兼容 → 回退默认参数重试
                Log.w(TAG, "configure(CBR) 失败，回退默认参数", e)
                try { c.reset() } catch (_: Exception) {}
                c.configure(buildFormat(w, h, targetFps, bitrate, cf, false), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
            c.start()
            codec = c
            this.width = w
            this.height = h
            this.fps = targetFps.coerceIn(1, 30)
            frameIndex = 0
            spsPps = null
            codecString = null
            isRunning = true
            Log.d(TAG, "编码器已启动 ${w}x$h @${this.fps}fps, ${bitrate / 1000}kbps, colorFormat=$cf")
            true
        } catch (e: Exception) {
            Log.e(TAG, "编码器启动失败", e)
            stop()
            false
        }
    }

    private fun buildFormat(w: Int, h: Int, targetFps: Int, bitrate: Int, cf: Int, withBitrateMode: Boolean): MediaFormat {
        val format = MediaFormat.createVideoFormat(MIME, w, h)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, cf)
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate.coerceAtLeast(500_000))
        format.setInteger(MediaFormat.KEY_FRAME_RATE, targetFps.coerceIn(1, 30))
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)  // 每秒一个关键帧
        if (withBitrateMode) {
            try {
                format.setInteger(
                    MediaFormat.KEY_BITRATE_MODE,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                )
            } catch (_: Exception) {
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // 让每个 IDR 前自动带 SPS/PPS，新客户端可随时解码
            try { format.setInteger("prepend-sps-pps-to-idr-frames", 1) } catch (_: Exception) {}
        }
        return format
    }

    fun stop() {
        isRunning = false
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        codec = null
        codecString = null
        spsPps = null
    }

    /** 编码一帧 NV12。仅在调用线程（相机分析线程）内串行调用 */
    fun encode(nv12: ByteArray, w: Int, h: Int) {
        val c = codec ?: return
        if (!isRunning || w != width || h != height) return
        try {
            if (keyframeReq.getAndSet(false)) requestSyncFrame()
            val inIdx = c.dequeueInputBuffer(10_000)
            if (inIdx >= 0) {
                val pts = frameIndex * 1_000_000L / fps
                var size = 0
                if (useInputImage) {
                    val img = c.getInputImage(inIdx)
                    if (img != null) {
                        writeNv12ToImage(img, nv12, w, h)
                        size = w * h * 3 / 2
                    }
                } else {
                    val buf = c.getInputBuffer(inIdx)
                    if (buf != null) {
                        buf.clear()
                        val n = minOf(buf.remaining(), nv12.size)
                        buf.put(nv12, 0, n)
                        size = n
                    }
                }
                c.queueInputBuffer(inIdx, 0, size, pts, 0)
                frameIndex++
            }
            drain(c)
        } catch (e: Exception) {
            Log.e(TAG, "编码失败", e)
        }
    }

    private fun drain(c: MediaCodec) {
        while (true) {
            val outIdx = c.dequeueOutputBuffer(bufferInfo, 0)
            if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) break
            if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val of = c.outputFormat
                val csd0 = of.getByteBuffer("csd-0")
                val csd1 = of.getByteBuffer("csd-1")
                spsPps = buildSpsPps(csd0, csd1)
                codecString = parseCodecString(csd0)
                continue
            }
            if (outIdx < 0) continue
            val ob = c.getOutputBuffer(outIdx)
            if (ob != null) {
                ob.position(bufferInfo.offset)
                ob.limit(bufferInfo.offset + bufferInfo.size)
                val flags = bufferInfo.flags
                val isKey = (flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                val isConfig = (flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                if (bufferInfo.size > 0 && !isConfig) {
                    var data = ByteArray(bufferInfo.size)
                    ob.get(data)
                    if (isKey) {
                        val sp = spsPps
                        if (sp != null && !startsWithSps(data)) {
                            val merged = ByteArray(sp.size + data.size)
                            System.arraycopy(sp, 0, merged, 0, sp.size)
                            System.arraycopy(data, 0, merged, sp.size, data.size)
                            data = merged
                        }
                    }
                    onFrame(data, isKey, bufferInfo.presentationTimeUs)
                }
            }
            c.releaseOutputBuffer(outIdx, false)
        }
    }

    private fun requestSyncFrame() {
        try {
            val b = Bundle()
            b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            codec?.setParameters(b)
        } catch (e: Exception) {
            Log.w(TAG, "请求关键帧失败", e)
        }
    }

    private fun pickColorFormat(): Pair<Int, Boolean> {
        return try {
            val ci = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull {
                it.isEncoder && it.supportedTypes.any { t -> t.equals(MIME, true) }
            } ?: return CF_SEMI_PLANAR to false
            val cf = ci.getCapabilitiesForType(MIME).colorFormats
            when {
                cf.contains(CF_SEMI_PLANAR) -> CF_SEMI_PLANAR to false
                cf.contains(CF_PACKED_SEMI) -> CF_PACKED_SEMI to false
                cf.contains(CF_FLEXIBLE) -> CF_FLEXIBLE to true
                else -> CF_SEMI_PLANAR to false
            }
        } catch (e: Exception) {
            Log.w(TAG, "查询编码器颜色格式失败，使用 NV12", e)
            CF_SEMI_PLANAR to false
        }
    }

    private fun buildSpsPps(csd0: java.nio.ByteBuffer?, csd1: java.nio.ByteBuffer?): ByteArray? {
        if (csd0 == null) return null
        val b0 = ByteArray(csd0.remaining()).also { csd0.get(it) }
        val b1 = if (csd1 != null) ByteArray(csd1.remaining()).also { csd1.get(it) } else ByteArray(0)
        return b0 + b1
    }

    /** 从 csd-0（含起始码的 SPS）解析 profile/level，构造 avc1.PPCCLL */
    private fun parseCodecString(csd0: java.nio.ByteBuffer?): String? {
        if (csd0 == null) return null
        val b = ByteArray(csd0.remaining()).also { csd0.get(it) }
        // 找到 SPS NAL 头 0x67
        var i = 0
        while (i < b.size && (b[i].toInt() and 0x1F) != 7) i++
        if (i + 3 >= b.size) return null
        val profile = b[i + 1].toInt() and 0xFF
        val constraint = b[i + 2].toInt() and 0xFF
        val level = b[i + 3].toInt() and 0xFF
        return "avc1.%02x%02x%02x".format(profile, constraint, level)
    }

    private fun startsWithSps(data: ByteArray): Boolean {
        if (data.size < 5) return false
        return ((data[0].toInt() == 0 && data[1].toInt() == 0 && data[2].toInt() == 0 && data[3].toInt() == 1 && (data[4].toInt() and 0x1F) == 7) ||
                (data[0].toInt() == 0 && data[1].toInt() == 0 && data[2].toInt() == 1 && (data[3].toInt() and 0x1F) == 7))
    }

    /** 把 NV12 写入 flexible 编码器的输入 Image（各 plane 带 rowStride/pixelStride） */
    private fun writeNv12ToImage(img: Image, nv12: ByteArray, w: Int, h: Int) {
        val y = img.planes[0]
        val yBuf = y.buffer
        val yRow = y.rowStride
        val yPix = y.pixelStride
        if (yPix == 1) {
            for (r in 0 until h) {
                yBuf.position(r * yRow)
                val remain = yBuf.remaining()
                if (remain <= 0) break
                yBuf.put(nv12, r * w, minOf(w, remain))
            }
        } else {
            var src = 0
            for (r in 0 until h) {
                for (c in 0 until w) {
                    yBuf.position(r * yRow + c * yPix)
                    yBuf.put(nv12[src++])
                }
            }
        }
        val uP = img.planes[1]
        val vP = img.planes[2]
        val uBuf = uP.buffer
        val vBuf = vP.buffer
        val cw = w / 2
        val ch = h / 2
        val ySize = w * h
        for (r in 0 until ch) {
            for (c in 0 until cw) {
                val idx = ySize + (r * cw + c) * 2
                if (idx + 1 >= nv12.size) return
                uBuf.position(r * uP.rowStride + c * uP.pixelStride)
                uBuf.put(nv12[idx])
                vBuf.position(r * vP.rowStride + c * vP.pixelStride)
                vBuf.put(nv12[idx + 1])
            }
        }
    }
}
