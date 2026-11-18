package xyz.billybishop4.fleet.tv

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.Editable
import android.text.TextWatcher
import android.text.InputType
import android.view.*
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.recyclerview.widget.RecyclerView
import coil.ImageLoader
import coil.load
import coil.dispose
import coil.disk.DiskCache
import java.io.File

internal const val INK = 0xFF080808.toInt()
internal const val PANEL = 0xFF171717.toInt()
internal const val RAISED = 0xFF29292C.toInt()
internal const val MUTED = 0xFFAAAAAF.toInt()
internal const val WHITE = Color.WHITE
internal fun Context.dp(n: Int) = (n * resources.displayMetrics.density + .5f).toInt()
internal fun View.pad(n: Int) = setPadding(context.dp(n), context.dp(n), context.dp(n), context.dp(n))
internal fun Context.round(color: Int, stroke: Int = Color.TRANSPARENT, radius: Int = 16) = GradientDrawable().apply {
    setColor(color); cornerRadius = dp(radius).toFloat(); setStroke(dp(2), stroke)
}
internal fun Context.focusBackground(prominent: Boolean = false) = StateListDrawable().apply {
    addState(intArrayOf(android.R.attr.state_focused), round(0xFF3A3A3D.toInt(), WHITE))
    addState(intArrayOf(android.R.attr.state_pressed), round(0xFF48484A.toInt(), WHITE))
    addState(intArrayOf(), round(if (prominent) 0xFFF3F3F6.toInt() else PANEL))
}
internal fun Context.text(value: String, size: Float = 16f, color: Int = WHITE, bold: Boolean = false) = TextView(this).apply {
    text = value; textSize = size; setTextColor(color); includeFontPadding = false
    if (bold) typeface = Typeface.DEFAULT_BOLD
}
/** TextView.setText can schedule layout even when a model update did not change this label. */
internal fun TextView.textIfChanged(value:CharSequence) { if(!android.text.TextUtils.equals(text,value))text=value }
internal fun Context.column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
internal fun Context.row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
internal fun ViewGroup.add(view: View, width: Int = ViewGroup.LayoutParams.MATCH_PARENT, height: Int = ViewGroup.LayoutParams.WRAP_CONTENT) {
    addView(view, ViewGroup.LayoutParams(width, height))
}
internal fun LinearLayout.gap(n: Int = 10) { addView(Space(context), LinearLayout.LayoutParams(1, context.dp(n))) }
internal fun Context.action(label: String, description: String = label, prominent: Boolean = false, click: () -> Unit) = Button(this).apply {
    text = label; contentDescription = description; tag = "button:$description"; isAllCaps = false
    textSize = 14f; typeface = Typeface.DEFAULT_BOLD; minHeight = dp(44); minimumHeight = dp(44); minWidth = 0; minimumWidth = 0
    setPadding(dp(16), dp(9), dp(16), dp(9)); background = focusBackground(prominent)
    setTextColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused),intArrayOf(android.R.attr.state_pressed),intArrayOf()),
        intArrayOf(WHITE,WHITE,if(prominent)INK else WHITE)))
    isFocusable = true; isFocusableInTouchMode = true
    setOnClickListener { click() }
    stateListAnimator = null
}
internal fun LinearLayout.button(view: View, weight: Float = 0f) {
    addView(view, LinearLayout.LayoutParams(if (weight > 0f) 0 else ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, weight).apply { marginEnd = context.dp(10) })
}
internal fun Button.quiet() = apply {
    background = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), context.round(RAISED, WHITE))
        addState(intArrayOf(android.R.attr.state_selected), context.round(PANEL))
        addState(intArrayOf(), context.round(Color.TRANSPARENT))
    }
    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    textSize = 14f; minHeight = context.dp(40); minimumHeight = context.dp(40)
    setPadding(context.dp(12), context.dp(6), context.dp(12), context.dp(6))
}
internal fun LinearLayout.heading(title: String, subtitle: String = "") {
    add(context.text(title, 25f, bold = true).apply { maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END }); gap(6)
    if (subtitle.isNotEmpty()) add(context.text(subtitle, 14f, MUTED).apply { maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END })
    gap(16)
}
internal fun Context.field(label: String, value: String, password: Boolean = false, changed: (String) -> Unit): LinearLayout {
    val group = column(); group.tag = "field-group:$label"
    group.add(text(label, 13f, MUTED)); group.gap(6)
    val input = EditText(this).apply {
        tag = "field:$label"; contentDescription = label; textSize = 16f; setTextColor(WHITE); setHintTextColor(MUTED)
        setSingleLine(true); imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), round(PANEL, WHITE))
            addState(intArrayOf(), round(PANEL))
        }
        setPadding(dp(14), dp(10), dp(14), dp(10)); setText(value)
        // D-pad focus highlights an input; Center opens the TV keyboard.
        showSoftInputOnFocus = false
        fun keyboard() { requestFocus(); (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(this, InputMethodManager.SHOW_IMPLICIT) }
        setOnClickListener { keyboard() }
        setOnKeyListener { _, code, event ->
            if (code in listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER) && event.action == KeyEvent.ACTION_UP) { keyboard(); true } else false
        }
        setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) {
                (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(windowToken, 0)
                true
            } else false
        }
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { changed(s?.toString().orEmpty()) }
            override fun afterTextChanged(s: Editable?) {}
        })
    }
    group.add(input, height = dp(46)); group.gap(10); return group
}
internal fun Context.confirm(question: String, positive: String, action: () -> Unit):AlertDialog {
    val restore=captureDialogFocus()
    return AlertDialog.Builder(this).setMessage(question).setNegativeButton("Cancel", null).setPositiveButton(positive) { _, _ -> action() }.create().apply {
        ownDialog(this,restore)
        setOnShowListener { styleDialog(this);getButton(AlertDialog.BUTTON_NEGATIVE).apply { isFocusableInTouchMode = true; requestFocus() } }; show()
    }
}
/** One selection opens the action; dismissing a sheet never loses its originating focus. */
internal fun Context.showInfo(title:String,body:String):AlertDialog {
    val restore=captureDialogFocus()
    return AlertDialog.Builder(this).setTitle(title).setMessage(body).setPositiveButton("Done",null).create().apply {
        ownDialog(this,restore)
        setOnShowListener { styleDialog(this);getButton(AlertDialog.BUTTON_POSITIVE).requestFocusFromTouch() };show()
    }
}
internal fun Context.showActions(title:String,actions:List<Pair<String,()->Unit>>):AlertDialog? {
    if(actions.isEmpty())return null
    val restore=captureDialogFocus()
    return AlertDialog.Builder(this).setTitle(title).setItems(actions.map{it.first}.toTypedArray()) { _,index -> actions[index].second() }
        .setNegativeButton("Cancel",null).create().apply {
            ownDialog(this,restore)
            setOnShowListener { styleDialog(this);listView.requestFocusFromTouch();listView.setSelection(0) };show()
        }
}
internal fun Context.chooseOption(title:String,values:List<String>,selected:Int,onChoose:(Int)->Unit):AlertDialog? {
    if(values.isEmpty())return null
    val restore=captureDialogFocus()
    return AlertDialog.Builder(this).setTitle(title).setSingleChoiceItems(values.toTypedArray(),selected) { dialog,index ->
        if(index!=selected)onChoose(index)
        dialog.dismiss()
    }.setNegativeButton("Cancel",null).create().apply {
        ownDialog(this,restore)
        setOnShowListener { styleDialog(this);listView.requestFocusFromTouch();listView.setSelection(selected.coerceIn(values.indices)) };show()
    }
}
private fun Context.ownDialog(dialog:AlertDialog,restore:()->Unit) {
    val activity=this as? MainActivity
    activity?.ownDialog(dialog)
    dialog.setOnDismissListener { activity?.releaseDialog(dialog);restore() }
}
private fun Context.styleDialog(dialog:AlertDialog) {
    dialog.window?.apply {
        setBackgroundDrawable(round(PANEL,radius=16));decorView.clipToOutline=true
        setLayout(dp(500).coerceAtMost(resources.displayMetrics.widthPixels-dp(48)),ViewGroup.LayoutParams.WRAP_CONTENT)
    }
    for(button in listOf(AlertDialog.BUTTON_POSITIVE,AlertDialog.BUTTON_NEGATIVE,AlertDialog.BUTTON_NEUTRAL))dialog.getButton(button)?.quiet()?.isAllCaps=false
}
private fun Context.captureDialogFocus():()->Unit {
    val anchor=(this as? android.app.Activity)?.currentFocus
    val activity=this as? MainActivity
    val tag=anchor?.tag;val parent=anchor?.parent
    return { val generation=activity?.focusInputGeneration;anchor?.postOnAnimation {
        if(generation==activity?.focusInputGeneration && anchor.isAttachedToWindow && anchor.isShown && anchor.hasWindowFocus() && anchor.tag==tag && anchor.parent===parent)anchor.requestFocus()
    };Unit }
}
internal fun View.findTag(value: String): View? {
    if (tag == value) return this
    if (this is ViewGroup) for (i in 0 until childCount) getChildAt(i).findTag(value)?.let { return it }
    return null
}
internal class ArtworkLoader private constructor(context: Context) {
    private data class Request(val url:String,val width:Int,val height:Int)
    companion object {
        @Volatile private var shared: ArtworkLoader? = null
        fun get(context: Context): ArtworkLoader = shared ?: synchronized(this) { shared ?: ArtworkLoader(context.applicationContext).also { shared = it } }
    }
    private val lowRam=(context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).isLowRamDevice
    val loader = ImageLoader.Builder(context.applicationContext)
        .memoryCache { ArtworkMemoryCache((if(lowRam)4 else 8)*1024*1024) }
        .diskCache { DiskCache.Builder().directory(File(context.cacheDir, "artwork")).maxSizeBytes(64L * 1024 * 1024).build() }
        .bitmapFactoryMaxParallelism(if(lowRam)1 else 2)
        .crossfade(false).allowHardware(true).build()
    fun show(view: ImageView, url: String, width: Int, height: Int) {
        val previous=view.tag as? Request
        if (previous?.url==url && previous.width==width && previous.height==height) return
        view.tag = Request(url,width,height)
        if (url.isBlank()) { view.dispose();view.setImageDrawable(null); return }
        view.load(url, loader) { size(width, height);precision(coil.size.Precision.EXACT);addHeader("User-Agent", "FleetTV/0.5 (Android TV)"); crossfade(false); allowHardware(true) }
    }
}
internal fun RecyclerView.tune(cachedViews:Int=2) {
    itemAnimator = null; setItemViewCacheSize(cachedViews); overScrollMode = View.OVER_SCROLL_NEVER
    clipToPadding = false; descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
    setHasFixedSize(true)
}
