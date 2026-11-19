package xyz.billybishop4.fleet.tv

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout

/** One outgoing surface may retain its last picture for 210 ms, after its
 * decoder has been released. The incoming clip prepares immediately; there is
 * never a second player, frame copy, or transition bitmap. */
internal class ShortsTransition(private val root:FrameLayout) {
    private var outgoing:View?=null
    private var incoming:View?=null
    private var direction=1
    private var startedAt=0L
    private val tick=object:Runnable {
        override fun run() {
            val old=outgoing?:return
            val next=incoming?:return
            val now=SystemClock.uptimeMillis()
            // Start on a drawable frame, rather than spending the transition's
            // duration in the incoming player's first measure/surface setup.
            if(startedAt==0L)startedAt=now
            val elapsed=(now-startedAt).coerceAtLeast(0L)
            val t=(elapsed/210f).coerceIn(0f,1f)
            val eased=1f-(1f-t)*(1f-t)*(1f-t)
            val distance=root.height.toFloat()
            old.translationY=-direction*distance*eased
            next.translationY=direction*distance*(1f-eased)
            if(t<1f)root.postOnAnimation(this) else cancel()
        }
    }
    fun start(old:View,next:View,sign:Int) {
        cancel()
        outgoing=old;incoming=next;direction=if(sign<0)-1 else 1
        old.tag="shorts-outgoing-frame"
        old.descendantFocusabilityIfGroup()
        old.clearFocus();old.isFocusable=false;old.importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        next.translationY=direction*root.height.toFloat()
        startedAt=0L;root.postOnAnimation(tick)
    }
    fun cancel() {
        root.removeCallbacks(tick)
        outgoing?.let { old->old.translationY=0f;(old.parent as? ViewGroup)?.removeView(old) }
        incoming?.let { it.translationY=0f;it.alpha=1f }
        outgoing=null;incoming=null
    }
    private fun View.descendantFocusabilityIfGroup() {
        if(this is ViewGroup)descendantFocusability=ViewGroup.FOCUS_BLOCK_DESCENDANTS
    }
}
