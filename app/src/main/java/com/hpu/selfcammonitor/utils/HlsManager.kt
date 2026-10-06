package com.hpu.selfcammonitor.utils

import java.io.ByteArrayOutputStream
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong

/**
 * HLS 分片管理器：把编码器输出的 H.264 访问单元切成 MPEG-TS 分片，
 * 维护一个滑动窗口（DVR），对外提供 m3u8 播放列表与分片下载。
 *
 * - 以关键帧为分片边界（编码器 I 帧间隔 1s → 分片约 1s）
 * - 保留最近 dvrWindowSec 秒的分片，支持网页回看（拖动进度条）
 */
class HlsManager(private val dvrWindowSec: Int = 90) {

    data class Segment(val seq: Long, val duration: Double, val bytes: ByteArray)

    private val muxer = TsMuxer()
    private val lock = Any()

    private val segments = ArrayDeque<Segment>()
    private var current = ByteArrayOutputStream(256 * 1024)
    private var currentStartPts = 0L
    private var lastPts = 0L
    private var nextSeq = 0L
    private var haveKeyframe = false

    /** 最近一次有客户端请求播放列表/分片的时间（用于按需启停编码器） */
    @Volatile var lastClientMs = 0L
        private set

    fun noteClient() {
        val wasIdle = !hasRecentClient()
        lastClientMs = System.currentTimeMillis()
        if (wasIdle) keyframeReq.set(true)
    }

    private val keyframeReq = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 消费一次「请求关键帧」标志 */
    fun consumeKeyframeRequestIfAny(): Boolean = keyframeReq.getAndSet(false)

    fun hasRecentClient(now: Long = System.currentTimeMillis()): Boolean =
        now - lastClientMs < 12_000L

    /** 接收一个访问单元 */
    fun feed(es: ByteArray, pts90: Long, keyframe: Boolean) {
        synchronized(lock) {
            if (keyframe) {
                if (haveKeyframe && current.size() > 0) {
                    finalize(pts90)
                }
                muxer.writeHeaders(current)
                currentStartPts = pts90
                haveKeyframe = true
            } else if (!haveKeyframe) {
                return  // 首个关键帧之前的数据无法解码，丢弃
            }
            muxer.writeAccessUnit(current, es, pts90)
            lastPts = pts90
        }
    }

    private fun finalize(nextKeyPts: Long) {
        val dur = ((nextKeyPts - currentStartPts).coerceAtLeast(1L)) / 90000.0
        val seg = Segment(nextSeq, dur, current.toByteArray())
        segments.addLast(seg)
        nextSeq++
        current = ByteArrayOutputStream(256 * 1024)
        trim()
    }

    private fun trim() {
        var totalDur = 0.0
        for (s in segments) totalDur += s.duration
        while (segments.size > 1 && totalDur - segments.first.duration > dvrWindowSec) {
            totalDur -= segments.first.duration
            segments.removeFirst()
        }
    }

    /** 生成 m3u8 播放列表；无分片时返回 null */
    fun playlist(): String? {
        synchronized(lock) {
            if (segments.isEmpty()) return null
            val targetDur = Math.ceil(segments.maxOf { it.duration }).toInt().coerceAtLeast(1)
            val sb = StringBuilder()
            sb.append("#EXTM3U\n")
            sb.append("#EXT-X-VERSION:3\n")
            sb.append("#EXT-X-TARGETDURATION:").append(targetDur).append("\n")
            sb.append("#EXT-X-MEDIA-SEQUENCE:").append(segments.first.seq).append("\n")
            sb.append("#EXT-X-INDEPENDENT-SEGMENTS\n")
            sb.append("#EXT-X-DISCONTINUITY-SEQUENCE:0\n")
            for (s in segments) {
                sb.append("#EXTINF:").append(String.format(java.util.Locale.US, "%.3f", s.duration)).append(",\n")
                sb.append("seg/").append(s.seq).append(".ts\n")
            }
            return sb.toString()
        }
    }

    fun segment(seq: Long): ByteArray? {
        synchronized(lock) {
            for (s in segments) if (s.seq == seq) return s.bytes
            return null
        }
    }

    fun segmentCount(): Int = synchronized(lock) { segments.size }

    private val servedBytes = AtomicLong(0)

    fun addServedBytes(n: Long) { servedBytes.addAndGet(n) }

    fun getServedBytes(): Long = servedBytes.get()

    fun reset() {
        synchronized(lock) {
            segments.clear()
            current = ByteArrayOutputStream(256 * 1024)
            haveKeyframe = false
            nextSeq = 0
            currentStartPts = 0
            lastPts = 0
        }
    }
}
