package xyz.billybishop4.fleet.tv

import java.net.URI
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** TV JSON requests and device pairing, without retaining a browser or the TV site's JavaScript. */
internal class YouTubeClient(
    private val credentials:YouTubeCredentials, private val transport:YouTubeTransport=YouTubeHttp(),
    internal val origin:String="https://www.youtube.com", private val now:()->Long=System::currentTimeMillis,
) {
    private val configMutex=Mutex();private var config:YouTubeBootstrap?=null;private var configAt=0L
    private val accountMutex=Mutex();private var loaded=false;private var session:YouTubeSession?=null
    private var accountEpoch=0
    private val webMutex=Mutex();private var webConfig:Pair<String,String>?=null;private var webAt=0L
    var accountStorageError=false;private set
    private suspend fun bootstrap(signIn:Boolean=false):YouTubeBootstrap=configMutex.withLock {
        var cached=config
        if(cached==null || now()-configAt !in 0..900_000L) {
            val response=transport.request("$origin/tv",headers=mapOf("User-Agent" to YouTubeHttp.USER_AGENT))
            if(response.status !in 200..299)throw HttpFailure(response.status)
            cached=withContext(Dispatchers.Default) { YouTubeDecode.bootstrap(response.text,origin) }
            config=cached;configAt=now()
        }
        if(signIn && cached!!.clientId.isBlank()) {
            val response=transport.request(cached!!.scriptUrl)
            if(response.status !in 200..299)throw HttpFailure(response.status)
            val (id,secret)=withContext(Dispatchers.Default) { YouTubeDecode.deviceClient(response.text) }
            cached=cached!!.copy(clientId=id,clientSecret=secret);config=cached
        }
        cached!!
    }
    private suspend fun loadLocked():YouTubeSession? {
        if(!loaded) {
            session=withContext(Dispatchers.IO) { runCatching { credentials.read() }.getOrElse { accountStorageError=true;null } }
            loaded=true
        }
        return session
    }
    suspend fun account():YouTubeSession?=accountMutex.withLock { loadLocked() }
    suspend fun signOut()=accountMutex.withLock {
        withContext(Dispatchers.IO) { credentials.write(null) }
        accountEpoch++;session=null;loaded=true;accountStorageError=false
    }
    private suspend fun access(force:Boolean=false):String?=accountMutex.withLock {
        val current=loadLocked()?:return@withLock null
        if(!force && current.expiresAtMs>now()+60_000)return@withLock current.accessToken
        val client=bootstrap(true)
        val result=oauth("token",JSONObject().put("client_id",client.clientId).put("client_secret",client.clientSecret)
            .put("refresh_token",current.refreshToken).put("grant_type","refresh_token"))
        val reason=result.opt("error") as? String
        if(reason!=null) {
            if(reason=="invalid_grant") { withContext(Dispatchers.IO){credentials.write(null)};session=null;accountEpoch++ }
            throw YouTubeAuthError(reason)
        }
        val next=current.copy(accessToken=result.getString("access_token"),expiresAtMs=now()+result.optLong("expires_in",3600).coerceIn(60,86400)*1000)
        withContext(Dispatchers.IO){credentials.write(next)};session=next
        next.accessToken
    }
    private fun context(client:YouTubeBootstrap)=JSONObject().put("client",JSONObject()
        .put("clientName","TVHTML5").put("clientVersion",client.version).put("hl","en").put("gl","US")
        .put("userAgent",YouTubeHttp.USER_AGENT).put("browserName","Cobalt").put("browserVersion","25.lts.3.1062949-gold")
        .put("visitorData",client.visitor).put("tvAppInfo",JSONObject().put("appQuality","TV_APP_QUALITY_FULL_ANIMATION").put("zylonLeftNav",true)))
    private suspend fun api(method:String,body:JSONObject,retry:Boolean=true):JSONObject {
        val client=bootstrap();val token=access()
        val headers=buildMap { put("Origin",origin);put("Referer","$origin/tv");put("X-Youtube-Client-Name","7");put("X-Youtube-Client-Version",client.version)
            if(token!=null)put("Authorization","Bearer $token") }
        val response=transport.request("$origin/youtubei/v1/$method?prettyPrint=false",body.put("context",context(client)).toString(),headers)
        if(response.status==401 && token!=null && retry) { access(true);return api(method,body,false) }
        if(response.status==401 && token!=null)throw YouTubeAuthError("unauthorized")
        if(response.status !in 200..299)throw HttpFailure(response.status)
        return withContext(Dispatchers.Default){JSONObject(response.text)}
    }
    suspend fun page(feed:YouTubeFeed,query:String="",continuation:String?=null):YouTubePage {
        if(feed==YouTubeFeed.SHORTS)return shorts(continuation)
        val body=JSONObject()
        if(continuation!=null)body.put("continuation",continuation)
        else if(feed==YouTubeFeed.SEARCH)body.put("query",query.trim().take(200))
        else body.put("browseId","default")
        val result=api(if(feed==YouTubeFeed.SEARCH)"search" else "browse",body)
        return withContext(Dispatchers.Default){YouTubeDecode.page(result)}
    }
    suspend fun watchDetails(videoId:String):YouTubeWatchDetails {
        require(YouTubeDecode.validId(videoId))
        val result=api("next",JSONObject().put("videoId",videoId))
        return withContext(Dispatchers.Default){YouTubeWatchDecode.details(result,videoId)}
    }
    suspend fun comments(continuation:String):YouTubeCommentsPage {
        require(continuation.isNotBlank() && continuation.length<=20_000)
        val result=api("next",JSONObject().put("continuation",continuation))
        return withContext(Dispatchers.Default){YouTubeWatchDecode.comments(result)}
    }
    suspend fun heatmap(videoId:String):List<YouTubeHeatMarker> {
        require(YouTubeDecode.validId(videoId))
        // Replay markers are supplied by WEB, independently of the TV account. No credentials go to this request.
        val config=webMutex.withLock {
            if(webConfig==null || now()-webAt !in 0..900_000L) {
                val response=transport.request("$origin/",headers=mapOf("User-Agent" to WEB_AGENT))
                if(response.status !in 200..299)throw HttpFailure(response.status)
                val parsed=withContext(Dispatchers.Default) {
                    val version=Regex("\"INNERTUBE_CLIENT_VERSION\"\\s*:\\s*\"([^\"]+)\"").find(response.text)?.groupValues?.get(1)
                        ?:throw UserMessage("Replay data is unavailable.")
                    val visitor=Regex("\"VISITOR_DATA\"\\s*:\\s*\"([^\"]*)\"").find(response.text)?.groupValues?.get(1).orEmpty()
                    version to visitor
                }
                webConfig=parsed;webAt=now()
            }
            webConfig!!
        }
        val client=JSONObject().put("clientName","WEB").put("clientVersion",config.first).put("hl","en").put("gl","US")
            .put("visitorData",config.second).put("userAgent",WEB_AGENT)
        val body=JSONObject().put("context",JSONObject().put("client",client)).put("videoId",videoId)
        val response=transport.request("$origin/youtubei/v1/next?prettyPrint=false",body.toString(),mapOf("User-Agent" to WEB_AGENT,
            "Origin" to origin,"Referer" to "$origin/watch?v=$videoId","X-Youtube-Client-Name" to "1","X-Youtube-Client-Version" to config.first))
        if(response.status !in 200..299)throw HttpFailure(response.status)
        return withContext(Dispatchers.Default){YouTubeWatchDecode.heatmap(JSONObject(response.text))}
    }
    private suspend fun shorts(continuation:String?):YouTubePage=coroutineScope {
        val initial=ArrayList<YouTubeVideo>()
        val cursor=continuation?.let { JSONObject(it) }
        var nextSequence=cursor?.optString("sequence").orEmpty()
        var pending=cursor?.optJSONArray("pending")?.objects().orEmpty()
        if(continuation==null) {
            val first=api("reel/reel_item_watch",JSONObject().put("disablePlayerResponse",true).put("inputType","REEL_WATCH_INPUT_TYPE_SEEDLESS").put("params","CA8%3D"))
            YouTubeDecode.reel(first)?.let(initial::add)
            nextSequence=first.optString("sequenceContinuation")
        }
        if(pending.isEmpty() && nextSequence.isNotBlank()) {
            val sequence=api("reel/reel_watch_sequence",JSONObject().put("sequenceParams",nextSequence))
            pending=sequence.optJSONArray("entries")?.objects()?.mapNotNull { it.optJSONObject("command")?.optJSONObject("reelWatchEndpoint") }
                ?.filter { YouTubeDecode.validId(it.optString("videoId")) }?.take(24).orEmpty()
            nextSequence=sequence.optString("continuation")
        }
        val semaphore=Semaphore(3)
        val details=pending.take(6).map { endpoint -> async {
            semaphore.withPermit {
                try {
                    val response=api("reel/reel_item_watch",JSONObject().put("disablePlayerResponse",true)
                        .put("params",endpoint.optString("params")).put("playerRequest",JSONObject().put("videoId",endpoint.optString("videoId"))))
                    withContext(Dispatchers.Default) { YouTubeDecode.reel(response,endpoint) }
                } catch(e:CancellationException){throw e}
                catch(e:YouTubeAuthError){throw e}
                catch(_:Exception){null}
            }
        } }.awaitAll().filterNotNull()
        val remaining=pending.drop(6).map { JSONObject().put("videoId",it.optString("videoId")).put("params",it.optString("params"))
            .put("thumbnail",it.optJSONObject("thumbnail")) }
        val next=if(remaining.isNotEmpty() || nextSequence.isNotBlank())JSONObject().put("sequence",nextSequence).put("pending",JSONArray(remaining)).toString() else null
        if(initial.isEmpty() && details.isEmpty() && pending.isNotEmpty())throw UserMessage("Couldn't load these Shorts. Try again.")
        YouTubePage((initial+details).distinctBy { it.id },next)
    }
    private suspend fun oauth(path:String,body:JSONObject):JSONObject {
        val result=transport.request("$origin/o/oauth2/$path",body.toString())
        if(result.status !in 200..299 && result.status!=400)throw HttpFailure(result.status)
        return withContext(Dispatchers.Default){JSONObject(result.text)}
    }
    companion object {
        private const val WEB_AGENT="Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }
    suspend fun beginSignIn():YouTubeDeviceCode {
        val client=bootstrap(true)
        val result=oauth("device/code",JSONObject().put("client_id",client.clientId).put("device_id",UUID.randomUUID().toString())
            .put("model_name","ytlr::").put("scope","http://gdata.youtube.com https://www.googleapis.com/auth/youtube-paid-content"))
        (result.opt("error") as? String)?.let { throw YouTubeAuthError(it) }
        val address=result.optString("verification_url").ifBlank { result.optString("verification_uri") }
        val u=URI(address)
        require(u.scheme=="https" && u.userInfo==null && u.host in setOf("www.google.com","google.com","accounts.google.com","www.youtube.com","youtube.com"))
        return YouTubeDeviceCode(result.getString("device_code"),result.getString("user_code"),address,
            now()+result.optLong("expires_in",1800).coerceIn(20,1800)*1000,result.optInt("interval",5).coerceIn(5,60))
    }
    suspend fun pollSignIn(code:YouTubeDeviceCode):YouTubeSession? {
        if(now()>=code.expiresAtMs)throw YouTubeAuthError("expired_token")
        val epoch=accountEpoch
        val client=bootstrap(true)
        val result=oauth("token",JSONObject().put("code",code.deviceCode).put("client_id",client.clientId)
            .put("client_secret",client.clientSecret).put("grant_type","http://oauth.net/grant_type/device/1.0"))
        when(val error=result.opt("error") as? String) {
            "authorization_pending" -> return null
            null -> Unit
            else -> throw YouTubeAuthError(error)
        }
        val next=YouTubeSession(result.getString("access_token"),result.getString("refresh_token"),now()+result.optLong("expires_in",3600).coerceIn(60,86400)*1000)
        accountMutex.withLock {
            if(epoch!=accountEpoch)throw YouTubeAuthError("access_denied")
            withContext(Dispatchers.IO){credentials.write(next)};session=next;loaded=true;accountStorageError=false
        }
        return next
    }
}
