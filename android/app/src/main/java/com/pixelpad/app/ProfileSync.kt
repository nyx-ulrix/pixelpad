package com.pixelpad.app

/** One controller layout kept on the PC. layout is "id:x:y:size;..." exactly as the controller screen stores it. */
class RemoteProfile(val id: Int, val name: String, val template: String, val layout: String)

/**
 * Talks to the PC's profile library over the paired link (packet mode 10; the format is described at the top of server/pixelpad_profiles.py): list, get,
 * save and delete, plus the PC's "a linked game just started, use this profile" notice. Text longer than 11 bytes travels as several 16-byte packets, a
 * request that gets no answer is sent again (up to three times), and every callback is run on the UI thread with null / 0 / false when nothing came back.
 * No Android classes are used here, so it can be tested on its own.
 *   send: puts one 16-byte packet on the link   after: runs something later   ui: runs something on the UI thread
 */
class ProfileSync(private val send: (ByteArray) -> Unit, private val after: (Long, () -> Unit) -> Unit, private val ui: (() -> Unit) -> Unit) {
    private class Pending(val reply: Int, val packets: List<ByteArray>, val done: (String?) -> Unit) { var tries = 0 }
    private val pending = HashMap<Int, Pending>()                              // message id -> what we are waiting for
    private val parts = LinkedHashMap<Int, Pair<Int, HashMap<Int, ByteArray>>>()   // (op, message id) -> chunks so far
    private var next = 0

    /** Called (on the UI thread) when the PC says a linked game started: the profile number to use and the game's name. */
    @Volatile var onSwitch: ((Int, String) -> Unit)? = null

    private fun chunks(op: Int, mid: Int, text: String): List<ByteArray> {
        val data = text.toByteArray(Charsets.UTF_8)
        val n = maxOf(1, (data.size + 10) / 11)
        if (n > 255) return emptyList()
        return (0 until n).map { i ->
            ByteArray(16).also { p ->
                p[0] = 10; p[1] = op.toByte(); p[2] = mid.toByte(); p[3] = i.toByte(); p[4] = n.toByte()
                System.arraycopy(data, i * 11, p, 5, minOf(11, data.size - i * 11).coerceAtLeast(0))
            }
        }
    }

    private fun request(op: Int, text: String, reply: Int, done: (String?) -> Unit) {
        val id: Int; val packets: List<ByteArray>
        synchronized(this) {
            do { next = next % 255 + 1 } while (next in pending)
            id = next; packets = chunks(op, id, text)
            if (packets.isEmpty()) { ui { done(null) }; return }
            pending[id] = Pending(reply, packets, done)
        }
        packets.forEach(send)
        after(1500) { retry(id) }
    }

    private fun retry(id: Int) {
        val p = synchronized(this) { pending[id]?.also { it.tries++ }?.takeIf { it.tries <= 3 } ?: run { pending.remove(id)?.let { gone -> ui { gone.done(null) } }; null } } ?: return
        p.packets.forEach(send)
        after(1500) { retry(id) }
    }

    /** A packet from the PC. Returns true if it was a profile message. */
    fun onPacket(b: ByteArray): Boolean {
        if (b.size != 16 || b[0].toInt() != 10) return false
        val op = b[1].toInt() and 255; val mid = b[2].toInt() and 255; val seq = b[3].toInt() and 255; val total = b[4].toInt() and 255
        if (op < 0x81 || op > 0x85 || total == 0 || seq >= total) return true
        val text = synchronized(this) {
            val key = (op shl 8) or mid
            val have = parts[key]?.takeIf { it.first == total } ?: (total to HashMap<Int, ByteArray>()).also { parts.remove(key); parts[key] = it; while (parts.size > 8) parts.remove(parts.keys.first()) }
            have.second[seq] = b.copyOfRange(5, 16)
            if (have.second.size < total) return true
            parts.remove(key)
            val all = ByteArray(total * 11).also { out -> have.second.forEach { (i, d) -> System.arraycopy(d, 0, out, i * 11, 11) } }
            var end = all.size; while (end > 0 && all[end - 1].toInt() == 0) end--
            String(all, 0, end, Charsets.UTF_8)
        }
        if (op == 0x85) {
            val a = text.split("\n"); val id = a.getOrNull(0)?.toIntOrNull() ?: return true
            ui { onSwitch?.invoke(id, a.getOrNull(1) ?: "") }
        } else synchronized(this) { pending[mid]?.takeIf { it.reply == op } }?.let { p -> synchronized(this) { pending.remove(mid) }; ui { p.done(text) } }
        return true
    }

    private fun clean(s: String) = s.replace(Regex("[\\u0000-\\u001f\\u007f\\s]+"), " ").trim().take(20).ifEmpty { "PROFILE" }

    /** The profiles on the PC as (number, name), or null if the PC didn't answer. */
    fun list(done: (List<Pair<Int, String>>?) -> Unit) = request(1, "", 0x81) { t ->
        done(t?.split("\n")?.mapNotNull { l -> l.split("\t").takeIf { it.size == 2 }?.let { a -> a[0].toIntOrNull()?.let { it to a[1] } } })
    }

    /** One profile, or null if there is no such profile or the PC didn't answer. */
    fun get(id: Int, done: (RemoteProfile?) -> Unit) = request(2, id.toString(), 0x82) { t ->
        val a = t?.split("\n")
        done(if (a != null && a.size == 4) a[0].toIntOrNull()?.let { RemoteProfile(it, a[1], a[2], a[3]) } else null)
    }

    /** Saves a profile (id 0 = a new one) and returns its number, or 0 if the PC refused it or didn't answer. */
    fun put(name: String, template: String, layout: String, id: Int, done: (Int) -> Unit) =
        request(3, "$id\n${clean(name)}\n$template\n$layout", 0x83) { t -> done(t?.split("\n")?.firstOrNull()?.toIntOrNull() ?: 0) }

    fun delete(id: Int, done: (Boolean) -> Unit) = request(4, id.toString(), 0x84) { t -> done((t?.toIntOrNull() ?: 0) == id) }
}
