package xyz.billybishop4.fleet.tv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import android.widget.SeekBar

/** One cached path above the existing seek track. No timer, bitmap, extra hit target or layout height. */
internal class YouTubeHeatmapView(context:Context,private val track:SeekBar):View(context) {
    private val path=Path()
    private val fill=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=(ACCENT and 0x00FFFFFF) or 0x38000000 }
    private val outline=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=(ACCENT and 0x00FFFFFF) or 0x70000000;style=Paint.Style.STROKE;strokeWidth=context.dp(1).toFloat() }
    private var markers:List<YouTubeHeatMarker> = emptyList()
    private var durationMs=0L
    private var dirty=true
    init { tag="youtube-replay-heatmap";visibility=GONE;isFocusable=false;importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO;contentDescription="Most replayed sections" }
    fun markers(value:List<YouTubeHeatMarker>) { markers=value.take(256);dirty=true;visibility();invalidate() }
    fun duration(value:Long) { if(durationMs!=value){durationMs=value;dirty=true;visibility();invalidate()} }
    private fun visibility(){visibility=if(durationMs>0 && markers.any { it.startMs<durationMs })VISIBLE else GONE}
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int){dirty=true}
    override fun onDraw(canvas:Canvas) {
        super.onDraw(canvas)
        if(dirty) {
            dirty=false;path.rewind()
            val left=track.paddingLeft.toFloat();val right=(width-track.paddingRight).toFloat()
            val baseline=(track.paddingTop+track.height-track.paddingBottom)/2f-context.dp(1)
            val amplitude=minOf(context.dp(13).toFloat(),baseline.coerceAtLeast(0f))
            if(durationMs<=0 || right<=left)return
            fun x(time:Double)=left+(right-left)*(time/durationMs).coerceIn(0.0,1.0).toFloat()
            var end=-1L
            for(marker in markers) {
                if(marker.startMs>=durationMs)break
                if(end<0 || marker.startMs>end) {
                    if(end>=0){path.lineTo(x(end.toDouble()),baseline);path.close()}
                    path.moveTo(x(marker.startMs.toDouble()),baseline)
                }
                path.lineTo(x(marker.startMs+marker.durationMs/2.0),baseline-amplitude*marker.intensity)
                end=maxOf(end,(marker.startMs+marker.durationMs).coerceAtMost(durationMs))
            }
            if(end>=0){path.lineTo(x(end.toDouble()),baseline);path.close()}
        }
        canvas.drawPath(path,fill);canvas.drawPath(path,outline)
    }
}
