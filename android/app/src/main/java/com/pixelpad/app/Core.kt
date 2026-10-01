package com.pixelpad.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.hypot
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/** A tablet button the user maps to any shortcut. These are the only buttons that work while the tablet is locked. */
class ExpressKey(var id: String, var label: String, var action: String, var fx: Float, var fy: Float, var size: Float = 0.09f, var hold: Boolean = false)

/** A pen stroke the user recorded, and what it does. pts holds x0, y0, x1, y1... resampled and normalised. */
class Gesture(var name: String, var pts: FloatArray, var action: String)

/** A pen button the user recorded: its name, the button code the pen reports, and what it does (hold = active only while held). */
class PenButton(var name: String, var bit: Int, var action: String, var hold: Boolean = false)

/**
 * The app's theme colour, in the standard Nintendo Switch colours. This device's colour is whatever it says it is: it is chosen here
 * (or picked once at random on first use) and sent to PixelPad Desk, which only displays it. The PC never assigns a colour.
 * top/bottom make the background gradient; accent is the stronger colour used for what is selected (dark enough for white text).
 */
object Themes {
    class T(val name: String, val top: Int, val bottom: Int, val accent: Int)
    val list = listOf(
        T("NEON BLUE", 0xFFD8F6FF.toInt(), 0xFF7ED8F5.toInt(), 0xFF0A9BC8.toInt()), T("NEON RED", 0xFFFFE0DC.toInt(), 0xFFFF8F84.toInt(), 0xFFE0301E.toInt()),
        T("NEON GREEN", 0xFFE3FFDC.toInt(), 0xFF8EEA7A.toInt(), 0xFF14A800.toInt()), T("NEON PINK", 0xFFFFE0EA.toInt(), 0xFFFF8FB4.toInt(), 0xFFE02467.toInt()),
        T("NEON YELLOW", 0xFFFCFFD0.toInt(), 0xFFEEF56A.toInt(), 0xFFB38F00.toInt()), T("NEON PURPLE", 0xFFF3DCFF.toInt(), 0xFFD58CF5.toInt(), 0xFF9A00C8.toInt()),
        T("NEON ORANGE", 0xFFFFEAD6.toInt(), 0xFFFFB470.toInt(), 0xFFE06A00.toInt()), T("GREY", 0xFFEDEDED.toInt(), 0xFFB5B5B5.toInt(), 0xFF6B6B6B.toInt()),
    )
    /** The colour a setting ("0".."7") means. */
    fun index(theme: String) = (theme.toIntOrNull() ?: 0).coerceIn(0, list.size - 1)
    fun current(): T = list[index(Cfg.theme)]
}

/** A PC you have paired with. You choose its name; its address is kept so the app can reach it, but it is never shown. */
class SavedPc(var name: String, var host: String, var port: Int)

/** Everything the user can change. Values are saved as soon as they are set, and the main screen reads them live. */
object Cfg {
    lateinit var prefs: SharedPreferences
    val keys = ArrayList<ExpressKey>()
    val gestures = ArrayList<Gesture>()
    val penButtons = ArrayList<PenButton>()
    val pcs = ArrayList<SavedPc>()

