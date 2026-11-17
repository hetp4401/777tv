@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package xyz.billybishop4.fleet.tv

import androidx.media3.effect.RgbMatrix

/** Per-playback adjustments. Neutral playback never creates an effects pipeline. */
internal data class VideoPicture(val brightness:Int=0,val contrast:Int=0,val saturation:Int=100) {
    val isNeutral:Boolean get()=brightness==0 && contrast==0 && saturation==100
    fun normalized()=VideoPicture(brightness.coerceIn(-50,50),contrast.coerceIn(-50,50),saturation.coerceIn(0,200))
    /** Linear SDR RGB, column-major for OpenGL; alpha is unchanged. */
    fun matrix():FloatArray {
        val value=normalized()
        val c=(1f+value.contrast/100f)/(1f-value.contrast/100f)
        val s=value.saturation/100f
        val offset=value.brightness/100f+.5f*(1f-c)
        val luma=floatArrayOf(.2126f,.7152f,.0722f)
        return FloatArray(16).apply {
            for(column in 0..2)for(row in 0..2)this[column*4+row]=c*((if(column==row)s else 0f)+(1f-s)*luma[column])
            this[12]=offset;this[13]=offset;this[14]=offset;this[15]=1f
        }
    }
}

/** The render thread sees complete immutable matrices, never a half-updated slider value. */
internal class VideoPictureEffect(initial:VideoPicture):RgbMatrix {
    @Volatile private var values=initial.matrix()
    fun update(picture:VideoPicture) { values=picture.matrix() }
    override fun getMatrix(presentationTimeUs:Long,useHdr:Boolean):FloatArray {
        check(!useHdr) { "Picture controls are available for SDR video." }
        return values
    }
}
