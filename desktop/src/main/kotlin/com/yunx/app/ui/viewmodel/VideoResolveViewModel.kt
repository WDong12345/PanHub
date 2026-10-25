package com.yunx.app.ui.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.yunx.app.data.db.AppDatabase
import com.yunx.app.data.network.video.VideoParseException
import com.yunx.app.data.network.video.VideoParseResult
import com.yunx.app.data.network.video.userMessage
import com.yunx.app.data.repository.VideoAccountRepository
import com.yunx.app.data.repository.VideoParseRepository
import kotlinx.coroutines.launch
import kotlin.reflect.KClass

/** 视频解析页的三种状态（Idle 时页面不显示） */
sealed interface VideoResolveUiState {
    data object Idle : VideoResolveUiState
    data object Loading : VideoResolveUiState
    data class Success(val result: VideoParseResult) : VideoResolveUiState
    data class Error(val message: String) : VideoResolveUiState
}

/**
 * 视频解析 ViewModel：只负责「识别 → 解析 → 结果与多选状态」。
 *
 * ★ 为什么不塞进 [ResolveViewModel]：网盘解析有 C1/C2/多账号/任务级目录等分支，
 *   视频解析是独立链路（解析器在 `data/network/video`），拆开后可单独演进、互不影响。
 * ★ 这里只管解析与多选；下载（含 DASH 合轨 / 动图合成）由 `DownloadManager` 按媒体的
 *   `mergeAudioUrl` / `motionUrl` 字段分流，预览由 `VideoPreviewOverlay` 负责。
 */
class VideoResolveViewModel internal constructor(
    private val repository: VideoParseRepository
) : ViewModel() {

    var uiState by mutableStateOf<VideoResolveUiState>(VideoResolveUiState.Idle)
        private set

    /** 多选：存的是 `medias` 下标（与界面列出的顺序一致） */
    var selected by mutableStateOf<Set<Int>>(emptySet())
        private set

    private var lastInput: String? = null

    fun resolve(text: String) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return
        lastInput = trimmed
        uiState = VideoResolveUiState.Loading
        selected = emptySet()
        viewModelScope.launch {
            uiState = try {
                val parsed = repository.parse(trimmed)
                if (parsed == null) {
                    VideoResolveUiState.Error("未识别到视频链接")
                } else {
                    VideoResolveUiState.Success(parsed)
                }
            } catch (e: VideoParseException) {
                VideoResolveUiState.Error(e.userMessage())
            } catch (e: Exception) {
                VideoResolveUiState.Error("解析失败：${e.message ?: "未知错误"}")
            }
        }
    }

    fun retry() {
        lastInput?.let { resolve(it) }
    }

    fun reset() {
        uiState = VideoResolveUiState.Idle
        selected = emptySet()
        lastInput = null
    }

    fun toggleSelect(index: Int) {
        selected = if (index in selected) selected - index else selected + index
    }

    fun selectAll() {
        val result = (uiState as? VideoResolveUiState.Success)?.result ?: return
        selected = result.medias.indices.toSet()
    }

    fun clearSelection() {
        selected = emptySet()
    }

    class Factory : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: KClass<T>, extras: CreationExtras): T {
            require(modelClass == VideoResolveViewModel::class)
            return VideoResolveViewModel(
                VideoParseRepository(
                    VideoAccountRepository(AppDatabase.get().videoAccountDao())
                )
            ) as T
        }
    }
}
