package com.hpu.selfcammonitor.service

import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.Socket
import java.net.URL
import java.util.Random

/**
 * 解析 Cloudflare Tunnel 边缘地址，供 cloudflared 的 `TUNNEL_EDGE` 环境变量使用。
 *
 * 背景：cloudflared 通过 SRV 查询 `_v2-origintunneld._tcp.argotunnel.com` 发现边缘节点，
 * 但它只读 `/etc/resolv.conf`；Android 沙箱没有该文件，于是回退到 `[::1]:53` 并报
 * `connection refused`。App 自身（走 Android 网络栈）把地址解析好后交给它就绕开了这个问题。
 *
 * 采用多重保险，任一成功即可：
 *   1) DoH（DNS over HTTPS，走系统 TLS，最不易被拦截/污染）
 *   2) 原生 UDP/TCP DNS（系统 DNS + 常见公共 DNS）
 *   3) 内置种子边缘 IP（前两者都失败时的最后兜底）
 */
object EdgeDiscovery {

    private const val TAG = "EdgeDiscovery"
    private const val SRV_NAME = "_v2-origintunneld._tcp.argotunnel.com"
    private const val EDGE_PORT = 7844
    private const val TIMEOUT_MS = 5000
    private const val TYPE_A = 1
    private const val TYPE_SRV = 33

    /** DoH 端点（按优先级：国内可达优先） */
    private val DOH_ENDPOINTS = listOf(
        "https://dns.alidns.com/resolve",
        "https://doh.pub/dns-query",
        "https://cloudflare-dns.com/dns-query",
        "https://1.1.1.1/dns-query",
    )

    /**
     * 内置兜底边缘地址（Cloudflare argotunnel anycast，端口 7844）。
     * 网络能解析时不会用到；仅在 DoH/原生 DNS 全失败时保证仍能连上。
     */
    private val SEED_EDGES = listOf(
        "198.41.192.167", "198.41.192.227", "198.41.192.107", "198.41.192.77",
        "198.41.192.47", "198.41.192.27", "198.41.192.7", "198.41.192.37",
        "198.41.200.13", "198.41.200.113", "198.41.200.53", "198.41.200.193",
        "198.41.200.23", "198.41.200.233", "198.41.200.43", "198.41.200.73",
    )

    /** 已知的边缘主机名（无需 SRV，直接用系统解析 A 记录） */
    private val REGION_HOSTS = listOf(
        "region1.v2.argotunnel.com",
        "region2.v2.argotunnel.com",
    )

    /**
     * 返回形如 ["198.41.192.77:7844", ...] 的边缘地址列表。**保证非空**（最终回退种子 IP）。
     */
    fun discover(systemDns: List<String>): List<String> {
        // 1) DoH
        try {
            val viaDoh = discoverViaDoh()
            if (viaDoh.isNotEmpty()) {
                Log.i(TAG, "DoH 发现边缘地址: ${viaDoh.joinToString(",")}")
                return viaDoh
            }
        } catch (e: Exception) {
            Log.w(TAG, "DoH 发现失败: ${e.message}")
        }

        // 2) 直接用系统解析器解析已知边缘主机（不依赖 SRV）
        try {
            val viaHost = discoverViaRegionHosts()
            if (viaHost.isNotEmpty()) {
                Log.i(TAG, "主机名解析发现边缘地址: ${viaHost.joinToString(",")}")
                return viaHost
            }
        } catch (e: Exception) {
            Log.w(TAG, "主机名解析失败: ${e.message}")
        }

        // 3) 原生 DNS（SRV）
        try {
            val servers = LinkedHashSet<String>()
            servers.addAll(systemDns)
            servers.addAll(listOf("119.29.29.29:53", "223.5.5.5:53", "180.76.76.76:53", "8.8.8.8:53", "1.1.1.1:53"))
            val viaDns = discoverViaDns(servers.toList())
            if (viaDns.isNotEmpty()) {
                Log.i(TAG, "DNS 发现边缘地址: ${viaDns.joinToString(",")}")
                return viaDns
            }
        } catch (e: Exception) {
            Log.w(TAG, "原生 DNS 发现失败: ${e.message}")
        }

        // 4) 种子兜底
        val seeds = SEED_EDGES.map { "$it:$EDGE_PORT" }
        Log.w(TAG, "使用内置边缘地址兜底: ${seeds.joinToString(",")}")
        return seeds
    }

