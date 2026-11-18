package xyz.billybishop4.fleet.tv

import java.net.URI
import java.security.SecureRandom
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject

/** The client owns credentials, epoch checks and cancellation; playback never receives a bearer. */
internal interface YouTubeHistoryBackend {
    fun historyAccountEpoch():Int?
    suspend fun historySession(videoId:String,nonce:String,expectedEpoch:Int):YouTubeHistorySession?
    suspend fun sendHistory(session:YouTubeHistorySession,url:String):Boolean
    suspend fun historySessionCurrent(session:YouTubeHistorySession):Boolean
}

internal data class YouTubeHistorySession(val epoch:Int,val videoId:String,val nonce:String,val playbackUrl:String,
    val watchtimeUrl:String,val resumePositionMs:Long?=null) {
    override fun toString()="YouTubeHistorySession(epoch=$epoch)"
}
private val historyPausedFlags=arrayOf("historyPaused","isHistoryPaused","watchHistoryPaused","historyTrackingDisabled")
private val unrelatedHistoryPayload=setOf("streamingData","adPlacements","playerAds","adSlots","captions","microformat","storyboards","assets")
private val historyPolicyRoots=setOf("responseContext","playerConfig","playbackTracking","policy","historyPolicy","watchHistoryPolicy",
    "historyContext","historySettings","history","watchHistory")

/** Tracking never follows an arbitrary endpoint supplied by a response. */
internal fun validHistoryTrackingUrl(value:String,origin:String):Boolean=runCatching {
    val base=URI(origin);val url=URI(value)
    val real=base.scheme=="https" && base.host=="www.youtube.com" && base.port in listOf(-1,443) && base.rawPath.orEmpty().isEmpty()
    val fixture=base.scheme=="http" && base.host in setOf("127.0.0.1","localhost","10.0.2.2")
    (real || fixture) && base.userInfo==null && base.rawQuery==null && base.fragment==null &&
        url.userInfo==null && url.fragment==null && sameOrigin(origin,value) &&
        url.rawPath in listOf(base.rawPath.orEmpty().trimEnd('/')+"/api/stats/playback",base.rawPath.orEmpty().trimEnd('/')+"/api/stats/watchtime")
}.getOrDefault(false)

private fun canonicalHistoryMetadataUrl(value:String,origin:String):String? {
    if(validHistoryTrackingUrl(value,origin))return value
    return runCatching {
        val supplied=URI(value)
        // Real player metadata uses s.youtube.com. Upstream TrackingApi instead
        // sends its parameters to fixed www endpoints; never send a bearer to s.
        if(supplied.scheme!="https" || supplied.host!="s.youtube.com" || supplied.port !in listOf(-1,443) ||
            supplied.userInfo!=null || supplied.fragment!=null || supplied.rawPath !in listOf("/api/stats/playback","/api/stats/watchtime"))return null
        value.toHttpUrlOrNull()?.newBuilder()?.host("www.youtube.com")?.build()?.toString()
            ?.takeIf{validHistoryTrackingUrl(it,origin)}
    }.getOrNull()
}

