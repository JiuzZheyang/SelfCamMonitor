package com.hpu.selfcammonitor.service

import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
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
    }

    fun setControl(c: StreamControl) {
        this.control = c
    }

    override fun serve(session: IHTTPSession?): Response {
        val s = session
            ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "bad request")

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
        return when {
            uri == "/" || uri == "/index.html" -> htmlPage()
            uri == "/live.m3u8" -> servePlaylist()
            uri.startsWith("/seg/") -> serveSegment(uri)
            uri == "/hls.js" -> serveHlsJs()
            uri == "/video" -> serveVideo()
            uri == "/status" -> serveLegacyStatus()
            uri == "/snapshot" -> serveSnapshot(download = false)
            uri == "/api/state" -> serveState()
            uri == "/api/config" -> serveConfig(s)
            uri == "/api/record" -> serveRecord(s)
            uri == "/api/snapshot" -> serveSnapshot(download = true)
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "404 Not Found")
        }
    }

    // ─── 端点实现 ──────────────────────────────────────────────

    private fun htmlPage(): Response {
        val res = newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", VIEWER_HTML)
        res.addHeader("Cache-Control", "no-store")
        return res
    }

    /** HLS 播放列表（滑动窗口，DVR） */
    private fun servePlaylist(): Response {
        hlsManager.noteClient()
        val pl = hlsManager.playlist()
            ?: return newFixedLengthResponse(
                Response.Status.SERVICE_UNAVAILABLE, "text/plain", "#EXTM3U\n# 等待首个分片..."
            )
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
        val res = newFixedLengthResponse(
            Response.Status.OK, "application/javascript; charset=utf-8",
            ByteArrayInputStream(bytes), bytes.size.toLong()
        )
        res.addHeader("Cache-Control", "public, max-age=86400")
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
                "start" -> ctrl.startManualRecording()
                "stop" -> ctrl.stopManualRecording()
                else -> false
            }
            val result = LinkedHashMap<String, Any?>(ctrl.state())
            result["ok"] = ok
            jsonResponse(toJson(result))
        } catch (e: Exception) {
            jsonResponse("{\"error\":\"${jsonEscape(e.message ?: "record error")}\"}")
        }
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

    /** 极简 JSON 序列化：支持 Map<String,Any?>，值类型为 String/Number/Boolean/List<*> */
    private fun toJson(map: Map<String, Any?>): String = buildString {
        append("{")
        var first = true
        for ((k, v) in map) {
            if (!first) append(",")
            first = false
            append("\"").append(jsonEscape(k)).append("\":")
            when (v) {
                null -> append("null")
                is Number, is Boolean -> append(v.toString())
                is List<*> -> {
                    append("[")
                    append(v.joinToString(",") { "\"" + jsonEscape(it.toString()) + "\"" })
                    append("]")
                }
                else -> append("\"").append(jsonEscape(v.toString())).append("\"")
            }
        }
        append("}")
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
main{flex:1;display:flex;flex-direction:column;min-height:0}
#stage{position:relative;flex:1;min-height:0;display:flex;align-items:center;justify-content:center;overflow:hidden;background:#000;touch-action:none}
#img{display:block;max-width:100%;max-height:100%;object-fit:contain;-webkit-user-drag:none;user-select:none;transform-origin:center center;will-change:transform}
#video{display:none;width:100%;height:100%;object-fit:contain;background:#000;transform-origin:center center}
#overlay{position:absolute;top:50%;left:50%;transform:translate(-50%,-50%);text-align:center;color:#7c8798;font-size:15px;display:none;z-index:5}
#overlay.show{display:block}
.vtoolbar{position:absolute;left:50%;bottom:14px;transform:translateX(-50%);display:flex;gap:8px;z-index:6}
.iconbtn{width:44px;height:44px;border-radius:50%;border:1px solid rgba(255,255,255,.18);background:rgba(20,25,32,.72);color:#e6ebf2;font-size:17px;cursor:pointer;display:flex;align-items:center;justify-content:center;backdrop-filter:blur(6px);touch-action:manipulation}
.iconbtn:active{background:rgba(59,130,246,.85)}
.iconbtn.rec.on{background:var(--err);border-color:var(--err)}
.vtoolbar.up{bottom:70px}
#dvr{position:absolute;left:12px;right:12px;bottom:12px;display:none;align-items:center;gap:10px;background:rgba(20,25,32,.74);border:1px solid rgba(255,255,255,.14);border-radius:12px;padding:8px 12px;z-index:7;backdrop-filter:blur(6px)}
#dvr.show{display:flex}
.dvr-live{flex-shrink:0;font-size:11px;font-weight:700;color:var(--mut);background:var(--card2);border:1px solid var(--line);border-radius:999px;padding:5px 11px;cursor:pointer;letter-spacing:.5px}
.dvr-live.on{color:#fff;background:var(--err);border-color:var(--err)}
.dvr-live.off{color:#111;background:var(--warn);border-color:var(--warn)}
#dvr-seek{flex:1;min-width:0;background:transparent;border:none;height:26px;cursor:pointer}
.dvr-time{flex-shrink:0;font-size:12px;color:var(--mut);white-space:nowrap;font-variant-numeric:tabular-nums}
#panel{flex-shrink:0;background:var(--card);border-top:1px solid var(--line);padding:12px 14px;display:flex;flex-direction:column;gap:12px;max-height:46vh;overflow-y:auto}
.prow{display:flex;align-items:center;gap:10px}
.prow label.k{width:64px;flex-shrink:0;font-size:13px;color:var(--mut)}
select,input[type=range]{flex:1;min-width:0;background:var(--card2);color:var(--fg);border:1px solid var(--line);border-radius:8px;padding:8px 10px;font-size:14px;outline:none}
input[type=range]{padding:0;height:28px;background:transparent;border:none}
.val{min-width:56px;text-align:right;font-size:13px;color:var(--mut);flex-shrink:0}
.switch{position:relative;width:46px;height:26px;flex-shrink:0}
.switch input{opacity:0;width:0;height:0}
.switch span{position:absolute;inset:0;background:var(--card2);border:1px solid var(--line);border-radius:999px;transition:.2s}
.switch span:before{content:"";position:absolute;width:18px;height:18px;left:3px;top:2px;background:var(--mut);border-radius:50%;transition:.2s}
.switch input:checked+span{background:var(--acc);border-color:var(--acc)}
.switch input:checked+span:before{transform:translateX(20px);background:#fff}
.pinfo{font-size:12px;color:var(--mut);line-height:1.6}
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
<button class="iconbtn" id="btn-fs" title="全屏">&#9974;</button>
</div>
</div>
<aside id="panel">
<div class="prow"><label class="k">流畅度</label><select id="sel-buf"><option value="1">低延迟</option><option value="2" selected>平衡</option><option value="4">流畅（大缓冲）</option></select></div>
<div class="prow"><label class="k">分辨率</label><select id="sel-res"></select></div>
<div class="prow"><label class="k">帧率</label><input id="rng-fps" type="range" min="1" max="30" step="1" value="16"><span class="val" id="lbl-fps">16 fps</span></div>
<div class="prow"><label class="k">摄像头</label><select id="sel-face"><option value="0">后置</option><option value="1">前置</option></select></div>
<div class="prow"><label class="k">录像模式</label><select id="sel-mode"><option value="2">仅预览</option><option value="0">连续录像</option><option value="1">运动触发</option></select></div>
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
pinfo=document.getElementById('pinfo'),toastEl=document.getElementById('toast');
var dvrEl=document.getElementById('dvr'),dvSeek=document.getElementById('dvr-seek'),
dvLive=document.getElementById('dvr-live'),dvTime=document.getElementById('dvr-time'),
vtEl=document.getElementById('vtoolbar');
var dragging=false,hlsFallbackTimer=null;
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
function refresh(d){
  if(!d||d.error)return;
  lastState=d;
  bFps.textContent=(d.currentFps||0)+' fps';
  if(typeof d.netRateBps==='number')bNet.textContent=fmtRate(d.netRateBps);
  bRes.textContent=d.resolution||'--';
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
    hls=new Hls({lowLatencyMode:true,liveSyncDurationCount:parseInt(selBuf.value,10)||2,
      maxBufferLength:15,maxMaxBufferLength:60,backBufferLength:120,enableWorker:true,
      liveDurationInfinity:true,manifestLoadingMaxRetry:10,levelLoadingMaxRetry:10,fragLoadingMaxRetry:10});
    hls.on(Hls.Events.ERROR,function(ev,data){
      if(data&&data.fatal){
        if(data.type==='networkError'){setStatus('disconnected');}
        else{toast('HLS 错误，回退 MJPEG');startMjpeg();}
      }
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
    }
}
