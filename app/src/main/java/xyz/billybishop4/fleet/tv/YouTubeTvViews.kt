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
    private val palette=AppTheme.palette
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG);private val rect=RectF()
    private val radius=context.dp(10).toFloat();private val textPixels=context.dp(15).toFloat()
    private var numeric=false;private var selected=0
    init {
        id=View.generateViewId();tag="youtube-keyboard";isFocusable=true;isFocusableInTouchMode=true;isClickable=true
        if(Build.VERSION.SDK_INT>=26)defaultFocusHighlightEnabled=false
        describe()
    }
    private fun label(index:Int)=if(index<32)(if(numeric)numbers else letters)[index] else actions[index-32]
    private fun describe(){contentDescription="YouTube keyboard, ${when(val key=label(selected)){"⌫"->"Delete";"⌨"->"System keyboard";else->key}}"}
    private fun choose(index:Int) { selected=index.coerceIn(0,34).let{if(it==31)30 else it};describe();invalidate() }
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
        paint.typeface=Typeface.DEFAULT;paint.textAlign=Paint.Align.CENTER;paint.textSize=textPixels
        val baseline=-(paint.ascent()+paint.descent())/2
        for(i in 0..34) {
            if(i==31)continue // The final alphabet row has seven real keys, not a focusable spacer.
            bounds(i,rect);val focused=hasFocus() && i==selected
            paint.color=if(focused)palette.accent else palette.surface;canvas.drawRoundRect(rect,radius,radius,paint)
            paint.color=if(focused)palette.onAccent else palette.text
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
        if(event.action==MotionEvent.ACTION_UP){for(i in 0..34){if(i==31)continue;bounds(i,rect);if(rect.contains(event.x,event.y)){choose(i);performClick();break}};return true}
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
    private val resume=YouTubeResumeProgress(context)
    private var video:YouTubeVideo?=null;private var click:((YouTubeVideo)->Unit)?=null
    private val frameHeight=if(portrait)frameWidth*16/9 else frameWidth*9/16
    init {
        orientation=VERTICAL;pad(4);isFocusable=true;isFocusableInTouchMode=true;isClickable=true;stateListAnimator=null
        if(Build.VERSION.SDK_INT>=26)defaultFocusHighlightEnabled=false
        layoutParams=RecyclerView.LayoutParams(if(horizontal)frameWidth+context.dp(8) else -1,frameHeight+context.dp(69)).apply { marginEnd=context.dp(8);bottomMargin=context.dp(6) }
        image.foreground=StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused),context.round(Color.TRANSPARENT,FOCUS));addState(intArrayOf(),context.round(Color.TRANSPARENT))
        }
        add(FrameLayout(context).apply {
            clipToOutline=true;background=context.round(PANEL);setDuplicateParentStateEnabled(true)
            addView(image,FrameLayout.LayoutParams(-1,-1))
            addView(resume,FrameLayout.LayoutParams(-1,context.dp(3),Gravity.BOTTOM))
            addView(duration,FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM or Gravity.END).apply { setMargins(context.dp(6),context.dp(6),context.dp(6),context.dp(6)) })
        },height=frameHeight)
        gap(6);add(title,height=context.dp(34));gap(3);add(channel,height=context.dp(16))
        setOnClickListener { video?.let { click?.invoke(it) } }
        // The TV remote's held OK/Menu opens creator and account actions without another card button.
        setOnLongClickListener {
            val a=context as? MainActivity;val value=video
            if(a!=null && value!=null){YouTubeVideoActions(a,value).show();true}else false
        }
        setOnKeyListener { _,key,event->
            if(key==KeyEvent.KEYCODE_MENU){if(event.action==KeyEvent.ACTION_DOWN && event.repeatCount==0)performLongClick();true}else false
        }
    }
    fun bind(value:YouTubeVideo,open:(YouTubeVideo)->Unit) {
        click=open;if(video==value)return
        if(id==View.NO_ID)id=View.generateViewId()
        video=value;tag="youtube-video:${value.id}";contentDescription=if(value.channel.isBlank())value.name else "${value.name}, ${value.channel}"
        title.textIfChanged(value.name);channel.textIfChanged(listOf(value.channel,value.metadata).filter(String::isNotBlank).joinToString(" · "))
        duration.textIfChanged(if(value.live)"LIVE" else value.duration.ifBlank { if(value.isShort)"Shorts" else "" })
        duration.visibility=if(duration.text.isNotEmpty())View.VISIBLE else View.GONE;image.refreshDrawableState()
        resume.bind(value)
        loader.show(image,value.thumbnail,frameWidth,frameHeight)
    }
    fun release(){image.dispose();image.setImageDrawable(null);image.tag=null;resume.visibility=View.GONE;video=null;click=null}
}