internal fun decodeHistorySession(response:JSONObject,epoch:Int,videoId:String,nonce:String,origin:String):YouTubeHistorySession? {
    if(!YouTubeDecode.validId(videoId) || !nonce.matches(Regex("[A-Za-z0-9_-]{16}")))return null
    if(response.optJSONObject("videoDetails")?.optString("videoId")!=videoId ||
        response.optJSONObject("playabilityStatus")?.optString("status")!="OK")return null
    // Do not enable/resume Google history. Explicit policy flags suppress telemetry;
    // the authenticated stats endpoint also enforces Google's current account policy.
    var nodes=0
    fun disabled(value:Any?,depth:Int=0):Boolean {
        // An unusually deep/large policy response is not permission to track.
        if(depth>16 || ++nodes>3000)return true
        if(value is JSONObject) {
            for(name in historyPausedFlags)
                if(value.optBoolean(name,false))return true
            if(value.has("watchHistoryEnabled") && !value.optBoolean("watchHistoryEnabled",true))return true
            if(value.optString("historyState").equals("PAUSED",true) || value.has("watchHistoryPausedRenderer"))return true
            // Root annotations/cards contain deeply nested UI commands, not
            // account policy. Only explicitly named policy containers recurse.
            val keys=if(depth==0)historyPolicyRoots.iterator() else value.keys()
            while(keys.hasNext()) {
                val key=keys.next()
                // Media formats, ads and caption payloads cannot set account
                // history policy. Inspect root/context/config/tracking instead.
                if(value.has(key) && key !in unrelatedHistoryPayload && disabled(value.opt(key),depth+1))return true
            }
        }else if(value is JSONArray)for(index in 0 until value.length())if(disabled(value.opt(index),depth+1))return true
        return false
    }
    if(disabled(response))return null
    val tracking=response.optJSONObject("playbackTracking")?:return null
    val playback=canonicalHistoryMetadataUrl(tracking.optJSONObject("videostatsPlaybackUrl")?.optString("baseUrl").orEmpty(),origin)?:return null
    val watchtime=canonicalHistoryMetadataUrl(tracking.optJSONObject("videostatsWatchtimeUrl")?.optString("baseUrl").orEmpty(),origin)?:return null
    if(playback.toHttpUrlOrNull()?.encodedPath?.endsWith("/playback")!=true ||
        watchtime.toHttpUrlOrNull()?.encodedPath?.endsWith("/watchtime")!=true)return null
    for(url in listOf(playback,watchtime))url.toHttpUrlOrNull()?.queryParameter("docid")?.let{if(it!=videoId)return null}
    val seconds=response.optJSONObject("playerConfig")?.optJSONObject("playbackStartConfig")?.optDouble("startSeconds",Double.NaN)
    val resume=seconds?.takeIf{it.isFinite() && it>0 && it<=604800}?.let{(it*1000).toLong()}
    return YouTubeHistorySession(epoch,videoId,nonce,playback,watchtime,resume)
}

/** One active writer and one bounded final flush per YouTube model, including rapid Shorts changes. */
internal class YouTubeHistorySync(private val backend:YouTubeHistoryBackend,private val scope:CoroutineScope,
    private val now:()->Long=::playbackNowMs,private val intervalMs:Long=30_000L,
    private val nonce:()->String=::historyNonce,private val finalFlushMs:Long=4000L) {
    private var current:YouTubeHistoryPlayback?=null
    private var finalJob:Job?=null
    private var finalDeadline:Job?=null
    fun begin(videoId:String):YouTubeHistoryPlayback? {
        current?.finish()
        val epoch=backend.historyAccountEpoch()?:return null
        return YouTubeHistoryPlayback(backend,scope,videoId,nonce(),epoch,now,intervalMs,::retire).also{current=it}
    }
    private fun retire(playback:YouTubeHistoryPlayback,job:Job) {
        if(current===playback)current=null
        finalDeadline?.cancel();finalJob?.cancel();finalJob=job
        finalDeadline=scope.launch { delay(finalFlushMs);job.cancel() }
        job.invokeOnCompletion { if(finalJob===job){finalDeadline?.cancel();finalDeadline=null;finalJob=null} }
    }
    fun cancel(){current?.cancel();current=null;finalDeadline?.cancel();finalDeadline=null;finalJob?.cancel();finalJob=null}
}

private val historyRandom by lazy { SecureRandom() }
private fun historyNonce():String {
    val alphabet="ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-"
    return buildString(16){repeat(16){append(alphabet[historyRandom.nextInt(alphabet.length)])}}
}

