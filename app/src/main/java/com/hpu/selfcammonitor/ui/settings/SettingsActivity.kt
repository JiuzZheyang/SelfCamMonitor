package com.hpu.selfcammonitor.ui.settings

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.hpu.selfcammonitor.R
import com.hpu.selfcammonitor.service.CameraService
import com.hpu.selfcammonitor.service.TunnelService

class SettingsActivity : AppCompatActivity() {
    private lateinit var prefs: SharedPreferences
    private lateinit var spResolution: AutoCompleteTextView
    private lateinit var spCameraFacing: AutoCompleteTextView
    private lateinit var seekBarFps: SeekBar
    private lateinit var tvFpsValue: TextView
    private lateinit var seekBarSensitivity: SeekBar
    private lateinit var tvSensitivityValue: TextView
    private lateinit var switchBoot: SwitchMaterial
    private lateinit var switchMotionAlert: SwitchMaterial
    private lateinit var etAlertUrl: EditText
    private lateinit var etAlertQuiet: EditText
    private lateinit var etStartTime: EditText
    private lateinit var etEndTime: EditText
    private lateinit var etUsername: EditText
    private lateinit var etPassword: EditText
    private lateinit var etReserveMb: EditText
    private lateinit var switchOnDemand: SwitchMaterial

    // 内网穿透
    private lateinit var switchTunnelAutoStart: SwitchMaterial
    private lateinit var switchCfEnabled: SwitchMaterial
    private lateinit var switchFrpEnabled: SwitchMaterial
    private lateinit var tilCloudflaredToken: TextInputLayout
    private lateinit var etCloudflaredToken: TextInputEditText
    private lateinit var layoutFrpSettings: LinearLayout
    private lateinit var etFrpServer: TextInputEditText
    private lateinit var etFrpServerPort: TextInputEditText
    private lateinit var etFrpToken: TextInputEditText
    private lateinit var etFrpLocalIp: TextInputEditText
    private lateinit var etFrpLocalPort: TextInputEditText
    private lateinit var spinnerFrpProtocol: AutoCompleteTextView
    private lateinit var tilFrpDomain: TextInputLayout
    private lateinit var etFrpDomain: TextInputEditText
    private lateinit var tilFrpSubdomain: TextInputLayout
    private lateinit var etFrpSubdomain: TextInputEditText
    private lateinit var tilFrpRemotePort: TextInputLayout
    private lateinit var etFrpRemotePort: TextInputEditText
    private lateinit var tvCfStatus: TextView
    private lateinit var tvFrpStatus: TextView
    private lateinit var btnCfCopy: MaterialButton
    private lateinit var btnFrpCopy: MaterialButton

    private lateinit var spinnerMotionDuration: AutoCompleteTextView
    private lateinit var spinnerContinuousDuration: AutoCompleteTextView
    private lateinit var tunnelReceiver: BroadcastReceiver

    /** 程序化修改开关时置位，避免触发监听里的启停逻辑 */
    private var suppressToggle = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        prefs = getSharedPreferences("camera_prefs", MODE_PRIVATE)

