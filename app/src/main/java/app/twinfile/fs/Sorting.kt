package app.twinfile.fs

enum class SortBy { NAME, DATE, SIZE, TYPE }

data class SortSpec(val by: SortBy = SortBy.NAME, val reverse: Boolean = false)

/** Case-insensitive comparison that orders "file2" before "file10". */
object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                var ei = i
                while (ei < a.length && a[ei].isDigit()) ei++
                var ej = j
                while (ej < b.length && b[ej].isDigit()) ej++
                val na = a.substring(i, ei).trimStart('0')
                val nb = b.substring(j, ej).trimStart('0')
                if (na.length != nb.length) return na.length - nb.length
                val c = na.compareTo(nb)
                if (c != 0) return c
                i = ei
                j = ej
            } else {
                val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (c != 0) return c
                i++
                j++
            }
        }
        val rest = (a.length - i) - (b.length - j)
        return if (rest != 0) rest else a.compareTo(b)
    }
}

/** Folders always come first; [SortSpec.reverse] flips the order inside each group. */
fun List<FEntry>.sortedBy(spec: SortSpec): List<FEntry> {
    val byName = Comparator<FEntry> { x, y -> NaturalOrder.compare(x.name, y.name) }
    val key: Comparator<FEntry> = when (spec.by) {
        SortBy.NAME -> byName
        SortBy.DATE -> compareByDescending<FEntry> { it.mtime }.then(byName)
        SortBy.SIZE -> compareByDescending<FEntry> { it.size }.then(byName)
        SortBy.TYPE -> compareBy<FEntry> { Paths.extension(it.name) }.then(byName)
    }
    val ordered = if (spec.reverse) key.reversed() else key
    return sortedWith(compareBy<FEntry> { !it.isDir }.then(ordered))
}