internal class YouTubeHistoryPlayback internal constructor(private val backend:YouTubeHistoryBackend,scope:CoroutineScope,
    private val videoId:String,private val nonce:String,private val epoch:Int,private val now:()->Long,private val intervalMs:Long,
    private val retired:(YouTubeHistoryPlayback,Job)->Unit) {
    private data class Sample(val position:Long,val duration:Long,val playing:Boolean,val at:Long)
    private data class Span(val start:Long,val end:Long)
    private val signal=Channel<Unit>(Channel.CONFLATED)
    private val pending=ArrayList<Span>(8)
    private var last:Sample?=null
    private var watchedMs=0L
    private var force=false
    private var finished=false
    private var lastSentAt=Long.MIN_VALUE
    private var retryAt=0L
    private var session:YouTubeHistorySession?=null
    private var recorded=false
    private val worker=scope.launch {
        try {
            for(ignored in signal) {
                if(watchedMs<2000 || last==null){if(finished)break;continue}
                val time=now()
                if(!finished && (time<retryAt || !force && lastSentAt!=Long.MIN_VALUE && time-lastSentAt<intervalMs))continue
                val final=finished
                force=false
                try {
                    val context=session?:withTimeoutOrNull(6000){backend.historySession(videoId,nonce,epoch)}?.also{session=it}
                    if(context==null || !backend.historySessionCurrent(context))break
                    val snapshot=last!!
                    val batch=pending.toList();pending.clear()
                    try {
                        if(!recorded) {
                            if(!backend.sendHistory(context,url(context.playbackUrl,snapshot,emptyList(),false)))throw java.io.IOException("History unavailable")
                            recorded=true
                        }
                        if(!backend.sendHistory(context,url(context.watchtimeUrl,snapshot,batch,final)))throw java.io.IOException("History unavailable")
                        lastSentAt=now();retryAt=0
                    }catch(e:Exception){restore(batch);throw e}
                }catch(e:CancellationException){throw e}
                catch(_:Exception){retryAt=now()+15_000}
                if(final)break
                if(finished)signal.trySend(Unit)
            }
        }finally{signal.close();pending.clear();session=null}
    }
    /** Main-thread snapshots; no I/O, sleeps or per-frame jobs. */
    fun sample(positionMs:Long,durationMs:Long,playing:Boolean,ended:Boolean=false) {
        if(finished || !worker.isActive)return
        val time=now();val position=positionMs.coerceAtLeast(0);val duration=durationMs.coerceAtLeast(0)
        val previous=last
        if(previous!=null && previous.playing) {
            val elapsed=(time-previous.at).coerceAtLeast(0)
            val advanced=position-previous.position
            // Discontinuities have their own exact before/after hook. This bound is
            // a second guard against treating an unobserved seek as watched video.
            if(advanced in 1..(elapsed*3+1000)) {
                watchedMs+=minOf(elapsed,advanced)
                append(Span(previous.position,position))
            }
        }
        last=Sample(position,duration,playing,time)
        if(previous?.playing==true && !playing || ended)force=true
        signal.trySend(Unit)
    }
    fun discontinuity(oldPositionMs:Long,newPositionMs:Long,durationMs:Long,playing:Boolean) {
        if(finished)return
        sample(oldPositionMs,durationMs,playing)
        last=Sample(newPositionMs.coerceAtLeast(0),durationMs.coerceAtLeast(0),playing,now())
        signal.trySend(Unit)
    }
    private fun append(span:Span) {
        if(span.end<=span.start)return
        val previous=pending.lastOrNull()
        if(previous!=null && span.start in previous.end..previous.end+250)pending[pending.lastIndex]=Span(previous.start,span.end)
        else {if(pending.size==8)pending.removeAt(0);pending+=span}
    }
    private fun restore(batch:List<Span>) {
        val fresh=pending.toList();pending.clear()
        (batch+fresh).takeLast(8).forEach(::append)
    }
    private fun seconds(value:Long)=String.format(Locale.US,"%.3f",value.coerceAtLeast(0)/1000.0)
    private fun url(base:String,snapshot:Sample,spans:List<Span>,final:Boolean):String {
        val builder=base.toHttpUrlOrNull()!!.newBuilder().setQueryParameter("ns","yt").setQueryParameter("ver","2")
            .setQueryParameter("docid",videoId).setQueryParameter("cpn",nonce).setQueryParameter("cmt",seconds(snapshot.position))
        if(snapshot.duration>0)builder.setQueryParameter("len",seconds(snapshot.duration))else builder.removeAllQueryParameters("len")
        if(base.toHttpUrlOrNull()!!.encodedPath.endsWith("/watchtime")) {
            val ranges=spans.ifEmpty{listOf(Span(snapshot.position,snapshot.position))}
            builder.setQueryParameter("st",ranges.joinToString(","){seconds(it.start)})
                .setQueryParameter("et",ranges.joinToString(","){seconds(it.end)})
        }
        if(final)builder.setQueryParameter("final","1")else builder.removeAllQueryParameters("final")
        return builder.build().toString()
    }
    // A policy-disabled worker stays attached to this epoch, avoiding repeated
    // metadata requests. A new account gets a new timeline baseline instead.
    fun matchesCurrentAccount()=backend.historyAccountEpoch()==epoch
    fun finish() {
        if(finished)return
        finished=true;force=true;retired(this,worker);signal.trySend(Unit)
    }
    fun cancel(){finished=true;worker.cancel();signal.close();pending.clear();session=null}
}
