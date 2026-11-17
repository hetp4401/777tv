package xyz.billybishop4.fleet.tv

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

/** Owned by playback, so enabling the renderer can replace the video view without closing the dialog. */
internal fun showPictureControls(
    a: MainActivity,
    initial: VideoPicture,
    apply: (VideoPicture, (String?) -> Unit) -> Unit,
    actualPicture: (() -> VideoPicture)? = null,
    restoreAnchor: Boolean = true,
    closed: () -> Unit = {},
): AlertDialog {
    val main = Handler(Looper.getMainLooper())
    val anchor = a.currentFocus
    val body = a.column().apply { tag = "picture-controls"; contentDescription = "Picture controls"; pad(12) }
    val error = a.text("", 12f, 0xFFFFC4C4.toInt()).apply {
        tag = "picture-error"; visibility = View.GONE; maxLines = 3
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    val settings = listOf(
        PictureAdjustment("Brightness", "brightness", -50, 50, { it.brightness }, { value, amount -> value.copy(brightness = amount) }),
        PictureAdjustment("Contrast", "contrast", -50, 50, { it.contrast }, { value, amount -> value.copy(contrast = amount) }),
        PictureAdjustment("Saturation", "saturation", 0, 200, { it.saturation }, { value, amount -> value.copy(saturation = amount) }),
    )
    data class Binding(val setting: PictureAdjustment, val slider: SeekBar, val value: TextView)
    val bindings = ArrayList<Binding>()
    var picture = initial
    var acceptedPicture = initial
    var syncing = false
    var request = 0
    var open = true
    fun bind() {
        syncing = true
        bindings.forEach { item ->
            val value = item.setting.read(picture)
            item.slider.progress = (value - item.setting.min) / 5
            item.value.text = if (item.setting.key == "saturation") "$value%" else if (value > 0) "+$value" else value.toString()
            if (android.os.Build.VERSION.SDK_INT >= 30) item.slider.stateDescription = item.value.text
        }
        syncing = false
    }
    fun change(next: VideoPicture) {
        if (!open || next == picture) return
        picture = next; bind(); val token = ++request
        error.visibility = View.GONE
        apply(next) { message -> main.post {
            if (open && token == request) {
                if (message.isNullOrBlank()) acceptedPicture = next
                else { acceptedPicture = actualPicture?.invoke() ?: acceptedPicture; picture = acceptedPicture; bind() }
                error.text = message.orEmpty()
                error.visibility = if (message.isNullOrBlank()) View.GONE else View.VISIBLE
            }
        } }
    }
    settings.forEach { setting ->
        val value = a.text("", 13f).apply { tag = "picture-value:${setting.key}"; gravity = Gravity.END }
        val slider = PictureSlider(a, setting).apply {
            tag = "picture-${setting.key}"; id = View.generateViewId(); max = (setting.max - setting.min) / 5
            keyProgressIncrement = 1; contentDescription = setting.label
            isFocusable = true; isFocusableInTouchMode = true; minimumHeight = a.dp(44)
            progressTintList = ColorStateList.valueOf(WHITE); secondaryProgressTintList = ColorStateList.valueOf(WHITE)
            progressBackgroundTintList = ColorStateList.valueOf(0x44FFFFFF)
            setPadding(a.dp(6), 0, a.dp(6), 0); background = null
            fun dot(size: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(WHITE); setSize(a.dp(size), a.dp(size)) }
            thumb = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), dot(12))
                addState(intArrayOf(android.R.attr.state_pressed), dot(12))
                addState(intArrayOf(), InsetDrawable(dot(6), a.dp(3)))
            }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser && !syncing) change(setting.write(picture, setting.min + progress * 5))
                }
                override fun onStartTrackingTouch(seekBar: SeekBar) {}
                override fun onStopTrackingTouch(seekBar: SeekBar) {}
            })
            setOnKeyListener { _, code, event ->
                when (code) {
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (event.action == KeyEvent.ACTION_DOWN) {
                            val direction = if (code == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1
                            change(setting.write(picture, (setting.read(picture) + direction * 5).coerceIn(setting.min, setting.max)))
                        }
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> true
                    else -> false
                }
            }
        }
        bindings += Binding(setting, slider, value)
        body.add(a.row().apply {
            addView(a.text(setting.label, 14f), LinearLayout.LayoutParams(a.dp(104), -2))
            addView(slider, LinearLayout.LayoutParams(0, a.dp(44), 1f))
            addView(value, LinearLayout.LayoutParams(a.dp(50), -2).apply { marginStart = a.dp(8) })
        }, height = a.dp(48))
    }
    body.gap(4); body.add(error)
    bind()
    val dialog = AlertDialog.Builder(a).setTitle("Picture controls").setView(body)
        .setNeutralButton("Reset picture", null).setPositiveButton("Done", null).create()
    dialog.setOnShowListener {
        dialog.window?.apply {
            setBackgroundDrawable(a.round(PANEL, radius = 16)); decorView.clipToOutline = true
            setLayout(a.dp(560).coerceAtMost(a.resources.displayMetrics.widthPixels - a.dp(48)), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val reset = dialog.getButton(AlertDialog.BUTTON_NEUTRAL).quiet().apply {
            if (id == View.NO_ID) id = View.generateViewId()
            isAllCaps = false; minHeight = a.dp(44); minimumHeight = a.dp(44)
            setOnClickListener { change(VideoPicture()) }
        }
        val done = dialog.getButton(AlertDialog.BUTTON_POSITIVE).quiet().apply {
            if (id == View.NO_ID) id = View.generateViewId()
            isAllCaps = false; minHeight = a.dp(44); minimumHeight = a.dp(44)
        }
        bindings.forEachIndexed { index, binding ->
            binding.slider.nextFocusUpId = bindings.getOrNull(index - 1)?.slider?.id ?: binding.slider.id
            binding.slider.nextFocusDownId = bindings.getOrNull(index + 1)?.slider?.id ?: reset.id
        }
        reset.nextFocusUpId = bindings.last().slider.id; reset.nextFocusRightId = done.id
        done.nextFocusUpId = bindings.last().slider.id; done.nextFocusLeftId = reset.id
        bindings.first().slider.requestFocus()
    }
    dialog.setOnDismissListener {
        open = false; request++; main.removeCallbacksAndMessages(null)
        closed()
        if (restoreAnchor) anchor?.postOnAnimation {
            if (anchor.isAttachedToWindow && anchor.isShown && anchor.hasWindowFocus() && (a.currentFocus == null || a.currentFocus === anchor)) anchor.requestFocus()
        }
    }
    dialog.show()
    return dialog
}

private data class PictureAdjustment(
    val label: String,
    val key: String,
    val min: Int,
    val max: Int,
    val read: (VideoPicture) -> Int,
    val write: (VideoPicture, Int) -> VideoPicture,
)

private class PictureSlider(context: Context, private val adjustment: PictureAdjustment) : SeekBar(context) {
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.rangeInfo = AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT,
            adjustment.min.toFloat(), adjustment.max.toFloat(), (adjustment.min + progress * 5).toFloat())
    }
}
