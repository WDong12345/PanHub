package com.yunx.app.ui.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import kotlin.reflect.KClass
import com.yunx.app.data.download.DownloadManager
import com.yunx.app.data.download.DownloadPlatform
import com.yunx.app.data.network.BaiduApi
import com.yunx.app.data.network.BaiduConstants
import com.yunx.app.data.network.model.ShareExpire
import com.yunx.app.data.network.model.ShareFile
import com.yunx.app.data.network.model.ShareInfo
import com.yunx.app.ui.components.DirNode
import com.yunx.app.ui.components.DirTreeState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 百度网盘云盘浏览 UI 状态 */
sealed interface BaiduCloudUiState {
    data object Loading : BaiduCloudUiState
    data class Loaded(
        val files: List<ShareFile>,
        val pathNames: List<String>,
        /** 当前目录绝对路径（根="/"） */
        val dirPath: String
    ) : BaiduCloudUiState
    data class Error(val message: String) : BaiduCloudUiState
}

/**
 * 百度网盘云盘浏览 ViewModel（参考夸克/UC/迅雷云盘）：
 * - 目录浏览（根/子目录/面包屑回退）+ 下拉刷新
 * - 文件操作：下载 / 重命名 / 移动 / 创建分享 / 删除 + 长按多选批量
 * 认证走 Cookie（BDUSS），目录用绝对路径，文件标识 fs_id + path。
 */
