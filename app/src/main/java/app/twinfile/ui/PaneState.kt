package app.twinfile.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.twinfile.fs.FEntry
import app.twinfile.fs.FileSource
import app.twinfile.fs.SortSpec
import app.twinfile.fs.sortedBy

/** A place a pane has been: which source and which folder. */
data class Loc(val source: String, val path: String)

/** Everything one pane shows; all fields are Compose state. */
class PaneState(val index: Int, source: FileSource, private val showHidden: () -> Boolean) {
    var recentView by mutableStateOf(false)
    var recentOpened by mutableStateOf(false)
    val recentModifiedList = LazyListState()
    val recentOpenedList = LazyListState()
    var source by mutableStateOf(source)
    var path by mutableStateOf("")
    var raw by mutableStateOf<List<FEntry>>(emptyList())
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var sort by mutableStateOf(SortSpec())
    var filter by mutableStateOf("")
    var selected by mutableStateOf<Set<String>>(emptySet())
    var listState by mutableStateOf(LazyListState())

    internal val back = ArrayDeque<Loc>()
    internal val forward = ArrayDeque<Loc>()
    /** Bumped whenever the history stacks change, so buttons recompose. */
    var historyVersion by mutableIntStateOf(0)
    internal val scrollMemory = PaneScrollMemory()
    internal var loadGen = 0
    /** Bumped when a different folder's listing lands; drives the slide-in animation. */
    var navTick by mutableIntStateOf(0)
    /** +1 when the last navigation went deeper, -1 when it went up or back. */
    var navDir = 1
    internal var pendingDir = 1
    /** Entry to scroll into view once the next listing lands. */
    internal var reveal: String? = null
    /** Entry that briefly lights up (located from outside, or the folder just left). */
    var flash by mutableStateOf<String?>(null)
    var flashTick by mutableIntStateOf(0)
    /** Row being dragged sideways to start selecting, and how far (px); it follows the finger. */
    var swipeKey by mutableStateOf<String?>(null)
    var swipeOffset by mutableStateOf(0f)

    val loc get() = Loc(source.id, path)
    val canBack get() = historyVersion >= 0 && back.isNotEmpty()
    val canForward get() = historyVersion >= 0 && forward.isNotEmpty()

    val visible by derivedStateOf {
        val hidden = showHidden()
        val f = filter.trim()
        raw.asSequence()
            .filter { hidden || !it.hidden }
            .filter { f.isEmpty() || it.name.contains(f, ignoreCase = true) }
            .toList()
            .sortedBy(sort)
    }

    val selecting get() = selected.isNotEmpty()

    fun selectedEntries(): List<FEntry> = visible.filter { it.pathName in selected }
}
