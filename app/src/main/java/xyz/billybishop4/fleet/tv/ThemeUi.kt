package xyz.billybishop4.fleet.tv

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.widget.ProgressBar

internal val FOCUS get()=AppTheme.palette.focusRing
internal val ACCENT get()=AppTheme.palette.accent
internal val ON_ACCENT get()=AppTheme.palette.onAccent
internal const val ERROR_TEXT=0xFFFFC4C4.toInt()
internal const val SUCCESS_TEXT=0xFFB9E8C5.toInt()

/** The drawable caches its native gradient; selection and scrolling do not allocate shaders. */
internal fun Context.themeBackground(palette:ThemePalette=AppTheme.palette,gradient:Boolean=AppTheme.gradient):Drawable =
    if(gradient && palette.prefersGradient && palette.gradientStart!=palette.gradientEnd)
        GradientDrawable(GradientDrawable.Orientation.TL_BR,intArrayOf(palette.gradientStart,palette.gradientEnd))
    else ColorDrawable(palette.background)
internal fun Context.panelOverlay(alpha:Int=242):Int=(AppTheme.palette.overlaySurface and 0x00FFFFFF) or (alpha.coerceIn(0,255) shl 24)
internal fun ProgressBar.themeTint()=apply {
    val tint=ColorStateList.valueOf(ACCENT)
    indeterminateTintList=tint;progressTintList=tint
}
