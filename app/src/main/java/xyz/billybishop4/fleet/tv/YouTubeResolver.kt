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
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.services.youtube.YoutubeStreamHelper
import com.grack.nanojson.JsonObject

internal interface YouTubeStreamResolver { suspend fun resolve(video:YouTubeVideo):Watch }

/** Initialized only on explicit playback. One worker, interruptible sockets, no bundled browser/native runtime. */
internal class YouTubeResolver(private val playbackOnly:Boolean=true,private val trace:((String,Long)->Unit)?=null):YouTubeStreamResolver {
    override suspend fun resolve(video:YouTubeVideo):Watch=suspendCancellableCoroutine { continuation ->
        val work=Work(trace);val future=AtomicReference<Future<*>?>()
        continuation.invokeOnCancellation { work.cancel();future.get()?.cancel(true) }
        future.set(worker.submit {
            activeWork.set(work)
            try {
                if(work.cancelled.get())return@submit
                initialize()
                require(YouTubeDecode.validId(video.id))
                // The full extractor also fetches WEB thumbnails and watch-next. Neither is
                // required for a first frame; keep that path for unsupported player responses.
                val quick=if(playbackOnly)try {
                    playerWatch(video,YoutubeStreamHelper.getVisionOsPlayerResponse(ContentCountry.DEFAULT,
                        Localization.DEFAULT,video.id,YoutubeParsingHelper.generateContentPlaybackNonce()))
                }catch(e:Exception){if(work.cancelled.get() || Thread.currentThread().isInterrupted)throw e;null} else null
                val result=quick?:extract(video)
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
        val title=playbackTitle(video,runCatching{extractor.name}.getOrDefault(""),runCatching{extractor.uploaderName}.getOrDefault(""))
        val hls=runCatching { extractor.hlsUrl }.getOrDefault("")
        val headers=mapOf("User-Agent" to WEB_AGENT,"Referer" to "https://www.youtube.com/","Origin" to "https://www.youtube.com")
        val live=extractor.streamType==StreamType.LIVE_STREAM
        val captions=runCatching { extractor.getSubtitles(MediaFormat.VTT).filter { it.isUrl && isHttp(it.content) }.take(20)
            .map { VideoCaption(it.content,it.languageTag,"text/vtt") } }.getOrDefault(emptyList())
        if(isHttp(hls))return Watch(title,Stream("YouTube",hls),headers=headers,live=live,
            videoMimeType="application/x-mpegURL",resolvedAtMs=System.currentTimeMillis(),short=video.isShort,captions=captions)
        val audio=extractor.audioStreams.filter { it.isUrl && isHttp(it.content) }
            .sortedWith(compareByDescending<org.schabi.newpipe.extractor.stream.AudioStream> { it.format==MediaFormat.M4A }
                .thenByDescending { it.averageBitrate.coerceAtMost(160) }).firstOrNull()
        val separate=extractor.videoOnlyStreams.filter { it.isUrl && isHttp(it.content) && it.height in 1..1080 && it.fps<=60 }
            .sortedWith(compareByDescending<org.schabi.newpipe.extractor.stream.VideoStream> { it.format==MediaFormat.MPEG_4 }
                .thenByDescending { it.height }.thenBy { it.fps }).firstOrNull()
        if(separate!=null && audio!=null)return Watch(title,Stream("YouTube",separate.content),headers=headers,live=live,
            audioUrl=audio.content,videoMimeType=separate.format?.mimeType.orEmpty(),audioMimeType=audio.format?.mimeType.orEmpty(),
            resolvedAtMs=System.currentTimeMillis(),short=video.isShort,captions=captions)
        val combined=extractor.videoStreams.filter { it.isUrl && isHttp(it.content) }
            .sortedWith(compareByDescending<org.schabi.newpipe.extractor.stream.VideoStream> { it.format==MediaFormat.MPEG_4 }.thenByDescending { it.height }).firstOrNull()
            ?:throw UserMessage("YouTube didn't provide a playable stream. Try again or choose another video.")
        return Watch(title,Stream("YouTube",combined.content),headers=headers,live=live,videoMimeType=combined.format?.mimeType.orEmpty(),
            resolvedAtMs=System.currentTimeMillis(),short=video.isShort,captions=captions)
    }
    private class Work(val trace:((String,Long)->Unit)?) {
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
                    val started=playbackNowMs()
                    try { return call.execute().use { Response(it.code,it.message,it.headers.toMultimap(),YouTubeHttp.readBounded(it),it.request.url.toString()) } }
                    finally { work?.call?.compareAndSet(call,null);work?.trace?.invoke(call.request().url.encodedPath.substringAfterLast('/').ifBlank{"bootstrap"},playbackNowMs()-started) }
                }
            })
            initialized=true
        }
    }
}

/** Only accept a matching, playable response. The maintained extractor remains the fallback. */
internal fun playerWatch(video:YouTubeVideo,response:JsonObject):Watch? {
    val status=response.getObject("playabilityStatus")
    if(status.getString("status")!="OK" || response.getObject("videoDetails").getString("videoId")!=video.id)return null
    val url=response.getObject("streamingData").getString("hlsManifestUrl").orEmpty()
    if(!isHttp(url))return null
    val captions=response.getObject("captions").getObject("playerCaptionsTracklistRenderer").getArray("captionTracks")
        .filterIsInstance<JsonObject>().take(20).mapNotNull { track->
            val base=track.getString("baseUrl").orEmpty()
            val caption=base.toHttpUrlOrNull()?.newBuilder()?.setQueryParameter("fmt","vtt")?.build()?.toString()
            if(caption!=null)VideoCaption(caption,track.getString("languageCode").orEmpty(),"text/vtt")else null
        }
    val details=response.getObject("videoDetails")
    return Watch(playbackTitle(video,details.getString("title"),details.getString("author")),Stream("YouTube",url),headers=mapOf("User-Agent" to
        "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
        "Referer" to "https://www.youtube.com/","Origin" to "https://www.youtube.com"),
        live=status.containsKey("liveStreamability"),videoMimeType="application/x-mpegURL",
        resolvedAtMs=System.currentTimeMillis(),short=video.isShort,captions=captions)
}

/** A cast sender may provide only an ID. Reuse the already fetched player
 * metadata for that placeholder; normal browse titles keep their exact identity. */
internal fun playbackTitle(video:YouTubeVideo,name:String?,author:String?):Title {
    if(video.name!="YouTube video" || name.isNullOrBlank())return video.title()
    return video.copy(name=name.take(500),channel=video.channel.ifBlank{author.orEmpty().take(500)}).title()
}
