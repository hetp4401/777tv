package xyz.billybishop4.fleet.tv

import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.view.*
import android.widget.*
import coil.dispose

/** YouTube TV's watch-page geometry. This is interface chrome around the existing
 * SurfaceView, not a second player, animated background or effect pipeline. */
internal class YouTubePlayerChrome(
    private val a:MainActivity,watch:Watch,root:FrameLayout,
    timeline:LinearLayout,bar:SeekBar,heatmap:View?,elapsed:TextView,remaining:TextView,
    feedback:TextView,private val play:ImageButton,previous:ImageButton?,next:ImageButton?,
    chapters:ImageButton?,close:ImageButton,related:View,
    channel:()->Unit,description:()->Unit,subscribe:()->Unit,like:()->Unit,dislike:()->Unit,
    comments:()->Unit,save:()->Unit,captions:()->Unit,settings:()->Unit,
) {
    private val ink=0xFFFFFFFF.toInt()
    private val muted=0xFFCDCDCD.toInt()
    private val normalIcon=PorterDuffColorFilter(ink,PorterDuff.Mode.SRC_IN)
    private val focusedIcon=PorterDuffColorFilter(0xFF080808.toInt(),PorterDuff.Mode.SRC_IN)
    private val compact=a.resources.displayMetrics.widthPixels/a.resources.displayMetrics.density<800
    private val size=if(compact)36 else 40
    private var shownAvatar=""
    private val videoMetadata=watch.title.description
    private val avatar=FixedArtworkView(a).apply {
        scaleType=ImageView.ScaleType.CENTER_CROP;background=a.round(0xFF353535.toInt(),radius=24);clipToOutline=true
        importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val creator=a.text("Channel",12f,ink,true).apply { maxLines=1;ellipsize=TextUtils.TruncateAt.END }
    val channelAction=a.row().apply {
        id=View.generateViewId();tag="youtube-channel";contentDescription="View channel";isFocusable=true;isFocusableInTouchMode=true;isClickable=true
        background=focusBackground();gravity=Gravity.CENTER_VERTICAL;setPadding(a.dp(5),0,a.dp(5),0);isEnabled=false;alpha=.6f
        add(avatar,width=a.dp(28),height=a.dp(28));addView(creator,LinearLayout.LayoutParams(0,-2,1f).apply{marginStart=a.dp(7)})
        setOnClickListener{channel()}
    }
    val descriptionAction=button("Description","youtube-description",YouTubePlayerIcon.INFO,description)
    val subscribeAction=Button(a).apply {
        id=View.generateViewId();tag="youtube-subscribe";text="Subscribe";textSize=11f;isAllCaps=false;setSingleLine(true)
        ellipsize=TextUtils.TruncateAt.END;minWidth=0;minimumWidth=0;minHeight=0;minimumHeight=0;setPadding(a.dp(8),0,a.dp(8),0)
        setTextColor(textColors());background=focusBackground(selectedFill=true);stateListAnimator=null
        setOnClickListener{subscribe()}
    }
    val likeAction=button("Like","youtube-like",YouTubePlayerIcon.LIKE,like)
    val dislikeAction=button("Dislike","youtube-dislike",YouTubePlayerIcon.DISLIKE,dislike)
    val commentsAction=button("Video comments","youtube-comments",YouTubePlayerIcon.COMMENTS,comments)
    val saveAction=button("Save video","youtube-save",YouTubePlayerIcon.SAVE,save)
    val captionsAction=button("Captions","youtube-captions",YouTubePlayerIcon.CAPTIONS,captions)
    val settingsAction=button("Settings","youtube-settings",YouTubePlayerIcon.SETTINGS,settings)
    private val label=a.text("",11f,muted).apply { tag="youtube-focused-action";maxLines=1;ellipsize=TextUtils.TruncateAt.END }
    private val subtitle=a.text(watch.title.description,12f,muted).apply { maxLines=1;ellipsize=TextUtils.TruncateAt.END }
    val row=FrameLayout(a).apply { tag="youtube-controls-row";clipChildren=false;clipToPadding=false }
    private val transport=listOfNotNull(previous,play,next)
    private val engagement=listOf(likeAction,dislikeAction,commentsAction,saveAction,captionsAction,settingsAction)
    val actions:List<View> = listOf(channelAction,descriptionAction,subscribeAction)+transport+engagement
    private val focus=ViewTreeObserver.OnGlobalFocusChangeListener { _,focused ->
        val text=when {
            focused===bar->"◀ / ▶ Seek · Hold to move faster"
            focused===play->play.contentDescription.toString()
            focused===subscribeAction->subscribeAction.text.toString()
            focused in actions->focused?.contentDescription?.toString().orEmpty()
            else->""
        }
        label.textIfChanged(text)
        creator.setTextColor(if(channelAction.hasFocus())0xFF080808.toInt() else ink)
    }
    init {
        root.addView(View(a).apply { importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
            background=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,intArrayOf(0xA6000000.toInt(),Color.TRANSPARENT))
        },FrameLayout.LayoutParams(-1,a.dp(120),Gravity.TOP))
        root.addView(View(a).apply { importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
            background=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,intArrayOf(Color.TRANSPARENT,0xE6000000.toInt()))
        },FrameLayout.LayoutParams(-1,a.dp(210),Gravity.BOTTOM))
        val title=a.column().apply {
            add(a.text(watch.title.name,20f,ink,true).apply { tag="vod-title";maxLines=2;ellipsize=TextUtils.TruncateAt.END })
            gap(4);add(subtitle);gap(4);add(feedback)
        }
        root.addView(title,FrameLayout.LayoutParams(-1,-2,Gravity.TOP).apply{setMargins(a.dp(40),a.dp(24),a.dp(100),0)})
        style(close);root.addView(close,FrameLayout.LayoutParams(a.dp(40),a.dp(40),Gravity.TOP or Gravity.END).apply{topMargin=a.dp(20);rightMargin=a.dp(32)})
        val chrome=a.column().apply { tag="youtube-player-chrome" }
        val clockHeight=maxOf(a.dp(24),elapsed.lineHeight,remaining.lineHeight)
        bar.setPadding(a.dp(6),0,a.dp(6),clockHeight)
        timeline.add(FrameLayout(a).apply {
            heatmap?.let{addView(it,FrameLayout.LayoutParams(-1,a.dp(44)))}
            addView(bar,FrameLayout.LayoutParams(-1,a.dp(44)))
            addView(a.row().apply {
                addView(elapsed,LinearLayout.LayoutParams(0,-2,1f))
                chapters?.let{style(it);it.setPadding(a.dp(4),a.dp(3),a.dp(4),a.dp(3));add(it,width=a.dp(30),height=a.dp(24))}
                add(remaining,width=-2)
            },FrameLayout.LayoutParams(-1,clockHeight,Gravity.BOTTOM).apply{leftMargin=a.dp(4);rightMargin=a.dp(4)})
        },height=a.dp(44))
        chrome.add(timeline,height=a.dp(48));chrome.gap(6)
        val left=a.row().apply {
            tag="youtube-channel-controls";gravity=Gravity.CENTER_VERTICAL
            add(channelAction,width=a.dp(if(compact)108 else 126),height=a.dp(44))
            add(descriptionAction,width=a.dp(if(compact)30 else 34),height=a.dp(40))
            add(subscribeAction,width=a.dp(if(compact)76 else 86),height=a.dp(34))
        }
        row.addView(left,FrameLayout.LayoutParams(-2,-1,Gravity.START))
        // Fixed slots keep Play in the middle when Previous is unavailable.
        val center=FrameLayout(a).apply { tag="youtube-transport-controls" }
        for((index,control) in listOf(previous,play,next).withIndex())control?.let {
            style(it);center.addView(it,FrameLayout.LayoutParams(a.dp(44),a.dp(44),Gravity.CENTER_VERTICAL).apply{leftMargin=a.dp(index*44)})
        }
        row.addView(center,FrameLayout.LayoutParams(a.dp(132),-1,Gravity.CENTER))
        val right=a.row().apply { tag="youtube-engagement-controls";gravity=Gravity.CENTER_VERTICAL
            for(action in engagement)add(action,width=a.dp(size),height=a.dp(44))
        }
        row.addView(right,FrameLayout.LayoutParams(-2,-1,Gravity.END))
        chrome.add(row,height=a.dp(48))
        related.apply {
            contentDescription="Related videos"
            if(this is TextView){text="More videos  ⌄";textSize=11f;setTextColor(textColors());background=focusBackground();setPadding(a.dp(10),0,a.dp(10),0)}
        }
        chrome.add(a.row().apply {
            gravity=Gravity.CENTER_VERTICAL;addView(label,LinearLayout.LayoutParams(0,-2,1f));add(related,width=a.dp(124),height=a.dp(26))
        },height=a.dp(26))
        root.addView(chrome,FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM).apply{setMargins(a.dp(40),0,a.dp(40),a.dp(18))})
        root.viewTreeObserver.addOnGlobalFocusChangeListener(focus)
    }
    fun render(state:YouTubePlayerActionState) {
        val channel=state.channel
        channelAction.isEnabled=channel!=null && YouTubeSocialDecode.validChannelId(channel.id)
        channelAction.alpha=if(channelAction.isEnabled)1f else .6f
        creator.textIfChanged(channel?.name?.ifBlank{"Channel"}?:"Channel")
        if(channel!=null && videoMetadata.isBlank())subtitle.textIfChanged(listOf(channel.name,channel.subscriberText).filter(String::isNotBlank).joinToString(" · "))
        val image=channel?.thumbnail.orEmpty()
        if(shownAvatar!=image){shownAvatar=image;avatar.dispose();avatar.tag=null;avatar.setImageDrawable(null);if(image.isNotBlank())a.artwork.show(avatar,image,a.dp(28),a.dp(28))}
        val subscribed=state.signedIn && channel?.subscribed==true
        subscribeAction.textIfChanged(if(subscribed)"Subscribed" else "Subscribe")
        subscribeAction.contentDescription=if(subscribed)"Unsubscribe from channel" else "Subscribe to channel"
        subscribeAction.isSelected=subscribed
        likeAction.isSelected=state.signedIn && state.rating==YouTubeRating.LIKE
        dislikeAction.isSelected=state.signedIn && state.rating==YouTubeRating.DISLIKE
        likeAction.contentDescription=if(likeAction.isSelected)"Remove like" else "Like"
        dislikeAction.contentDescription=if(dislikeAction.isSelected)"Remove dislike" else "Dislike"
        for(action in listOf(subscribeAction,likeAction,dislikeAction,saveAction))action.alpha=if(state.busy).55f else 1f
        if(android.os.Build.VERSION.SDK_INT>=30){likeAction.stateDescription=if(likeAction.isSelected)"Liked" else "Not liked";dislikeAction.stateDescription=if(dislikeAction.isSelected)"Disliked" else "Not disliked"}
        refreshLabel()
    }
    private fun refreshLabel(){
        val focused=actions.firstOrNull{it.hasFocus()}?:return
        val text=when(focused){subscribeAction->subscribeAction.text.toString();captionsAction->"Captions · "+(if(captionsAction.isSelected)"On" else "Off");else->focused.contentDescription.toString()}
        label.textIfChanged(text)
    }
    fun playbackChanged(){if(play.hasFocus())label.textIfChanged(play.contentDescription.toString())}
    fun captions(selected:Boolean){captionsAction.isSelected=selected;if(android.os.Build.VERSION.SDK_INT>=30)captionsAction.stateDescription=if(selected)"On" else "Off";refreshLabel()}
    fun close(root:View){avatar.dispose();avatar.tag=null;avatar.setImageDrawable(null);if(root.viewTreeObserver.isAlive)root.viewTreeObserver.removeOnGlobalFocusChangeListener(focus)}
    private fun button(label:String,tag:String,icon:Int,action:()->Unit)=ImageButton(a).apply {
        id=View.generateViewId();contentDescription=label;this.tag=tag;setImageDrawable(YouTubePlayerIcon(icon));style(this)
        setOnClickListener{action()}
    }
    private fun style(view:ImageButton) { view.isFocusable=true;view.isFocusableInTouchMode=true;view.scaleType=ImageView.ScaleType.FIT_CENTER
        view.setPadding(a.dp(10),a.dp(10),a.dp(10),a.dp(10));view.imageTintList=null;view.background=focusBackground();view.stateListAnimator=null
        // Canvas drawables implement setColorFilter, while Drawable's default
        // setTintList is a no-op. Apply the shared filters explicitly so a focused
        // icon stays dark and legible on its white circle on every supported API.
        view.setColorFilter(if(view.hasFocus())focusedIcon else normalIcon)
        view.setOnFocusChangeListener{_,focused->view.setColorFilter(if(focused)focusedIcon else normalIcon)}
    }
    private fun textColors()=ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused),intArrayOf()),intArrayOf(0xFF080808.toInt(),ink))
    private fun focusBackground(selectedFill:Boolean=false)=StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused),a.round(ink,radius=24))
        addState(intArrayOf(android.R.attr.state_pressed),a.round(0xFFBDBDBD.toInt(),radius=24))
        addState(intArrayOf(android.R.attr.state_selected),a.round(0x554F4F4F,radius=24))
        addState(intArrayOf(),a.round(if(selectedFill)0xFF383838.toInt() else Color.TRANSPARENT,radius=24))
    }
}

