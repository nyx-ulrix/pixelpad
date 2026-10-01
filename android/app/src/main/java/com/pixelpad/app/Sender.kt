package com.pixelpad.app

import android.util.Log
import java.io.DataInputStream
import java.net.ConnectException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

private const val PING = 3
private const val HELLO = 4

class Stats(val connected: Boolean, val avgUs: Long, val minUs: Long, val maxUs: Long, val rate: Int)

/** Sends 16-byte packets to the PC: TCP over USB (via adb reverse), UDP over Wi-Fi / Bluetooth tethering. */
class Sender(private val usbPort: Int = 7777) {
    private val q = LinkedBlockingQueue<ByteArray>()
    @Volatile private var wifi = false
    @Volatile private var host = ""
    @Volatile private var port = 7777 // the PC's port for Wi-Fi / Bluetooth; USB always dials the same local port
    @Volatile private var dirty = true

    private val sent = AtomicInteger()
    private val rtts = LongArray(20) // last 20 round trips in microseconds
    private var rttN = 0
    @Volatile private var lastPong = 0L
    @Volatile private var lastRttUs = -1
    @Volatile private var padW = 0
    @Volatile private var padH = 0
    @Volatile private var rate = 0

    /** Why the last attempt failed: "", "refused", "timeout", "badhost" or "other". */
    @Volatile var lastError = ""
    /** Player number the PC gave this device (1-4), 0 until connected. */
    @Volatile var slot = 0

    /** False while no screen of the app is visible: no pings, sockets closed. */
    @Volatile var active = true
    private var retryAt = 0L

    /** Called with (virtual key, modifiers, status) when the PC answers a shortcut recording. status 1 = recorded, 2 = nothing / cancelled, 3 = off on the PC. */
    @Volatile var recordListener: ((Int, Int, Int) -> Unit)? = null
    fun startRecord() = pen(9, 1, 0, 0, 0)
    fun cancelRecord() = pen(9, 0, 0, 0, 0)

    fun configure(wifi: Boolean, host: String, port: Int) {
        val changed = wifi != this.wifi || host != this.host || port != this.port
        this.wifi = wifi; this.host = host; this.port = port
        if (changed) reconnect()
    }
    fun reconnect() { dirty = true; lastError = ""; lastPong = 0 }
    fun connected() = System.nanoTime() - lastPong < 2_000_000_000L
    fun rttUs() = lastRttUs

    /** Size of the PC's main screen in pixels, learned from the PC's replies (0 until connected). */
    @Volatile var screenW = 0
    @Volatile var screenH = 0

    /** Press / hold / release a keyboard shortcut on the PC. phase: 0 tap, 1 press, 2 release. mods: 1 ctrl, 2 shift, 4 alt, 8 win. */
    /** One finger of a multi-finger gesture, forwarded raw so Windows recognises its own touch gestures. phase 0 move, 1 down, 2 up. */
    fun touch(phase: Int, slot: Int, offX: Int, offY: Int) { pen(8, phase, slot, offX, offY); if (phase == 2) pen(8, 2, slot, offX, offY) }   // a release goes twice; the PC ignores the extra one

    /** Tells the PC how to draw its pen cursor ring. Only once the user has changed something here. */
    /** How much the PC evens out the timing of pen packets (a small fixed delay, in ms). */
    private var colourSent = -1
    /** Tells the PC which colour this device is, so PixelPad Desk can show each player in theirs. */
    fun syncColour() { val i = Themes.index(Cfg.theme, slot); pen(7, 7, 0, i, 0); colourSent = i }

    fun syncSmooth() = pen(7, 6, 0, when (Cfg.smooth) { 0 -> 0; 1 -> 10; 2 -> 20; else -> 40 }, 0)

    fun syncRing() {
        if (!Cfg.ringTouched) return
        pen(7, 1, 0, if (Cfg.ringOn) 1 else 0, 0); pen(7, 2, 0, Cfg.ringSize, 0); pen(7, 3, 0, Cfg.ringStyle, 0)
        pen(7, 4, 0, Cfg.ringColor, 0); pen(7, 5, 0, Cfg.ringThick, 0)
    }

    fun key(vk: Int, mods: Int, phase: Int) { pen(6, vk, mods, phase, 0); if (phase == 2) pen(6, vk, mods, 2, 0) } // a release is sent twice; the PC ignores the extra one

    /** Size of the drawing area, told to the PC so it can match the shape automatically. */
    fun setPad(w: Int, h: Int) { padW = w; padH = h }

