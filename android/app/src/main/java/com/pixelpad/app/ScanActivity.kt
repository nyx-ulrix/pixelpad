package com.pixelpad.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.ResultPoint
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.BarcodeView
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import kotlin.math.min

/**
 * The connect screen: a pixel-style window with the camera as a little screen inside a thick bezel.
 * Shown when the app starts and no PC answers; scan the QR code that PixelPad Desk shows.
 * Result: OK = connected, FIRST_USER = "type the address instead", CANCELED = skipped.
 */
class ScanActivity : Activity() {
    private val INK = 0xFF2F6FE0.toInt(); private val PAPER = 0xFFF7FBFF.toInt(); private val LILAC = 0xFFD9C8FF.toInt()
    private val PINK = 0xFFFFB8E6.toInt(); private val HOT get() = Themes.current().accent; private val GREEN = 0xFFB8E986.toInt()
    private val BABY = 0xFFBFE3FA.toInt(); private val SKY = 0xFFC9EEFF.toInt(); private val BLUSH = 0xFFFFC9EA.toInt(); private val WARN = 0xFFB0206E.toInt()
    private val mono = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var camera: BarcodeView
    private lateinit var status: TextView
    private lateinit var blocks: Blocks
    private var waiting = false
    private var cameraAllowed = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun shape(fill: Int, radius: Int, stroke: Int = 3) = GradientDrawable().apply { setColor(fill); cornerRadius = radius.toFloat(); setStroke(dp(stroke), INK) }

    private fun label(text: String, size: Float = 12f, color: Int = INK) = IconText(this).apply {
        this.text = text.uppercase(); typeface = mono; setTextColor(color); textSize = size; letterSpacing = 0.05f
    }

    private fun pill(text: String, fill: Int, icon: String? = null, onClick: () -> Unit) = label(text, 13f).apply {
        gravity = Gravity.CENTER; setPadding(dp(14), dp(10), dp(14), dp(10)); background = shape(fill, dp(26)); maxLines = 1
        if (icon != null) { gravity = Gravity.START or Gravity.CENTER_VERTICAL; centreGroup = true; compoundDrawablePadding = if (text.isEmpty()) 0 else dp(8); setCompoundDrawablesWithIntrinsicBounds(IconDrawable(icon, INK, dp(26)), null, null, null) }
        setOnClickListener { onClick() }
    }

