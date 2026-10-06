package com.hpu.selfcammonitor.service

import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.URLDecoder
import android.util.Base64
import com.hpu.selfcammonitor.utils.H264Streamer
import com.hpu.selfcammonitor.utils.MJPEGStreamer

class StreamServer(port: Int = 8080) : NanoHTTPD(port) {

    private lateinit var mjpegStreamer: MJPEGStreamer
    private lateinit var h264Streamer: H264Streamer

    var isMjpegEnabled: Boolean = true

    var username: String? = null
    var password: String? = null

    /** 网页端控制入口（由 CameraService 注入） */
    @Volatile
    private var control: StreamControl? = null

    fun setMJPEGStreamer(streamer: MJPEGStreamer) {
        this.mjpegStreamer = streamer
    }

    fun setH264Streamer(streamer: H264Streamer) {
        this.h264Streamer = streamer
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

        return when (s.uri) {
            "/", "/index.html" -> htmlPage()
            "/h264" -> serveH264()
            "/video" -> serveVideo()
            "/status" -> serveLegacyStatus()
            "/snapshot" -> serveSnapshot(download = false)
            "/api/state" -> serveState()
            "/api/config" -> serveConfig(s)
            "/api/record" -> serveRecord(s)
            "/api/snapshot" -> serveSnapshot(download = true)
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "404 Not Found")
        }
    }

    // ─── 端点实现 ──────────────────────────────────────────────

    private fun htmlPage(): Response {
        val res = newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", VIEWER_HTML)
        res.addHeader("Cache-Control", "no-store")
        return res
    }

    /** H.264 低延迟码流（长度前缀帧格式），网页端用 WebCodecs 硬件解码 */
    private fun serveH264(): Response {
        val pipedOut = PipedOutputStream()
        val pipedIn = PipedInputStream(pipedOut, 1 shl 20)
        h264Streamer.addClient(pipedOut)
        val res = newChunkedResponse(Response.Status.OK, "application/octet-stream", pipedIn)
        res.addHeader("Cache-Control", "no-store, no-cache, must-revalidate")
        res.addHeader("X-Accel-Buffering", "no")
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
        val jpeg = mjpegStreamer.getLatestJpeg()
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
#img,#canvas{display:block;max-width:100%;max-height:100%;object-fit:contain;-webkit-user-drag:none;user-select:none;transform-origin:center center;will-change:transform}
#canvas{display:none}
#overlay{position:absolute;top:50%;left:50%;transform:translate(-50%,-50%);text-align:center;color:#7c8798;font-size:15px;display:none;z-index:5}
#overlay.show{display:block}
.vtoolbar{position:absolute;left:50%;bottom:14px;transform:translateX(-50%);display:flex;gap:8px;z-index:6}
.iconbtn{width:44px;height:44px;border-radius:50%;border:1px solid rgba(255,255,255,.18);background:rgba(20,25,32,.72);color:#e6ebf2;font-size:17px;cursor:pointer;display:flex;align-items:center;justify-content:center;backdrop-filter:blur(6px);touch-action:manipulation}
.iconbtn:active{background:rgba(59,130,246,.85)}
.iconbtn.rec.on{background:var(--err);border-color:var(--err)}
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
#panel{width:340px;max-height:none;border-top:none;border-left:1px solid var(--line)}
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
<span class="badge" id="b-res">--</span>
<span class="badge rec" id="b-rec" style="display:none">录制中</span>
</div>
</header>
<main>
<div id="stage">
<img id="img" alt="监控画面" draggable="false">
<canvas id="canvas"></canvas>
<div id="overlay"></div>
<div class="vtoolbar">
<button class="iconbtn" id="btn-r" title="旋转">&#8635;</button>
<button class="iconbtn" id="btn-shot" title="截图">&#128247;</button>
<button class="iconbtn rec" id="btn-rec" title="录像">&#9679;</button>
<button class="iconbtn" id="btn-fs" title="全屏">&#9974;</button>
</div>
</div>
<aside id="panel">
<div class="prow"><label class="k">分辨率</label><select id="sel-res"></select></div>
<div class="prow"><label class="k">帧率</label><input id="rng-fps" type="range" min="1" max="30" step="1" value="16"><span class="val" id="lbl-fps">16 fps</span></div>
<div class="prow"><label class="k">摄像头</label><select id="sel-face"><option value="0">后置</option><option value="1">前置</option></select></div>
<div class="prow"><label class="k">录像模式</label><select id="sel-mode"><option value="2">仅预览</option><option value="0">连续录像</option><option value="1">运动触发</option></select></div>
<div class="prow"><label class="k">MJPEG</label><label class="switch"><input id="sw-mjpeg" type="checkbox"><span></span></label><span class="val" id="lbl-mjpeg">开启</span></div>
<div class="pinfo" id="pinfo"></div>
</aside>
</main>
<div id="toast"></div>
<script>
var img=document.getElementById('img'),canvas=document.getElementById('canvas'),
ctx=canvas.getContext('2d'),stage=document.getElementById('stage'),
dot=document.getElementById('dot'),ov=document.getElementById('overlay');
var selRes=document.getElementById('sel-res'),rngFps=document.getElementById('rng-fps'),
lblFps=document.getElementById('lbl-fps'),selFace=document.getElementById('sel-face'),
swMjpeg=document.getElementById('sw-mjpeg'),lblMjpeg=document.getElementById('lbl-mjpeg'),
selMode=document.getElementById('sel-mode'),btnRec=document.getElementById('btn-rec'),
bFps=document.getElementById('b-fps'),bNet=document.getElementById('b-net'),
bRes=document.getElementById('b-res'),bRec=document.getElementById('b-rec'),
bProto=document.getElementById('b-proto'),
pinfo=document.getElementById('pinfo'),toastEl=document.getElementById('toast');
var HI=2000;
var activeEl=img;
var rot=0,zoom=1,panX=0,panY=0,stat='connecting',wantStream=true,reloadTimer=null;
var resFilled=false,userRec=false,firstLoad=false,lastState=null;
var mode='mjpeg',decoder=null,configuredCodec=null,decodeSeq=0,h264Abort=null,h264Failed=false,h264Timer=null;
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
  activeEl.style.transform='translate('+panX+'px,'+panY+'px) rotate('+rot+'deg) scale('+zoom+')';
}
function fit(){
  var sw=stage.clientWidth,sh=stage.clientHeight;
  if(rot%180===0){activeEl.style.maxWidth='100%';activeEl.style.maxHeight='100%';}
  else{activeEl.style.maxWidth=sh+'px';activeEl.style.maxHeight=sw+'px';}
}
function doRotate(){
  activeEl.style.transition='transform .3s ease';
  rot=(rot+90)%360;zoom=1;panX=0;panY=0;fit();applyTransform();
  setTimeout(function(){activeEl.style.transition='none';},350);
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
  else if(touching&&e.touches.length===1){e.preventDefault();panX+=e.touches[0].clientX-tx;panY+=e.touches[0].clientY-ty;tx=e.touches[0].clientX;ty=e.touches[0].clientY;applyTransform();}
},{passive:false});
stage.addEventListener('touchend',function(e){
  if(e.touches.length<2)pinching=false;
  if(e.touches.length===1&&zoom>1){touching=true;tx=e.touches[0].clientX;ty=e.touches[0].clientY;}
  else if(e.touches.length===0){touching=false;var now=Date.now();if(now-lastTap<300){if(zoom>1.01){zoom=1;panX=0;panY=0;}else{zoom=2;}applyTransform();}lastTap=now;}
});
stage.addEventListener('touchcancel',function(){pinching=false;touching=false;});
stage.addEventListener('wheel',function(e){e.preventDefault();zoom=clampZ(zoom*(e.deltaY<0?1.1:0.9));if(zoom<=1.01){zoom=1;panX=0;panY=0;}applyTransform();},{passive:false});
var mousing=false,mx=0,my=0;
stage.addEventListener('mousedown',function(e){if(zoom>1){mousing=true;mx=e.clientX;my=e.clientY;e.preventDefault();}});
window.addEventListener('mousemove',function(e){if(mousing){panX+=e.clientX-mx;panY+=e.clientY-my;mx=e.clientX;my=e.clientY;applyTransform();}});
window.addEventListener('mouseup',function(){mousing=false;});
stage.addEventListener('dblclick',function(){if(zoom>1.01){zoom=1;panX=0;panY=0;}else{zoom=2;}applyTransform();});
function reload(){
  if(mode!=='mjpeg')return;
  if(!wantStream){img.removeAttribute('src');return;}
  clearTimeout(reloadTimer);
  img.src='/video?t='+Date.now();
}
img.addEventListener('load',function(){if(mode!=='mjpeg')return;firstLoad=true;setStatus('live');});
img.addEventListener('error',function(){
  if(mode!=='mjpeg'||!wantStream)return;
  setStatus('connecting');
  clearTimeout(reloadTimer);reloadTimer=setTimeout(reload,1500);
});
function toggleFs(){
  var e=document.documentElement,fs=document.fullscreenElement||document.webkitFullscreenElement;
  if(!fs){if(e.requestFullscreen)e.requestFullscreen();else if(e.webkitRequestFullscreen)e.webkitRequestFullscreen();}
  else{if(document.exitFullscreen)document.exitFullscreen();else if(document.webkitExitFullscreen)document.webkitExitFullscreen();}
}
function api(path){return fetch(path,{cache:'no-store'}).then(function(r){return r.json();});}
function fmtRate(bps){
  var mb=bps/1048576;
  return mb.toFixed(2)+' MB/s';
}
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
  bProto.textContent=(mode==='h264'?'H.264':'MJPEG');
  var parts=[];
  parts.push('模式: '+(d.modeLabel||'--'));
  if(typeof d.h264Bitrate==='number'&&d.h264Bitrate>0)parts.push('编码: '+(d.h264Bitrate/1000000).toFixed(1)+'Mbps');
  if(typeof d.clientCount==='number')parts.push('观看: '+d.clientCount);
  if(typeof d.netRateBps==='number')parts.push('网络: '+fmtRate(d.netRateBps));
  pinfo.textContent=parts.join('  ·  ');
}
function applyConfig(params,msg){
  var qs=Object.keys(params).map(function(k){return encodeURIComponent(k)+'='+encodeURIComponent(params[k]);}).join('&');
  api('/api/config?'+qs).then(function(d){
    if(d.error){toast('失败: '+d.error);return;}
    refresh(d);toast(msg||'已应用');
  }).catch(function(){toast('设置失败');});
}
function toggleRec(){
  var action=userRec?'stop':'start';
  api('/api/record?action='+action).then(function(d){
    if(d.error){toast('失败: '+d.error);return;}
    refresh(d);toast(action==='start'?'开始录制':'已停止录制');
  }).catch(function(){toast('操作失败');});
}
function snapshot(){
  var a=document.createElement('a');
  a.href='/api/snapshot?t='+Date.now();a.download='snapshot.jpg';
  document.body.appendChild(a);a.click();a.remove();toast('已保存截图');
}
// ── H.264 / WebCodecs 播放 ──
function initDecoder(){
  var codecStr=lastState&&lastState.h264Codec;
  if(!codecStr)return false;
  try{
    if(decoder){try{decoder.close();}catch(_){}decoder=null;}
    decoder=new VideoDecoder({output:onDecoded,error:function(e){onH264Err(e);}});
    decoder.configure({codec:codecStr,optimizeForLatency:true});
    configuredCodec=codecStr;
    return true;
  }catch(e){decoder=null;return false;}
}
function feedUnit(data,key){
  if(!decoder||configuredCodec!==(lastState&&lastState.h264Codec)){if(!initDecoder())return;}
  if(decoder.decodeQueueSize>8&&!key)return;
  try{decoder.decode(new EncodedVideoChunk({type:key?'key':'delta',timestamp:decodeSeq++,data:data}));}catch(e){onH264Err(e);}
}
function onDecoded(frame){
  try{
    if(canvas.width!==frame.displayWidth||canvas.height!==frame.displayHeight){canvas.width=frame.displayWidth;canvas.height=frame.displayHeight;}
    ctx.drawImage(frame,0,0,canvas.width,canvas.height);
  }finally{frame.close();}
  if(stat!=='live'){firstLoad=true;setStatus('live');}
}
function onH264Err(e){
  if(mode!=='h264')return;
  h264Failed=true;
  if(h264Abort)try{h264Abort.abort();}catch(_){}
  try{if(decoder)decoder.close();}catch(_){}
  decoder=null;
  toast('H.264 解码不可用，切换到 MJPEG');
  startMjpeg();
}
function openH264(){
  if(h264Abort)try{h264Abort.abort();}catch(_){}
  h264Abort=new AbortController();
  fetch('/h264',{cache:'no-store',signal:h264Abort.signal}).then(function(resp){
    if(!resp.ok||!resp.body)throw new Error('http '+resp.status);
    var reader=resp.body.getReader();
    var acc=new Uint8Array(1<<16),accLen=0;
    function pump(){
      reader.read().then(function(r){
        if(r.done)throw new Error('closed');
        var v=r.value;
        if(accLen+v.length>acc.length){var n=new Uint8Array(Math.max(acc.length*2,accLen+v.length));n.set(acc.subarray(0,accLen));acc=n;}
        acc.set(v,accLen);accLen+=v.length;
        var off=0;
        while(accLen-off>=5){
          var len=((acc[off]<<24)|(acc[off+1]<<16)|(acc[off+2]<<8)|acc[off+3])>>>0;
          if(accLen-off-5<len)break;
          var key=(acc[off+4]&1)===1;
          var payload=acc.slice(off+5,off+5+len);
          feedUnit(payload,key);
          off+=5+len;
        }
        if(off>0){acc.copyWithin(0,off,accLen);accLen-=off;}
        pump();
      }).catch(function(e){if(e&&e.name==='AbortError')return;onH264Err(e);});
    }
    pump();
  }).catch(function(e){if(e&&e.name==='AbortError')return;onH264Err(e);});
}
function startH264(){
  mode='h264';
  img.style.display='none';canvas.style.display='block';activeEl=canvas;
  setStatus('connecting');
  img.removeAttribute('src');
  bProto.textContent='H.264';
  openH264();
  clearTimeout(h264Timer);
  h264Timer=setTimeout(function(){
    if(mode==='h264'&&(!decoder||stat!=='live')){toast('H.264 无响应，切换到 MJPEG');startMjpeg();}
  },6000);
}
function startMjpeg(){
  mode='mjpeg';
  if(h264Abort)try{h264Abort.abort();}catch(_){}
  try{if(decoder)decoder.close();}catch(_){}
  decoder=null;
  canvas.style.display='none';img.style.display='block';activeEl=img;
  bProto.textContent='MJPEG';
  setStatus('connecting');
  reload();
}
function chooseMode(){
  api('/api/state').then(function(d){
    lastState=d;
    if(!h264Failed&&('VideoDecoder' in window)&&d.h264Enabled){
      startH264();
    }else{
      startMjpeg();
    }
  }).catch(function(){startMjpeg();});
}
function heartbeat(){
  api('/api/state').then(function(d){
    refresh(d);
    if(mode==='h264'){
      if(d.h264Enabled===false){startMjpeg();return;}
      if(!d.h264Ready&&stat!=='live'){/* 等编码器就绪 */}
      return;
    }
    if(d&&d.mjpegEnabled===false){
      if(wantStream){wantStream=false;img.removeAttribute('src');}
      setStatus('off');
      return;
    }
    if(!wantStream){wantStream=true;reload();}
    if(stat==='off'){setStatus('connecting');reload();}
    if(stat!=='live'&&!firstLoad){setStatus('connecting');}
  }).catch(function(){
    if(stat!=='off')setStatus('disconnected');
  });
}
document.getElementById('btn-r').addEventListener('click',doRotate);
document.getElementById('btn-fs').addEventListener('click',toggleFs);
document.getElementById('btn-shot').addEventListener('click',snapshot);
btnRec.addEventListener('click',toggleRec);
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
window.addEventListener('resize',function(){fit();applyTransform();});
document.addEventListener('fullscreenchange',function(){fit();applyTransform();});
document.addEventListener('webkitfullscreenchange',function(){fit();applyTransform();});
chooseMode();
heartbeat();
setInterval(heartbeat,HI);
</script>
</body>
</html>
        """.trimIndent()
    }
}