    fun init(ctx: Context) {
        if (::prefs.isInitialized) return
        prefs = ctx.applicationContext.getSharedPreferences("pixelpad", Context.MODE_PRIVATE)
        runCatching {
            val a = JSONArray(prefs.getString("keys", "[]"))
            for (i in 0 until a.length()) a.getJSONObject(i).let {
                keys.add(ExpressKey(it.getString("id"), it.getString("label"), it.getString("action"), it.getDouble("fx").toFloat(), it.getDouble("fy").toFloat(), it.getDouble("size").toFloat(), it.optBoolean("hold")))
            }
        }
        if (keys.isEmpty() && !prefs.getBoolean("keysSeeded", false)) seedKeys()
        runCatching {
            val a = JSONArray(prefs.getString("gestures", "[]"))
            for (i in 0 until a.length()) a.getJSONObject(i).let {
                val p = it.getJSONArray("pts"); gestures.add(Gesture(it.getString("name"), FloatArray(p.length()) { j -> p.getDouble(j).toFloat() }, it.getString("action")))
            }
        }
        runCatching {
            val a = JSONArray(prefs.getString("penButtons", "[]"))
            for (i in 0 until a.length()) a.getJSONObject(i).let { penButtons.add(PenButton(it.getString("name"), it.getInt("bit"), it.getString("action"), it.optBoolean("hold"))) }
        }
        if (penButtons.isEmpty() && !prefs.getBoolean("penSeeded", false)) { resetPenButtons(); prefs.edit().putBoolean("penSeeded", true).apply() }
        runCatching {
            val a = JSONArray(prefs.getString("pcs", "[]"))
            for (i in 0 until a.length()) a.getJSONObject(i).let { pcs.add(SavedPc(it.getString("name"), it.getString("host"), it.getInt("port"))) }
        }
        if (pcs.isEmpty() && host.isNotBlank()) { pcs.add(SavedPc("MY PC", host, port)); savePcs() }   // a PC saved before PCs had names
        if (theme.toIntOrNull() == null) theme = (0 until 4).random().toString()   // first use, or the old "auto": pick one of the four player colours once; after that it only changes when you change it
    }

    fun savePcs() = prefs.edit().putString("pcs", JSONArray().apply { pcs.forEach { put(JSONObject().put("name", it.name).put("host", it.host).put("port", it.port)) } }.toString()).apply()

    /** Saves a PC under the name you picked (or renames it if that address is already saved). */
    fun addPc(name: String, host: String, port: Int): SavedPc {
        val n = name.trim().take(20).ifEmpty { "MY PC" }
        val pc = pcs.firstOrNull { it.host == host && it.port == port }?.also { it.name = n } ?: SavedPc(n, host, port).also { pcs.add(it) }
        savePcs(); return pc
    }

    /** Makes this PC the one the app connects to. */
    fun selectPc(pc: SavedPc) { host = pc.host; port = pc.port }
    fun activePc(): SavedPc? = pcs.firstOrNull { it.host == host && it.port == port }
    fun removePc(pc: SavedPc) { val wasActive = activePc() === pc; pcs.remove(pc); savePcs(); if (wasActive) { host = ""; port = 7777 } }

    /** A starter set, like a drawing tablet's express keys; edit or remove freely. */
    fun seedKeys() {
        listOf("UNDO" to "key:ctrl+z", "REDO" to "key:ctrl+y", "ERASER" to "eraser", "BRUSH -" to "key:[", "BRUSH +" to "key:]").forEachIndexed { i, (l, a) ->
            keys.add(ExpressKey("seed$i", l, a, 0.94f, 0.14f + 0.18f * i, 0.075f))
        }
        prefs.edit().putBoolean("keysSeeded", true).apply(); saveKeys()
    }

    /** The two buttons most pens have, with the behaviour they had before pen buttons became recordable. Keeps older settings. */
    fun resetPenButtons() {
        val lower = when (prefs.getString("penPrimary", "pc")) { "right" -> "mouse:right"; "middle" -> "mouse:middle"; "eraser" -> "eraser-hold"; "gesture" -> "gesture"; else -> "barrel" }
        val upper = when (prefs.getString("penSecondary", "mode")) { "eraser" -> "eraser-hold"; "gesture" -> "gesture"; "none" -> "none"; else -> "mode:next" }
        penButtons.clear()
        penButtons.add(PenButton("LOWER BUTTON", 32, lower)); penButtons.add(PenButton("UPPER BUTTON", 64, upper))
        prefs.edit().remove("penPrimary").remove("penSecondary").apply()
        savePenButtons()
    }

    fun savePenButtons() = prefs.edit().putString("penButtons", JSONArray().apply {
        penButtons.forEach { put(JSONObject().put("name", it.name).put("bit", it.bit).put("action", it.action).put("hold", it.hold)) }
    }.toString()).apply()