/** A static watched-progress line; no animator, handler, bitmap or playback preparation. */
internal class YouTubeResumeProgress(context:Context):View(context) {
    private val paint=Paint()
    private val palette=AppTheme.palette
    private var fraction=0f
    init { tag="youtube-resume-progress";visibility=GONE;importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO }
    fun bind(video:YouTubeVideo) {
        val position=video.resumePositionMs?:0
        val percent=video.watchedPercent?.takeIf{it in 0..100}
        if(video.live || video.isShort || position<=0 && (percent==null || percent==0)) {
            fraction=0f;visibility=GONE;return
        }
        // Ordinary unwatched cards bypass duration parsing entirely.
        val totalMs=if(position>0)durationMs(video.duration) else 0L
        fraction=if(position>0 && totalMs>0)(position.toDouble()/totalMs).coerceIn(0.0,1.0).toFloat()
            else percent?.div(100f)?:0f
        visibility=if(fraction>0f)VISIBLE else GONE
        if(fraction>0f)invalidate()
    }
    private fun durationMs(duration:String):Long {
        val parts=duration.trim().split(':')
        val times=parts.map{it.toLongOrNull()}
        val valid=parts.size in 2..3 && times.all{it!=null && it>=0} && times.drop(1).all{it!!<60} &&
            parts.all{it.length in 1..3}
        return if(valid)times.fold(0L){total,part->total*60+part!!}*1000 else 0L
    }
    override fun onDraw(canvas:Canvas) {
        paint.color=0xAA000000.toInt();canvas.drawRect(0f,0f,width.toFloat(),height.toFloat(),paint)
        paint.color=palette.accent;canvas.drawRect(0f,0f,width*fraction,height.toFloat(),paint)
    }
}

internal class YouTubeShelfView(
    context:Context,loader:ArtworkLoader,pool:RecyclerView.RecycledViewPool,
    private val kind:Int,private val play:(YouTubeVideo,List<YouTubeVideo>)->Unit,
    private val end:((String,String)->Unit)?=null,
):LinearLayout(context) {
    var shelfKey="";private set
    var stateKey="";private set
    private var bound=false
    private var items:List<YouTubeVideo> = emptyList()
    private val heading=context.text("",19f,bold=true).apply { maxLines=1;ellipsize=TextUtils.TruncateAt.END }
    val list=TvRecyclerView(context)
    private val width=context.dp(when(kind){2->128;1->288;else->218})
    private val select:(YouTubeVideo)->Unit={play(it,items)}
    private val adapter=NativeAdapter<YouTubeVideo>({it.id},{parent,_->YouTubeCard(parent.context,loader,kind==2,width,true)},
        {view,value,_->(view as YouTubeCard).bind(value,select)},type={10+kind},recycle={(it as YouTubeCard).release()})
    init {
        orientation=VERTICAL;layoutParams=RecyclerView.LayoutParams(-1,-2).apply { bottomMargin=context.dp(16) }
        add(heading);gap(9)
        val slot=width+context.dp(16)
        val viewport=(context.resources.displayMetrics.widthPixels-context.dp(48+64)).coerceAtLeast(slot)
        val prefetch=((viewport+slot-1)/slot).coerceIn(2,if(kind==2)8 else 6)
        list.layoutManager=LinearLayoutManager(context,RecyclerView.HORIZONTAL,false).apply { initialPrefetchItemCount=prefetch }
        list.setRecycledViewPool(pool);list.tune(cachedViews=2);list.adapter=adapter
        if(end!=null)list.addOnScrollListener(object:RecyclerView.OnScrollListener(){override fun onScrolled(recyclerView:RecyclerView,dx:Int,dy:Int) {
            if(isShown && list.adapter===adapter && adapter.currentList==items && items.isNotEmpty() &&
                (list.layoutManager as LinearLayoutManager).findLastVisibleItemPosition()>=items.lastIndex-2)end.invoke(shelfKey,stateKey)
        }})
        add(list,height=(if(kind==2)width*16/9 else width*9/16)+context.dp(75))
    }
    fun bind(row:YouTubeShelf,state:Parcelable?,identity:String=row.key) {
        val changed=!bound || stateKey!=identity;bound=true;shelfKey=row.key;stateKey=identity;items=row.videos;tag="youtube-shelf:${row.key}"
        heading.textIfChanged(row.name);list.contentDescription=row.name
        if(list.adapter==null)list.adapter=adapter
        if(changed)list.stopScroll()
        adapter.submitList(items) { if(changed && shelfKey==row.key) { if(state==null)list.scrollToPosition(0) else list.layoutManager?.onRestoreInstanceState(state) } }
    }
    fun saveState():Parcelable?=list.layoutManager?.onSaveInstanceState()
    fun attachedVideo(tag:String):View?=if(adapter.currentList!=items || list.adapter!==adapter)null else list.findTag(tag)?.takeIf{it.isShown}
    fun firstVideo():View? {
        if(adapter.currentList!=items || list.adapter!==adapter)return null
        val first=list.getChildAt(0)?:return null
        val position=list.getChildAdapterPosition(first)
        return first.takeIf{position in items.indices && it.tag=="youtube-video:${items[position].id}" && it.isShown}
    }
    fun focusVideo(tag:String):View? {
        if(adapter.currentList!=items)return null
        val index=items.indexOfFirst{"youtube-video:${it.id}"==tag};if(index<0)return null
        return attachedVideo(tag)?:run { list.scrollToPosition(index);null }
    }
    fun release(){for(i in 0 until list.childCount)(list.getChildAt(i) as? YouTubeCard)?.release();list.adapter=null;bound=false;items=emptyList();adapter.submitList(emptyList())}
}