    /** 直接用系统解析器解析 region1/region2 主机名（大多数 Android 网络下最可靠） */
    private fun discoverViaRegionHosts(): List<String> {
        val out = LinkedHashSet<String>()
        for (host in REGION_HOSTS) {
            try {
                val addrs = InetAddress.getAllByName(host)
                val v4 = addrs.filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress }
                val v6 = addrs.filter { it !is Inet4Address }.mapNotNull { it.hostAddress }
                val picked = if (v4.isNotEmpty()) v4 else v6
                for (ip in picked) out.add(formatAddr(ip, EDGE_PORT))
            } catch (e: Exception) {
                Log.w(TAG, "解析 $host 失败: ${e.message}")
            }
        }
        return out.toList()
    }

    // ─── DoH ─────────────────────────────────────────────────────

    private fun discoverViaDoh(): List<String> {
        for (endpoint in DOH_ENDPOINTS) {
            val srv = try {
                dohLookup(endpoint, SRV_NAME, "SRV")
            } catch (e: Exception) {
                Log.w(TAG, "DoH $endpoint 查询失败: ${e.message}")
                continue
            }
            if (srv.isEmpty()) continue
            val out = LinkedHashSet<String>()
            for ((target, port) in srv) {
                val ips = dohLookup(endpoint, target, "A").map { it.first }.filter { it.isNotEmpty() }
                val picked = ips.ifEmpty { tryResolveIps(target) }
                for (ip in picked) out.add(formatAddr(ip, port))
            }
            if (out.isNotEmpty()) return out.toList()
        }
        return emptyList()
    }

    /**
     * DoH JSON 查询，返回 (data, type) 列表。
     * 兼容阿里 DoH（Answer 中 type 为数字）、Cloudflare/Google（type 数字）。
     */
    private fun dohLookup(endpoint: String, name: String, type: String): List<Pair<String, Int>> {
        val url = URL("$endpoint?name=${enc(name)}&type=$type")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Accept", "application/dns-json")
        }
        try {
            if (conn.responseCode !in 200..299) return emptyList()
            val body = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            val json = JSONObject(body)
            val answers = json.optJSONArray("Answer") ?: return emptyList()
            val out = mutableListOf<Pair<String, Int>>()
            for (i in 0 until answers.length()) {
                val a = answers.optJSONObject(i) ?: continue
                val aType = a.optInt("type", -1)
                val data = a.optString("data", "")
                when (aType) {
                    TYPE_SRV -> {
                        // "1 1 7844 region1.v2.argotunnel.com."
                        val parts = data.trim().split(Regex("\\s+"))
                        if (parts.size >= 4) {
                            val port = parts[2].toIntOrNull() ?: continue
                            val target = parts[3].trimEnd('.')
                            out.add(target to port)
                        }
                    }
                    TYPE_A -> if (data.isNotEmpty()) out.add(data to EDGE_PORT)
                }
            }
            return out
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    private fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    // ─── 原生 DNS ────────────────────────────────────────────────

    private fun discoverViaDns(dnsServers: List<String>): List<String> {
        var srv: List<Pair<String, Int>> = emptyList()
        for (server in dnsServers) {
            srv = try {
                querySrv(server)
            } catch (e: Exception) {
                Log.w(TAG, "SRV 查询失败 $server: ${e.message}")
                emptyList()
            }
            if (srv.isNotEmpty()) break
        }
        if (srv.isEmpty()) return emptyList()

        val out = LinkedHashSet<String>()
        for ((target, port) in srv) {
            val ips = tryResolveIps(target)
            for (ip in ips) out.add(formatAddr(ip, port))
        }
        return out.toList()
    }

    private fun tryResolveIps(target: String): List<String> {
        // 先直接尝试系统解析该主机名（可能已是 IP 字面量）
        try {
            val addrs = InetAddress.getAllByName(target)
            val v4 = addrs.filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress }
            val v6 = addrs.filter { it !is Inet4Address }.mapNotNull { it.hostAddress }
            val picked = if (v4.isNotEmpty()) v4 else v6
            if (picked.isNotEmpty()) return picked
        } catch (e: Exception) {
            Log.w(TAG, "解析边缘主机失败 $target: ${e.message}")
        }
        return emptyList()
    }

    private fun querySrv(server: String): List<Pair<String, Int>> {
        val hp = splitHostPort(server) ?: return emptyList()
        return try {
            udpQuery(hp.first, hp.second)
        } catch (e: Exception) {
            // UDP 可能被截断/被拦，退回 TCP
            tcpQuery(hp.first, hp.second)
        }
    }

    private fun udpQuery(host: String, port: Int): List<Pair<String, Int>> {
        val id = Random().nextInt(0xFFFF)
        val query = buildSrvQuery(id)
        DatagramSocket().use { sock ->
            sock.soTimeout = TIMEOUT_MS
            sock.send(DatagramPacket(query, query.size, InetAddress.getByName(host), port))
            val buf = ByteArray(4096)
            val resp = DatagramPacket(buf, buf.size)
            sock.receive(resp)
            // TC 位置位表示被截断，需要走 TCP
            if (resp.length >= 4 && (buf[2].toInt() and 0x02) != 0) {
                return tcpQuery(host, port)
            }
            return parseSrv(buf, resp.length, id)
        }
    }

    private fun tcpQuery(host: String, port: Int): List<Pair<String, Int>> {
        val id = Random().nextInt(0xFFFF)
        val query = buildSrvQuery(id)
        Socket().use { s ->
            s.connect(java.net.InetSocketAddress(InetAddress.getByName(host), port), TIMEOUT_MS)
            s.soTimeout = TIMEOUT_MS
            val out = s.getOutputStream()
            out.write((query.size shr 8) and 0xFF)
            out.write(query.size and 0xFF)
            out.write(query)
            out.flush()
            val ins = s.getInputStream()
            val lenHi = ins.read(); val lenLo = ins.read()
            if (lenHi < 0 || lenLo < 0) return emptyList()
            val n = (lenHi shl 8) or lenLo
            val buf = ByteArray(n)
            var read = 0
            while (read < n) {
                val r = ins.read(buf, read, n - read)
                if (r < 0) break
                read += r
            }
            return parseSrv(buf, read, id)
        }
    }

    private fun buildSrvQuery(id: Int): ByteArray {
        val bos = ByteArrayOutputStream()
        bos.write((id shr 8) and 0xFF); bos.write(id and 0xFF)
        bos.write(0x01); bos.write(0x00)              // flags: RD
        bos.write(0x00); bos.write(0x01)              // QDCOUNT = 1
        repeat(6) { bos.write(0x00) }                 // AN/NS/AR = 0
        for (label in SRV_NAME.split('.')) {
            val b = label.toByteArray(Charsets.US_ASCII)
            bos.write(b.size); bos.write(b, 0, b.size)
        }
        bos.write(0x00)
        bos.write(0x00); bos.write(TYPE_SRV)
        bos.write(0x00); bos.write(0x01)
        return bos.toByteArray()
    }

    private fun parseSrv(data: ByteArray, len: Int, expectedId: Int): List<Pair<String, Int>> {
        if (len < 12) return emptyList()
        if (u16(data, 0) != expectedId) return emptyList()
        val qd = u16(data, 4)
        val an = u16(data, 6)

        var off = 12
        repeat(qd) { off = skipName(data, off) + 4 }

        val out = mutableListOf<Pair<String, Int>>()
        repeat(an) {
            if (off + 10 > len) return out
            var p = readName(data, off).second
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

    /** 读取（可能含压缩指针的）域名，返回 [名字, 下一个字段偏移] */
    private fun readName(data: ByteArray, start: Int): Pair<String, Int> {
        val sb = StringBuilder()
        var off = start
        var next = -1
        var guard = 0
        while (guard++ < 128) {
            if (off >= data.size) break
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
            if (off >= data.size) return off
            val l = data[off].toInt() and 0xFF
            if (l == 0) return off + 1
            if (l and 0xC0 == 0xC0) return off + 2
            off += 1 + l
        }
        return off
    }

    private fun u16(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

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
