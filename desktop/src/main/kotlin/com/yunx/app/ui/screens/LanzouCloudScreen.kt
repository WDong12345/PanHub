package com.yunx.app.ui.screens

import com.yunx.app.ui.SnackbarController
import com.yunx.app.ui.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yunx.app.data.network.model.ShareFile
import com.yunx.app.data.network.model.ShareInfo
import com.yunx.app.ui.components.DirTreePanel
import com.yunx.app.ui.components.FadeAlertDialog
import com.yunx.app.ui.components.ScrollToTopButton
import com.yunx.app.ui.components.rememberDownloadDirPicker
import com.yunx.app.ui.items.MultiSelectAction
import com.yunx.app.ui.items.MultiSelectBar
import com.yunx.app.ui.resolve.BackToParentItem
import com.yunx.app.ui.resolve.CrumbBar
import com.yunx.app.ui.resolve.FileRowAction
import com.yunx.app.ui.resolve.ShareFileRow
import com.yunx.app.ui.viewmodel.LanzouCloudUiState
import com.yunx.app.ui.viewmodel.LanzouCloudViewModel
import com.yunx.app.util.DesktopActions

/**
 * 蓝奏云个人盘浏览页（镜像 123 云盘页）：
 * - 目录浏览 + 下拉刷新 + 面包屑回退
 * - 长按多选（批量下载/分享/移动/删除）
 * - 文件/文件夹操作菜单（下载/重命名/移动/分享/删除）+ 新建文件夹
 * 认证走整串登录 Cookie（ylogin + phpdisk_info），目录用 folder_id（根="-1"，文件夹 id 前缀 d:）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanzouCloudScreen(
    viewModel: LanzouCloudViewModel,
    scrollBehavior: TopAppBarScrollBehavior,
    onExit: () -> Unit,
    onDownloadStarted: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()
    BackHandler {
        val s = state
        if (s is LanzouCloudUiState.Loaded && s.pathNames.isNotEmpty()) viewModel.back() else onExit()
    }
    val listState = rememberLazyListState()
    var showActionSheet by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showMove by remember { mutableStateOf(false) }
    var showShare by remember { mutableStateOf(false) }
    val pickDownloadDir = rememberDownloadDirPicker()
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showCreateFolder by remember { mutableStateOf(false) }

    // 列表切到哪个目录，左侧树就把那条路径逐级展开并滚到看得见
    LaunchedEffect(state) {
        val s = state
        if (s is LanzouCloudUiState.Loaded) viewModel.dirTree.reveal(viewModel.currentPathFids)
    }

    LaunchedEffect(viewModel.cloudMessage) {
        viewModel.cloudMessage?.let {
            SnackbarController.show(it)
            viewModel.consumeMessage()
        }
    }

    LaunchedEffect(viewModel.downloadTriggered) {
        if (viewModel.downloadTriggered > 0) {
            viewModel.consumeDownloadTriggered()
            onDownloadStarted()
        }
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface
    ) {
        AnimatedContent(
            targetState = state,
            transitionSpec = {
                fadeIn(tween(200)) togetherWith fadeOut(tween(140))
            },
            label = "lanzouCloudState"
        ) { s ->
            when (s) {
                is LanzouCloudUiState.Loading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }

                is LanzouCloudUiState.Error -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = s.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 32.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(onClick = onExit) { Text("返回") }
                            TextButton(onClick = { viewModel.loadRoot() }) { Text("重试") }
                        }
                    }
                }

                is LanzouCloudUiState.Loaded -> Box(modifier = Modifier.fillMaxSize()) {
                    // 左树右列表：树只管「跳到哪个目录」，列表仍是该目录的内容，两者共用 VM。
                    // 覆盖层（回到顶部/批量栏）留在外层 Box 里：这版 Compose 只有 RowScope/ColumnScope 的
                    // AnimatedVisibility，被 Row 包着会让它去取无法跨 composable lambda 捕获的 RowScope 接收者。
                    Row(modifier = Modifier.fillMaxSize()) {
                    if (viewModel.dirTree.paneVisible) {
                        DirTreePanel(
                            state = viewModel.dirTree,
                            selectedId = s.dirId,
                            rootTitle = "蓝奏云",
                            onSelect = { row -> viewModel.openPath(row.pathIds, row.pathNames) },
                            modifier = Modifier
                                .width(232.dp)
                                .fillMaxHeight()
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .width(1.dp)
                                .background(MaterialTheme.colorScheme.outlineVariant)
                        )
                    }
                    LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxSize()
                                .nestedScroll(scrollBehavior.nestedScrollConnection),
                            contentPadding = PaddingValues(
                                start = 16.dp, end = 16.dp, top = 16.dp,
                                bottom = if (viewModel.multiSelectMode) 96.dp else 16.dp
                            ),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            item {
                                Column {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (viewModel.multiSelectMode) {
                                            IconButton(onClick = { viewModel.exitMultiSelect() }) {
                                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "取消选择")
                                            }
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = "已选 ${viewModel.selected.size} 项",
                                                    style = MaterialTheme.typography.titleMedium,
                                                    fontWeight = FontWeight.Medium
                                                )
                                                Text(
                                                    text = if (viewModel.selected.size == s.files.size) "已全选" else "点击选择更多文件",
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            TextButton(onClick = { viewModel.toggleSelectAll(s.files) }) {
                                                Text(if (viewModel.selected.size == s.files.size) "取消全选" else "全选")
                                            }
                                        } else {
                                            IconButton(onClick = onExit) {
                                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                                            }
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = "蓝奏云",
                                                    style = MaterialTheme.typography.titleMedium,
                                                    fontWeight = FontWeight.Medium,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = "共 ${s.files.size} 项",
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            // 目录树栏开关：窗口窄的时候收起来，把宽度让给文件列表
                                            IconButton(onClick = { viewModel.dirTree.togglePane() }) {
                                                Icon(
                                                    imageVector = Icons.Outlined.Layers,
                                                    contentDescription = if (viewModel.dirTree.paneVisible) "隐藏目录树" else "显示目录树",
                                                    tint = if (viewModel.dirTree.paneVisible)
                                                        MaterialTheme.colorScheme.primary
                                                    else
                                                        MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            // 新建文件夹入口（窗口内覆盖层输入）
                                            IconButton(onClick = { showCreateFolder = true }) {
                                                Icon(
                                                    imageVector = Icons.Outlined.CreateNewFolder,
                                                    contentDescription = "新建文件夹",
                                                    tint = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                            IconButton(onClick = { viewModel.refresh() }) {
                                                Icon(imageVector = Icons.Outlined.Refresh, contentDescription = "刷新")
                                            }
                                        }
                                    }
                                    if (!viewModel.multiSelectMode) {
                                        CrumbBar(
                                            rootTitle = "蓝奏云",
                                            pathNames = s.pathNames,
                                            onNavigate = { viewModel.navigateToLevel(it) }
                                        )
                                    }
                                }
                            }

                            if (s.pathNames.isNotEmpty()) {
                                item {
                                    BackToParentItem(onClick = { viewModel.back() })
                                }
                            }

                            if (s.files.isEmpty()) {
                                item {
                                    Text(
                                        text = "此目录为空",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 32.dp),
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }

                            items(s.files, key = { it.fid }) { file ->
                                ShareFileRow(
                                    file = file,
                                    modifier = Modifier.animateItem(),
                                    onClick = {
                                        if (viewModel.multiSelectMode) {
                                            viewModel.toggleSelect(file)
                                        } else if (file.isdir) {
                                            viewModel.openFolder(file)
                                        } else {
                                            viewModel.openActions(file)
                                            showActionSheet = true
                                        }
                                    },
                                    // 行尾内联操作：三点菜单里的动作直接铺到行上，一点到位
                                    // （多选模式收起，改由底部批量栏承担）
                                    actions = if (viewModel.multiSelectMode) emptyList() else buildList {
                                        add(FileRowAction(label = if (file.isdir) "下载文件夹" else "下载", icon = Icons.Outlined.Download) {
                                            viewModel.openActions(file)
                                            if (file.isdir) viewModel.downloadFolder() else viewModel.downloadFile()
                                            viewModel.dismissActions()
                                        })
                                        add(FileRowAction(label = "分享", icon = Icons.Outlined.Share) {
                                            viewModel.openActions(file)
                                            showShare = true
                                        })
                                        // 蓝奏接口不支持移动文件夹：目录行不给「移动到」（原先也是选完目标才报错）
                                        if (!file.isdir) {
                                            add(FileRowAction(label = "移动到", icon = Icons.Outlined.DriveFileMove) {
                                                viewModel.openActions(file)
                                                viewModel.openMoveRoot()
                                                showMove = true
                                            })
                                        }
                                        add(FileRowAction(label = "重命名", icon = Icons.Outlined.Edit) {
                                            viewModel.openActions(file)
                                            showRename = true
                                        })
                                        add(FileRowAction(label = "删除", icon = Icons.Outlined.Delete, destructive = true) {
                                            viewModel.openActions(file)
                                            showDeleteConfirm = true
                                        })
                                    },
                                    onLongClick = if (!viewModel.multiSelectMode) {
                                        { viewModel.enterMultiSelect(file) }
                                    } else {
                                        null
                                    },
                                    selected = viewModel.selected.contains(file),
                                    showCheckbox = viewModel.multiSelectMode
                                )
                            }
                        }
                    }

                    ScrollToTopButton(
                        listState = listState,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(
                                end = 16.dp,
                                bottom = if (viewModel.multiSelectMode) 104.dp else 16.dp
                            )
                    )

                    AnimatedVisibility(
                        visible = viewModel.multiSelectMode,
                        enter = slideInVertically(tween(220)) { it } + fadeIn(tween(220)),
                        exit = slideOutVertically(tween(180)) { it } + fadeOut(tween(180)),
                        modifier = Modifier.align(Alignment.BottomCenter)
                    ) {
                        MultiSelectBar(
                            busy = viewModel.isOperating,
                            count = viewModel.selected.size,
                            actions = listOf(
                                MultiSelectAction("下载", Icons.Outlined.Download, MaterialTheme.colorScheme.primary) {
                                    viewModel.downloadSelected()
                                },
                                MultiSelectAction("下载到…", Icons.Outlined.FolderOpen, MaterialTheme.colorScheme.primary) {
                                    pickDownloadDir { viewModel.downloadSelected(it) }
                                },
                                MultiSelectAction("分享", Icons.Outlined.Share, MaterialTheme.colorScheme.primary) {
                                    showShare = true
                                },
                                MultiSelectAction("移动", Icons.Outlined.DriveFileMove, MaterialTheme.colorScheme.primary) {
                                    viewModel.openMoveRoot()
                                    showMove = true
                                },
                                MultiSelectAction("删除", Icons.Outlined.Delete, MaterialTheme.colorScheme.error) {
                                    showDeleteConfirm = true
                                }
                            )
                        )
                    }
                }
            }
        }
    }

    // 文件操作菜单
    if (showActionSheet && viewModel.actionFile != null) {
        LanzouActionSheet(
            file = viewModel.actionFile!!,
            onDownload = {
                showActionSheet = false
                viewModel.downloadFile()
            },
            onDownloadTo = {
                pickDownloadDir { dir ->
                    showActionSheet = false
                    viewModel.downloadFileTo(dir)
                }
            },
            onDownloadFolder = {
                showActionSheet = false
                viewModel.downloadFolder()
            },
            onDownloadFolderTo = {
                pickDownloadDir { dir ->
                    showActionSheet = false
                    viewModel.downloadFolder(dir)
                }
            },
            onRename = {
                showActionSheet = false
                showRename = true
            },
            onMove = {
                showActionSheet = false
                viewModel.openMoveRoot()
                showMove = true
            },
            onShare = {
                showActionSheet = false
                showShare = true
            },
            onDelete = {
                showActionSheet = false
                showDeleteConfirm = true
            },
            onDismiss = {
                showActionSheet = false
                viewModel.dismissActions()
            }
        )
    }

    if (showRename && viewModel.actionFile != null) {
        LanzouRenameDialog(
            file = viewModel.actionFile!!,
            onConfirm = { name ->
                showRename = false
                viewModel.renameFile(name)
            },
            onDismiss = { showRename = false }
        )
    }

    if (showMove) {
        LanzouMoveSheet(
            viewModel = viewModel,
            onDismiss = { showMove = false }
        )
    }

    if (showShare) {
        LanzouShareSheet(
            viewModel = viewModel,
            onDismiss = { showShare = false }
        )
    }

    viewModel.shareResult?.let { info ->
        LanzouShareResultDialog(
            info = info,
            onDismiss = { viewModel.dismissShareResult() }
        )
    }

    if (showCreateFolder) {
        CloudCreateFolderDialog(
            onDismiss = { showCreateFolder = false },
            onConfirm = { name ->
                showCreateFolder = false
                viewModel.createFolder(name)
            }
        )
    }

    // 删除二次确认（窗口内覆盖层）
    FadeAlertDialog(
        visible = showDeleteConfirm,
        onDismissRequest = { showDeleteConfirm = false },
        title = { Text("删除文件") },
        text = {
            val deleting = if (viewModel.multiSelectMode) {
                "选中的 ${viewModel.selected.size} 项"
            } else {
                "「${viewModel.actionFile?.fname ?: ""}」"
            }
            Text("确定要删除$deleting 吗？删除后不可恢复。")
        },
        confirmButton = {
            TextButton(
                onClick = {
                    showDeleteConfirm = false
                    if (viewModel.multiSelectMode) viewModel.deleteSelected() else viewModel.deleteFile()
                }
            ) {
                Text("删除", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") }
        }
    )

    // 操作执行中加载弹窗（下载文件夹/批量下载显示进度；窗口内覆盖层）
    FadeAlertDialog(
        visible = viewModel.isOperating,
        onDismissRequest = { },
        title = { Text("处理中") },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = viewModel.folderProgress ?: "正在处理，请稍候…",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = { viewModel.cancelDownload() }) {
                Text("中断", color = MaterialTheme.colorScheme.error)
            }
        }
    )
}

/** 蓝奏文件操作菜单：下载/下载文件夹/分享/移动/重命名/删除 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanzouActionSheet(
    file: ShareFile,
    onDownload: () -> Unit,
    onDownloadTo: () -> Unit,
    onDownloadFolder: (() -> Unit)? = null,
    onDownloadFolderTo: (() -> Unit)? = null,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 32.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(40.dp),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (file.isdir) Icons.Outlined.DriveFileMove else Icons.Outlined.Download,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(file.fname, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium, maxLines = 1)
                    Text(
                        text = if (file.isdir) "文件夹" else "文件",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))
            if (!file.isdir) {
                LanzouActionItem(Icons.Outlined.Download, "下载", "使用内置下载功能保存到本机", MaterialTheme.colorScheme.primary, onDownload)
                LanzouActionItem(Icons.Outlined.FolderOpen, "下载到…", "选择本次保存的文件夹（不改全局设置）", MaterialTheme.colorScheme.primary, onDownloadTo)
            } else {
                if (onDownloadFolder != null) {
                    LanzouActionItem(Icons.Outlined.Download, "下载文件夹", "递归下载整个文件夹，保持目录结构", MaterialTheme.colorScheme.primary, onDownloadFolder)
                }
                if (onDownloadFolderTo != null) {
                    LanzouActionItem(Icons.Outlined.FolderOpen, "下载文件夹到…", "选择本次保存的文件夹（不改全局设置）", MaterialTheme.colorScheme.primary, onDownloadFolderTo)
                }
            }
            LanzouActionItem(Icons.Outlined.Share, "分享", "生成分享链接（提取码由蓝奏服务端生成）", MaterialTheme.colorScheme.primary, onShare)
            LanzouActionItem(Icons.Outlined.DriveFileMove, "移动到", "移动到网盘的其他目录", MaterialTheme.colorScheme.primary, onMove)
            LanzouActionItem(Icons.Outlined.Edit, "重命名", "修改文件名", MaterialTheme.colorScheme.primary, onRename)
            LanzouActionItem(Icons.Outlined.Delete, "删除", "删除文件（不可恢复）", MaterialTheme.colorScheme.error, onDelete)
        }
    }
}

@Composable
private fun LanzouActionItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    desc: String,
    tint: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(38.dp),
            shape = MaterialTheme.shapes.large,
            color = tint.copy(alpha = 0.12f)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 重命名弹窗（窗口内覆盖层） */