/** Library collections use their actual cover/name; opening a card browses, never starts playback. */
internal class YouTubePlaylistCard(context:Context,private val loader:ArtworkLoader,private val frameWidth:Int):LinearLayout(context) {
    private val fallback=YouTubeRailIcon("Library",context.dp(32))
    private val image=FixedArtworkView(context).apply {
        scaleType=ImageView.ScaleType.CENTER_CROP;clipToOutline=true;background=context.round(PANEL);setDuplicateParentStateEnabled(true)
        foreground=StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused),context.round(Color.TRANSPARENT,FOCUS));addState(intArrayOf(),context.round(Color.TRANSPARENT))
        }
    }
    private val name=context.text("",14f).apply{maxLines=2;ellipsize=TextUtils.TruncateAt.END}
    private val metadata=context.text("",11f,MUTED).apply{maxLines=1;ellipsize=TextUtils.TruncateAt.END}
    init {
        orientation=VERTICAL;pad(4);isFocusable=true;isFocusableInTouchMode=true;isClickable=true;stateListAnimator=null
        if(Build.VERSION.SDK_INT>=26)defaultFocusHighlightEnabled=false
        layoutParams=RecyclerView.LayoutParams(-1,frameWidth*9/16+context.dp(69)).apply{marginEnd=context.dp(8);bottomMargin=context.dp(6)}
        add(image,height=frameWidth*9/16);gap(6);add(name,height=context.dp(34));gap(3);add(metadata,height=context.dp(16))
    }
    fun bind(value:YouTubePlaylist,open:(YouTubePlaylist)->Unit) {
        if(id==View.NO_ID)id=View.generateViewId()
        tag="youtube-playlist-card:${value.id}";contentDescription="Open playlist: ${value.name}"
        name.textIfChanged(value.name);metadata.textIfChanged(value.metadata.ifBlank{"Playlist"})
        image.scaleType=if(value.thumbnail.isBlank())ImageView.ScaleType.CENTER else ImageView.ScaleType.CENTER_CROP
        if(value.thumbnail.isBlank()){image.dispose();image.setImageDrawable(fallback)}else loader.show(image,value.thumbnail,frameWidth,frameWidth*9/16)
        setOnClickListener{open(value)}
    }
    fun release(){image.dispose();image.setImageDrawable(null);setOnClickListener(null);contentDescription=null}
}
