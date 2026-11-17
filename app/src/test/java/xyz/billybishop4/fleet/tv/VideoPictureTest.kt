package xyz.billybishop4.fleet.tv

import org.junit.Assert.*
import org.junit.Test

class VideoPictureTest {
    private fun color(picture:VideoPicture,r:Float,g:Float,b:Float):List<Float> {
        val m=picture.matrix()
        return (0..2).map { row -> m[row]*r+m[4+row]*g+m[8+row]*b+m[12+row] }
    }
    @Test fun neutralPreservesEachChannelAndAlpha() {
        assertTrue(VideoPicture().isNeutral)
        assertEquals(listOf(.2f,.4f,.8f),color(VideoPicture(),.2f,.4f,.8f))
        val m=VideoPicture().matrix()
        assertEquals(1f,m[15]);assertEquals(0f,m[3]);assertEquals(0f,m[7]);assertEquals(0f,m[11])
    }
    @Test fun removingSaturationProducesEqualLinearLuminanceInAllChannels() {
        val gray=color(VideoPicture(saturation=0),1f,0f,0f)
        gray.forEach { assertEquals(.2126f,it,.00001f) }
    }
    @Test fun brightnessAndContrastHaveIndependentPredictablePivots() {
        color(VideoPicture(brightness=20),.3f,.3f,.3f).forEach { assertEquals(.5f,it,.00001f) }
        color(VideoPicture(contrast=50),.5f,.5f,.5f).forEach { assertEquals(.5f,it,.00001f) }
        assertTrue(color(VideoPicture(contrast=50),.6f,.6f,.6f).first()>.6f)
    }
    @Test fun inputBoundsPreventSingularOrNonFiniteEffects() {
        val picture=VideoPicture(Int.MAX_VALUE,Int.MAX_VALUE,Int.MIN_VALUE)
        assertEquals(VideoPicture(50,50,0),picture.normalized())
        assertTrue(picture.matrix().all{it.isFinite()})
    }
    @Test fun renderingNeverSeesMutatedOldMatricesAndRejectsHdr() {
        val effect=VideoPictureEffect(VideoPicture())
        val prior=effect.getMatrix(0,false);val saved=prior.clone()
        effect.update(VideoPicture(brightness=25))
        assertArrayEquals(saved,prior,0f)
        assertNotSame(prior,effect.getMatrix(0,false))
        assertThrows(IllegalStateException::class.java) { effect.getMatrix(0,true) }
    }
}
