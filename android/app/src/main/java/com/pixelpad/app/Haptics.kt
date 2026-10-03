package com.pixelpad.app

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator

/** Turns the game's rumble (the two motors of the virtual DualShock 4: heavy and light) into this device's vibration. */
object Haptics {
    private var vib: Vibrator? = null
    private var lastAmp = -1
    private var lastAt = 0L

    /** False while the app isn't on screen: a game's rumble must not keep buzzing a phone in your pocket. */
    @Volatile var visible = true

    /** large and small are the motor strengths the game asked for, 0..255; 0 and 0 stops. */
    @Suppress("DEPRECATION")
    fun set(ctx: Context, large: Int, small: Int) {
        if (!visible && (large != 0 || small != 0)) return
        val v = vib ?: (ctx.applicationContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)?.also { vib = it } ?: return
        if (!v.hasVibrator()) return
        val gain = floatArrayOf(.5f, 1f, 1.5f)[Cfg.rumbleLevel.coerceIn(0, 2)]
        val amp = (maxOf(large.toFloat(), small * .6f) * gain).toInt().coerceIn(0, 255)
        val now = SystemClock.uptimeMillis()
        if (amp == lastAmp && (amp == 0 || now - lastAt < 800)) return   // the same buzz is still running; PixelPad Desk renews it twice a second
        lastAmp = amp; lastAt = now
        if (amp == 0) { v.cancel(); return }
        // a buzz of 1.5 s: it carries on only while the PC keeps renewing it, so if the game, the PC or the link goes away it dies by itself
        if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 1500), intArrayOf(0, amp), -1))
        else v.vibrate(longArrayOf(0, 1500), -1)
    }

    fun stop() { lastAmp = 0; vib?.cancel() }

    /** A short buzz at the chosen strength, for the Test button. */
    fun test(ctx: Context) {
        val was = visible; visible = true; lastAmp = -1; set(ctx, 220, 0)
        Handler(Looper.getMainLooper()).postDelayed({ stop(); visible = was }, 350)
    }
}
