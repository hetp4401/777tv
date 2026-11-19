package xyz.billybishop4.fleet.tv

import android.content.Context
import android.view.animation.DecelerateInterpolator
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs

/** Keep native focus/edge handling, with a short scroll that can follow a held
 * remote instead of repeatedly starting RecyclerView's much longer default.
 * There is no key throttling, queued animation or extra drawing layer. */
internal open class TvRecyclerView(context:Context):RecyclerView(context) {
    private val easing=DecelerateInterpolator(1.5f)
    override fun smoothScrollBy(dx:Int,dy:Int) {
        if(dx==0 && dy==0)return
        val distance=maxOf(abs(dx),abs(dy))/resources.displayMetrics.density
        val duration=(65+distance*.18f).toInt().coerceIn(70,140)
        super.smoothScrollBy(dx,dy,easing,duration)
    }
}
