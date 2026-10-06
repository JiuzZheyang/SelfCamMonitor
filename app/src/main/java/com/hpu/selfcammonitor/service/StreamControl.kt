package com.hpu.selfcammonitor.service

/**
 * 网页端控制接口：由 CameraService 实现，供 StreamServer 暴露 HTTP API。
 *
 * 所有方法都可能从 HTTP 工作线程调用，实现内部自行切主线程，
 * 且必须保证不抛异常（失败时返回错误信息或 false）。
 */
interface StreamControl {

    /** 当前状态快照。值类型限定为 String / Int / Long / Boolean / List<String> / Map / null */
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

    /** 列出已保存的录像（按修改时间倒序）。每项包含 relPath/name/date/size/modified/durationMs/starred */
    fun listRecordings(): List<Map<String, Any?>>

    /** 把相对路径安全解析为录像文件（防目录穿越）；不存在或非法返回 null */
    fun recordingFile(relPath: String): java.io.File?

    /** 录像缩略图（jpg 字节）；无法生成返回 null */
    fun recordingThumbnail(relPath: String): ByteArray?

    /**
     * 设置/取消「精选」。精选的录像不会被存储配额自动清理。
     * @return 路径合法并已写入时返回 true
     */
    fun setStarred(relPath: String, starred: Boolean): Boolean

    /**
     * 删除一条录像。
     * @param force true 表示连精选文件也删除（需用户显式确认）
     * @return 删除成功返回 true；文件不存在、非法路径或精选未强制时返回 false
     */
    fun deleteRecording(relPath: String, force: Boolean = false): Boolean

    /**
     * 唤醒摄像头（按需省电模式下使用）。
     * @param ttlSec 保持时长（秒），期间即使无客户端也不释放摄像头
     * @return 是否受理
     */
    fun wakeCamera(ttlSec: Int): Boolean
}
