package xyz.billybishop4.fleet.tv

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** A non-modal channel HUD. It never adds transport controls or consumes Back. */
internal class LiveInfoOverlay(private val a:MainActivity,private val watch:Watch) {
    private val main=Handler(Looper.getMainLooper())
    private var closed=false;private var request:Job?=null;private var loadedAt=0L
    private var schedule:List<GuideProgram> = emptyList()
    private val program=a.text("",14f).apply { tag="live-now-program";maxLines=1;ellipsize=TextUtils.TruncateAt.END;visibility=View.GONE }
    val view=FrameLayout(a).apply {
        tag="live-info";isFocusable=false;visibility=View.GONE
        background=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,intArrayOf(Color.TRANSPARENT,0xB3000000.toInt()))
        addView(a.column().apply {
            add(a.text("LIVE",10f,0xFFFF7373.toInt(),bold=true));gap(5)
            add(a.text(watch.channel?.name?:watch.title.name,21f,bold=true).apply { tag="live-channel-name";maxLines=1;ellipsize=TextUtils.TruncateAt.END })
            gap(5);add(program)
        },FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM).apply{setMargins(a.dp(36),0,a.dp(36),a.dp(24))})
    }
    private val hideLater=Runnable { hide() }
    fun show() {
        if(closed)return
        view.visibility=View.VISIBLE;main.removeCallbacks(hideLater);main.postDelayed(hideLater,4000);render()
        val provider=watch.provider?:return;val channel=watch.channel?:return
        if(request?.isActive==true || System.currentTimeMillis()-loadedAt in 0..60_000)return
        request=a.lifecycleScope.launch {
            try { schedule=a.model.repository.guide(provider,channel).filter{it.endMs>System.currentTimeMillis()}.take(8);loadedAt=System.currentTimeMillis();if(!closed && view.isShown)render() }
            catch(e:CancellationException){throw e}
            catch(_:Exception){ /* Missing guide data never obstructs the channel. */ }
        }
    }
    private fun render() {
        val now=System.currentTimeMillis();val current=schedule.firstOrNull{now in it.startMs until it.endMs}
        val next=schedule.firstOrNull{it.startMs>now}
        val label=current?.name?.let{name->name+(next?.let { "  ·  Next: ${it.name}" }?:"")}.orEmpty()
        program.textIfChanged(label);program.visibility=if(label.isEmpty())View.GONE else View.VISIBLE
    }
    fun hide(){main.removeCallbacks(hideLater);view.visibility=View.GONE;request?.cancel();request=null}
    fun close(){closed=true;hide();schedule=emptyList()}
}
