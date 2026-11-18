package xyz.billybishop4.fleet.tv

import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.stream.StreamType

internal interface YouTubeStreamResolver { suspend fun resolve(video:YouTubeVideo):Watch }

/** Initialized only on explicit playback. One worker, interruptible sockets, no bundled browser/native runtime. */
internal class YouTubeResolver:YouTubeStreamResolver {
    override suspend fun resolve(video:YouTubeVideo):Watch=suspendCancellableCoroutine { continuation ->
        val work=Work();val future=AtomicReference<Future<*>?>()
        continuation.invokeOnCancellation { work.cancel();future.get()?.cancel(true) }
        future.set(worker.submit {
            activeWork.set(work)
            try {
                if(work.cancelled.get())return@submit
                initialize()
                val result=extract(video)
                if(continuation.isActive)continuation.resume(result)
            } catch(e:Exception) { if(continuation.isActive)continuation.resumeWithException(e) }
            finally { work.call.getAndSet(null)?.cancel();activeWork.remove() }
        })
        if(work.cancelled.get())future.get()?.cancel(true)
    }
    private fun extract(video:YouTubeVideo):Watch {
        require(YouTubeDecode.validId(video.id))
        val extractor=ServiceList.YouTube.getStreamExtractor("https://www.youtube.com/watch?v=${video.id}")
        extractor.fetchPage()
        val hls=runCatching { extractor.hlsUrl }.getOrDefault("")
        val headers=mapOf("User-Agent" to WEB_AGENT,"Referer" to "https://www.youtube.com/","Origin" to "https://www.youtube.com")
        val live=extractor.streamType==StreamType.LIVE_STREAM
        val captions=runCatching { extractor.getSubtitles(MediaFormat.VTT).filter { it.isUrl && isHttp(it.content) }.take(20)
            .map { VideoCaption(it.content,it.languageTag,"text/vtt") } }.getOrDefault(emptyList())
        if(isHttp(hls))return Watch(video.title(),Stream("YouTube",hls),headers=headers,live=live,
            videoMimeType="application/x-mpegURL",resolvedAtMs=System.currentTimeMillis(),short=video.isShort,captions=captions)
        val audio=extractor.audioStreams.filter { it.isUrl && isHttp(it.content) }
            .sortedWith(compareByDescending<org.schabi.newpipe.extractor.stream.AudioStream> { it.format==MediaFormat.M4A }
                .thenByDescending { it.averageBitrate.coerceAtMost(160) }).firstOrNull()
        val separate=extractor.videoOnlyStreams.filter { it.isUrl && isHttp(it.content) && it.height in 1..1080 && it.fps<=60 }
            .sortedWith(compareByDescending<org.schabi.newpipe.extractor.stream.VideoStream> { it.format==MediaFormat.MPEG_4 }
                .thenByDescending { it.height }.thenBy { it.fps }).firstOrNull()
        if(separate!=null && audio!=null)return Watch(video.title(),Stream("YouTube",separate.content),headers=headers,live=live,
            audioUrl=audio.content,videoMimeType=separate.format?.mimeType.orEmpty(),audioMimeType=audio.format?.mimeType.orEmpty(),
            resolvedAtMs=System.currentTimeMillis(),short=video.isShort,captions=captions)
        val combined=extractor.videoStreams.filter { it.isUrl && isHttp(it.content) }
            .sortedWith(compareByDescending<org.schabi.newpipe.extractor.stream.VideoStream> { it.format==MediaFormat.MPEG_4 }.thenByDescending { it.height }).firstOrNull()
            ?:throw UserMessage("YouTube didn't provide a playable stream. Try again or choose another video.")
        return Watch(video.title(),Stream("YouTube",combined.content),headers=headers,live=live,videoMimeType=combined.format?.mimeType.orEmpty(),
            resolvedAtMs=System.currentTimeMillis(),short=video.isShort,captions=captions)
    }
    private class Work {
        val call=AtomicReference<Call?>();val cancelled=AtomicBoolean(false)
        fun cancel(){cancelled.set(true);call.get()?.cancel()}
    }
    companion object {
        private const val WEB_AGENT="Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
        private val worker by lazy { Executors.newSingleThreadExecutor { r->Thread(r,"777tv-youtube-resolver").apply { isDaemon=true } } }
        private val activeWork=ThreadLocal<Work>()
        private var initialized=false
        @Synchronized private fun initialize() {
            if(initialized)return
            NewPipe.init(object:Downloader() {
                override fun execute(request:Request):Response {
                    val work=activeWork.get()
                    if(work?.cancelled?.get()==true || Thread.currentThread().isInterrupted)throw IOException("Playback request cancelled")
                    val headers=request.headers()
                    val mime=headers.entries.firstOrNull { it.key.equals("Content-Type",true) }?.value?.firstOrNull()?.toMediaTypeOrNull()
                    val method=request.httpMethod()
                    val data=request.dataToSend()
                    val body=if(data!=null)data.toRequestBody(mime) else if(method in setOf("POST","PUT","PATCH"))ByteArray(0).toRequestBody(mime) else null
                    val call=YouTubeHttp.client.newCall(okhttp3.Request.Builder().url(request.url()).header("User-Agent",WEB_AGENT).apply {
                        headers.forEach { (key,values)->if(!key.equals("Accept-Encoding",true)) { removeHeader(key);values.forEach { addHeader(key,it) } } };method(method,body)
                    }.build())
                    work?.call?.set(call)
                    if(work?.cancelled?.get()==true)call.cancel()
                    try { return call.execute().use { Response(it.code,it.message,it.headers.toMultimap(),YouTubeHttp.readBounded(it),it.request.url.toString()) } }
                    finally { work?.call?.compareAndSet(call,null) }
                }
            })
            initialized=true
        }
    }
}
