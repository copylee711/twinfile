package app.twinfile.fs

/** Helpers for the '/'-separated absolute paths every [FileSource] uses. */
object Paths {
    fun join(dir: String, name: String): String = if (dir.endsWith("/")) dir + name else "$dir/$name"

    fun parent(path: String): String? {
        if (path == "/" || path.isEmpty()) return null
        val i = path.trimEnd('/').lastIndexOf('/')
        return if (i <= 0) "/" else path.substring(0, i)
    }

    fun name(path: String): String = path.trimEnd('/').substringAfterLast('/').ifEmpty { "/" }

    fun normalize(path: String): String {
        val parts = ArrayList<String>()
        for (p in path.split('/')) {
            when (p) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts += p
            }
        }
        return "/" + parts.joinToString("/")
    }

    /** True when [path] is [dir] itself or somewhere below it. */
    fun isInside(path: String, dir: String): Boolean =
        path == dir || path.startsWith(if (dir.endsWith("/")) dir else "$dir/")

    fun extension(name: String): String {
        val i = name.lastIndexOf('.')
        return if (i <= 0 || i == name.length - 1) "" else name.substring(i + 1).lowercase()
    }

    /** "a.txt" -> "a (1).txt", "a (1).txt" -> "a (2).txt"; folders get the suffix at the end. */
    fun numbered(name: String, n: Int, isDir: Boolean): String {
        val dot = if (isDir) -1 else name.lastIndexOf('.')
        val (base, ext) = if (dot > 0) name.substring(0, dot) to name.substring(dot) else name to ""
        val stripped = base.replace(Regex(""" \(\d+\)$"""), "")
        return "$stripped ($n)$ext"
    }

    /** First name based on [name] that [taken] says is free. */
    fun freeName(name: String, isDir: Boolean, taken: (String) -> Boolean): String {
        if (!taken(name)) return name
        var n = 1
        while (true) {
            val c = numbered(name, n++, isDir)
            if (!taken(c)) return c
        }
    }
}
