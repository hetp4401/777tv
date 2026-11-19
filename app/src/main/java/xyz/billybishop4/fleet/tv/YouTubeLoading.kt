package xyz.billybishop4.fleet.tv

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import coil.dispose

/** No loading card flashes between clips. Artwork is bounded by the shared cache,
 * and rebuffering keeps the decoder's existing surface/frame in place. */
internal class YouTubeLoading(a:MainActivity,watch:Watch):FrameLayout(a) {
    private val portrait=watch.short
    private val cover=FixedArtworkView(a).apply {
        scaleType=if(portrait)ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
        setBackgroundColor(Color.BLACK)
        importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val spinner=ProgressBar(a).apply {
        tag="youtube-loading-indicator"
        indeterminateTintList=ColorStateList.valueOf(Color.WHITE)
        visibility=View.GONE
        contentDescription="Loading video"
        importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }
    private var hasFrame=false
    private var waiting=true
    private var closed=false
    private val reveal=Runnable { if(!closed && waiting)spinner.visibility=View.VISIBLE }
    init {
        tag="youtube-loading";isFocusable=false;isClickable=false
        addView(cover,LayoutParams(if(portrait)a.resources.displayMetrics.heightPixels*9/16 else -1,-1,Gravity.CENTER))
        addView(spinner,LayoutParams(a.dp(36),a.dp(36),Gravity.CENTER))
        a.artwork.show(cover,watch.title.poster,if(watch.short)360 else 960,if(watch.short)640 else 540)
        postDelayed(reveal,650)
    }
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int) {
        super.onSizeChanged(w,h,oldw,oldh)
        // Keep the thumbnail in the same portrait viewport as the clip. A
        // landscape/square source image must not flash across the letterboxes.
        val desired=minOf(w,h*9/16)
        if(portrait && desired>0 && cover.layoutParams.width!=desired)
            cover.layoutParams=cover.layoutParams.apply{width=desired}
    }
    fun buffering(value:Boolean) {
        if(closed)return
        // READY may precede the first rendered video frame. Keep the initial
        // poster/indicator until this attachment actually produces its frame.
        val next=value || !hasFrame
        if(next==waiting)return
        waiting=next;removeCallbacks(reveal)
        spinner.visibility=View.GONE
        if(waiting)postDelayed(reveal,900)
    }
    fun firstFrame() {
        if(closed)return
        hasFrame=true;waiting=false;removeCallbacks(reveal);spinner.visibility=View.GONE
        cover.visibility=View.GONE;cover.dispose();cover.setImageDrawable(null)
    }
    fun close() {
        if(closed)return
        closed=true;waiting=false;removeCallbacks(reveal)
        spinner.visibility=View.GONE;cover.dispose();cover.setImageDrawable(null)
    }
}
