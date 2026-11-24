package com.rik.vidaraplus

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

object Palette {
    const val RED = 0xFFED001A.toInt()
    const val BAR = 0xFF0F0F0F.toInt()
    const val CARD = 0xFF1F1F1F.toInt()
    const val DIM = 0xFFAAAAAA.toInt()
    const val WHITE = 0xFFFFFFFF.toInt()
    const val BLUE = 0xFF3AA0FF.toInt()
}

fun Context.dp(value: Number): Int =
    (value.toFloat() * resources.displayMetrics.density + 0.5f).toInt()

fun Context.rounded(color: Int, radiusDp: Float): GradientDrawable =
    GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(radiusDp).toFloat()
        setColor(color)
    }

fun Context.outlined(color: Int, strokeDp: Float, radiusDp: Float): GradientDrawable =
    GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(radiusDp).toFloat()
        setColor(Color.TRANSPARENT)
        setStroke(dp(strokeDp), color)
    }

fun Context.label(
    text: String,
    sizeSp: Float,
    color: Int = Color.WHITE,
    bold: Boolean = false,
): TextView =
    TextView(this).apply {
        this.text = text
        textSize = sizeSp
        setTextColor(color)
        includeFontPadding = false
        if (bold) setTypeface(Typeface.DEFAULT, Typeface.BOLD)
    }

fun Context.simpleIcon(icon: Int, sizeDp: Int, tint: Int = Color.WHITE, paddingDp: Int = 8): ImageView =
    ImageView(this).apply {
        setImageResource(icon)
        setColorFilter(tint)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(paddingDp), dp(paddingDp), dp(paddingDp), dp(paddingDp))
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

fun Context.iconButton(icon: Int, sizeDp: Int, tint: Int = Color.WHITE, onClick: () -> Unit): ImageView =
    simpleIcon(icon, sizeDp, tint, paddingDp = 6).apply {
        isClickable = true
        setOnClickListener { onClick() }
    }

fun Context.pill(text: String, textColor: Int, fill: Int, onClick: () -> Unit): TextView =
    label(text, 13.5f, textColor, bold = true).apply {
        gravity = Gravity.CENTER
        setPadding(dp(12), 0, dp(12), 0)
        background = rounded(fill, 16f)
        isClickable = true
        setOnClickListener { onClick() }
    }
