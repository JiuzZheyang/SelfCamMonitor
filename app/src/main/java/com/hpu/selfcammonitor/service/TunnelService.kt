package com.hpu.selfcammonitor.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.hpu.selfcammonitor.R
import com.hpu.selfcammonitor.ui.MainActivity
import java.io.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 内网穿透服务：支持 cloudflared (Cloudflare Tunnel) 和 frp 两种模式
 *
 * cloudflared:
 *   - 认证方式: Tunnel Token（在设置中直接填入）
 *   - 二进制: 从 app assets/bin/cloudflared 提取到 app 私有目录
 *
 * frp:
 *   - 认证方式: frps 服务器地址 + 认证令牌
 *   - 二进制: 从 app assets/bin/frpc 提取到 app 私有目录
 */
class TunnelService : Service() {

    private lateinit var executor: ExecutorService
    private val handler = Handler(Looper.getMainLooper())

    private var cloudflaredProcess: Process? = null
    private var frpcProcess: Process? = null

    private var currentTunnelType: String = TYPE_NONE
    private var tunnelUrl: String = ""
    private var lastError: String = ""

    private val isRunning = AtomicBoolean(false)

    private val CHANNEL_ID = "tunnel_service_channel"
    private val NOTIFICATION_ID = 2

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

        const val BROADCAST_TUNNEL_STATUS = "com.hpu.selfcammonitor.TUNNEL_STATUS"

