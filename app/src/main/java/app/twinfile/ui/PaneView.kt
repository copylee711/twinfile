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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.runtime.getValue
import android.view.HapticFeedbackConstants
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
    showHeader: Boolean = true,
) {
    val twin = LocalTwin.current
    val compact = vm.prefs.compact
    val rows = pane.visible
    val showParent = pane.path.isNotEmpty() && vm.canGoUp(pane)
    val shape = RoundedCornerShape(14.dp)
    val touchSlop = LocalViewConfiguration.current.touchSlop
    val view = LocalView.current
    val enterPx = with(LocalDensity.current) { 56.dp.toPx() }

    Column(
        modifier
            .clip(shape)
            .background(if (focused) twin.paneFocused else twin.pane)
            .border(if (focused) 1.5.dp else 0.dp, if (focused) twin.paneBorder.copy(alpha = 0.55f) else twin.pane, shape)
            // Any touch inside a pane (rows, empty space, header, a scroll) makes it the active one.
            .pointerInput(pane.index) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    vm.focus = pane.index
                }
            }
    ) {
        if (showHeader) PaneHeader(vm.label(pane), pane, focused)
        // Folder change: the new listing slides in from the side it came from and fades up.
        val slide = remember(pane) { Animatable(0f) }
        LaunchedEffect(pane.navTick) {
            if (pane.navTick == 0 || !vm.prefs.animateNav) return@LaunchedEffect
            slide.snapTo(pane.navDir.toFloat())
            slide.animateTo(0f, tween(240, easing = FastOutSlowInEasing))
        }
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                state = pane.listState,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationX = slide.value * size.width * 0.18f
                        alpha = 1f - abs(slide.value) * 0.75f
                    }
                    .pointerInput(pane, rows, showParent) {
                        swipeSelect(pane.listState, touchSlop, enterPx,
                            selecting = { pane.selecting },
                            isSelected = { it in pane.selected },
                            onFocus = { vm.focus = pane.index },
                            onDrag = { key, dx -> pane.swipeKey = key; pane.swipeOffset = dx },
                            onEnter = { view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) },
                            set = { key, on -> vm.select(pane, key, on) })
                    },
            ) {
                if (showParent) {
                    val parentContent: @Composable () -> Unit = {
                        Box(Modifier.fillMaxWidth().background(if (focused) twin.paneFocused else twin.pane)) {
                            ParentRow(compact) { vm.focus = pane.index; vm.up(pane) }
                        }
                    }
                    if (vm.prefs.stickyParent) stickyHeader(key = PARENT_KEY) { parentContent() }
                    else item(key = PARENT_KEY) { parentContent() }
                }
                items(rows, key = { it.pathName }) { e ->
                    FileRow(
                        vm, pane, e, compact,
                        selected = e.pathName in pane.selected,
                        onClick = {
                            vm.focus = pane.index
                            when {
                                pane.selecting -> vm.toggle(pane, e.pathName)
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
private fun PaneHeader(label: String, pane: PaneState, focused: Boolean) {
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
            label,
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
    // Follows the finger while a sideways drag is about to start selection, then springs back.
    val held = pane.swipeKey == e.pathName
    val shift by animateFloatAsState(
        if (held) pane.swipeOffset else 0f,
        if (held) snap() else spring(dampingRatio = 0.6f, stiffness = 600f),
        label = "swipe",
    )
    // A located entry glows once and fades back.
    val glow = remember { Animatable(0f) }
    if (pane.flash == e.pathName) LaunchedEffect(pane.flashTick) {
        glow.snapTo(1f)
        kotlinx.coroutines.delay(500)
        glow.animateTo(0f, tween(1200))
        if (pane.flash == e.pathName) pane.flash = null
    }
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) twin.selection else twin.paneBorder.copy(alpha = 0.22f * glow.value))
            .graphicsLayer { translationX = shift }
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
                // Non-breaking spaces keep date and size whole: in a narrow pane the size wraps
                // to its own line instead of losing its unit.
                val meta = if (e.isDir) Format.time(e.mtime)
                else Format.time(e.mtime).replace(' ', Typography.nbsp) + "  " + Format.size(e.size).replace(' ', Typography.nbsp)
                Text(meta, fontSize = 11.sp, lineHeight = 14.sp, color = twin.muted, maxLines = 2)
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
    enterPx: Float,
    selecting: () -> Boolean,
    isSelected: (String) -> Boolean,
    onFocus: () -> Unit,
    onDrag: (String?, Float) -> Unit,
    onEnter: () -> Unit,
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
        var dragging = false // row follows the finger before selection mode starts
        var painting = false
        var mode = true
        var lastIndex = start.first
        try {
            while (true) {
                val ev = awaitPointerEvent(PointerEventPass.Initial)
                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                if (!ch.pressed) break
                if (!painting) {
                    val d = ch.positionChange()
                    dx += d.x
                    dy += d.y
                    if (!dragging) {
                        if (abs(dy) > slop && abs(dy) > abs(dx)) break // a scroll: not ours
                        if (abs(dx) > slop * 1.5f && abs(dx) > abs(dy) * 1.5f) {
                            if (selecting()) {
                                // Already selecting: toggle right away, no transition.
                                painting = true
                                onFocus()
                                mode = !isSelected(start.second)
                                set(start.second, mode)
                            } else dragging = true
                        }
                    }
                    if (dragging && !painting) {
                        ch.consume()
                        // Rubber band past the threshold.
                        val over = (abs(dx) - enterPx).coerceAtLeast(0f)
                        val shown = (abs(dx).coerceAtMost(enterPx) + over * 0.3f) * kotlin.math.sign(dx)
                        onDrag(start.second, shown)
                        if (abs(dx) >= enterPx) {
                            onEnter()
                            painting = true
                            onFocus()
                            mode = true
                            set(start.second, true)
                            onDrag(null, 0f)
                        }
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
        } finally {
            onDrag(null, 0f)
        }
    }
}
