package xyz.billybishop4.fleet.tv

import android.content.Context
import android.graphics.Matrix
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import coil.request.CachePolicy
import coil.request.Disposable
import coil.request.ImageRequest
import coil.size.Precision
import coil.size.Scale

/** Seeking previews are optional artwork, not another player or decoded frame
 * cache. One cropped sprite reuses the app's bounded Coil memory budget. */
internal class YouTubeSeekPreview(private val a:MainActivity,watch:Watch) {
    private val storyboard=watch.youtubeStoryboard
    private val main=Handler(Looper.getMainLooper())
    private val image=StoryboardSpriteView(a).apply { tag="youtube-seek-preview-image";visibility=View.INVISIBLE }
    private val imageFrame=FrameLayout(a).apply {
        background=a.round(0xFF141414.toInt(),0xCCFFFFFF.toInt(),8);clipToOutline=true
        addView(image,FrameLayout.LayoutParams(-1,-1))
        visibility=View.GONE
    }
    private val time=a.text("",15f,0xFFFFFFFF.toInt(),true).apply {
        tag="youtube-seek-preview-time";gravity=Gravity.CENTER
        background=a.round(0xB3000000.toInt(),radius=6);setPadding(a.dp(9),a.dp(4),a.dp(9),a.dp(4))
    }
    private val chapter=a.text("",12f,0xFFE2E2E2.toInt()).apply {
        tag="youtube-seek-preview-chapter";gravity=Gravity.CENTER;maxLines=1;ellipsize=TextUtils.TruncateAt.END;visibility=View.GONE
        setShadowLayer(a.dp(2).toFloat(),0f,a.dp(1).toFloat(),0xE6000000.toInt())
    }
    val view=FrameLayout(a).apply {
        tag="youtube-seek-preview";visibility=View.GONE;isFocusable=false;isClickable=false
        addView(a.column().apply {
            gravity=Gravity.CENTER_HORIZONTAL;add(imageFrame,width=a.dp(200),height=a.dp(112));gap(8)
            add(time,width=-2);gap(4);add(chapter,width=a.dp(230))
        },FrameLayout.LayoutParams(-2,-2,Gravity.CENTER))
    }
    private var closed=false
    private var generation=0
    private var desired:YouTubeStoryboardFrame?=null
    private var loadedUrl:String?=null
    private var failedUrl:String?=null
    private var scheduledUrl:String?=null
    private var request:Disposable?=null
    private val load=Runnable { loadSheet() }

    fun show(positionMs:Long,durationMs:Long,chapter:String="") {
        if(closed)return
        view.visibility=View.VISIBLE
        val at=positionMs.coerceAtLeast(0).let{if(durationMs>0)it.coerceAtMost(durationMs) else it}
        time.textIfChanged(format(at));this.chapter.textIfChanged(chapter.take(160))
        this.chapter.visibility=if(chapter.isBlank())View.GONE else View.VISIBLE
        val frame=storyboard?.frame(at)
        desired=frame
        if(frame==null){imageFrame.visibility=View.GONE;return}
        if(failedUrl==frame.url){imageFrame.visibility=View.GONE;return}
        imageFrame.visibility=View.VISIBLE
        if(loadedUrl==frame.url && image.drawable!=null) { image.frame(frame);return }
        if(scheduledUrl==frame.url)return
        generation++;main.removeCallbacks(load);request?.dispose();request=null
        loadedUrl=null;failedUrl=null;image.clear();image.visibility=View.INVISIBLE;scheduledUrl=frame.url
        main.postDelayed(load,80)
    }
    private fun loadSheet() {
        val frame=desired?:return
        if(closed || view.visibility!=View.VISIBLE || frame.url!=scheduledUrl)return
        val expected=generation;val url=frame.url
        request=a.artwork.loader.enqueue(ImageRequest.Builder(a).data(url).size(frame.sheetWidth,frame.sheetHeight)
            .precision(Precision.EXACT).scale(Scale.FIT).crossfade(false).allowHardware(true)
            .diskCachePolicy(CachePolicy.DISABLED)
            .target(onSuccess={ drawable->
                if(!closed && generation==expected && view.visibility==View.VISIBLE && desired?.url==url) {
                    loadedUrl=url;image.setImageDrawable(drawable);image.visibility=View.VISIBLE
                    desired?.let{image.frame(it)}
                }
            },onError={
                if(generation==expected){failedUrl=url;image.clear();image.visibility=View.INVISIBLE;imageFrame.visibility=View.GONE}
            }).build())
    }
    fun hide() {
        generation++;main.removeCallbacks(load);request?.dispose();request=null
        desired=null;loadedUrl=null;failedUrl=null;scheduledUrl=null;image.clear();image.visibility=View.INVISIBLE
        imageFrame.visibility=View.GONE;view.visibility=View.GONE
    }
    fun close(){if(closed)return;closed=true;hide()}
    private fun format(ms:Long):String {
        val seconds=ms/1000
        fun two(value:Long)=if(value<10)"0$value" else value.toString()
        return if(seconds>=3600)"${seconds/3600}:${two(seconds/60%60)}:${two(seconds%60)}" else "${seconds/60}:${two(seconds%60)}"
    }
}

/** Matrix cropping changes only the displayed cell. The original contact-sheet
 * drawable is retained once; no Bitmap.createBitmap or per-cell image is made. */
private class StoryboardSpriteView(context:Context):ImageView(context) {
    private val crop=Matrix()
    private var cell:YouTubeStoryboardFrame?=null
    init { scaleType=ScaleType.MATRIX;adjustViewBounds=false;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO }
    fun frame(value:YouTubeStoryboardFrame){cell=value;position()}
    fun clear(){cell=null;setImageDrawable(null)}
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int){super.onSizeChanged(w,h,oldw,oldh);position()}
    private fun position() {
        val frame=cell?:return;val sheet=drawable?:return
        if(width<=0 || height<=0 || sheet.intrinsicWidth<=0 || sheet.intrinsicHeight<=0)return
        val cellWidth=sheet.intrinsicWidth.toFloat()/frame.columns
        val cellHeight=cellWidth*frame.height/frame.width
        if((frame.row+1)*cellHeight>sheet.intrinsicHeight+1){visibility=View.INVISIBLE;return}
        val scale=maxOf(width/cellWidth,height/cellHeight)
        crop.setScale(scale,scale)
        crop.postTranslate(-frame.column*cellWidth*scale+(width-cellWidth*scale)/2,
            -frame.row*cellHeight*scale+(height-cellHeight*scale)/2)
        imageMatrix=crop;visibility=View.VISIBLE
    }
}
