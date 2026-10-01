package com.pixelpad.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.widget.TextView
import android.content.res.Resources
import kotlin.math.cos
import kotlin.math.sin

/** The app's pixel icons, drawn from straight lines so they stay crisp at any size. Shared by every screen. */
object Icons {
    /** Draws icon [id] centred on (x, y); s is the half-size in pixels. p is used as the brush and left in stroke mode. */
    fun draw(c: Canvas, p: Paint, dp: Float, id: String, x: Float, y: Float, s: Float, color: Int) {
        p.style = Paint.Style.STROKE; p.strokeWidth = maxOf(2 * dp, s * 0.2f); p.color = color
        p.strokeCap = Paint.Cap.SQUARE; p.strokeJoin = Paint.Join.MITER
        fun ln(a: Float, b: Float, d: Float, e: Float) = c.drawLine(x + a * s, y + b * s, x + d * s, y + e * s, p)
        fun box(a: Float, b: Float, d: Float, e: Float) = c.drawRect(x + a * s, y + b * s, x + d * s, y + e * s, p)
        when (id) {
            "plus" -> { ln(-1f, 0f, 1f, 0f); ln(0f, -1f, 0f, 1f) }
            "minus" -> ln(-1f, 0f, 1f, 0f)
            "x" -> { ln(-.8f, -.8f, .8f, .8f); ln(-.8f, .8f, .8f, -.8f) }
            "check" -> { ln(-.9f, 0f, -.3f, .7f); ln(-.3f, .7f, .9f, -.7f) }
            "menu" -> { ln(-1f, -.7f, 1f, -.7f); ln(-1f, 0f, 1f, 0f); ln(-1f, .7f, 1f, .7f) }
            "share" -> { ln(-.9f, .1f, -.9f, .9f); ln(-.9f, .9f, .9f, .9f); ln(.9f, .9f, .9f, .1f); ln(0f, .5f, 0f, -.9f); ln(0f, -.9f, -.5f, -.4f); ln(0f, -.9f, .5f, -.4f) }
            "touchpad" -> { box(-1f, -.7f, 1f, .7f); ln(-1f, .3f, 1f, .3f); ln(0f, .3f, 0f, .7f) }
            "pen", "edit" -> { // a pen, tip down-left: body, band and a pointed tip
                c.save(); c.rotate(45f, x, y)
                box(-.3f, -.95f, .3f, .3f); ln(-.3f, -.5f, .3f, -.5f)
                c.drawPath(Path().apply { moveTo(x - .3f * s, y + .3f * s); lineTo(x + .3f * s, y + .3f * s); lineTo(x, y + .95f * s); close() }, p)
                c.restore()
            }
            "cursor" -> c.drawPath(Path().apply {
                moveTo(x - .6f * s, y - .9f * s); lineTo(x - .6f * s, y + .55f * s); lineTo(x - .25f * s, y + .2f * s); lineTo(x + .05f * s, y + .9f * s)
                lineTo(x + .3f * s, y + .78f * s); lineTo(x, y + .12f * s); lineTo(x + .42f * s, y + .12f * s); close() }, p)
            "gesture" -> { ln(-.9f, .6f, -.3f, .6f); ln(-.3f, .6f, .1f, -.2f); ln(.1f, -.2f, .7f, -.2f); ln(.7f, -.2f, .4f, -.5f); ln(.7f, -.2f, .4f, .1f) }
            "keys" -> { box(-.9f, -.9f, -.1f, -.1f); box(.1f, -.9f, .9f, -.1f); box(-.9f, .1f, -.1f, .9f); box(.1f, .1f, .9f, .9f) }
            "info" -> { c.drawCircle(x, y, .85f * s, p); ln(0f, -.05f, 0f, .5f); ln(0f, -.45f, 0f, -.45f) }
            "camera" -> { box(-.9f, -.6f, .9f, .7f); c.drawCircle(x, y + .05f * s, .35f * s, p); ln(-.4f, -.6f, -.25f, -.9f); ln(-.25f, -.9f, .25f, -.9f); ln(.25f, -.9f, .4f, -.6f) }
            "eraser" -> { c.save(); c.rotate(45f, x, y); box(-.4f, -.9f, .4f, .6f); ln(-.4f, -.05f, .4f, -.05f); c.restore() }
            "penbutton" -> { c.drawCircle(x, y, .85f * s, p); p.style = Paint.Style.FILL; c.drawCircle(x, y, .3f * s, p) }
            "dot" -> { p.style = Paint.Style.FILL; c.drawCircle(x, y, .5f * s, p) }
            "gamepad" -> { box(-1f, -.5f, 1f, .6f); ln(-.6f, .05f, -.2f, .05f); ln(-.4f, -.15f, -.4f, .25f); ln(.3f, .05f, .3f, .05f); ln(.65f, -.15f, .65f, -.15f) }
            "retry" -> { c.drawArc(RectF(x - .8f * s, y - .8f * s, x + .8f * s, y + .8f * s), -60f, 280f, false, p); ln(-.61f, -.51f, -.1f, -.6f); ln(-.61f, -.51f, -.75f, 0f) }
            "left" -> { ln(.9f, 0f, -.9f, 0f); ln(-.9f, 0f, -.3f, -.6f); ln(-.9f, 0f, -.3f, .6f) }
            "right" -> { ln(-.9f, 0f, .9f, 0f); ln(.9f, 0f, .3f, -.6f); ln(.9f, 0f, .3f, .6f) }
            "present" -> { box(-1f, -.8f, 1f, .4f); ln(0f, .4f, 0f, .9f); ln(-.5f, .9f, .5f, .9f) }
            "white" -> box(-.9f, -.7f, .9f, .7f)
            "power" -> { c.drawArc(RectF(x - .8f * s, y - .8f * s, x + .8f * s, y + .8f * s), -60f, 300f, false, p); ln(0f, -1f, 0f, -.1f) }
            "lock" -> { box(-.75f, -.1f, .75f, .95f); c.drawArc(RectF(x - .45f * s, y - .95f * s, x + .45f * s, y + .1f * s), 180f, 180f, false, p); ln(-.45f, -.4f, -.45f, -.1f); ln(.45f, -.4f, .45f, -.1f) }
            "unlock" -> { box(-.75f, -.1f, .75f, .95f); c.drawArc(RectF(x - .45f * s, y - .95f * s, x + .45f * s, y + .1f * s), 180f, 120f, false, p); ln(-.45f, -.4f, -.45f, -.1f) }
            "gear" -> { c.drawCircle(x, y, .5f * s, p); for (k in 0 until 8) { val a = k * Math.PI / 4; ln((.75 * cos(a)).toFloat(), (.75 * sin(a)).toFloat(), cos(a).toFloat(), sin(a).toFloat()) } }
            "rotate" -> { c.drawArc(RectF(x - .95f * s, y - .95f * s, x + .95f * s, y + .95f * s), -40f, 250f, false, p); box(-.3f, -.5f, .3f, .5f); ln(.62f, -.78f, .78f, -.38f); ln(.62f, -.78f, .22f, -.74f) }
            "wifi" -> { for (k in 1..3) { val r = .45f * k; c.drawArc(RectF(x - r * s, y + (.8f - r) * s, x + r * s, y + (.8f + r) * s), 225f, 90f, false, p) }; ln(0f, .8f, 0f, .8f) }
            "usb" -> { ln(0f, -1f, 0f, .6f); ln(0f, -1f, -.4f, -.5f); ln(0f, -1f, .4f, -.5f); c.drawCircle(x, y + .85f * s, .25f * s, p); ln(0f, .1f, .7f, -.2f); ln(.7f, -.2f, .7f, -.5f) }
            "bt" -> { ln(-.5f, -.5f, .5f, .5f); ln(.5f, .5f, 0f, 1f); ln(0f, 1f, 0f, -1f); ln(0f, -1f, .5f, -.5f); ln(.5f, -.5f, -.5f, .5f) }
            "qr" -> { box(-1f, -1f, -.2f, -.2f); box(.2f, -1f, 1f, -.2f); box(-1f, .2f, -.2f, 1f); ln(.4f, .4f, .4f, .4f); ln(.9f, .4f, .9f, .4f); ln(.6f, .9f, .6f, .9f) }
            "keyboard" -> { box(-1f, -.6f, 1f, .6f); ln(-.6f, -.2f, -.6f, -.2f); ln(0f, -.2f, 0f, -.2f); ln(.6f, -.2f, .6f, -.2f); ln(-.5f, .3f, .5f, .3f) }
            "play" -> {
                p.style = Paint.Style.FILL
                c.drawPath(Path().apply { moveTo(x - .6f * s, y - .8f * s); lineTo(x + .8f * s, y); lineTo(x - .6f * s, y + .8f * s); close() }, p)
            }
            "stop", "black" -> { p.style = Paint.Style.FILL; c.drawRect(x - .8f * s, y - .8f * s, x + .8f * s, y + .8f * s, p) }
        }
        p.strokeCap = Paint.Cap.BUTT
    }
}

