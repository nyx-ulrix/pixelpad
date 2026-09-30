package com.pixelpad.app

import android.app.Activity
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager

/** What the main screen needs from the app. */
interface Host {
    fun setDark(on: Boolean)
    fun lockNav(on: Boolean)
    fun openSettings(page: Int = 0)
    fun exit()
}

class MainActivity : Activity() {
    private lateinit var view: PixelPadView
    private var pinned = false
    private var scanChecked = false
    private var scanPending = false
    private val handler = Handler(Looper.getMainLooper())

    private val host: Host = object : Host {
        /** Locked tablet: minimum backlight. The screen stays awake, so the pen keeps working. */
        override fun setDark(on: Boolean) {
            window.attributes = window.attributes.apply {
                screenBrightness = if (on) 0.01f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
        }

        /**
         * Claims the screen edges in trackpad / presenter mode, and pins the app (unless switched off in Settings) so Android's own
         * swipe, three- and four-finger gestures can't take you out of it. Pinning is never undone on a screen change, because
         * unpinning can lock the device; the Exit button is how you leave.
         */
        override fun lockNav(on: Boolean) {
            if (Build.VERSION.SDK_INT >= 29 && ::view.isInitialized)
                view.systemGestureExclusionRects = if (on) listOf(Rect(0, 0, view.width, view.height)) else emptyList()
            if (Cfg.lockNav && !pinned) { try { startLockTask(); pinned = true } catch (e: Exception) {} }
            else if (!Cfg.lockNav && pinned) { try { stopLockTask() } catch (e: Exception) {}; pinned = false }
        }

        /** The Exit button: unpin first, then close the app completely. */
        override fun exit() { try { stopLockTask() } catch (e: Exception) {}; pinned = false; finishAndRemoveTask() }

        override fun openSettings(page: Int) = startActivity(Intent(this@MainActivity, SettingsActivity::class.java).putExtra("page", page))
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Cfg.init(this)
        Core.applyConnection()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        view = PixelPadView(this, Core.sender, host)
        setContentView(view)
    }

    override fun onResume() {
        super.onResume(); Core.applyConnection(); view.reload()
        if (!scanChecked) { // on launch: if no PC answers, show the QR scanner (once per launch)
            scanChecked = true; scanPending = true
            handler.postDelayed(scanRun, 1800)
        }
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == 3 && res == RESULT_FIRST_USER) host.openSettings(0)   // "type the address instead"
    }
    private val scanRun = Runnable { scanPending = false; if (!isFinishing && hasWindowFocus() && !Core.sender.connected()) startActivityForResult(Intent(this, ScanActivity::class.java), 3) }

    override fun onPause() {
        if (scanPending) { handler.removeCallbacks(scanRun); scanPending = false; scanChecked = false }   // left before it showed: try again next time we're back
        view.pause(); super.onPause()
    }
    override fun onStart() { super.onStart(); Core.visible(1) }
    override fun onStop() { Core.visible(-1); super.onStop() }

    override fun onKeyDown(code: Int, e: KeyEvent): Boolean = if (view.handleKey(code)) true else super.onKeyDown(code, e)
    override fun onKeyUp(code: Int, e: KeyEvent): Boolean = if (view.wantsKey(code)) true else super.onKeyUp(code, e)

    override fun onWindowFocusChanged(focus: Boolean) {
        if (focus) window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }
}
