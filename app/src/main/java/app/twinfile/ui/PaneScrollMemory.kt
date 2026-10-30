package app.twinfile.ui

data class ScrollPosition(val index: Int = 0, val offset: Int = 0, val key: String? = null)

/** Per-pane, bounded directory positions. Stable row keys survive sorting and insertions. */
class PaneScrollMemory(private val capacity: Int = 100) {
    private val positions = LinkedHashMap<Loc, ScrollPosition>(16, 0.75f, true)

    fun save(loc: Loc, position: ScrollPosition) {
        positions[loc] = position
        while (positions.size > capacity) positions.remove(positions.keys.first())
    }

    fun restore(loc: Loc, keys: List<String>, enabled: Boolean): ScrollPosition {
        if (!enabled || keys.isEmpty()) return ScrollPosition()
        val saved = positions[loc] ?: return ScrollPosition()
        val anchor = saved.key?.let(keys::indexOf)?.takeIf { it >= 0 }
        val index = anchor ?: saved.index.coerceIn(0, keys.lastIndex)
        return ScrollPosition(index, if (saved.key == null || anchor != null) saved.offset.coerceAtLeast(0) else 0, keys[index])
    }
}