        @Volatile var instance: TunnelService? = null
            private set

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
        when (intent?.action) {
            ACTION_START -> {
                // 必须在 5 秒内 startForeground，否则系统抛异常
                updateNotification("正在启动内网穿透...")
                val type = intent.getStringExtra(EXTRA_TUNNEL_TYPE) ?: TYPE_NONE
                if (type == TYPE_CLOUDFLARED) {
                    startCloudflared(intent.getStringExtra(EXTRA_CLOUDFLARED_TOKEN) ?: "")
                } else if (type == TYPE_FRP) {
                    startFrpc(
                        intent.getStringExtra(EXTRA_FRP_SERVER) ?: "",
                        intent.getStringExtra(EXTRA_FRP_SERVER_PORT)?.toIntOrNull() ?: 7000,
                        intent.getStringExtra(EXTRA_FRP_TOKEN) ?: "",
                        intent.getStringExtra(EXTRA_FRP_LOCAL_IP) ?: "127.0.0.1",
                        intent.getStringExtra(EXTRA_FRP_LOCAL_PORT)?.toIntOrNull() ?: 8080,
                        intent.getStringExtra(EXTRA_FRP_SUBDOMAIN) ?: "",
                        intent.getStringExtra(EXTRA_FRP_DOMAIN) ?: "",
                        intent.getStringExtra(EXTRA_FRP_PROTOCOL) ?: "http"
                    )
                }
            }
            ACTION_RESTART -> {
                updateNotification("正在重启内网穿透...")
                killProcesses()
                val prefs = getSharedPreferences("camera_prefs", MODE_PRIVATE)
                val type = prefs.getString("tunnel_type", TYPE_NONE) ?: TYPE_NONE
                if (type == TYPE_CLOUDFLARED) {
                    startCloudflared(prefs.getString("cloudflared_token", "") ?: "")
                } else if (type == TYPE_FRP) {
                    startFrpc(
                        prefs.getString("frp_server", "") ?: "",
                        prefs.getString("frp_server_port", "7000")?.toIntOrNull() ?: 7000,
                        prefs.getString("frp_token", "") ?: "",
                        prefs.getString("frp_local_ip", "127.0.0.1") ?: "127.0.0.1",
                        prefs.getString("frp_local_port", "8080")?.toIntOrNull() ?: 8080,
                        prefs.getString("frp_subdomain", "") ?: "",
                        prefs.getString("frp_domain", "") ?: "",
                        prefs.getString("frp_protocol", "http") ?: "http"
                    )
                }
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

    // ─── cloudflared ───────────────────────────────────────────────

    private fun startCloudflared(token: String) {
        if (token.isBlank()) {
            failAndStop(TYPE_CLOUDFLARED, "Token 为空，请先在设置中配置")
            return
        }
        killProcesses()
        currentTunnelType = TYPE_CLOUDFLARED

        executor.execute {
            try {
                val cfBinary = prepareCloudflaredBinary()
                if (cfBinary == null) {
                    failAndStop(TYPE_CLOUDFLARED, "找不到 cloudflared 可执行文件")
                    return@execute
                }

                val cmd = listOf(cfBinary.absolutePath, "--no-autoupdate", "tunnel", "--token", token.trim())
                Log.d(TAG, "启动 cloudflared: $cmd")
                updateNotification("正在连接 Cloudflare...")

                val pb = ProcessBuilder(cmd).directory(filesDir)
                pb.environment()["HOME"] = filesDir.absolutePath
                pb.redirectErrorStream(true)
                cloudflaredProcess = pb.start()
                isRunning.set(true)

                val reader = BufferedReader(InputStreamReader(cloudflaredProcess!!.inputStream))
                while (true) {
                    try { cloudflaredProcess!!.exitValue(); break } catch (_: IllegalThreadStateException) { }
                    val line = reader.readLine() ?: break
                    Log.d(TAG, "[cloudflared] $line")
                    if (line.contains("Registered tunnel connection", ignoreCase = true) || line.contains("unnel running", ignoreCase = true) || line.contains("registered", ignoreCase = true)) {
                        val domain = extractDomainFromLog(line)
                        tunnelUrl = when {
                            domain.startsWith("http") -> domain
                            domain.isNotEmpty() -> "https://$domain"
                            else -> "已连接 Cloudflare Tunnel"
                        }
                        updateNotification("穿透运行中: $tunnelUrl")
                        broadcastStatus(TYPE_CLOUDFLARED, tunnelUrl, true, "")
                    }
                    if (line.contains("error", ignoreCase = true) || line.contains("failed", ignoreCase = true)) lastError = line
                    Thread.sleep(200)
                }
                reader.close()
            } catch (e: Exception) {
                Log.e(TAG, "cloudflared 启动失败", e)
                failAndStop(TYPE_CLOUDFLARED, e.message ?: "启动失败")
            } finally {
                isRunning.set(false)
            }
        }
    }

    private fun prepareCloudflaredBinary(): File? {
        val f = File(applicationInfo.nativeLibraryDir, "libcloudflared.so")
        if (!f.exists()) {
            Log.e(TAG, "nativeLibraryDir 中找不到 libcloudflared.so")
            return null
        }
        f.setExecutable(true)
        return f
    }

    private fun extractDomainFromLog(line: String): String {
        val patterns = listOf(
            Regex("(https?://[^\\s]+\\.trycloudflare\\.com[^\\s]*)"),
            Regex("(https?://[^\\s]+\\.cfms\\.io[^\\s]*)"),
            Regex("(https?://[^\\s]+-[a-z0-9]+\\.trycloudflare\\.com)")
        )
        for (pattern in patterns) {
            val m = pattern.find(line)
            if (m != null) {
                var d = m.groupValues.getOrNull(1) ?: ""
                if (d.isBlank()) d = m.groupValues.getOrNull(2) ?: ""
                if (d.isNotBlank()) return d.replace("https://", "").replace("http://", "").trim()
            }
        }
        return ""
    }

    // ─── frp ─────────────────────────────────────────────────────

    private fun startFrpc(server: String, serverPort: Int, token: String, localIp: String, localPort: Int, subdomain: String, domain: String, protocol: String) {
        if (server.isBlank()) {
            failAndStop(TYPE_FRP, "frps 服务器地址为空")
            return
        }
        killProcesses()
        currentTunnelType = TYPE_FRP

        executor.execute {
            try {
                val frpcBinary = prepareFrpcBinary()
                if (frpcBinary == null) {
                    failAndStop(TYPE_FRP, "找不到 frpc 可执行文件")
                    return@execute
                }

                val frpcDir = File(filesDir, "frpc").also { it.mkdirs() }
                val iniFile = File(frpcDir, "frpc.ini")
                val customDomains = if (subdomain.isNotBlank() && domain.isNotBlank()) "$subdomain.$domain" else ""

                val iniContent = buildString {
                    appendLine("[common]")
                    appendLine("server_addr = $server")
                    appendLine("server_port = $serverPort")
                    appendLine("token = $token")
                    appendLine("protocol = tcp")
                    appendLine()
                    if (protocol == "http" && customDomains.isNotBlank()) {
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
                        appendLine("remote_port = 0")
                    }
                }
                iniFile.writeText(iniContent)
                Log.d(TAG, "frpc.ini: $iniContent")

                val cmd = listOf(frpcBinary.absolutePath, "-c", iniFile.absolutePath)
                Log.d(TAG, "启动 frpc: $cmd")
                updateNotification("正在连接 frps $server...")

                val pb = ProcessBuilder(cmd).directory(frpcDir)
                pb.environment()["HOME"] = filesDir.absolutePath
                pb.redirectErrorStream(true)
                frpcProcess = pb.start()
                isRunning.set(true)

                val reader = BufferedReader(InputStreamReader(frpcProcess!!.inputStream))
                while (true) {
                    try { frpcProcess!!.exitValue(); break } catch (_: IllegalThreadStateException) { }
                    val line = reader.readLine() ?: break
                    Log.d(TAG, "[frpc] $line")
                    if (line.contains("start proxy success", ignoreCase = true)) {
                        tunnelUrl = if (protocol == "http" && customDomains.isNotBlank()) "http://$customDomains" else "已连接 (frp)"
                        updateNotification("穿透运行中: $tunnelUrl")
                        broadcastStatus(TYPE_FRP, tunnelUrl, true, "")
                    }
                    if (line.contains("login", ignoreCase = true) && line.contains("success", ignoreCase = true)) {
                        tunnelUrl = if (protocol == "http" && customDomains.isNotBlank()) "http://$customDomains" else "登录成功"
                        updateNotification("穿透运行中: $tunnelUrl")
                        broadcastStatus(TYPE_FRP, tunnelUrl, true, "")
                    }
                    val portMatch = Regex("remote_port\\s*=\\s*(\\d{4,5})").find(line)
                    if (portMatch != null) {
                        tunnelUrl = "$server:${portMatch.groupValues[1]}"
                        updateNotification("穿透运行中: $tunnelUrl")
                        broadcastStatus(TYPE_FRP, tunnelUrl, true, "")
                    }
                    Thread.sleep(200)
                }
                reader.close()
            } catch (e: Exception) {
                Log.e(TAG, "frpc 启动失败", e)
                failAndStop(TYPE_FRP, e.message ?: "启动失败")
            } finally {
                isRunning.set(false)
            }
        }
    }

    private fun prepareFrpcBinary(): File? {
        val f = File(applicationInfo.nativeLibraryDir, "libfrpc.so")
        if (!f.exists()) {
            Log.e(TAG, "nativeLibraryDir 中找不到 libfrpc.so")
            return null
        }
        f.setExecutable(true)
        return f
    }

    // ─── 通用 ─────────────────────────────────────────────────────

    private fun stopAll() {
        killProcesses()
        currentTunnelType = TYPE_NONE; tunnelUrl = ""; lastError = ""
        broadcastStatus(TYPE_NONE, "", false, "已停止")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun killProcesses() {
        isRunning.set(false)
        cloudflaredProcess?.destroy(); cloudflaredProcess = null
        frpcProcess?.destroy(); frpcProcess = null
    }

    /** 启动/连接失败：广播错误并停掉前台服务，避免通知栏卡在“正在启动” */
    private fun failAndStop(type: String, msg: String) {
        broadcastStatus(type, "", false, msg)
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } catch (_: Exception) {
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "内网穿透服务", NotificationManager.IMPORTANCE_LOW).apply { description = "保持内网穿透后台运行" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun updateNotification(text: String) {
        val pendingIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("内网穿透")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_menu_camera)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun broadcastStatus(type: String, url: String, running: Boolean, error: String) {
        val intent = Intent(BROADCAST_TUNNEL_STATUS).apply {
            setPackage(packageName)
            putExtra("type", type)
            putExtra("url", url)
            putExtra("running", running)
            putExtra("error", error)
        }
        sendBroadcast(intent)
    }
}
