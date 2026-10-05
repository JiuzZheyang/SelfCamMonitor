package com.hpu.selfcammonitor.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.hpu.selfcammonitor.R
import com.hpu.selfcammonitor.ui.MainActivity
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 内网穿透服务：支持 cloudflared (Cloudflare Tunnel) 和 frp 两种模式。
 *
 * 二进制以 jniLibs 下的 lib*.so 形式打包（libcloudflared.so / libfrpc.so），安装时被系统解压到
 * applicationInfo.nativeLibraryDir（只读 + 可执行），以此绕开 Android 10+ 禁止从 app
 * 数据目录执行程序（W^X）的限制。
 *
 * cloudflared: 使用 Tunnel Token 认证（`tunnel --token <token>`）。
 * frp:         生成 frpc.ini，支持 TCP（remote_port）与 HTTP（custom_domains）两种代理。
 */
class TunnelService : Service() {

    private lateinit var executor: ExecutorService

    @Volatile private var cloudflaredProcess: Process? = null
    @Volatile private var frpcProcess: Process? = null

    @Volatile private var currentTunnelType: String = TYPE_NONE
    @Volatile private var tunnelUrl: String = ""

    private val isRunning = AtomicBoolean(false)

    private val channelId = "tunnel_service_channel"
    private val notificationId = 2

