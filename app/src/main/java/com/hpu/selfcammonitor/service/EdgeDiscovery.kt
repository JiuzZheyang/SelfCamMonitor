package com.hpu.selfcammonitor.service

import android.util.Log
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.util.Random

/**
 * 解析 Cloudflare Tunnel 边缘地址，供 cloudflared 的 `TUNNEL_EDGE` 环境变量使用。
 *
 * 背景：cloudflared 通过 SRV 查询 `_v2-origintunneld._tcp.argotunnel.com` 发现边缘节点。
 * 但它是静态链接的 Go 程序，只读 `/etc/resolv.conf`；Android 沙箱没有该文件，于是回退到
 * `[::1]:53` 并报 `connection refused`。
 *
 * 这里用 App 自身的网络栈（走 Android 系统解析器）完成同样的 SRV + A 记录解析，
 * 把结果以 `ip:port` 列表交给 cloudflared，从根本上绕开它自己的域名解析。
 * 实测：给出显式边缘地址后 cloudflared 可直接连上边缘（QUIC/TCP 预检通过）。
 */
object EdgeDiscovery {

    private const val TAG = "EdgeDiscovery"
    private const val SRV_NAME = "_v2-origintunneld._tcp.argotunnel.com"
    private const val TIMEOUT_MS = 4000
    private const val TYPE_SRV = 33

    /**
     * 依次尝试每个 DNS 服务器，返回形如 ["198.41.192.77:7844", ...] 的边缘地址列表。
     * 失败返回空列表（调用方将退化为依赖本地 DNS 中继）。
     */
    fun discover(dnsServers: List<String>): List<String> {
        for (server in dnsServers) {
            val hp = splitHostPort(server) ?: continue
            val srv = try {
                querySrv(hp.first, hp.second)
            } catch (e: Exception) {
                Log.w(TAG, "SRV 查询失败 $server: ${e.message}")
                null
            }
            if (srv.isNullOrEmpty()) continue

            val out = LinkedHashSet<String>()
            for ((target, port) in srv) {
                val ips = resolveToIps(target) ?: continue
                for (ip in ips) out.add(formatAddr(ip, port))
            }
            if (out.isNotEmpty()) {
                Log.i(TAG, "发现边缘地址: ${out.joinToString(",")}")
                return out.toList()
            }
        }
        Log.w(TAG, "未能发现边缘地址")
        return emptyList()
    }

    // ─── DNS 查询 ────────────────────────────────────────────────

    private fun querySrv(host: String, port: Int): List<Pair<String, Int>> {
        val id = Random().nextInt(0xFFFF)
        val query = buildQuery(id, SRV_NAME)
        DatagramSocket().use { sock ->
            sock.soTimeout = TIMEOUT_MS
            sock.send(DatagramPacket(query, query.size, InetAddress.getByName(host), port))
            val buf = ByteArray(4096)
            val resp = DatagramPacket(buf, buf.size)
            sock.receive(resp)
            return parseSrv(buf, resp.length, id)
        }
    }

    private fun buildQuery(id: Int, name: String): ByteArray {
        val bos = ByteArrayOutputStream()
        bos.write((id shr 8) and 0xFF); bos.write(id and 0xFF)
        bos.write(0x01); bos.write(0x00)              // flags: RD
        bos.write(0x00); bos.write(0x01)              // QDCOUNT = 1
        repeat(6) { bos.write(0x00) }                 // AN/NS/AR = 0
        for (label in name.split('.')) {
            val b = label.toByteArray(Charsets.US_ASCII)
            bos.write(b.size); bos.write(b, 0, b.size)
        }
        bos.write(0x00)                               // 名字结束
        bos.write(0x00); bos.write(TYPE_SRV)          // QTYPE
        bos.write(0x00); bos.write(0x01)              // QCLASS = IN
        return bos.toByteArray()
    }

    private fun parseSrv(data: ByteArray, len: Int, expectedId: Int): List<Pair<String, Int>> {
        if (len < 12) return emptyList()
        val id = u16(data, 0)
        if (id != expectedId) return emptyList()
        val qd = u16(data, 4)
        val an = u16(data, 6)

        var off = 12
        repeat(qd) { off = skipName(data, off) + 4 }  // 跳过问题区

        val out = mutableListOf<Pair<String, Int>>()
        repeat(an) {
            if (off + 10 > len) return out
            val nameRes = readName(data, off)
            var p = nameRes.second
            if (p + 10 > len) return out
            val type = u16(data, p)
            val rdlen = u16(data, p + 8)
            val rdata = p + 10
            if (type == TYPE_SRV && rdlen >= 6 && rdata + 6 <= len) {
                val port = u16(data, rdata + 4)
                val target = readName(data, rdata + 6).first
                if (target.isNotEmpty()) out.add(target to port)
            }
            off = rdata + rdlen
        }
        return out
    }

    /** 读取（可能是压缩指针的）域名，返回 [名字, 下一个字段偏移] */
    private fun readName(data: ByteArray, start: Int): Pair<String, Int> {
        val sb = StringBuilder()
        var off = start
        var next = -1
        var guard = 0
        while (guard++ < 128) {
            val l = data[off].toInt() and 0xFF
            if (l == 0) { off += 1; break }
            if (l and 0xC0 == 0xC0) {
                if (off + 1 >= data.size) break
                val ptr = ((l and 0x3F) shl 8) or (data[off + 1].toInt() and 0xFF)
                if (next < 0) next = off + 2
                off = ptr
                continue
            }
            if (off + 1 + l > data.size) break
            if (sb.isNotEmpty()) sb.append('.')
            sb.append(String(data, off + 1, l, Charsets.US_ASCII))
            off += 1 + l
        }
        return sb.toString() to (if (next >= 0) next else off)
    }

    private fun skipName(data: ByteArray, start: Int): Int {
        var off = start
        var guard = 0
        while (guard++ < 128) {
            val l = data[off].toInt() and 0xFF
            if (l == 0) return off + 1
            if (l and 0xC0 == 0xC0) return off + 2
            off += 1 + l
        }
        return off
    }

    private fun u16(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

    // ─── 辅助 ───────────────────────────────────────────────────

    /** 目标可能是主机名或已是 IP 字面量；优先返回 IPv4，无 IPv4 时才用 IPv6 */
    private fun resolveToIps(target: String): List<String>? {
        if (target.isEmpty()) return null
        return try {
            val addrs = InetAddress.getAllByName(target)
            val v4 = addrs.filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress }
            val v6 = addrs.filter { it !is Inet4Address }.mapNotNull { it.hostAddress }
            (if (v4.isNotEmpty()) v4 else v6).ifEmpty { null }
        } catch (e: Exception) {
            Log.w(TAG, "解析边缘主机失败 $target: ${e.message}")
            null
        }
    }

    private fun formatAddr(ip: String, port: Int): String =
        if (ip.contains(':')) "[$ip]:$port" else "$ip:$port"

    private fun splitHostPort(value: String): Pair<String, Int>? = try {
        if (value.startsWith("[")) {
            val end = value.indexOf(']')
            if (end < 0) null else value.substring(1, end) to value.substring(end + 2).toInt()
        } else {
            val idx = value.lastIndexOf(':')
            if (idx <= 0) null else value.substring(0, idx) to value.substring(idx + 1).toInt()
        }
    } catch (e: Exception) {
        null
    }
}
