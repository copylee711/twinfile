package app.twinfile.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.twinfile.fs.FEntry
import app.twinfile.fs.Paths
import kotlin.math.abs

const val PARENT_KEY = "\u0000.."

/**
 * One file list. Gestures: tap opens (or toggles while selecting), long press opens the item
 * menu, and a sideways swipe on a row selects it — keep the finger down and move up or down
 * to paint more rows with the same state.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PaneView(
    vm: MainViewModel,
    pane: PaneState,
    focused: Boolean,
    onLongPress: (FEntry) -> Unit,
    onOpenFile: (FEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val twin = LocalTwin.current
    val compact = vm.prefs.compact
    val rows = pane.visible
    val showParent = pane.path != "/" && pane.path.isNotEmpty()
    val shape = RoundedCornerShape(14.dp)
    val touchSlop = LocalViewConfiguration.current.touchSlop

    Column(
        modifier
            .clip(shape)
            .background(if (focused) twin.paneFocused else twin.pane)
            .border(if (focused) 1.5.dp else 0.dp, if (focused) twin.paneBorder.copy(alpha = 0.55f) else twin.pane, shape)
    ) {
        PaneHeader(pane, focused)
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                state = pane.listState,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(pane, rows, showParent) {
                        swipeSelect(pane.listState, touchSlop,
                            isSelected = { it in pane.selected },
                            onFocus = { vm.focus = pane.index },
                            set = { key, on -> vm.select(pane, key, on) })
                    },
            ) {
                if (showParent) {
                    item(key = PARENT_KEY) {
                        ParentRow(compact) { vm.focus = pane.index; vm.up(pane) }
                    }
                }
                items(rows, key = { it.name }) { e ->
                    FileRow(
                        vm, pane, e, compact,
                        selected = e.name in pane.selected,
                        onClick = {
                            vm.focus = pane.index
                            when {
                                pane.selecting -> vm.toggle(pane, e.name)
                                e.isDir -> vm.enter(pane, e)
                                else -> onOpenFile(e)
                            }
                        },
                        onLongClick = { vm.focus = pane.index; onLongPress(e) },
                    )
                }
                item(key = "\u0000end") { Spacer(Modifier.height(72.dp)) }
            }
            when {
                pane.loading && rows.isEmpty() -> CircularProgressIndicator(Modifier.align(Alignment.Center).padding(24.dp))
                pane.error != null -> Text(
                    pane.error!!, Modifier.align(Alignment.Center).padding(16.dp),
                    color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center, fontSize = 13.sp,
                )
                rows.isEmpty() && !pane.loading -> Text(
                    if (pane.filter.isNotBlank()) "没有匹配「${pane.filter}」的文件" else "空文件夹",
                    Modifier.align(Alignment.Center).padding(16.dp), color = twin.muted, fontSize = 13.sp,
                )
            }
        }
    }
}

@Composable
private fun PaneHeader(pane: PaneState, focused: Boolean) {
    val twin = LocalTwin.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.width(3.dp).height(14.dp).clip(RoundedCornerShape(2.dp))
                .background(if (focused) twin.paneBorder else twin.muted.copy(alpha = 0.3f))
        )
        Spacer(Modifier.width(6.dp))
        Text(
            if (pane.path == "/") "/" else Paths.name(pane.path),
            fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (focused) MaterialTheme.colorScheme.onSurface else twin.muted,
            modifier = Modifier.weight(1f),
        )
        if (pane.filter.isNotBlank()) {
            Text("筛选", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ParentRow(compact: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = if (compact) 5.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ParentIcon(if (compact) 30.dp else 38.dp)
        Spacer(Modifier.width(8.dp))
        Text("..", fontSize = 15.sp)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    vm: MainViewModel,
    pane: PaneState,
    e: FEntry,
    compact: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val twin = LocalTwin.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) twin.selection else androidx.compose.ui.graphics.Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 8.dp, vertical = if (compact) 4.dp else 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FileIcon(pane.source, pane.path, e, if (compact) 30.dp else 38.dp)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(
                e.name, fontSize = if (compact) 13.sp else 14.sp, lineHeight = 17.sp,
                maxLines = 2, overflow = TextOverflow.MiddleEllipsis,
                color = if (e.hidden) twin.muted else MaterialTheme.colorScheme.onSurface,
            )
            if (!compact || !e.isDir) {
                val meta = if (e.isDir) Format.time(e.mtime) else "${Format.time(e.mtime)}  ${Format.size(e.size)}"
                Text(meta, fontSize = 11.sp, color = twin.muted, maxLines = 1)
            }
        }
    }
}

/**
 * Sideways drag on a row toggles it; moving vertically afterwards applies the same state to
 * every row the finger crosses. Vertical drags are left to the list's own scrolling.
 */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.swipeSelect(
    list: LazyListState,
    slop: Float,
    isSelected: (String) -> Boolean,
    onFocus: () -> Unit,
    set: (String, Boolean) -> Unit,
) {
    fun keyAt(y: Float): Pair<Int, String>? {
        val item = list.layoutInfo.visibleItemsInfo.firstOrNull { y >= it.offset && y < it.offset + it.size } ?: return null
        val key = item.key as? String ?: return null
        if (key.startsWith("\u0000")) return null
        return item.index to key
    }
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val start = keyAt(down.position.y) ?: return@awaitEachGesture
        var dx = 0f
        var dy = 0f
        var painting = false
        var mode = true
        var lastIndex = start.first
        while (true) {
            val ev = awaitPointerEvent(PointerEventPass.Initial)
            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
            if (!ch.pressed) break
            if (!painting) {
                val d = ch.positionChange()
                dx += d.x
                dy += d.y
                if (abs(dy) > slop && abs(dy) > abs(dx)) break // a scroll: not ours
                if (abs(dx) > slop * 1.5f && abs(dx) > abs(dy) * 1.5f) {
                    painting = true
                    onFocus()
                    mode = !isSelected(start.second)
                    set(start.second, mode)
                }
            }
            if (painting) {
                ch.consume()
                val hit = keyAt(ch.position.y)
                if (hit != null && hit.first != lastIndex) {
                    // Fill rows skipped by a fast move.
                    val range = if (hit.first > lastIndex) (lastIndex + 1)..hit.first else hit.first until lastIndex
                    for (info in list.layoutInfo.visibleItemsInfo) {
                        val k = info.key as? String ?: continue
                        if (info.index in range && !k.startsWith("\u0000")) set(k, mode)
                    }
                    lastIndex = hit.first
                }
            }
        }
    }
}
