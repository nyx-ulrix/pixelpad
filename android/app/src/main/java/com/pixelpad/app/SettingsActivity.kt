package com.pixelpad.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

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

/** Every setting in one place, in pages: Connection, Tablet, Buttons, Gestures, Controller, Trackpad, About. */
class SettingsActivity : Activity() {
    private val mono = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    private val handler = Handler(Looper.getMainLooper())
    private var live: Runnable? = null            // refreshes the visible page (connection checks, area info)
    private lateinit var content: LinearLayout
    private lateinit var tabRow: LinearLayout
    private lateinit var tabScroll: HorizontalScrollView
    private var page = 0
    private var saveConn: (() -> Unit)? = null  // saves the address and port fields when the page or screen is left
    private var autoUpdate = false                 // opened from the start-up update prompt: start the download as soon as the version is known
    private var afterAllow: (() -> Unit)? = null   // carry on with the update once Android's "install unknown apps" switch has been turned on
    private val pages = listOf("CONNECTION", "TRACKPAD", "TABLET", "PEN BUTTONS", "TABLET KEYS", "GESTURES", "CONTROLLER", "PC CURSOR", "APP")
    private val pageIcons = listOf("wifi", "touchpad", "pen", "penbutton", "keys", "gesture", "gamepad", "cursor", "gear")
    private val pageColors = listOf(BABY, LILAC, PINK, PEACH, LILAC, PEACH, GREEN, PINK, BABY)

    // ---------- small retro widgets ----------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun shape(fill: Int, radius: Int) = GradientDrawable().apply { setColor(fill); cornerRadius = radius.toFloat(); setStroke(dp(3), INK) }

    private fun label(text: String, size: Float = 12f, color: Int = INK) = IconText(this).apply {
        this.text = text.uppercase(); typeface = mono; setTextColor(color); textSize = size; letterSpacing = 0.05f
    }

    private fun iconD(id: String, color: Int = INK, size: Int = 22) = IconDrawable(id, color, dp(size))

    /** Icon and text (if any) sit in the middle of the button together. */
    private fun IconText.centred() { gravity = Gravity.START or Gravity.CENTER_VERTICAL; centreGroup = true }

    /** A button. With an icon it shows the icon (and the text, if any, beside it); icon-only buttons are the norm. */
    private fun pill(text: String, fill: Int = PAPER, textColor: Int = INK, icon: String? = null, onClick: () -> Unit) = label(text, 12f, textColor).apply {
        gravity = Gravity.CENTER; setPadding(dp(if (text.isEmpty()) 12 else 14), dp(9), dp(if (text.isEmpty()) 12 else 14), dp(9)); background = shape(fill, dp(24)); maxLines = 1
        if (icon != null) { centred(); setCompoundDrawablesWithIntrinsicBounds(iconD(icon, textColor), null, null, null); compoundDrawablePadding = if (text.isEmpty()) 0 else dp(8) }
        if (Build.VERSION.SDK_INT >= 26) setAutoSizeTextTypeUniformWithConfiguration(7, 13, 1, TypedValue.COMPLEX_UNIT_SP)
        setOnClickListener { onClick() }
    }