    private class P<T : Any>(val key: String, val def: T) : ReadWriteProperty<Any?, T> {
        @Suppress("UNCHECKED_CAST")
        override fun getValue(thisRef: Any?, property: KProperty<*>): T = when (def) {
            is Boolean -> prefs.getBoolean(key, def) as T
            is Int -> prefs.getInt(key, def) as T
            is Float -> prefs.getFloat(key, def) as T
            else -> (prefs.getString(key, def as String) ?: def) as T
        }
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
            prefs.edit().apply {
                when (value) { is Boolean -> putBoolean(key, value); is Int -> putInt(key, value); is Float -> putFloat(key, value); else -> putString(key, value as String) }
            }.apply()
        }
    }

    // connection
    var theme by P("theme", "")              // this device's colour: an index into Themes.list (empty until first use, when one is picked at random)
    var transport by P("transport", "wifi")  // usb | wifi | bt (Wi-Fi with the QR code is the easy way in)
    var host by P("host", "")
    var port by P("port", 7777)

    // tablet: where the pen works on the tablet, and how it maps to the PC screen
    var tabletRel by P("tabletRel", false)   // relative (mouse-like) instead of absolute
    var ax by P("ax", 0f); var ay by P("ay", 0f); var aw by P("aw", 1f); var ah by P("ah", 1f) // active area, fractions of the tablet surface
    var keepShape by P("keepShape", true)    // keep the active area the same shape as the PC screen while resizing it
    var rotation by P("rotation", 0)         // 0, 1, 2, 3 quarter turns clockwise
    var flipX by P("flipX", false); var flipY by P("flipY", false)
    var gamma by P("gamma", 1f)              // pressure curve: 1 = raw, below 1 = softer, above 1 = firmer
    var pressMin by P("pressMin", 0f)        // click threshold: pressure needed before the pen counts as touching
    var tiltOn by P("tiltOn", true)
    var smooth by P("smooth", 2)             // stroke smoothing: 0 off, 1 low, 2 medium, 3 high (filters pen jitter here, evens out arrival times on the PC)
    var hoverRange by P("hoverRange", 100)   // % of the pen's hover distance that counts
    var linger by P("linger", 150)           // ms the pen stays in range after the sensor loses it

    // other screens
    var lockNav by P("lockNav", true)        // pin the app: blocks Android's own swipe, 3- and 4-finger gestures. Leave with the Exit button
    var penDrag by P("penDrag", "select")    // select: pressing the pen down and dragging drags/selects; move: it only moves the cursor
    var naturalScroll by P("naturalScroll", true)
    var trackSpeed by P("trackSpeed", 1f)    // 1.0 = raw: Windows' own pointer speed applies
    var scrollSpeed by P("scrollSpeed", 1f)  // how far multi-finger touch gestures travel
    var axisLock by P("axisLock", 1)         // 0 off, 1 normal, 2 strict: stops a mostly-vertical swipe drifting sideways (and vice versa)

    // the pen cursor ring on the PC (only sent once you change something here, so the PC's own choices aren't overwritten)
    var ringOn by P("ringOn", true); var ringSize by P("ringSize", 90); var ringStyle by P("ringStyle", 0)
    var ringColor by P("ringColor", 0); var ringThick by P("ringThick", 3); var ringTouched by P("ringTouched", false)
    var tpl by P("tpl", "ps")                // controller template
    var fightStick by P("fightStick", true)  // fighting pad: a joystick (true) or arrow buttons (false)
    var pending by P("pending", "")          // "controller" or "keys": open that screen's editor when the main screen returns

    fun saveKeys() = prefs.edit().putString("keys", JSONArray().apply {
        keys.forEach { put(JSONObject().put("id", it.id).put("label", it.label).put("action", it.action).put("fx", it.fx.toDouble()).put("fy", it.fy.toDouble()).put("size", it.size.toDouble()).put("hold", it.hold)) }
    }.toString()).apply()

    fun saveGestures() = prefs.edit().putString("gestures", JSONArray().apply {
        gestures.forEach { put(JSONObject().put("name", it.name).put("action", it.action).put("pts", JSONArray().apply { it.pts.forEach { v -> put(v.toDouble()) } })) }
    }.toString()).apply()

    fun newKey(): ExpressKey {
        val n = keys.size
        val k = ExpressKey(UUID.randomUUID().toString().take(8), "KEY ${n + 1}", "key:ctrl+z", 0.9f, (0.15f + 0.14f * (n % 6)).coerceAtMost(0.9f))
        keys.add(k); saveKeys(); return k
    }

    fun resetTablet() {
        ax = 0f; ay = 0f; aw = 1f; ah = 1f; keepShape = true; rotation = 0; flipX = false; flipY = false
        gamma = 1f; pressMin = 0f; tiltOn = true; smooth = 2; hoverRange = 100; linger = 150; tabletRel = false
    }
}

