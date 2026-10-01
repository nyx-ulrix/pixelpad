package com.pixelpad.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.MotionEvent.TOOL_TYPE_STYLUS
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

private const val TRACKPAD = 0
private const val TABLET = 1
private const val CONTROLLER = 2
private const val PRESENT = 3

// the presenter is a clicker: previous / next slide
private const val C_NEXT = 1
private const val C_PREV = 2

// Retro pixel palette
private val INK = 0xFF2F6FE0.toInt()
private val PAPER = 0xFFF7FBFF.toInt()
private val LILAC = 0xFFD9C8FF.toInt()
private val PINK = 0xFFFFB8E6.toInt()
private val HOT get() = Themes.current().accent   // the theme colour
private val GREEN = 0xFFB8E986.toInt()
private val BABY = 0xFFBFE3FA.toInt()
private val PEACH = 0xFFFFD9A8.toInt()
private val SKY = 0xFFC9EEFF.toInt()
private val BLUSH = 0xFFFFC9EA.toInt()
private val WARN = 0xFFB0206E.toInt()

/** One on-screen controller control. Position and size are fractions of the pad area, so layouts survive rotation. */
private class Ctl(val id: String, val label: String, val bit: Int, var fx: Float, var fy: Float, var fr: Float, val color: Int,
                  val icon: String? = null, val visible: Boolean = true) {
    val isStick get() = bit <= -10
}
private const val L2 = -1
private const val R2 = -2

/** Controller templates. All of them drive the same virtual DualShock 4, so Steam sees a PlayStation pad whichever you pick. */
private fun template(name: String): MutableList<Ctl> = when (name) {
    "xbox" -> mutableListOf(
        Ctl("tri", "Y", 3, .84f, .24f, .11f, LILAC), Ctl("x", "A", 0, .84f, .52f, .11f, GREEN),
        Ctl("sq", "X", 2, .76f, .38f, .11f, BABY), Ctl("ci", "B", 1, .92f, .38f, .11f, PINK),
        Ctl("up", "▲", 12, .34f, .56f, .085f, PAPER), Ctl("dn", "▼", 13, .34f, .80f, .085f, PAPER),
        Ctl("lf", "◀", 14, .27f, .68f, .085f, PAPER), Ctl("rt", "▶", 15, .41f, .68f, .085f, PAPER),
        Ctl("l1", "LB", 4, .10f, .07f, .09f, BABY), Ctl("r1", "RB", 5, .90f, .07f, .09f, BABY),
        Ctl("l2", "LT", L2, .24f, .07f, .09f, BABY), Ctl("r2", "RT", R2, .76f, .07f, .09f, BABY),
        Ctl("share", "VIEW", 8, .40f, .30f, .07f, PAPER, "share"), Ctl("opt", "MENU", 9, .60f, .30f, .07f, PAPER, "menu"),
        Ctl("ps", "XB", 10, .50f, .18f, .075f, GREEN), Ctl("tp", "PAD", 11, .50f, .50f, .06f, PAPER, "touchpad", false),
        Ctl("l3", "L3", 6, .30f, .46f, .05f, PAPER), Ctl("r3", "R3", 7, .82f, .70f, .05f, PAPER),
        Ctl("sl", "", -10, .16f, .36f, .17f, PAPER), Ctl("sr", "", -11, .62f, .74f, .17f, PAPER),
    )
    "switch" -> mutableListOf( // full: like a Switch Pro Controller (the PC still sees a DualShock 4; the buttons sit in the same places)
        Ctl("tri", "X", 3, .84f, .24f, .11f, LILAC), Ctl("x", "B", 0, .84f, .52f, .11f, BABY),
        Ctl("sq", "Y", 2, .76f, .38f, .11f, GREEN), Ctl("ci", "A", 1, .92f, .38f, .11f, PINK),
        Ctl("up", "▲", 12, .34f, .56f, .085f, PAPER), Ctl("dn", "▼", 13, .34f, .80f, .085f, PAPER),
        Ctl("lf", "◀", 14, .27f, .68f, .085f, PAPER), Ctl("rt", "▶", 15, .41f, .68f, .085f, PAPER),
        Ctl("l2", "ZL", L2, .10f, .07f, .09f, BABY), Ctl("l1", "L", 4, .24f, .07f, .09f, BABY),
        Ctl("r1", "R", 5, .76f, .07f, .09f, BABY), Ctl("r2", "ZR", R2, .90f, .07f, .09f, BABY),
        Ctl("share", "-", 8, .40f, .20f, .065f, PAPER), Ctl("opt", "+", 9, .60f, .20f, .065f, PAPER),
        Ctl("tp", "", 11, .42f, .36f, .06f, PAPER, "camera"), Ctl("ps", "HOME", 10, .58f, .36f, .085f, GREEN),
        Ctl("l3", "L3", 6, .30f, .46f, .05f, PAPER), Ctl("r3", "R3", 7, .82f, .70f, .05f, PAPER),
        Ctl("sl", "", -10, .16f, .36f, .17f, PAPER), Ctl("sr", "", -11, .62f, .74f, .17f, PAPER),
    )
    "joycon" -> mutableListOf( // half: a single Joy-Con held sideways: one stick, A B X Y, the shoulder buttons, minus and home
        Ctl("sl", "", -10, .24f, .60f, .24f, PAPER),
        Ctl("tri", "X", 3, .80f, .30f, .115f, LILAC), Ctl("ci", "A", 1, .92f, .52f, .115f, PINK),
        Ctl("x", "B", 0, .80f, .74f, .115f, BABY), Ctl("sq", "Y", 2, .68f, .52f, .115f, GREEN),
        Ctl("l2", "ZL", L2, .10f, .10f, .085f, BABY), Ctl("l1", "SL", 4, .26f, .10f, .085f, BABY),
        Ctl("r1", "SR", 5, .74f, .10f, .085f, BABY), Ctl("r2", "ZR", R2, .90f, .10f, .085f, BABY),
        Ctl("share", "-", 8, .42f, .14f, .06f, PAPER), Ctl("ps", "HOME", 10, .58f, .14f, .065f, GREEN),
        Ctl("l3", "L3", 6, .24f, .30f, .05f, PAPER),
    )
    "fight" -> { val stick = Cfg.fightStick; mutableListOf( // arcade layout: a joystick (or arrow buttons) on the left, eight attack buttons in two rows
        Ctl("up", "▲", 12, .17f, .30f, .105f, PAPER, null, !stick), Ctl("dn", "▼", 13, .17f, .74f, .105f, PAPER, null, !stick),
        Ctl("lf", "◀", 14, .07f, .52f, .105f, PAPER, null, !stick), Ctl("rt", "▶", 15, .27f, .52f, .105f, PAPER, null, !stick),
        Ctl("sq", "□", 2, .46f, .36f, .095f, PINK), Ctl("tri", "△", 3, .58f, .36f, .095f, GREEN),
        Ctl("r1", "R1", 5, .70f, .36f, .095f, BABY), Ctl("l1", "L1", 4, .82f, .36f, .095f, BABY),
        Ctl("x", "✕", 0, .46f, .68f, .095f, BABY), Ctl("ci", "○", 1, .58f, .68f, .095f, LILAC),
        Ctl("r2", "R2", R2, .70f, .68f, .095f, BABY), Ctl("l2", "L2", L2, .82f, .68f, .095f, BABY),
        Ctl("share", "SHARE", 8, .38f, .10f, .06f, PAPER, "share"), Ctl("ps", "PS", 10, .50f, .10f, .06f, PINK),
        Ctl("opt", "OPTIONS", 9, .62f, .10f, .06f, PAPER, "menu"),
        Ctl("tp", "PAD", 11, .5f, .5f, .05f, PAPER, "touchpad", false), Ctl("l3", "L3", 6, .3f, .5f, .05f, PAPER, null, false),
        Ctl("r3", "R3", 7, .7f, .5f, .05f, PAPER, null, false),
        Ctl("sl", "", -10, .17f, .52f, .21f, PAPER, null, stick), Ctl("sr", "", -11, .73f, .74f, .20f, PAPER, null, false),
    ) }
    else -> mutableListOf( // "ps"
        Ctl("tri", "△", 3, .84f, .24f, .11f, GREEN), Ctl("x", "✕", 0, .84f, .52f, .11f, BABY),
        Ctl("sq", "□", 2, .76f, .38f, .11f, PINK), Ctl("ci", "○", 1, .92f, .38f, .11f, LILAC),
        Ctl("up", "▲", 12, .14f, .24f, .10f, PAPER), Ctl("dn", "▼", 13, .14f, .52f, .10f, PAPER),
        Ctl("lf", "◀", 14, .07f, .38f, .10f, PAPER), Ctl("rt", "▶", 15, .21f, .38f, .10f, PAPER),
        Ctl("l1", "L1", 4, .10f, .07f, .09f, BABY), Ctl("r1", "R1", 5, .90f, .07f, .09f, BABY),
        Ctl("l2", "L2", L2, .24f, .07f, .09f, BABY), Ctl("r2", "R2", R2, .76f, .07f, .09f, BABY),
        Ctl("share", "SHARE", 8, .40f, .22f, .085f, PAPER, "share"), Ctl("opt", "OPTIONS", 9, .60f, .22f, .085f, PAPER, "menu"),
        Ctl("ps", "PS", 10, .50f, .42f, .08f, PINK), Ctl("tp", "PAD", 11, .50f, .08f, .07f, PAPER, "touchpad"),
        Ctl("l3", "L3", 6, .40f, .60f, .05f, PAPER), Ctl("r3", "R3", 7, .60f, .60f, .05f, PAPER),
        Ctl("sl", "", -10, .27f, .74f, .20f, PAPER), Ctl("sr", "", -11, .73f, .74f, .20f, PAPER),
    )
}
private val TEMPLATES = listOf("ps", "xbox", "switch", "joycon", "fight")
private fun tplLabel(t: String) = when (t) { "switch" -> "SWITCH"; "joycon" -> "JOY-CON"; else -> t.uppercase() }
private val DARK_BG = 0xFF0A0F1F.toInt()
private val DARK_FACE = 0xFF10182E.toInt()

