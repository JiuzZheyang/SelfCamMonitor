package com.hpu.selfcammonitor.service

import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.FileInputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.URLDecoder
import android.util.Base64
import com.hpu.selfcammonitor.utils.HlsManager
import com.hpu.selfcammonitor.utils.MJPEGStreamer

class StreamServer(port: Int = 8080) : NanoHTTPD(port) {

    private lateinit var mjpegStreamer: MJPEGStreamer
    private lateinit var hlsManager: HlsManager

    var isMjpegEnabled: Boolean = true

    var username: String? = null
    var password: String? = null

    private var hlsJs: ByteArray? = null
    private var hlsJsGz: ByteArray? = null

    /** 网页端控制入口（由 CameraService 注入） */
    @Volatile
    private var control: StreamControl? = null

    fun setMJPEGStreamer(streamer: MJPEGStreamer) {
        this.mjpegStreamer = streamer
    }

    fun setHlsManager(m: HlsManager) {
        this.hlsManager = m
    }

    fun setHlsJs(bytes: ByteArray) {
        this.hlsJs = bytes
        this.hlsJsGz = null
    }

    fun setControl(c: StreamControl) {
        this.control = c
    }

    override fun serve(session: IHTTPSession?): Response {
        val s = session
            ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "bad request")

        // 跨隧道访问：预检请求不带凭证，需在鉴权前放行
        if (s.method == Method.OPTIONS) {
            val pre = newFixedLengthResponse(Response.Status.NO_CONTENT, "text/plain", "")
            addCorsHeaders(pre)
            return pre
        }

        // 认证检查
        if (username != null && password != null) {
            val auth = s.headers["authorization"]
            if (auth == null || !auth.startsWith("Basic ")) {
                val res = newFixedLengthResponse(
                    Response.Status.UNAUTHORIZED, "text/plain", "需要认证"
                )
                res.addHeader("WWW-Authenticate", "Basic realm=\"Camera\"")
                return res
            }
            val cred = String(
                Base64.decode(auth.substring(6), Base64.DEFAULT)
            ).split(":", limit = 2)
            if (cred.size != 2 || cred[0] != username || cred[1] != password) {
                val res = newFixedLengthResponse(
                    Response.Status.UNAUTHORIZED, "text/plain", "认证失败"
                )
                res.addHeader("WWW-Authenticate", "Basic realm=\"Camera\"")
                return res
            }
        }

        val uri = s.uri

