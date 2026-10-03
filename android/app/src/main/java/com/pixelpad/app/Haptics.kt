package com.pixelpad.app

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator

/** Turns the game's rumble (the two motors of the virtual DualShock 4: heavy and light) into this device's vibration. */
object Haptics {
    private var vib: Vibrator? = null
    private var lastAmp = -1

    /** large and small are the motor strengths the game asked for, 0..255; 0 and 0 stops. */
    @Suppress("DEPRECATION")
    fun set(ctx: Context, large: Int, small: Int) {
        val v = vib ?: (ctx.applicationContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)?.also { vib = it } ?: return
        if (!v.hasVibrator()) return
        val gain = floatArrayOf(.5f, 1f, 1.5f)[Cfg.rumbleLevel.coerceIn(0, 2)]
        val amp = (maxOf(large.toFloat(), small * .6f) * gain).toInt().coerceIn(0, 255)
        if (amp == lastAmp) return
        lastAmp = amp
        if (amp == 0) { v.cancel(); return }
        // one long buzz that keeps going until the game changes or stops it (or the link drops, see Sender)
        if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 20000), intArrayOf(0, amp), 1))
        else v.vibrate(longArrayOf(0, 20000), 1)
    }

    fun stop() { lastAmp = 0; vib?.cancel() }

    /** A short buzz at the chosen strength, for the Test button. */
    fun test(ctx: Context) {
        lastAmp = -1; set(ctx, 220, 0)
        Handler(Looper.getMainLooper()).postDelayed({ stop() }, 350)
    }
}
