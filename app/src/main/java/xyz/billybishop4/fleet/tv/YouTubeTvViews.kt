package xyz.billybishop4.fleet.tv

import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.Parcelable
import android.text.TextUtils
import android.view.*
import android.widget.*
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.dispose

/** Updating artwork in an already fixed frame must not remeasure every shelf above it. */
internal class FixedArtworkView(context:Context):ImageView(context) {
    private var replacingFixedImage=false
    override fun setImageDrawable(drawable:Drawable?) {
        val previous=replacingFixedImage
        val params=layoutParams
        replacingFixedImage=width>0 && height>0 && !adjustViewBounds && params!=null &&
            params.width!=ViewGroup.LayoutParams.WRAP_CONTENT && params.height!=ViewGroup.LayoutParams.WRAP_CONTENT
        try { super.setImageDrawable(drawable) } finally { replacingFixedImage=previous }
    }
    override fun requestLayout() { if(!replacingFixedImage)super.requestLayout() }
}

/** The labels are clipped by the collapsed rail, and revealed without moving the content. */
internal class YouTubeRailIcon(private val kind:String,private val size:Int):Drawable() {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=WHITE;style=Paint.Style.STROKE;strokeWidth=2f }
    private val path=Path();private val rect=RectF()
    override fun getIntrinsicWidth()=size
    override fun getIntrinsicHeight()=size
    override fun draw(canvas:Canvas) {
        val save=canvas.save();canvas.translate(bounds.left.toFloat(),bounds.top.toFloat());canvas.scale(bounds.width()/24f,bounds.height()/24f)
        when(kind) {
            "Search"->{canvas.drawCircle(10f,10f,6f,paint);canvas.drawLine(15f,15f,21f,21f,paint)}
            "Home"->{path.reset();path.moveTo(3f,11f);path.lineTo(12f,3f);path.lineTo(21f,11f);path.lineTo(21f,21f);path.lineTo(15f,21f);path.lineTo(15f,14f);path.lineTo(9f,14f);path.lineTo(9f,21f);path.lineTo(3f,21f);path.close();canvas.drawPath(path,paint)}
            "Shorts"->{rect.set(6f,2f,18f,22f);canvas.drawRoundRect(rect,4f,4f,paint);path.reset();path.moveTo(10f,8f);path.lineTo(16f,12f);path.lineTo(10f,16f);path.close();canvas.drawPath(path,paint)}
            "Subscriptions"->{canvas.drawLine(7f,3f,17f,3f,paint);canvas.drawLine(4f,6f,20f,6f,paint);rect.set(3f,9f,21f,22f);canvas.drawRoundRect(rect,2f,2f,paint);path.reset();path.moveTo(10f,12f);path.lineTo(16f,15f);path.lineTo(10f,19f);path.close();canvas.drawPath(path,paint)}
            "Library"->{rect.set(3f,3f,8f,21f);canvas.drawRoundRect(rect,1f,1f,paint);rect.set(11f,3f,16f,21f);canvas.drawRoundRect(rect,1f,1f,paint);canvas.drawLine(19f,4f,23f,21f,paint)}
            else->{canvas.drawCircle(12f,8f,4f,paint);rect.set(4f,14f,20f,24f);canvas.drawArc(rect,180f,180f,false,paint)}
        }
        canvas.restoreToCount(save)
    }
    override fun setAlpha(alpha:Int){paint.alpha=alpha;invalidateSelf()}
    override fun setColorFilter(filter:ColorFilter?){paint.colorFilter=filter;invalidateSelf()}
    @Deprecated("Deprecated in Java") override fun getOpacity()=PixelFormat.TRANSLUCENT
}