@Composable
private fun LanzouRenameDialog(
    file: ShareFile,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(file.fname) }
    FadeAlertDialog(
        visible = true,
        onDismissRequest = onDismiss,
        title = { Text("重命名") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("新名称") },
                singleLine = true,
                shape = MaterialTheme.shapes.large
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (name.isNotBlank() && name != file.fname) onConfirm(name.trim()) else onDismiss()
                },
                enabled = name.isNotBlank()
            ) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/** 移动目录选择弹窗（独立浏览，不影响主列表） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanzouMoveSheet(
    viewModel: LanzouCloudViewModel,
    onDismiss: () -> Unit
) {
    val moveState by viewModel.moveUiState.collectAsState()
    LaunchedEffect(Unit) { viewModel.openMoveRoot() }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 32.dp)
        ) {
            Text("移动到", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(8.dp))
            CrumbBar(
                rootTitle = "根目录",
                pathNames = (moveState as? LanzouCloudUiState.Loaded)?.pathNames ?: emptyList(),
                onNavigate = { viewModel.moveNavigateToLevel(it) }
            )
            Spacer(modifier = Modifier.height(8.dp))
            if ((moveState as? LanzouCloudUiState.Loaded)?.pathNames?.isNotEmpty() == true) {
                BackToParentItem(onClick = { viewModel.moveBack() })
                Spacer(modifier = Modifier.height(4.dp))
            }
            AnimatedContent(
                targetState = moveState,
                transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(140)) },
                label = "lanzouMoveState"
            ) { s ->
                when (s) {
                    is LanzouCloudUiState.Loading -> Box(
                        modifier = Modifier.fillMaxWidth().height(180.dp),
                        contentAlignment = Alignment.Center
                    ) { CircularProgressIndicator() }

                    is LanzouCloudUiState.Error -> Box(
                        modifier = Modifier.fillMaxWidth().height(140.dp),
                        contentAlignment = Alignment.Center
                    ) { Text(s.message, color = MaterialTheme.colorScheme.onSurfaceVariant) }

                    is LanzouCloudUiState.Loaded -> {
                        val dirs = s.files.filter { it.isdir }
                        if (dirs.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxWidth().height(90.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "当前目录没有子文件夹，可直接移动到此处",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                items(dirs, key = { it.fid }) { dir ->
                                    ShareFileRow(file = dir, onClick = { viewModel.openMoveFolder(dir) })
                                }
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            val dirName = (moveState as? LanzouCloudUiState.Loaded)?.pathNames?.lastOrNull() ?: "根目录"
            Button(
                onClick = {
                    val to = (moveState as? LanzouCloudUiState.Loaded)?.dirId ?: "-1"
                    if (viewModel.multiSelectMode) viewModel.moveSelected(to) else viewModel.moveFile(to)
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth().height(50.dp)
            ) {
                Icon(Icons.Outlined.DriveFileMove, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("移动到此处（$dirName）")
            }
        }
    }
}

/** 分享弹窗（蓝奏由服务端生成提取码，无有效期/提取码选项） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanzouShareSheet(
    viewModel: LanzouCloudViewModel,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 32.dp)
        ) {
            Text("分享文件", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                if (viewModel.multiSelectMode) "已选 ${viewModel.selected.size} 项（一次仅分享第一项）" else "分享到蓝奏云链接",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "蓝奏云的提取码由服务端生成、不可自定义；创建后展示在结果中。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(20.dp))
            Button(
                onClick = {
                    if (viewModel.multiSelectMode) {
                        viewModel.shareSelected()
                    } else {
                        viewModel.shareFile()
                    }
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth().height(50.dp)
            ) {
                Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("创建分享")
            }
        }
    }
}

/** 分享结果：链接 + 提取码 + 复制（窗口内覆盖层） */
@Composable
private fun LanzouShareResultDialog(
    info: ShareInfo,
    onDismiss: () -> Unit
) {
    val shareText = buildString {
        append("我用蓝奏云分享了「${info.title}」\n")
        append("链接：${info.shareUrl}")
        if (info.passcode.isNotBlank()) append("\n提取码：${info.passcode}")
    }
    FadeAlertDialog(
        visible = true,
        onDismissRequest = onDismiss,
        title = { Text("分享成功") },
        text = {
            Column {
                Text(
                    text = shareText,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    lineHeight = 22.sp
                )
                if (info.passcode.isBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "该分享无需提取码",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    DesktopActions.copyToClipboard(shareText)
                    SnackbarController.show("分享文案已复制")
                }
            ) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("复制全部")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        }
    )
}