/** Key names <-> Windows virtual-key codes, so shortcuts like "ctrl+shift+z" can be sent as (modifiers, key). */
object Keys {
    val MODS = mapOf("ctrl" to 1, "shift" to 2, "alt" to 4, "win" to 8)
    private val NAMED = mapOf("space" to 0x20, "enter" to 0x0D, "esc" to 0x1B, "tab" to 0x09, "backspace" to 0x08, "delete" to 0x2E,
        "insert" to 0x2D, "home" to 0x24, "end" to 0x23, "pageup" to 0x21, "pagedown" to 0x22, "left" to 0x25, "up" to 0x26, "right" to 0x27,
        "down" to 0x28, "=" to 0xBB, "-" to 0xBD, "[" to 0xDB, "]" to 0xDD, "," to 0xBC, "." to 0xBE, "/" to 0xBF, ";" to 0xBA, "'" to 0xDE,
        "`" to 0xC0, "\\" to 0xDC)

    fun vk(name: String): Int? {
        NAMED[name]?.let { return it }
        if (name.length == 1 && (name[0] in 'a'..'z')) return name[0].uppercaseChar().code
        if (name.length == 1 && (name[0] in '0'..'9')) return name[0].code
        if (name.startsWith("f")) name.drop(1).toIntOrNull()?.let { if (it in 1..12) return 0x6F + it }
        return null
    }

    private val NAMES by lazy { NAMED.entries.associate { it.value to it.key } }

    /** The name of a virtual key, the reverse of vk(). */
    fun nameOf(vk: Int): String? = NAMES[vk] ?: when (vk) {
        in 0x41..0x5A, in 0x30..0x39 -> vk.toChar().lowercaseChar().toString()
        in 0x70..0x7B -> "f${vk - 0x6F}"
        else -> null
    }

    /** modifier bits + key -> "ctrl+shift+z", the text form used in actions. */
    fun text(mods: Int, vk: Int): String =
        (listOf("ctrl", "shift", "alt", "win").filter { (MODS[it] ?: 0) and mods != 0 } + listOfNotNull(if (vk != 0) nameOf(vk) else null)).joinToString("+")

    /** "ctrl+shift+z" -> (modifier bits, virtual key). The key is 0 for modifier-only shortcuts like "ctrl". Null if it can't be read. */
    fun parse(s: String): Pair<Int, Int>? {
        var mods = 0; var vk = 0
        val parts = s.lowercase().split("+").map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        for (p in parts) {
            val m = MODS[p]
            if (m != null) mods = mods or m
            else { if (vk != 0) return null; vk = vk(p) ?: return null }
        }
        return mods to vk
    }
}

object Actions {
    /** Actions that only make sense for a pen button: the tablet keys and gestures don't offer them. */
    val PEN_ONLY = setOf("barrel", "eraser-hold", "gesture")

    /** Ready-made things a button or gesture can do. "custom" lets the user type their own shortcut. */
    val presets = listOf(
        "Undo" to "key:ctrl+z", "Redo" to "key:ctrl+y", "Copy" to "key:ctrl+c", "Paste" to "key:ctrl+v", "Cut" to "key:ctrl+x",
        "Save" to "key:ctrl+s", "Select all" to "key:ctrl+a", "Pen eraser" to "eraser", "Brush (B)" to "key:b", "Eraser tool (E)" to "key:e",
        "Pan (hold Space)" to "key:space", "Zoom in" to "key:ctrl+=", "Zoom out" to "key:ctrl+-", "Brush bigger ]" to "key:]",
        "Brush smaller [" to "key:[", "Hold Ctrl" to "key:ctrl", "Hold Shift" to "key:shift", "Hold Alt" to "key:alt",
        "Enter" to "key:enter", "Escape" to "key:esc", "Tab" to "key:tab", "Delete" to "key:delete",
        "Left click" to "mouse:left", "Right click" to "mouse:right", "Middle click" to "mouse:middle",
        "Pass to the PC as a pen button" to "barrel", "Eraser while held" to "eraser-hold", "Draw a gesture while held" to "gesture",
        "Switch to the next screen" to "mode:next", "Do nothing" to "none",
        "Next slide" to "key:right", "Previous slide" to "key:left", "Screenshot" to "key:win+shift+s", "Task view" to "key:win+tab",
        "Custom shortcut..." to "custom")