    /** A row of pictures joined by arrows, e.g. PC -> camera -> tick. */
    private inner class Flow(ctx: Context, val ids: List<String>) : View(ctx) {
        private val p = Paint().apply { isAntiAlias = false }
        override fun onDraw(c: Canvas) {
            val n = ids.size; val cell = width / n.toFloat(); val s = minOf(cell * .28f, height * .4f)
            ids.forEachIndexed { i, id ->
                Icons.draw(c, p, resources.displayMetrics.density, id, cell * (i + .5f), height / 2f, s, INK)
                if (i < n - 1) Icons.draw(c, p, resources.displayMetrics.density, "right", cell * (i + 1f), height / 2f, s * .45f, HOT)
            }
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Cfg.init(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        camera = BarcodeView(this).apply {
            decoderFactory = DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE))
        }
        status = label("POINT THE CAMERA AT THE QR CODE", 12f, INK)
        blocks = Blocks(this)

        // the camera "screen": a thick bezel, the live picture, and a scan frame on top
        val screen = FrameLayout(this).apply {
            addView(camera, FrameLayout.LayoutParams(-1, -1))
            addView(Frame(this@ScanActivity), FrameLayout.LayoutParams(-1, -1))
        }
        val bezel = FrameLayout(this).apply {
            background = shape(INK, dp(14), 0); setPadding(dp(10), dp(10), dp(10), dp(10))
            addView(screen, FrameLayout.LayoutParams(-1, -1))
        }

        val steps = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(Flow(this@ScanActivity, listOf("present", "qr", "camera", "check")), LinearLayout.LayoutParams(-1, dp(64))); addView(space(8))
            addView(label("1. OPEN PIXELPAD DESK ON YOUR PC", 12f)); addView(space(4))
            addView(label("2. POINT THIS CAMERA AT ITS QR CODE", 12f)); addView(space(14))
            addView(status); addView(space(8))
            addView(blocks, LinearLayout.LayoutParams(-1, dp(18))); addView(space(18))
            addView(pill("TYPE THE ADDRESS", LILAC, "keyboard") { setResult(RESULT_FIRST_USER); finish() }); addView(space(8))
            addView(pill("USE THE USB CABLE", GREEN, "usb") { tryUsb() }); addView(space(8))
            addView(pill("SKIP FOR NOW", PINK, "x") { finish() })
        }

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(dp(16), dp(14), dp(16), dp(14))
            addView(bezel, LinearLayout.LayoutParams(0, -1, 1.15f))
            addView(steps, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(18); gravity = Gravity.CENTER_VERTICAL })
        }

        val close = label("×", 16f, PAPER).apply {
            gravity = Gravity.CENTER; setBackgroundColor(HOT); setPadding(dp(14), 0, dp(14), 0); setOnClickListener { finish() }
        }
        val titleBar = LinearLayout(this).apply {
            setBackgroundColor(LILAC); gravity = Gravity.CENTER_VERTICAL
            addView(label("SCAN TO CONNECT", 14f).apply { setPadding(dp(14), dp(8), dp(14), dp(8)); compoundDrawablePadding = dp(10); setCompoundDrawablesWithIntrinsicBounds(IconDrawable("qr", INK, dp(28)), null, null, null) }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(close, LinearLayout.LayoutParams(-2, dp(34)).apply { marginEnd = dp(6) })
        }
        val window = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = shape(PAPER, 0)
            addView(titleBar); addView(View(context).apply { setBackgroundColor(INK) }, LinearLayout.LayoutParams(-1, dp(3)))
            addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        val shadowed = LinearLayout(this).apply { setBackgroundColor(INK); setPadding(0, 0, dp(7), dp(7)); addView(window, LinearLayout.LayoutParams(-1, -1)) }

        val root = FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Themes.current().top, Themes.current().bottom))
            setPadding(dp(28), dp(24), dp(28), dp(24))
            addView(shadowed, FrameLayout.LayoutParams(-1, -1))
        }
        setContentView(root)

        camera.decodeContinuous(object : BarcodeCallback {
            override fun barcodeResult(result: BarcodeResult?) { result?.text?.let { handleCode(it) } }
            override fun possibleResultPoints(resultPoints: MutableList<ResultPoint>?) {}
        })
        cameraAllowed = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (!cameraAllowed) requestPermissions(arrayOf(Manifest.permission.CAMERA), 7)
    }

    private fun space(h: Int) = View(this).apply { layoutParams = ViewGroup.LayoutParams(1, dp(h)) }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        cameraAllowed = results.isNotEmpty() && results[0] == PackageManager.PERMISSION_GRANTED
        if (cameraAllowed) camera.resume() else say("CAMERA ACCESS IS OFF. ALLOW IT IN ANDROID SETTINGS, OR TYPE THE ADDRESS.", true)
    }

    override fun onResume() { super.onResume(); if (cameraAllowed) camera.resume() }
    override fun onPause() { camera.pause(); super.onPause() }
    override fun onStart() { super.onStart(); Core.visible(1) }
    override fun onStop() { Core.visible(-1); super.onStop() }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy() }

    private fun say(text: String, warn: Boolean = false) { status.text = text.uppercase(); status.setTextColor(if (warn) WARN else INK) }

    private fun handleCode(text: String) {
        if (waiting) return
        if (!text.startsWith("pixelpad://")) { say("THAT QR CODE IS NOT FROM PIXELPAD DESK", true); return }
        // pixelpad://<address>:<port>?name=<the name chosen in PixelPad Desk>&k=<its pairing key, 32 hex digits>
        val body = text.removePrefix("pixelpad://"); val addr = body.substringBefore("?"); val query = body.substringAfter("?", "")
        fun param(n: String) = query.split("&").firstOrNull { it.startsWith("$n=") }?.substringAfter("=") ?: ""
        val v6 = addr.startsWith("[")
        val ip = (if (v6) addr.substringBefore("]").removePrefix("[") else addr.substringBefore(":")).trim()
        val portText = (if (v6) addr.substringAfter("]:", "7777") else addr.substringAfter(":", "7777"))
        val port = portText.toIntOrNull() ?: 0
        val qrName = runCatching { java.net.URLDecoder.decode(param("name"), "UTF-8") }.getOrDefault("").trim()
        val key = Seal.parse(param("k"))?.let { Seal.hex(it) } ?: ""
        if (!Cfg.validAddress(ip, port)) { say("THAT QR CODE'S ADDRESS ISN'T VALID", true); return }
        val known = Cfg.pcs.firstOrNull { it.host == ip && it.port == port }
        if (known != null) { if (key.isNotEmpty()) { known.key = key; Cfg.savePcs(); Cfg.usbKey = key }; connectTo(known); return }          // already saved: just connect
        waiting = true
        val field = EditText(this).apply {
            typeface = mono; setTextColor(INK); textSize = 14f; setSingleLine(); setText(qrName.ifEmpty { "MY PC" }.take(20)); selectAll()
            background = shape(PAPER, dp(4)); setPadding(dp(10), dp(8), dp(10), dp(8)); alwaysKeyboard()
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(8)); background = shape(PAPER, dp(4))
            addView(label("NAME THIS PC", 12f)); addView(space(6)); addView(field); addView(space(8))
            // the address is shown here once, so you can see where this app will send its input (it is never shown again)
            addView(label("THE APP WILL SEND TO " + (if (port == 7777) ip else "$ip:$port"), 10f))
            addView(label(if (key.isNotEmpty()) "PAIRED: EVERYTHING IS SIGNED AND ENCRYPTED" else "NOT PAIRED (AN OLD PIXELPAD DESK): ANYONE ON THE NETWORK COULD CONTROL THE PC. UPDATE PIXELPAD DESK.", 10f, if (key.isEmpty()) WARN else INK))
        }
        AlertDialog.Builder(this).setView(box)
            .setPositiveButton("SAVE") { _, _ ->
                val pc = Cfg.addPc(field.text.toString(), ip, port, key)
                if (pc == null) { say("THAT QR CODE'S ADDRESS ISN'T VALID", true); waiting = false }
                else { if (key.isNotEmpty()) Cfg.usbKey = key; connectTo(pc) }
            }
            .setNegativeButton("CANCEL") { _, _ -> waiting = false }
            .setOnCancelListener { waiting = false }.show()
    }

    private fun connectTo(pc: SavedPc) {
        Cfg.selectPc(pc); Cfg.transport = "wifi"
        Core.applyConnection(); Core.sender.reconnect()
        waitForPc("CONNECTING TO ${pc.name}", "COULDN'T REACH THE PC. SAME WI-FI? ALLOW PIXELPAD DESK THROUGH WINDOWS FIREWALL, THEN SCAN AGAIN.")
    }

    private fun tryUsb() {
        if (waiting) return
        Cfg.transport = "usb"; Core.applyConnection(); Core.sender.reconnect()
        waitForPc("CHECKING THE USB LINK", "NO PC OVER USB. PLUG IN, TURN ON USB DEBUGGING AND OPEN PIXELPAD DESK.")
    }

    /** Waits a few seconds for the PC to answer, then closes with success, or explains what to check. */
    private fun waitForPc(now: String, failed: String) {
        waiting = true; blocks.running = true; say(now)
        val start = SystemClock.uptimeMillis()
        val check = object : Runnable {
            override fun run() {
                if (Core.sender.connected()) { blocks.done = true; say("CONNECTED!"); handler.postDelayed({ setResult(RESULT_OK); finish() }, 700); return }
                if (SystemClock.uptimeMillis() - start > 6000) { waiting = false; blocks.running = false; say(failed, true); return }
                handler.postDelayed(this, 250)
            }
        }
        handler.post(check)
    }

    /** Scan frame: corner brackets and a sweeping line over the picture. */
    private inner class Frame(ctx: Context) : View(ctx) {
        private val p = Paint().apply { isAntiAlias = false }
        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat(); val side = min(w, h) * 0.7f
            val r = RectF((w - side) / 2, (h - side) / 2, (w + side) / 2, (h + side) / 2)
            p.style = Paint.Style.FILL; p.color = 0x552F6FE0
            c.drawRect(0f, 0f, w, r.top, p); c.drawRect(0f, r.bottom, w, h, p); c.drawRect(0f, r.top, r.left, r.bottom, p); c.drawRect(r.right, r.top, w, r.bottom, p)
            val t = dp(5).toFloat(); val l = side * 0.22f; p.color = HOT
            for ((x, y, dx, dy) in listOf(listOf(r.left, r.top, 1f, 1f), listOf(r.right, r.top, -1f, 1f), listOf(r.left, r.bottom, 1f, -1f), listOf(r.right, r.bottom, -1f, -1f))) {
                c.drawRect(minOf(x, x + dx * l), minOf(y, y + dy * t), maxOf(x, x + dx * l), maxOf(y, y + dy * t), p)
                c.drawRect(minOf(x, x + dx * t), minOf(y, y + dy * l), maxOf(x, x + dx * t), maxOf(y, y + dy * l), p)
            }
            val ph = (SystemClock.uptimeMillis() % 1800) / 1800f            // sweeping line
            p.color = 0xAAE84FB0.toInt(); c.drawRect(r.left, r.top + ph * side, r.right, r.top + ph * side + dp(3), p)
            postInvalidateDelayed(40)
        }
    }

    /** Chunky pixel progress bar: sweeps while looking, fills while connecting. */
    private inner class Blocks(ctx: Context) : View(ctx) {
        var running = false; var done = false
        private val p = Paint().apply { isAntiAlias = false }
        override fun onDraw(c: Canvas) {
            val n = 12; val gap = dp(3).toFloat(); val bw = (width - gap * (n - 1)) / n
            val step = ((SystemClock.uptimeMillis() / 140) % (n + 3)).toInt()
            for (i in 0 until n) {
                val on = done || (if (running) i <= step else i == step)
                p.style = Paint.Style.FILL; p.color = if (on) HOT else BABY
                val x = i * (bw + gap); c.drawRect(x, 0f, x + bw, height.toFloat(), p)
                p.style = Paint.Style.STROKE; p.strokeWidth = dp(2).toFloat(); p.color = INK; c.drawRect(x + 1, 1f, x + bw - 1, height - 1f, p)
            }
            postInvalidateDelayed(140)
        }
    }
}