    private fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, weight: Float = 0f, bottom: Int = 8) =
        LinearLayout.LayoutParams(w, h, weight).apply { bottomMargin = dp(bottom) }

    private fun vbox() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun hbox(vararg v: View, weights: Boolean = true) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        v.forEachIndexed { i, x -> addView(x, LinearLayout.LayoutParams(if (weights) 0 else ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, if (weights) 1f else 0f).apply { if (i > 0) marginStart = dp(8) }) }
    }

    /** A titled window with a hard offset shadow. */
    private fun card(title: String, color: Int, vararg items: View): View {
        val body = vbox().apply { setPadding(dp(12), dp(10), dp(12), dp(4)) }
        items.forEach { if (it.layoutParams != null) body.addView(it) else body.addView(it, lp()) } // views that brought their own size keep it
        val inner = vbox().apply {
            background = shape(PAPER, 0)
            addView(label(title, 13f).apply { setBackgroundColor(color); setPadding(dp(12), dp(6), dp(12), dp(6)) })
            addView(View(context).apply { setBackgroundColor(INK) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)))
            addView(body)
        }
        return LinearLayout(this).apply {
            setBackgroundColor(INK); setPadding(0, 0, dp(6), dp(6))
            addView(inner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }.also { it.layoutParams = lp(bottom = 16) }
    }

    private fun confirm(title: String, message: String, yes: () -> Unit) {
        AlertDialog.Builder(this).setTitle(title.uppercase()).setMessage(message.uppercase())
            .setPositiveButton("YES") { _, _ -> yes() }.setNegativeButton("CANCEL", null).show()
    }

    /** An explanation, shown as plain text. */
    private fun note(text: String): View = label(text, 10f, INK).apply { alpha = .85f }

    private fun toggle(name: String, get: () -> Boolean, set: (Boolean) -> Unit): View {
        val b = pill("", PAPER) {}; b.centred()
        fun paint() { val on = get(); b.text = ""; b.setCompoundDrawablesWithIntrinsicBounds(iconD(if (on) "check" else "x", INK, 20), null, null, null); b.background = shape(if (on) GREEN else PAPER, dp(24)) }
        b.setOnClickListener { set(!get()); paint() }; paint()
        return hbox(label(name, 12f), b, weights = false).apply { (getChildAt(0).layoutParams as LinearLayout.LayoutParams).weight = 1f; (getChildAt(0).layoutParams as LinearLayout.LayoutParams).width = 0 }
    }

    private fun stepper(name: String, get: () -> String, minus: () -> Unit, plus: () -> Unit): View {
        val v = label(get(), 12f).apply { gravity = Gravity.CENTER; minWidth = dp(70) }
        val refresh = { v.text = get().uppercase() }
        val m = pill("", BABY, icon = "minus") { minus(); refresh() }; val p = pill("", BABY, icon = "plus") { plus(); refresh() }
        return hbox(label(name, 12f), m, v, p, weights = false).apply { (getChildAt(0).layoutParams as LinearLayout.LayoutParams).apply { weight = 1f; width = 0 } }
    }

    /** One row of choices; the selected one is highlighted. */
    private fun chooser(name: String, opts: List<Pair<String, String>>, get: () -> String, set: (String) -> Unit, icons: Map<String, String> = emptyMap(), fills: Map<String, Int> = emptyMap()): View {
        val pills = ArrayList<Pair<TextView, String>>()
        fun paint() = pills.forEach { (t, v) ->
            val on = get() == v; t.background = shape(if (on) HOT else (fills[v] ?: PAPER), dp(24)); t.setTextColor(if (on) PAPER else INK)
            icons[v]?.let { t.compoundDrawablePadding = dp(8); t.setCompoundDrawablesWithIntrinsicBounds(iconD(it, if (on) PAPER else INK), null, null, null) }
        }
        val col = vbox(); col.addView(label(name, 12f), lp(bottom = 4))
        val row = LinearLayout(this).apply { orientation = if (opts.size > 4) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL }
        opts.forEachIndexed { i, (text, v) ->
            val t = pill(text) { set(v); paint() }.also { if (icons.containsKey(v)) it.centred() }; pills.add(t to v)
            row.addView(t, if (opts.size > 4) lp(bottom = 4) else LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) marginStart = dp(6) })
        }
        col.addView(row); paint(); return col
    }

    private fun input(hint: String, value: String, numeric: Boolean = false) = EditText(this).apply {
        typeface = mono; setTextColor(INK); setHintTextColor(0x802F6FE0.toInt()); textSize = 13f; this.hint = hint; setText(value); setSingleLine()
        background = shape(PAPER, dp(4)); setPadding(dp(10), dp(8), dp(10), dp(8))
        if (numeric) inputType = InputType.TYPE_CLASS_NUMBER
        alwaysKeyboard()
    }

    // ---------- shell ----------
    private lateinit var rootView: LinearLayout

    /** The page background: your picked colour, or the one for your player number. */
    private fun applyTheme() { val t = Themes.current(); rootView.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(t.top, t.bottom)) }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Cfg.init(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(6))
        }
        rootView = root; applyTheme()
        val top = hbox(label("", 20f).apply { setCompoundDrawablesWithIntrinsicBounds(iconD("gear", INK, 32), null, null, null) }, pill("", PINK, icon = "x") { finish() }, weights = false)
        (top.getChildAt(0).layoutParams as LinearLayout.LayoutParams).apply { weight = 1f; width = 0 }
        root.addView(top, lp(bottom = 8))
        tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tabScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(tabRow) }
        root.addView(tabScroll, lp(bottom = 10))
        content = vbox()
        root.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        autoUpdate = intent.getBooleanExtra("update", false)
        show(intent.getIntExtra("page", 0))
    }

    override fun onDestroy() { Updater.onResult = null; super.onDestroy() }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) { super.onActivityResult(req, res, data); if (req == 5 && page == 0) show(0) }

    override fun onResume() { super.onResume(); afterAllow?.let { if (Updater.canInstall(this)) { afterAllow = null; it() } }; live?.let { handler.removeCallbacks(it); handler.post(it) }; window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY }
    override fun onStart() { super.onStart(); Core.visible(1) }
    override fun onStop() { Core.visible(-1); super.onStop() }
    override fun onPause() { saveConn?.invoke(); live?.let { handler.removeCallbacks(it) }; Core.sender.recordListener = null; Core.sender.cancelRecord(); super.onPause() }
    override fun onWindowFocusChanged(f: Boolean) { super.onWindowFocusChanged(f); if (f) onResume() }

    private fun show(i: Int) {
        saveConn?.invoke(); saveConn = null
        page = i.coerceIn(0, pages.size - 1)
        live?.let { handler.removeCallbacks(it) }; live = null
        tabRow.removeAllViews()
        pages.forEachIndexed { n, name ->
            tabRow.addView(pill(if (n == page) name else "", if (n == page) HOT else pageColors[n], if (n == page) PAPER else INK, pageIcons[n]) { show(n) }.apply { minWidth = if (n == page) dp(150) else dp(58) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) })
        }
        tabScroll.post { tabRow.getChildAt(page)?.let { tabScroll.scrollTo(maxOf(0, it.left - dp(24)), 0) } } // keep the open page's tab in view
        content.removeAllViews()
        when (page) {
            0 -> connection(); 1 -> trackpad(); 2 -> tablet(); 3 -> pen(); 4 -> buttons(); 5 -> gestures(); 6 -> controller(); 7 -> cursor(); else -> about()
        }
    }

    private fun every(ms: Long, f: () -> Unit) {
        val r = object : Runnable { override fun run() { f(); handler.postDelayed(this, ms) } }
        live = r; handler.post(r)
    }

    // ---------- pages ----------
    private fun connection() {
        val msg = label("", 10.5f, HOT)
        val checks = vbox()
        val pcBox = vbox()
        fun use(pc: SavedPc) { Cfg.selectPc(pc); if (Cfg.transport == "usb") Cfg.transport = "wifi"; Core.applyConnection(); Core.sender.reconnect() }
        fun rebuildPcs() {
            pcBox.removeAllViews()
            if (Cfg.pcs.isEmpty()) pcBox.addView(note("NO PC SAVED YET. SCAN THE QR CODE IN PIXELPAD DESK, OR ADD ONE BY ITS ADDRESS."), lp())
            Cfg.pcs.toList().forEach { pc ->
                val active = Cfg.activePc() === pc
                pcBox.addView(hbox(pill(pc.name, if (active) GREEN else PAPER, icon = if (active) "check" else "present") { use(pc); show(0) },
                    pill("", BABY, icon = "edit") { pcDialog(pc) { rebuildPcs() } },
                    pill("", PINK, icon = "x") { confirm("FORGET THIS PC?", pc.name) { Cfg.removePc(pc); Core.applyConnection(); show(0) } }, weights = false).apply {
                    (getChildAt(0).layoutParams as LinearLayout.LayoutParams).apply { weight = 1f; width = 0 }
                }, lp())
            }
        }
        rebuildPcs()
        content.addView(card("LINK", BABY,
            chooser("HOW TO CONNECT", listOf("USB CABLE" to "usb", "WI-FI" to "wifi", "BLUETOOTH" to "bt"), { Cfg.transport }, { Cfg.transport = it; Core.applyConnection(); show(0) }, mapOf("usb" to "usb", "wifi" to "wifi", "bt" to "bt")),
            if (Cfg.transport != "usb") vbox().apply {
                addView(label("MY PCS", 11f), lp(bottom = 4)); addView(pcBox)
                addView(pill("SCAN THE QR CODE FROM PIXELPAD DESK", LILAC, icon = "qr") { startActivityForResult(Intent(this@SettingsActivity, ScanActivity::class.java), 5) }, lp())
                addView(pill("ADD A PC BY ITS ADDRESS", PAPER, icon = "plus") { pcDialog(null) { show(0) } }, lp())
            } else vbox().apply {
                addView(note("USB NEEDS PIXELPAD DESK OPEN ON THE PC. IT SETS UP THE CABLE LINK BY ITSELF."), lp())
                val usbCode = input("PAIRING CODE", Cfg.usbKey.chunked(4).joinToString(" "))
                addView(label("PAIRING CODE (SHOWN IN PIXELPAD DESK UNDER THE QR CODE; SCANNING THE QR CODE ONCE FILLS IT IN)", 10f), lp(bottom = 4)); addView(usbCode, lp())
                addView(pill("SAVE THE CODE", PAPER, icon = "check") {
                    val k = Seal.parse(usbCode.text.toString())
                    if (k == null && usbCode.text.isNotBlank()) Toast.makeText(this@SettingsActivity, "THE PAIRING CODE IS 32 LETTERS AND DIGITS (0-9, A-F)", Toast.LENGTH_LONG).show()
                    else { Cfg.usbKey = k?.let { Seal.hex(it) } ?: ""; Core.applyConnection(); Core.sender.reconnect(); Toast.makeText(this@SettingsActivity, "SAVED", Toast.LENGTH_SHORT).show() }
                }, lp())
            },
            msg, hbox(pill("RETRY", BABY, icon = "retry") { Core.sender.reconnect(); msg.text = "" })))
        content.addView(card("CHECKLIST: WHY CAN'T I CONNECT?", GREEN, checks))
        val statLabel = label("", 10.5f)
        var sig = ""
        every(1000) {
            val list = connectionChecks()
            val now = list.joinToString("|") { "${it.ok}${it.label}${it.hint}" }
            if (now != sig) { // rebuild only when a line actually changed, so nothing flickers and the screen isn't churned
                sig = now; checks.removeAllViews()
                for (k in list) {
                    val box = label(if (k.ok) "✓" else "×", 13f, if (k.ok) INK else WARN).apply { gravity = Gravity.CENTER; background = shape(if (k.ok) GREEN else PINK, 0) }
                    val col = vbox().apply { addView(label(k.label, 11.5f)); if (!k.ok) addView(label(k.hint, 10f, WARN)) }
                    checks.addView(hbox(box, col, weights = false).apply {
                        (getChildAt(0).layoutParams as LinearLayout.LayoutParams).apply { width = dp(26); height = dp(26) }
                        (getChildAt(1).layoutParams as LinearLayout.LayoutParams).apply { weight = 1f; width = 0 }
                    }, lp(bottom = 8))
                }
                checks.addView(statLabel)
            }
            val st = Core.sender.stats()
            statLabel.text = if (st.connected) "ROUND TRIP AVG %.1f MS · MIN %.1f · MAX %.1f · %d PKT/S".format(st.avgUs / 1000.0, st.minUs / 1000.0, st.maxUs / 1000.0, st.rate) else "NOT CONNECTED YET"
        }
    }

    /**
     * Name a PC, or add one by its address. The address is only ever typed here; once saved it is never shown again.
     * pc = null adds a new one (name, address and port); otherwise it renames that PC.
     */
    private fun pcDialog(pc: SavedPc?, done: () -> Unit) {
        val name = input("NAME, E.G. HOME PC", pc?.name ?: "")
        val address = input("PC ADDRESS", ""); val port = input("PORT", "7777", true)
        val code = input("PAIRING CODE", "")
        val box = vbox().apply {
            setPadding(dp(16), dp(12), dp(16), dp(4)); background = shape(PAPER, dp(4))
            addView(label("NAME", 11f), lp(bottom = 4)); addView(name, lp())
            if (pc == null) { addView(label("PC ADDRESS (SHOWN IN PIXELPAD DESK'S SETTINGS IF YOU CAN'T SCAN)", 10f), lp(bottom = 4)); addView(address, lp()); addView(label("PORT", 11f), lp(bottom = 4)); addView(port, lp()); addView(label("PAIRING CODE (SHOWN IN PIXELPAD DESK UNDER THE QR CODE)", 10f), lp(bottom = 4)); addView(code, lp()) }
        }
        AlertDialog.Builder(this).setView(box).setPositiveButton("SAVE") { _, _ ->
            val typed = code.text.toString()
            if (typed.isNotBlank() && Seal.parse(typed) == null) Toast.makeText(this, "THE PAIRING CODE IS 32 LETTERS AND DIGITS (0-9, A-F)", Toast.LENGTH_LONG).show()
            else if (pc != null) { pc.name = name.text.toString().trim().take(20).ifEmpty { pc.name }; Seal.parse(typed)?.let { pc.key = Seal.hex(it) }; Cfg.savePcs(); Core.applyConnection(); done() }
            else if (address.text.isBlank()) Toast.makeText(this, "TYPE THE PC'S ADDRESS", Toast.LENGTH_SHORT).show()
            else {
                val saved = Cfg.addPc(name.text.toString(), address.text.toString().trim(), port.text.toString().toIntOrNull() ?: 7777, typed)
                if (saved == null) Toast.makeText(this, "THAT ADDRESS OR PORT ISN'T VALID", Toast.LENGTH_LONG).show()
                else { Seal.parse(typed)?.let { Cfg.usbKey = Seal.hex(it) }; Cfg.selectPc(saved); Cfg.transport = "wifi"; Core.applyConnection(); Core.sender.reconnect(); done() }
            }
        }.setNegativeButton("CANCEL", null).show()
    }

    private fun connectionChecks(): List<Check> {
        val r = ArrayList<Check>(); val t = Cfg.transport; val conn = Core.sender.connected(); val err = Core.sender.lastError
        if (t == "usb") {
            val plugged = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
            r += Check(plugged and BatteryManager.BATTERY_PLUGGED_USB != 0, "USB CABLE PLUGGED INTO THE PC", "USE A DATA CABLE, NOT A CHARGE-ONLY ONE.")
            val adb = Settings.Global.getInt(contentResolver, Settings.Global.ADB_ENABLED, 0) == 1
            r += Check(adb, "USB DEBUGGING IS ON", "SETTINGS > DEVELOPER OPTIONS > USB DEBUGGING, THEN TAP ALLOW ON THE PROMPT.")
        } else {
            if (t == "wifi") {
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val wifi = cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
                r += Check(wifi, "CONNECTED TO WI-FI", "JOIN THE SAME WI-FI NETWORK AS THE PC.")
            } else {
                val bt = try { android.bluetooth.BluetoothAdapter.getDefaultAdapter()?.isEnabled == true } catch (e: SecurityException) { true }
                r += Check(bt, "BLUETOOTH IS ON", "TURN BLUETOOTH ON AND PAIR WITH THE PC.")
            }
            val pc = Cfg.activePc()
            r += Check(pc != null && err != "badhost", if (pc != null) "PC SAVED: ${pc.name}" else "NO PC SAVED YET", "SCAN THE QR CODE IN PIXELPAD DESK, OR ADD A PC BY ITS ADDRESS.")
        }
        r += Check(Core.sender.paired(), if (Core.sender.paired()) "PAIRED: SIGNED AND ENCRYPTED" else "NOT PAIRED", "SCAN THE QR CODE IN PIXELPAD DESK (OR TYPE ITS PAIRING CODE) SO ONLY YOUR PC CAN TALK TO THIS APP AND NOBODY ELSE CAN CONTROL THE PC.")
        val older = if (Core.sender.paired()) " THIS APP IS PAIRED: PIXELPAD DESK MUST BE 1.3.0 OR NEWER, AND ITS PAIRING CODE MUST BE THE ONE SCANNED HERE (AFTER NEW PAIRING CODE, SCAN AGAIN)." else ""
        val hint = when (t) {
            "usb" -> "PIXELPAD DESK MUST BE OPEN ON THE PC. PRESS RECONNECT USB THERE, OR TRY WI-FI.$older"
            "wifi" -> "SAME WI-FI AS THE PC? ALLOW PIXELPAD DESK THROUGH WINDOWS FIREWALL. SCAN THE QR CODE AGAIN.$older"
            else -> "TURN ON BLUETOOTH TETHERING HERE. ON THE PC: BLUETOOTH, THIS DEVICE, CONNECT USING ACCESS POINT. THEN SCAN THE QR CODE."
        }
        r += Check(conn, if (conn) "PIXELPAD DESK ANSWERS · PLAYER ${Core.sender.slot}" else "PIXELPAD DESK ANSWERS" + (if (err.isNotEmpty()) " ($err)" else ""), hint)
        return r
    }

    private fun tablet() {
        val info = label("", 10.5f)
        val editor = AreaEditor(this) { info.text = it.uppercase() }
        content.addView(card("MODE", PINK, chooser("HOW THE PEN MOVES THE CURSOR", listOf("ABSOLUTE" to "abs", "RELATIVE" to "rel"), { if (Cfg.tabletRel) "rel" else "abs" }, { Cfg.tabletRel = it == "rel" }),
            note("ABSOLUTE: THE PEN SITS WHERE THE CURSOR IS. RELATIVE: THE PEN MOVES THE CURSOR LIKE A MOUSE.")))
        content.addView(card("ACTIVE AREA ON THE TABLET", LILAC,
            note("THE WHOLE PC SCREEN IS ALWAYS REACHABLE. THIS BOX IS THE PART OF THE TABLET THE PEN USES TO REACH IT. DRAG THE BOX TO MOVE IT, DRAG THE CORNER TO RESIZE."),
            editor.also { it.layoutParams = lp(h = dp(230)) }, info,
            hbox(pill("FULL TABLET", BABY) { Cfg.ax = 0f; Cfg.ay = 0f; Cfg.aw = 1f; Cfg.ah = 1f; editor.invalidate(); editor.report() },
                pill("MATCH PC SCREEN SHAPE", GREEN) { editor.matchScreen() }),
            toggle("KEEP PC SCREEN SHAPE WHILE RESIZING", { Cfg.keepShape }, { Cfg.keepShape = it }),
            note("ON: DRAGGING THE CORNER KEEPS THE BOX THE SAME SHAPE AS YOUR PC'S SCREEN. THE SHAPE IS REMEMBERED FROM THE LAST TIME YOU CONNECTED (16:9 UNTIL THEN), SO IT ALSO WORKS WHILE NOT CONNECTED.")))
        content.addView(card("STROKE SMOOTHING", BABY,
            chooser("SMOOTHNESS", listOf("OFF" to "0", "LOW" to "1", "MEDIUM" to "2", "HIGH" to "3"), { Cfg.smooth.toString() }, { Cfg.smooth = it.toInt(); Core.sender.syncSmooth() }),
            note("STEADIES SHAKY LINES AND EVENS OUT UNEVEN WI-FI. THE PEN POSITION IS FILTERED (FAST STROKES BARELY CHANGE) AND THE PC REPLAYS EACH PEN SAMPLE WITH THE SPACING YOU DREW IT, AFTER A SMALL FIXED DELAY (LOW 10 MS, MEDIUM 20, HIGH 40). OFF IS THE RAWEST AND LOWEST DELAY.")))
        content.addView(card("PRESSURE AND TILT", BABY,
            stepper("PRESSURE CURVE", { if (abs(Cfg.gamma - 1f) < .01f) "RAW 1.0" else "%.1f".format(Cfg.gamma) }, { Cfg.gamma = ((Cfg.gamma - .1f) * 10).roundToInt().coerceAtLeast(3) / 10f }, { Cfg.gamma = ((Cfg.gamma + .1f) * 10).roundToInt().coerceAtMost(30) / 10f }),
            note("1.0 SENDS THE PEN'S PRESSURE AS-IS. BELOW 1 IS SOFTER, ABOVE 1 IS FIRMER."),
            stepper("CLICK THRESHOLD", { "${(Cfg.pressMin * 100).roundToInt()}%" }, { Cfg.pressMin = (((Cfg.pressMin * 100).roundToInt() - 2).coerceAtLeast(0)) / 100f }, { Cfg.pressMin = (((Cfg.pressMin * 100).roundToInt() + 2).coerceAtMost(40)) / 100f }),
            toggle("SEND PEN TILT", { Cfg.tiltOn }, { Cfg.tiltOn = it })))
        content.addView(card("HOVER", GREEN,
            stepper("HOVER RANGE", { "${Cfg.hoverRange}%" }, { Cfg.hoverRange = (Cfg.hoverRange - 10).coerceAtLeast(10) }, { Cfg.hoverRange = (Cfg.hoverRange + 10).coerceAtMost(100) }),
            stepper("HOVER LINGER", { "${Cfg.linger} MS" }, { Cfg.linger = (Cfg.linger - 50).coerceAtLeast(0) }, { Cfg.linger = (Cfg.linger + 50).coerceAtMost(1000) }),
            note("RANGE ONLY LIMITS HOW FAR THE PEN COUNTS: THE TABLET'S SENSOR SETS THE MAXIMUM. LINGER STOPS THE CURSOR FLICKERING AT THE EDGE OF RANGE.")))
        content.addView(card("ORIENTATION", GREEN,
            chooser("ROTATE", listOf("0°" to "0", "90°" to "1", "180°" to "2", "270°" to "3"), { Cfg.rotation.toString() }, { Cfg.rotation = it.toInt(); editor.invalidate(); editor.report() }),
            toggle("FLIP LEFT-RIGHT", { Cfg.flipX }, { Cfg.flipX = it }), toggle("FLIP UP-DOWN", { Cfg.flipY }, { Cfg.flipY = it })))
        content.addView(card("WHAT DRAWS", GREEN,
            chooser("IN THE TABLET AREA", listOf("PEN ONLY" to "pen", "PEN OR FINGER" to "finger"), { if (Cfg.fingerDraw) "finger" else "pen" }, { Cfg.fingerDraw = it == "finger" }),
            note("PEN ONLY: ONLY A STYLUS DRAWS (A FINGER CAN STILL USE YOUR BUTTONS). PEN OR FINGER: A FINGER DRAWS LIKE A PEN TOO, FOR DEVICES WITHOUT A STYLUS. IT STARTS ON IF THIS DEVICE REPORTS NO STYLUS.")))
        content.addView(card("PALM REJECTION", LILAC,
            toggle("IGNORE FINGERS AND PALMS WHILE THE PEN IS NEAR", { Cfg.palmReject }, { Cfg.palmReject = it }),
            note("ON: A FINGER OR PALM IS IGNORED WHILE THE PEN IS HOVERING OR WRITING, AND FOR A MOMENT AFTER, SO A RESTING PALM CAN'T DRAW OR PRESS YOUR BUTTONS. OFF: FINGERS ARE NEVER IGNORED. WITH NO PEN IN USE, A FINGER ALWAYS WORKS.")))
        content.addView(card("RESET", PINK, pill("RESET ALL TABLET SETTINGS", PAPER, icon = "retry") { confirm("RESET TABLET SETTINGS?", "AREA, ORIENTATION, PRESSURE, SMOOTHING, HOVER, FINGER DRAWING AND PALM REJECTION GO BACK TO DEFAULTS. YOUR PEN BUTTONS AND TABLET KEYS ARE KEPT.") { Cfg.resetTablet(); show(2) } }))
        every(1000) { editor.report() }
    }

    private fun buttons() {
        val list = vbox()
        fun rebuild() {
            list.removeAllViews()
            if (Cfg.keys.isEmpty()) list.addView(note("NO BUTTONS YET. ADD AS MANY OR AS FEW AS YOU LIKE."))
            Cfg.keys.toList().forEach { k ->
                list.addView(hbox(label(k.label, 12f), label(Actions.label(k.action) + if (k.hold) " (HOLD)" else "", 10.5f),
                    pill("", BABY, icon = "edit") { editKey(k) { rebuild() } }, pill("", PINK, icon = "x") { confirm("DELETE THIS BUTTON?", k.label) { Cfg.keys.remove(k); Cfg.saveKeys(); rebuild() } }, weights = false).apply {
                    (getChildAt(0).layoutParams as LinearLayout.LayoutParams).apply { weight = 1f; width = 0 }
                    (getChildAt(1).layoutParams as LinearLayout.LayoutParams).apply { weight = 1f; width = 0 }
                }, lp())
            }
        }
        rebuild()
        content.addView(card("TABLET BUTTONS", LILAC,
            note("BUTTONS SHOWN ON THE TABLET SCREEN, EACH DOING ANYTHING YOU MAP: SHORTCUTS, CLICKS, ERASER. THESE ARE THE ONLY BUTTONS THAT WORK WHILE THE TABLET IS LOCKED. A LEFT, RIGHT OR MIDDLE CLICK BUTTON MAKES THE PEN ACT AS THAT MOUSE BUTTON: WHILE YOU HOLD IT (TURN ON HOLD), OR FOR THE NEXT STROKE IF YOU JUST TAP IT."),
            list, hbox(pill("ADD BUTTON", GREEN, icon = "plus") { val k = Cfg.newKey(); editKey(k) { rebuild() } }, pill("ARRANGE ON TABLET", LILAC, icon = "keys") { Cfg.pending = "keys"; finish() })))
    }

    private fun editKey(k: ExpressKey, done: () -> Unit) {
        val name = input("NAME", k.label)
        var action = k.action
        val actionBtn = pill(Actions.label(action), BABY) {}
        actionBtn.setOnClickListener { pickAction(action) { a -> action = a; actionBtn.text = Actions.label(a).uppercase() } }
        var hold = k.hold
        val holdBtn = toggle("PRESS AND HOLD (KEY STAYS DOWN WHILE TOUCHED)", { hold }, { hold = it })
        val box = vbox().apply { setPadding(dp(16), dp(12), dp(16), dp(4)); addView(label("BUTTON NAME", 11f), lp(bottom = 4)); addView(name, lp()); addView(label("WHAT IT DOES", 11f), lp(bottom = 4)); addView(actionBtn, lp()); addView(holdBtn, lp()); background = shape(PAPER, dp(4)) }
        AlertDialog.Builder(this).setView(box).setPositiveButton("SAVE") { _, _ ->
            k.label = name.text.toString().trim().ifEmpty { "KEY" }.take(8).uppercase(); k.action = action; k.hold = hold; Cfg.saveKeys(); done()
        }.setNegativeButton("CANCEL") { _, _ -> done() }.setOnCancelListener { done() }.show()
    }

    /** Pick a ready-made action or type a shortcut like ctrl+shift+z. */
    private fun pickAction(current: String, pen: Boolean = false, onPick: (String) -> Unit) {
        val presets = Actions.presets.filter { pen || it.second !in Actions.PEN_ONLY }
        val names = (listOf("Record from my PC keyboard...") + presets.map { it.first }).toTypedArray()
        AlertDialog.Builder(this).setTitle("WHAT SHOULD IT DO?").setItems(names) { _, i ->
            if (i == 0) { recordFromPc(onPick); return@setItems }
            val a = presets[i - 1].second
            if (a != "custom") onPick(a)
            else {
                val e = input("E.G. CTRL+SHIFT+Z", current.removePrefix("keys:").removePrefix("key:"))
                AlertDialog.Builder(this).setTitle("TYPE A SHORTCUT").setMessage("USE + BETWEEN KEYS. SEVERAL SHORTCUTS IN A ROW: SEPARATE THEM WITH COMMAS, E.G. CTRL+C, CTRL+V. MODIFIERS: CTRL SHIFT ALT WIN. KEYS: A-Z 0-9 F1-F12 SPACE ENTER ESC TAB LEFT RIGHT UP DOWN")
                    .setView(e).setPositiveButton("OK") { _, _ ->
                        val parts = e.text.toString().lowercase().split(",").map { it.trim() }.filter { it.isNotEmpty() }
                        if (parts.isNotEmpty() && parts.all { Keys.parse(it) != null }) onPick(if (parts.size == 1) "key:${parts[0]}" else "keys:${parts.joinToString(",")}")
                        else Toast.makeText(this, "I DON'T UNDERSTAND THAT SHORTCUT", Toast.LENGTH_LONG).show()
                    }.setNegativeButton("CANCEL", null).show()
            }
        }.show()
    }

    /** Asks PixelPad Desk to listen for one shortcut on the PC's keyboard and send it back. */
    private fun recordFromPc(onPick: (String) -> Unit) {
        if (!Core.sender.connected()) { Toast.makeText(this, "CONNECT TO THE PC FIRST (CONNECTION PAGE)", Toast.LENGTH_LONG).show(); return }
        val box = vbox().apply { setPadding(dp(16), dp(14), dp(16), dp(8)); background = shape(PAPER, dp(4)); addView(label("PRESS THE SHORTCUT ON YOUR PC KEYBOARD NOW. ESC CANCELS.", 12f)) }
        val dlg = AlertDialog.Builder(this).setView(box).setNegativeButton("CANCEL") { _, _ -> Core.sender.cancelRecord() }.create()
        dlg.setOnDismissListener { Core.sender.recordListener = null; Core.sender.cancelRecord() }   // any way out of the dialog lets the PC keyboard go
        Core.sender.recordListener = { vk, mods, status ->
            runOnUiThread {
                dlg.dismiss()
                val t = Keys.text(mods, vk)
                when {
                    status == 3 -> Toast.makeText(this, "SHORTCUT RECORDING IS OFF IN PIXELPAD DESK (ITS SETTINGS)", Toast.LENGTH_LONG).show()
                    status != 1 -> Toast.makeText(this, "NOTHING RECORDED", Toast.LENGTH_SHORT).show()
                    t.isEmpty() || Keys.parse(t) == null || (vk != 0 && Keys.nameOf(vk) == null) -> Toast.makeText(this, "THAT KEY ISN'T SUPPORTED", Toast.LENGTH_LONG).show()
                    else -> onPick("key:$t")
                }
            }
        }
        dlg.show(); Core.sender.startRecord()
    }

    // ---------- pen buttons ----------
    private fun pen() {
        val list = vbox()
        fun rebuild() {
            list.removeAllViews()
            if (Cfg.penButtons.isEmpty()) list.addView(note("NO PEN BUTTONS YET. RECORD ONE BELOW."))
            Cfg.penButtons.toList().forEach { b ->
                list.addView(hbox(label(b.name, 12f), label(Actions.label(b.action) + if (b.hold) " (HOLD)" else "", 10.5f),
                    pill("", BABY, icon = "edit") { penButtonDialog(b) { rebuild() } },
                    pill("", PINK, icon = "x") { confirm("DELETE THIS PEN BUTTON?", b.name) { Cfg.penButtons.remove(b); Cfg.savePenButtons(); rebuild() } }, weights = false).apply {
                    (getChildAt(0).layoutParams as LinearLayout.LayoutParams).apply { weight = 1f; width = 0 }
                    (getChildAt(1).layoutParams as LinearLayout.LayoutParams).apply { weight = 1f; width = 0 }
                }, lp())
            }
        }
        rebuild()
        content.addView(card("PEN BUTTONS", PEACH,
            note("RECORD THE BUTTONS ON YOUR PEN: TAP RECORD, PRESS THE BUTTON WHILE HOVERING OVER THE BOX, NAME IT AND CHOOSE WHAT IT DOES. ADD AS MANY OR AS FEW AS YOUR PEN HAS."),
            list, hbox(pill("RECORD A PEN BUTTON", GREEN, icon = "plus") { penButtonDialog(null) { rebuild() } }, pill("RESET TO DEFAULTS", PAPER, icon = "retry") { confirm("RESET PEN BUTTONS?", "YOUR RECORDED PEN BUTTONS WILL BE REPLACED BY THE TWO DEFAULTS.") { Cfg.resetPenButtons(); rebuild() } })))
        content.addView(card("WHAT THEY CAN DO", BABY,
            note("ANY SHORTCUT (TYPED OR RECORDED FROM YOUR PC KEYBOARD), A CLICK (IN TABLET MODE THE PEN ACTS AS THAT MOUSE BUTTON WHILE THE PEN BUTTON IS HELD), THE ERASER, DRAWING A GESTURE WHILE HELD, PASSING THE BUTTON TO THE PC AS A PEN BUTTON, OR SWITCHING SCREEN. TURN ON 'HOLD' TO KEEP A SHORTCUT PRESSED WHILE THE BUTTON IS HELD.")))
    }

    private fun penButtonDialog(existing: PenButton?, done: () -> Unit) {
        var bit = existing?.bit ?: 0
        val detected = label(if (bit != 0) "BUTTON CODE $bit" else "NO BUTTON DETECTED YET", 11f, HOT)
        val listener = PenListener(this) { b -> bit = b; detected.text = "DETECTED: BUTTON CODE $b" }
        val name = input("NAME, E.G. BARREL", existing?.name ?: "")
        var action = existing?.action ?: "mouse:right"
        val actionBtn = pill(Actions.label(action), BABY) {}
        actionBtn.setOnClickListener { pickAction(action, pen = true) { a -> action = a; actionBtn.text = Actions.label(a).uppercase() } }
        var hold = existing?.hold ?: false
        val box = vbox().apply {
            setPadding(dp(16), dp(12), dp(16), dp(4)); background = shape(PAPER, dp(4))
            addView(label(if (existing == null) "HOVER THE PEN OVER THE BOX BELOW AND PRESS THE BUTTON" else "PRESS A BUTTON BELOW TO RE-RECORD IT", 10.5f), lp())
            addView(listener, lp(h = dp(100))); addView(detected, lp()); addView(name, lp())
            addView(label("WHAT IT DOES", 11f), lp(bottom = 4)); addView(actionBtn, lp())
            addView(toggle("HOLD: ACTIVE ONLY WHILE THE BUTTON IS HELD", { hold }, { hold = it }), lp())
        }
        val dlg = AlertDialog.Builder(this).setView(box).setPositiveButton("SAVE", null).setNegativeButton("CANCEL", null).create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (bit == 0) detected.text = "PRESS THE PEN BUTTON FIRST"
                else if (name.text.isBlank()) detected.text = "GIVE IT A NAME"
                else {
                    val n = name.text.toString().trim().take(16)
                    if (existing == null) Cfg.penButtons.add(PenButton(n, bit, action, hold)) else { existing.name = n; existing.bit = bit; existing.action = action; existing.hold = hold }
                    Cfg.savePenButtons(); dlg.dismiss(); done()
                }
            }
        }
        dlg.show()
    }


    private fun gestures() {
        val list = vbox()
        fun rebuild() {
            list.removeAllViews()
            if (Cfg.gestures.isEmpty()) list.addView(note("NO GESTURES YET. RECORD ONE BELOW."))
            Cfg.gestures.toList().forEach { g ->
                list.addView(hbox(label(g.name, 12f), label(Actions.label(g.action), 10.5f),
                    pill("ACTION", BABY, icon = "keys") { pickAction(g.action) { a -> g.action = a; Cfg.saveGestures(); rebuild() } },
                    pill("", PINK, icon = "x") { confirm("DELETE THIS GESTURE?", "${g.name}: A RECORDED GESTURE CANNOT BE GOT BACK.") { Cfg.gestures.remove(g); Cfg.saveGestures(); rebuild() } }, weights = false).apply {
                    (getChildAt(0).layoutParams as LinearLayout.LayoutParams).apply { weight = 1f; width = 0 }
                    (getChildAt(1).layoutParams as LinearLayout.LayoutParams).apply { weight = 1f; width = 0 }
                }, lp())
            }
        }
        rebuild()
        content.addView(card("PEN GESTURES", PEACH,
            note("RECORD A SHAPE, THEN MAP IT TO ANYTHING. IN TABLET MODE, HOLD THE PEN BUTTON YOU SET TO 'DRAW A GESTURE WHILE HELD' (PEN PAGE), DRAW THE SHAPE, AND LET GO. DIRECTION MATTERS: UP AND DOWN ARE DIFFERENT."),
            list, hbox(pill("RECORD A GESTURE", GREEN, icon = "plus") { record { rebuild() } }),
            label(if (Cfg.penButtons.any { it.action == "gesture" }) "A PEN BUTTON IS SET TO DRAW GESTURES" else "NO PEN BUTTON IS SET TO DRAW GESTURES YET (PEN PAGE)", 10.5f, if (Cfg.penButtons.any { it.action == "gesture" }) INK else WARN)))
    }

    private fun record(done: () -> Unit) {
        val pad = GesturePad(this)
        val name = input("NAME, E.G. SWIPE UP", "")
        var action = "key:ctrl+z"
        val actionBtn = pill(Actions.label(action), BABY) {}
        actionBtn.setOnClickListener { pickAction(action) { a -> action = a; actionBtn.text = Actions.label(a).uppercase() } }
        val msg = label("DRAW THE SHAPE BELOW WITH THE PEN OR A FINGER", 10.5f)
        val box = vbox().apply {
            setPadding(dp(16), dp(12), dp(16), dp(4)); background = shape(PAPER, dp(4))
            addView(msg, lp()); addView(pad, lp(h = dp(220))); addView(hbox(pill("CLEAR", PINK, icon = "retry") { pad.clear() }), lp())
            addView(name, lp()); addView(label("WHAT IT DOES", 11f), lp(bottom = 4)); addView(actionBtn, lp())
        }
        val dlg = AlertDialog.Builder(this).setView(box).setPositiveButton("SAVE", null).setNegativeButton("CANCEL", null).create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val n = Recog.normalise(pad.raw())
                if (n == null) msg.text = "DRAW A LONGER SHAPE FIRST"
                else if (name.text.isBlank()) msg.text = "GIVE IT A NAME"
                else { Cfg.gestures.add(Gesture(name.text.toString().trim().take(16), n, action)); Cfg.saveGestures(); dlg.dismiss(); done() }
            }
        }
        dlg.show()
    }

    private fun controller() {
        content.addView(card("TEMPLATE", GREEN,
            chooser("LAYOUT", listOf("PLAYSTATION" to "ps", "XBOX" to "xbox", "SWITCH (FULL)" to "switch", "SWITCH (HALF: ONE JOY-CON)" to "joycon", "FIGHTING PAD" to "fight"), { Cfg.tpl }, { Cfg.tpl = it }),
            toggle("FIGHTING PAD: JOYSTICK (OFF = ARROW BUTTONS)", { Cfg.fightStick }, { Cfg.fightStick = it }),
            note("ALL TEMPLATES ACT AS A PLAYSTATION 4 (DUALSHOCK 4) CONTROLLER ON THE PC, SO STEAM KEEPS ITS NATIVE SETTINGS. IT SHOWS UP AS A PS4 CONTROLLER, NOT A PS5 DUALSENSE: THE DRIVER THAT MAKES THE VIRTUAL CONTROLLER (VIGEMBUS) CAN ONLY MAKE A DUALSHOCK 4 OR AN XBOX 360 PAD, SO DUALSENSE-ONLY FEATURES LIKE ADAPTIVE TRIGGERS AREN'T AVAILABLE. THE XBOX TEMPLATE JUST USES XBOX NAMES AND POSITIONS.")))
        content.addView(profilesCard())
        content.addView(card("VIBRATION", PEACH,
            toggle("VIBRATE WHEN THE GAME RUMBLES", { Cfg.rumble }, { Cfg.rumble = it; if (!it) Haptics.stop() }),
            chooser("STRENGTH", listOf("LOW" to "0", "MEDIUM" to "1", "HIGH" to "2"), { Cfg.rumbleLevel.toString() }, { Cfg.rumbleLevel = it.toInt() }),
            pill("TEST THE VIBRATION", PAPER, icon = "check") { Haptics.test(this) },
            note("WHEN A GAME ON THE PC RUMBLES THE CONTROLLER (EVERY TEMPLATE), THIS DEVICE VIBRATES. IT NEEDS PIXELPAD DESK 1.4.0 OR NEWER ON THE PC. A TABLET WITHOUT A VIBRATION MOTOR CAN'T DO IT.")))
        content.addView(card("CUSTOMISE", LILAC,
            note("MOVE AND RESIZE ANY CONTROL ON THE CONTROLLER SCREEN ITSELF. EACH TEMPLATE REMEMBERS ITS OWN LAYOUT."),
            hbox(pill("EDIT LAYOUT", LILAC, icon = "edit") { Cfg.pending = "controller"; finish() },
                pill("RESET THIS LAYOUT", PINK, icon = "retry") {
                    confirm("RESET THIS LAYOUT?", "THE ${Cfg.tpl.uppercase()} LAYOUT GOES BACK TO ITS DEFAULT POSITIONS AND SIZES.") {
                        Cfg.prefs.edit().remove(if (Cfg.tpl == "ps") "layout" else "layout_${Cfg.tpl}").apply()
                        Toast.makeText(this, "LAYOUT RESET", Toast.LENGTH_SHORT).show()
                    }
                })))
    }

    /** The controller layouts kept on the PC (any connected device can use them), and whether a game's profile is followed when the game starts. */
    private fun profilesCard(): View {
        val box = vbox()
        fun fill(list: List<Pair<Int, String>>) {
            box.removeAllViews()
            if (list.isEmpty()) box.addView(note("NO PROFILES YET. ON THE CONTROLLER SCREEN CHOOSE PROFILES > SAVE THIS LAYOUT AS A NEW PROFILE."), lp())
            list.forEach { (id, name) ->
                box.addView(hbox(label(name, 12f), pill("", PINK, icon = "x") { confirm("DELETE THIS PROFILE?", name) { Core.profiles.delete(id) { ok -> if (!ok) Toast.makeText(this, "COULDN'T DELETE IT: IS THE PC CONNECTED?", Toast.LENGTH_LONG).show(); refresh() } } }, weights = false)
                    .apply { (getChildAt(0).layoutParams as LinearLayout.LayoutParams).apply { weight = 1f; width = 0 } }, lp())
            }
        }
        refreshList = {
            fill(ProfileCache.list())
            Core.profiles.list { r -> if (r != null) { ProfileCache.saveList(r); fill(r) } }
        }
        refreshList()
        return card("PROFILES KEPT ON THE PC", LILAC,
            toggle("SWITCH TO A GAME'S PROFILE WHEN IT STARTS", { Cfg.autoProfile }, { Cfg.autoProfile = it }),
            note("A PROFILE IS A CONTROLLER LAYOUT. IT IS SAVED ON THE PC, SO EVERY CONNECTED DEVICE CAN USE IT. WHILE A PROFILE IS ON SCREEN, EDIT THE LAYOUT AND TAP THE TICK: YOU CAN UPDATE THE PROFILE FOR ALL DEVICES. IN PIXELPAD DESK (GAMES) YOU LINK STEAM GAMES TO PROFILES; WHEN A LINKED GAME STARTS, THIS DEVICE GOES TO THE CONTROLLER SCREEN AND LOADS ITS PROFILE ONCE. NOTHING IS LOCKED: PICK ANOTHER LAYOUT AT ANY TIME, AND NOTHING CHANGES WHEN THE GAME ENDS."),
            box, pill("REFRESH THE LIST", BABY, icon = "retry") { refreshList() })
    }
    private var refreshList: () -> Unit = {}
    private fun refresh() = refreshList()

    private val speeds = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 2.5f, 3f, 4f)

    private fun nextSpeed(v: Float, d: Int): Float {
        val i = speeds.indexOfFirst { it >= v - 0.001f }.let { if (it < 0) speeds.size - 1 else it }
        return speeds[(i + d).coerceIn(0, speeds.lastIndex)]
    }

    private fun speedName(v: Float) = when { v < 0.9f -> "%.2fX SLOW".format(v); v < 1.05f -> "1.0X RAW"; v < 1.6f -> "%.2fX FAST".format(v); else -> "%.1fX TURBO".format(v) }

    private fun trackpad() {
        content.addView(card("SCREEN ORIENTATION", BABY,
            chooser("TRACKPAD AND PRESENTER SCREENS", listOf("LANDSCAPE" to "landscape", "PORTRAIT" to "portrait", "AUTO-ROTATE" to "auto"), { Cfg.orient }, { Cfg.orient = it }),
            note("THE TABLET AND CONTROLLER SCREENS ALWAYS USE LANDSCAPE. ON THE TRACKPAD AND PRESENTER SCREENS, THE ROTATE BUTTON AT THE BOTTOM RIGHT FLIPS BETWEEN LANDSCAPE AND PORTRAIT.")))
        content.addView(card("POINTER SPEED", LILAC,
            stepper("POINTER SPEED", { speedName(Cfg.trackSpeed) }, { Cfg.trackSpeed = nextSpeed(Cfg.trackSpeed, -1) }, { Cfg.trackSpeed = nextSpeed(Cfg.trackSpeed, 1) }),
            note("1.0X SENDS YOUR MOVEMENT RAW, SO WINDOWS' OWN POINTER SPEED AND ACCELERATION APPLY. RAISE OR LOWER IT HERE TO MOVE FASTER OR SLOWER THAN THAT."),
            stepper("GESTURE DISTANCE", { speedName(Cfg.scrollSpeed) }, { Cfg.scrollSpeed = nextSpeed(Cfg.scrollSpeed, -1) }, { Cfg.scrollSpeed = nextSpeed(Cfg.scrollSpeed, 1) }),
            note("HOW FAR TWO-, THREE- AND FOUR-FINGER GESTURES TRAVEL ON THE PC.")))
        content.addView(card("SWIPE DIRECTION", GREEN,
            chooser("AXIS LOCK", listOf("OFF" to "0", "NORMAL" to "1", "STRICT" to "2"), { Cfg.axisLock.toString() }, { Cfg.axisLock = it.toInt() }),
            note("STOPS A MOSTLY-UP SWIPE DRIFTING LEFT OR RIGHT, AND A MOSTLY-SIDEWAYS ONE DRIFTING UP OR DOWN. STRICT LOCKS TO WHICHEVER WAY YOU MOVE MOST."),
            toggle("NATURAL DIRECTION", { Cfg.naturalScroll }, { Cfg.naturalScroll = it })))
        content.addView(card("GESTURES", PEACH,
            note("ONE FINGER OR THE PEN MOVES THE CURSOR. TWO OR MORE FINGERS ARE SENT TO WINDOWS AS REAL TOUCH INPUT, SO SCROLLING, PINCHING AND THREE- AND FOUR-FINGER SWIPES ARE WHATEVER YOUR WINDOWS TOUCH GESTURE SETTINGS SAY (WINDOWS SETTINGS > BLUETOOTH & DEVICES > TOUCH). PIXELPAD ADDS NO SHORTCUTS OF ITS OWN.")))
        content.addView(card("PIN THE APP", BABY,
            toggle("BLOCK ANDROID'S GESTURES BY PINNING", { Cfg.lockNav }, { Cfg.lockNav = it }),
            note("PINNING STOPS ANDROID'S OWN SWIPES AND THREE- AND FOUR-FINGER GESTURES FROM TAKING YOU OUT OF THE APP. LEAVE WITH THE EXIT BUTTON AT THE TOP LEFT (TAP IT TWICE). ANDROID ITSELF LOCKS THE SCREEN WHEN AN APP UNPINS, AND ONLY ANDROID CAN TURN THAT OFF: IN ANDROID'S SETTINGS SEARCH FOR SCREEN PINNING (SOME DEVICES CALL IT PIN WINDOWS) AND TURN OFF LOCK DEVICE WHEN UNPINNING.")))
        content.addView(card("PEN DRAGGING", LILAC,
            chooser("WHEN YOU PRESS THE PEN DOWN AND DRAG", listOf("DRAGS / SELECTS" to "select", "ONLY MOVES THE CURSOR" to "move"), { Cfg.penDrag }, { Cfg.penDrag = it }),
            note("DRAGS / SELECTS: LIKE HOLDING A MOUSE BUTTON, WITH NO DELAY: THE BUTTON GOES DOWN THE MOMENT THE PEN TOUCHES. A QUICK TAP IS A CLICK, HOLDING STILL IS A RIGHT CLICK, HOVERING MOVES THE CURSOR. ONLY MOVES THE CURSOR: TAP, THEN TOUCH AND DRAG TO SELECT. FINGERS ALWAYS WORK LIKE A LAPTOP TRACKPAD, WITH TAP-THEN-DRAG TO SELECT.")))
        content.addView(card("PRESENTER", PEACH,
            note("A CLICKER: PREV AND NEXT, AND A POINTER AREA. VOLUME DOWN = NEXT, VOLUME UP = PREVIOUS. WORKS WITH POWERPOINT, KEYNOTE, GOOGLE SLIDES AND PDF VIEWERS.")))
    }

    private fun cursor() {
        fun changed() { Cfg.ringTouched = true; Core.sender.syncRing() }
        val colours = listOf("PINK" to "0", "BLUE" to "1", "LILAC" to "2", "GREEN" to "3", "WHITE" to "4", "RED" to "5")
        content.addView(card("PC CURSOR RING", PINK,
            note("A RING THAT FOLLOWS THE PC CURSOR, WHICH IS WHERE THE PEN IS. IT SHOWS ONLY WHILE THE PEN IS IN RANGE ON THE TABLET SCREEN, AND NEVER AT OTHER TIMES. YOU CAN ALSO CHANGE IT IN PIXELPAD DESK."),
            toggle("SHOW THE RING", { Cfg.ringOn }, { Cfg.ringOn = it; changed() }),
            stepper("SIZE", { "${Cfg.ringSize} PX" }, { Cfg.ringSize = (Cfg.ringSize - 10).coerceAtLeast(40); changed() }, { Cfg.ringSize = (Cfg.ringSize + 10).coerceAtMost(300); changed() }),
            stepper("THICKNESS", { "${Cfg.ringThick}" }, { Cfg.ringThick = (Cfg.ringThick - 1).coerceAtLeast(1); changed() }, { Cfg.ringThick = (Cfg.ringThick + 1).coerceAtMost(8); changed() }),
            chooser("SHAPE", listOf("RING" to "0", "CROSSHAIR" to "1", "DOT" to "2"), { Cfg.ringStyle.toString() }, { Cfg.ringStyle = it.toInt(); changed() }),
            chooser("COLOUR", colours, { Cfg.ringColor.toString() }, { Cfg.ringColor = it.toInt(); changed() })))
    }

    /** Version, a check against GitHub, and the update itself with its download progress. */
    private fun updateCard(): View {
        val have = Updater.version(this)
        val status = label("", 12f)
        val bar = Bar(this).apply { visibility = View.GONE }
        var found: Updater.Release? = null
        val btn = pill("CHECK FOR UPDATES", GREEN, icon = "retry") {}
        fun idle(msg: String) {
            status.text = msg.uppercase(); bar.visibility = View.GONE; btn.isEnabled = true; btn.alpha = 1f
            btn.text = (found?.let { "UPDATE TO ${it.version}" } ?: "CHECK FOR UPDATES").uppercase()
        }
        fun busy(msg: String, pct: Int = -1) { status.text = msg.uppercase(); btn.isEnabled = false; btn.alpha = .5f; bar.visibility = if (pct >= 0) View.VISIBLE else View.GONE; bar.pct = pct }
        fun install() {
            val r = found ?: return
            if (!Updater.canInstall(this)) {   // only when Android has not been allowed yet do we leave for its page
                try { stopLockTask() } catch (e: Exception) {}   // a pinned app can't open Android's own screens
                Cfg.updateResume = true; afterAllow = { install() }
                idle("ANDROID WANTS YOUR OK FIRST: TURN ON ALLOW FROM THIS SOURCE FOR PIXELPAD, THEN COME BACK AND THE UPDATE STARTS"); Updater.askPermission(this); return
            }
            Cfg.updateResume = false
            Updater.onResult = { msg -> idle(msg) }
            busy("DOWNLOADING 0%", 0)
            Updater.install(this, r, { got, total ->
                if (total <= 0 || got < total) busy(if (total > 0) "DOWNLOADING ${(got * 100 / total).toInt()}%% (%.1f / %.1f MB)".format(got / 1048576f, total / 1048576f) else "DOWNLOADING %.1f MB".format(got / 1048576f), if (total > 0) (got * 100 / total).toInt() else 0)
                else {
                    try { stopLockTask() } catch (e: Exception) {}   // downloaded: only now unpin, so Android can show its install screen
                    busy("DOWNLOADED. ANDROID'S INSTALL SCREEN OPENS NEXT: TAP INSTALL. IF THE SCREEN LOCKED, UNLOCK IT.", 100)
                }
            }, { msg -> idle(msg) })
        }
        fun check() {
            busy("CHECKING GITHUB...")
            Thread {
                val r = Updater.latest()
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    found = r?.takeIf { Updater.newer(it.version, have) }
                    when {
                        r == null -> idle("COULDN'T REACH GITHUB. CHECK THE INTERNET AND TRY AGAIN")
                        found == null -> idle("YOU HAVE THE LATEST VERSION")
                        else -> { idle("VERSION ${r.version} IS OUT"); if (autoUpdate) { autoUpdate = false; install() } }
                    }
                }
            }.apply { isDaemon = true }.start()
        }
        btn.setOnClickListener { if (found != null) install() else check() }
        idle("YOU HAVE VERSION $have"); check()
        return card("UPDATES", GREEN, label("PIXELPAD $have", 12f), status, bar.also { it.layoutParams = lp(h = dp(16)) }, btn,
            note("PIXELPAD ASKS ABOUT A NEW VERSION ONCE WHEN YOU OPEN THE APP. UPDATING DOWNLOADS THE APK FROM THE LATEST GITHUB RELEASE AND ANDROID ASKS YOU TO CONFIRM THE INSTALL. WHEN THE DOWNLOAD IS DONE THE APP UNPINS ITSELF (ANDROID CAN'T SHOW ITS OWN SCREENS OVER A PINNED APP), AND ANDROID MAY LOCK THE SCREEN WHEN IT DOES: UNLOCK IT AND TAP INSTALL. THE APP RESTARTS WHEN IT IS DONE."))
    }

    /** A thin retro progress bar. */
    private class Bar(c: Context) : View(c) {
        var pct = 0; set(v) { field = v; invalidate() }
        private val p = Paint()
        override fun onDraw(cv: Canvas) {
            val d = resources.displayMetrics.density; val w = width.toFloat(); val h = height.toFloat()
            p.style = Paint.Style.FILL; p.color = PAPER; cv.drawRect(0f, 0f, w, h, p)
            p.color = HOT; cv.drawRect(0f, 0f, w * pct.coerceIn(0, 100) / 100f, h, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 3 * d; p.color = INK; cv.drawRect(1.5f * d, 1.5f * d, w - 1.5f * d, h - 1.5f * d, p)
        }
    }

    private fun about() {
        content.addView(updateCard())
        content.addView(card("PIXELPAD", BABY,
            note("USE A TABLET WITH A PEN AS A TRACKPAD, A DRAWING TABLET, A CONTROLLER AND A PRESENTER FOR YOUR PC."),
            note("LOCK: ON ANY SCREEN, DOUBLE-TAP THE LOCK ICON AT THE TOP LEFT. THE SCREEN GOES DARK AND MINIMAL (STILL IN THE SAME COLOURS), THE TOP BAR STOPS RESPONDING, AND YOU KEEP WHAT YOU NEED: THE PEN, YOUR TABLET BUTTONS, THE CONTROLLER OR THE PREV / NEXT BUTTONS. DOUBLE-TAP AGAIN TO UNLOCK.")))
        content.addView(card("THEME COLOUR", BABY,
            chooser("THIS DEVICE'S COLOUR", Themes.list.mapIndexed { i, t -> t.name to i.toString() },
                { Cfg.theme }, { Cfg.theme = it; Core.applyColour(); applyTheme(); show(8) }, fills = Themes.list.mapIndexed { i, t -> i.toString() to t.bottom }.toMap()),
            note("THE COLOUR YOU PICK BECOMES THE THEME OF THE APP, AND IS THIS DEVICE'S COLOUR. PIXELPAD DESK SHOWS IT NEXT TO THIS DEVICE'S PLAYER NUMBER; THE PC NEVER CHOOSES IT.")))
        content.addView(card("RESET", PINK, pill("RESET EVERYTHING", PAPER, icon = "retry") {
            AlertDialog.Builder(this).setTitle("RESET ALL SETTINGS?").setMessage("THIS CLEARS EVERY SETTING, BUTTON, GESTURE AND LAYOUT.")
                .setPositiveButton("RESET") { _, _ -> Cfg.prefs.edit().clear().apply(); Cfg.pcs.clear(); Cfg.keys.clear(); Cfg.seedKeys(); Cfg.gestures.clear(); Cfg.penButtons.clear(); Cfg.resetPenButtons(); Core.applyConnection(); show(8) }
                .setNegativeButton("CANCEL", null).show()
        }))
    }

    // ---------- custom views ----------
    /** The tablet surface with a draggable, resizable box showing where the pen works. */
    private class AreaEditor(ctx: Context, val onInfo: (String) -> Unit) : View(ctx) {
        private val dp = resources.displayMetrics.density
        private val p = Paint().apply { isAntiAlias = false; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) }
        private var mode = 0 // 0 none, 1 move, 2 resize
        private var gx = 0f; private var gy = 0f
        private val dm = resources.displayMetrics
        private val padW = max(dm.widthPixels, dm.heightPixels).toFloat()
        private val padH = min(dm.widthPixels, dm.heightPixels) - 52 * dp

        private fun surface(): RectF {
            val ar = padW / padH; var w = width - 8 * dp; var h = w / ar
            if (h > height - 8 * dp) { h = height - 8 * dp; w = h * ar }
            return RectF((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
        }
        private fun box(s: RectF) = RectF(s.left + Cfg.ax * s.width(), s.top + Cfg.ay * s.height(), s.left + (Cfg.ax + Cfg.aw) * s.width(), s.top + (Cfg.ay + Cfg.ah) * s.height())

        /** The PC screen's size: live when connected, else the last one seen, else 16:9 until the first connection. */
        private fun pcSize(): Pair<Int, Int> = when {
            Core.sender.screenW > 0 && Core.sender.screenH > 0 -> Core.sender.screenW to Core.sender.screenH
            Cfg.pcW > 0 && Cfg.pcH > 0 -> Cfg.pcW to Cfg.pcH
            else -> 1920 to 1080
        }

        /** Height as a fraction of the tablet, for a width fraction, so the box matches the PC screen's shape. */
        private fun heightFor(aw: Float): Float {
            val (sw, sh) = pcSize()
            val ratio = if (Cfg.rotation % 2 == 0) sw.toFloat() / sh else sh.toFloat() / sw // width / height of the box in tablet pixels
            return aw * padW / (padH * ratio)
        }

        fun matchScreen() {
            var w = 1f; var h = heightFor(w); val (sw, sh) = pcSize()
            if (h > 1f) { h = 1f; w = min(1f, (h * (padH * (if (Cfg.rotation % 2 == 0) sw.toFloat() / max(1, sh) else sh.toFloat() / max(1, sw))) / padW)) }
            Cfg.aw = w; Cfg.ah = h.coerceAtMost(1f); Cfg.ax = (1f - Cfg.aw) / 2; Cfg.ay = (1f - Cfg.ah) / 2; invalidate(); report()
        }

        fun report() {
            val (sw, sh) = pcSize()
            val known = Core.sender.screenW > 0 || Cfg.pcW > 0
            onInfo("ACTIVE AREA ${(Cfg.aw * 100).roundToInt()}% x ${(Cfg.ah * 100).roundToInt()}% OF THE TABLET → PC SCREEN ${sw}x$sh" + if (known) "" else " (ASSUMED UNTIL YOU CONNECT)")
            invalidate()
        }

        override fun onDraw(c: Canvas) {
            val s = surface()
            p.style = Paint.Style.FILL; p.color = BABY; c.drawRect(s, p)
            p.color = 0x70FFFFFF; var x = s.left; while (x < s.right) { c.drawRect(x, s.top, x + dp, s.bottom, p); x += 24 * dp }
            var y = s.top; while (y < s.bottom) { c.drawRect(s.left, y, s.right, y + dp, p); y += 24 * dp }
            p.style = Paint.Style.STROKE; p.strokeWidth = 3 * dp; p.color = INK; c.drawRect(s, p)
            val b = box(s)
            p.style = Paint.Style.FILL; p.color = 0x55E84FB0; c.drawRect(b, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 4 * dp; p.color = HOT; c.drawRect(b, p)
            p.style = Paint.Style.FILL; p.color = HOT; c.drawRect(b.right - 14 * dp, b.bottom - 14 * dp, b.right, b.bottom, p)
            p.color = WARN; p.textSize = 12 * dp; p.textAlign = Paint.Align.CENTER; c.drawText("PEN WORKS HERE", b.centerX(), b.centerY() + 4 * dp, p)
            p.color = INK; p.textSize = 10 * dp; c.drawText("TABLET SURFACE", s.centerX(), s.top + 14 * dp, p)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            val s = surface(); val b = box(s)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (!s.contains(e.x, e.y)) { mode = 0; return false }   // only touches on the tablet surface count (and let the page scroll)
                    parent.requestDisallowInterceptTouchEvent(true)
                    mode = if (abs(e.x - b.right) < 28 * dp && abs(e.y - b.bottom) < 28 * dp) 2 else if (b.contains(e.x, e.y)) 1 else 0
                    gx = e.x - b.left; gy = e.y - b.top
                }
                MotionEvent.ACTION_MOVE -> if (mode == 1) {
                    Cfg.ax = ((e.x - gx - s.left) / s.width()).coerceIn(0f, 1f - Cfg.aw); Cfg.ay = ((e.y - gy - s.top) / s.height()).coerceIn(0f, 1f - Cfg.ah)
                } else if (mode == 2) {
                    var w = ((e.x - s.left) / s.width() - Cfg.ax).coerceIn(0.1f, max(0.1f, 1f - Cfg.ax))
                    var h = ((e.y - s.top) / s.height() - Cfg.ay).coerceIn(0.1f, max(0.1f, 1f - Cfg.ay))
                    if (Cfg.keepShape) {
                        h = heightFor(w)
                        if (h > 1f - Cfg.ay) { h = 1f - Cfg.ay; w = min(w, w * h / heightFor(w)) }
                    }
                    Cfg.aw = w.coerceIn(0.1f, 1f); Cfg.ah = h.coerceIn(0.1f, 1f)
                    Cfg.ax = Cfg.ax.coerceAtMost(1f - Cfg.aw); Cfg.ay = Cfg.ay.coerceAtMost(1f - Cfg.ah)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { mode = 0; parent.requestDisallowInterceptTouchEvent(false) }
            }
            if (mode != 0) report()
            return true
        }
    }

    /** Hover the pen over this box and press a pen button: it reports the button's code. */
    private class PenListener(ctx: Context, val onBit: (Int) -> Unit) : View(ctx) {
        private val dp = resources.displayMetrics.density
        private val p = Paint().apply { isAntiAlias = false; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) }
        private var last = 0
        private var seen = ""

        private fun scan(e: MotionEvent) {
            val bs = e.buttonState and 1.inv()            // the pen tip counts as BUTTON_PRIMARY: not a button
            val fresh = bs and last.inv(); last = bs
            val pressed = if (e.actionMasked == MotionEvent.ACTION_BUTTON_PRESS) e.actionButton and 1.inv() else 0
            val bit = if (pressed != 0) pressed else if (fresh != 0) fresh and -fresh else 0
            if (bit != 0) { seen = "BUTTON $bit"; onBit(bit); invalidate() }
        }

        override fun onGenericMotionEvent(e: MotionEvent): Boolean { scan(e); return true }
        override fun onTouchEvent(e: MotionEvent): Boolean { scan(e); return true }
        override fun onDraw(c: Canvas) {
            p.style = Paint.Style.FILL; p.color = BABY; c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 3 * dp; p.color = INK; c.drawRect(1.5f * dp, 1.5f * dp, width - 1.5f * dp, height - 1.5f * dp, p)
            p.style = Paint.Style.FILL; p.textAlign = Paint.Align.CENTER; p.textSize = 12 * dp; p.color = if (seen.isEmpty()) INK else HOT
            c.drawText(if (seen.isEmpty()) "PRESS A PEN BUTTON HERE" else "GOT IT: $seen", width / 2f, height / 2f + 4 * dp, p)
        }
    }

    /** A pad to draw a gesture on. Pen or finger. */
    private class GesturePad(ctx: Context) : View(ctx) {
        private val dp = resources.displayMetrics.density
        private val pts = ArrayList<Float>()
        private val path = Path()
        private val p = Paint().apply { isAntiAlias = false }
        fun raw() = pts.toFloatArray()
        fun clear() { pts.clear(); path.reset(); invalidate() }
        override fun onDraw(c: Canvas) {
            p.style = Paint.Style.FILL; p.color = BABY; c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 3 * dp; p.color = INK; c.drawRect(1.5f * dp, 1.5f * dp, width - 1.5f * dp, height - 1.5f * dp, p)
            p.strokeWidth = 6 * dp; p.color = HOT; c.drawPath(path, p)
        }
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { parent.requestDisallowInterceptTouchEvent(true); clear(); path.moveTo(e.x, e.y); pts.add(e.x); pts.add(e.y) }
                MotionEvent.ACTION_MOVE -> {
                    for (h in 0 until e.historySize) { pts.add(e.getHistoricalX(h)); pts.add(e.getHistoricalY(h)) }
                    path.lineTo(e.x, e.y); pts.add(e.x); pts.add(e.y)
                }
            }
            invalidate(); return true
        }
    }
}