/** The same pastel, turned down towards the dark background, for the locked (dark) screens. */
private fun dimmed(color: Int, keep: Float): Int {
    fun mix(sh: Int) = ((color shr sh and 255) * keep + (DARK_BG shr sh and 255) * (1 - keep)).toInt()
    return Color.rgb(mix(16), mix(8), mix(0))
}
private val MODE_ICONS = listOf("touchpad", "pen", "gamepad", "present")

/**
 * A One Euro filter: smooths the little jitters of a pen slowly moved, but lets fast strokes through almost untouched, so it
 * steadies lines without making the pen feel like it drags behind.
 */
private class OneEuro {
    private var xPrev = 0f; private var dxPrev = 0f; private var tPrev = 0L; private var ready = false
    fun reset() { ready = false }
    private fun alpha(dt: Float, cutoff: Float) = 1f / (1f + (1f / (2f * Math.PI.toFloat() * cutoff)) / dt)
    fun filter(x: Float, tMs: Long, minCutoff: Float, beta: Float): Float {
        if (!ready) { ready = true; xPrev = x; dxPrev = 0f; tPrev = tMs; return x }
        val dt = ((tMs - tPrev).coerceAtLeast(1L)) / 1000f; tPrev = tMs
        val ad = alpha(dt, 1f); dxPrev += ad * ((x - xPrev) / dt - dxPrev)
        xPrev += alpha(dt, minCutoff + beta * abs(dxPrev)) * (x - xPrev)
        return xPrev
    }
}

/**
 * The main screen: trackpad, drawing tablet, controller and presenter, plus the lock. Everything configurable lives in the Settings page.
 * Pen and fingers drive the trackpad, controller and presenter (fingers get laptop gestures on the trackpad).
 * On the tablet only the pen draws; fingers can still use the buttons.
 */
class PixelPadView(ctx: Context, private val tx: Sender, private val host: Host) : View(ctx) {
    private var mode = TRACKPAD
    private var locked = false          // tablet locked: dark, only the lock and your tablet buttons respond
    private var lastLockTap = 0L
    private val barLocked get() = locked   // while locked, the top bar ignores touches so a slip can't change screen or exit
    private var lastPenMs = SystemClock.uptimeMillis()
    private var editing = false         // controller layout editing, or tablet-button arranging
    private var eraserOn = false
    private val dp = resources.displayMetrics.density
    private val barH = 52 * dp
    private val exitW = 52 * dp
    private val lockW = 52 * dp
    private val inset get() = exitW + lockW   // the exit and lock buttons live at the left of the bar
    private var exitArmedUntil = 0L
    private var resetArmedUntil = 0L
    private var flashMsg = ""; private var flashUntil = 0L

    /** A short message in the middle of the top of the screen. */
    private fun flash(msg: String) { flashMsg = msg; flashUntil = SystemClock.uptimeMillis() + 1200; invalidate(); postInvalidateDelayed(1300) }
    private val slop = 10 * dp
    private val p = Paint()
    private val prefs = Cfg.also { Cfg.init(ctx) }.prefs
    private val mono = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    private val uiBtns = ArrayList<Pair<RectF, () -> Unit>>() // rebuilt on every draw
    private var bgBmp: Bitmap? = null
    private var bgTheme = -1
    private var checkerBmp: Bitmap? = null

    private val ctls = ArrayList<Ctl>()
    private var tpl = Cfg.tpl
    private var autoFit = true // until the user edits, sizes are computed for this screen
    private var fitK = 1f
    private var visCache: List<Ctl> = emptyList()   // the visible controls, rebuilt when a template loads
    private val vis get() = visCache

    init { p.isAntiAlias = false; p.typeface = mono; p.letterSpacing = 0.06f }

    private fun layoutKey() = if (tpl == "ps") "layout" else "layout_$tpl"

    private fun loadTemplate(name: String) {
        tpl = name; Cfg.tpl = name
        ctls.clear(); ctls.addAll(template(name)); visCache = ctls.filter { it.visible }
        val saved = prefs.getString(layoutKey(), "") ?: ""
        autoFit = saved.isEmpty()
        saved.split(";").forEach { s ->
            val a = s.split(":")
            if (a.size == 4) ctls.find { it.id == a[0] }?.apply {
                fx = a[1].toFloatOrNull() ?: fx; fy = a[2].toFloatOrNull() ?: fy; fr = a[3].toFloatOrNull() ?: fr
            }
        }
        selected = null; computeFit()
    }

    /** Shrinks the default sizes just enough that no two controls, the screen edge or the bottom buttons collide. */
    private fun computeFit() {
        if (width == 0) return
        val w = width.toFloat(); val h = padH(); val gap = 4 * dp; var k = 1f
        val v = vis; val basis = ctlBasis()
        for (i in v.indices) {
            val a = v[i]; val ra = a.fr * basis
            k = minOf(k, (minOf(a.fx * w, w - a.fx * w, a.fy * h, h - a.fy * h) - gap) / ra)
            k = minOf(k, (hypot(a.fx * w - w / 2, a.fy * h - (h - 28 * dp)) - 96 * dp - gap) / ra) // EDIT + template buttons
            for (j in i + 1 until v.size) {
                val b = v[j]
                k = minOf(k, (hypot((a.fx - b.fx) * w, (a.fy - b.fy) * h) - gap) / (ra + b.fr * basis))
            }
        }
        fitK = k.coerceIn(0.2f, 1f)
        keepInside()
    }

    /** Keeps every control fully inside the pad area, below the top bar, wherever it was saved or dragged. */
    private fun keepInside(save: Boolean = true) {
        if (width == 0) return
        val w = width.toFloat(); val h = padH(); var changed = false
        for (k in ctls) {
            val r = cr(k); val mx = r / w + 0.004f; val my = r / h + 0.004f
            val nx = k.fx.coerceIn(minOf(mx, .5f), maxOf(1f - mx, .5f)); val ny = k.fy.coerceIn(minOf(my, .5f), maxOf(1f - my, .5f))
            if (nx != k.fx || ny != k.fy) { k.fx = nx; k.fy = ny; changed = true }
        }
        if (changed && save && !autoFit) saveNow()
    }

    /** Same for the tablet buttons. */
    private fun keepKeysInside() {
        if (width == 0) return
        for (k in Cfg.keys) {
            val r = kr(k); val mx = r / width + 0.004f; val my = r / padH() + 0.004f
            k.fx = k.fx.coerceIn(minOf(mx, .5f), maxOf(1f - mx, .5f)); k.fy = k.fy.coerceIn(minOf(my, .5f), maxOf(1f - my, .5f))
        }
    }

    private fun saveKeys() { keepKeysInside(); Cfg.saveKeys() }

    /** Freezes the computed sizes into the layout once the user starts customising it. */
    private fun bake() { if (autoFit) { val s = fitK * ctlBasis() / padH(); ctls.forEach { it.fr *= s }; autoFit = false } }   // keep exactly the size on screen

    private fun saveLayout() { bake(); keepInside(); saveNow() }
    private fun saveNow() = prefs.edit().putString(layoutKey(), ctls.joinToString(";") { "${it.id}:${it.fx}:${it.fy}:${it.fr}" }).apply()

    // ---------- lifecycle ----------
    private var stats = tx.stats()
    private var latencyMsg = ""
    private var shownKey = ""
    private val tick = object : Runnable {
        override fun run() {
            stats = tx.stats()
            if (Themes.index(Cfg.theme) != bgTheme && width > 0) { buildBackground(); invalidate() }   // the colour or our player number changed
            val key = "${stats.connected}${stats.avgUs / 1000}"
            if (key != shownKey) { // repaint only when something on screen would actually change
                shownKey = key
                latencyMsg = if (stats.connected) "RTT %.1f MS · LATENCY ~%.1f MS".format(stats.avgUs / 1000.0, stats.avgUs / 2000.0) else "NO LINK"
                if (!locked) invalidate()
            }
            postDelayed(this, 500)
        }
    }

    /** Re-reads the settings after the Settings page closes. */
    fun reload() {
        refreshPad(); buildBackground()
        if (!editing) loadTemplate(Cfg.tpl)
        when (Cfg.pending) {
            "controller" -> { setMode(CONTROLLER); editing = true }
            "keys" -> { setMode(TABLET); editing = true }
        }
        Cfg.pending = ""
        host.lockNav(mode == TRACKPAD || mode == PRESENT)
        invalidate()
    }

    /** Called when the app leaves the screen, so the brightness override never sticks. */
    fun wake() = setLocked(false)

    /** The app is leaving the screen: unlock, lift the pen, and let go of every key, stick and finger. */
    fun pause() { trackPenButtons(0); setLocked(false); endTouch(); hoverExit(); releaseKeys(); ctrlReset(); fingerReset() }

    fun wantsKey(code: Int) = mode == PRESENT && (code == KeyEvent.KEYCODE_VOLUME_DOWN || code == KeyEvent.KEYCODE_VOLUME_UP)

