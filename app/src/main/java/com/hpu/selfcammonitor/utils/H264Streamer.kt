package com.hpu.selfcammonitor.utils

import android.util.Log
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * H.264 码流分发器：把编码器产出的 Annex-B 帧按「长度前缀」帧格式推给每个网页客户端。
 *
 * 帧格式（大端）：
 *   [4字节 payload 长度][1字节 标志(bit0=关键帧)][payload...]
 *
 * 机制与 MJPEGStreamer 相同：每个客户端一个推送线程 + 覆盖式 pending 槽位，
 * 慢客户端自动丢帧而非积压，避免延迟累积。
 *
 * 关键帧门控：新客户端在收到第一个关键帧前，丢弃所有 delta 帧（否则无法解码）；
 * 客户端接入时置位关键帧请求标志，由上层向编码器请求立即出 IDR。
 */
class H264Streamer {

    companion object {
        private const val TAG = "H264Streamer"
    }

    private data class ClientInfo(
        val outputStream: OutputStream,
        val pending: AtomicReference<ByteArray?> = AtomicReference(null),
        val lock: Any = Any(),
        @Volatile var synced: Boolean = false
    )

    private val clients = ConcurrentHashMap<OutputStream, ClientInfo>()
    private val removedClients = ConcurrentHashMap.newKeySet<ClientInfo>()
    private val pushExecutor: ExecutorService = Executors.newCachedThreadPool()

    private val sentBytes = AtomicLong(0)

    /** 新客户端接入时置位；上层消费后向编码器请求关键帧 */
    private val keyframeReq = AtomicBoolean(false)

    fun addClient(out: OutputStream) {
        val info = ClientInfo(out)
        removedClients.remove(info)
        clients[out] = info
        keyframeReq.set(true)
        pushExecutor.submit { clientPushLoop(info) }
        Log.d(TAG, "addClient, total=${clients.size}")
    }

    fun removeClient(out: OutputStream) {
        clients.remove(out)?.let { removedClients.add(it) }
        Log.d(TAG, "Client disconnected, total=${clients.size}")
        try { out.close() } catch (_: Exception) {}
    }

    fun getClientCount(): Int = clients.size

    fun getSentBytes(): Long = sentBytes.get()

    /** 消费一次「请求关键帧」标志（有则返回 true） */
    fun consumeKeyframeRequest(): Boolean = keyframeReq.getAndSet(false)

    /** 推送一帧 Annex-B 数据给所有客户端 */
    fun pushFrame(annexb: ByteArray, keyframe: Boolean) {
        if (clients.isEmpty()) return
        val header = ByteArray(5)
        val len = annexb.size
        header[0] = ((len ushr 24) and 0xFF).toByte()
        header[1] = ((len ushr 16) and 0xFF).toByte()
        header[2] = ((len ushr 8) and 0xFF).toByte()
        header[3] = (len and 0xFF).toByte()
        header[4] = if (keyframe) 1 else 0
        val frame = ByteArray(header.size + len)
        System.arraycopy(header, 0, frame, 0, header.size)
        System.arraycopy(annexb, 0, frame, header.size, len)

        clients.values.forEach { info ->
            if (!info.synced) {
                if (!keyframe) return@forEach  // 未同步前丢弃 delta 帧
                info.synced = true
            }
            info.pending.set(frame)
            synchronized(info.lock) { info.lock.notifyAll() }
        }
    }

    private fun clientPushLoop(info: ClientInfo) {
        try {
            while (!removedClients.contains(info)) {
                var bytes = info.pending.getAndSet(null)
                if (bytes == null) {
                    synchronized(info.lock) {
                        if (info.pending.get() == null) info.lock.wait(500)
                        bytes = info.pending.getAndSet(null)
                    }
                }
                if (bytes != null) {
                    try {
                        info.outputStream.write(bytes)
                        info.outputStream.flush()
                        sentBytes.addAndGet(bytes.size.toLong())
                    } catch (e: Exception) {
                        Log.w(TAG, "Client write failed, removing")
                        removeClient(info.outputStream)
                        return
                    }
                }
            }
        } catch (_: InterruptedException) {
        } finally {
            removedClients.remove(info)
            try { info.outputStream.close() } catch (_: Exception) {}
        }
    }
}
