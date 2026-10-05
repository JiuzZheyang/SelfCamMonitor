package com.hpu.selfcammonitor.service

import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 本地 DNS 中继（NetBIOS stub resolver 的替代）。
 *
 * 背景：cloudflared / frpc 都是**静态链接的 Go 程序**，只读 `/etc/resolv.conf` 来获取
 * 上游 DNS。而 Android 沙箱里**没有** `/etc/resolv.conf`，Go 会退回到内置默认
 * `127.0.0.1:53` / `[::1]:53`，于是 SRV 查询报 `connection refused`。
 *
 * 解决方案：在 App 进程内监听 127.0.0.1:53 与 [::1]:53（UDP + TCP），把所有收到的
 * DNS 报文原样转发到系统真实 DNS，再把应答回传。这样 Go 的默认解析器就能正常工作。
 *
 * 本类只做字节级转发，不解析 DNS 协议，因此 SRV/ANY/EDNS 等都能正确透传。
 */
class LocalDnsForwarder(
    private val upstreams: List<String>,
    private val logger: (String) -> Unit = { Log.i(TAG, it) },
) {

    companion object {
        private const val TAG = "LocalDnsForwarder"
        private const val PORT = 53
        private const val UDP_BUF = 4096
        private const val TIMEOUT_MS = 4000
    }

    private val running = AtomicBoolean(false)
    private var pool: ExecutorService? = null

    private val udpSockets = mutableListOf<DatagramSocket>()
    private val tcpSockets = mutableListOf<ServerSocket>()

    val isRunning: Boolean get() = running.get()

    /** 启动中继。返回是否至少成功绑定了一个回环 53 端口。 */
    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return true
        if (upstreams.isEmpty()) {
            logger("DNS 中继：没有可用的上游 DNS，跳过")
            running.set(false)
            return false
        }
        val exec = Executors.newCachedThreadPool()
        pool = exec

        var bound = false

        // UDP：IPv4 + IPv6 回环
        for (host in listOf("127.0.0.1", "::1")) {
            try {
                val s = DatagramSocket(null as SocketAddress?)
                s.reuseAddress = true
                s.bind(InetSocketAddress(InetAddress.getByName(host), PORT))
                udpSockets.add(s)
                exec.execute { udpLoop(s) }
                bound = true
                logger("DNS 中继：UDP $host:$PORT 已监听")
            } catch (e: Exception) {
                logger("DNS 中继：UDP $host:$PORT 绑定失败 - ${e.message}")
            }
        }

        // TCP：IPv4 + IPv6 回环（应对 DNS 截断后的 TCP 重试）
        for (host in listOf("127.0.0.1", "::1")) {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(InetAddress.getByName(host), PORT))
                tcpSockets.add(ss)
                exec.execute { tcpLoop(ss) }
                bound = true
                logger("DNS 中继：TCP $host:$PORT 已监听")
            } catch (e: Exception) {
                logger("DNS 中继：TCP $host:$PORT 绑定失败 - ${e.message}")
            }
        }

        if (!bound) {
            logger("DNS 中继：所有端口绑定失败，穿透的域名解析可能仍不可用")
            running.set(false)
            pool?.shutdownNow()
            pool = null
        } else {
            logger("DNS 中继已启动，上游：${upstreams.joinToString()}")
        }
        return bound
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        udpSockets.forEach { runCatching { it.close() } }
        tcpSockets.forEach { runCatching { it.close() } }
        udpSockets.clear()
        tcpSockets.clear()
        pool?.shutdownNow()
        pool = null
        logger("DNS 中继已停止")
    }

    // ─── UDP ──────────────────────────────────────────────────────

    private fun udpLoop(sock: DatagramSocket) {
        val buf = ByteArray(UDP_BUF)
        while (running.get()) {
            val pkt = DatagramPacket(buf, buf.size)
            try {
                sock.receive(pkt)
            } catch (e: Exception) {
                if (!running.get()) break
                continue
            }
            val query = pkt.data.copyOfRange(pkt.offset, pkt.offset + pkt.length)
            val client: SocketAddress = pkt.socketAddress
            runCatching { pool?.execute { relayUdp(sock, query, client) } }
        }
    }

    private fun relayUdp(clientSock: DatagramSocket, query: ByteArray, client: SocketAddress) {
        for (up in upstreams) {
            val (host, port) = splitHostPort(up) ?: continue
            try {
                DatagramSocket().use { us ->
                    us.soTimeout = TIMEOUT_MS
                    us.send(DatagramPacket(query, query.size, InetAddress.getByName(host), port))
                    val rbuf = ByteArray(UDP_BUF)
                    val resp = DatagramPacket(rbuf, rbuf.size)
                    us.receive(resp)
                    synchronized(clientSock) {
                        clientSock.send(DatagramPacket(rbuf, resp.length, client))
                    }
                    return
                }
            } catch (e: Exception) {
                // 换下一个上游
            }
        }
    }

    // ─── TCP ──────────────────────────────────────────────────────

    private fun tcpLoop(server: ServerSocket) {
        while (running.get()) {
            val client = try {
                server.accept()
            } catch (e: Exception) {
                if (!running.get()) break
                continue
            }
            runCatching { pool?.execute { relayTcp(client) } }
        }
    }

    private fun relayTcp(client: Socket) {
        client.use { c ->
            c.soTimeout = TIMEOUT_MS
            val cin = DataInputStream(BufferedInputStream(c.getInputStream()))
            val cout = DataOutputStream(BufferedOutputStream(c.getOutputStream()))

            // DNS over TCP：2 字节长度前缀
            val len = cin.readUnsignedShort()
            val query = ByteArray(len)
            cin.readFully(query)

            for (up in upstreams) {
                val (host, port) = splitHostPort(up) ?: continue
                try {
                    Socket().use { us ->
                        us.connect(InetSocketAddress(InetAddress.getByName(host), port), TIMEOUT_MS)
                        us.soTimeout = TIMEOUT_MS
                        val uout = DataOutputStream(BufferedOutputStream(us.getOutputStream()))
                        uout.writeShort(len)
                        uout.write(query)
                        uout.flush()

                        val uin = DataInputStream(BufferedInputStream(us.getInputStream()))
                        val rlen = uin.readUnsignedShort()
                        val resp = ByteArray(rlen)
                        uin.readFully(resp)

                        cout.writeShort(rlen)
                        cout.write(resp)
                        cout.flush()
                        return
                    }
                } catch (e: Exception) {
                    // 换下一个上游
                }
            }
        }
    }

    /** 解析 "1.2.3.4:53" 或 "[::1]:53" */
    private fun splitHostPort(value: String): Pair<String, Int>? {
        return try {
            if (value.startsWith("[")) {
                val end = value.indexOf(']')
                if (end < 0) return null
                val host = value.substring(1, end)
                val port = value.substring(end + 2).toInt()
                host to port
            } else {
                val idx = value.lastIndexOf(':')
                if (idx <= 0) return null
                value.substring(0, idx) to value.substring(idx + 1).toInt()
            }
        } catch (e: Exception) {
            null
        }
    }
}