class BaiduCloudViewModel(
    private val api: BaiduApi,
    private val cookieProvider: suspend () -> String?,
    private val downloadManager: DownloadManager
) : ViewModel() {

    private val _uiState = MutableStateFlow<BaiduCloudUiState>(BaiduCloudUiState.Loading)
    val uiState: StateFlow<BaiduCloudUiState> = _uiState.asStateFlow()

    var actionFile by mutableStateOf<ShareFile?>(null)
        private set
    var cloudMessage by mutableStateOf<String?>(null)
        private set
    var isOperating by mutableStateOf(false)
        private set
    var folderProgress by mutableStateOf<String?>(null)
        private set
    private var downloadCancelRequested = false
    var refreshing by mutableStateOf(false)
        private set
    var downloadTriggered by mutableStateOf(0)
        private set
    var shareResult by mutableStateOf<ShareInfo?>(null)
        private set
    var multiSelectMode by mutableStateOf(false)
        private set
    private val _selected = mutableStateListOf<ShareFile>()
    val selected: List<ShareFile> get() = _selected

    private val dirStack = ArrayDeque<String>()
    private val nameStack = ArrayDeque<String>()

    private val _moveUiState = MutableStateFlow<BaiduCloudUiState>(BaiduCloudUiState.Loading)
    val moveUiState: StateFlow<BaiduCloudUiState> = _moveUiState.asStateFlow()
    private val moveDirStack = ArrayDeque<String>()
    private val moveNameStack = ArrayDeque<String>()

    init {
        loadRoot()
    }

    private suspend fun cookie(): String =
        cookieProvider() ?: throw IllegalStateException("请先登录百度网盘")

    // ---------- 目录浏览 ----------

    fun loadRoot() {
        dirStack.clear()
        nameStack.clear()
        load("/", emptyList())
    }

    fun openFolder(file: ShareFile) {
        val path = file.fidToken
        dirStack.addLast(path)
        nameStack.addLast(file.fname)
        load(path, nameStack.toList())
    }

    fun back() {
        if (nameStack.isEmpty()) {
            loadRoot()
            return
        }
        dirStack.removeLast()
        nameStack.removeLast()
        load(dirStack.lastOrNull() ?: "/", nameStack.toList())
    }

    fun navigateToLevel(level: Int) {
        while (nameStack.size > level) {
            dirStack.removeLast()
            nameStack.removeLast()
        }
        load(dirStack.lastOrNull() ?: "/", nameStack.toList())
    }

    // ---------- 左侧目录树 ----------

    /**
     * 当前浏览目录的绝对路径链（不含根 "/"），左侧树据此逐级展开并高亮。
     * 百度拿绝对路径当目录主键（fidToken 即子目录全路径），比 fid 平台少一层反查：链末元素直接就是当前目录。
     */
    val currentPathFids: List<String> get() = dirStack.toList()

    /** 目录树状态：根 id 与 loadRoot() 的 "/" 对齐，点开节点就按子目录绝对路径现拉它的下一级 */
    val dirTree = DirTreeState(DirNode("/", "百度网盘"), viewModelScope) { listSubDirs(it) }

    /**
     * 按绝对路径列子目录（树懒加载）：fidToken 是百度返回的子目录全路径，直接当节点 id，无需再拼路径。
     * 注意 listCloudFiles 内部写死 num=100&page=1，单个目录一次最多取回 100 条（既有接口行为，不为此改网络层）。
     */
    suspend fun listSubDirs(dirPath: String): List<DirNode> {
        val cookie = cookieProvider() ?: return emptyList()
        return api.listCloudFiles(dirPath, cookie)
            .filter { it.isdir }
            .map { DirNode(it.fidToken, it.fname) }
    }

    /** 整段替换浏览栈跳到指定路径（树上点任意节点）：pathIds 为绝对路径链、末元素即当前目录，空链回根 "/" */
    fun openPath(pathIds: List<String>, pathNames: List<String>) {
        dirStack.clear()
        nameStack.clear()
        pathIds.forEach { dirStack.addLast(it) }
        pathNames.forEach { nameStack.addLast(it) }
        load(pathIds.lastOrNull() ?: "/", pathNames)
    }

    // ---------- 多选 ----------

    fun enterMultiSelect(file: ShareFile) {
        multiSelectMode = true
        _selected.clear()
        _selected.add(file)
    }

    fun toggleSelect(file: ShareFile) {
        if (_selected.contains(file)) _selected.remove(file) else _selected.add(file)
    }

    fun toggleSelectAll(files: List<ShareFile>) {
        if (_selected.size == files.size) _selected.clear()
        else {
            _selected.clear()
            _selected.addAll(files)
        }
    }

    fun exitMultiSelect() {
        multiSelectMode = false
        _selected.clear()
    }

    fun openActions(file: ShareFile) {
        actionFile = file
    }

    fun dismissActions() {
        actionFile = null
    }

    fun consumeMessage() {
        cloudMessage = null
    }

    fun dismissShareResult() {
        shareResult = null
    }

    fun consumeDownloadTriggered() {
        downloadTriggered = 0
    }

    /** 中断当前下载（批量下载/文件夹下载） */
    fun cancelDownload() {
        downloadCancelRequested = true
    }

    // ---------- 移动目标浏览 ----------

    fun openMoveRoot() {
        moveDirStack.clear()
        moveNameStack.clear()
        moveLoad("/", emptyList())
    }

    fun openMoveFolder(file: ShareFile) {
        moveDirStack.addLast(file.fidToken)
        moveNameStack.addLast(file.fname)
        moveLoad(file.fidToken, moveNameStack.toList())
    }

    fun moveBack() {
        if (moveNameStack.isEmpty()) return
        moveDirStack.removeLast()
        moveNameStack.removeLast()
        moveLoad(moveDirStack.lastOrNull() ?: "/", moveNameStack.toList())
    }

    fun moveNavigateToLevel(level: Int) {
        while (moveNameStack.size > level) {
            moveDirStack.removeLast()
            moveNameStack.removeLast()
        }
        moveLoad(moveDirStack.lastOrNull() ?: "/", moveNameStack.toList())
    }

    private fun moveLoad(dirPath: String, pathNames: List<String>) {
        _moveUiState.value = BaiduCloudUiState.Loading
        viewModelScope.launch {
            try {
                val files = api.listCloudFiles(dirPath, cookie()) ?: emptyList()
                _moveUiState.value = BaiduCloudUiState.Loaded(files, pathNames, dirPath)
            } catch (e: Exception) {
                _moveUiState.value = BaiduCloudUiState.Error(e.message ?: "加载失败")
            }
        }
    }

    // ---------- 单文件操作 ----------

    /** 百度下载直链的请求头（Cookie 用于鉴权；UA 用浏览器 UA，CDN 不校验 UA） */
    private fun downloadHeaders(cookie: String): Map<String, String> = mapOf(
        "Cookie" to cookie,
        "User-Agent" to BaiduConstants.UA_WEB
    )

    /**
     * 递归收集文件夹内所有文件（保持目录结构）。
     * 百度目录用绝对路径（dirPath），文件夹路径在 fidToken 字段。
     */
    private suspend fun collectFolderFiles(
        dirPath: String,
        prefix: String,
        cookie: String,
        result: MutableList<Pair<ShareFile, String>>,
        depth: Int
    ) {
        if (depth > 12) return
        val list = runCatching { api.listCloudFiles(dirPath, cookie) ?: emptyList() }
            .getOrDefault(emptyList())
        list.filter { !it.isdir }.forEach { result.add(it to "$prefix/${it.fname}") }
        list.filter { it.isdir }.forEach {
            collectFolderFiles(it.fidToken, "$prefix/${it.fname}", cookie, result, depth + 1)
        }
    }

    /** 下载整个文件夹（操作菜单）：递归收集所有文件，保持目录结构保存到 Download */
    fun downloadFolder() {
        val folder = actionFile ?: return
        if (!folder.isdir) return
        viewModelScope.launch {
            isOperating = true
            folderProgress = "正在收集文件…"
            downloadCancelRequested = false
            try {
                val cookie = cookie()
                val tasks = mutableListOf<Pair<ShareFile, String>>()
                collectFolderFiles(folder.fidToken, folder.fname, cookie, tasks, 0)
                if (tasks.isEmpty()) {
                    cloudMessage = "文件夹为空"
                    actionFile = null
                    return@launch
                }
                var okCount = 0
                tasks.forEachIndexed { index, (file, relPath) ->
                    // 用户点击「中断」：跳过剩余项（已入队任务保留下载）
                    if (downloadCancelRequested) return@forEachIndexed
                    folderProgress = "正在加入下载 ${index + 1}/${tasks.size}"
                    runCatching {
                        val link = api.locateDownload(file.fidToken, cookie)
                        downloadManager.enqueue(
                            url = link,
                            fileName = relPath, // 相对路径：Download/文件夹A/子目录/文件.mp4
                            size = file.fsize,
                            platform = DownloadPlatform.BAIDU,
                            headers = downloadHeaders(cookie)
                        )
                        okCount++
                    }
                }
                if (downloadCancelRequested) {
                    cloudMessage = "已中断下载"
                    actionFile = null
                    return@launch
                }
                cloudMessage = "已加入 $okCount 个下载任务"
                actionFile = null
            } catch (e: Exception) {
                cloudMessage = e.message ?: "下载文件夹失败"
            } finally {
                isOperating = false
                folderProgress = null
                downloadCancelRequested = false
            }
        }
    }

    /** 下载文件：取直链后直接入队并切到下载页；直链仍可在下载页右键复制 */
    fun downloadFile() {
        val file = actionFile ?: return
        viewModelScope.launch {
            isOperating = true
            try {
                val url = api.locateDownload(file.fidToken, cookie())
                downloadManager.enqueue(
                    url = url,
                    fileName = file.fname,
                    size = file.fsize,
                    platform = DownloadPlatform.BAIDU,
                    headers = mapOf(
                        "Cookie" to cookie(),
                        "User-Agent" to BaiduConstants.UA_WEB
                    )
                )
                cloudMessage = "已加入下载：${file.fname}"
                actionFile = null
                downloadTriggered++
            } catch (e: Exception) {
                cloudMessage = e.message ?: "下载失败"
            } finally {
                isOperating = false
            }
        }
    }

    /** 重命名 */
    fun renameFile(newName: String) {
        val file = actionFile ?: return
        viewModelScope.launch {
            isOperating = true
            try {
                if (api.renameFile(file.fidToken, newName, cookie())) {
                    cloudMessage = "已重命名"
                    actionFile = null
                    reloadCurrent()
                } else {
                    cloudMessage = "重命名失败"
                }
            } catch (e: Exception) {
                cloudMessage = e.message ?: "重命名失败"
            } finally {
                isOperating = false
            }
        }
    }

    /** 新建文件夹（当前目录下）；百度按**绝对路径**操作，不是 fid */
    fun createFolder(name: String) {
        val newName = name.trim()
        if (newName.isEmpty()) return
        val parentPath = (uiState.value as? BaiduCloudUiState.Loaded)?.dirPath ?: "/"
        // 根目录 "/" 不能拼成 "//名字"
        val path = parentPath.trimEnd('/') + "/" + newName
        viewModelScope.launch {
            isOperating = true
            try {
                if (api.createDir(path, cookie())) {
                    cloudMessage = "已创建文件夹「$newName」"
                    reloadCurrent()
                } else {
                    cloudMessage = "新建文件夹失败"
                }
            } catch (e: Exception) {
                cloudMessage = e.message ?: "新建文件夹失败"
            } finally {
                isOperating = false
            }
        }
    }

    /** 移动 */
    fun moveFile(toDirPath: String) {
        val file = actionFile ?: return
        viewModelScope.launch {
            isOperating = true
            try {
                api.moveFiles(listOf(file.fidToken), toDirPath, cookie())
                cloudMessage = "已移动到目标目录"
                actionFile = null
                kotlinx.coroutines.delay(1500)
                reloadCurrent()
            } catch (e: Exception) {
                cloudMessage = e.message ?: "移动失败"
            } finally {
                isOperating = false
            }
        }
    }

    /**
     * 创建分享（百度必须带 4 位提取码）。
     *
     * @param expiredType UI 中性码（[ShareExpire]），**必须转成百度 period**（0/1/7/30，0=永久）：
     *   直接下发中性码会让「永久」变成 1 天、「7 天」变成 3 天。
     */
    fun shareFile(expiredType: Int, pwd: String) {
        val file = actionFile ?: return
        viewModelScope.launch {
            isOperating = true
            try {
                val result = api.createShare(listOf(file.fid), ShareExpire.baiduPeriod(expiredType), pwd, cookie())
                shareResult = ShareInfo(
                    shareUrl = result.link,
                    passcode = result.pwd,
                    pwdId = result.shareId,
                    title = file.fname,
                    // 优先用服务端回填的真实有效期，响应没给才回退到用户所选值
                    expiredType = result.expiredType?.let { expireType(it) } ?: expiredType
                )
            } catch (e: Exception) {
                cloudMessage = e.message ?: "分享失败"
            } finally {
                isOperating = false
            }
        }
    }

    /** 删除 */
    fun deleteFile() {
        val file = actionFile ?: return
        viewModelScope.launch {
            isOperating = true
            try {
                api.deleteFiles(listOf(file.fidToken), cookie())
                cloudMessage = "已删除「${file.fname}」"
                actionFile = null
                kotlinx.coroutines.delay(1200)
                reloadCurrent()
            } catch (e: Exception) {
                cloudMessage = e.message ?: "删除失败"
            } finally {
                isOperating = false
            }
        }
    }

    // ---------- 批量操作 ----------

    /** 批量下载（不切页；选中文件夹时递归下载整个文件夹并保持目录结构） */
    fun downloadSelected() {
        val files = _selected.toList()
        if (files.isEmpty()) return
        viewModelScope.launch {
            isOperating = true
            folderProgress = "正在收集文件…"
            downloadCancelRequested = false
            try {
                val cookie = cookie()
                // 展开选中项：文件直接加入，文件夹递归收集
                val tasks = mutableListOf<Pair<ShareFile, String>>()
                for (file in files) {
                    if (file.isdir) {
                        collectFolderFiles(file.fidToken, file.fname, cookie, tasks, 0)
                    } else {
                        tasks.add(file to file.fname)
                    }
                }
                if (tasks.isEmpty()) {
                    cloudMessage = "所选文件夹为空"
                    exitMultiSelect()
                    return@launch
                }
                var okCount = 0
                var failCount = 0
                tasks.forEachIndexed { index, (file, relPath) ->
                    // 用户点击「中断」：跳过剩余项（已入队任务保留下载）
                    if (downloadCancelRequested) return@forEachIndexed
                    folderProgress = "正在加入下载 ${index + 1}/${tasks.size}"
                    runCatching {
                        val link = api.locateDownload(file.fidToken, cookie)
                        downloadManager.enqueue(
                            url = link,
                            fileName = if (relPath.contains('/')) relPath else file.fname,
                            size = file.fsize,
                            platform = DownloadPlatform.BAIDU,
                            headers = downloadHeaders(cookie)
                        )
                        okCount++
                    }
                }
                if (downloadCancelRequested) {
                    cloudMessage = "已中断批量下载"
                    exitMultiSelect()
                    return@launch
                }
                cloudMessage = if (failCount > 0) {
                    "已加入 $okCount 个下载任务（$failCount 个失败）"
                } else {
                    "已加入 $okCount 个下载任务"
                }
                exitMultiSelect()
            } catch (e: Exception) {
                cloudMessage = e.message ?: "批量下载失败"
            } finally {
                isOperating = false
                folderProgress = null
                downloadCancelRequested = false
            }
        }
    }

    /** 批量分享（@param expiredType UI 中性码，转换见 [shareFile]） */
    fun shareSelected(expiredType: Int, pwd: String) {
        val files = _selected.toList()
        if (files.isEmpty()) return
        viewModelScope.launch {
            isOperating = true
            try {
                val result = api.createShare(
                    files.map { it.fid }, ShareExpire.baiduPeriod(expiredType), pwd, cookie()
                )
                shareResult = ShareInfo(
                    shareUrl = result.link,
                    passcode = result.pwd,
                    pwdId = result.shareId,
                    title = if (files.size == 1) files[0].fname else "分享 ${files.size} 个文件",
                    expiredType = result.expiredType?.let { expireType(it) } ?: expiredType
                )
                exitMultiSelect()
            } catch (e: Exception) {
                cloudMessage = e.message ?: "分享失败"
            } finally {
                isOperating = false
            }
        }
    }

    /** 批量移动 */
    fun moveSelected(toDirPath: String) {
        val files = _selected.toList()
        if (files.isEmpty()) return
        viewModelScope.launch {
            isOperating = true
            try {
                api.moveFiles(files.map { it.fidToken }, toDirPath, cookie())
                cloudMessage = "已移动 ${files.size} 项"
                exitMultiSelect()
                kotlinx.coroutines.delay(1500)
                reloadCurrent()
            } catch (e: Exception) {
                cloudMessage = e.message ?: "移动失败"
            } finally {
                isOperating = false
            }
        }
    }

    /** 批量删除 */
    fun deleteSelected() {
        val files = _selected.toList()
        if (files.isEmpty()) return
        viewModelScope.launch {
            isOperating = true
            try {
                api.deleteFiles(files.map { it.fidToken }, cookie())
                cloudMessage = "已删除 ${files.size} 项"
                exitMultiSelect()
                kotlinx.coroutines.delay(1200)
                reloadCurrent()
            } catch (e: Exception) {
                cloudMessage = e.message ?: "删除失败"
            } finally {
                isOperating = false
            }
        }
    }

    // ---------- 内部 ----------

    /**
     * 下拉刷新当前目录（即使是 Error 态也能刷新当前路径，避免用户必须退回根目录重试）。
     * Loading 态直接忽略，避免叠加并发加载。
     */
    fun refresh() {
        dirTree.invalidate()
        val current = uiState.value
        if (current is BaiduCloudUiState.Loading) return
        // Loaded → 取其 dirPath/pathNames；Error/Loading → 退回根目录字段
        val dirPath = (current as? BaiduCloudUiState.Loaded)?.dirPath ?: "/"
        val pathNames = (current as? BaiduCloudUiState.Loaded)?.pathNames ?: emptyList()
        refreshing = true
        viewModelScope.launch {
            try {
                val files = api.listCloudFiles(dirPath, cookie())
                _uiState.value = BaiduCloudUiState.Loaded(files, pathNames, dirPath)
            } catch (e: Exception) {
                cloudMessage = e.message ?: "刷新失败"
            } finally {
                refreshing = false
            }
        }
    }

    private fun reloadCurrent() {
        // 增删改移之后目录树必须跟着重取，否则树上还挂着已删的目录、也看不见新建的
        dirTree.invalidate()
        val current = uiState.value
        if (current is BaiduCloudUiState.Loaded) {
            load(current.dirPath, current.pathNames)
        } else {
            loadRoot()
        }
    }

    /**
     * 加载指定目录：首次失败自动重试一次（500ms 后），仍失败置 Error 态。
     * 对齐 fork 的容错策略，应对偶发的 errno=-6/限流瞬时抖动。
     */
    private fun load(dirPath: String, pathNames: List<String>) {
        _uiState.value = BaiduCloudUiState.Loading
        viewModelScope.launch {
            var lastError: Exception? = null
            repeat(2) { attempt ->
                try {
                    val files = api.listCloudFiles(dirPath, cookie())
                    _uiState.value = BaiduCloudUiState.Loaded(files, pathNames, dirPath)
                    return@launch
                } catch (e: Exception) {
                    lastError = e
                    if (attempt < 1) kotlinx.coroutines.delay(500)
                }
            }
            _uiState.value = BaiduCloudUiState.Error(lastError?.message ?: "加载失败")
        }
    }

    /** 百度 period → ShareInfo.expiredType（0永久/1一天/7七天/30三十天 → 中性码）；其他取值显示为「未知」 */
    private fun expireType(period: Int): Int = when (period) {
        0 -> ShareExpire.FOREVER
        1 -> ShareExpire.ONE_DAY
        7 -> ShareExpire.SEVEN_DAYS
        30 -> ShareExpire.THIRTY_DAYS
        else -> ShareExpire.UNKNOWN
    }

    class Factory(
        private val api: BaiduApi,
        private val cookieProvider: suspend () -> String?,
        private val downloadManager: DownloadManager
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: KClass<T>, extras: CreationExtras): T =
            BaiduCloudViewModel(api, cookieProvider, downloadManager) as T
    }
}