/** Lets any TextView (buttons, tabs) carry one of the icons: setCompoundDrawablesWithIntrinsicBounds(IconDrawable(...), ...). */
class IconDrawable(private val id: String, private val color: Int, private val px: Int) : Drawable() {
    private val paint = Paint().apply { isAntiAlias = false }
    private val dp = Resources.getSystem().displayMetrics.density
    override fun draw(canvas: Canvas) { val b = bounds; Icons.draw(canvas, paint, dp, id, b.exactCenterX(), b.exactCenterY(), px * 0.42f, color) }
    override fun getIntrinsicWidth() = px
    override fun getIntrinsicHeight() = px
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Suppress("OVERRIDE_DEPRECATION") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/**
 * A TextView that keeps its icon and its text centred together. A plain TextView leaves a left-hand icon at the edge of a wide button.
 * Turn [centreGroup] on and give the view a start gravity; the icon and text then sit in the middle as one group.
 */
class IconText(ctx: Context) : TextView(ctx) {
    var centreGroup = false
    override fun onDraw(c: Canvas) {
        val d = compoundDrawables[0]
        if (!centreGroup || d == null) { super.onDraw(c); return }
        val t = text?.toString().orEmpty()
        val tw = if (t.isEmpty()) 0f else minOf(paint.measureText(t), (width - paddingLeft - paddingRight - d.intrinsicWidth - compoundDrawablePadding).toFloat())
        val content = d.intrinsicWidth + (if (tw > 0) compoundDrawablePadding else 0) + tw
        c.save(); c.translate(maxOf(0f, ((width - paddingLeft - paddingRight) - content) / 2f), 0f); super.onDraw(c); c.restore()
    }
}
