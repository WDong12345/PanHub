package com.yunx.app.data.download

import com.yunx.app.data.preview.FfplayManager
import com.yunx.app.util.Log
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * DASH 合轨（ffmpeg 版）：调用 ffmpeg `-c copy` 只重封装、不重编码，把分离的视频轨与音频轨
 * 合成一个普通 MP4。
 *
 * 与 [DashMuxer]（纯 Java 自研 ISO BMFF 解析器）的关系：
 * - **ffmpeg 为主**：本机有 ffmpeg（应用私有目录 / PATH / 常见安装目录）时优先用它，
 *   兼容性与异常输入处理更稳；
 * - **纯 Java 兜底**：没有引擎，或 ffmpeg 合轨失败时退回 [DashMuxer]，保证功能始终可用。
 *
 * 引擎从 [FfplayManager.locateFfmpeg] 取；ffmpeg 精简版不随包发布，由「引擎管理」页或
 * 首次提示下载。
 */
object FfmpegMuxer {

    private const val TAG = "YunX-FfmpegMux"

    /** 合轨超时（10 分钟；`-c copy` 只是重封装，正常远小于此） */
    private const val MUX_TIMEOUT_SECONDS = 600L

    /**
     * 用 ffmpeg 合轨。成功返回 true，失败返回 false（交由上层兜底）。
     * 同步 IO 操作，调用方应在 IO 线程执行。
     *
     * @param onProgress 0f..1f 的粗略进度（本实现只在起止各回调一次，`-c copy` 很快）
     */
    fun mux(video: File, audio: File, out: File, onProgress: (Float) -> Unit = {}): Boolean {
        val exe = FfplayManager.locateFfmpeg() ?: return false
        if (!video.isFile || video.length() == 0L || !audio.isFile || audio.length() == 0L) {
            Log.w(TAG, "合轨输入缺失或为空 video=${video.absolutePath} audio=${audio.absolutePath}")
            return false
        }
        onProgress(0f)
        out.parentFile?.mkdirs()
        runCatching { out.delete() }
        val cmd = listOf(
            exe.absolutePath,
            "-hide_banner",
            "-loglevel", "error",
            "-y",
            "-i", video.absolutePath,
            "-i", audio.absolutePath,
            "-map", "0:v:0",
            "-map", "1:a:0",
            "-c", "copy",
            "-movflags", "+faststart",
            out.absolutePath
        )
        val logFile = File(
            out.parentFile ?: File(System.getProperty("java.io.tmpdir")),
            "ffmpeg-mux-${System.currentTimeMillis()}.log"
        )
        return runCatching {
            val proc = ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .redirectOutput(logFile)
                .start()
            try {
                if (!proc.waitFor(MUX_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    proc.destroyForcibly()
                    Log.w(TAG, "ffmpeg 合轨超时（${MUX_TIMEOUT_SECONDS}s）")
                    return@runCatching false
                }
                val code = proc.exitValue()
                if (code != 0 || !out.isFile || out.length() == 0L) {
                    Log.w(TAG, "ffmpeg 合轨失败 code=$code：${runCatching { logFile.readText() }.getOrDefault("").takeLast(600)}")
                    return@runCatching false
                }
                onProgress(1f)
                true
            } finally {
                runCatching { logFile.delete() }
            }
        }.getOrElse { t ->
            Log.w(TAG, "ffmpeg 合轨异常：${t.message}")
            false
        }
    }
}
