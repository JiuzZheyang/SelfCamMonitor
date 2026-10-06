package com.hpu.selfcammonitor.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.LinkProperties
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

/**
 * 内网穿透服务：支持 cloudflared (Cloudflare Tunnel) 与 frp 两种模式，
 * 且**两者可同时运行**（互不影响）。
 *
 * 二进制以 jniLibs 下的 lib*.so 形式打包（libcloudflared.so / libfrpc.so），安装时被系统解压到
 * applicationInfo.nativeLibraryDir（只读 + 可执行），以此绕开 Android 10+ 禁止从 app
 * 数据目录执行程序（W^X）的限制。
 *
 * cloudflared: 使用 Tunnel Token 认证（`tunnel run --token <token>`）。
 * frp:         生成 frpc.ini，支持 TCP（remote_port）与 HTTP（custom_domains）两种代理。
 *
 * 状态按类型分别维护：cfRunning/frpRunning、cfUrl/frpUrl。广播 [BROADCAST_TUNNEL_STATUS]
 * 附带 type 字段，界面据此分别刷新。全部停止后服务自动 stopSelf。
 */
class TunnelService : Service() {

    private lateinit var executor: ExecutorService

    @Volatile private var dnsForwarder: LocalDnsForwarder? = null

    @Volatile private var cloudflaredProcess: Process? = null
    @Volatile private var frpcProcess: Process? = null

    @Volatile private var cfRunning = false
    @Volatile private var frpRunning = false
    @Volatile private var cfUrl = ""
    @Volatile private var frpUrl = ""

    private val channelId = "tunnel_service_channel"
    private val notificationId = 2

    companion object {
        const val TAG = "TunnelService"

        const val ACTION_START = "com.hpu.selfcammonitor.tunnel.START"
        const val ACTION_STOP = "com.hpu.selfcammonitor.tunnel.STOP"
        const val ACTION_RESTART = "com.hpu.selfcammonitor.tunnel.RESTART"
        const val ACTION_START_ALL = "com.hpu.selfcammonitor.tunnel.START_ALL"

        const val TYPE_NONE = "none"
        const val TYPE_CLOUDFLARED = "cloudflared"
        const val TYPE_FRP = "frp"

        // 偏好键（同时供设置页/自动启动使用）
        const val PREF_CF_ENABLED = "cf_enabled"
        const val PREF_FRP_ENABLED = "frp_enabled"
        const val PREF_AUTO_START = "tunnel_auto_start"

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

        /** 任意一种穿透是否在运行 */
        fun isTunnelRunning(): Boolean = instance?.anyRunning() == true

        /** 指定类型是否在运行 */
        fun isTunnelRunning(type: String): Boolean = instance?.isTypeRunning(type) == true

        /** 首个运行中的穿透地址（兼容旧调用） */
        fun getTunnelUrl(): String = instance?.firstUrl() ?: ""

        /** 指定类型的穿透地址 */
        fun getTunnelUrl(type: String): String = instance?.urlOf(type) ?: ""

        /** 首个运行中的穿透类型（兼容旧调用） */
        fun getTunnelType(): String = instance?.runningTypes()?.firstOrNull() ?: TYPE_NONE

        /** 当前运行中的全部穿透类型 */
        fun runningTypes(): List<String> = instance?.runningTypes() ?: emptyList()

        fun startTunnel(context: Context, type: String, extras: Map<String, String> = emptyMap()) {
            val intent = Intent(context, TunnelService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_TUNNEL_TYPE, type)
                extras.forEach { putExtra(it.key, it.value) }
            }
            startFgsSafely(context, intent)
        }

        /** 启动所有在偏好中已启用的穿透（App 启动 / 开机时调用） */
        fun startAllEnabled(context: Context) {
            val intent = Intent(context, TunnelService::class.java).apply { action = ACTION_START_ALL }
            startFgsSafely(context, intent)
        }

        fun stopTunnel(context: Context, type: String = "") {
            val intent = Intent(context, TunnelService::class.java).apply {
                action = ACTION_STOP
                putExtra(EXTRA_TUNNEL_TYPE, type)
            }
            runCatching { context.startService(intent) }
        }

        /** 停止全部穿透 */
        fun stopAllTunnels(context: Context) = stopTunnel(context, "")

        fun restartTunnel(context: Context) {
            val intent = Intent(context, TunnelService::class.java).apply { action = ACTION_RESTART }
            startFgsSafely(context, intent)
        }

