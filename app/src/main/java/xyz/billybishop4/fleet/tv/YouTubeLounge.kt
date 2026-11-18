package xyz.billybishop4.fleet.tv

import java.io.BufferedReader
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resumeWithException

internal data class YouTubeScreenIdentity(val screenId:String,val deviceId:String) {
    override fun toString()="YouTubeScreenIdentity(saved=true)"
}
internal interface YouTubeScreenStore {
    fun read():YouTubeScreenIdentity?
    fun write(value:YouTubeScreenIdentity?)
}
internal data class YouTubeLoungeEvent(val index:Long,val type:String,val data:JSONObject,val value:String="") {
    override fun toString()="YouTubeLoungeEvent(type=$type)"
}
internal interface YouTubeLoungeWire {
    suspend fun request(path:String,query:Map<String,String> = emptyMap(),form:Map<String,String>?=null):String
    suspend fun events(query:Map<String,String>,receive:suspend(YouTubeLoungeEvent)->Unit)
}

/** BrowserChannel keeps only one bounded frame, rather than buffering an indefinite response. */
internal object YouTubeLoungeFrames {
    const val MAX_FRAME=262_144
    fun read(reader:BufferedReader):List<YouTubeLoungeEvent>? {
        var header=""
        while(header.isEmpty()) {
            val line=StringBuilder()
            while(true) {
                val c=reader.read();if(c<0){if(line.isEmpty())return null;throw UserMessage("Phone connection ended unexpectedly.")}
                if(c==10)break
                require(line.length<16){"Invalid phone frame"};if(c!=13)line.append(c.toChar())
            }
            header=line.toString().trim()
        }
        val count=header.toIntOrNull()?.takeIf{it in 1..MAX_FRAME}?:throw UserMessage("Phone connection sent an invalid frame.")
        val buffer=CharArray(count);var offset=0
        while(offset<count){val n=reader.read(buffer,offset,count-offset);if(n<0)throw UserMessage("Phone connection ended unexpectedly.");offset+=n}
        return decode(String(buffer))
    }
    fun decode(frame:String):List<YouTubeLoungeEvent> {
        require(frame.length<=MAX_FRAME)
        val rows=JSONArray(frame);require(rows.length()<=128)
        return (0 until rows.length()).mapNotNull { i->
            val row=rows.optJSONArray(i)?:return@mapNotNull null
            val command=row.optJSONArray(1)?:return@mapNotNull null
            val index=row.optLong(0,-1);val type=command.optString(0)
            if(index<0 || type.length !in 1..80)return@mapNotNull null
            YouTubeLoungeEvent(index,type,command.optJSONObject(1)?:JSONObject(),(command.opt(1) as? String).orEmpty().take(4096))
        }
    }
}

