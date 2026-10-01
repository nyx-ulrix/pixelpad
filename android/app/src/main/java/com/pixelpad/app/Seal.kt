package com.pixelpad.app

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Pairing security between the app and PixelPad Desk. The PC shows a pairing key in its QR code; with it every 16-byte packet is sent
 * inside a sealed frame, so nobody else on the network (public Wi-Fi, Bluetooth tethering) or on the USB tunnel can send input to the PC,
 * read what you do, or replay what they captured.
 *
 *   app -> PC (48 bytes): session id (4), counter (4), packet XOR keystream (16), tag (8) = 32 bytes, spread over three 16-byte rows that each start
 *                         with 0xA5 (15 + 15 + 2 bytes, then zero padding), so a pre-1.3 PC that cuts a USB stream into 16-byte packets only sees "mode 165"
 *   PC -> app (37 bytes): 0xA6, PC start-up nonce (8), counter (4), packet XOR keystream (16), tag (8)
 * keystream and tag are HMAC-SHA256 of the key over a label, the PC's start-up nonce, our session id and the counter (tag over the encrypted packet,
 * cut to 8 bytes). Replies are bound to our session id, so one device's reply can't be passed off as another's. The nonce changes every time
 * PixelPad Desk starts, so nothing recorded earlier is valid; the counter (per session id) makes each frame usable once, and being 32-bit it runs
 * out after four billion packets (about 200 days without closing the app), after which the PC refuses the session until the app restarts.
 * The app learns the nonce from a PC reply to a "discovery" ping, which carries the nonce 0.
 * The same code, in Python, is in server/pixelpad_server.py (class Pairing): change them together.
 */
class Seal(val key: ByteArray, private val sid: ByteArray = ByteArray(4).also { SecureRandom().nextBytes(it) }) {   // sid: random per run (a fixed one is for tests)
    private var ctr = 0
    private val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }

    /** The PC's start-up nonce, learned from a ping reply; all zeros until then. */
    @Volatile var nonce = ByteArray(8)
    private var lastRc = -1L

    private fun h(vararg parts: ByteArray): ByteArray = synchronized(mac) { mac.reset(); parts.forEach { mac.update(it) }; mac.doFinal() }
    private fun le(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
    private val a5 = byteArrayOf(0xA5.toByte())
    private val a6 = byteArrayOf(0xA6.toByte())

    /** Seals one 16-byte packet for the PC. [discovery] seals it with the nonce 0, which the PC only accepts for a ping. */
    @Synchronized fun seal(p: ByteArray, discovery: Boolean = false): ByteArray {
        val n = if (discovery) ByteArray(8) else nonce
        val c = le(ctr++)
        val ks = h("E".toByteArray(), n, sid, c)
        val ct = ByteArray(16) { (p[it].toInt() xor ks[it].toInt()).toByte() }
        val body = sid + c + ct + h("T".toByteArray(), n, a5, sid, c, ct).copyOf(8)   // 32 bytes
        val f = ByteArray(48); f[0] = a5[0]; f[16] = a5[0]; f[32] = a5[0]
        System.arraycopy(body, 0, f, 1, 15); System.arraycopy(body, 15, f, 17, 15); System.arraycopy(body, 30, f, 33, 2)
        return f
    }

    /**
     * Opens a 37-byte frame from the PC and returns its 16-byte packet, or null if it is not genuine, not new, or from another PC run.
     * A frame carrying a different nonce is accepted only as the reply to a ping we sent a moment ago ([freshPing] says so), which is how
     * a restarted PixelPad Desk is picked up; anything else with a stale nonce is a replay.
     */
    @Synchronized fun open(f: ByteArray, freshPing: (ByteArray) -> Boolean): ByteArray? {
        if (f.size != 37 || f[0] != a6[0]) return null
        val n = f.copyOfRange(1, 9); val c = f.copyOfRange(9, 13); val ct = f.copyOfRange(13, 29); val tag = f.copyOfRange(29, 37)
        if (!MessageDigest.isEqual(h("S".toByteArray(), a6, n, sid, c, ct).copyOf(8), tag)) return null
        val ks = h("R".toByteArray(), n, sid, c)
        val pt = ByteArray(16) { (ct[it].toInt() xor ks[it].toInt()).toByte() }
        val rc = (c[0].toLong() and 255) or ((c[1].toLong() and 255) shl 8) or ((c[2].toLong() and 255) shl 16) or ((c[3].toLong() and 255) shl 24)
        if (MessageDigest.isEqual(n, nonce)) { if (rc <= lastRc) return null; lastRc = rc; return pt }
        if (!freshPing(pt)) return null
        nonce = n; lastRc = rc; return pt
    }

    /** Forget the PC's nonce (it stopped answering): the next ping goes out as a discovery ping. */
    @Synchronized fun forget() { nonce = ByteArray(8); lastRc = -1 }

    companion object {
        /** A pairing key as typed or scanned: 32 hex digits (spaces and dashes ignored). Null if it isn't one. */
        fun parse(text: String?): ByteArray? {
            val t = (text ?: "").filter { it != ' ' && it != '-' }
            if (t.length != 32 || !t.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
            return ByteArray(16) { t.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        }
        fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    }
}
