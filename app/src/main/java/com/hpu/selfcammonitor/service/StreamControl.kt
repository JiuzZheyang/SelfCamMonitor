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
     *  - fps         1..30
     *  - facing      0=后置 1=前置
     *  - mode        0=连续录像 1=运动触发 2=仅预览
     *  - mjpeg       0/1 或 true/false
     * 返回应用后的状态快照（附带 applied 字段说明结果）。
     */
    fun applyConfig(params: Map<String, String>): Map<String, Any?>

    /** 手动开始录像（不受录像模式限制），返回是否受理 */
    fun startManualRecording(): Boolean

    /** 手动停止录像，返回是否受理 */
    fun stopManualRecording(): Boolean
}