    /** trackpad/tablet packet: mode, action, buttons, x, y, pressure, tiltX, tiltY */
    fun pen(mode: Int, action: Int, buttons: Int, x: Int, y: Int, pressure: Int = 0, tx: Int = 0, ty: Int = 0, dt: Int = 0) =
        put(ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).put(mode.toByte()).put(action.toByte()).put(buttons.toByte())
            .putInt(x).putInt(y).putShort(pressure.toShort()).put(tx.toByte()).put(ty.toByte()).put(dt.coerceIn(0, 255).toByte())) // dt: ms since the previous pen sample

    /** controller packet: buttons bitmask, sticks -127..127 (y up), triggers 0..255 */
    fun pad(buttons: Int, lx: Int, ly: Int, rx: Int, ry: Int, lt: Int, rt: Int) =
        put(ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).put(2).putShort(buttons.toShort())
            .put(lx.toByte()).put(ly.toByte()).put(rx.toByte()).put(ry.toByte()).put(lt.toByte()).put(rt.toByte()))

    private fun put(b: ByteBuffer) {
        if (q.size > 64) q.removeIf { (it[0].toInt() == 0 && it[1].toInt() == 0) || (it[0].toInt() == 1 && it[1].toInt() <= 1) || it[0].toInt() == 4 }
        q.offer(b.array())
    }

    private fun nowUs() = (System.nanoTime() / 1000).toInt()

    private fun onPong(b: ByteArray) {
        val us = nowUs() - ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).getInt(3) // wraps safely
        if (us < 0) return
        synchronized(rtts) { rtts[rttN++ % rtts.size] = us.toLong() }
        val scr = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).getInt(7)
        if (scr != 0) { screenW = scr ushr 16; screenH = scr and 0xFFFF }
        lastRttUs = us; slot = b[1].toInt(); lastError = ""; lastPong = System.nanoTime()
    }

    fun stats(): Stats {
        val v = synchronized(rtts) { rtts.take(minOf(rttN, rtts.size)) }
        if (!connected() || v.isEmpty()) return Stats(false, 0, 0, 0, rate)
        return Stats(true, v.average().toLong(), v.min(), v.max(), rate)
    }

    private fun fail(e: Exception) {
        lastError = when (e) { is ConnectException -> "refused"; is SocketTimeoutException -> "timeout"; is UnknownHostException -> "badhost"; else -> "other" }
    }

    private fun reader(read: () -> ByteArray?) = Thread {
        try {
            while (true) {
                val b = read() ?: break
                if (b[0].toInt() == PING) onPong(b)
                else if (b[0].toInt() == 9) recordListener?.invoke(b[1].toInt() and 255, b[2].toInt() and 255, ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).getInt(3))
            }
        } catch (e: Exception) {}
    }.apply { isDaemon = true }.start()

    init {
        Thread { // twice a second: measure latency, keep the connection alive, and tell the PC our drawing-area size
            var lastSent = 0; var lastAt = System.nanoTime(); var wasConnected = false; var beat = 0
            while (true) {
                Thread.sleep(500)
                if (!active) { dirty = true; continue }   // nothing on screen: stay quiet
                val c = connected(); if (c && !wasConnected) { syncRing(); syncSmooth() }; wasConnected = c
                if (!c) colourSent = -1 else if (Themes.index(Cfg.theme, slot) != colourSent) syncColour()   // on connect, and whenever the colour (or our player number) changes
                val now = System.nanoTime(); val n = sent.get()
                rate = ((n - lastSent) * 1e9 / (now - lastAt)).toInt(); lastSent = n; lastAt = now
                if (++beat % 4 == 0) Log.d("PixelPad", "link ${if (wifi) "udp" else "usb tcp"} connected=$c sent=$n rtt=${lastRttUs}us queue=${q.size} error='$lastError'")
                put(ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).put(PING.toByte()).put(0).put(0).putInt(0).putInt(lastRttUs))
                if (padW > 0) put(ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).put(HELLO.toByte()).put(0).put(0).putInt(padW).putInt(padH))
            }
        }.apply { isDaemon = true }.start()

        Thread {
            var tcp: Socket? = null
            var udp: DatagramSocket? = null
            var addr: InetAddress? = null
            while (true) {
                val p = q.take()
                if (System.nanoTime() < retryAt && p[0].toInt() != PING) continue   // recently failed: don't hammer a PC that isn't there
                try {
                    if (dirty) { dirty = false; tcp?.close(); tcp = null; udp?.close(); udp = null; addr = null; lastPong = 0 }
                    if (p[0].toInt() == PING) ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN).putInt(3, nowUs()) // stamp at send time
                    else if (p[0].toInt() != HELLO) sent.incrementAndGet()
                    if (wifi) {
                        val a = addr ?: InetAddress.getByName(host).also { addr = it }
                        val s = udp ?: DatagramSocket().also { ns ->
                            udp = ns
                            reader { val r = DatagramPacket(ByteArray(16), 16); ns.receive(r); r.data }
                        }
                        s.send(DatagramPacket(p, p.size, a, port))
                    } else {
                        val s = tcp ?: Socket().apply { tcpNoDelay = true; connect(InetSocketAddress("127.0.0.1", usbPort), 300) }.also { ns ->
                            tcp = ns
                            val inp = DataInputStream(ns.getInputStream())
                            reader { ByteArray(16).also { inp.readFully(it) } }
                        }
                        s.getOutputStream().write(p)
                    }
                } catch (e: Exception) { fail(e); retryAt = System.nanoTime() + 500_000_000L; tcp?.close(); tcp = null; udp?.close(); udp = null; addr = null }
            }
        }.apply { isDaemon = true; name = "pixelpad-send" }.start()
    }
}