    fun label(action: String): String =
        presets.firstOrNull { it.second == action }?.first ?: when {
            action.startsWith("key:") -> action.removePrefix("key:").uppercase()
            action.startsWith("keys:") -> action.removePrefix("keys:").uppercase().replace(",", "  THEN  ")
            else -> action.uppercase()
        }
}

/** Tiny stroke recogniser: resample, centre, scale, then compare point by point. Direction matters, so up and down are different gestures. */
object Recog {
    private const val N = 32

    fun normalise(raw: FloatArray): FloatArray? {
        if (raw.size < 8) return null
        var len = 0f
        for (i in 2 until raw.size step 2) len += hypot(raw[i] - raw[i - 2], raw[i + 1] - raw[i - 1])
        if (len < 1f) return null
        val step = len / (N - 1); val out = FloatArray(N * 2)
        out[0] = raw[0]; out[1] = raw[1]
        var n = 1; var acc = 0f; var px = raw[0]; var py = raw[1]; var i = 2
        while (i < raw.size && n < N) {
            val x = raw[i]; val y = raw[i + 1]; val d = hypot(x - px, y - py)
            if (d > 0f && acc + d >= step) {
                val t = (step - acc) / d; val nx = px + t * (x - px); val ny = py + t * (y - py)
                out[n * 2] = nx; out[n * 2 + 1] = ny; n++; px = nx; py = ny; acc = 0f
            } else { acc += d; px = x; py = y; i += 2 }
        }
        while (n < N) { out[n * 2] = raw[raw.size - 2]; out[n * 2 + 1] = raw[raw.size - 1]; n++ }
        var cx = 0f; var cy = 0f
        for (k in 0 until N) { cx += out[k * 2]; cy += out[k * 2 + 1] }
        cx /= N; cy /= N
        var mnx = Float.MAX_VALUE; var mxx = -Float.MAX_VALUE; var mny = Float.MAX_VALUE; var mxy = -Float.MAX_VALUE
        for (k in 0 until N) { out[k * 2] -= cx; out[k * 2 + 1] -= cy; mnx = minOf(mnx, out[k * 2]); mxx = maxOf(mxx, out[k * 2]); mny = minOf(mny, out[k * 2 + 1]); mxy = maxOf(mxy, out[k * 2 + 1]) }
        val size = maxOf(mxx - mnx, mxy - mny)
        if (size < 1f) return null
        for (k in out.indices) out[k] /= size
        return out
    }

    fun distance(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (k in 0 until N) s += hypot(a[k * 2] - b[k * 2], a[k * 2 + 1] - b[k * 2 + 1])
        return s / N
    }

    /** The closest recorded gesture, if it is close enough. */
    fun match(raw: FloatArray, list: List<Gesture>): Gesture? {
        val n = normalise(raw) ?: return null
        return list.filter { it.pts.size == N * 2 }.map { it to distance(n, it.pts) }.filter { it.second < 0.2f }.minByOrNull { it.second }?.first
    }
}

/** One line of the connection checklist. */
class Check(val ok: Boolean, val label: String, val hint: String)

object Core {
    val sender by lazy { Sender() }
    private var onScreen = 0

    /** Activities call this from onStart (+1) and onStop (-1). With nothing on screen the sender stops pinging and closes its sockets. */
    fun visible(delta: Int) { onScreen = (onScreen + delta).coerceAtLeast(0); sender.active = onScreen > 0 }

    /** Points the sender at whatever the settings say. Call after any connection setting changes. */
    fun applyConnection() { applyColour(); sender.configure(Cfg.transport != "usb", Cfg.host, Cfg.port) }

    /** Hands this device's colour to the sender, which puts it in every heartbeat so the PC always shows the real one. */
    fun applyColour() { sender.colourIndex = Themes.index(Cfg.theme) }
}