    companion object {
        const val TAG = "TunnelService"

        const val ACTION_START = "com.hpu.selfcammonitor.tunnel.START"
        const val ACTION_STOP = "com.hpu.selfcammonitor.tunnel.STOP"
        const val ACTION_RESTART = "com.hpu.selfcammonitor.tunnel.RESTART"

        const val TYPE_NONE = "none"
        const val TYPE_CLOUDFLARED = "cloudflared"
        const val TYPE_FRP = "frp"

        const val EXTRA_TUNNEL_TYPE = "tunnel_type"
        const val EXTRA_CLOUDFLARED_TOKEN = "cloudflared_token"
        const val EXTRA_FRP_SERVER = "frp_server"
        const val EXTRA_FRP_SERVER_PORT = "frp_server_port"
        const val EXTRA_FRP_TOKEN = "frp_token"
        const val EXTRA_FRP_LOCAL_IP = "frp_local_ip"
        const val EXTRA_FRP_LOCAL_PORT = "frp_local_port"
        const val EXTRA_FRP_SUBDOMAIN = "frp_subdomain"
        const val EXTRA_FRP_DOMAIN = "frp_domain"
        const val EXTRA_FRP_PROTOCOL = "frp_protocol"
        const val EXTRA_FRP_REMOTE_PORT = "frp_remote_port"

        const val BROADCAST_TUNNEL_STATUS = "com.hpu.selfcammonitor.TUNNEL_STATUS"

        @Volatile private var instance: TunnelService? = null

        fun isTunnelRunning(): Boolean = instance?.isRunning?.get() == true
        fun getTunnelUrl(): String = instance?.tunnelUrl ?: ""
        fun getTunnelType(): String = instance?.currentTunnelType ?: TYPE_NONE

        fun startTunnel(context: Context, type: String, extras: Map<String, String> = emptyMap()) {
            val intent = Intent(context, TunnelService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_TUNNEL_TYPE, type)
                extras.forEach { putExtra(it.key, it.value) }
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stopTunnel(context: Context) {
            val intent = Intent(context, TunnelService::class.java).apply { action = ACTION_STOP }
            context.startService(intent)
        }

        fun restartTunnel(context: Context) {
            val intent = Intent(context, TunnelService::class.java).apply { action = ACTION_RESTART }
            ContextCompat.startForegroundService(context, intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        executor = Executors.newSingleThreadExecutor()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // intent 为 null：服务被系统以 START_STICKY 重启，从持久化配置恢复
        if (intent == null) {
            updateNotification("正在恢复内网穿透...")
            startFromPrefs()
            return START_STICKY
        }
        when (intent.action) {
            ACTION_START -> {
                // 必须在 5 秒内 startForeground，否则系统抛异常
                updateNotification("正在启动内网穿透...")
                when (intent.getStringExtra(EXTRA_TUNNEL_TYPE)) {
                    TYPE_CLOUDFLARED -> startCloudflared(intent.getStringExtra(EXTRA_CLOUDFLARED_TOKEN) ?: "")
                    TYPE_FRP -> startFrpc(
                        server = intent.getStringExtra(EXTRA_FRP_SERVER) ?: "",
                        serverPort = intent.getStringExtra(EXTRA_FRP_SERVER_PORT)?.toIntOrNull() ?: 7000,
                        token = intent.getStringExtra(EXTRA_FRP_TOKEN) ?: "",
                        localIp = intent.getStringExtra(EXTRA_FRP_LOCAL_IP) ?: "127.0.0.1",
                        localPort = intent.getStringExtra(EXTRA_FRP_LOCAL_PORT)?.toIntOrNull() ?: 8080,
                        subdomain = intent.getStringExtra(EXTRA_FRP_SUBDOMAIN) ?: "",
                        domain = intent.getStringExtra(EXTRA_FRP_DOMAIN) ?: "",
                        protocol = intent.getStringExtra(EXTRA_FRP_PROTOCOL) ?: "http",
                        remotePort = intent.getStringExtra(EXTRA_FRP_REMOTE_PORT)?.toIntOrNull() ?: 0,
                    )
                }
            }
            ACTION_RESTART -> {
                updateNotification("正在重启内网穿透...")
                killProcesses()
                startFromPrefs()
            }
            ACTION_STOP -> stopAll()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        // 只清理进程，不再广播（避免覆盖失败/停止原因）
        killProcesses()
        currentTunnelType = TYPE_NONE
        instance = null
        executor.shutdown()
    }

    /** 从 SharedPreferences 读取配置并按需启动对应穿透 */
    private fun startFromPrefs() {
        val prefs = getSharedPreferences("camera_prefs", MODE_PRIVATE)
        when (prefs.getString("tunnel_type", TYPE_NONE)) {
            TYPE_CLOUDFLARED -> startCloudflared(prefs.getString("cloudflared_token", "") ?: "")
            TYPE_FRP -> startFrpc(
                server = prefs.getString("frp_server", "") ?: "",
                serverPort = prefs.getString("frp_server_port", "7000")?.toIntOrNull() ?: 7000,
                token = prefs.getString("frp_token", "") ?: "",
                localIp = prefs.getString("frp_local_ip", "127.0.0.1") ?: "127.0.0.1",
                localPort = prefs.getString("frp_local_port", "8080")?.toIntOrNull() ?: 8080,
                subdomain = prefs.getString("frp_subdomain", "") ?: "",
                domain = prefs.getString("frp_domain", "") ?: "",
                protocol = prefs.getString("frp_protocol", "http") ?: "http",
                remotePort = prefs.getString("frp_remote_port", "0")?.toIntOrNull() ?: 0,
            )
        }
    }

    // ─── cloudflared ───────────────────────────────────────────────

    private fun startCloudflared(token: String) {
        if (token.isBlank()) {
            failAndStop(TYPE_CLOUDFLARED, "Token 为空，请先在设置中配置")
            return
        }
        killProcesses()
        currentTunnelType = TYPE_CLOUDFLARED
        tunnelUrl = ""

        executor.execute {
            val binary = prepareBinary("libcloudflared.so") ?: run {
                failAndStop(TYPE_CLOUDFLARED, "找不到 cloudflared 可执行文件")
                return@execute
            }

            val cmd = listOf(binary.absolutePath, "--no-autoupdate", "tunnel", "--token", token.trim())
            Log.d(TAG, "启动 cloudflared: $cmd")
            updateNotification("正在连接 Cloudflare...")

            val proc = try {
                ProcessBuilder(cmd)
                    .directory(filesDir)
                    .apply { environment()["HOME"] = filesDir.absolutePath; redirectErrorStream(true) }
                    .start()
            } catch (e: Exception) {
                Log.e(TAG, "cloudflared 启动失败", e)
                failAndStop(TYPE_CLOUDFLARED, e.message ?: "启动失败")
                return@execute
            }

            cloudflaredProcess = proc
            isRunning.set(true)

            var lastErr = ""
            var connected = false
            readLogs(proc, "cloudflared") { line ->
                if (!connected && line.contains("registered tunnel connection", ignoreCase = true)) {
                    connected = true
                    val domain = extractDomain(line)
                    tunnelUrl = when {
                        domain.startsWith("http") -> domain
                        domain.isNotEmpty() -> "https://$domain"
                        else -> "已连接 Cloudflare Tunnel"
                    }
                    updateNotification("穿透运行中: $tunnelUrl")
                    broadcastStatus(TYPE_CLOUDFLARED, tunnelUrl, true, "")
                }
                if (line.contains("err", ignoreCase = true) || line.contains("failed", ignoreCase = true)) lastErr = line
            }

            onProcessEnded(proc) {
                cloudflaredProcess = null
                broadcastStatus(TYPE_CLOUDFLARED, tunnelUrl, false, lastErr.ifBlank { "连接已断开" })
            }
        }
    }

    private fun extractDomain(line: String): String {
        val patterns = listOf(
            Regex("(https?://[^\\s]+\\.trycloudflare\\.com[^\\s]*)"),
            Regex("(https?://[^\\s]+\\.cfargotunnel\\.com[^\\s]*)"),
        )
        for (p in patterns) {
            val m = p.find(line) ?: continue
            val d = m.groupValues.getOrNull(1) ?: continue
            if (d.isNotBlank()) return d.replace("https://", "").replace("http://", "").trim()
        }
        return ""
    }

    // ─── frp ─────────────────────────────────────────────────────

    private fun startFrpc(
        server: String, serverPort: Int, token: String, localIp: String, localPort: Int,
        subdomain: String, domain: String, protocol: String, remotePort: Int,
    ) {
        if (server.isBlank()) {
            failAndStop(TYPE_FRP, "frps 服务器地址为空")
            return
        }
        killProcesses()
        currentTunnelType = TYPE_FRP
        tunnelUrl = ""

        executor.execute {
            val binary = prepareBinary("libfrpc.so") ?: run {
                failAndStop(TYPE_FRP, "找不到 frpc 可执行文件")
                return@execute
            }

            val frpcDir = File(filesDir, "frpc").apply { mkdirs() }
            val iniFile = File(frpcDir, "frpc.ini")
            val customDomains = if (subdomain.isNotBlank() && domain.isNotBlank()) "$subdomain.$domain" else ""
            val isHttp = protocol == "http" && customDomains.isNotBlank()

            val iniContent = buildString {
                appendLine("[common]")
                appendLine("server_addr = $server")
                appendLine("server_port = $serverPort")
                if (token.isNotBlank()) appendLine("token = $token")
                appendLine("protocol = tcp")
                appendLine("log_level = info")
                appendLine()
                if (isHttp) {
                    appendLine("[web]")
                    appendLine("type = http")
                    appendLine("local_ip = $localIp")
                    appendLine("local_port = $localPort")
                    appendLine("custom_domains = $customDomains")
                } else {
                    appendLine("[camera_tcp]")
                    appendLine("type = tcp")
                    appendLine("local_ip = $localIp")
                    appendLine("local_port = $localPort")
                    appendLine("remote_port = ${if (remotePort > 0) remotePort else 0}")
                }
            }
            iniFile.writeText(iniContent)
            Log.d(TAG, "frpc.ini:\n$iniContent")

            val cmd = listOf(binary.absolutePath, "-c", iniFile.absolutePath)
            Log.d(TAG, "启动 frpc: $cmd")
            updateNotification("正在连接 frps $server...")

            val proc = try {
                ProcessBuilder(cmd)
                    .directory(frpcDir)
                    .apply { environment()["HOME"] = filesDir.absolutePath; redirectErrorStream(true) }
                    .start()
            } catch (e: Exception) {
                Log.e(TAG, "frpc 启动失败", e)
                failAndStop(TYPE_FRP, e.message ?: "启动失败")
                return@execute
            }

            frpcProcess = proc
            isRunning.set(true)

            val httpUrl = "http://$customDomains"
            var announced = ""
            var lastErr = ""

            // url 变化时才广播（登录成功 → 拿到真实远程端口可能两次）
            val announce: (String) -> Unit = { url ->
                if (url != announced) {
                    announced = url
                    tunnelUrl = url
                    updateNotification("穿透运行中: $url")
                    broadcastStatus(TYPE_FRP, url, true, "")
                }
            }

            readLogs(proc, "frpc") { line ->
                if (line.contains("start proxy success", ignoreCase = true) ||
                    (line.contains("login", ignoreCase = true) && line.contains("success", ignoreCase = true))) {
                    announce(
                        if (isHttp) httpUrl
                        else if (remotePort > 0) "$server:$remotePort"
                        else "$server:?"
                    )
                }
                // frps 分配的远程端口（"remote port = 12345" 之类）
                val portMatch = Regex("remote[_ ]port\\D{0,4}(\\d{2,5})").find(line)
                if (portMatch != null && !isHttp) {
                    announce("$server:${portMatch.groupValues[1]}")
                }
                if (line.contains("err", ignoreCase = true) || line.contains("failed", ignoreCase = true)) lastErr = line
            }

            onProcessEnded(proc) {
                frpcProcess = null
                broadcastStatus(TYPE_FRP, tunnelUrl, false, lastErr.ifBlank { "连接已断开" })
            }
        }
    }

    // ─── 通用 ─────────────────────────────────────────────────────

    /** 从 nativeLibraryDir 取可执行二进制 */
    private fun prepareBinary(name: String): File? {
        val f = File(applicationInfo.nativeLibraryDir, name)
        if (!f.exists()) {
            Log.e(TAG, "nativeLibraryDir 中找不到 $name")
            return null
        }
        f.setExecutable(true)
        return f
    }

    /** 阻塞式读取子进程输出，直到进程结束或流关闭（无忙等/轮询） */
    private fun readLogs(proc: Process, tag: String, onLine: (String) -> Unit) {
        try {
            BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    Log.d(TAG, "[$tag] $line")
                    try { onLine(line) } catch (e: Exception) { Log.w(TAG, "处理日志行失败", e) }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "读取 $tag 日志中断: ${e.message}")
        }
    }

    /** 进程自然结束时统一收尾（仅当没有任何新进程顶替时生效） */
    private fun onProcessEnded(proc: Process, block: () -> Unit) {
        isRunning.set(false)
        // 若有新进程顶替（或已被 stopAll 清空），不覆盖当前状态
        if (cloudflaredProcess === proc || frpcProcess === proc) {
            block()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun stopAll() {
        killProcesses()
        currentTunnelType = TYPE_NONE
        tunnelUrl = ""
        broadcastStatus(TYPE_NONE, "", false, "已停止")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun killProcesses() {
        isRunning.set(false)
        cloudflaredProcess?.destroy()
        cloudflaredProcess = null
        frpcProcess?.destroy()
        frpcProcess = null
    }

    /** 启动/连接失败：广播错误并停掉前台服务，避免通知栏卡在“正在启动” */
    private fun failAndStop(type: String, msg: String) {
        isRunning.set(false)
        broadcastStatus(type, "", false, msg)
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } catch (_: Exception) {
        }
    }

    private fun createNotificationChannel() {
        // NotificationChannel 为 API 26+，低版本无此概念，直接跳过
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(channelId, "内网穿透服务", NotificationManager.IMPORTANCE_LOW)
            .apply { description = "保持内网穿透后台运行" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun updateNotification(text: String) {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("内网穿透")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_tunnel)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
        startForeground(notificationId, notification)
    }

    private fun broadcastStatus(type: String, url: String, running: Boolean, error: String) {
        sendBroadcast(
            Intent(BROADCAST_TUNNEL_STATUS).apply {
                setPackage(packageName)
                putExtra("type", type)
                putExtra("url", url)
                putExtra("running", running)
                putExtra("error", error)
            }
        )
    }
}