        spCameraFacing = findViewById(R.id.spinnerCameraFacing)
        val facingLabels = resources.getStringArray(R.array.camera_facing_labels)
        spCameraFacing.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, facingLabels)
        )
        spCameraFacing.setText(facingLabels[prefs.getInt("camera_facing", 0)], false)

        spResolution = findViewById(R.id.spinnerResolution)
        // 动态加载支持的分辨率（按所选镜头朝向枚举）
        loadSupportedResolutions(prefs.getInt("camera_facing", 0))
        seekBarFps = findViewById(R.id.seekBarFps)
        tvFpsValue = findViewById(R.id.tvFpsValue)
        seekBarSensitivity = findViewById(R.id.seekBarSensitivity)
        tvSensitivityValue = findViewById(R.id.tvSensitivityValue)
        switchBoot = findViewById(R.id.switchBootStart)
        switchMotionAlert = findViewById(R.id.switchMotionAlert)
        etAlertUrl = findViewById(R.id.etAlertUrl)
        etAlertQuiet = findViewById(R.id.etAlertQuiet)
        etStartTime = findViewById(R.id.etStartTime)
        etEndTime = findViewById(R.id.etEndTime)
        etUsername = findViewById(R.id.etUsername)
        etPassword = findViewById(R.id.etPassword)
        etReserveMb = findViewById(R.id.etReserveMb)
        switchOnDemand = findViewById(R.id.switchOnDemand)
        spinnerMotionDuration = findViewById(R.id.spinner_motion_duration)
        spinnerMotionDuration.setAdapter(
            ArrayAdapter(
                this, android.R.layout.simple_spinner_dropdown_item,
                resources.getStringArray(R.array.motion_duration_labels)
            )
        )
        spinnerContinuousDuration = findViewById(R.id.spinner_continuous_duration)
        spinnerContinuousDuration.setAdapter(
            ArrayAdapter(
                this, android.R.layout.simple_spinner_dropdown_item,
                resources.getStringArray(R.array.continuous_duration_labels)
            )
        )

        // 内网穿透
        switchTunnelAutoStart = findViewById(R.id.switchTunnelAutoStart)
        switchCfEnabled = findViewById(R.id.switchCfEnabled)
        switchFrpEnabled = findViewById(R.id.switchFrpEnabled)
        tilCloudflaredToken = findViewById(R.id.tilCloudflaredToken)
        etCloudflaredToken = findViewById(R.id.etCloudflaredToken)
        layoutFrpSettings = findViewById(R.id.layoutFrpSettings)
        etFrpServer = findViewById(R.id.etFrpServer)
        etFrpServerPort = findViewById(R.id.etFrpServerPort)
        etFrpToken = findViewById(R.id.etFrpToken)
        etFrpLocalIp = findViewById(R.id.etFrpLocalIp)
        etFrpLocalPort = findViewById(R.id.etFrpLocalPort)
        spinnerFrpProtocol = findViewById(R.id.spinnerFrpProtocol)
        tilFrpDomain = findViewById(R.id.tilFrpDomain)
        etFrpDomain = findViewById(R.id.etFrpDomain)
        tilFrpSubdomain = findViewById(R.id.tilFrpSubdomain)
        etFrpSubdomain = findViewById(R.id.etFrpSubdomain)
        tilFrpRemotePort = findViewById(R.id.tilFrpRemotePort)
        etFrpRemotePort = findViewById(R.id.etFrpRemotePort)
        tvCfStatus = findViewById(R.id.tvCfStatus)
        tvFrpStatus = findViewById(R.id.tvFrpStatus)
        btnCfCopy = findViewById(R.id.btnCfCopy)
        btnFrpCopy = findViewById(R.id.btnFrpCopy)

        // frp 协议选项
        val protocolItems = listOf(getString(R.string.frp_protocol_tcp), getString(R.string.frp_protocol_http))
        spinnerFrpProtocol.setAdapter(ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, protocolItems))
        spinnerFrpProtocol.setOnItemClickListener { _, _, position, _ -> applyFrpProtocolVisibility(position == 1) }

        // 自动启动开关：仅持久化，App 启动时读取
        switchTunnelAutoStart.setOnCheckedChangeListener { _, isChecked ->
            if (suppressToggle) return@setOnCheckedChangeListener
            prefs.edit().putBoolean(TunnelService.PREF_AUTO_START, isChecked).apply()
        }

        // Cloudflare 开关：开=保存并启动；关=停止（不影响 frp）
        switchCfEnabled.setOnCheckedChangeListener { _, isChecked ->
            if (suppressToggle) return@setOnCheckedChangeListener
            prefs.edit().putBoolean(TunnelService.PREF_CF_ENABLED, isChecked).apply()
            if (isChecked) startCf() else TunnelService.stopTunnel(this, TunnelService.TYPE_CLOUDFLARED)
        }

        // frp 开关：开=保存并启动；关=停止（不影响 Cloudflare）
        switchFrpEnabled.setOnCheckedChangeListener { _, isChecked ->
            if (suppressToggle) return@setOnCheckedChangeListener
            prefs.edit().putBoolean(TunnelService.PREF_FRP_ENABLED, isChecked).apply()
            if (isChecked) startFrp() else TunnelService.stopTunnel(this, TunnelService.TYPE_FRP)
        }

        // 注册穿透状态广播接收
        tunnelReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val type = intent.getStringExtra("type") ?: ""
                val running = intent.getBooleanExtra("running", false)
                val url = intent.getStringExtra("url") ?: ""
                val error = intent.getStringExtra("error") ?: ""
                when (type) {
                    TunnelService.TYPE_CLOUDFLARED -> applyTunnelStatus(true, running, url, error)
                    TunnelService.TYPE_FRP -> applyTunnelStatus(false, running, url, error)
                }
            }
        }
        ContextCompat.registerReceiver(this, tunnelReceiver, IntentFilter(TunnelService.BROADCAST_TUNNEL_STATUS), ContextCompat.RECEIVER_NOT_EXPORTED)

        // 复制穿透地址
        btnCfCopy.setOnClickListener { copyUrl(TunnelService.getTunnelUrl(TunnelService.TYPE_CLOUDFLARED)) }
        btnFrpCopy.setOnClickListener { copyUrl(TunnelService.getTunnelUrl(TunnelService.TYPE_FRP)) }

        loadSettings()
        setupListeners()

        findViewById<Button>(R.id.btnSave).setOnClickListener { saveSettings() }

        findViewById<Button>(R.id.btnCancel).setOnClickListener {
            finish()   // 返回上一页（主界面）
        }

        findViewById<ImageView>(R.id.btnHelpSensitivity).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("检测灵敏度说明")
                .setMessage("动作识别灵敏度数值越低越敏感。\n\n数值越小，越轻微的画面变化就会触发运动检测；数值越大，需要更明显的画面变化才会触发。")
                .setPositiveButton("知道了", null)
                .show()
        }
    }

    /**
     * 点击输入框以外的区域时：清除焦点并隐藏软键盘。
     * 下拉框的选项弹层属于独立窗口，点选选项不会经过此处，不受影响。
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_UP) {
            val focused = currentFocus
            if (focused is EditText) {
                val rect = Rect()
                focused.getGlobalVisibleRect(rect)
                if (!rect.contains(ev.rawX.toInt(), ev.rawY.toInt())) {
                    focused.clearFocus()
                    val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.hideSoftInputFromWindow(focused.windowToken, 0)
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    // ---------- 内网穿透 ----------

    /** 是否 HTTP 模式：控制域名/子域名/远程端口字段的显隐 */
    private fun applyFrpProtocolVisibility(isHttp: Boolean) {
        tilFrpDomain.visibility = if (isHttp) View.VISIBLE else View.GONE
        tilFrpSubdomain.visibility = if (isHttp) View.VISIBLE else View.GONE
        tilFrpRemotePort.visibility = if (isHttp) View.GONE else View.VISIBLE
    }

    /** 收集 frp 表单为 extras（同时用于启动与持久化） */
    private fun buildFrpExtras(): Map<String, String> = mapOf(
        "frp_server" to etFrpServer.text.toString().trim(),
        "frp_server_port" to (etFrpServerPort.text.toString().toIntOrNull() ?: 7000).toString(),
        "frp_token" to etFrpToken.text.toString(),
        "frp_local_ip" to etFrpLocalIp.text.toString().trim().ifEmpty { "127.0.0.1" },
        "frp_local_port" to (etFrpLocalPort.text.toString().toIntOrNull() ?: 8080).toString(),
        "frp_protocol" to frpProtocolValue(),
        "frp_subdomain" to etFrpSubdomain.text.toString().trim(),
        "frp_domain" to etFrpDomain.text.toString().trim(),
        "frp_remote_port" to (etFrpRemotePort.text.toString().toIntOrNull() ?: 0).toString(),
    )

    private fun frpProtocolValue(): String =
        if (spinnerFrpProtocol.text.toString() == getString(R.string.frp_protocol_http)) "http" else "tcp"

    private fun persistTunnelFields() {
        prefs.edit()
            .putBoolean(TunnelService.PREF_AUTO_START, switchTunnelAutoStart.isChecked)
            .putBoolean(TunnelService.PREF_CF_ENABLED, switchCfEnabled.isChecked)
            .putBoolean(TunnelService.PREF_FRP_ENABLED, switchFrpEnabled.isChecked)
            .putString("cloudflared_token", etCloudflaredToken.text.toString().trim())
            .putString("frp_server", etFrpServer.text.toString().trim())
            .putString("frp_server_port", etFrpServerPort.text.toString().trim().ifEmpty { "7000" })
            .putString("frp_token", etFrpToken.text.toString())
            .putString("frp_local_ip", etFrpLocalIp.text.toString().trim().ifEmpty { "127.0.0.1" })
            .putString("frp_local_port", etFrpLocalPort.text.toString().trim().ifEmpty { "8080" })
            .putString("frp_subdomain", etFrpSubdomain.text.toString().trim())
            .putString("frp_domain", etFrpDomain.text.toString().trim())
            .putString("frp_protocol", frpProtocolValue())
            .putString("frp_remote_port", etFrpRemotePort.text.toString().trim().ifEmpty { "0" })
            // 兼容旧版单选项：记录当前启用了哪些
            .putString("tunnel_type", when {
                switchCfEnabled.isChecked && switchFrpEnabled.isChecked -> "both"
                switchCfEnabled.isChecked -> "cloudflared"
                switchFrpEnabled.isChecked -> "frp"
                else -> "none"
            })
            .apply()
    }

    private fun startCf() {
        val token = etCloudflaredToken.text.toString().trim()
        if (token.isEmpty()) {
            Toast.makeText(this, "请先填写 Cloudflare Tunnel Token", Toast.LENGTH_SHORT).show()
            setSwitchChecked(switchCfEnabled, false)
            prefs.edit().putBoolean(TunnelService.PREF_CF_ENABLED, false).apply()
            return
        }
        prefs.edit().putString("cloudflared_token", token).apply()
        TunnelService.startTunnel(this, TunnelService.TYPE_CLOUDFLARED, mapOf("cloudflared_token" to token))
    }

    private fun startFrp() {
        val server = etFrpServer.text.toString().trim()
        if (server.isEmpty()) {
            Toast.makeText(this, "请先填写 frps 服务器地址", Toast.LENGTH_SHORT).show()
            setSwitchChecked(switchFrpEnabled, false)
            prefs.edit().putBoolean(TunnelService.PREF_FRP_ENABLED, false).apply()
            return
        }
        val extras = buildFrpExtras()
        prefs.edit()
            .putString("frp_server", server)
            .putString("frp_server_port", extras["frp_server_port"])
            .putString("frp_token", extras["frp_token"])
            .putString("frp_local_ip", extras["frp_local_ip"])
            .putString("frp_local_port", extras["frp_local_port"])
            .putString("frp_protocol", extras["frp_protocol"])
            .putString("frp_subdomain", extras["frp_subdomain"])
            .putString("frp_domain", extras["frp_domain"])
            .putString("frp_remote_port", extras["frp_remote_port"])
            .apply()
        TunnelService.startTunnel(this, TunnelService.TYPE_FRP, extras)
    }

    /** 程序化设置开关状态（不触发启停监听） */
    private fun setSwitchChecked(sw: SwitchMaterial, checked: Boolean) {
        if (sw.isChecked == checked) return
        suppressToggle = true
        sw.isChecked = checked
        suppressToggle = false
    }

    private fun copyUrl(url: String) {
        if (url.isBlank()) return
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("tunnel_url", url))
        Toast.makeText(this, "已复制: $url", Toast.LENGTH_SHORT).show()
    }

    /** 按广播类型刷新对应状态行 / 开关 / 复制按钮 */
    private fun applyTunnelStatus(isCf: Boolean, running: Boolean, url: String, error: String) {
        val statusTv = if (isCf) tvCfStatus else tvFrpStatus
        val copyBtn = if (isCf) btnCfCopy else btnFrpCopy
        val sw = if (isCf) switchCfEnabled else switchFrpEnabled
        if (running) {
            setSwitchChecked(sw, true)
            statusTv.text = url.ifBlank { "运行中" }
            statusTv.setTextColor(getColor(R.color.green))
            statusTv.setBackgroundResource(R.drawable.bg_status_running)
            copyBtn.visibility = if (url.startsWith("http")) View.VISIBLE else View.GONE
        } else {
            statusTv.text = if (error.isNotEmpty()) "错误: $error" else "未启动"
            statusTv.setTextColor(getColor(R.color.text_secondary))
            statusTv.setBackgroundResource(R.drawable.bg_status_idle)
            copyBtn.visibility = View.GONE
        }
    }

    private fun loadSettings() {
        seekBarFps.progress = prefs.getInt("fps", 16).coerceIn(1, 60)
        tvFpsValue.text = "${seekBarFps.progress} fps"

        seekBarSensitivity.progress = prefs.getInt("sensitivity", 50)
        tvSensitivityValue.text = seekBarSensitivity.progress.toString()

        switchBoot.isChecked = prefs.getBoolean("boot_start", false)
        // URL 为空时强制回退为关：防止历史上被打开、但 URL 后来被清空的配置残留
        if (prefs.getBoolean("motion_alert_enabled", false) &&
            prefs.getString("alert_url", "")?.isNotBlank() == true) {
            switchMotionAlert.isChecked = true
        }
        etAlertUrl.setText(prefs.getString("alert_url", ""))
        etAlertQuiet.setText(prefs.getInt("alert_quiet", 30).toString())
        etStartTime.setText(prefs.getString("monitor_start", ""))
        etEndTime.setText(prefs.getString("monitor_end", ""))
        etUsername.setText(prefs.getString("http_user", ""))
        etPassword.setText(prefs.getString("http_pass", ""))
        etReserveMb.setText(prefs.getInt("record_reserve_mb", 0).toString())
        setSwitchChecked(switchOnDemand, prefs.getBoolean("ondemand_camera", false))

        // 加载运动录像时长（秒）
        val motionSec = prefs.getInt("motion_clip_sec", CameraService.Companion.DEFAULT_MOTION_CLIP_SEC)
        val motionValues = resources.getStringArray(R.array.motion_duration_values)
        val motionLabels = resources.getStringArray(R.array.motion_duration_labels)
        val motionIndex = motionValues.indexOf(motionSec.toString())
        if (motionIndex >= 0) spinnerMotionDuration.setText(motionLabels[motionIndex], false)

        // 加载连续录像分段时长（秒）
        val continuousSec = prefs.getInt("continuous_segment_sec", CameraService.Companion.DEFAULT_CONTINUOUS_SEGMENT_SEC)
        val continuousValues = resources.getStringArray(R.array.continuous_duration_values)
        val continuousLabels = resources.getStringArray(R.array.continuous_duration_labels)
        val continuousIndex = continuousValues.indexOf(continuousSec.toString())
        if (continuousIndex >= 0) spinnerContinuousDuration.setText(continuousLabels[continuousIndex], false)

        // 加载内网穿透配置（兼容旧版 tunnel_type 单选）
        val legacyType = prefs.getString("tunnel_type", "none") ?: "none"
        val cfEnabled = if (prefs.contains(TunnelService.PREF_CF_ENABLED))
            prefs.getBoolean(TunnelService.PREF_CF_ENABLED, false)
        else legacyType == "cloudflared" || legacyType == "both"
        val frpEnabled = if (prefs.contains(TunnelService.PREF_FRP_ENABLED))
            prefs.getBoolean(TunnelService.PREF_FRP_ENABLED, false)
        else legacyType == "frp" || legacyType == "both"

        setSwitchChecked(switchTunnelAutoStart, prefs.getBoolean(TunnelService.PREF_AUTO_START, true))
        setSwitchChecked(switchCfEnabled, cfEnabled)
        setSwitchChecked(switchFrpEnabled, frpEnabled)

        etCloudflaredToken.setText(prefs.getString("cloudflared_token", ""))
        etFrpServer.setText(prefs.getString("frp_server", ""))
        etFrpServerPort.setText(prefs.getString("frp_server_port", "7000"))
        etFrpToken.setText(prefs.getString("frp_token", ""))
        etFrpLocalIp.setText(prefs.getString("frp_local_ip", "127.0.0.1"))
        etFrpLocalPort.setText(prefs.getString("frp_local_port", "8080"))
        etFrpSubdomain.setText(prefs.getString("frp_subdomain", ""))
        etFrpDomain.setText(prefs.getString("frp_domain", ""))
        etFrpRemotePort.setText(prefs.getString("frp_remote_port", "0"))
        val frpProto = prefs.getString("frp_protocol", "tcp") ?: "tcp"
        spinnerFrpProtocol.setText(
            if (frpProto == "http") getString(R.string.frp_protocol_http) else getString(R.string.frp_protocol_tcp), false
        )
        applyFrpProtocolVisibility(frpProto == "http")

        // 根据服务实际运行状态刷新两个穿透的状态显示
        renderTunnelStatus(TunnelService.TYPE_CLOUDFLARED, tvCfStatus, btnCfCopy, switchCfEnabled)
        renderTunnelStatus(TunnelService.TYPE_FRP, tvFrpStatus, btnFrpCopy, switchFrpEnabled)
    }

    /** 初次进入界面时按服务实际状态渲染（无广播） */
    private fun renderTunnelStatus(type: String, statusTv: TextView, copyBtn: MaterialButton, sw: SwitchMaterial) {
        if (TunnelService.isTunnelRunning(type)) {
            val url = TunnelService.getTunnelUrl(type)
            setSwitchChecked(sw, true)
            statusTv.text = url.ifBlank { "运行中" }
            statusTv.setTextColor(getColor(R.color.green))
            statusTv.setBackgroundResource(R.drawable.bg_status_running)
            copyBtn.visibility = if (url.startsWith("http")) View.VISIBLE else View.GONE
        } else {
            statusTv.text = "未启动"
            statusTv.setTextColor(getColor(R.color.text_secondary))
            statusTv.setBackgroundResource(R.drawable.bg_status_idle)
            copyBtn.visibility = View.GONE
        }
    }

    private fun loadSupportedResolutions(cameraFacing: Int) {
        val cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        val resolutionItems = mutableListOf<String>()

        try {
            val targetFacing = if (cameraFacing == 1) {
                CameraCharacteristics.LENS_FACING_FRONT
            } else {
                CameraCharacteristics.LENS_FACING_BACK
            }
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                val characteristics = cameraManager.getCameraCharacteristics(id)
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                facing == targetFacing
            } ?: cameraManager.cameraIdList[0]

            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val configMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val outputSizes = configMap?.getOutputSizes(ImageFormat.YUV_420_888)

            outputSizes?.forEach { size ->
                val aspect = size.width.toFloat() / size.height.toFloat()
                // 只保留主流横屏比例，且宽度在 320~1920 之间
                if (size.width in 320..1920 && size.height >= 240 &&
                    aspect >= 1.33f && aspect <= 1.78f) {
                    val label = "${size.width}x${size.height}"
                    if (label !in resolutionItems) {
                        resolutionItems.add(label)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("Settings", "获取摄像头分辨率失败", e)
        }

        // 兜底安全列表
        if (resolutionItems.isEmpty()) {
            resolutionItems.addAll(listOf("320x240", "640x480", "1280x720", "1920x1080"))
        }

        spResolution.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, resolutionItems)
        )

        val savedRes = prefs.getString("resolution", "640x480") ?: "640x480"
        val index = resolutionItems.indexOf(savedRes).coerceAtLeast(0)
        spResolution.setText(resolutionItems[index], false)
    }

    private fun setupListeners() {
        // 切换镜头时立即按新朝向重新枚举分辨率列表
        spCameraFacing.setOnItemClickListener { _, _, position, _ ->
            loadSupportedResolutions(position)
        }

        seekBarFps.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                tvFpsValue.text = "$progress fps"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
        seekBarSensitivity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                tvSensitivityValue.text = progress.toString()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        spinnerMotionDuration.setOnItemClickListener { _, _, position, _ ->
            val values = resources.getStringArray(R.array.motion_duration_values)
            val seconds = values[position].toInt()
            prefs.edit().putInt("motion_clip_sec", seconds).apply()
            sendReloadBroadcast()   // 实时通知服务生效
        }

        spinnerContinuousDuration.setOnItemClickListener { _, _, position, _ ->
            val values = resources.getStringArray(R.array.continuous_duration_values)
            val seconds = values[position].toInt()
            prefs.edit().putInt("continuous_segment_sec", seconds).apply()
            sendReloadBroadcast()
        }

        // 监控时间段：点击弹出时间选择器，不允许手动输入
        etStartTime.setOnClickListener { showTimePicker(etStartTime, "选择监控开始时间") }
        etEndTime.setOnClickListener { showTimePicker(etEndTime, "选择监控结束时间") }

        // 运动报警开关：即时保存并热生效；URL 未配置时禁止打开
        switchMotionAlert.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && etAlertUrl.text.toString().trim().isBlank()) {
                switchMotionAlert.isChecked = false  // 回拨会再次进入本监听，走 else 分支不保存
                Toast.makeText(
                    this, "请先在下方配置报警接收 URL", Toast.LENGTH_SHORT
                ).show()
                return@setOnCheckedChangeListener
            }
            prefs.edit().putBoolean("motion_alert_enabled", isChecked).apply()
            sendReloadBroadcast()
        }

        // 按需启动摄像头：即时保存并热生效
        switchOnDemand.setOnCheckedChangeListener { _, isChecked ->
            if (suppressToggle) return@setOnCheckedChangeListener
            prefs.edit().putBoolean("ondemand_camera", isChecked).apply()
            sendReloadBroadcast()
        }
    }

    /**
     * 弹出紧凑的滚轮时间选择器（小时 + 分钟），选中后以 HH:mm 格式回填目标输入框。
     * 相比系统时钟样式弹框更小巧，圆角与按钮颜色跟随应用 Material3 主题。
     */
    private fun showTimePicker(target: EditText, title: String) {
        val parts = target.text.toString().split(":")
        val initHour = parts.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 23) ?: 0
        val initMinute = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 59) ?: 0

        val density = resources.displayMetrics.density
        val hourPicker = NumberPicker(this).apply {
            minValue = 0
            maxValue = 23
            value = initHour
            wrapSelectorWheel = true
            setFormatter { "%02d".format(it) }
        }
        val minutePicker = NumberPicker(this).apply {
            minValue = 0
            maxValue = 59
            value = initMinute
            wrapSelectorWheel = true
            setFormatter { "%02d".format(it) }
        }
        val colon = TextView(this).apply {
            text = ":"
            textSize = 22f
            setTextColor(getColor(R.color.text_primary))
            setPadding((12 * density).toInt(), 0, (12 * density).toInt(), 0)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            val vp = (12 * density).toInt()
            setPadding(0, vp, 0, vp)
            addView(hourPicker)
            addView(colon)
            addView(minutePicker)
        }

        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(container)
            .setPositiveButton("确定") { _, _ ->
                target.setText("%02d:%02d".format(hourPicker.value, minutePicker.value))
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun sendReloadBroadcast() {
        val intent = Intent("com.hpu.selfcammonitor.RELOAD_CONFIG")
        intent.setPackage(packageName)  // 显式广播：防系统过滤隐式广播
        sendBroadcast(intent)
    }

    private fun saveSettings() {
        // 下拉框取值：读取显示文本并反查对应索引
        val resolution = spResolution.text.toString()
        val facingLabels = resources.getStringArray(R.array.camera_facing_labels)
        val cameraFacing = facingLabels.indexOf(spCameraFacing.text.toString()).coerceAtLeast(0)
        val motionLabels = resources.getStringArray(R.array.motion_duration_labels)
        val motionValues = resources.getStringArray(R.array.motion_duration_values)
        val motionSec = motionValues[motionLabels.indexOf(spinnerMotionDuration.text.toString()).coerceAtLeast(0)].toInt()
        val continuousLabels = resources.getStringArray(R.array.continuous_duration_labels)
        val continuousValues = resources.getStringArray(R.array.continuous_duration_values)
        val continuousSec = continuousValues[continuousLabels.indexOf(spinnerContinuousDuration.text.toString()).coerceAtLeast(0)].toInt()

        // 保存时 URL 若被清空，同步关闭运动报警开关（服务端空 URL 本就不发送，这里保持配置自洽）
        if (etAlertUrl.text.toString().trim().isBlank()) {
            prefs.edit().putBoolean("motion_alert_enabled", false).apply()
            switchMotionAlert.isChecked = false
        }

        // 内网穿透配置（字段 + 启用开关 + 自动启动）
        persistTunnelFields()

        prefs.edit()
            .putString("resolution", resolution)   // 保存纯字符串
            .putInt("camera_facing", cameraFacing)  // 镜头：0=后置，1=前置
            .putInt("fps", seekBarFps.progress)
            .putInt("sensitivity", seekBarSensitivity.progress)
            .putBoolean("boot_start", switchBoot.isChecked)
            .putBoolean("motion_alert_enabled", switchMotionAlert.isChecked)
            .putString("alert_url", etAlertUrl.text.toString().trim())
            .putInt("alert_quiet", etAlertQuiet.text.toString().toIntOrNull() ?: 30)
            .putString("monitor_start", etStartTime.text.toString().trim())
            .putString("monitor_end", etEndTime.text.toString().trim())
            .putString("http_user", etUsername.text.toString().trim())
            .putString("http_pass", etPassword.text.toString().trim())
            .putInt("record_reserve_mb", etReserveMb.text.toString().toIntOrNull()?.coerceIn(0, 1_000_000) ?: 0)
            .putBoolean("ondemand_camera", switchOnDemand.isChecked)
            .putInt("motion_clip_sec", motionSec)
            .putInt("continuous_segment_sec", continuousSec)
            .apply()

        Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show()
        // 发送广播通知服务重新加载
        sendBroadcast(Intent("com.hpu.selfcammonitor.RELOAD_CONFIG").setPackage(packageName))

        // 让启用的穿透按最新配置生效（startTunnel 会先停同类型旧进程再起）
        if (switchCfEnabled.isChecked) startCf()
        if (switchFrpEnabled.isChecked) startFrp()

        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::tunnelReceiver.isInitialized) {
            try {
                unregisterReceiver(tunnelReceiver)
            } catch (_: Exception) {
            }
        }
    }
}