/** One small native canvas instead of an IME window and a hierarchy of keyboard buttons. */
internal class YouTubeKeyboard(
    context:Context,private val insert:(String)->Unit,private val erase:()->Unit,
    private val clear:()->Unit,private val search:()->Unit,private val systemKeyboard:()->Unit,
    private val leave:(Int)->Boolean,
):View(context) {
    private val letters=("A B C D E F G ⌫ H I J K L M N &123 O P Q R S T U ⌨ V W X Y Z - ' ·").split(' ')
    private val numbers=("1 2 3 4 5 6 7 ⌫ 8 9 0 @ # $ & ABC ( ) / : ; ! ? ⌨ . , + = _ - ' ·").split(' ')
    private val actions=listOf("Space","Clear","Search")
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG);private val rect=RectF()
    private var numeric=false;private var selected=0
    init {
        tag="youtube-keyboard";isFocusable=true;isFocusableInTouchMode=true;isClickable=true
        if(Build.VERSION.SDK_INT>=26)defaultFocusHighlightEnabled=false
        describe()
    }
    private fun label(index:Int)=if(index<32)(if(numeric)numbers else letters)[index] else actions[index-32]
    private fun describe(){contentDescription="YouTube keyboard, ${when(val key=label(selected)){"⌫"->"Delete";"⌨"->"System keyboard";else->key}}"}
    private fun choose(index:Int) { selected=index.coerceIn(0,34);describe();invalidate() }
    override fun onFocusChanged(gainFocus:Boolean,direction:Int,previouslyFocusedRect:Rect?) {
        super.onFocusChanged(gainFocus,direction,previouslyFocusedRect);invalidate()
    }
    private fun bounds(index:Int,out:RectF) {
        val h=height/5f;val row=if(index<32)index/8 else 4
        val columns=if(row==4)3 else 8;val column=if(row==4)index-32 else index%8;val w=width.toFloat()/columns
        out.set(column*w+2,row*h+2,(column+1)*w-2,(row+1)*h-2)
    }
    override fun onDraw(canvas:Canvas) {
        super.onDraw(canvas)
        paint.typeface=Typeface.DEFAULT;paint.textAlign=Paint.Align.CENTER;paint.textSize=context.dp(15).toFloat()
        val baseline=-(paint.ascent()+paint.descent())/2
        for(i in 0..34) {
            bounds(i,rect);val focused=hasFocus() && i==selected
            paint.color=if(focused)WHITE else PANEL;canvas.drawRoundRect(rect,context.dp(10).toFloat(),context.dp(10).toFloat(),paint)
            paint.color=if(focused)INK else WHITE
            canvas.drawText(label(i),rect.centerX(),rect.centerY()+baseline,paint)
        }
    }
    override fun onKeyDown(keyCode:Int,event:KeyEvent):Boolean {
        val row=if(selected<32)selected/8 else 4;val col=if(row==4)selected-32 else selected%8
        when(keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT->if(col>0)choose(selected-1) else return leave(View.FOCUS_LEFT)
            KeyEvent.KEYCODE_DPAD_RIGHT->if(col<(if(row==4)2 else 7))choose(selected+1) else return true
            KeyEvent.KEYCODE_DPAD_UP->if(row>0)choose(if(row==4)24+col*3 else selected-8) else return leave(View.FOCUS_UP)
            KeyEvent.KEYCODE_DPAD_DOWN->if(row<4)choose(if(row==3)32+(col*3/8) else selected+8) else return leave(View.FOCUS_DOWN)
            KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER->{if(event.repeatCount==0 || label(selected)=="⌫")performClick()}
            KeyEvent.KEYCODE_DEL->{erase();return true}
            else->return super.onKeyDown(keyCode,event)
        }
        return true
    }
    override fun onKeyUp(keyCode:Int,event:KeyEvent):Boolean =
        if(keyCode==KeyEvent.KEYCODE_DPAD_CENTER || keyCode==KeyEvent.KEYCODE_ENTER || keyCode==KeyEvent.KEYCODE_DEL)true else super.onKeyUp(keyCode,event)
    override fun performClick():Boolean {
        super.performClick()
        when(val key=label(selected)) {
            "⌫"->erase();"&123","ABC"->{numeric=!numeric;describe();invalidate()};"⌨"->systemKeyboard()
            "Space"->insert(" ");"Clear"->clear();"Search"->search();"·"->{};else->insert(key.lowercase())
        }
        return true
    }
    override fun onTouchEvent(event:MotionEvent):Boolean {
        if(event.action==MotionEvent.ACTION_DOWN){requestFocus();return true}
        if(event.action==MotionEvent.ACTION_UP){for(i in 0..34){bounds(i,rect);if(rect.contains(event.x,event.y)){choose(i);performClick();break}};return true}
        return super.onTouchEvent(event)
    }
}

