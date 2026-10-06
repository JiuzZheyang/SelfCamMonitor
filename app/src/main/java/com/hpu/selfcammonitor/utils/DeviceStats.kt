package com.hpu.selfcammonitor.utils

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.SystemClock
import java.io.File
import kotlin.math.roundToInt

/**
 * 设备运行状态采集：电量 / 充电 / 电池温度 / SoC 温度 / 内存 / CPU 占用 / 运行时长。
 *
 * 全部使用标准库 + /sys、/proc 只读文件，无需额外权限。
 * 取不到的值返回 null（由调用方过滤），不抛异常。
 */
object DeviceStats {

    private var lastCpuTotal = 0L
    private var lastCpuIdle = 0L
    private var lastCpuAt = 0L

    /** 电池：电量百分比、温度(°C)、电压(mV)、电流(uA)、是否充电、电源类型 */
    fun battery(ctx: Context): Map<String, Any?> {
        val out = linkedMapOf<String, Any?>()
        try {
            val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (i != null) {
                val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                if (level >= 0 && scale > 0) out["percent"] = level * 100 / scale
                val t = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                if (t != Int.MIN_VALUE) out["tempC"] = Math.round(t / 10.0 * 10) / 10.0
                val v = i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
                if (v > 0) out["voltageMv"] = v
                val st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                out["charging"] = (st == BatteryManager.BATTERY_STATUS_CHARGING ||
                        st == BatteryManager.BATTERY_STATUS_FULL)
                val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
                out["plugged"] = when (plugged) {
                    BatteryManager.BATTERY_PLUGGED_AC -> "AC"
                    BatteryManager.BATTERY_PLUGGED_USB -> "USB"
                    BatteryManager.BATTERY_PLUGGED_WIRELESS -> "无线"
                    else -> ""
                }
            }
            val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            val ua = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            if (ua != null && ua != Int.MIN_VALUE) out["currentUa"] = ua
        } catch (_: Exception) {
        }
        return out
    }

    /** SoC/CPU 温度（°C）：扫描 thermal zone 取最高值；取不到返回 null */
    fun cpuTempC(): Double? {
        var max = Double.NaN
        try {
            val base = File("/sys/class/thermal")
            base.listFiles()?.forEach { z ->
                if (!z.name.startsWith("thermal_zone")) return@forEach
                try {
                    val raw = File(z, "temp").readText().trim().toLongOrNull() ?: return@forEach
                    var c = raw.toDouble()
                    if (c > 1000) c /= 1000.0
                    if (c in 1.0..150.0) {
                        if (max.isNaN() || c > max) max = c
                    }
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
        return if (max.isNaN()) null else Math.round(max * 10) / 10.0
    }

    /** 内存总量 / 可用量 */
    fun memory(ctx: Context): Map<String, Any?> {
        val out = linkedMapOf<String, Any?>()
        try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            out["totalBytes"] = mi.totalMem
            out["availBytes"] = mi.availMem
            out["lowMemory"] = mi.lowMemory
        } catch (_: Exception) {
        }
        return out
    }

    /** /proc/stat 采样的整机 CPU 使用率（%），首次调用返回 null，之后基于两次采样差值 */
    fun cpuUsagePct(): Double? {
        try {
            val line = File("/proc/stat").readLines()
                .firstOrNull { it.startsWith("cpu ") } ?: return null
            val p = line.trim().split(Regex("\\s+"))
            if (p.size < 5) return null
            val idle = p[4].toLongOrNull() ?: return null
            var total = 0L
            for (i in 1 until p.size) total += (p[i].toLongOrNull() ?: 0L)
            var pct: Double? = null
            if (lastCpuAt > 0 && total > lastCpuTotal) {
                val dt = total - lastCpuTotal
                val di = idle - lastCpuIdle
                if (dt > 0) pct = ((100.0 * (dt - di) / dt).coerceIn(0.0, 100.0) * 10).roundToInt() / 10.0
            }
            lastCpuTotal = total
            lastCpuIdle = idle
            lastCpuAt = System.currentTimeMillis()
            return pct
        } catch (_: Exception) {
            return null
        }
    }

    /** 开机运行时长（秒，含深睡） */
    fun uptimeSec(): Long = SystemClock.elapsedRealtime() / 1000
}
