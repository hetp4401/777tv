package xyz.billybishop4.fleet.tv

import android.content.ComponentCallbacks2
import android.graphics.Bitmap
import android.os.Build
import coil.memory.MemoryCache

/** A strong LRU whose budget includes pixel storage, even if a hardware bitmap reports only its handle. */
internal class ArtworkMemoryCache(override val maxSize:Int):MemoryCache {
    private data class Entry(val value:MemoryCache.Value,val bytes:Int)
    private val entries=LinkedHashMap<MemoryCache.Key,Entry>(0,.75f,true)
    private var used=0
    init { require(maxSize>0) }
    @get:Synchronized override val size:Int get()=used
    @get:Synchronized override val keys:Set<MemoryCache.Key> get()=entries.keys.toSet()
    @Synchronized override fun get(key:MemoryCache.Key):MemoryCache.Value?=entries[key]?.value
    @Synchronized override fun set(key:MemoryCache.Key,value:MemoryCache.Value) {
        entries.remove(key)?.let{used-=it.bytes}
        val bitmap=value.bitmap
        if(bitmap.isRecycled)return
        val config=bitmap.config
        val bytesPerPixel=when {
            config==Bitmap.Config.ALPHA_8->1
            config==Bitmap.Config.RGB_565 || config==Bitmap.Config.ARGB_4444->2
            Build.VERSION.SDK_INT>=26 && config==Bitmap.Config.RGBA_F16->8
            else->4
        }
        val pixels=bitmap.width.toLong()*bitmap.height*bytesPerPixel
        val allocation=try{bitmap.allocationByteCount.toLong()}catch(_:RuntimeException){0L}
        val bytes=maxOf(pixels,allocation).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        if(bytes>maxSize)return
        // Never recycle an evicted bitmap: an ImageView may still be displaying it.
        trimTo(maxSize-bytes)
        entries[key.copy(extras=key.extras.toMap())]=Entry(value.copy(extras=value.extras.toMap()),bytes);used+=bytes
    }
    @Synchronized override fun remove(key:MemoryCache.Key):Boolean {
        val previous=entries.remove(key)?:return false;used-=previous.bytes;return true
    }
    @Synchronized override fun clear(){entries.clear();used=0}
    @Synchronized override fun trimMemory(level:Int) {
        if(level>=ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)clear()
        else if(level>=ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)trimTo(maxSize/2)
    }
    private fun trimTo(target:Int) {
        val oldest=entries.entries.iterator()
        while(used>target && oldest.hasNext()){used-=oldest.next().value.bytes;oldest.remove()}
    }
}
