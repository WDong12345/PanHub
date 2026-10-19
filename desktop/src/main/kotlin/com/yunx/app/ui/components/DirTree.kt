package com.yunx.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** 目录树节点：各平台把自家模型映射成它，树组件不必认识任何一个网盘 */
data class DirNode(val id: String, val name: String)

/**
 * 树里的一行（按展开状态深度优先摊平后的可见行）。
 * [pathIds] / [pathNames] 是从根到本节点的链、不含虚拟根，与云盘 VM 的 dirStack/nameStack 同形：
 * 点行时原样交给 VM 整段替换浏览栈即可，不必逐级 openFolder。
 */
data class DirRow(
    val node: DirNode,
    val depth: Int,
    val pathIds: List<String>,
    val pathNames: List<String>
)

/**
 * 资源管理器式目录树的展开状态与子目录缓存。
 *
 * 状态挂在 ViewModel 而不是 Composable 里：网盘页切 Tab 时整块离开组成，UI 层的 remember 会丢，
 * 用户每切回来都要把刚才点开的分支重新点开一遍。子目录只在节点首次展开时拉一次。
 */
class DirTreeState(
    private val root: DirNode,
    private val scope: CoroutineScope,
    private val listSubDirs: suspend (String) -> List<DirNode>
) {
    private val children = mutableStateMapOf<String, List<DirNode>>()
    private val expanded = mutableStateMapOf<String, Boolean>()
    private val loading = mutableStateMapOf<String, Boolean>()

    /**
     * 树栏是否显示。放在这里而不是 Composable 里：网盘页切 Tab 时整块离开组成，
     * UI 层的 remember 会丢，用户收起的栏会在他切回来时自己弹出来。
     */
    var paneVisible by mutableStateOf(true)
        private set

    fun togglePane() {
        paneVisible = !paneVisible
    }

    /**
     * 可见行：深度优先摊平，折叠的分支不进列表。
     * [MAX_DEPTH] 兜住「服务端把某个目录的父级指回自己」这类脏数据造成的无限递归。
     */
    val rows: List<DirRow>
        get() = buildList {
            add(DirRow(root, 0, emptyList(), emptyList()))
            collect(this, root.id, 1, emptyList(), emptyList())
        }

    fun isLoading(dirId: String): Boolean = loading[dirId] == true

    fun isExpanded(dirId: String): Boolean = expanded[dirId] == true

    /** 展开 / 折叠某节点；首次展开时拉子目录 */
    fun toggle(row: DirRow) {
        val id = row.node.id
        if (expanded[id] == true) {
            expanded[id] = false
            return
        }
        expanded[id] = true
        scope.launch {
            // 失败就收回：不缓存错误态，用户再点一次即重试，界面不会留下一个点不开的假分支
            runCatching { ensureLoaded(id) }.onFailure { expanded[id] = false }
        }
    }

    /**
     * 展开并加载到 [pathIds] 这条路径，让树里看得见当前所在目录。
     * 面包屑回退、进入文件夹、转存后自动跳转都汇到这里，路径上每一级各拉一次子目录。
     */
    fun reveal(pathIds: List<String>) {
        if (pathIds.isEmpty() && expanded[root.id] == true) return
        scope.launch {
            expanded[root.id] = true
            runCatching {
                ensureLoaded(root.id)
                for (fid in pathIds) {
                    expanded[fid] = true
                    ensureLoaded(fid)
                }
            }
        }
    }

    /** 丢弃子目录缓存（刷新 / 新建文件夹 / 转存落地后调用），仍展开的分支重新拉一次 */
    fun invalidate() {
        children.clear()
        val openDirs = expanded.keys.filter { expanded[it] == true }
        scope.launch {
            for (dirId in openDirs) runCatching { ensureLoaded(dirId) }
        }
    }

    private suspend fun ensureLoaded(dirId: String) {
        if (children.containsKey(dirId)) return
        loading[dirId] = true
        try {
            children[dirId] = listSubDirs(dirId)
        } finally {
            loading[dirId] = false
        }
    }

    private fun collect(
        out: MutableList<DirRow>,
        dirId: String,
        depth: Int,
        pathIds: List<String>,
        pathNames: List<String>
    ) {
        if (depth > MAX_DEPTH || expanded[dirId] != true) return
        for (child in children[dirId].orEmpty()) {
            val nextIds = pathIds + child.id
            val nextNames = pathNames + child.name
            out.add(DirRow(child, depth, nextIds, nextNames))
            collect(out, child.id, depth + 1, nextIds, nextNames)
        }
    }

    private companion object {
        const val MAX_DEPTH = 20
    }
}

/**
 * 左侧目录树栏：懒加载、当前目录高亮、点行把右侧文件列表切到该目录。
 *
 * @param state 由所在页面的 ViewModel 持有（切 Tab 不丢展开状态）
 * @param selectedId 当前浏览目录 ID，用于高亮与滚动定位；根目录传根 ID
 * @param rootTitle 根节点显示名（各平台叫法不同：夸克「根目录」、115「全部文件」）
 */
@Composable
fun DirTreePanel(
    state: DirTreeState,
    selectedId: String,
    rootTitle: String,
    onSelect: (DirRow) -> Unit,
    modifier: Modifier = Modifier
) {
    val rows = state.rows
    val listState = rememberLazyListState()
    val selectedIndex = remember(rows, selectedId) { rows.indexOfFirst { it.node.id == selectedId } }

    // 切目录后把选中项滚进视野，否则深路径跳过去只看见树顶
    LaunchedEffect(selectedIndex, rows.size) {
        if (selectedIndex >= 0) listState.animateScrollToItem(selectedIndex)
    }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column {
            Text(
                text = "目录",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 14.dp, top = 14.dp, bottom = 6.dp)
            )
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 6.dp, end = 6.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                items(rows, key = { "${it.depth}/${it.pathIds.joinToString("/")}" }) { row ->
                    DirTreeRow(
                        row = row,
                        rootTitle = rootTitle,
                        selected = row.node.id == selectedId,
                        loading = state.isLoading(row.node.id),
                        expanded = state.isExpanded(row.node.id),
                        onToggle = { state.toggle(row) },
                        onClick = { onSelect(row) }
                    )
                }
            }
        }
    }
}

@Composable
private fun DirTreeRow(
    row: DirRow,
    rootTitle: String,
    selected: Boolean,
    loading: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 缩进体现层级；三角只管展开折叠，不切目录 —— 否则点折叠会顺手把右侧列表带走
        Spacer(modifier = Modifier.width((row.depth * 12).dp))
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(RoundedCornerShape(4.dp))
                .clickable(onClick = onToggle),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = if (expanded) "折叠" else "展开",
                modifier = Modifier
                    .size(16.dp)
                    .then(if (expanded) Modifier.rotate(90f) else Modifier),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(4.dp))
        Icon(
            imageVector = when {
                row.depth == 0 -> Icons.Outlined.Home
                expanded -> Icons.Outlined.FolderOpen
                else -> Icons.Outlined.Folder
            },
            contentDescription = null,
            modifier = Modifier.size(17.dp),
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = if (row.depth == 0) rootTitle else row.node.name,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(13.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}
