package xyz.billybishop4.fleet.tv

import android.graphics.Bitmap
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import coil.memory.MemoryCache
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class ArtworkMemoryCacheTest {
    @Test fun lruBudgetUsesPixelMemoryWithoutRecyclingImagesStillInUse() {
        val bitmaps=List(3){Bitmap.createBitmap(200,100,Bitmap.Config.ARGB_8888)}
        val bytes=bitmaps[0].allocationByteCount;val cache=ArtworkMemoryCache(bytes*2)
        val keys=List(3){MemoryCache.Key("image-$it")}
        cache[keys[0]]=MemoryCache.Value(bitmaps[0]);cache[keys[1]]=MemoryCache.Value(bitmaps[1]);assertNotNull(cache[keys[0]])
        cache[keys[2]]=MemoryCache.Value(bitmaps[2])
        assertNull(cache[keys[1]]);assertNotNull(cache[keys[0]]);assertNotNull(cache[keys[2]]);assertEquals(bytes*2,cache.size)
        assertFalse(bitmaps[1].isRecycled);cache.clear();assertEquals(0,cache.size);assertTrue(bitmaps.none{it.isRecycled})
        bitmaps.forEach(Bitmap::recycle)
    }
    @Test fun hardwareBuffersCannotEnterACacheSmallerThanTheirPixels() {
        assumeTrue(Build.VERSION.SDK_INT>=26)
        val source=Bitmap.createBitmap(320,180,Bitmap.Config.ARGB_8888)
        val hardware=source.copy(Bitmap.Config.HARDWARE,false)
        assumeTrue(hardware!=null)
        val pixels=hardware!!.width*hardware.height*4
        val cache=ArtworkMemoryCache(pixels-1);val key=MemoryCache.Key("hardware")
        cache[key]=MemoryCache.Value(hardware)
        assertNull(cache[key]);assertEquals(0,cache.size);assertFalse(hardware.isRecycled)
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null),"hardware-bitmap-accounting.json").writeText(JSONObject().put("width",hardware.width)
            .put("height",hardware.height).put("pixelBytes",pixels).put("platformAllocationBytes",hardware.allocationByteCount).put("oversizedRejected",true).toString(2))
        source.recycle();hardware.recycle()
    }
    @Test fun replacementRemovalAndTrimKeepAccountingAndMetadataConsistent() {
        val small=Bitmap.createBitmap(160,100,Bitmap.Config.RGB_565);val large=Bitmap.createBitmap(200,100,Bitmap.Config.ARGB_8888)
        val cache=ArtworkMemoryCache(large.allocationByteCount*2);val key=MemoryCache.Key("same",mapOf("variant" to "one"))
        cache[key]=MemoryCache.Value(large,mapOf("sampled" to false));cache[key]=MemoryCache.Value(small,mapOf("sampled" to true))
        assertEquals(small.allocationByteCount,cache.size);assertEquals(true,cache[key]!!.extras["sampled"])
        assertTrue(cache.remove(key));assertEquals(0,cache.size);assertFalse(cache.remove(key))
        cache[MemoryCache.Key("a")]=MemoryCache.Value(large);cache[MemoryCache.Key("b")]=MemoryCache.Value(large)
        cache.trimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW);assertTrue(cache.size<=cache.maxSize/2)
        cache.trimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND);assertTrue(cache.keys.isEmpty())
        assertFalse(large.isRecycled);small.recycle();large.recycle()
    }
}