internal class YouTubeLoungeHttp(origin:String="https://www.youtube.com",client:OkHttpClient=YouTubeHttp.client):YouTubeLoungeWire {
    private val requestClient=client.newBuilder().readTimeout(65,TimeUnit.SECONDS).callTimeout(75,TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()
    private val streamClient=requestClient.newBuilder().callTimeout(0,TimeUnit.SECONDS).build()
    private val base=origin.trimEnd('/').toHttpUrl().also {
        require(it.username.isEmpty() && it.password.isEmpty() && it.query==null && it.fragment==null)
        require(it.isHttps && it.host=="www.youtube.com" || it.host in setOf("127.0.0.1","localhost","10.0.2.2"))
    }
    private fun buildRequest(path:String,query:Map<String,String>,form:Map<String,String>?):Request {
        require(path.startsWith("pairing/") || path=="bc/bind")
        val url=base.newBuilder().addPathSegments("api/lounge/$path").apply{query.forEach{(k,v)->addQueryParameter(k,v)}}.build()
        return Request.Builder().url(url).header("User-Agent",YouTubeHttp.USER_AGENT).apply {
            if(form!=null)post(FormBody.Builder().apply{form.forEach{(k,v)->add(k,v)}}.build())
        }.build()
    }
    private suspend fun <T> exchange(request:Request,streaming:Boolean=false,read:suspend(Response)->T):T=coroutineScope {
        val call=(if(streaming)streamClient else requestClient).newCall(request)
        val cancellation=launch(start=CoroutineStart.UNDISPATCHED){try{awaitCancellation()}finally{call.cancel()}}
        try {
            val response=suspendCancellableCoroutine<Response>{ c->
                c.invokeOnCancellation{call.cancel()}
                call.enqueue(object:Callback {
                    override fun onFailure(call:Call,e:java.io.IOException){if(c.isActive)c.resumeWithException(e)}
                    override fun onResponse(call:Call,response:Response){c.resume(response){_,value,_->value.close()}}
                })
            }
            response.use { if(it.code !in 200..299)throw HttpFailure(it.code);read(it) }
        } finally { cancellation.cancel() }
    }
    override suspend fun request(path:String,query:Map<String,String>,form:Map<String,String>?):String=exchange(buildRequest(path,query,form)){ response->
        withContext(Dispatchers.IO) {
            val reader=response.body?.charStream()?:throw UserMessage("Phone connection returned no data.")
            val result=StringBuilder();val buffer=CharArray(4096)
            while(true){val count=reader.read(buffer);if(count<0)break;require(result.length+count<=YouTubeLoungeFrames.MAX_FRAME);result.append(buffer,0,count)}
            result.toString()
        }
    }
    override suspend fun events(query:Map<String,String>,receive:suspend(YouTubeLoungeEvent)->Unit)=exchange(buildRequest("bc/bind",query,null),streaming=true){ response->
        withContext(Dispatchers.IO) {
            val reader=response.body?.charStream()?.buffered()?:throw UserMessage("Phone connection returned no data.")
            while(currentCoroutineContext().isActive) {
                val frame=YouTubeLoungeFrames.read(reader)?:break
                for(event in frame){currentCoroutineContext().ensureActive();receive(event)}
            }
        }
    }
}

/** Anonymous YouTube TV-code receiver; Google account bearer tokens never enter this protocol. */
internal class YouTubeLoungeClient(private val store:YouTubeScreenStore,private val wire:YouTubeLoungeWire=YouTubeLoungeHttp(),
    private val name:String="777tv",private val now:()->Long=System::currentTimeMillis) {
    private val identityLock=Mutex();private val writeLock=Mutex()
    private var identity:YouTubeScreenIdentity?=null;private var token="";private var tokenAt=0L
    private class Session(val sid:String,val gsession:String,val aid:AtomicLong=AtomicLong(-1))
    @Volatile private var session:Session?=null
    private val rid=AtomicInteger(1000);private val ofs=AtomicInteger();private val generation=AtomicInteger()
    private suspend fun prepare():Pair<YouTubeScreenIdentity,String> = identityLock.withLock {
        if(identity==null)identity=withContext(Dispatchers.IO){store.read()}
        if(identity==null) {
            val screen=wire.request("pairing/generate_screen_id").trim().trim('"')
            require(screen.length in 8..256 && screen.all{it.isLetterOrDigit() || it in "-_"})
            identity=YouTubeScreenIdentity(screen,UUID.randomUUID().toString())
            withContext(Dispatchers.IO){store.write(identity)}
        }
        if(token.isEmpty() || now()-tokenAt !in 0..1_500_000) {
            val result=JSONObject(wire.request("pairing/get_lounge_token_batch",form=mapOf("screen_ids" to identity!!.screenId)))
            val value=result.optJSONArray("screens")?.objects()?.firstOrNull{it.optString("screenId")==identity!!.screenId}
                ?:throw UserMessage("YouTube couldn't link this screen. Try again.")
            token=value.optString("loungeToken").also{require(it.length in 8..8192)};tokenAt=now()
        }
        identity!! to token
    }
    suspend fun pairingCode():String {
        val (screen,token)=prepare()
        val result=wire.request("pairing/get_pairing_code",mapOf("ctx" to "pair"),mapOf("lounge_token" to token,"screen_id" to screen.screenId,
            "screen_name" to name.take(100),"access_type" to "permanent","app" to "lb-v4","device_id" to screen.deviceId,"qr" to "1"))
        val code=runCatching{JSONObject(result).optString("code")}.getOrDefault(result.trim()).filterNot(Char::isWhitespace)
        require(code.length in 6..16 && code.all(Char::isDigit))
        return code.chunked(3).joinToString(" ")
    }
    suspend fun discoveryFields():Map<String,String> {
        val identity=prepare().first
        return mapOf("discoveryDeviceId" to identity.deviceId,"loungeDeviceId" to identity.deviceId)
    }
    private fun query(screen:YouTubeScreenIdentity,token:String)=linkedMapOf("device" to "LOUNGE_SCREEN","name" to name.take(100),"id" to screen.deviceId,
        "app" to "lb-v4","theme" to "cl","capabilities" to "dsp,dpa,que","mdxVersion" to "2","VER" to "8","v" to "2","t" to "1",
        "loungeIdToken" to token,"zx" to UUID.randomUUID().toString().replace("-",""))
    private fun decodeInitial(raw:String):List<YouTubeLoungeEvent> {
        val reader=raw.reader().buffered();val result=ArrayList<YouTubeLoungeEvent>()
        while(true){val events=YouTubeLoungeFrames.read(reader)?:break;result+=events;require(result.size<=128)}
        return result
    }
    suspend fun listen(receive:suspend(YouTubeLoungeEvent)->Unit,connection:suspend(Boolean)->Unit={}) {
        val epoch=generation.incrementAndGet();var failures=0
        try {
            while(currentCoroutineContext().isActive && epoch==generation.get()) {
                try {
                    val (screen,token)=prepare()
                    val params=query(screen,token)
                    if(session==null) {
                        val initial=decodeInitial(wire.request("bc/bind",params+mapOf("RID" to rid.incrementAndGet().toString()),mapOf("count" to "0")))
                        val sid=initial.firstOrNull{it.type=="c"}?.value.orEmpty();val gsession=initial.firstOrNull{it.type=="S"}?.value.orEmpty()
                        if(sid.isBlank() || gsession.isBlank())throw UserMessage("YouTube phone connection could not start.")
                        if(epoch!=generation.get())return
                        val next=Session(sid,gsession);session=next;connection(true)
                        for(event in initial){if(event.index>next.aid.get()){next.aid.set(event.index);if(event.type!="c" && event.type!="S")receive(event)}}
                    }
                    val active=session?:continue
                    wire.events(params+mapOf("RID" to "rpc","SID" to active.sid,"gsessionid" to active.gsession,"AID" to active.aid.get().toString(),"CI" to "0","TYPE" to "xmlhttp")){ event->
                        if(epoch!=generation.get() || session!==active)return@events
                        if(event.index>active.aid.get()) {
                            active.aid.set(event.index)
                            if(event.type in setOf("stop","close","gracefulReconnect"))throw UserMessage("YouTube phone connection is reconnecting.")
                            receive(event);failures=0
                        }
                    }
                    delay(500)
                } catch(e:CancellationException){throw e}
                catch(_:Exception) {
                    if(epoch!=generation.get())return
                    session=null;identityLock.withLock{tokenAt=0;token=""};connection(false)
                    failures=(failures+1).coerceAtMost(5);delay((1_000L shl failures).coerceAtMost(30_000))
                }
            }
        } finally { if(epoch==generation.get())session=null }
    }
    suspend fun send(command:String,fields:Map<String,String>)=sendAll(listOf(command to fields))
    suspend fun sendAll(commands:List<Pair<String,Map<String,String>>>)=writeLock.withLock {
        require(commands.size in 1..6)
        val active=session?:return@withLock
        val epoch=generation.get();val (screen,token)=prepare()
        val form=linkedMapOf("count" to commands.size.toString(),"ofs" to ofs.getAndAdd(commands.size).toString())
        commands.forEachIndexed { i,(command,fields)->
            require(command.matches(Regex("[A-Za-z_]{1,64}")))
            form["req${i}__sc"]=command
            fields.forEach{(k,v)->if(k.matches(Regex("[A-Za-z_]{1,64}")) && v.length<=20_000)form["req${i}_$k"]=v}
        }
        if(epoch==generation.get() && session===active)wire.request("bc/bind",query(screen,token)+mapOf("RID" to rid.incrementAndGet().toString(),"SID" to active.sid,"gsessionid" to active.gsession,"AID" to active.aid.get().toString()),form)
        Unit
    }
    suspend fun forget()=identityLock.withLock {
        generation.incrementAndGet();session=null;token="";identity=null
        withContext(Dispatchers.IO){store.write(null)}
    }
}

/** A phone queue holds IDs only; selecting a new item still resolves one native player. */
internal class YouTubeRemoteQueue {
    var ids:List<String> = emptyList();private set
    var index=0;private set
    var listId="";private set
    var ctt="";private set
    fun replace(data:JSONObject):String? {
        val parsed=data.optString("videoIds").splitToSequence(',').filter(YouTubeDecode::validId).distinct().take(120).toList()
        val id=data.optString("videoId").takeIf(YouTubeDecode::validId)
        ids=if(id!=null && id !in parsed)(listOf(id)+parsed).take(120) else parsed
        index=if(id!=null && id in ids)ids.indexOf(id) else data.optInt("currentIndex",0).coerceIn(0,(ids.size-1).coerceAtLeast(0))
        listId=data.optString("listId").take(300);ctt=data.optString("ctt").take(4096)
        return ids.getOrNull(index)
    }
    fun step(direction:Int):String? { val next=index+direction;if(next !in ids.indices)return null;index=next;return ids[index] }
    fun align(id:String?) { val selected=ids.indexOf(id);if(selected>=0)index=selected else clear() }
    fun clear(){ids=emptyList();index=0;listId="";ctt=""}
    companion object {
        fun position(data:JSONObject):Long=data.optString("currentTime",data.optString("newTime","0")).toDoubleOrNull()
            ?.takeIf{it.isFinite()}?.coerceIn(0.0,604800.0)?.let{(it*1000).toLong()}?:0
    }
}
