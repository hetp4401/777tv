package xyz.billybishop4.fleet.tv

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.text.Layout
import android.text.BoringLayout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.recyclerview.widget.RecyclerView
import coil.request.Disposable
import coil.request.ImageRequest

/** A recycled channel is one view. Text is shaped once, never during drawing. */
internal class LiveChannelRow(private val a: MainActivity,
    click: (Channel, View) -> Unit, toggle: (Channel) -> Unit) : View(a) {
    private val namePaint=TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color=WHITE;textSize=14*a.resources.displayMetrics.scaledDensity;typeface=Typeface.DEFAULT_BOLD
    }
    private val numberPaint=TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color=MUTED;textSize=11*a.resources.displayMetrics.scaledDensity }
    private val starPaint=TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color=ACCENT;textSize=15*a.resources.displayMetrics.scaledDensity }
    private var channel:Channel?=null
    private var favorite=false
    private var showGroup=true
    private var nameLayout:Layout?=null
    private var numberLayout:Layout?=null
    private val nameMetrics=BoringLayout.Metrics()
    private val numberMetrics=BoringLayout.Metrics()
    private var logo:Drawable?=null
    private var logoUrl=""
    private var request:Disposable?=null
    private var generation=0
    private var textX=0f
    init {
        layoutParams=RecyclerView.LayoutParams(-1,a.dp(58)).apply { bottomMargin=a.dp(3) }
        isFocusable=true;isFocusableInTouchMode=true;isClickable=true;isLongClickable=true
        background=StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused),a.round(AppTheme.palette.focusFill,FOCUS))
            addState(intArrayOf(android.R.attr.state_selected),a.round(PANEL))
            addState(intArrayOf(),a.round(android.graphics.Color.TRANSPARENT))
        }
        setOnClickListener { v -> channel?.let { click(it,v) } }
        setOnLongClickListener { channel?.let(toggle);true }
    }
    fun bind(c:Channel,selected:Boolean,saved:Boolean,displayGroup:Boolean=true) {
        val previous=channel
        val reshape=showGroup!=displayGroup || previous==null || previous.name!=c.name || previous.number!=c.number || previous.group!=c.group || previous.logo.isBlank()!=c.logo.isBlank()
        showGroup=displayGroup
        channel=c;tag="channel:${c.id}";contentDescription=c.name
        if(reshape)shapeText()
        mark(selected,saved)
        if(logoUrl!=c.logo) {
            clearLogo();logoUrl=c.logo
            if(c.logo.isNotBlank()) {
                val expected=generation
                request=a.artwork.loader.enqueue(ImageRequest.Builder(a).data(c.logo).size(a.dp(36),a.dp(36))
                    .allowHardware(true).crossfade(false).target(onSuccess={ drawable ->
                        if(generation==expected) { logo=drawable;drawable.callback=this;layoutLogo();invalidate() }
                    }).build())
            }
        }
        invalidate()
    }
    fun mark(selected:Boolean,saved:Boolean,displayGroup:Boolean=showGroup) {
        isSelected=selected
        if(showGroup!=displayGroup) { showGroup=displayGroup;shapeText();invalidate() }
        if(favorite!=saved) { favorite=saved;invalidate() }
        if(Build.VERSION.SDK_INT>=30)stateDescription=when {
            selected && saved -> "Selected preview, favorite"
            selected -> "Selected preview"
            saved -> "Favorite"
            else -> null
        }
    }
    private fun shapeText() {
        val c=channel?:return
        if(width==0)return
        val inset=a.dp(9);val icon=if(c.logo.isBlank())0 else a.dp(45);val star=a.dp(20)
        val available=(width-2*inset-icon-star).coerceAtLeast(1)
        textX=(inset+if(layoutDirection==LAYOUT_DIRECTION_RTL)star else icon).toFloat()
        fun line(text:String,paint:TextPaint,previous:Layout?,metrics:BoringLayout.Metrics):Layout {
            val simple=BoringLayout.isBoring(text,paint,metrics)
            if(simple!=null)return if(previous is BoringLayout)
                previous.replaceOrMake(text,paint,available,Layout.Alignment.ALIGN_NORMAL,1f,0f,simple,false,TextUtils.TruncateAt.END,available)
            else BoringLayout.make(text,paint,available,Layout.Alignment.ALIGN_NORMAL,1f,0f,simple,false,TextUtils.TruncateAt.END,available)
            return StaticLayout.Builder.obtain(text,0,text.length,paint,available)
                .setMaxLines(1).setEllipsize(TextUtils.TruncateAt.END).setIncludePad(false)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR).build()
        }
        nameLayout=line(c.name,namePaint,nameLayout,nameMetrics)
        val secondary=c.number.ifBlank { if(showGroup)c.group else "" }
        numberLayout=if(secondary.isBlank())null else line(secondary,numberPaint,numberLayout,numberMetrics)
    }
    private fun layoutLogo() {
        val d=logo?:return;val size=a.dp(36)
        val scale=minOf(size.toFloat()/d.intrinsicWidth.coerceAtLeast(1),size.toFloat()/d.intrinsicHeight.coerceAtLeast(1))
        val w=(d.intrinsicWidth.coerceAtLeast(1)*scale).toInt();val h=(d.intrinsicHeight.coerceAtLeast(1)*scale).toInt()
        val left=(if(layoutDirection==LAYOUT_DIRECTION_RTL)width-a.dp(9)-size else a.dp(9))+(size-w)/2
        d.setBounds(left,(height-h)/2,left+w,(height+h)/2)
    }
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int) { super.onSizeChanged(w,h,oldw,oldh);shapeText();layoutLogo() }
    override fun onRtlPropertiesChanged(direction:Int) { super.onRtlPropertiesChanged(direction);shapeText();layoutLogo() }
    override fun onDraw(canvas:Canvas) {
        super.onDraw(canvas)
        if(channel==null)return
        val first=nameLayout?:return;val second=numberLayout
        val top=(height-first.height-(second?.let { it.height+a.dp(4) }?:0))/2f
        canvas.save();canvas.translate(textX,top);first.draw(canvas)
        if(second!=null) { canvas.translate(0f,(first.height+a.dp(4)).toFloat());second.draw(canvas) };canvas.restore()
        logo?.draw(canvas)
        if(favorite)canvas.drawText("★",(if(layoutDirection==LAYOUT_DIRECTION_RTL)a.dp(9) else width-a.dp(25)).toFloat(),
            (height-starPaint.ascent()-starPaint.descent())/2f,starPaint)
    }
    override fun verifyDrawable(who:Drawable)=who===logo || super.verifyDrawable(who)
    override fun onInitializeAccessibilityNodeInfo(info:AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info);info.className="android.widget.TextView"
        info.text=channel?.let { "${it.name}, ${it.number.ifBlank { it.group }}" }
        info.addAction(AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK,
            if(isSelected)"Open full screen" else "Preview channel"))
        info.addAction(AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_LONG_CLICK,
            if(favorite)"Remove from favorites" else "Add to favorites"))
    }
    private fun clearLogo() { generation++;request?.dispose();request=null;logo?.callback=null;logo=null;logoUrl="" }
    fun release() { clearLogo();channel=null }
}