/** Cached vector paths; drawing icons never loads a font or bitmap. */
private class YouTubePlayerIcon(private val mode:Int):Drawable() {
    companion object { const val INFO=1;const val LIKE=2;const val DISLIKE=3;const val COMMENTS=4;const val SAVE=5;const val CAPTIONS=6;const val SETTINGS=7 }
    private val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.WHITE;strokeWidth=1.8f;strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND}
    private val path=Path()
    override fun draw(c:Canvas) {
        c.save();c.translate(bounds.left.toFloat(),bounds.top.toFloat());c.scale(bounds.width()/24f,bounds.height()/24f);p.style=Paint.Style.STROKE
        when(mode) {
            INFO->{c.drawCircle(12f,12f,9f,p);c.drawLine(12f,11f,12f,17f,p);p.style=Paint.Style.FILL;c.drawCircle(12f,7f,1f,p)}
            LIKE,DISLIKE->{if(mode==DISLIKE){c.rotate(180f,12f,12f)};c.drawRect(3f,11f,6f,21f,p);path.reset();path.moveTo(9f,21f);path.lineTo(18f,21f);path.lineTo(21f,12f);path.quadTo(21f,10f,18f,10f);path.lineTo(14f,10f);path.lineTo(15f,5f);path.quadTo(14f,2f,12f,4f);path.lineTo(8f,11f);path.lineTo(8f,20f);path.close();c.drawPath(path,p)}
            COMMENTS->{path.reset();path.moveTo(4f,4f);path.lineTo(20f,4f);path.lineTo(20f,17f);path.lineTo(10f,17f);path.lineTo(4f,21f);path.close();c.drawPath(path,p);c.drawLine(8f,9f,16f,9f,p);c.drawLine(8f,13f,14f,13f,p)}
            SAVE->{c.drawLine(3f,6f,15f,6f,p);c.drawLine(3f,11f,15f,11f,p);c.drawLine(3f,16f,10f,16f,p);c.drawLine(17f,13f,17f,21f,p);c.drawLine(13f,17f,21f,17f,p)}
            CAPTIONS->{c.drawRoundRect(2f,5f,22f,19f,2f,2f,p);c.drawArc(5f,8f,10f,16f,45f,270f,false,p);c.drawArc(14f,8f,19f,16f,45f,270f,false,p)}
            SETTINGS->{path.reset();for(i in 0..23){val angle=(i*15-90)*Math.PI/180;val radius=if(i%3==0)10f else 8f;val x=12+radius*kotlin.math.cos(angle).toFloat();val y=12+radius*kotlin.math.sin(angle).toFloat();if(i==0)path.moveTo(x,y)else path.lineTo(x,y)};path.close();c.drawPath(path,p);c.drawCircle(12f,12f,3f,p)}
        }
        c.restore()
    }
    override fun setAlpha(alpha:Int){p.alpha=alpha;invalidateSelf()}
    override fun setColorFilter(filter:ColorFilter?){p.colorFilter=filter;invalidateSelf()}
    @Suppress("DEPRECATION") override fun getOpacity()=PixelFormat.TRANSLUCENT
}