        /** Android 12+ 后台启动前台服务可能抛异常，做兜底避免崩溃 */
        private fun startFgsSafely(context: Context, intent: Intent) {
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                Log.w(TAG, "启动穿透服务失败: ${e.message}")
            }
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
            ACTION_START_ALL -> {
                updateNotification("正在启动内网穿透...")
                startFromPrefs()
            }
            ACTION_RESTART -> {
                updateNotification("正在重启内网穿透...")
                killAll()
                startFromPrefs()
            }
            ACTION_STOP -> {
                val type = intent.getStringExtra(EXTRA_TUNNEL_TYPE) ?: ""
                when (type) {
                    TYPE_CLOUDFLARED -> stopType(TYPE_CLOUDFLARED)
                    TYPE_FRP -> stopType(TYPE_FRP)
                    else -> stopAll()
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        // 只清理进程，不再广播（避免覆盖失败/停止原因）
        killAll()
        stopDnsForwarder()
        instance = null
        executor.shutdown()
    }

    // ─── 状态查询 ─────────────────────────────────────────────────

    private fun anyRunning(): Boolean = cfRunning || frpRunning

    private fun isTypeRunning(type: String): Boolean = when (type) {
        TYPE_CLOUDFLARED -> cfRunning
        TYPE_FRP -> frpRunning
        else -> false
    }

    private fun urlOf(type: String): String = when (type) {
        TYPE_CLOUDFLARED -> cfUrl
        TYPE_FRP -> frpUrl
        else -> ""
    }

    private fun firstUrl(): String = when {
        cfRunning && cfUrl.isNotBlank() -> cfUrl
        frpRunning && frpUrl.isNotBlank() -> frpUrl
        cfRunning -> cfUrl
        frpRunning -> frpUrl
        else -> ""
    }

    private fun runningTypes(): List<String> {
        val out = mutableListOf<String>()
        if (cfRunning) out.add(TYPE_CLOUDFLARED)
        if (frpRunning) out.add(TYPE_FRP)
        return out
    }

    /** 从 SharedPreferences 读取配置并按需启动已启用的穿透（不会停掉已在运行的） */
    private fun startFromPrefs() {
        val prefs = getSharedPreferences("camera_prefs", MODE_PRIVATE)
        if (cfEnabled(prefs) && !cfRunning) {
            startCloudflared(prefs.getString("cloudflared_token", "") ?: "")
        }
        if (frpEnabled(prefs) && !frpRunning) {
            startFrpc(
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
            markFailed(TYPE_CLOUDFLARED, "Token 为空，请先在设置中配置")
            return
        }
        // 只重启 cloudflared，不影响 frp
        killCloudflared()
        cfRunning = false
        cfUrl = ""

        executor.execute {
            val binary = prepareBinary("libcloudflared.so") ?: run {
                markFailed(TYPE_CLOUDFLARED, "找不到 cloudflared 可执行文件")
                return@execute
            }

            // Android 沙箱下没有 /etc/resolv.conf，Go 会回退到 127.0.0.1:53/[::1]:53。
            // 方案一（主）：App 端自己解析 SRV 拿到边缘 IP，用 TUNNEL_EDGE 注入，
            //   让 cloudflared 不再依赖自身域名解析（实测可直接连上边缘）。
            // 方案二（备）：启动本地 DNS 中继（在能绑定 53 端口的老版本上仍有效）。
            ensureDnsForwarder()
            val cmd = mutableListOf(binary.absolutePath, "--no-autoupdate", "tunnel", "run", "--token", token.trim())
            Log.d(TAG, "启动 cloudflared: $cmd")

            val edges = try {
                EdgeDiscovery.discover(systemDnsServers())
            } catch (e: Exception) {
                Log.w(TAG, "边缘发现失败: ${e.message}")
                emptyList()
            }
            updateNotification(notificationText("正在连接 Cloudflare..."))

            val proc = try {
                ProcessBuilder(cmd)
                    .directory(filesDir)
                    .apply {
                        environment()["HOME"] = filesDir.absolutePath
                        if (edges.isNotEmpty()) {
                            environment()["TUNNEL_EDGE"] = edges.joinToString(",")
                            Log.i(TAG, "使用显式边缘地址: ${edges.joinToString(",")}")
                        }
                        redirectErrorStream(true)
                    }
                    .start()
            } catch (e: Exception) {
                Log.e(TAG, "cloudflared 启动失败", e)
                markFailed(TYPE_CLOUDFLARED, e.message ?: "启动失败")
                return@execute
            }

            cloudflaredProcess = proc
            cfRunning = true
            broadcastStatus(TYPE_CLOUDFLARED, "", true, "")
            refreshNotification()

            var lastErr = ""
            var connected = false
            readLogs(proc, "cloudflared") { line ->
                if (!connected && line.contains("registered tunnel connection", ignoreCase = true)) {
                    connected = true
                    val domain = extractDomain(line)
                    cfUrl = when {
                        domain.startsWith("http") -> domain
                        domain.isNotEmpty() -> "https://$domain"
                        else -> ""
                    }
                    broadcastStatus(TYPE_CLOUDFLARED, cfUrl, true, "")
                    refreshNotification()
                }
                if (line.contains("err", ignoreCase = true) || line.contains("failed", ignoreCase = true)) lastErr = line
            }

            onProcessEnded(proc, TYPE_CLOUDFLARED, cfUrl, lastErr)
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
            markFailed(TYPE_FRP, "frps 服务器地址为空")
            return
        }
        // 只重启 frp，不影响 cloudflared
        killFrpc()
        frpRunning = false
        frpUrl = ""

        executor.execute {
            val binary = prepareBinary("libfrpc.so") ?: run {
                markFailed(TYPE_FRP, "找不到 frpc 可执行文件")
                return@execute
            }

            // frpc 若 server_addr 填域名，同样依赖系统解析器，先启动 DNS 中继
            ensureDnsForwarder()

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
            updateNotification(notificationText("正在连接 frps $server..."))

            val proc = try {
                ProcessBuilder(cmd)
                    .directory(frpcDir)
                    .apply { environment()["HOME"] = filesDir.absolutePath; redirectErrorStream(true) }
                    .start()
            } catch (e: Exception) {
                Log.e(TAG, "frpc 启动失败", e)
                markFailed(TYPE_FRP, e.message ?: "启动失败")
                return@execute
            }

            frpcProcess = proc
            frpRunning = true
            broadcastStatus(TYPE_FRP, "", true, "")
            refreshNotification()

            val httpUrl = "http://$customDomains"
            var announced = ""
            var lastErr = ""

            // url 变化时才广播（登录成功 → 拿到真实远程端口可能两次）
            val announce: (String) -> Unit = { url ->
                if (url != announced) {
                    announced = url
                    frpUrl = url
                    broadcastStatus(TYPE_FRP, url, true, "")
                    refreshNotification()
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

            onProcessEnded(proc, TYPE_FRP, frpUrl, lastErr)
        }
    }

    // ─── 通用 ─────────────────────────────────────────────────────

    /**
     * 获取当前活动网络的 DNS 服务器（格式 ip:53）。
     * Android 沙箱里 Go 程序读不到 resolv.conf，需手动注入给 cloudflared。
     */
    private fun systemDnsServers(): List<String> {
        val result = LinkedHashSet<String>()
        try {
            val cm = getSystemService(ConnectivityManager::class.java)
            val active = cm?.activeNetwork
            if (active != null) {
                val lp: LinkProperties? = cm.getLinkProperties(active)
                lp?.dnsServers?.forEach { addr ->
                    var ip = addr.hostAddress ?: return@forEach
                    ip = ip.substringBefore('%') // 去掉 IPv6 的 scope id (fe80::1%wlan0)
                    if (ip.isBlank() || ip == "::1" || ip == "127.0.0.1") return@forEach
                    result.add(if (ip.contains(':')) "[$ip]:53" else "$ip:53")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "获取系统 DNS 失败", e)
        }
        // 兜底：常见公共 DNS（优先 DNSPod / AliDNS，国内可达性更好）
        if (result.isEmpty()) {
            result.add("119.29.29.29:53")
            result.add("223.5.5.5:53")
        }
        return result.toList()
    }

    /** 确保本地 DNS 中继已启动（幂等） */
    private fun ensureDnsForwarder() {
        if (dnsForwarder?.isRunning == true) return
        val forwarder = LocalDnsForwarder(systemDnsServers())
        if (forwarder.start()) {
            dnsForwarder = forwarder
        } else {
            Log.w(TAG, "本地 DNS 中继启动失败，cloudflared/frpc 的域名解析可能不可用")
        }
    }

    private fun stopDnsForwarder() {
        runCatching { dnsForwarder?.stop() }
        dnsForwarder = null
    }

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

    /** 某类型进程自然结束：只清空并广播该类型；两者都停下后服务退出 */
    private fun onProcessEnded(proc: Process, type: String, url: String, error: String) {
        val wasCurrent = when (type) {
            TYPE_CLOUDFLARED -> cloudflaredProcess === proc
            else -> frpcProcess === proc
        }
        if (type == TYPE_CLOUDFLARED) {
            if (cloudflaredProcess === proc) {
                cloudflaredProcess = null
                cfRunning = false
                cfUrl = ""
            }
        } else {
            if (frpcProcess === proc) {
                frpcProcess = null
                frpRunning = false
                frpUrl = ""
            }
        }
        if (wasCurrent) {
            broadcastStatus(type, url, false, error.ifBlank { "连接已断开" })
        }
        refreshNotification()
        stopSelfIfIdle()
    }

    /** 停止单个类型（只杀对应进程） */
    private fun stopType(type: String) {
        if (type == TYPE_CLOUDFLARED) {
            killCloudflared()
            broadcastStatus(TYPE_CLOUDFLARED, "", false, "")
        } else if (type == TYPE_FRP) {
            killFrpc()
            broadcastStatus(TYPE_FRP, "", false, "")
        }
        refreshNotification()
        stopSelfIfIdle()
    }

    private fun stopAll() {
        killAll()
        stopDnsForwarder()
        // 主动停止不是错误：error 传空串，界面会显示“已停止”而非“错误: 已停止”
        broadcastStatus(TYPE_CLOUDFLARED, "", false, "")
        broadcastStatus(TYPE_FRP, "", false, "")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun killCloudflared() {
        runCatching { cloudflaredProcess?.destroy() }
        cloudflaredProcess = null
        cfRunning = false
        cfUrl = ""
    }

    private fun killFrpc() {
        runCatching { frpcProcess?.destroy() }
        frpcProcess = null
        frpRunning = false
        frpUrl = ""
    }

    private fun killAll() {
        killCloudflared()
        killFrpc()
    }

    /** 两者都不在运行则自行停止服务，避免通知常驻 */
    private fun stopSelfIfIdle() {
        if (!anyRunning()) {
            try {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            } catch (_: Exception) {
            }
        }
    }

    /** 启动/连接失败：广播错误并清理该类型；若再无运行项则停掉前台服务 */
    private fun markFailed(type: String, msg: String) {
        when (type) {
            TYPE_CLOUDFLARED -> { cfRunning = false; cfUrl = "" }
            TYPE_FRP -> { frpRunning = false; frpUrl = "" }
        }
        broadcastStatus(type, "", false, msg)
        refreshNotification()
        stopSelfIfIdle()
    }

    private fun createNotificationChannel() {
        // NotificationChannel 为 API 26+，低版本无此概念，直接跳过
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(channelId, "内网穿透服务", NotificationManager.IMPORTANCE_LOW)
            .apply { description = "保持内网穿透后台运行" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** 根据当前运行状态拼装通知文案 */
    private fun notificationText(prefix: String): String {
        val parts = mutableListOf<String>()
        if (cfRunning) parts.add("Cloudflare: " + cfUrl.ifBlank { "连接中" })
        if (frpRunning) parts.add("frp: " + frpUrl.ifBlank { "连接中" })
        return if (parts.isEmpty()) prefix else parts.joinToString("  |  ")
    }

    private fun refreshNotification() {
        if (!anyRunning()) return  // 全停时由 stopSelfIfIdle 处理，不再占用通知
        updateNotification(notificationText("内网穿透"))
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

    private fun cfEnabled(prefs: SharedPreferences): Boolean =
        if (prefs.contains(PREF_CF_ENABLED)) prefs.getBoolean(PREF_CF_ENABLED, false)
        else prefs.getString("tunnel_type", TYPE_NONE) == TYPE_CLOUDFLARED

    private fun frpEnabled(prefs: SharedPreferences): Boolean =
        if (prefs.contains(PREF_FRP_ENABLED)) prefs.getBoolean(PREF_FRP_ENABLED, false)
        else prefs.getString("tunnel_type", TYPE_NONE) == TYPE_FRP
}
