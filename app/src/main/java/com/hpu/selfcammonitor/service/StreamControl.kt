package com.hpu.selfcammonitor.service

/**
 * 网页端控制接口：由 CameraService 实现，供 StreamServer 暴露 HTTP API。
 *
 * 所有方法都可能从 HTTP 工作线程调用，实现内部自行切主线程，
 * 且必须保证不抛异常（失败时返回错误信息或 false）。
 */
interface StreamControl {

    /** 当前状态快照。值类型限定为 String / Int / Long / Boolean / List<String> / null */
    fun state(): Map<String, Any?>

    /** 当前所选镜头支持的推流分辨率列表（如 "640x480"） */
    fun supportedResolutions(): List<String>

    /**
     * 应用配置项。支持键：
     *  - resolution  如 "1280x720"
     *  - fps         1..60
     *  - facing      0=后置 1=前置
     *  - mode        0=连续录像 1=运动触发 2=仅预览
     *  - mjpeg       0/1 或 true/false
     * 返回应用后的状态快照（附带 applied 字段说明结果）。
     */
    fun applyConfig(params: Map<String, String>): Map<String, Any?>

    /**
     * 手动开始录像（不受录像模式限制）。
     * @param durationSec 录制时长（秒）；<=0 表示一直录到调用 stopManualRecording
     * 返回是否受理
     */
    fun startManualRecording(durationSec: Int): Boolean

    /** 手动停止录像，返回是否受理 */
    fun stopManualRecording(): Boolean

    /** 列出已保存的录像（按修改时间倒序）。每项包含 relPath/name/date/size/modified/durationMs */
    fun listRecordings(): List<Map<String, Any?>>

    /** 把相对路径安全解析为录像文件（防目录穿越）；不存在或非法返回 null */
    fun recordingFile(relPath: String): java.io.File?

    /** 录像缩略图（jpg 字节）；无法生成返回 null */
    fun recordingThumbnail(relPath: String): ByteArray?
}
