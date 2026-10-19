package app.twinfile.pc

/**
 * The PC's one pairing QR (protocol §7): Android's wireless-debugging format,
 * `WIFI:T:ADB;S:<name>;P:<password>;;`, where the name is `sb-<hex ipv4>-...-<random>`
 * (the PC's addresses) and the password is a SyncBoard pairing token.
 */
object AdbQr {
    data class Parsed(val name: String, val token: String, val hosts: List<String>)

    fun parse(text: String): Parsed? {
        if (!text.startsWith("WIFI:")) return null
        val fields = text.removePrefix("WIFI:").split(';').mapNotNull {
            val i = it.indexOf(':')
            if (i > 0) it.substring(0, i) to it.substring(i + 1) else null
        }.toMap()
        if (fields["T"] != "ADB") return null
        val name = fields["S"] ?: return null
        val hosts = hostsOf(name)
        if (hosts.isEmpty()) return null
        return Parsed(name, fields["P"].orEmpty(), hosts)
    }

    /** PC addresses from a pairing service name, or none if it isn't SyncBoard's. */
    fun hostsOf(serviceName: String): List<String> {
        val parts = serviceName.split('-')
        if (parts.size < 3 || parts[0] != "sb") return emptyList()
        return parts.drop(1).dropLast(1).mapNotNull { h ->
            if (h.length != 8) return@mapNotNull null
            runCatching { h.chunked(2).joinToString(".") { it.toInt(16).toString() } }.getOrNull()
        }
    }

    /** The equivalent `syncboard://pair` URI for the daemon's QR pairing. */
    fun toPairUri(p: Parsed, port: Int = 53317) =
        "syncboard://pair?v=1&h=${p.hosts.joinToString(",")}&p=$port&t=${p.token}"
}