internal class YouTubeCard(
    context:Context,private val loader:ArtworkLoader,portrait:Boolean,
    private val frameWidth:Int=context.dp(if(portrait)142 else 228),horizontal:Boolean=false,
):LinearLayout(context) {
    private val image=FixedArtworkView(context).apply { scaleType=ImageView.ScaleType.CENTER_CROP;clipToOutline=true;background=context.round(PANEL);setDuplicateParentStateEnabled(true) }
    private val title=context.text("",14f).apply { maxLines=2;ellipsize=TextUtils.TruncateAt.END }
    private val channel=context.text("",11f,MUTED).apply { maxLines=1;ellipsize=TextUtils.TruncateAt.END }
    private val duration=context.text("",10f).apply { background=context.round(0xCC000000.toInt(),radius=5);pad(3) }
    private var video:YouTubeVideo?=null;private var click:((YouTubeVideo)->Unit)?=null
    private val frameHeight=if(portrait)frameWidth*16/9 else frameWidth*9/16
    init {
        orientation=VERTICAL;pad(4);isFocusable=true;isFocusableInTouchMode=true;isClickable=true;stateListAnimator=null
        if(Build.VERSION.SDK_INT>=26)defaultFocusHighlightEnabled=false
        layoutParams=RecyclerView.LayoutParams(if(horizontal)frameWidth+context.dp(8) else -1,frameHeight+context.dp(69)).apply { marginEnd=context.dp(8);bottomMargin=context.dp(6) }
        image.foreground=StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused),context.round(Color.TRANSPARENT,WHITE));addState(intArrayOf(),context.round(Color.TRANSPARENT))
        }
        add(FrameLayout(context).apply {
            clipToOutline=true;background=context.round(PANEL);setDuplicateParentStateEnabled(true)
            addView(image,FrameLayout.LayoutParams(-1,-1))
            addView(duration,FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM or Gravity.END).apply { setMargins(context.dp(6),context.dp(6),context.dp(6),context.dp(6)) })
        },height=frameHeight)
        gap(6);add(title,height=context.dp(34));gap(3);add(channel,height=context.dp(16))
        setOnClickListener { video?.let { click?.invoke(it) } }
    }
    fun bind(value:YouTubeVideo,open:(YouTubeVideo)->Unit) {
        click=open;if(video==value)return
        if(id==View.NO_ID)id=View.generateViewId()
        video=value;tag="youtube-video:${value.id}";contentDescription=if(value.channel.isBlank())value.name else "${value.name}, ${value.channel}"
        title.textIfChanged(value.name);channel.textIfChanged(listOf(value.channel,value.metadata).filter(String::isNotBlank).joinToString(" · "))
        duration.textIfChanged(if(value.live)"LIVE" else value.duration.ifBlank { if(value.isShort)"Shorts" else "" })
        duration.visibility=if(duration.text.isNotEmpty())View.VISIBLE else View.GONE;image.refreshDrawableState()
        loader.show(image,value.thumbnail,frameWidth,frameHeight)
    }
    fun release(){image.dispose();image.setImageDrawable(null);image.tag=null;video=null;click=null}
}

internal class YouTubeShelfView(
    context:Context,loader:ArtworkLoader,pool:RecyclerView.RecycledViewPool,
    private val kind:Int,private val play:(YouTubeVideo,List<YouTubeVideo>)->Unit,
):LinearLayout(context) {
    var shelfKey="";private set
    private var items:List<YouTubeVideo> = emptyList()
    private val heading=context.text("",19f,bold=true).apply { maxLines=1;ellipsize=TextUtils.TruncateAt.END }
    val list=RecyclerView(context)
    private val width=context.dp(when(kind){2->128;1->288;else->218})
    private val select:(YouTubeVideo)->Unit={play(it,items)}
    private val adapter=NativeAdapter<YouTubeVideo>({it.id},{parent,_->YouTubeCard(parent.context,loader,kind==2,width,true)},
        {view,value,_->(view as YouTubeCard).bind(value,select)},type={10+kind},recycle={(it as YouTubeCard).release()})
    init {
        orientation=VERTICAL;layoutParams=RecyclerView.LayoutParams(-1,-2).apply { bottomMargin=context.dp(16) }
        add(heading);gap(9)
        list.layoutManager=LinearLayoutManager(context,RecyclerView.HORIZONTAL,false).apply { initialPrefetchItemCount=1 }
        list.setRecycledViewPool(pool);list.tune(cachedViews=1);list.adapter=adapter
        add(list,height=(if(kind==2)width*16/9 else width*9/16)+context.dp(75))
    }
    fun bind(row:YouTubeShelf,state:Parcelable?) {
        val changed=shelfKey!=row.key;shelfKey=row.key;items=row.videos;tag="youtube-shelf:${row.key}"
        heading.textIfChanged(row.name);list.contentDescription=row.name
        if(list.adapter==null)list.adapter=adapter
        if(changed)list.stopScroll()
        adapter.submitList(items) { if(changed && shelfKey==row.key) { if(state==null)list.scrollToPosition(0) else list.layoutManager?.onRestoreInstanceState(state) } }
    }
    fun saveState():Parcelable?=list.layoutManager?.onSaveInstanceState()
    fun focusVideo(tag:String):View? {
        if(adapter.currentList!=items)return null
        val index=items.indexOfFirst{"youtube-video:${it.id}"==tag};if(index<0)return null
        return list.findTag(tag)?:run { list.scrollToPosition(index);null }
    }
    fun release(){for(i in 0 until list.childCount)(list.getChildAt(i) as? YouTubeCard)?.release();list.adapter=null}
}