        val res = when {
            uri == "/" || uri == "/index.html" -> htmlPage()
            uri == "/gallery" || uri == "/gallery.html" -> galleryPage()
            uri == "/info" || uri == "/info.html" -> infoPage()
            uri == "/qr" -> serveQr(s)
            uri == "/live.m3u8" -> servePlaylist()
            uri.startsWith("/seg/") -> serveSegment(uri)
            uri == "/hls.js" -> serveHlsJs()
            uri == "/video" -> serveVideo()
            uri == "/status" -> serveLegacyStatus()
            uri == "/snapshot" -> serveSnapshot(download = false)
            uri == "/api" -> serveApiDoc()
            uri == "/api/state" -> serveState()
            uri == "/api/config" -> serveConfig(s)
            uri == "/api/record" -> serveRecord(s)
            uri == "/api/recordings" -> serveRecordings()
            uri == "/api/star" -> serveStar(s)
            uri == "/api/delete" -> serveDelete(s)
            uri == "/api/camera" -> serveCamera(s)
            uri == "/api/snapshot" -> serveSnapshot(download = true)
            uri.startsWith("/thumb/") -> serveThumb(uri)
            uri.startsWith("/dl/") -> serveRecordingFile(s, uri, download = true)
            uri.startsWith("/play/") -> serveRecordingFile(s, uri, download = false)
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "404 Not Found")
        }
        addCorsHeaders(res)
        return res
    }

    /** 允许跨源读取（多个隧道域名指向同一后端，浏览器可任选链路下载） */
    private fun addCorsHeaders(res: Response) {
        res.addHeader("Access-Control-Allow-Origin", "*")
        res.addHeader("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS")
        res.addHeader("Access-Control-Allow-Headers", "Range, Content-Type, Authorization")
        res.addHeader("Access-Control-Expose-Headers", "Content-Range, Content-Length, Accept-Ranges, Content-Disposition")
    }

    // ─── 端点实现 ──────────────────────────────────────────────

    private fun htmlPage(): Response {
        val res = newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", VIEWER_HTML)
        res.addHeader("Cache-Control", "no-store")
        return res
    }

    private fun galleryPage(): Response {
        val res = newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", GALLERY_HTML)
        res.addHeader("Cache-Control", "no-store")
        return res
    }

    private fun infoPage(): Response {
        val res = newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", INFO_HTML)
        res.addHeader("Cache-Control", "no-store")
        return res
    }

    /** HLS 播放列表（滑动窗口，DVR） */
    private fun servePlaylist(): Response {
        hlsManager.noteClient()
        val pl = hlsManager.playlist()
        val res = newFixedLengthResponse(Response.Status.OK, "application/vnd.apple.mpegurl", pl)
        res.addHeader("Cache-Control", "no-store")
        return res
    }

    /** HLS TS 分片，如 /seg/12.ts */
    private fun serveSegment(uri: String): Response {
        hlsManager.noteClient()
        val name = uri.removePrefix("/seg/").substringBefore('?')
        val seq = name.substringBefore('.').toLongOrNull()
            ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "bad seq")
        val bytes = hlsManager.segment(seq)
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "gone")
        hlsManager.addServedBytes(bytes.size.toLong())
        val res = newFixedLengthResponse(
            Response.Status.OK, "video/mp2t", ByteArrayInputStream(bytes), bytes.size.toLong()
        )
        res.addHeader("Cache-Control", "public, max-age=60")
        return res
    }

    private fun serveHlsJs(): Response {
        val bytes = hlsJs
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "no hls.js")
        val gz = hlsJsGz ?: gzip(bytes).also { hlsJsGz = it }
        val res = newFixedLengthResponse(
            Response.Status.OK, "application/javascript; charset=utf-8",
            ByteArrayInputStream(gz), gz.size.toLong()
        )
        res.addHeader("Content-Encoding", "gzip")
        res.addHeader("Vary", "Accept-Encoding")
        res.addHeader("Cache-Control", "public, max-age=86400")
        return res
    }

    /** 一次性 gzip 压缩（hls.js 静态不变，压缩后缓存） */
    private fun gzip(data: ByteArray): ByteArray {
        val bos = java.io.ByteArrayOutputStream(data.size / 3)
        java.util.zip.GZIPOutputStream(bos).use { it.write(data) }
        return bos.toByteArray()
    }

    /** 生成二维码 PNG：默认取当前最优访问地址，可用 ?u= 指定、?size= 调整边长 */
    private fun serveQr(session: IHTTPSession): Response {
        val params = readParams(session)
        var target = params["u"]?.trim().orEmpty()
        if (target.isBlank()) {
            val st = try { control?.state() ?: emptyMap<String, Any?>() } catch (_: Exception) { emptyMap<String, Any?>() }
            target = (st["cfUrl"] as? String)?.takeIf { it.isNotBlank() }
                ?: (st["frpUrl"] as? String)?.takeIf { it.isNotBlank() }
                ?: ("http://" + (st["lanIp"] as? String ?: "127.0.0.1") + ":8080")
        }
        val size = (params["size"]?.toIntOrNull() ?: 512).coerceIn(128, 1024)
        val png = com.hpu.selfcammonitor.utils.QrUtil.png(target, size)
            ?: return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "qr error")
        val res = newFixedLengthResponse(
            Response.Status.OK, "image/png", ByteArrayInputStream(png), png.size.toLong()
        )
        res.addHeader("Cache-Control", "public, max-age=60")
        return res
    }

    private fun serveVideo(): Response {
        if (!isMjpegEnabled) {
            return newFixedLengthResponse(
                Response.Status.SERVICE_UNAVAILABLE, "text/plain", "MJPEG 推流已关闭，请在网页或 App 中开启。"
            )
        }
        val pipedOut = PipedOutputStream()
        val pipedIn = PipedInputStream(pipedOut, 1 shl 20)
        mjpegStreamer.addClient(pipedOut)
        val res = newChunkedResponse(
            Response.Status.OK,
            "multipart/x-mixed-replace; boundary=${MJPEGStreamer.Companion.BOUNDARY}",
            pipedIn
        )
        res.addHeader("Cache-Control", "no-store, no-cache, must-revalidate")
        res.addHeader("Pragma", "no-cache")
        res.addHeader("X-Accel-Buffering", "no")
        return res
    }

    private fun serveLegacyStatus(): Response {
        val json = "{\"mjpegEnabled\":$isMjpegEnabled," +
                "\"clientCount\":${mjpegStreamer.getClientCount()}," +
                "\"lastFrameAge\":${mjpegStreamer.getLastFrameAge()}}"
        return jsonResponse(json)
    }

    private fun serveState(): Response {
        val ctrl = control
            ?: return jsonResponse("{\"error\":\"服务未就绪\"}")
        return try {
            jsonResponse(toJson(ctrl.state()))
        } catch (e: Exception) {
            jsonResponse("{\"error\":\"${jsonEscape(e.message ?: "state error")}\"}")
        }
    }

    private fun serveConfig(session: IHTTPSession): Response {
        val ctrl = control
            ?: return jsonResponse("{\"error\":\"服务未就绪\"}")
        return try {
            val params = readParams(session)
            if (params.isEmpty()) jsonResponse(toJson(ctrl.state()))
            else jsonResponse(toJson(ctrl.applyConfig(params)))
        } catch (e: Exception) {
            jsonResponse("{\"error\":\"${jsonEscape(e.message ?: "config error")}\"}")
        }
    }

    private fun serveRecord(session: IHTTPSession): Response {
        val ctrl = control
            ?: return jsonResponse("{\"error\":\"服务未就绪\"}")
        return try {
            val params = readParams(session)
            val ok = when (params["action"]) {
                "start" -> {
                    val dur = params["duration"]?.toIntOrNull()
                        ?: params["seconds"]?.toIntOrNull()
                        ?: params["t"]?.toIntOrNull() ?: 0
                    ctrl.startManualRecording(dur.coerceIn(0, 24 * 3600))
                }
                "stop" -> ctrl.stopManualRecording()
                else -> false
            }
            val result = LinkedHashMap<String, Any?>(ctrl.state())
            result["ok"] = ok
            result["action"] = params["action"] ?: ""
            jsonResponse(toJson(result))
        } catch (e: Exception) {
            jsonResponse("{\"error\":\"${jsonEscape(e.message ?: "record error")}\"}")
        }
    }

    /** 录像列表（相册数据源） */
    private fun serveRecordings(): Response {
        val ctrl = control
            ?: return jsonResponse("{\"error\":\"服务未就绪\"}")
        return try {
            val list = ctrl.listRecordings()
            val sb = StringBuilder("{\"dir\":\"Recordings\",\"files\":[")
            var first = true
            for (item in list) {
                if (!first) sb.append(',')
                first = false
                sb.append(toJson(item))
            }
            sb.append("]}")
            jsonResponse(sb.toString())
        } catch (e: Exception) {
            jsonResponse("{\"error\":\"${jsonEscape(e.message ?: "list error")}\"}")
        }
    }

    /** 设置/取消「精选」（精选不会被自动清理） */
    private fun serveStar(session: IHTTPSession): Response {
        val ctrl = control
            ?: return jsonResponse("{\"error\":\"\u670d\u52a1\u672a\u5c31\u7eea\"}")
        return try {
            val p = readParams(session)
            val rel = p["path"] ?: p["file"] ?: p["rel"] ?: ""
            val raw = p["on"]
            val on = !(raw == "0" || raw.equals("false", true) || raw.equals("no", true))
            val ok = rel.isNotBlank() && ctrl.setStarred(rel, on)
            jsonResponse("{\"ok\":$ok,\"path\":\"${jsonEscape(rel)}\",\"starred\":$on}")
        } catch (e: Exception) {
            jsonResponse("{\"error\":\"${jsonEscape(e.message ?: "star error")}\"}")
        }
    }

    /** 删除录像（force=1 时可删除精选） */
    private fun serveDelete(session: IHTTPSession): Response {
        val ctrl = control
            ?: return jsonResponse("{\"error\":\"\u670d\u52a1\u672a\u5c31\u7eea\"}")
        return try {
            val p = readParams(session)
            val rel = p["path"] ?: p["file"] ?: p["rel"] ?: ""
            if (rel.isBlank()) return jsonResponse("{\"ok\":false,\"error\":\"\u7f3a\u5c11 path\"}")
            val force = p["force"] == "1" || p["force"].equals("true", true)
            val ok = ctrl.deleteRecording(rel, force)
            jsonResponse("{\"ok\":$ok,\"path\":\"${jsonEscape(rel)}\"}")
        } catch (e: Exception) {
            jsonResponse("{\"error\":\"${jsonEscape(e.message ?: "delete error")}\"}")
        }
    }

    /** 手动唤醒摄像头（按需省电模式下用） */
    private fun serveCamera(session: IHTTPSession): Response {
        val ctrl = control
            ?: return jsonResponse("{\"error\":\"\u670d\u52a1\u672a\u5c31\u7eea\"}")
        return try {
            val p = readParams(session)
            val ttl = p["ttl"]?.toIntOrNull() ?: 60
            val on = !(p["on"] == "0" || p["on"].equals("false", true))
            val ok = if (on) ctrl.wakeCamera(ttl) else false
            val active = ctrl.state()["cameraActive"] ?: false
            jsonResponse("{\"ok\":$ok,\"cameraActive\":$active,\"ttl\":$ttl}")
        } catch (e: Exception) {
            jsonResponse("{\"error\":\"${jsonEscape(e.message ?: "camera error")}\"}")
        }
    }

    /** 录像缩略图 */
    private fun serveThumb(uri: String): Response {
        val ctrl = control
            ?: return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "text/plain", "\u670d\u52a1\u672a\u5c31\u7eea")
        val rel = urlDecode(uri.removePrefix("/thumb/").substringBefore('?'))
        val bytes = ctrl.recordingThumbnail(rel)
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "no thumb")
        val res = newFixedLengthResponse(
            Response.Status.OK, "image/jpeg", ByteArrayInputStream(bytes), bytes.size.toLong()
        )
        res.addHeader("Cache-Control", "public, max-age=600")
        return res
    }

    /**
     * 录像文件：支持 HTTP Range（断点续传 / 分片下载 / 浏览器拖动播放）。
     * /play/<rel> 内联播放；/dl/<rel> 附件下载。
     */
    private fun serveRecordingFile(session: IHTTPSession, uri: String, download: Boolean): Response {
        val ctrl = control
            ?: return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "text/plain", "\u670d\u52a1\u672a\u5c31\u7eea")
        val prefix = if (download) "/dl/" else "/play/"
        val rel = urlDecode(uri.removePrefix(prefix).substringBefore('?'))
        val file = ctrl.recordingFile(rel)
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "404 not found")
        val total = file.length()
        val mime = "video/mp4"

        var start = 0L
        var end = total - 1
        var partial = false
        val rangeHeader = session.headers["range"]
        if (rangeHeader != null) {
            val r = parseRange(rangeHeader, total)
            if (r != null) {
                start = r.first; end = r.second; partial = true
            } else if (rangeHeader.trim().startsWith("bytes=")) {
                val bad = newFixedLengthResponse(
                    Response.Status.RANGE_NOT_SATISFIABLE, "text/plain", "range not satisfiable"
                )
                bad.addHeader("Content-Range", "bytes */$total")
                return bad
            }
        }
        val len = (end - start + 1).coerceAtLeast(0)
        val fis = FileInputStream(file)
        if (start > 0) skipFully(fis, start)
        val status = if (partial) Response.Status.PARTIAL_CONTENT else Response.Status.OK
        val res = newFixedLengthResponse(status, mime, fis, len)
        res.addHeader("Accept-Ranges", "bytes")
        if (partial) res.addHeader("Content-Range", "bytes $start-$end/$total")
        res.addHeader("Content-Disposition",
            if (download) "attachment; filename=\"${file.name}\"" else "inline")
        res.addHeader("Cache-Control", "no-store")
        return res
    }

    /** 解析 Range 请求头（仅处理单区间），返回 (start,end)；不可满足返回 null */
    private fun parseRange(header: String, total: Long): Pair<Long, Long>? {
        val h = header.trim()
        if (!h.startsWith("bytes=")) return null
        var spec = h.substring(6).trim()
        if (spec.contains(',')) spec = spec.substringBefore(',').trim()
        val dash = spec.indexOf('-')
        if (dash < 0) return null
        val sStr = spec.substring(0, dash).trim()
        val eStr = spec.substring(dash + 1).trim()
        var start: Long
        var end: Long
        try {
            if (sStr.isEmpty()) {
                val n = eStr.toLong()
                if (n <= 0 || total <= 0) return null
                start = (total - n).coerceAtLeast(0)
                end = total - 1
            } else {
                start = sStr.toLong()
                end = if (eStr.isEmpty()) total - 1 else eStr.toLong()
            }
        } catch (e: Exception) {
            return null
        }
        if (total <= 0 || start > end || start >= total) return null
        if (end >= total) end = total - 1
        return Pair(start, end)
    }

    private fun skipFully(fis: FileInputStream, n: Long) {
        var remaining = n
        while (remaining > 0) {
            val skipped = fis.skip(remaining)
            if (skipped <= 0) {
                if (fis.read() < 0) break
                remaining -= 1
            } else {
                remaining -= skipped
            }
        }
    }

    /** API 说明（便于其他设备对接） */
    private fun serveApiDoc(): Response {
        val doc = "{" +
            "\"endpoints\":[" +
            "{\"path\":\"/api/record?action=start&duration=N\",\"desc\":\"开始录制，N 秒后自动停止（duration=0 表示不限时）\"}," +
            "{\"path\":\"/api/record?action=stop\",\"desc\":\"停止录制\"}," +
            "{\"path\":\"/api/recordings\",\"desc\":\"录像列表 JSON\"}," +
            "{\"path\":\"/api/star?path=<rel>&on=1\",\"desc\":\"设置/取消精选（精选不会被自动清理）\"}," +
            "{\"path\":\"/api/delete?path=<rel>[&force=1]\",\"desc\":\"删除录像；精选需 force=1\"}," +
            "{\"path\":\"/api/camera?on=1&ttl=60\",\"desc\":\"唤醒摄像头（按需省电模式）\"}," +
            "{\"path\":\"/dl/<date>/<file>.mp4\",\"desc\":\"下载（支持 Range 断点/分片）\"}," +
            "{\"path\":\"/play/<date>/<file>.mp4\",\"desc\":\"网页内联播放（支持 Range）\"}," +
            "{\"path\":\"/thumb/<date>/<file>.mp4\",\"desc\":\"缩略图 jpg\"}," +
            "{\"path\":\"/gallery\",\"desc\":\"录像相册网页\"}," +
            "{\"path\":\"/info\",\"desc\":\"设备信息网页（电量/温度/内存/CPU）\"}," +
            "{\"path\":\"/qr?u=&size=\",\"desc\":\"二维码 PNG（默认取最优访问地址）\"}," +
            "{\"path\":\"/api/state\",\"desc\":\"设备状态\"}," +
            "{\"path\":\"/api/config?resolution=&fps=&facing=&mode=&mjpeg=\",\"desc\":\"应用配置\"}," +
            "{\"path\":\"/api/snapshot\",\"desc\":\"下载当前截图\"}" +
            "]}"
        return jsonResponse(doc)
    }

    private fun serveSnapshot(download: Boolean): Response {
        val jpeg = mjpegStreamer.getLatestJpegFresh(1200)
            ?: return newFixedLengthResponse(
                Response.Status.SERVICE_UNAVAILABLE, "text/plain", "暂无画面"
            )
        val res = newFixedLengthResponse(
            Response.Status.OK, "image/jpeg", ByteArrayInputStream(jpeg), jpeg.size.toLong()
        )
        if (download) {
            res.addHeader("Content-Disposition", "attachment; filename=\"snapshot_${System.currentTimeMillis()}.jpg\"")
        }
        res.addHeader("Cache-Control", "no-store")
        return res
    }

    // ─── 工具 ─────────────────────────────────────────────────

    private fun readParams(session: IHTTPSession): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        fun feed(query: String?) {
            if (query.isNullOrBlank()) return
            for (pair in query.split("&")) {
                if (pair.isEmpty()) continue
                val i = pair.indexOf('=')
                val k = if (i < 0) pair else pair.substring(0, i)
                val v = if (i < 0) "" else pair.substring(i + 1)
                out[urlDecode(k)] = urlDecode(v)
            }
        }
        feed(session.queryParameterString)
        if (session.method == Method.POST) {
            try {
                val body = HashMap<String, String>()
                session.parseBody(body)
                feed(body["postData"])
            } catch (_: Exception) {
            }
        }
        return out
    }

    private fun urlDecode(v: String): String = try {
        URLDecoder.decode(v, "UTF-8")
    } catch (_: Exception) {
        v
    }

    private fun jsonResponse(body: String): Response {
        val res = newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", body)
        res.addHeader("Cache-Control", "no-store")
        return res
    }

    private fun jsonEscape(s: String): String = buildString {
        for (c in s) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
    }

    /** 极简 JSON 序列化：支持 Map<String,Any?>，值类型为 String/Number/Boolean/List/Map */
    private fun toJson(map: Map<String, Any?>): String = buildString {
        append("{")
        var first = true
        for ((k, v) in map) {
            if (!first) append(",")
            first = false
            append("\"").append(jsonEscape(k)).append("\":")
            append(jsonValue(v))
        }
        append("}")
    }

    private fun jsonValue(v: Any?): String = when (v) {
        null -> "null"
        is Number, is Boolean -> v.toString()
        is Map<*, *> -> {
            val m = LinkedHashMap<String, Any?>()
            v.forEach { (k, vv) -> m[k.toString()] = vv }
            toJson(m)
        }
        is List<*> -> "[" + v.joinToString(",") { jsonValue(it) } + "]"
        else -> "\"" + jsonEscape(v.toString()) + "\""
    }

    companion object {
        private val VIEWER_HTML = """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<meta name="theme-color" content="#0f1216">
<title>SelfCamMonitor 监控</title>
<style>
:root{--bg:#0f1216;--card:#161b22;--card2:#1e242d;--line:#2a323d;--fg:#e6ebf2;--mut:#8b97a7;--acc:#3b82f6;--ok:#22c55e;--warn:#f59e0b;--err:#ef4444}
*{margin:0;padding:0;box-sizing:border-box;-webkit-tap-highlight-color:transparent}
html,body{height:100%}
body{background:var(--bg);color:var(--fg);font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,"PingFang SC","Microsoft YaHei",sans-serif;display:flex;flex-direction:column;overflow:hidden}
header{display:flex;align-items:center;gap:10px;padding:10px 14px;background:linear-gradient(180deg,#1a212b,#141920);border-bottom:1px solid var(--line);flex-shrink:0}
.brand{display:flex;align-items:center;gap:8px;font-weight:600;font-size:15px;flex:1;min-width:0}
.brand span.t{white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.dot{width:10px;height:10px;border-radius:50%;background:var(--mut);flex-shrink:0}
.dot.live{background:var(--ok);box-shadow:0 0 8px var(--ok)}
.dot.connecting{background:var(--warn);animation:pulse 1.2s infinite}
.dot.disconnected{background:var(--err);animation:pulse 1.2s infinite}
.dot.off{background:var(--mut)}
@keyframes pulse{0%,100%{opacity:1}50%{opacity:.35}}
.badges{display:flex;gap:6px;flex-shrink:0;flex-wrap:wrap;justify-content:flex-end}
.badge{font-size:11px;color:var(--mut);background:var(--card2);border:1px solid var(--line);border-radius:999px;padding:3px 9px;white-space:nowrap}
.badge.rec{color:#fff;background:var(--err);border-color:var(--err);animation:pulse 1.2s infinite}
.badge.proto{color:#93c5fd;border-color:#3b82f6}
.badge.warn{color:#fbbf24;border-color:#b45309}
main{flex:1;display:flex;flex-direction:column;min-height:0}
#stage{position:relative;flex:1;min-height:0;display:flex;align-items:center;justify-content:center;overflow:hidden;background:#000;touch-action:none}
#img{display:block;max-width:100%;max-height:100%;object-fit:contain;-webkit-user-drag:none;user-select:none;transform-origin:center center;will-change:transform}
#video{display:none;width:100%;height:100%;object-fit:contain;background:#000;transform-origin:center center}
#overlay{position:absolute;top:50%;left:50%;transform:translate(-50%,-50%);text-align:center;color:#7c8798;font-size:15px;display:none;z-index:5}
#overlay.show{display:block}
.vtoolbar{position:absolute;left:50%;bottom:14px;transform:translateX(-50%);display:flex;gap:8px;z-index:6}
.iconbtn{width:44px;height:44px;border-radius:50%;border:1px solid rgba(255,255,255,.18);background:rgba(20,25,32,.72);color:#e6ebf2;font-size:17px;cursor:pointer;display:flex;align-items:center;justify-content:center;backdrop-filter:blur(6px);touch-action:manipulation;text-decoration:none;transition:transform .16s ease,background .16s ease,border-color .16s ease,box-shadow .16s ease}
.iconbtn:hover{background:rgba(59,130,246,.9);border-color:var(--acc);transform:translateY(-3px) scale(1.05);box-shadow:0 8px 20px rgba(59,130,246,.4)}
.iconbtn:active{transform:translateY(0) scale(.96);background:rgba(59,130,246,.85)}
.iconbtn.rec.on{background:var(--err);border-color:var(--err)}
.iconbtn.on{background:var(--acc);border-color:var(--acc)}
.vtoolbar.up{bottom:70px}
#dvr{position:absolute;left:12px;right:12px;bottom:12px;display:none;align-items:center;gap:10px;background:rgba(20,25,32,.74);border:1px solid rgba(255,255,255,.14);border-radius:12px;padding:8px 12px;z-index:7;backdrop-filter:blur(6px)}
#dvr.show{display:flex}
.dvr-live{flex-shrink:0;font-size:11px;font-weight:700;color:var(--mut);background:var(--card2);border:1px solid var(--line);border-radius:999px;padding:5px 11px;cursor:pointer;letter-spacing:.5px;transition:background .16s ease,color .16s ease,border-color .16s ease,box-shadow .16s ease}
.dvr-live:hover{border-color:var(--acc);color:var(--fg);box-shadow:0 0 0 3px rgba(59,130,246,.18)}
.dvr-live.on{color:#fff;background:var(--err);border-color:var(--err)}
.dvr-live.off{color:#111;background:var(--warn);border-color:var(--warn)}
#dvr-seek{flex:1;min-width:0;background:transparent;border:none;height:26px;cursor:pointer}
.dvr-time{flex-shrink:0;font-size:12px;color:var(--mut);white-space:nowrap;font-variant-numeric:tabular-nums}
#panel{flex-shrink:0;background:var(--card);border-top:1px solid var(--line);padding:12px 14px;display:flex;flex-direction:column;gap:12px;max-height:46vh;overflow-y:auto}
.prow{display:flex;align-items:center;gap:10px}
.prow label.k{width:64px;flex-shrink:0;font-size:13px;color:var(--mut)}
select,input[type=range]{flex:1;min-width:0;background:var(--card2);color:var(--fg);border:1px solid var(--line);border-radius:8px;padding:8px 10px;font-size:14px;outline:none;transition:border-color .16s ease,box-shadow .16s ease,background .16s ease}
select:hover,input[type=range]:hover{border-color:var(--acc);box-shadow:0 0 0 3px rgba(59,130,246,.15)}
select:focus{border-color:var(--acc);box-shadow:0 0 0 3px rgba(59,130,246,.22)}
input[type=range]{padding:0;height:28px;background:transparent;border:none}
.val{min-width:56px;text-align:right;font-size:13px;color:var(--mut);flex-shrink:0}
.switch{position:relative;width:46px;height:26px;flex-shrink:0}
.switch input{opacity:0;width:0;height:0}
.switch span{position:absolute;inset:0;background:var(--card2);border:1px solid var(--line);border-radius:999px;transition:.2s}
.switch span:before{content:"";position:absolute;width:18px;height:18px;left:3px;top:2px;background:var(--mut);border-radius:50%;transition:.2s}
.switch input:checked+span{background:var(--acc);border-color:var(--acc)}
.switch input:checked+span:before{transform:translateX(20px);background:#fff}
.pinfo{font-size:12px;color:var(--mut);line-height:1.6}
.minibtn{flex-shrink:0;font-size:12px;color:#fff;background:var(--acc);border:1px solid var(--acc);border-radius:8px;padding:6px 12px;cursor:pointer;transition:transform .16s ease,background .16s ease,box-shadow .16s ease,filter .16s ease}
.minibtn:hover{background:var(--primary_dark,#1d4ed8);transform:translateY(-2px);box-shadow:0 6px 16px rgba(59,130,246,.4)}
.minibtn:active{transform:translateY(0) scale(.97);filter:brightness(.95)}
.minibtn.warn{background:var(--warn);border-color:var(--warn);color:#111}
.minibtn.warn:hover{background:#d97706;box-shadow:0 6px 16px rgba(245,158,11,.4)}
#toast{position:fixed;left:50%;bottom:24px;transform:translateX(-50%) translateY(20px);background:rgba(20,25,32,.95);color:#fff;border:1px solid var(--line);padding:10px 16px;border-radius:10px;font-size:13px;opacity:0;pointer-events:none;transition:.25s;z-index:50;max-width:80vw;text-align:center}
#toast.show{opacity:1;transform:translateX(-50%) translateY(0)}
@media(min-width:900px){
main{flex-direction:row}
#panel{width:360px;max-height:none;border-top:none;border-left:1px solid var(--line)}
.brand{font-size:16px}
}
:fullscreen #panel,:fullscreen header{display:none}
:fullscreen #stage{height:100vh}
:-webkit-full-screen #panel,:-webkit-full-screen header{display:none}
:-webkit-full-screen #stage{height:100vh}
</style>
</head>
<body>
<header>
<div class="brand"><span class="dot connecting" id="dot"></span><span class="t" id="title">SelfCamMonitor</span></div>
<div class="badges">
<span class="badge proto" id="b-proto">--</span>
<span class="badge" id="b-fps">-- fps</span>
<span class="badge" id="b-net">-- MB/s</span>
<span class="badge" id="b-buf">--</span>
<span class="badge" id="b-res">--</span>
<span class="badge" id="b-cam">摄像头 --</span>
<span class="badge" id="b-store">存储 --</span>
<span class="badge rec" id="b-rec" style="display:none">录制中</span>
</div>
</header>
<main>
<div id="stage">
<img id="img" alt="监控画面" draggable="false">
<video id="video" playsinline muted></video>
<div id="overlay"></div>
<div id="dvr"><button class="dvr-live" id="dvr-live">LIVE</button><input id="dvr-seek" type="range" min="0" max="0" step="0.05" value="0"><span class="dvr-time" id="dvr-time">--</span></div>
<div class="vtoolbar" id="vtoolbar">
<button class="iconbtn" id="btn-r" title="旋转">&#8635;</button>
<button class="iconbtn" id="btn-shot" title="截图">&#128247;</button>
<button class="iconbtn rec" id="btn-rec" title="录像">&#9679;</button>
<a class="iconbtn" id="btn-gal" href="/gallery" title="录像相册">&#128193;</a>
<a class="iconbtn" id="btn-info" href="/info" title="设备信息">&#8505;</a>
<a class="iconbtn" id="btn-qr" href="/qr" target="_blank" title="二维码">&#9638;</a>
<button class="iconbtn" id="btn-fs" title="全屏">&#9974;</button>
<button class="iconbtn" id="btn-snd" title="声音">&#128263;</button>
</div>
</div>
<aside id="panel">
<div class="prow"><label class="k">流畅度</label><select id="sel-buf"><option value="1">低延迟</option><option value="2" selected>平衡</option><option value="4">流畅（大缓冲）</option></select></div>
<div class="prow"><label class="k">分辨率</label><select id="sel-res"></select></div>
<div class="prow"><label class="k">帧率</label><input id="rng-fps" type="range" min="1" max="60" step="1" value="16"><span class="val" id="lbl-fps">16 fps</span></div>
<div class="prow"><label class="k">摄像头</label><select id="sel-face"><option value="0">后置</option><option value="1">前置</option></select></div>
<div class="prow"><label class="k">录像模式</label><select id="sel-mode"><option value="2">仅预览</option><option value="0">连续录像</option><option value="1">运动触发</option></select></div>
<div class="prow"><label class="k">摄像头</label><span class="badge" id="b-cam2" style="flex:1;text-align:center">--</span><button class="minibtn" id="btn-wake">唤醒</button></div>
<div class="prow"><label class="k">MJPEG</label><label class="switch"><input id="sw-mjpeg" type="checkbox"><span></span></label><span class="val" id="lbl-mjpeg">开启</span></div>
<div class="pinfo" id="pinfo"></div>
</aside>
</main>
<div id="toast"></div>
<script src="/hls.js"></script>
<script>
var img=document.getElementById('img'),video=document.getElementById('video'),stage=document.getElementById('stage'),
dot=document.getElementById('dot'),ov=document.getElementById('overlay');
var selBuf=document.getElementById('sel-buf'),selRes=document.getElementById('sel-res'),rngFps=document.getElementById('rng-fps'),
lblFps=document.getElementById('lbl-fps'),selFace=document.getElementById('sel-face'),
swMjpeg=document.getElementById('sw-mjpeg'),lblMjpeg=document.getElementById('lbl-mjpeg'),
selMode=document.getElementById('sel-mode'),btnRec=document.getElementById('btn-rec'),
bFps=document.getElementById('b-fps'),bNet=document.getElementById('b-net'),bBuf=document.getElementById('b-buf'),
bRes=document.getElementById('b-res'),bRec=document.getElementById('b-rec'),bProto=document.getElementById('b-proto'),
bCam=document.getElementById('b-cam'),bCam2=document.getElementById('b-cam2'),bStore=document.getElementById('b-store'),
btnWake=document.getElementById('btn-wake'),
pinfo=document.getElementById('pinfo'),toastEl=document.getElementById('toast');
var dvrEl=document.getElementById('dvr'),dvSeek=document.getElementById('dvr-seek'),
dvLive=document.getElementById('dvr-live'),dvTime=document.getElementById('dvr-time'),
vtEl=document.getElementById('vtoolbar');
var btnSnd=document.getElementById('btn-snd');
var soundOn=false;
var dragging=false,hlsFallbackTimer=null,lastHlsErr='';
var HI=2000;
var activeEl=img,useHls=false;
var rot=0,zoom=1,panX=0,panY=0,stat='connecting',wantStream=true,reloadTimer=null;
var resFilled=false,userRec=false,firstLoad=false,lastState=null,hls=null;
var HIST_KEY='selfcam_snapshot_hist';
function setStatus(s){
  stat=s;dot.className='dot '+s;
  var t={connecting:'连接中...',live:'已连接',disconnected:'已断开，正在重连...',off:'推流已关闭'};
  if(s==='live'){ov.classList.remove('show');}
  else{ov.textContent=t[s]||'';ov.classList.add('show');}
}
function toast(msg){
  toastEl.textContent=msg;toastEl.classList.add('show');
  clearTimeout(toastEl._t);toastEl._t=setTimeout(function(){toastEl.classList.remove('show');},1800);
}
function applyTransform(){
  var t='translate('+panX+'px,'+panY+'px) rotate('+rot+'deg) scale('+zoom+')';
  img.style.transform=t;video.style.transform=t;
}
function doRotate(){
  activeEl.style.transition='transform .3s ease';
  rot=(rot+90)%360;zoom=1;panX=0;panY=0;applyTransform();
  setTimeout(function(){img.style.transition='none';video.style.transition='none';},350);
}
var MIN_Z=1,MAX_Z=5;
function clampZ(v){return Math.max(MIN_Z,Math.min(MAX_Z,v));}
function getDist(t){var dx=t[0].clientX-t[1].clientX,dy=t[0].clientY-t[1].clientY;return Math.sqrt(dx*dx+dy*dy);}
var touching=false,tx=0,ty=0,pinching=false,pd=0,pz=1,lastTap=0;
stage.addEventListener('touchstart',function(e){
  if(e.touches.length===2){pinching=true;touching=false;pd=getDist(e.touches);pz=zoom;e.preventDefault();}
  else if(e.touches.length===1&&zoom>1){touching=true;tx=e.touches[0].clientX;ty=e.touches[0].clientY;}
},{passive:false});
stage.addEventListener('touchmove',function(e){
  if(pinching&&e.touches.length===2){e.preventDefault();zoom=clampZ(pz*getDist(e.touches)/pd);if(zoom<=1.01){zoom=1;panX=0;panY=0;}applyTransform();}
  else if(touching&&e.touches.length===1&&!useHls){e.preventDefault();panX+=e.touches[0].clientX-tx;panY+=e.touches[0].clientY-ty;tx=e.touches[0].clientX;ty=e.touches[0].clientY;applyTransform();}
},{passive:false});
stage.addEventListener('touchend',function(e){
  if(e.touches.length<2)pinching=false;
  if(e.touches.length===1&&zoom>1){touching=true;tx=e.touches[0].clientX;ty=e.touches[0].clientY;}
  else if(e.touches.length===0){touching=false;var now=Date.now();if(now-lastTap<300){if(zoom>1.01){zoom=1;panX=0;panY=0;}else{zoom=2;}applyTransform();}lastTap=now;}
});
stage.addEventListener('touchcancel',function(){pinching=false;touching=false;});
stage.addEventListener('wheel',function(e){e.preventDefault();zoom=clampZ(zoom*(e.deltaY<0?1.1:0.9));if(zoom<=1.01){zoom=1;panX=0;panY=0;}applyTransform();},{passive:false});
stage.addEventListener('dblclick',function(){if(zoom>1.01){zoom=1;panX=0;panY=0;}else{zoom=2;}applyTransform();});
function reload(){
  if(useHls)return;
  if(!wantStream){img.removeAttribute('src');return;}
  clearTimeout(reloadTimer);
  img.src='/video?t='+Date.now();
}
img.addEventListener('load',function(){if(useHls)return;firstLoad=true;setStatus('live');});
img.addEventListener('error',function(){
  if(useHls||!wantStream)return;
  setStatus('connecting');
  clearTimeout(reloadTimer);reloadTimer=setTimeout(reload,1500);
});
function toggleFs(){
  var e=document.documentElement,fs=document.fullscreenElement||document.webkitFullscreenElement;
  if(!fs){if(e.requestFullscreen)e.requestFullscreen();else if(e.webkitRequestFullscreen)e.webkitRequestFullscreen();}
  else{if(document.exitFullscreen)document.exitFullscreen();else if(document.webkitExitFullscreen)document.webkitExitFullscreen();}
}
function api(path){return fetch(path,{cache:'no-store'}).then(function(r){return r.json();});}
function fmtRate(bps){return (bps/1048576).toFixed(2)+' MB/s';}
function fmtSize(n){n=Number(n)||0;if(!n)return '0 B';var u=['B','KB','MB','GB','TB'];var i=0;while(n>=1024&&i<u.length-1){n/=1024;i++;}return (i?n.toFixed(1):n)+' '+u[i];}
function refresh(d){
  if(!d||d.error)return;
  lastState=d;
  bFps.textContent=(d.currentFps||0)+' fps';
  if(typeof d.netRateBps==='number')bNet.textContent=fmtRate(d.netRateBps);
  bRes.textContent=d.resolution||'--';
  if(typeof d.cameraActive==='boolean'){
    var camTxt=d.cameraActive?'已开启':'休眠';
    bCam.textContent='摄像头 '+camTxt;
    bCam.className='badge'+(d.cameraActive?'':' warn');
    if(bCam2){bCam2.textContent=camTxt+(d.ondemand?'（按需）':'');bCam2.className='badge'+(d.cameraActive?'':' warn');}
    if(btnWake){btnWake.style.display=(d.ondemand&&!d.cameraActive)?'':'none';}
  }
  if(d.storage&&typeof d.storage==='object'){
    var st=d.storage;var free=Number(st.freeBytes)||0;
    var s='可用 '+fmtSize(free);
    if(Number(st.reserveMb)>0)s+=' / 预留 '+st.reserveMb+'M';
    bStore.textContent='存储 '+s;
  }
  if(!resFilled&&d.resolutions&&d.resolutions.length){
    resFilled=true;
    d.resolutions.forEach(function(r){var o=document.createElement('option');o.value=r;o.textContent=r;selRes.appendChild(o);});
  }
  if(d.configuredResolution)selRes.value=d.configuredResolution;
  if(typeof d.fps==='number'){rngFps.value=d.fps;lblFps.textContent=d.fps+' fps';}
  selFace.value=String(d.facing);
  swMjpeg.checked=!!d.mjpegEnabled;
  lblMjpeg.textContent=d.mjpegEnabled?'开启':'关闭';
  selMode.value=String(d.mode);
  userRec=!!d.manualRecording;
  btnRec.classList.toggle('on',userRec);
  btnRec.innerHTML=userRec?'&#9632;':'&#9679;';
  bRec.style.display=(d.manualRecording||d.recording)?'':'none';
  bProto.textContent=(useHls?'HLS(TS)':'MJPEG');
  var parts=[];
  parts.push('模式: '+(d.modeLabel||'--'));
  if(typeof d.audio==='boolean')parts.push('音频: '+(d.audio?'开':'关'));
  if(typeof d.h264Bitrate==='number'&&d.h264Bitrate>0)parts.push('编码: '+(d.h264Bitrate/1000000).toFixed(1)+'Mbps');
  if(typeof d.hlsSegments==='number')parts.push('分片: '+d.hlsSegments);
  if(typeof d.netRateBps==='number')parts.push('网络: '+fmtRate(d.netRateBps));
  pinfo.textContent=parts.join('  ·  ');
}
function updateBufBadge(){
  try{
    if(video.buffered&&video.buffered.length){
      var end=video.buffered.end(video.buffered.length-1);
      var lag=end-video.currentTime;
      bBuf.textContent='延迟 '+lag.toFixed(1)+'s / 缓存 '+(end-video.buffered.start(0)).toFixed(0)+'s';
    }else{bBuf.textContent='--';}
  }catch(e){bBuf.textContent='--';}
}
var fmtT=function(s){s=Math.max(0,Math.floor(s));var m=Math.floor(s/60);s=s%60;return (m<10?'0':'')+m+':'+(s<10?'0':'')+s;};
function updateSeek(){
  if(!useHls||!video.seekable||!video.seekable.length){dvTime.textContent='--';return;}
  var s0=video.seekable.start(0);
  var s1=video.seekable.end(video.seekable.length-1);
  if(s1-s0<0.5){dvTime.textContent='缓冲中...';return;}
  dvSeek.min=s0;dvSeek.max=s1;
  if(!dragging)dvSeek.value=video.currentTime;
  var lag=s1-video.currentTime;
  dvTime.textContent=fmtT(video.currentTime-s0)+' / '+fmtT(s1-s0)+'  ·  延迟 '+lag.toFixed(1)+'s';
  dvLive.className='dvr-live '+(lag<3?'on':'off');
  dvLive.textContent=(lag<3?'LIVE':'回看');
}
function seekTo(v){try{video.currentTime=v;if(video.paused)video.play().catch(function(){});}catch(e){}}
function showDvr(on){
  dvrEl.classList.toggle('show',on);
  vtEl.classList.toggle('up',on);
}
if(dvSeek){
  dvSeek.addEventListener('input',function(){
    dragging=true;
    var s0=parseFloat(dvSeek.min)||0;
    dvTime.textContent=fmtT(parseFloat(dvSeek.value)-s0)+' / '+fmtT((parseFloat(dvSeek.max)||0)-s0)+'  ·  松手跳转';
  });
  dvSeek.addEventListener('change',function(){dragging=false;seekTo(parseFloat(dvSeek.value));});
}
if(dvLive){
  dvLive.addEventListener('click',function(){
    try{if(video.seekable&&video.seekable.length){var s1=video.seekable.end(video.seekable.length-1);video.currentTime=Math.max(0,s1-0.5);video.play().catch(function(){});toast('回到直播');}}catch(e){}
  });
}
function applyBufferPreset(){
  var n=parseInt(selBuf.value,10)||2;
  if(hls){
    hls.config.lowLatencyMode=(n<=2);
    hls.config.liveSyncDurationCount=n;
    hls.config.maxBufferLength=n*6;
    hls.config.maxMaxBufferLength=n*12;
    hls.config.backBufferLength=120;
    try{hls.loadSource('/live.m3u8?t='+Date.now());}catch(e){}
    toast('流畅度：'+selBuf.options[selBuf.selectedIndex].text);
  }
}
function startHls(){
  useHls=true;firstLoad=false;
  img.style.display='none';video.style.display='block';activeEl=video;
  setStatus('connecting');img.removeAttribute('src');bProto.textContent='HLS(TS)';showDvr(true);
  if(hlsFallbackTimer)clearTimeout(hlsFallbackTimer);
  hlsFallbackTimer=setTimeout(function(){if(useHls&&!firstLoad){toast('HLS 无数据，回退 MJPEG');startMjpeg();}},12000);
  if(window.Hls&&Hls.isSupported()){
    if(hls){try{hls.destroy();}catch(e){}}
    hls=new Hls({lowLatencyMode:(parseInt(selBuf.value,10)<=1),liveSyncDurationCount:parseInt(selBuf.value,10)||2,
      maxBufferLength:(parseInt(selBuf.value,10)||2)*12,maxMaxBufferLength:(parseInt(selBuf.value,10)||2)*24,backBufferLength:120,enableWorker:true,
      liveDurationInfinity:true,manifestLoadingMaxRetry:10,manifestLoadingRetryDelay:800,
      levelLoadingMaxRetry:10,fragLoadingMaxRetry:10,maxLiveSyncPlaybackRate:1.5});
    hls.on(Hls.Events.ERROR,function(ev,data){
      if(!data||!data.fatal)return;
      lastHlsErr=data.type+'/'+data.details;
      pinfo.textContent='HLS 错误: '+lastHlsErr+(data.reason?(' | '+data.reason):'');
      toast('HLS 错误('+data.details+')，回退 MJPEG');
      startMjpeg();
    });
    hls.loadSource('/live.m3u8?t='+Date.now());
    hls.attachMedia(video);
    hls.on(Hls.Events.MANIFEST_PARSED,function(){video.play().catch(function(){});});
  }else{
    video.src='/live.m3u8?t='+Date.now();
    video.play().catch(function(){});
  }
  video.onplaying=function(){firstLoad=true;if(hlsFallbackTimer)clearTimeout(hlsFallbackTimer);setStatus('live');};
  video.onwaiting=function(){if(stat==='live')setStatus('connecting');};
}
function startMjpeg(){
  useHls=false;
  if(hlsFallbackTimer)clearTimeout(hlsFallbackTimer);
  showDvr(false);
  if(hls){try{hls.destroy();}catch(e){}hls=null;}
  try{video.pause();video.removeAttribute('src');video.load();}catch(e){}
  video.style.display='none';img.style.display='block';activeEl=img;
  bProto.textContent='MJPEG';setStatus('connecting');reload();
}
function chooseMode(){
  api('/api/state').then(function(d){
    lastState=d;
    if(window.Hls&&Hls.isSupported()){startHls();}
    else if(video.canPlayType('application/vnd.apple.mpegurl')){startHls();}
    else{startMjpeg();}
  }).catch(function(){startMjpeg();});
}
function heartbeat(){
  api('/api/state').then(function(d){
    refresh(d);
    if(useHls){updateBufBadge();updateSeek();return;}
    if(d&&d.mjpegEnabled===false){
      if(wantStream){wantStream=false;img.removeAttribute('src');}
      setStatus('off');return;
    }
    if(!wantStream){wantStream=true;reload();}
    if(stat==='off'){setStatus('connecting');reload();}
    if(stat!=='live'&&!firstLoad){setStatus('connecting');}
  }).catch(function(){if(stat!=='off')setStatus('disconnected');});
}
function snapshot(){
  if(useHls&&video.videoWidth>0){
    var c=document.createElement('canvas');c.width=video.videoWidth;c.height=video.videoHeight;
    c.getContext('2d').drawImage(video,0,0,c.width,c.height);
    var a=document.createElement('a');a.href=c.toDataURL('image/jpeg',0.92);a.download='snapshot_'+Date.now()+'.jpg';
    document.body.appendChild(a);a.click();a.remove();toast('已保存截图');
    return;
  }
  var a2=document.createElement('a');a2.href='/api/snapshot?t='+Date.now();a2.download='snapshot.jpg';
  document.body.appendChild(a2);a2.click();a2.remove();toast('已保存截图');
}
function toggleRec(){
  var action=userRec?'stop':'start';
  api('/api/record?action='+action).then(function(d){
    if(d.error){toast('失败: '+d.error);return;}
    refresh(d);toast(action==='start'?'开始录制':'已停止录制');
  }).catch(function(){toast('操作失败');});
}
function applyConfig(params,msg){
  var qs=Object.keys(params).map(function(k){return encodeURIComponent(k)+'='+encodeURIComponent(params[k]);}).join('&');
  api('/api/config?'+qs).then(function(d){
    if(d.error){toast('失败: '+d.error);return;}
    refresh(d);toast(msg||'已应用');
  }).catch(function(){toast('设置失败');});
}
document.getElementById('btn-r').addEventListener('click',doRotate);
document.getElementById('btn-fs').addEventListener('click',toggleFs);
document.getElementById('btn-shot').addEventListener('click',snapshot);
btnRec.addEventListener('click',toggleRec);
function toggleSound(){
  soundOn=!soundOn;
  video.muted=!soundOn;
  if(soundOn){video.volume=1;try{video.play().catch(function(){});}catch(e){}}
  btnSnd.innerHTML=soundOn?'&#128266;':'&#128263;';
  btnSnd.classList.toggle('on',soundOn);
  if(soundOn&&lastState&&lastState.audio===false)toast('该路流无音频（麦克风未授权）');
}
btnSnd.addEventListener('click',toggleSound);
if(btnWake){btnWake.addEventListener('click',function(){
  btnWake.classList.add('warn');btnWake.textContent='唤醒中...';
  api('/api/camera?on=1&ttl=120').then(function(d){
    toast('已唤醒摄像头');
    setTimeout(heartbeat,600);setTimeout(heartbeat,2500);
  }).catch(function(){toast('唤醒失败');}).then(function(){btnWake.classList.remove('warn');btnWake.textContent='唤醒';});
});}
selBuf.addEventListener('change',applyBufferPreset);
selRes.addEventListener('change',function(){applyConfig({resolution:selRes.value},'分辨率将重启相机生效');resFilled=false;});
selFace.addEventListener('change',function(){resFilled=false;selRes.innerHTML='';applyConfig({facing:selFace.value},'已切换摄像头');});
selMode.addEventListener('change',function(){applyConfig({mode:selMode.value},'已切换录像模式');});
var fpsTimer=null;
rngFps.addEventListener('input',function(){lblFps.textContent=rngFps.value+' fps';});
rngFps.addEventListener('change',function(){
  clearTimeout(fpsTimer);
  fpsTimer=setTimeout(function(){applyConfig({fps:rngFps.value},'帧率已设为 '+rngFps.value);},350);
});
swMjpeg.addEventListener('change',function(){
  applyConfig({mjpeg:swMjpeg.checked?'1':'0'},swMjpeg.checked?'已开启 MJPEG':'已关闭 MJPEG');
});
window.addEventListener('resize',function(){applyTransform();});
document.addEventListener('fullscreenchange',function(){applyTransform();});
document.addEventListener('webkitfullscreenchange',function(){applyTransform();});
chooseMode();
heartbeat();
setInterval(heartbeat,HI);
</script>
</body>
</html>
        """.trimIndent()

        private val INFO_HTML = """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<meta name="theme-color" content="#0f1216">
<title>设备信息 - SelfCamMonitor</title>
<style>
*{box-sizing:border-box;margin:0;padding:0}
:root{--bg:#0f1216;--card:#171b22;--card2:#1e242d;--fg:#e6e9ee;--dim:#8b95a3;--line:#262d37;--accent:#3b82f6;--ok:#22c55e;--warn:#f59e0b;--err:#ef4444}
body{background:var(--bg);color:var(--fg);font:15px/1.5 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,"PingFang SC","Microsoft YaHei",sans-serif;padding:14px;padding-bottom:40px}
a{color:var(--accent);text-decoration:none}
.top{display:flex;align-items:center;gap:10px;margin-bottom:14px}
.top .t{font-size:18px;font-weight:700;flex:1}
.back{display:inline-flex;align-items:center;gap:6px;padding:8px 14px;border-radius:10px;background:var(--card2);border:1px solid var(--line);color:var(--fg);transition:transform .15s ease,box-shadow .15s ease,background .15s}
.back:hover{transform:translateY(-2px);box-shadow:0 8px 20px rgba(0,0,0,.4);background:#252c36}
.grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(240px,1fr));gap:12px}
.card{background:var(--card);border:1px solid var(--line);border-radius:16px;padding:14px 16px;transition:transform .15s ease,box-shadow .15s ease,border-color .15s}
.card:hover{transform:translateY(-2px);box-shadow:0 10px 26px rgba(0,0,0,.45);border-color:#33405a}
.card h3{font-size:13px;font-weight:600;color:var(--dim);letter-spacing:.05em;margin-bottom:10px;text-transform:uppercase}
.big{font-size:30px;font-weight:750;line-height:1.1}
.big small{font-size:15px;font-weight:600;color:var(--dim);margin-left:3px}
.row{display:flex;justify-content:space-between;gap:10px;padding:5px 0;border-bottom:1px dashed rgba(255,255,255,.05)}
.row:last-child{border-bottom:0}
.row .k{color:var(--dim)}
.row .v{font-weight:600;text-align:right;word-break:break-all}
.bar{height:9px;border-radius:6px;background:var(--card2);overflow:hidden;margin-top:8px}
.bar>i{display:block;height:100%;border-radius:6px;background:linear-gradient(90deg,#22c55e,#3b82f6);transition:width .4s ease}
.bar.hot>i{background:linear-gradient(90deg,#f59e0b,#ef4444)}
.dot{width:9px;height:9px;border-radius:50%;display:inline-block;margin-right:7px;vertical-align:middle;background:var(--dim)}
.dot.on{background:var(--ok);box-shadow:0 0 8px var(--ok)}
.dot.off{background:var(--err)}
.mono{font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;font-size:13px}
.tag{display:inline-block;padding:2px 9px;border-radius:999px;font-size:12px;font-weight:600;background:var(--card2);border:1px solid var(--line);margin-right:6px}
.tag.on{background:rgba(34,197,94,.16);border-color:rgba(34,197,94,.5);color:#7ee2a8}
.tag.off{background:rgba(239,68,68,.14);border-color:rgba(239,68,68,.45);color:#f2a2a2}
.foot{margin-top:18px;text-align:center;color:var(--dim);font-size:12px}
</style>
</head>
<body>
<div class="top"><span class="t">设备信息</span><a class="back" href="/">&#8592; 实时画面</a><a class="back" href="/gallery">&#128193; 相册</a></div>
<div class="grid">
<div class="card"><h3>电量</h3><div class="big" id="bat">--<small>%</small></div><div class="bar" id="batbar"><i style="width:0"></i></div><div class="row"><span class="k">状态</span><span class="v" id="bash">--</span></div><div class="row"><span class="k">电池温度</span><span class="v" id="btemp">--</span></div><div class="row"><span class="k">电压 / 电流</span><span class="v" id="bvolt">--</span></div></div>
<div class="card"><h3>温度 / CPU</h3><div class="big" id="cpuT">--<small>°C</small></div><div class="row"><span class="k">CPU 使用率</span><span class="v" id="cpuU">--</span></div><div class="row"><span class="k">运行时长</span><span class="v" id="uptime">--</span></div></div>
<div class="card"><h3>内存</h3><div class="big" id="memP">--<small>%</small></div><div class="bar" id="membar"><i style="width:0"></i></div><div class="row"><span class="k">已用 / 总计</span><span class="v" id="memtxt">--</span></div></div>
<div class="card"><h3>存储</h3><div class="big" id="storeFree">--</div><div class="bar" id="storebar"><i style="width:0"></i></div><div class="row"><span class="k">录像占用</span><span class="v" id="storeRec">--</span></div><div class="row"><span class="k">精选数量</span><span class="v" id="storeStar">--</span></div></div>
<div class="card"><h3>设备</h3><div class="row"><span class="k">机型</span><span class="v" id="model">--</span></div><div class="row"><span class="k">Android</span><span class="v" id="os">--</span></div><div class="row"><span class="k">架构</span><span class="v" id="abi">--</span></div><div class="row"><span class="k">App 版本</span><span class="v" id="appv">--</span></div></div>
<div class="card"><h3>视频流 / 穿透</h3><div class="row"><span class="k">摄像头</span><span class="v" id="cam">--</span></div><div class="row"><span class="k">当前帧率</span><span class="v" id="fps">--</span></div><div class="row"><span class="k">分辨率</span><span class="v" id="res">--</span></div><div class="row"><span class="k">实时速率</span><span class="v" id="net">--</span></div><div class="row"><span class="k">正在录像</span><span class="v" id="rec">--</span></div><div class="row"><span class="k">Cloudflare</span><span class="v" id="cf">--</span></div><div class="row"><span class="k">frp</span><span class="v" id="frp">--</span></div><div class="row"><span class="k">局域网</span><span class="v mono" id="lan">--</span></div></div>
</div>
<div class="foot">SelfCamMonitor · <span id="ts">--</span></div>
<script>
function byId(i){return document.getElementById(i);}
function fmtSize(n){n=Number(n)||0;if(!n)return '0 B';var u=['B','KB','MB','GB','TB'];var i=0;while(n>=1024&&i<u.length-1){n/=1024;i++;}return (i?n.toFixed(1):n)+' '+u[i];}
function fmtDur(s){s=Math.max(0,Math.floor(s));var d=Math.floor(s/86400),h=Math.floor(s%86400/3600),m=Math.floor(s%3600/60);s=s%60;var o='';if(d)o+=d+'天';if(h)o+=h+'时';o+=m+'分'+s+'秒';return o;}
function setBar(barId,pct,hot){var b=byId(barId);if(!b)return;b.classList.toggle('hot',!!hot);var i=b.querySelector('i');i.style.width=Math.max(0,Math.min(100,pct))+'%';}
function render(d){
  if(!d||d.error)return;
  var bat=d.battery||{};
  var pct=(typeof bat.percent==='number')?bat.percent:null;
  byId('bat').innerHTML=(pct==null?'--':pct)+'<small>%</small>';
  setBar('batbar',pct==null?0:pct,(pct!=null&&pct<=20));
  byId('bash').textContent=(bat.charging?'充电中':'放电中')+(bat.plugged?('（'+bat.plugged+'）'):'');
  byId('btemp').textContent=(typeof bat.tempC==='number')?bat.tempC+' °C':'--';
  var cur=(typeof bat.currentUa==='number')?(Math.abs(bat.currentUa)/1000).toFixed(0)+' mA':'';
  byId('bvolt').textContent=((bat.voltageMv?bat.voltageMv+' mV':''))+(cur?(' / '+cur):'')||'--';
  var ct=d.cpuTempC;
  byId('cpuT').innerHTML=(typeof ct==='number')?ct+'<small>°C</small>':'--<small>°C</small>';
  byId('cpuU').textContent=(typeof d.cpuUsagePct==='number')?(d.cpuUsagePct+' %'):'--';
  byId('uptime').textContent=(typeof d.uptimeSec==='number')?fmtDur(d.uptimeSec):'--';
  var mem=d.memory||{};
  var mt=Number(mem.totalBytes)||0,ma=Number(mem.availBytes)||0,mu=mt-ma;
  var mp=mt>0?Math.round(mu*100/mt):null;
  byId('memP').innerHTML=(mp==null?'--':mp)+'<small>%</small>';
  setBar('membar',mp==null?0:mp,(mp!=null&&mp>=88));
  byId('memtxt').textContent=fmtSize(mu)+' / '+fmtSize(mt);
  var st=d.storage||{};
  var tot=Number(st.totalBytes)||0,free=Number(st.freeBytes)||0,used=tot-free;
  byId('storeFree').textContent='可用 '+fmtSize(free);
  setBar('storebar',tot>0?Math.round(used*100/tot):0,(tot>0&&free/tot<0.08));
  byId('storeRec').textContent=fmtSize(st.recordingBytes);
  byId('storeStar').textContent=(st.starredCount||0)+' 个';
  var dv=d.device||{};
  byId('model').textContent=dv.model||'--';
  byId('os').textContent='Android '+(dv.android||'--')+' (API '+(dv.sdkInt||'--')+')';
  byId('abi').textContent=dv.abi||'--';
  byId('appv').textContent=dv.appVersion||'--';
  byId('cam').textContent=(typeof d.cameraActive==='boolean')?(d.cameraActive?'已开启':'休眠'+(d.ondemand?'（按需）':'')):'--';
  byId('fps').textContent=(d.currentFps||0)+' fps';
  byId('res').textContent=d.resolution||'--';
  byId('net').textContent=(typeof d.netRateBps==='number')?((d.netRateBps/1048576).toFixed(2)+' MB/s'):'--';
  byId('rec').textContent=(d.recording?'是':'否');
  byId('cf').innerHTML=d.cfRunning?'<span class="tag on">运行</span>':'<span class="tag off">停止</span>';
  byId('frp').innerHTML=d.frpRunning?'<span class="tag on">运行</span>':'<span class="tag off">停止</span>';
  byId('lan').textContent=d.lanIp?('http://'+d.lanIp+':8080'):'--';
  var nw=new Date();byId('ts').textContent=nw.toLocaleTimeString();
}
function tick(){fetch('/api/state',{cache:'no-store'}).then(function(r){return r.json();}).then(render).catch(function(){});}
tick();setInterval(tick,3000);
</script>
</body>
</html>
        """.trimIndent()

        private val GALLERY_HTML = """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<meta name="theme-color" content="#0f1216">
<title>录像相册 - SelfCamMonitor</title>
<style>
:root{--bg:#0f1216;--card:#161b22;--card2:#1e242d;--line:#2a323d;--fg:#e6ebf2;--mut:#8b97a7;--acc:#3b82f6;--ok:#22c55e;--warn:#f59e0b;--err:#ef4444}
*{margin:0;padding:0;box-sizing:border-box;-webkit-tap-highlight-color:transparent}
body{background:var(--bg);color:var(--fg);font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,"PingFang SC","Microsoft YaHei",sans-serif;min-height:100vh}
a{color:var(--acc);text-decoration:none}
header{position:sticky;top:0;z-index:10;display:flex;align-items:center;gap:10px;padding:12px 14px;background:linear-gradient(180deg,#1a212b,#141920);border-bottom:1px solid var(--line)}
.brand{display:flex;align-items:center;gap:8px;font-weight:600;font-size:16px;flex:1;min-width:0}
.brand .t{white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.back{font-size:13px;color:var(--mut);border:1px solid var(--line);background:var(--card2);border-radius:999px;padding:6px 12px;white-space:nowrap}
.btn{font-size:13px;color:var(--fg);border:1px solid var(--line);background:var(--card2);border-radius:8px;padding:7px 12px;cursor:pointer}
.btn:active{background:var(--acc);border-color:var(--acc);color:#fff}
.bar{display:flex;flex-wrap:wrap;align-items:center;gap:8px;padding:10px 14px;border-bottom:1px solid var(--line);font-size:12px;color:var(--mut)}
.bar select{background:var(--card2);color:var(--fg);border:1px solid var(--line);border-radius:8px;padding:6px 8px;font-size:13px}
.grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(164px,1fr));gap:12px;padding:14px}
@media(max-width:520px){.grid{grid-template-columns:repeat(auto-fill,minmax(142px,1fr));gap:10px;padding:10px}}
.card{background:var(--card);border:1px solid var(--line);border-radius:12px;overflow:hidden;display:flex;flex-direction:column;cursor:pointer;transition:.15s}
.card:hover{border-color:var(--acc);transform:translateY(-1px)}
.thumb{position:relative;width:100%;aspect-ratio:16/10;background:#000;display:flex;align-items:center;justify-content:center;overflow:hidden}
.thumb img{width:100%;height:100%;object-fit:cover;display:block}
.thumb .ph{color:#4b5563;font-size:26px}
.thumb .dur{position:absolute;right:6px;bottom:6px;background:rgba(0,0,0,.72);color:#fff;font-size:11px;border-radius:4px;padding:1px 5px;font-variant-numeric:tabular-nums}
.meta{padding:8px 10px;display:flex;flex-direction:column;gap:3px}
.meta .name{font-size:13px;font-weight:600;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.meta .sub{font-size:11px;color:var(--mut);display:flex;justify-content:space-between;gap:6px}
.ops{position:absolute;top:6px;right:6px;display:flex;gap:6px;z-index:3}
.op{width:30px;height:30px;border-radius:8px;border:1px solid rgba(255,255,255,.25);background:rgba(20,25,32,.72);color:#e6ebf2;font-size:14px;cursor:pointer;display:flex;align-items:center;justify-content:center;padding:0;line-height:1;transition:transform .16s ease,background .16s ease,border-color .16s ease}
.op:hover{transform:translateY(-2px) scale(1.08)}
.op:active{background:var(--acc);border-color:var(--acc)}
.op.star.on{background:var(--warn);border-color:var(--warn);color:#111}
.op.del:hover{background:var(--err);border-color:var(--err);color:#fff}
.card.starcard{border-color:var(--warn)}
.card.starcard .thumb:after{content:"\2605";position:absolute;left:6px;top:4px;color:var(--warn);font-size:16px;text-shadow:0 1px 3px #000}
.empty{padding:60px 20px;text-align:center;color:var(--mut);line-height:1.9}
.empty .big{font-size:44px;margin-bottom:8px}
#modal{position:fixed;inset:0;background:rgba(0,0,0,.82);display:none;align-items:center;justify-content:center;z-index:100;padding:16px}
#modal.show{display:flex}
.dlg{background:var(--card);border:1px solid var(--line);border-radius:14px;max-width:860px;width:100%;max-height:92vh;display:flex;flex-direction:column;overflow:hidden}
.dlg .hd{display:flex;align-items:center;gap:8px;padding:10px 14px;border-bottom:1px solid var(--line)}
.dlg .hd .ttl{flex:1;min-width:0;font-size:14px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.dlg video{width:100%;max-height:60vh;background:#000;display:block}
.dlg .ft{display:flex;flex-wrap:wrap;gap:8px;padding:12px 14px;border-top:1px solid var(--line);align-items:center}
.sp{flex:1}
.ft2{padding-top:2px;border-top:none}
.lb{font-size:12px;color:var(--mut)}
.x{width:34px;height:34px;border-radius:50%;border:1px solid var(--line);background:var(--card2);color:var(--fg);font-size:16px;cursor:pointer;flex-shrink:0}
.pbtn{background:var(--acc);border-color:var(--acc);color:#fff;font-weight:600}
.prog{display:flex;align-items:center;gap:10px;width:100%;font-size:12px;color:var(--mut)}
.progbar{flex:1;height:8px;background:var(--card2);border:1px solid var(--line);border-radius:999px;overflow:hidden}
.progbar i{display:block;height:100%;width:0;background:var(--acc);transition:width .15s}
#toast{position:fixed;left:50%;bottom:24px;transform:translateX(-50%) translateY(20px);background:rgba(20,25,32,.96);color:#fff;border:1px solid var(--line);padding:10px 16px;border-radius:10px;font-size:13px;opacity:0;pointer-events:none;transition:.25s;z-index:200;max-width:84vw;text-align:center}
#toast.show{opacity:1;transform:translateX(-50%) translateY(0)}
</style>
</head>
<body>
<header>
<div class="brand"><span>&#128193;</span><span class="t" id="title">录像相册</span></div>
<a class="back" href="/">&#9654; 实时画面</a>
<button class="btn" id="btn-refresh">刷新</button>
</header>
<div class="bar">
<span id="stat">加载中...</span>
<span class="sp"></span>
<span id="storage"></span>
<span>分片大小</span>
<select id="sel-chunk">
<option value="1048576">1 MB</option>
<option value="2097152" selected>2 MB</option>
<option value="5242880">5 MB</option>
<option value="10485760">10 MB</option>
</select>
</div>
<div class="grid" id="grid"></div>
<div id="modal">
<div class="dlg">
<div class="hd"><span class="ttl" id="m-title">--</span><button class="x" id="m-close">&#10005;</button></div>
<video id="m-video" controls playsinline preload="metadata"></video>
<div class="ft">
<button class="btn pbtn" id="m-dl">&#11123; 下载</button>
<button class="btn" id="m-chunk">&#9986; 分片下载</button>
<span class="sp"></span>
<button class="btn" id="m-open">&#128065; 新窗口</button>
<button class="btn" id="m-star">&#9734; 精选</button>
<button class="btn" id="m-del">&#128465; 删除</button>
</div>
<div class="ft ft2">
<span class="lb">下载链路</span>
<select id="sel-link"><option value="auto">当前页面</option></select>
<span class="sp"></span>
<button class="btn" id="m-copy">&#128279; 复制链接</button>
</div>
<div class="ft" id="m-progwrap" style="display:none">
<div class="prog"><div class="progbar"><i id="m-progbar"></i></div><span id="m-progtxt">0%</span></div>
</div>
</div>
</div>
<div id="toast"></div>
<script>
var grid=document.getElementById('grid'),statEl=document.getElementById('stat'),toastEl=document.getElementById('toast');
var modal=document.getElementById('modal'),mVideo=document.getElementById('m-video'),mTitle=document.getElementById('m-title');
var progwrap=document.getElementById('m-progwrap'),progbar=document.getElementById('m-progbar'),progtxt=document.getElementById('m-progtxt');
var selChunk=document.getElementById('sel-chunk');
var files=[],curRel='',curIndex=-1,selLink=document.getElementById('sel-link'),linkMode='auto',tunnels={cf:'',frp:'',lan:''},linkLoaded=false;
var storageEl=document.getElementById('storage'),mStar=document.getElementById('m-star'),mDel=document.getElementById('m-del');
function toast(m){toastEl.textContent=m;toastEl.classList.add('show');clearTimeout(toastEl._t);toastEl._t=setTimeout(function(){toastEl.classList.remove('show');},2200);}
function fmtSize(n){n=Number(n)||0;if(!n)return '0 B';var u=['B','KB','MB','GB'];var i=0;while(n>=1024&&i<u.length-1){n/=1024;i++;}return (i?n.toFixed(1):n)+' '+u[i];}
function fmtDur(ms){ms=Number(ms)||0;if(ms<=0)return '--:--';var s=Math.round(ms/1000);var m=Math.floor(s/60);s=s%60;return (m<10?'0':'')+m+':'+(s<10?'0':'')+s;}
function fmtTime(ts){var d=new Date(Number(ts)||0);function p(n){return (n<10?'0':'')+n;}return d.getFullYear()+'-'+p(d.getMonth()+1)+'-'+p(d.getDate())+' '+p(d.getHours())+':'+p(d.getMinutes());}
function esc(s){return String(s).replace(/[&<>"']/g,function(c){return ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'})[c];});}
function baseUrl(){if(linkMode==='cf')return tunnels.cf;if(linkMode==='frp')return tunnels.frp;if(linkMode==='lan')return tunnels.lan;return '';}
function dlUrl(rel){return baseUrl()+'/dl/'+encodeURI(rel);}
function playUrl(rel){return baseUrl()+'/play/'+encodeURI(rel);}
function trimSlash(u){u=String(u);while(u.length&&u.charAt(u.length-1)==='/')u=u.slice(0,-1);return u;}
function loadLinks(){
  fetch('/api/state',{cache:'no-store'}).then(function(r){return r.json();}).then(function(d){
    d=d||{};
    tunnels.cf=(d.cfUrl&&String(d.cfUrl).indexOf('http')===0)?trimSlash(d.cfUrl):'';
    tunnels.frp=(d.frpUrl&&String(d.frpUrl).indexOf('http')===0)?trimSlash(d.frpUrl):'';
    tunnels.lan=d.lanIp?('http://'+d.lanIp+':8080'):'';
    var h='<option value="auto">当前页面</option>';
    if(tunnels.cf)h+='<option value="cf">Cloudflare 隧道</option>';
    if(tunnels.frp)h+='<option value="frp">frp 隧道</option>';
    if(tunnels.lan)h+='<option value="lan">局域网直连</option>';
    selLink.innerHTML=h;linkMode='auto';linkLoaded=true;
    if(storageEl&&d.storage&&typeof d.storage==='object'){
      var st=d.storage;
      storageEl.textContent='\u53ef\u7528 '+fmtSize(Number(st.freeBytes)||0)+' / \u5171 '+fmtSize(Number(st.totalBytes)||0)+(Number(st.reserveMb)>0?('  \u00b7  \u9884\u7559 '+st.reserveMb+'M'):'');
    }
  }).catch(function(){linkLoaded=true;});
}
function load(){
  if(!linkLoaded)loadLinks();
  statEl.textContent='加载中...';
  fetch('/api/recordings',{cache:'no-store'}).then(function(r){return r.json();}).then(function(d){
    if(d&&d.error){statEl.textContent='错误: '+d.error;return;}
    files=(d&&d.files)||[];
    render();
  }).catch(function(){statEl.textContent='加载失败，请刷新';});
}
function render(){
  var tot=0;for(var t=0;t<files.length;t++)tot+=Number(files[t].size)||0;
  statEl.textContent='共 '+files.length+' 个录像'+(files.length?('  ·  合计 '+fmtSize(tot)):'');
  if(!files.length){grid.innerHTML='<div class="empty" style="grid-column:1/-1"><div class="big">&#127909;</div>暂无录像<br>可在实时画面点录像，或调用 API 录制</div>';return;}
  var html='';
  for(var i=0;i<files.length;i++){
    var f=files[i];
    html+='<div class="card'+(f.starred?' starcard':'')+'" data-i="'+i+'">';
    html+='<div class="thumb"><img loading="lazy" src="/thumb/'+encodeURI(f.relPath)+'" alt=""><span class="dur">'+fmtDur(f.durationMs)+'</span>';
    html+='<div class="ops"><button class="op star'+(f.starred?' on':'')+'" data-op="star" data-i="'+i+'" title="精选">'+(f.starred?'\u2605':'\u2606')+'</button>';
    html+='<button class="op del" data-op="del" data-i="'+i+'" title="删除">\uD83D\uDDD1</button></div></div>';
    html+='<div class="meta"><div class="name">'+esc(f.name)+'</div><div class="sub"><span>'+esc(f.date)+'</span><span>'+fmtTime(f.modified)+'</span></div><div class="sub"><span>'+fmtSize(f.size)+'</span>'+(f.starred?'<span style="color:var(--warn)">\u5df2\u7cbe\u9009</span>':'')+'</div></div>';
    html+='</div>';
  }
  grid.innerHTML=html;
  var cards=grid.querySelectorAll('.card');
  for(var j=0;j<cards.length;j++){cards[j].addEventListener('click',function(){openPlayer(parseInt(this.getAttribute('data-i'),10));});}
  var ops=grid.querySelectorAll('.op');
  for(var m=0;m<ops.length;m++){ops[m].addEventListener('click',function(ev){ev.stopPropagation();var i=parseInt(this.getAttribute('data-i'),10);var op=this.getAttribute('data-op');if(op==='star')toggleStar(i);else delItem(i);});}
  var imgs=grid.querySelectorAll('.thumb img');
  for(var k=0;k<imgs.length;k++){imgs[k].addEventListener('error',function(){this.style.visibility='hidden';});}
}
function setStarBtn(f){if(!mStar)return;mStar.innerHTML=f.starred?'\u2605 \u53d6\u6d88\u7cbe\u9009':'\u2606 \u7cbe\u9009';}
function toggleStar(i){
  var f=files[i];if(!f)return;
  var want=!f.starred;
  fetch('/api/star?path='+encodeURIComponent(f.relPath)+'&on='+(want?'1':'0'),{cache:'no-store'})
    .then(function(r){return r.json();})
    .then(function(d){if(d&&d.ok){f.starred=want;render();if(curIndex===i)setStarBtn(f);toast(want?'\u5df2\u52a0\u5165\u7cbe\u9009\uff08\u4e0d\u4f1a\u88ab\u81ea\u52a8\u6e05\u7406\uff09':'\u5df2\u53d6\u6d88\u7cbe\u9009');}else{toast('\u64cd\u4f5c\u5931\u8d25');}})
    .catch(function(){toast('\u64cd\u4f5c\u5931\u8d25');});
}
function delItem(i){
  var f=files[i];if(!f)return;
  var msg=f.starred?('\u300c'+f.name+'\u300d\u5df2\u7cbe\u9009\uff0c\u5220\u9664\u540e\u4e0d\u53ef\u6062\u590d\uff0c\u786e\u5b9a\u5220\u9664\uff1f'):('\u786e\u5b9a\u5220\u9664\u300c'+f.name+'\u300d\uff1f\u6b64\u64cd\u4f5c\u4e0d\u53ef\u6062\u590d');
  if(!confirm(msg))return;
  var q='/api/delete?path='+encodeURIComponent(f.relPath)+(f.starred?'&force=1':'');
  fetch(q,{cache:'no-store'})
    .then(function(r){return r.json();})
    .then(function(d){if(d&&d.ok){toast('\u5df2\u5220\u9664');if(curRel===f.relPath)closePlayer();load();}else{toast('\u5220\u9664\u5931\u8d25');}})
    .catch(function(){toast('\u5220\u9664\u5931\u8d25');});
}
function openPlayer(i){
  var f=files[i];if(!f)return;
  curRel=f.relPath;curIndex=i;
  mTitle.textContent=f.name+'  ·  '+fmtSize(f.size)+'  ·  '+fmtDur(f.durationMs);
  setStarBtn(f);
  mVideo.src=playUrl(f.relPath);
  progwrap.style.display='none';progbar.style.width='0';progtxt.textContent='0%';
  modal.classList.add('show');
  try{mVideo.play().catch(function(){});}catch(e){}
}
function closePlayer(){try{mVideo.pause();}catch(e){}mVideo.removeAttribute('src');try{mVideo.load();}catch(e){}modal.classList.remove('show');}
function download(rel){var u=dlUrl(rel);var a=document.createElement('a');a.href=u;a.download=(rel.split('/').pop()||'video.mp4');if(u.indexOf('http')===0&&u.indexOf(location.origin)!==0)a.target='_blank';document.body.appendChild(a);a.click();a.remove();}
function doChunkDownload(){
  var rel=curRel;if(!rel)return;
  var f=null;for(var i=0;i<files.length;i++){if(files[i].relPath===rel){f=files[i];break;}}
  var name=(f&&f.name)||'video.mp4';
  var total=(f&&Number(f.size))||0;
  var chunk=parseInt(selChunk.value,10)||2097152;
  if(!total){toast('文件大小未知');return;}
  if(!window.showSaveFilePicker){toast('此浏览器不支持分片保存，改为普通下载');download(rel);return;}
  window.showSaveFilePicker({suggestedName:name}).then(function(handle){return handle.createWritable();}).then(function(writable){
    var start=0;
    function step(){
      if(start>=total){return writable.close().then(function(){toast('分片下载完成');});}
      var end=Math.min(start+chunk,total)-1;
      return fetch(dlUrl(rel),{headers:{'Range':'bytes='+start+'-'+end}}).then(function(resp){
        if(resp.status!==206&&!resp.ok)throw new Error('HTTP '+resp.status);
        return resp.arrayBuffer();
      }).then(function(buf){
        return writable.write(new Uint8Array(buf));
      }).then(function(){
        start=end+1;
        var pct=Math.floor(start*100/total);
        progbar.style.width=pct+'%';progtxt.textContent=pct+'%';
        return step();
      });
    }
    progwrap.style.display='flex';
    return step();
  }).catch(function(e){
    if(e&&e.name==='AbortError'){progwrap.style.display='none';return;}
    progwrap.style.display='none';
    toast('分片下载中断: '+((e&&e.message)?e.message:e)+'（可重试）');
  });
}
function copyText(t){if(navigator.clipboard&&navigator.clipboard.writeText){navigator.clipboard.writeText(t).then(function(){toast('已复制: '+t);},function(){toast(t);});}else{toast(t);}}
function copyDirect(){
  var rel=curRel;if(!rel)return;
  var u=dlUrl(rel);if(u.indexOf('http')!==0)u=location.origin+u;
  copyText(u);
}
document.getElementById('btn-refresh').addEventListener('click',load);
document.getElementById('m-close').addEventListener('click',closePlayer);
modal.addEventListener('click',function(e){if(e.target===modal)closePlayer();});
document.getElementById('m-dl').addEventListener('click',function(){if(curRel)download(curRel);});
document.getElementById('m-chunk').addEventListener('click',doChunkDownload);
document.getElementById('m-copy').addEventListener('click',copyDirect);
document.getElementById('m-open').addEventListener('click',function(){if(curRel)window.open(playUrl(curRel),'_blank');});
selLink.addEventListener('change',function(){linkMode=selLink.value;});
if(mStar)mStar.addEventListener('click',function(){if(curIndex>=0)toggleStar(curIndex);});
if(mDel)mDel.addEventListener('click',function(){if(curIndex>=0)delItem(curIndex);});
load();
setInterval(load,15000);
</script>
</body>
</html>
        """.trimIndent()
    }
}