    /** Volume keys turn the slides while presenting. */
    fun handleKey(code: Int): Boolean {
        if (!wantsKey(code)) return false
        cmd(if (code == KeyEvent.KEYCODE_VOLUME_DOWN) C_NEXT else C_PREV)
        return true
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); post(tick); loadTemplate(Cfg.tpl); host.lockNav(mode == TRACKPAD || mode == PRESENT) }
    override fun onDetachedFromWindow() { removeCallbacks(tick); super.onDetachedFromWindow() }

    private fun refreshPad() {
        if (width == 0) return
        tx.setPad(max(1, (Cfg.aw * width).toInt()), max(1, (Cfg.ah * padH()).toInt()))
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        refreshPad()
        buildBackground()
        val ch = Bitmap.createBitmap(w, (h - barH).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888); val cc = Canvas(ch)
        val s = 20 * dp
        for (i in 0..(w / s).toInt()) for (j in 0..(ch.height / s).toInt()) {
            p.color = if ((i + j) % 2 == 0) PAPER else 0xFFE3F1FF.toInt(); cc.drawRect(i * s, j * s, (i + 1) * s, (j + 1) * s, p)
        }
        checkerBmp = ch
        computeFit()
    }

    /** The gradient behind everything: the colour you picked, or on AUTO the one for your player number. */
    private fun buildBackground() {
        val w = width; val h = height; if (w == 0 || h == 0) return
        val th = Themes.current(); bgTheme = Themes.index(Cfg.theme)
        val bg = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); val c = Canvas(bg)
        val band = (6 * dp).toInt().coerceAtLeast(2)
        for (y in 0 until h step band) {
            val t = y / h.toFloat()
            fun mix(a: Int, b: Int, s: Int) = ((a shr s and 255) + ((b shr s and 255) - (a shr s and 255)) * t).toInt()
            p.style = Paint.Style.FILL; p.color = Color.rgb(mix(th.top, th.bottom, 16), mix(th.top, th.bottom, 8), mix(th.top, th.bottom, 0))
            c.drawRect(0f, y.toFloat(), w.toFloat(), (y + band).toFloat(), p)
        }
        p.color = 0x70FFFFFF; val g = 24 * dp
        var x = 0f; while (x < w) { c.drawRect(x, 0f, x + dp, h.toFloat(), p); x += g }
        var y = 0f; while (y < h) { c.drawRect(0f, y, w.toFloat(), y + dp, p); y += g }
        bgBmp = bg
    }

    // ---------- the lock ----------
    private fun lockAvailable() = !editing
    private fun lockRect() = RectF(exitW + 4 * dp, 5 * dp, exitW + lockW - 2 * dp, barH - 8 * dp)
    private fun exitRect() = RectF(4 * dp, 5 * dp, exitW - 2 * dp, barH - 8 * dp)

    /** Two taps to leave, so a stray pen tap can't close the app: the first arms it, the second (within 2.5 s) exits. */
    private fun exitTap(now: Long) {
        if (now < exitArmedUntil) { exitArmedUntil = 0; host.exit() } else { exitArmedUntil = now + 2500; invalidate(); postInvalidateDelayed(2600) }
    }

    /** Two taps on the lock icon within half a second lock or unlock. One tap does nothing, so it can't happen by accident. */
    private fun lockTap(now: Long) {
        if (now - lastLockTap < 500) { lastLockTap = 0; setLocked(!locked) } else lastLockTap = now
    }

    private fun setLocked(on: Boolean) {
        if (on == locked) return
        locked = on; host.setDark(on)
        if (on) uiBtns.clear()
        invalidate()
    }

    // ---------- input plumbing ----------
    private var lastStylusMs = 0L
    private var pmDev = -1
    private var pmVal = 1f

    private fun stylusIndex(e: MotionEvent): Int {
        for (i in 0 until e.pointerCount) if (e.getToolType(i) == TOOL_TYPE_STYLUS) return i
        return -1
    }

    /** The pen's own full-scale pressure, so the raw value is used as-is instead of being clipped at 1.0. */
    private fun pressMax(e: MotionEvent): Float {
        if (e.deviceId != pmDev) {
            pmDev = e.deviceId
            pmVal = e.device?.getMotionRange(MotionEvent.AXIS_PRESSURE, e.source)?.max?.takeIf { it > 0f } ?: 1f
        }
        return pmVal
    }

    /** Calls f for every batched history sample and then the current one: x, y, pressure 0..1 (raw / device max), tilt x, tilt y. */
    private var sampleT = 0L   // event time of the sample being handled
    private inline fun samples(e: MotionEvent, i: Int, f: (Float, Float, Float, Int, Int) -> Unit) {
        val pm = pressMax(e)
        for (h in 0..e.historySize) {
            val hist = h < e.historySize
            sampleT = if (hist) e.getHistoricalEventTime(h) else e.eventTime
            val x = if (hist) e.getHistoricalX(i, h) else e.getX(i)
            val y = if (hist) e.getHistoricalY(i, h) else e.getY(i)
            val pr = if (hist) e.getHistoricalPressure(i, h) else e.getPressure(i)
            val t = Math.toDegrees((if (hist) e.getHistoricalAxisValue(MotionEvent.AXIS_TILT, i, h) else e.getAxisValue(MotionEvent.AXIS_TILT, i)).toDouble())
            val o = (if (hist) e.getHistoricalOrientation(i, h) else e.getOrientation(i)).toDouble()
            f(x, y, pr / pm, (t * sin(o)).toInt().coerceIn(-90, 90), (-t * cos(o)).toInt().coerceIn(-90, 90))
        }
    }

    private var fBar = false
    private var uiGesture = false

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val a = e.actionMasked
        val i = stylusIndex(e)
        val now = SystemClock.uptimeMillis()
        if (i >= 0) { lastStylusMs = now; lastPenMs = now; trackPenButtons(e.buttonState) }
        val penOk = i >= 0 || now - lastStylusMs > 250 // a finger next to a pen is probably a palm
        // while a pen gesture is being drawn (gesture pen button held), the on-screen buttons, lock and exit ignore the pen
        val gestureNow = i >= 0 && mode == TABLET && gestureHeld(e.buttonState)
        // the lock icon: pen or finger, double tap
        if (a == MotionEvent.ACTION_DOWN && !gestureNow && lockAvailable() && lockRect().contains(e.x, e.y) && penOk) { lockTap(now); return true }
        if (a == MotionEvent.ACTION_DOWN && !gestureNow && !locked && !barLocked && exitRect().contains(e.x, e.y) && penOk) { exitTap(now); return true }
        // editing (controller layout / tablet buttons): any pointer, buttons first
        if (editing && (mode == CONTROLLER || mode == TABLET)) {
            if (a == MotionEvent.ACTION_DOWN) uiGesture = uiHit(e.x, e.y)
            if (uiGesture) return true // the rest of a button press (move, lift) must not drag anything
            editTouch(e)
            return true
        }
        if (i >= 0 && (a == MotionEvent.ACTION_BUTTON_PRESS || a == MotionEvent.ACTION_BUTTON_RELEASE)) return true
        if (mode == CONTROLLER) { controllerTouch(e); return true } // pen and fingers, multi-touch
        if (mode == TABLET && !gestureNow && keyTouch(e)) return true // your tablet buttons, also while locked
        if (i >= 0) return stylusTouch(e, i)
        // fingers: buttons everywhere, gestures on the trackpad / pointer zone; never the tablet drawing area
        if (!penOk) return true
        if (a == MotionEvent.ACTION_DOWN) { fBar = if (locked) (mode == PRESENT && uiHit(e.x, e.y)) else if (e.y < barH) { bar(e.x); true } else uiHit(e.x, e.y) }
        if (fBar) return true
        if (mode == TRACKPAD || mode == PRESENT) fingerTouch(e)
        return true
    }

    private fun stylusTouch(e: MotionEvent, i: Int): Boolean {
        val a = e.actionMasked
        val mine = e.actionIndex == i
        when {
            (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_POINTER_DOWN) && mine -> {
                requestUnbufferedDispatch(e)
                removeCallbacks(exitRun)
                down(e.getX(i), e.getY(i), e, i)
            }
            a == MotionEvent.ACTION_MOVE -> if (touching) samples(e, i) { x, y, pr, tX, tY -> hvP = pr; move(x, y, pr, tX, tY, e.buttonState) }
            (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_POINTER_UP) && mine -> up(e.getX(i), e.getY(i))
            a == MotionEvent.ACTION_CANCEL -> up(lastX, lastY)
        }
        return true
    }

    // hover: how far away the pen still counts, and a short grace period so it doesn't flicker at the edge of range
    private var hvX = 0f; private var hvY = 0f; private var hvD = 0f; private var hvP = 0f; private var hvOn = false
    private val exitRun = Runnable { if (!touching) { trackPenButtons(0); hoverExit() } }   // out of range for good: a held pen button can no longer be seen released

    private fun softExit() { removeCallbacks(exitRun); if (Cfg.linger <= 0) { if (!touching) hoverExit() } else postDelayed(exitRun, Cfg.linger.toLong()) }

    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        if (e.getToolType(0) != TOOL_TYPE_STYLUS) return true
        lastStylusMs = SystemClock.uptimeMillis(); lastPenMs = lastStylusMs
        trackPenButtons(e.buttonState)
        if (editing) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                hvD = e.getAxisValue(MotionEvent.AXIS_DISTANCE).coerceIn(0f, 1f)
                if (hvD * 100 > Cfg.hoverRange) softExit() // farther than the chosen range
                else {
                    removeCallbacks(exitRun); hvOn = true; hvX = e.x; hvY = e.y
                    samples(e, 0) { x, y, _, tX, tY -> hover(x, y, tX, tY, e.buttonState) }
                }
            }
            MotionEvent.ACTION_HOVER_EXIT -> softExit()
        }
        return true
    }

    private var lastBs = 0

    /** Watches which pen buttons are down and runs what each recorded button is mapped to: on press, and on release for hold actions. */
    private fun trackPenButtons(bs: Int) {
        val changed = bs xor lastBs
        if (changed == 0) return
        lastBs = bs
        for (b in Cfg.penButtons) if (changed and b.bit != 0) penButton(b, bs and b.bit != 0)
    }

    private fun penButton(b: PenButton, down: Boolean) {
        val a = b.action
        when {
            a == "barrel" -> if (down && (mode == TRACKPAD || mode == PRESENT)) tx.pen(TRACKPAD, 3, 0, 0, 0) // off the tablet screen it right-clicks
            a == "mode:next" -> if (down && !locked && !barLocked && !editing) { setMode((mode + 1) % 4); flash(MODE_ICONS[mode]) }
            a == "eraser-hold" || a == "gesture" || a == "none" -> {}  // read wherever they matter (penFlags / gestureHeld)
            b.hold -> perform(a, if (down) 1 else 2)
            down -> perform(a, 0)
        }
    }

    private fun setMode(m: Int) {
        editing = false
        if (m == mode) return
        endTouch(); hoverExit(); ctrlReset(); fingerReset(); releaseKeys()
        mode = m; lastPenMs = SystemClock.uptimeMillis()
        host.lockNav(m == TRACKPAD || m == PRESENT)
        when (m) { CONTROLLER -> { lastPad[0] = Int.MIN_VALUE; sendPad() }; TABLET -> tx.pen(TABLET, 3, 0, 0, 0); else -> tx.pen(TRACKPAD, 0, 0, 0, 0) }
        invalidate()
    }

    private fun uiHit(x: Float, y: Float): Boolean {
        if (locked && mode != PRESENT) return false   // the presenter's buttons are its whole point, so they work while dark
        for ((r, f) in uiBtns) if (r.contains(x, y)) { f(); invalidate(); return true }
        return false
    }

    /** Runs what a tablet button, pen button or gesture is mapped to. phase: 0 tap, 1 press, 2 release. */
    private fun perform(action: String, phase: Int) {
        when {
            action.startsWith("key:") -> Keys.parse(action.removePrefix("key:"))?.let { (m, v) -> tx.key(v, m, phase) }
            action.startsWith("keys:") -> if (phase != 2) action.removePrefix("keys:").split(",").forEach { s -> Keys.parse(s.trim())?.let { (m, v) -> tx.key(v, m, 0) } }
            action == "eraser" -> if (phase != 2) { eraserOn = !eraserOn; invalidate() }
            action == "mode:next" -> if (phase != 2 && !locked && !barLocked && !editing) { setMode((mode + 1) % 4); flash(MODE_ICONS[mode]) }
            action.startsWith("mouse:") -> {
                val (dn, up) = when (action.removePrefix("mouse:")) { "left" -> 1 to 2; "right" -> 7 to 8; else -> 9 to 10 }
                if (phase != 2) tx.pen(TRACKPAD, dn, 0, 0, 0)
                if (phase != 1) tx.pen(TRACKPAD, up, 0, 0, 0)
            }
        }
    }

    private fun cmd(id: Int) {
        when (id) { C_NEXT -> tx.key(0x27, 0, 0); C_PREV -> tx.key(0x25, 0, 0) }   // Right / Left arrow: every slide app understands them
    }

    // ---------- shared stylus state ----------
    private var touching = false
    private var inBar = false
    private var lastX = 0f
    private var lastY = 0f
    private var presZoneTop = 0f
    private var presStart = 0L

    private fun down(x: Float, y: Float, e: MotionEvent, i: Int) {
        lastX = x; lastY = y; hvX = x; hvY = y
        touching = true; hoverValid = false
        val gesture = mode == TABLET && gestureHeld(e.buttonState)   // drawing a pen gesture: the tab bar and buttons don't react to it
        if (!gesture && y < barH && !locked) { inBar = true; bar(x); return }
        if (!gesture && uiHit(x, y)) { inBar = true; return }
        if (mode == PRESENT && y < presZoneTop) { inBar = true; return }
        inBar = false
        when (mode) {
            TRACKPAD, PRESENT -> tpDown(x, y)
            TABLET -> {
                gesturing = gestureHeld(e.buttonState)
                if (gesturing) { gPts.clear(); gPts.add(x); gPts.add(y); invalidate() }
                else samples(e, i) { sx, sy, pr, tX, tY -> hvP = pr; tablet(1, sx, sy, pr, tX, tY, e.buttonState) }
            }
        }
    }

    private fun move(x: Float, y: Float, pr: Float, tX: Int, tY: Int, buttons: Int) {
        if (inBar) return
        hvX = x; hvY = y
        when (mode) {
            TRACKPAD, PRESENT -> tpMove(x, y)
            TABLET -> if (gesturing) { gPts.add(x); gPts.add(y); invalidate() } else tablet(1, x, y, pr, tX, tY, buttons)
        }
        lastX = x; lastY = y
    }

    private fun up(x: Float, y: Float) {
        if (!touching) return
        if (!inBar) when (mode) {
            TRACKPAD, PRESENT -> tpUp()
            TABLET -> if (gesturing) finishGesture() else { sampleT = SystemClock.uptimeMillis(); tablet(0, x, y, 0f, 0, 0, 0) }
        }
        touching = false; inBar = false; hvP = 0f; hoverValid = false
        if (mode == TABLET) softExit()
    }

    private fun endTouch() = up(lastX, lastY)

    private fun hover(x: Float, y: Float, tX: Int, tY: Int, buttons: Int) {
        if (y < barH && !locked) { hoverExit(); return }
        if (mode == PRESENT && y < presZoneTop) { hoverValid = false; return }
        when (mode) {
            TRACKPAD, PRESENT -> {
                if (hoverValid) relMove(x - hx, y - hy)
                hx = x; hy = y; hoverValid = true
            }
            TABLET -> if (keyAt(x, y) == null) tablet(0, x, y, 0f, tX, tY, buttons)
        }
    }

    private fun hoverExit() {
        hoverValid = false; carryX = 0f; carryY = 0f; relValid = false; hvOn = false; filtX.reset(); filtY.reset()
        if (mode == TABLET && penInside) { penInside = false; tx.pen(TABLET, 3, 0, 0, 0) }
    }

    // ---------- top bar ----------
    private fun bar(x: Float) {
        if (x < inset || barLocked) return
        when (((x - inset) / ((width - inset) / 5f)).toInt()) {
            0 -> setMode(TRACKPAD)
            1 -> setMode(TABLET)
            2 -> setMode(CONTROLLER)
            3 -> setMode(PRESENT)
            else -> host.openSettings(when (mode) { TABLET -> 2; CONTROLLER -> 6; else -> 1 }) // the settings for the screen you are on
        }
    }

    // ---------- trackpad: stylus ----------
    private var hx = 0f
    private var hy = 0f
    private var hoverValid = false
    private var carryX = 0f
    private var carryY = 0f
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var moved = false
    private var longFired = false
    private var dragging = false
    private var lastTapUp = 0L
    private var dragStarted = false
    private val longPress = Runnable {
        if (!touching || moved) return@Runnable
        if (dragging && selectDrag()) { tx.pen(TRACKPAD, 2, 0, 0, 0); dragging = false } // holding still: let go of the button, then right-click
        else if (dragging) return@Runnable
        longFired = true; tx.pen(TRACKPAD, 3, 0, 0, 0)
    }

    private fun relMove(dx: Float, dy: Float) {
        carryX += dx * Cfg.trackSpeed; carryY += dy * Cfg.trackSpeed
        val ix = carryX.toInt(); val iy = carryY.toInt()
        if (ix != 0 || iy != 0) { carryX -= ix; carryY -= iy; tx.pen(TRACKPAD, 0, 0, ix, iy) }
    }

    private fun tpDown(x: Float, y: Float) {
        downX = x; downY = y; moved = false; longFired = false
        downTime = SystemClock.uptimeMillis()
        dragStarted = false
        // pen drag = press and drag: the button goes down the instant the pen touches, so a drag starts with no delay and no dead zone.
        // (when the pen only moves the cursor: tap, then touch and drag)
        dragging = selectDrag() || downTime - lastTapUp < 500
        if (dragging) tx.pen(TRACKPAD, 1, 0, 0, 0)
        postDelayed(longPress, 500)
    }

    private fun selectDrag() = Cfg.penDrag == "select"

    private fun tpMove(x: Float, y: Float) {
        val d = hypot(x - downX, y - downY)
        if (!moved && d > slop) { moved = true; removeCallbacks(longPress) }
        if (selectDrag() && !dragStarted) {  // ignore a few pixels of pen wobble, then catch up to where the pen is
            if (d < 3 * dp) return
            dragStarted = true; relMove(x - downX, y - downY); return
        }
        relMove(x - lastX, y - lastY)
    }

    private fun tpUp() {
        removeCallbacks(longPress)
        val now = SystemClock.uptimeMillis()
        if (dragging) { tx.pen(TRACKPAD, 2, 0, 0, 0); dragging = false; lastTapUp = 0 }
        else if (!moved && !longFired && now - downTime < 250) {
            tx.pen(TRACKPAD, 1, 0, 0, 0); tx.pen(TRACKPAD, 2, 0, 0, 0); lastTapUp = now
        } else lastTapUp = 0
        carryX = 0f; carryY = 0f
    }

    // ---------- trackpad: fingers ----------
    // One finger moves the cursor with raw movement, so Windows applies its own pointer speed. Two or more fingers are forwarded to
    // Windows as real touch contacts around the cursor, so Windows' own touch gestures do the rest (scroll, pinch, swipes). Nothing here
    // is mapped to a keyboard shortcut.
    private var fActive = false; private var fIgnore = false
    private var fMaxN = 0; private var fStart = 0L; private var fMoved = false
    private var fCx = 0f; private var fCy = 0f; private var fSx = 0f; private var fSy = 0f
    private var fDrag = false; private var fLastTap = 0L
    private val tSlot = HashMap<Int, Int>()          // pointer id -> touch contact number on the PC
    private val tStartX = HashMap<Int, Float>(); private val tStartY = HashMap<Int, Float>()
    private val tLast = HashMap<Int, IntArray>()     // pointer id -> last offset sent, in PC pixels
    private var tAnchorX = 0f; private var tAnchorY = 0f
    private var tAxis = 0                            // 0 undecided, 1 vertical, 2 horizontal, 3 free

    private fun isFinger(e: MotionEvent, i: Int) = e.getToolType(i).let { it != TOOL_TYPE_STYLUS && it != MotionEvent.TOOL_TYPE_ERASER && it != 5 /* palm */ }

    private fun sendTouch(phase: Int, id: Int, px: Float, py: Float) {
        val slot = tSlot[id] ?: return
        val k = if (tx.screenW > 0 && width > 0) tx.screenW.toFloat() / width else 1f // tablet pixels -> PC pixels
        val ox = ((px - tAnchorX) * k).roundToInt(); val oy = ((py - tAnchorY) * k).roundToInt()
        val last = tLast[id]
        if (phase == 0 && last != null && last[0] == ox && last[1] == oy) return
        tLast[id] = intArrayOf(ox, oy)
        tx.touch(phase, slot, ox, oy)
    }

    private fun joinTouch(e: MotionEvent) {
        for (i in 0 until e.pointerCount) {
            if (!isFinger(e, i)) continue
            val id = e.getPointerId(i); if (id in tSlot) continue
            if (tSlot.isEmpty()) { tAnchorX = e.getX(i); tAnchorY = e.getY(i); tAxis = 0 }
            tSlot[id] = (0..9).first { s -> s !in tSlot.values }
            tStartX[id] = e.getX(i); tStartY[id] = e.getY(i)
            sendTouch(1, id, e.getX(i), e.getY(i))
        }
    }

    /** Sends where each finger is. Once you clearly swipe along one axis, the other axis is held still (unless axis lock is off). */
    private fun updateTouch(e: MotionEvent) {
        var sumX = 0f; var sumY = 0f; var n = 0; var far = 0f
        for (i in 0 until e.pointerCount) {
            val id = e.getPointerId(i); val x0 = tStartX[id] ?: continue; val y0 = tStartY[id] ?: continue
            sumX += e.getX(i) - x0; sumY += e.getY(i) - y0; n++
            far = maxOf(far, hypot(e.getX(i) - x0, e.getY(i) - y0))
        }
        if (n == 0) return
        val dX = sumX / n; val dY = sumY / n   // how the group moved
        if (far > slop) fMoved = true
        if (tAxis == 0 && hypot(dX, dY) > slop) {
            val r = when (Cfg.axisLock) { 0 -> Float.MAX_VALUE; 2 -> 1f; else -> 1.5f }
            tAxis = if (abs(dY) > abs(dX) * r) 1 else if (abs(dX) > abs(dY) * r) 2 else 3
        }
        val dir = (if (Cfg.naturalScroll) 1f else -1f) * Cfg.scrollSpeed
        val gx = if (tAxis == 0 || tAxis == 1) 0f else dX * dir   // vertical swipe: no sideways drift
        val gy = if (tAxis == 0 || tAxis == 2) 0f else dY * dir   // horizontal swipe: no up/down drift
        for (i in 0 until e.pointerCount) {
            val id = e.getPointerId(i); val x0 = tStartX[id] ?: continue; val y0 = tStartY[id] ?: continue
            // how this finger moves relative to the group is kept as is, so pinch and rotate still work
            sendTouch(0, id, x0 + gx + (e.getX(i) - x0 - dX), y0 + gy + (e.getY(i) - y0 - dY))
        }
    }

    private fun leaveTouch(id: Int) {
        val slot = tSlot[id] ?: return
        val l = tLast[id] ?: intArrayOf(0, 0)
        tx.touch(2, slot, l[0], l[1])
        tSlot.remove(id); tStartX.remove(id); tStartY.remove(id); tLast.remove(id)
    }

    private fun endTouchSession() { for (id in tSlot.keys.toList()) leaveTouch(id) }

    private fun fingerReset() {
        endTouchSession()
        if (fDrag) tx.pen(TRACKPAD, 2, 0, 0, 0)
        fActive = false; fDrag = false; fMaxN = 0; fMoved = false
    }

    private fun fingerTouch(e: MotionEvent) {
        val a = e.actionMasked
        if (a == MotionEvent.ACTION_CANCEL) { fingerReset(); return }
        if (a == MotionEvent.ACTION_DOWN) fIgnore = (e.y < barH && !locked) || (mode == PRESENT && e.y < presZoneTop) || (e.flags and 0x20 /* FLAG_CANCELED */) != 0
        if (fIgnore) return
        val lift = if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_POINTER_UP) e.actionIndex else -1
        var n = 0; var sx = 0f; var sy = 0f
        for (i in 0 until e.pointerCount) { if (i == lift || !isFinger(e, i)) continue; n++; sx += e.getX(i); sy += e.getY(i) }
        val now = SystemClock.uptimeMillis()
        if (a == MotionEvent.ACTION_DOWN) {
            endTouchSession()
            fActive = true; fMaxN = 0; fMoved = false; fStart = now; carryX = 0f; carryY = 0f
            fDrag = now - fLastTap < 500  // tap, then touch and drag
            if (fDrag) tx.pen(TRACKPAD, 1, 0, 0, 0)
            fSx = e.x; fSy = e.y
        }
        if (!fActive) return
        if (n > fMaxN) fMaxN = n
        if (n > 0 && a != MotionEvent.ACTION_MOVE) { fCx = sx / n; fCy = sy / n }
        if (a == MotionEvent.ACTION_POINTER_DOWN && n >= 2) joinTouch(e)
        if (a == MotionEvent.ACTION_POINTER_UP || a == MotionEvent.ACTION_UP) leaveTouch(e.getPointerId(e.actionIndex))
        if (a == MotionEvent.ACTION_MOVE && n > 0) {
            val cx = sx / n; val cy = sy / n
            if (n == 1 && fMaxN == 1) {
                if (hypot(cx - fSx, cy - fSy) > slop) fMoved = true
                relMove(cx - fCx, cy - fCy) // raw movement: Windows applies its own pointer speed
            } else if (tSlot.isNotEmpty()) updateTouch(e)
            fCx = cx; fCy = cy
        }
        if (a == MotionEvent.ACTION_UP && n == 0) {
            if (fDrag) { tx.pen(TRACKPAD, 2, 0, 0, 0); fDrag = false; fLastTap = 0 }
            else if (!fMoved && now - fStart < 250) when (fMaxN) {
                1 -> { tx.pen(TRACKPAD, 1, 0, 0, 0); tx.pen(TRACKPAD, 2, 0, 0, 0); fLastTap = now }
                2 -> tx.pen(TRACKPAD, 3, 0, 0, 0)     // two-finger tap: right click
            } else fLastTap = 0
            fActive = false; fMaxN = 0; endTouchSession()
        }
    }

    // ---------- tablet ----------
    private var relX = 0f; private var relY = 0f; private var relValid = false; private var rcx = 0f; private var rcy = 0f
    private var penInside = false

    /** The part of the tablet the pen uses to reach the whole PC screen. */
    private fun areaPx() = RectF(Cfg.ax * width, barH + Cfg.ay * padH(), (Cfg.ax + Cfg.aw) * width, barH + (Cfg.ay + Cfg.ah) * padH())

    private fun penHeld(action: String, bs: Int) = Cfg.penButtons.any { it.action == action && bs and it.bit != 0 }

    private fun penFlags(bs: Int): Int {
        var f = 0
        if (penHeld("barrel", bs)) f = f or 1
        if (eraserOn || penHeld("eraser-hold", bs)) f = f or 2
        return f
    }

    private fun gestureHeld(bs: Int) = penHeld("gesture", bs)

    private val filtX = OneEuro(); private val filtY = OneEuro()
    private var lastPenT = 0L

    private fun tablet(action: Int, x0: Float, y0: Float, pressureRaw: Float, tX: Int, tY: Int, buttons: Int) {
        val tms = sampleT
        var x = x0; var y = y0
        if (Cfg.smooth > 0) { // steady the pen position (one filter per axis)
            val (minC, beta) = when (Cfg.smooth) { 1 -> 6f to 0.04f; 2 -> 3f to 0.03f; else -> 1.5f to 0.02f }
            x = filtX.filter(x0, tms, minC, beta); y = filtY.filter(y0, tms, minC, beta)
        }
        val dt = (tms - lastPenT).coerceIn(0L, 255L).toInt(); lastPenT = tms // spacing between samples, so the PC can replay them evenly
        val ar = areaPx()
        if (x < ar.left || x > ar.right || y < ar.top || y > ar.bottom) { // outside the active area the pen does nothing
            if (penInside) { penInside = false; relValid = false; tx.pen(TABLET, 3, 0, 0, 0) }
            return
        }
        penInside = true
        var act = action
        val raw = pressureRaw.coerceIn(0f, 1f)
        if (act == 1 && raw < Cfg.pressMin) act = 0 // below the click threshold it is only hovering
        val pr = ((if (Cfg.gamma == 1f) raw else raw.pow(Cfg.gamma)) * 65535).roundToInt() // raw pressure unless a curve is set
        val b = penFlags(buttons)
        val ttx = if (Cfg.tiltOn) tX else 0; val tty = if (Cfg.tiltOn) tY else 0
        if (!Cfg.tabletRel) {
            val u = (x - ar.left) / ar.width(); val v = (y - ar.top) / ar.height()
            val (ru, rv) = when (Cfg.rotation) { 1 -> (1 - v) to u; 2 -> (1 - u) to (1 - v); 3 -> v to (1 - u); else -> u to v }
            val fu = if (Cfg.flipX) 1 - ru else ru; val fv = if (Cfg.flipY) 1 - rv else rv
            tx.pen(TABLET, act, b, (fu * 65535).roundToInt(), (fv * 65535).roundToInt(), pr, ttx, tty, dt)
            return
        }
        // relative: send movement (turned and flipped like the absolute mapping), keep sub-pixel remainders
        var dx = if (relValid) x - relX else 0f; var dy = if (relValid) y - relY else 0f
        relX = x; relY = y; relValid = true
        when (Cfg.rotation) { 1 -> { val t = dx; dx = -dy; dy = t }; 2 -> { dx = -dx; dy = -dy }; 3 -> { val t = dx; dx = dy; dy = -t } }
        if (Cfg.flipX) dx = -dx; if (Cfg.flipY) dy = -dy
        val fx = dx + rcx; val fy = dy + rcy
        val ix = fx.toInt(); val iy = fy.toInt(); rcx = fx - ix; rcy = fy - iy
        tx.pen(TABLET, act, b or 4, ix, iy, pr, ttx, tty, dt)
    }

    // ---------- tablet: your buttons ----------
    private val keyPtr = HashMap<Int, ExpressKey>()   // pointer id -> tablet button it is holding
    private var selKey: ExpressKey? = null

    private fun kcx(k: ExpressKey) = k.fx * width
    private fun kcy(k: ExpressKey) = barH + k.fy * padH()
    private fun kr(k: ExpressKey) = k.size * padH()
    private fun keyAt(x: Float, y: Float): ExpressKey? =
        Cfg.keys.filter { hypot(x - kcx(it), y - kcy(it)) < kr(it) }.minByOrNull { hypot(x - kcx(it), y - kcy(it)) }

    private fun releaseKeys() {
        for ((_, k) in keyPtr) if (k.hold) perform(k.action, 2)
        keyPtr.clear()
    }

    /** Presses and releases tablet buttons for any pointer (pen or finger). Returns true if the event was fully used by a button. */
    private fun keyTouch(e: MotionEvent): Boolean {
        val ai = e.actionIndex; val id = e.getPointerId(ai)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val k = keyAt(e.getX(ai), e.getY(ai)) ?: return false
                keyPtr[id] = k; perform(k.action, if (k.hold) 1 else 0); invalidate(); return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val k = keyPtr.remove(id) ?: return false
                if (k.hold) perform(k.action, 2); invalidate(); return true
            }
            MotionEvent.ACTION_CANCEL -> { releaseKeys(); return false }
            MotionEvent.ACTION_MOVE -> return keyPtr.isNotEmpty() && keyPtr.size == e.pointerCount
        }
        return false
    }

    // ---------- tablet: pen gestures ----------
    private var gesturing = false
    private val gPts = ArrayList<Float>()
    private var gMsg = ""; private var gMsgUntil = 0L

    private fun finishGesture() {
        gesturing = false
        val g = Recog.match(gPts.toFloatArray(), Cfg.gestures)
        if (g != null) { perform(g.action, 0); gMsg = "GESTURE: ${g.name}" } else gMsg = if (Cfg.gestures.isEmpty()) "NO GESTURES RECORDED YET" else "NO MATCH"
        gMsgUntil = SystemClock.uptimeMillis() + 1500
        gPts.clear(); invalidate(); postInvalidateDelayed(1600)
    }

    // ---------- controller ----------
    private val btnPtr = HashMap<Int, Ctl>()      // pointer id -> button it is holding
    private val stickPtr = HashMap<Int, Ctl>()    // pointer id -> stick it is steering
    private val stickVal = HashMap<String, FloatArray>() // stick id -> x, y in -1..1 (y down), for drawing and sending
    private val skip = HashSet<Int>()             // pointers that started on the bar or a menu button
    private var selected: Ctl? = null
    private var grabX = 0f; private var grabY = 0f

    private fun padH() = height - barH
    private fun cx(c: Ctl) = c.fx * width
    private fun cy(c: Ctl) = barH + c.fy * padH()
    /** Default sizes come from the screen as a whole (the geometric mean of the pad's width and height), so the same layout suits a phone and a tablet. */
    private fun ctlBasis() = sqrt(width * padH())
    private fun cr(c: Ctl) = if (autoFit) c.fr * ctlBasis() * fitK else c.fr * padH()

    private fun ctlAt(x: Float, y: Float, sticks: Boolean): Ctl? {
        var best: Ctl? = null; var bd = 1.44f   // 1.2 squared: a little slack around each control
        for (k in vis) {
            if (!sticks && k.isStick) continue
            val r = cr(k); val dx = x - cx(k); val dy = y - cy(k); val d = (dx * dx + dy * dy) / (r * r)
            if (d < bd) { bd = d; best = k }
        }
        return best
    }

    private fun steer(s: Ctl, x: Float, y: Float) {
        var dx = (x - cx(s)) / cr(s); var dy = (y - cy(s)) / cr(s)
        val m = hypot(dx, dy); if (m > 1) { dx /= m; dy /= m }
        stickVal[s.id] = floatArrayOf(dx, dy)
    }

    /** Every pointer (pen or finger) works its own control, so sticks and buttons can be used together. */
    private fun controllerTouch(e: MotionEvent) {
        val a = e.actionMasked
        val palmGuard = SystemClock.uptimeMillis() - lastStylusMs <= 250
        fun ok(i: Int) = e.getToolType(i).let { it == TOOL_TYPE_STYLUS || (it != 5 && !palmGuard) }
        when (a) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex; val id = e.getPointerId(i); val x = e.getX(i); val y = e.getY(i)
                if (e.getToolType(i) == TOOL_TYPE_STYLUS) requestUnbufferedDispatch(e)
                if (!ok(i)) skip.add(id)
                else if (y < barH) { skip.add(id); if (!barLocked) bar(x) }
                else if (uiHit(x, y)) skip.add(id)
                else {
                    val taken = stickPtr.values.map { o -> o.id }
                    val s = vis.filter { it.isStick && it.id !in taken }.firstOrNull { hypot(x - cx(it), y - cy(it)) < cr(it) * 1.2f }
                    if (s != null) { stickPtr[id] = s; steer(s, x, y) } else ctlAt(x, y, false)?.let { btnPtr[id] = it }
                }
            }
            MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) {
                val id = e.getPointerId(i)
                if (id in skip) continue
                val s = stickPtr[id]
                if (s != null) steer(s, e.getX(i), e.getY(i))
                else if (id in btnPtr) { val k = ctlAt(e.getX(i), e.getY(i), false); if (k != null) btnPtr[id] = k else btnPtr.remove(id) }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val id = e.getPointerId(e.actionIndex)
                skip.remove(id); btnPtr.remove(id); stickPtr.remove(id)?.let { stickVal.remove(it.id) }
            }
            MotionEvent.ACTION_CANCEL -> ctrlReset()
        }
        if (sendPad()) invalidate()
    }

    private fun ctrlReset() {
        btnPtr.clear(); stickPtr.clear(); stickVal.clear(); skip.clear()
        if (mode == CONTROLLER) { if (sendPad()) invalidate() }
    }

    private val lastPad = IntArray(7) { Int.MIN_VALUE }   // what the PC was last told

    /** Sends the controller state if it changed. Returns whether anything was sent. */
    private fun sendPad(): Boolean {
        var mask = 0; var l2 = 0; var r2 = 0
        for (k in btnPtr.values) when { k.bit >= 0 -> mask = mask or (1 shl k.bit); k.bit == L2 -> l2 = 255; k.bit == R2 -> r2 = 255 }
        fun ax(id: String, i: Int) = ((stickVal[id]?.get(i) ?: 0f) * 127 * (if (i == 1) -1 else 1)).roundToInt()
        val now = intArrayOf(mask, ax("sl", 0), ax("sl", 1), ax("sr", 0), ax("sr", 1), l2, r2)
        if (now.contentEquals(lastPad)) return false
        now.copyInto(lastPad)
        tx.pad(now[0], now[1], now[2], now[3], now[4], now[5], now[6])
        return true
    }

    /** Drag controls (controller screen) or tablet buttons (tablet screen) into place. */
    private fun editTouch(e: MotionEvent) {
        val x = e.x; val y = e.y
        val keys = mode == TABLET
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (y < barH) { bar(x); return }
                if (keys) { selKey = keyAt(x, y); selKey?.let { grabX = x - kcx(it); grabY = y - kcy(it) } }
                else {
                    selected = ctlAt(x, y, true)
                    if (selected != null) bake()
                    selected?.let { grabX = x - cx(it); grabY = y - cy(it) }
                }
            }
            MotionEvent.ACTION_MOVE -> if (keys) selKey?.let {
                it.fx = ((x - grabX) / width).coerceIn(0.03f, 0.97f); it.fy = ((y - grabY - barH) / padH()).coerceIn(0.03f, 0.97f)
            } else selected?.let {
                it.fx = ((x - grabX) / width).coerceIn(0.03f, 0.97f); it.fy = ((y - grabY - barH) / padH()).coerceIn(0.03f, 0.97f)
            }
            MotionEvent.ACTION_UP -> if (keys) saveKeys() else saveLayout()
        }
        if (e.actionMasked == MotionEvent.ACTION_MOVE) { if (keys) keepKeysInside() else keepInside(false) }
        invalidate()
    }

    // ---------- drawing helpers ----------
    private fun fillR(c: Canvas, r: RectF, color: Int) { p.style = Paint.Style.FILL; p.color = color; c.drawRect(r, p) }

    private fun outline(c: Canvas, r: RectF, w: Float = 3 * dp, color: Int = INK) {
        p.style = Paint.Style.STROKE; p.strokeWidth = w; p.color = color
        c.drawRect(r.left + w / 2, r.top + w / 2, r.right - w / 2, r.bottom - w / 2, p)
    }

    /** Draws text; with maxW it shrinks to fit (down to 4dp) and then trims, so it never spills out of its button. */
    private fun text(c: Canvas, s: String, x: Float, y: Float, size: Float, align: Paint.Align = Paint.Align.CENTER, color: Int = INK, maxW: Float = 0f) {
        p.style = Paint.Style.FILL; p.color = color; p.textSize = size * dp; p.textAlign = align
        var t = s.uppercase()
        if (maxW > 0f) {
            val m = p.measureText(t)
            if (m > maxW) p.textSize = (size * dp * maxW / m).coerceAtLeast(4 * dp)
            while (t.length > 1 && p.measureText(t) > maxW) t = t.dropLast(2) + "…"
        }
        c.drawText(t, x, y, p)
    }

    private fun win(c: Canvas, r: RectF, title: String, tc: Int) {
        fillR(c, RectF(r.left + 5 * dp, r.top + 5 * dp, r.right + 5 * dp, r.bottom + 5 * dp), INK) // hard offset shadow
        fillR(c, r, PAPER); outline(c, r, 3 * dp)
        fillR(c, RectF(r.left + 3 * dp, r.top + 3 * dp, r.right - 3 * dp, r.top + 26 * dp), tc)
        fillR(c, RectF(r.left + 3 * dp, r.top + 26 * dp, r.right - 3 * dp, r.top + 29 * dp), INK)
        text(c, title, r.left + 12 * dp, r.top + 20 * dp, 12f, Paint.Align.LEFT)
    }

    private fun icon(c: Canvas, id: String, x: Float, y: Float, s: Float, color: Int = INK) = Icons.draw(c, p, dp, id, x, y, s, color)

    private fun pill(c: Canvas, r: RectF, color: Int, label: String, textColor: Int = INK, size: Float = 12f, icon: String? = null, stroke: Int = INK) {
        p.style = Paint.Style.FILL; p.color = color; c.drawRoundRect(r, r.height() / 2, r.height() / 2, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 3 * dp; p.color = stroke; c.drawRoundRect(r, r.height() / 2, r.height() / 2, p)
        if (icon != null && label.isNotEmpty()) { // icon then text, the pair centred in the button; the text shrinks if it must
            val s = r.height() * .22f; val gap = 6 * dp; val avail = r.width() - r.height() * .8f - 2 * s - gap
            p.textSize = size * dp; val tw = minOf(p.measureText(label.uppercase()), avail)
            val start = r.centerX() - (2 * s + gap + tw) / 2
            icon(c, icon, start + s, r.centerY(), s, textColor)
            text(c, label, start + 2 * s + gap, r.centerY() + size * dp * 0.35f, size, Paint.Align.LEFT, textColor, avail)
        } else if (icon != null) icon(c, icon, r.centerX(), r.centerY(), r.height() * .26f, textColor)
        else text(c, label, r.centerX(), r.centerY() + size * dp * 0.35f, size, Paint.Align.CENTER, textColor, r.width() - 16 * dp)
    }

    private fun button(c: Canvas, r: RectF, color: Int, label: String, size: Float = 12f, icon: String? = null, act: () -> Unit) {
        pill(c, r, color, label, INK, size, icon); uiBtns.add(r to act)
    }

    // ---------- drawing ----------
    override fun onDraw(c: Canvas) {
        uiBtns.clear()
        if (locked) { drawLocked(c); drawLatency(c, true); return }
        bgBmp?.let { c.drawBitmap(it, 0f, 0f, null) }
        drawBar(c)
        when (mode) {
            TRACKPAD -> drawTrackpad(c)
            TABLET -> drawTablet(c)
            CONTROLLER -> drawPad(c)
            PRESENT -> drawPresent(c)
        }
        if (SystemClock.uptimeMillis() < flashUntil) pill(c, RectF(width / 2f - 44 * dp, barH + 12 * dp, width / 2f + 44 * dp, barH + 60 * dp), LILAC, "", INK, 13f, flashMsg)
        drawLatency(c, false)
    }

    /** Round trip to the PC, and roughly half of it as the one-way delay, bottom left. */
    /** How you're connected (an icon: cable, Wi-Fi or Bluetooth) and the round trip in ms, bottom left. Tap it when there's no link to set up. */
    private fun drawLatency(c: Canvas, dim: Boolean) {
        val st = stats
        val ic = when (Cfg.transport) { "usb" -> "usb"; "wifi" -> "wifi"; else -> "bt" }
        val h = 26 * dp; val y = height - h - 6 * dp
        p.textSize = 11 * dp; p.style = Paint.Style.FILL
        val r = RectF(6 * dp, y, minOf(6 * dp + 40 * dp + p.measureText(latencyMsg), width * .75f), y + h)
        val ink = if (dim) 0xFF4F6FAF.toInt() else if (st.connected) INK else WARN
        if (!dim) {
            p.color = if (st.connected) PAPER else PINK; c.drawRoundRect(r, h / 2, h / 2, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 2 * dp; p.color = INK; c.drawRoundRect(r, h / 2, h / 2, p)
        }
        icon(c, ic, r.left + 18 * dp, r.centerY(), 7 * dp, ink)
        text(c, latencyMsg, r.left + 32 * dp, r.centerY() + 4 * dp, 11f, Paint.Align.LEFT, ink, width * .7f)
        if (!dim && !st.connected) uiBtns.add(r to { host.openSettings(0) })   // not connected: tap this to set up
        // which player this device is, to the right of the latency, in the theme colour (the colour PixelPad Desk shows for it)
        val slot = tx.slot
        if (st.connected && slot in 1..4) {
            val tag = "P$slot"; p.textSize = 11 * dp; p.style = Paint.Style.FILL
            val pr = RectF(r.right + 6 * dp, y, r.right + 6 * dp + p.measureText(tag) + 24 * dp, y + h)
            if (!dim) {
                p.color = Themes.current().accent; c.drawRoundRect(pr, h / 2, h / 2, p)
                p.style = Paint.Style.STROKE; p.strokeWidth = 2 * dp; p.color = INK; c.drawRoundRect(pr, h / 2, h / 2, p)
            }
            text(c, tag, pr.centerX(), pr.centerY() + 4 * dp, 11f, Paint.Align.CENTER, if (dim) 0xFF4F6FAF.toInt() else PAPER)
        }
    }

    /**
     * Locked: dark navy with everything turned down but still in the cute colours. The lock stays lit. Each screen shows only what
     * you need: the tablet its working area as an outline plus your buttons, the controller and presenter their controls as outlines.
     */
    private fun drawLocked(c: Canvas) {
        c.drawColor(DARK_BG)
        val r = lockRect()
        p.style = Paint.Style.STROKE; p.strokeWidth = 3 * dp; p.color = dimmed(HOT, .8f); c.drawRoundRect(r, r.height() / 2, r.height() / 2, p)
        icon(c, "lock", r.centerX(), r.centerY(), r.height() * .26f, dimmed(HOT, .95f))
        when (mode) {
            TABLET -> {   // just the outline of the working area
                val a = areaPx()
                p.style = Paint.Style.STROKE; p.strokeWidth = 4 * dp; p.color = dimmed(HOT, .8f)
                c.drawRect(a.left + 2 * dp, a.top + 2 * dp, a.right - 2 * dp, a.bottom - 2 * dp, p)
                drawKeys(c, true)
            }
            CONTROLLER -> drawPad(c, true)
            PRESENT -> drawPresent(c, true)
        }
        if (SystemClock.uptimeMillis() < gMsgUntil) text(c, gMsg, width / 2f, height - 24 * dp, 12f, Paint.Align.CENTER, dimmed(BABY, .7f), width - 24 * dp)
        else text(c, "LOCKED · DOUBLE-TAP THE LOCK TO UNLOCK", width / 2f, height - 24 * dp, 10f, Paint.Align.CENTER, dimmed(BABY, .35f), width - 24 * dp)
    }

    private fun drawBar(c: Canvas) {
        val w = width.toFloat()
        fillR(c, RectF(0f, 0f, w, barH), PAPER); fillR(c, RectF(0f, barH - 3 * dp, w, barH), INK)
        val armed = SystemClock.uptimeMillis() < exitArmedUntil
        pill(c, exitRect(), if (armed) HOT else PINK, "", if (armed) PAPER else INK, 12f, "power")
        if (lockAvailable()) { // off by default; double-tap to lock (controller: locks the top bar)
            val r = lockRect()
            pill(c, r, PAPER, "", INK, 12f, "unlock")
        }
        val tw = (w - inset) / 5; val connected = tx.connected()
        val icons = listOf("touchpad", "pen", "gamepad", "present", "gear")
        val colors = listOf(LILAC, PINK, GREEN, PEACH, BABY)
        for (i in 0..4) {
            val r = RectF(inset + i * tw + 4 * dp, 6 * dp, inset + (i + 1) * tw - 4 * dp, barH - 9 * dp)
            val sel = i == mode
            pill(c, r, if (sel) HOT else colors[i], "", if (sel) PAPER else INK, 12f, icons[i])
            if (i == 4) { // connection dot: green = connected, pink = not
                p.style = Paint.Style.FILL; p.color = if (connected) 0xFF3CBF4A.toInt() else HOT
                c.drawCircle(r.right - 8 * dp, r.top + 6 * dp, 6 * dp, p)
                p.style = Paint.Style.STROKE; p.strokeWidth = 2 * dp; p.color = INK; c.drawCircle(r.right - 8 * dp, r.top + 6 * dp, 6 * dp, p)
            }
        }
    }

    private fun drawTrackpad(c: Canvas) {
        outline(c, RectF(8 * dp, barH + 8 * dp, width - 8 * dp, height - 8 * dp), 4 * dp) // just the pad edge; the how-to lives in Settings
    }

    private fun drawKeys(c: Canvas, dim: Boolean) {
        for (k in Cfg.keys) {
            val x = kcx(k); val y = kcy(k); val r = kr(k); val on = k in keyPtr.values
            p.style = Paint.Style.FILL; p.color = if (dim) (if (on) 0xFF2F6FE0.toInt() else 0xFF0B1226.toInt()) else if (on) HOT else LILAC
            c.drawCircle(x, y, r, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = (if (dim) 2 else 3) * dp; p.color = if (dim) 0xFF2F6FE0.toInt() else INK; c.drawCircle(x, y, r, p)
            text(c, k.label, x, y + r * .18f, r / dp * .55f, Paint.Align.CENTER, if (dim) 0xFF7F9FDF.toInt() else if (on) PAPER else INK, r * 1.7f)
            if (editing && selKey === k) { p.style = Paint.Style.STROKE; p.strokeWidth = 4 * dp; p.color = HOT; c.drawCircle(x, y, r + 6 * dp, p) }
        }
    }

    private fun drawTablet(c: Canvas) {
        val ar = areaPx()
        fillR(c, RectF(0f, barH, width.toFloat(), height.toFloat()), 0xFF9DB6E8.toInt()) // outside the active area: dim
        c.save(); c.clipRect(ar); checkerBmp?.let { c.drawBitmap(it, 0f, barH, null) }; c.restore()
        outline(c, ar, 4 * dp, HOT)
        val info = (if (Cfg.tabletRel) "RELATIVE" else "ABSOLUTE") + " · PEN AREA = WHOLE PC SCREEN" +
            (if (Cfg.rotation != 0) " · ${Cfg.rotation * 90}°" else "") + (if (Cfg.flipX) " · FLIP X" else "") + (if (Cfg.flipY) " · FLIP Y" else "")
        text(c, info, ar.centerX(), ar.top + 22 * dp, 10.5f, Paint.Align.CENTER, INK, ar.width() - 24 * dp)
        if (Cfg.keys.isEmpty() && !editing) text(c, "ADD YOUR OWN BUTTONS IN SETTINGS > TABLET KEYS", ar.centerX(), ar.centerY(), 10f, Paint.Align.CENTER, INK, ar.width() - 24 * dp)
        drawKeys(c, false)
        if (eraserOn) pill(c, RectF(ar.left + 10 * dp, ar.bottom - 42 * dp, ar.left + 66 * dp, ar.bottom - 10 * dp), PINK, "", INK, 11f, "eraser")
        if (gesturing && gPts.size >= 4) {
            p.style = Paint.Style.STROKE; p.strokeWidth = 6 * dp; p.color = HOT
            val path = Path().apply { moveTo(gPts[0], gPts[1]); var i = 2; while (i < gPts.size) { lineTo(gPts[i], gPts[i + 1]); i += 2 } }
            c.drawPath(path, p)
        }
        if (SystemClock.uptimeMillis() < gMsgUntil) pill(c, RectF(width / 2f - 130 * dp, height - 52 * dp, width / 2f + 130 * dp, height - 18 * dp), LILAC, gMsg, INK, 12f)
        if (editing) {
            text(c, if (Cfg.keys.isEmpty()) "NO BUTTONS YET: ADD SOME IN SETTINGS > TABLET KEYS" else "DRAG A BUTTON TO MOVE IT · USE − AND + TO RESIZE", width / 2f, barH + 26 * dp, 11f, Paint.Align.CENTER, INK, width - 24 * dp)
            val w = 100 * dp; val y = height - 42 * dp
            val acts = listOf(Triple("", "minus") { selKey?.let { it.size = (it.size * .9f).coerceAtLeast(.04f) }; saveKeys() },
                Triple("", "plus") { selKey?.let { it.size = (it.size * 1.1f).coerceAtMost(.25f) }; saveKeys() },
                Triple("", "check") { editing = false; selKey = null; saveKeys() })
            val x0 = width / 2f - (w * 3 + 12 * dp) / 2
            acts.forEachIndexed { i, (l, ic, f) -> button(c, RectF(x0 + i * (w + 6 * dp), y, x0 + i * (w + 6 * dp) + w, y + 32 * dp), if (i == 2) GREEN else BABY, l, 11f, ic, f) }
        }
    }

    private fun drawPresent(c: Canvas, dark: Boolean = false) {
        val m = 8 * dp; val gap = 8 * dp
        val top = barH + m; val bottom = height - m; val w = width - 2 * m
        val bh = minOf((bottom - top) * .62f, 340 * dp)
        val half = (w - gap) / 2
        fun btn(r: RectF, color: Int, label: String, ic: String, act: () -> Unit) {
            if (dark) { pill(c, r, DARK_FACE, label, dimmed(color, .9f), 18f, ic, dimmed(color, .8f)); uiBtns.add(r to act) }
            else button(c, r, color, label, 18f, ic, act)
        }
        btn(RectF(m, top, m + half, top + bh), BABY, "", "left") { cmd(C_PREV) }
        btn(RectF(m + half + gap, top, m + w, top + bh), GREEN, "", "right") { cmd(C_NEXT) }
        val zone = RectF(m, top + bh + gap, width - m, bottom)
        presZoneTop = zone.top
        if (dark) outline(c, zone, 4 * dp, dimmed(BABY, .7f)) else { fillR(c, zone, PAPER); outline(c, zone, 4 * dp) }
        val ic = minOf(zone.height(), zone.width()) * .13f
        icon(c, "cursor", zone.centerX(), zone.centerY() - ic * .6f, ic, if (dark) dimmed(BABY, .6f) else 0xFF9DB6E8.toInt())
        text(c, "POINTER: MOVE THE PEN OR A FINGER · TAP = CLICK (NEXT SLIDE) · VOLUME KEYS = NEXT / PREV", zone.centerX(), zone.centerY() + ic * 1.2f, 10.5f, Paint.Align.CENTER,
            if (dark) dimmed(BABY, .8f) else INK, zone.width() - 24 * dp)
    }

    private fun drawPad(c: Canvas, dark: Boolean = false) {
        for (k in vis) {
            val x = cx(k); val y = cy(k); val r = cr(k)
            val on = k in btnPtr.values
            if (k.isStick) {
                val ring = if (dark) dimmed(BABY, .8f) else INK
                p.style = Paint.Style.FILL; p.color = if (dark) DARK_FACE else BABY; c.drawCircle(x, y, r, p)
                p.style = Paint.Style.STROKE; p.strokeWidth = 3 * dp; p.color = ring; c.drawCircle(x, y, r, p)
                val v = stickVal[k.id]; val on2 = v != null
                p.style = Paint.Style.FILL
                p.color = if (dark) (if (on2) dimmed(HOT, .85f) else dimmed(PINK, .5f)) else if (on2) HOT else PINK
                val kx = x + (v?.get(0) ?: 0f) * r; val ky = y + (v?.get(1) ?: 0f) * r
                c.drawCircle(kx, ky, r * .45f, p)
                p.style = Paint.Style.STROKE; p.color = ring; c.drawCircle(kx, ky, r * .45f, p)
            } else {
                val edge = if (dark) dimmed(if (k.color == PAPER) BABY else k.color, .85f) else INK
                p.style = Paint.Style.FILL; p.color = if (dark) (if (on) dimmed(HOT, .7f) else DARK_FACE) else if (on) HOT else k.color; c.drawCircle(x, y, r, p)
                p.style = Paint.Style.STROKE; p.strokeWidth = 3 * dp; p.color = edge; c.drawCircle(x, y, r, p)
                val tc = if (dark) edge else if (on) PAPER else INK
                if (k.icon != null) icon(c, k.icon, x, y, r * .5f, tc)
                else text(c, k.label, x, y + r * .25f, r / dp * .7f, Paint.Align.CENTER, tc, r * 1.75f)
            }
            if (!dark && editing && selected === k) {
                p.style = Paint.Style.STROKE; p.strokeWidth = 4 * dp; p.color = HOT; c.drawCircle(x, y, r + 6 * dp, p)
            }
        }
        if (dark) return
        if (!editing) {
            val y0 = height - 44 * dp
            button(c, RectF(width / 2f - 91 * dp, y0, width / 2f - 3 * dp, y0 + 32 * dp), LILAC, "EDIT", 12f, "edit") { editing = true; selected = null }
            button(c, RectF(width / 2f + 3 * dp, y0, width / 2f + 91 * dp, y0 + 32 * dp), PINK, tplLabel(tpl), 12f, "gamepad") {
                ctrlReset(); loadTemplate(TEMPLATES[(TEMPLATES.indexOf(tpl) + 1) % TEMPLATES.size])
            }
            return
        }
        text(c, if (selected == null) "TOUCH A CONTROL, THEN DRAG IT" else "DRAG TO MOVE · USE − AND + TO RESIZE", width / 2f, barH + 26 * dp, 11f, Paint.Align.CENTER, INK, width - 24 * dp)
        val w = 100 * dp; val y = height - 42 * dp
        val armed = SystemClock.uptimeMillis() < resetArmedUntil
        val acts = listOf(
            Triple(if (armed) "SURE?" else "", "retry") {
                val n = SystemClock.uptimeMillis()
                if (n < resetArmedUntil) { resetArmedUntil = 0; prefs.edit().remove(layoutKey()).apply(); loadTemplate(tpl) }
                else { resetArmedUntil = n + 2500; postInvalidateDelayed(2600) }
            },
            Triple("", "minus") { bake(); selected?.let { it.fr = (it.fr * .9f).coerceAtLeast(.03f) }; saveLayout() },
            Triple("", "plus") { bake(); selected?.let { it.fr = (it.fr * 1.1f).coerceAtMost(.35f) }; saveLayout() },
            Triple("", "check") { editing = false; selected = null; saveLayout() })
        val colors = listOf(if (armed) HOT else PINK, BABY, BABY, GREEN)
        val x0 = width / 2f - (w * 4 + 18 * dp) / 2
        acts.forEachIndexed { i, (l, ic, f) -> button(c, RectF(x0 + i * (w + 6 * dp), y, x0 + i * (w + 6 * dp) + w, y + 32 * dp), colors[i], l, 11f, ic, f) }
    }
}
