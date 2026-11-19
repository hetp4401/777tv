package xyz.billybishop4.fleet.tv

import java.net.URI
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** TV JSON requests and device pairing, without retaining a browser or the TV site's JavaScript. */
internal class YouTubeClient(
    private val credentials:YouTubeCredentials, private val transport:YouTubeTransport=YouTubeHttp.tvSession(),
    internal val origin:String="https://www.youtube.com", private val now:()->Long=System::currentTimeMillis,
    private val localHistory:()->List<Progress> = { emptyList() },
    private val guestRequestTimeoutMs:Long=5_000,
) : YouTubeHistoryBackend {
    private val configMutex=Mutex();private var config:YouTubeBootstrap?=null;private var configAt=0L
    private val accountMutex=Mutex();private var loaded=false;private var session:YouTubeSession?=null
    private var accountEpoch=0
    @Volatile private var publishedAccountEpoch:Int?=null
    private val refreshMutex=Mutex()
    private val accountRequests=mutableSetOf<Job>()
    private val trackingTransport:YouTubeTransport=if(transport is YouTubeHttp)YouTubeHttp(YouTubeHttp.trackingClient) else transport
    private val suggestionTransport:YouTubeTransport=if(transport is YouTubeHttp)YouTubeHttp(YouTubeHttp.suggestionClient,64*1024) else transport
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
            loaded=true;publishedAccountEpoch=accountEpoch.takeIf{session!=null}
        }
        return session
    }
    suspend fun account():YouTubeSession?=accountMutex.withLock { loadLocked() }
    suspend fun accountScope():YouTubeAccountScope?=accountMutex.withLock { loadLocked()?.let{YouTubeAccountScope(accountEpoch)} }
    suspend fun accountScopeCurrent(scope:YouTubeAccountScope):Boolean=accountMutex.withLock { loadLocked()!=null && accountEpoch==scope.epoch }
    private fun requireAccountLocked(scope:YouTubeAccountScope):YouTubeSession {
        if(session==null || accountEpoch!=scope.epoch)throw YouTubeAuthError("unauthorized")
        return session!!
    }
    private suspend fun <T> scoped(scope:YouTubeAccountScope,action:suspend ()->T):T {
        val job=currentCoroutineContext()[Job]
        accountMutex.withLock { requireAccountLocked(scope);if(job!=null)accountRequests.add(job) }
        try {
            val result=action()
            accountMutex.withLock { requireAccountLocked(scope) }
            return result
        } finally {
            withContext(NonCancellable) { accountMutex.withLock { if(job!=null)accountRequests.remove(job) } }
        }
    }
    suspend fun signOut() {
        val pending=accountMutex.withLock {
            withContext(Dispatchers.IO) { credentials.write(null) }
            accountEpoch++;session=null;loaded=true;accountStorageError=false;publishedAccountEpoch=null
            accountRequests.toList().also{accountRequests.clear()}
        }
        val own=currentCoroutineContext()[Job];pending.filter{it!==own}.forEach{it.cancel()}
    }
    private suspend fun revoke(scope:YouTubeAccountScope) {
        val pending=accountMutex.withLock {
            if(accountEpoch!=scope.epoch)return@withLock emptyList<Job>()
            withContext(Dispatchers.IO) { runCatching{credentials.write(null)}.onFailure{accountStorageError=true} }
            accountEpoch++;session=null;loaded=true;publishedAccountEpoch=null
            accountRequests.toList().also{accountRequests.clear()}
        }
        val own=currentCoroutineContext()[Job];pending.filter{it!==own}.forEach{it.cancel()}
    }
    private suspend fun access(scope:YouTubeAccountScope,force:Boolean=false):String=refreshMutex.withLock {
        val current=accountMutex.withLock { requireAccountLocked(scope) }
        if(!force && current.expiresAtMs>now()+60_000)return@withLock current.accessToken
        // Network I/O never holds accountMutex: sign-out can immediately cancel
        // an old account's refresh/request and prevent its result being committed.
        val client=bootstrap(true)
        val result=oauth("token",JSONObject().put("client_id",client.clientId).put("client_secret",client.clientSecret)
            .put("refresh_token",current.refreshToken).put("grant_type","refresh_token"))
        val reason=result.opt("error") as? String
        if(reason!=null) { if(reason=="invalid_grant")revoke(scope);throw YouTubeAuthError(reason) }
        val next=current.copy(accessToken=result.getString("access_token"),expiresAtMs=now()+result.optLong("expires_in",3600).coerceIn(60,86400)*1000)
        accountMutex.withLock {
            requireAccountLocked(scope)
            withContext(Dispatchers.IO){credentials.write(next)};session=next
        }
        next.accessToken
    }
    private fun context(client:YouTubeBootstrap):JSONObject {
        // The TV application supplies its locale, device capabilities and
        // experiment/config tokens. Keep those instead of inventing a region.
        val context=client.context?.let{JSONObject(it.toString())}?:JSONObject()
        val device=context.optJSONObject("client")?:JSONObject().also{context.put("client",it)}
        device.put("clientName","TVHTML5").put("clientVersion",client.version).put("visitorData",client.visitor)
        if(!device.has("hl"))device.put("hl","en")
        if(!device.has("gl"))device.put("gl","US")
        if(!device.has("userAgent"))device.put("userAgent",YouTubeHttp.USER_AGENT)
        if(!device.has("browserName"))device.put("browserName","Cobalt")
        if(!device.has("browserVersion"))device.put("browserVersion","25.lts.3.1062949-gold")
        if(!device.has("tvAppInfo"))device.put("tvAppInfo",JSONObject().put("appQuality","TV_APP_QUALITY_FULL_ANIMATION").put("zylonLeftNav",true))
        return context
    }
    private suspend fun api(method:String,body:JSONObject,authenticated:Boolean=true,required:YouTubeAccountScope?=null,web:Boolean=false):JSONObject {
        val scope=required?:if(authenticated)accountScope() else null
        suspend fun request():JSONObject {
            val client=if(web)null else bootstrap();val webClient=if(web)webBootstrap() else null
            var token=scope?.let{access(it)}
            repeat(2) { attempt->
                val headers=buildMap {
                    put("Origin",origin);put("Referer",if(web)"$origin/" else "$origin/tv")
                    put("X-Youtube-Client-Name",if(web)"1" else "7");put("X-Youtube-Client-Version",client?.version?:webClient!!.first)
                    client?.visitor?.takeIf{it.isNotBlank()}?.let{put("X-Goog-Visitor-Id",it)}
                    if(web)put("User-Agent",WEB_AGENT)
                    if(token!=null)put("Authorization","Bearer $token")
                }
                body.put("context",if(web)webContext(webClient!!) else context(client!!))
                val response=transport.request("$origin/youtubei/v1/$method?prettyPrint=false",body.toString(),headers)
                if(response.status==401 && scope!=null) {
                    if(attempt==0)token=access(scope,true)
                    else { revoke(scope);throw YouTubeAuthError("unauthorized") }
                } else {
                    if(response.status !in 200..299)throw HttpFailure(response.status)
                    val decoded=withContext(Dispatchers.Default){JSONObject(response.text)}
                    // The TV client updates both its visitor header and request
                    // context when an anonymous response rotates that identity.
                    if(client!=null && scope==null)decoded.optJSONObject("responseContext")?.optString("visitorData")
                        ?.takeIf{it.isNotBlank() && it.length<=8192}?.let { visitor->
                            configMutex.withLock {
                                if(config?.version==client.version && config?.visitor==client.visitor)config=config?.copy(visitor=visitor)
                            }
                        }
                    return decoded
                }
            }
            error("Unreachable")
        }
        return if(scope==null)request() else scoped(scope){request()}
    }
    suspend fun page(feed:YouTubeFeed,query:String="",continuation:String?=null):YouTubePage {
        val signedIn=account()!=null
        if(feed==YouTubeFeed.HOME && !signedIn)return guestHome(continuation)
        if(feed==YouTubeFeed.HISTORY && !signedIn)return YouTubePage(history().map(::historyVideo),message="History on this TV")
        if(feed.requiresAccount && !signedIn)return YouTubePage(emptyList(),message="Sign in to see this part of your YouTube account.")
        if(feed==YouTubeFeed.SHORTS)return shorts(continuation)
        val body=JSONObject()
        if(continuation!=null)body.put("continuation",continuation)
        else if(feed==YouTubeFeed.SEARCH)body.put("query",query.trim().take(200))
        else when(feed) {
            YouTubeFeed.SUBSCRIPTIONS->body.put("browseId","FEsubscriptions")
            YouTubeFeed.HISTORY->body.put("browseId","FEhistory")
            YouTubeFeed.WATCH_LATER->body.put("browseId","FEmy_youtube").put("params","cAc%3D")
            YouTubeFeed.LIKED->body.put("browseId","VLLL")
            else->body.put("browseId","default")
        }
        val result=api(if(feed==YouTubeFeed.SEARCH)"search" else "browse",body)
        return withContext(Dispatchers.Default){when(feed){YouTubeFeed.HOME->YouTubeDecode.home(result);YouTubeFeed.SEARCH->YouTubeSearchPages.decode(result);else->YouTubeDecode.page(result)}}
    }
    /** Public TV query completion; never attach the account bearer or visitor
     * identity. Failure must leave the local keyboard and recent searches usable. */
    suspend fun searchSuggestions(query:String):List<String> {
        val text=YouTubeSuggestions.clean(query)
        if(text.isBlank())return emptyList()
        val base=if(origin=="https://www.youtube.com")"https://clients1.google.com" else origin
        val url="$base/complete/search".toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("client","youtube-lr")?.addQueryParameter("ds","yt")?.addQueryParameter("xhr","t")
            ?.addQueryParameter("oe","utf-8")?.addQueryParameter("xssi","t")
            ?.addQueryParameter("hl",config?.context?.optJSONObject("client")?.optString("hl")?.takeIf{it.isNotBlank()}?:"en")
            ?.addQueryParameter("gl",config?.context?.optJSONObject("client")?.optString("gl")?.takeIf{it.isNotBlank()}?:"US")
            ?.addQueryParameter("q",text)?.build()?.toString()?:return emptyList()
        return try {
            withTimeoutOrNull(4_000) {
                val response=suggestionTransport.request(url)
                if(response.status !in 200..299)emptyList()
                else withContext(Dispatchers.Default){YouTubeSuggestions.decode(response.text,text)}
            }.orEmpty()
        }catch(e:CancellationException){throw e}
        catch(_:Exception){emptyList()}
    }
    private suspend fun history():List<Progress> = withContext(Dispatchers.IO) {
        localHistory().filter{YouTubeDecode.validId(it.title.id) && it.title.type=="youtube" && it.positionMs>0}
            .sortedByDescending{it.updated}.distinctBy{it.title.id}.take(50)
    }
    private fun historyVideo(value:Progress)=YouTubeVideo(value.title.id,value.title.name,value.title.poster,metadata=value.title.description)
    private suspend fun guestHome(continuation:String?):YouTubePage {
        // TizenTube displays the TV application's actual anonymous Home. Topic
        // charts and local watch-next seeds are different feeds, not substitutes.
        val body=JSONObject().apply { if(continuation!=null)put("continuation",continuation) else put("browseId","default") }
        val result=withTimeoutOrNull(guestRequestTimeoutMs.coerceIn(1,5_000)) {
            api("browse",body,authenticated=false)
        }?:throw UserMessage("Recommendations couldn't load. Check your connection and try again.")
        result.opt("error")?.takeUnless{it===JSONObject.NULL}?.let { error->
            val code=(error as? JSONObject)?.optInt("code",0)?:0
            if(code in 400..599)throw HttpFailure(code)
            throw UserMessage("Recommendations couldn't load. Try again or use Search.")
        }
        return withContext(Dispatchers.Default) {
            val page=YouTubeDecode.home(result)
            if(page.videos.isNotEmpty())page else page.copy(message=page.message.ifBlank {
                "YouTube hasn't supplied recommendations for this signed-out session. Try Search or Refresh."
            })
        }
    }
    suspend fun watchDetails(videoId:String,expectedScope:YouTubeAccountScope?=null):YouTubeWatchDetails {
        require(YouTubeDecode.validId(videoId))
        val result=api("next",JSONObject().put("videoId",videoId),required=expectedScope)
        return withContext(Dispatchers.Default){YouTubeWatchDecode.details(result,videoId)}
    }
    suspend fun interaction(videoId:String,expectedScope:YouTubeAccountScope?=null)=watchDetails(videoId,expectedScope)
    private suspend fun signedScope(expected:YouTubeAccountScope?=null):YouTubeAccountScope {
        val current=accountScope()?:throw YouTubeAuthError("unauthorized")
        if(expected!=null && current!=expected)throw YouTubeAuthError("unauthorized")
        return expected?:current
    }
    /** Only explicit user actions reach these RPCs; loading a tile never mutates an account. */
    suspend fun rate(videoId:String,rating:YouTubeRating,previous:YouTubeRating?=null,expectedScope:YouTubeAccountScope?=null) {
        require(YouTubeDecode.validId(videoId))
        val scope=signedScope(expectedScope)
        val existing=if(rating==YouTubeRating.NONE)previous?:YouTubeSocialDecode.rating(api("next",JSONObject().put("videoId",videoId),required=scope)) else null
        val endpoint=when(rating) {
            YouTubeRating.LIKE->"like/like";YouTubeRating.DISLIKE->"like/dislike"
            YouTubeRating.NONE->when(existing){YouTubeRating.DISLIKE->"like/removedislike";YouTubeRating.LIKE->"like/removelike"
                YouTubeRating.NONE->return;null->throw UserMessage("YouTube couldn't verify your rating. Reopen video actions and try again.")}
        }
        YouTubeSocialDecode.requireAccepted(api(endpoint,JSONObject().put("target",JSONObject().put("videoId",videoId)),required=scope))
    }
    suspend fun subscribe(channel:YouTubeChannel,subscribed:Boolean,expectedScope:YouTubeAccountScope?=null) {
        require(YouTubeSocialDecode.validChannelId(channel.id))
        val params=if(subscribed)channel.subscribeParams else channel.unsubscribeParams
        require(params.length<=20_000)
        val body=JSONObject().put("channelIds",JSONArray().put(channel.id))
        if(params.isNotBlank())body.put("params",params)
        YouTubeSocialDecode.requireAccepted(api(if(subscribed)"subscription/subscribe" else "subscription/unsubscribe",body,required=signedScope(expectedScope)))
    }
    suspend fun playlists(videoId:String,expectedScope:YouTubeAccountScope?=null):List<YouTubePlaylist> {
        require(YouTubeDecode.validId(videoId))
        val result=api("playlist/get_add_to_playlist",JSONObject().put("videoIds",JSONArray().put(videoId)),required=signedScope(expectedScope))
        YouTubeSocialDecode.requireAccepted(result)
        return withContext(Dispatchers.Default){YouTubeSocialDecode.playlists(result)}
    }
    suspend fun playlistCollection(continuation:String?=null):YouTubePlaylistCollection {
        require(continuation==null || continuation.length in 1..20_000)
        val scope=signedScope()
        val body=if(continuation==null)JSONObject().put("browseId","FEplaylist_aggregation") else JSONObject().put("continuation",continuation)
        val response=api("browse",body,required=scope)
        YouTubeSocialDecode.requireAccepted(response)
        return withContext(Dispatchers.Default){YouTubeLibraryDecode.collection(response)}
    }
    suspend fun playlistVideos(playlistId:String,continuation:String?=null):YouTubePage {
        require(YouTubeSocialDecode.validPlaylistId(playlistId) && (continuation==null || continuation.length in 1..20_000))
        val scope=signedScope()
        val body=if(continuation==null)JSONObject().put("browseId","VL$playlistId") else JSONObject().put("continuation",continuation)
        val response=api("browse",body,required=scope)
        YouTubeSocialDecode.requireAccepted(response)
        return withContext(Dispatchers.Default){YouTubeDecode.page(response,limit=120)}
    }
    suspend fun setSaved(videoId:String,playlistId:String,saved:Boolean,expectedScope:YouTubeAccountScope?=null) {
        require(YouTubeDecode.validId(videoId) && YouTubeSocialDecode.validPlaylistId(playlistId))
        val action=JSONObject().put("action",if(saved)"ACTION_ADD_VIDEO" else "ACTION_REMOVE_VIDEO_BY_VIDEO_ID")
            .put(if(saved)"addedVideoId" else "removedVideoId",videoId)
        YouTubeSocialDecode.requireAccepted(api("browse/edit_playlist",JSONObject().put("playlistId",playlistId)
            .put("actions",JSONArray().put(action)),required=signedScope(expectedScope)))
    }
    suspend fun createPlaylist(name:String,videoId:String?=null,expectedScope:YouTubeAccountScope?=null):String {
        val title=name.trim();require(title.isNotEmpty() && title.length<=150 && (videoId==null || YouTubeDecode.validId(videoId)))
        val body=JSONObject().put("title",title).put("privacyStatus","PRIVATE")
        if(videoId!=null)body.put("videoIds",JSONArray().put(videoId))
        val result=api("playlist/create",body,required=signedScope(expectedScope),web=true)
        YouTubeSocialDecode.requireAccepted(result)
        return result.optString("playlistId").takeIf(YouTubeSocialDecode::validPlaylistId)
            ?:throw UserMessage("YouTube didn't return the new playlist. Check your account before trying again.")
    }
    suspend fun channel(channelId:String,tab:YouTubeChannelTab?=null,continuation:String?=null):YouTubeChannelPage {
        require(YouTubeSocialDecode.validChannelId(channelId) && (continuation==null || continuation.length in 1..20_000))
        require(tab==null || tab.params.length<=20_000)
        val body=if(continuation!=null)JSONObject().put("continuation",continuation) else JSONObject().put("browseId",channelId).apply {
            if(tab?.params?.isNotBlank()==true)put("params",tab.params)
        }
        val result=api("browse",body)
        return withContext(Dispatchers.Default){YouTubeSocialDecode.channel(result,channelId,tab)}
    }
    override fun historyAccountEpoch():Int?=publishedAccountEpoch
    override suspend fun historySession(videoId:String,nonce:String,expectedEpoch:Int):YouTubeHistorySession? {
        require(YouTubeDecode.validId(videoId) && Regex("[A-Za-z0-9_-]{16}").matches(nonce))
        val scope=accountScope()?.takeIf{it.epoch==expectedEpoch}?:return null
        val response=api("player",JSONObject().put("videoId",videoId).put("cpn",nonce)
            .put("contentCheckOk",true).put("racyCheckOk",true),required=scope)
        if(!accountScopeCurrent(scope))return null
        return withContext(Dispatchers.Default){decodeHistorySession(response,scope.epoch,videoId,nonce,origin)}
    }
    override suspend fun historySessionCurrent(session:YouTubeHistorySession)=accountScopeCurrent(YouTubeAccountScope(session.epoch))
    override suspend fun sendHistory(session:YouTubeHistorySession,url:String):Boolean {
        if(!validHistoryTrackingUrl(url,origin))return false
        val target=url.toHttpUrlOrNull()?:return false
        if(target.queryParameterValues("docid")!=listOf(session.videoId) || target.queryParameterValues("cpn")!=listOf(session.nonce))return false
        val scope=YouTubeAccountScope(session.epoch)
        if(!accountScopeCurrent(scope))return false
        return scoped(scope) {
            val token=access(scope)
            val response=trackingTransport.request(url,headers=mapOf("Authorization" to "Bearer $token","Origin" to origin,"Referer" to "$origin/tv"))
            response.status in 200..299
        }
    }
    suspend fun comments(continuation:String):YouTubeCommentsPage {
        require(continuation.isNotBlank() && continuation.length<=20_000)
        val result=api("next",JSONObject().put("continuation",continuation))
        return withContext(Dispatchers.Default){YouTubeWatchDecode.comments(result)}
    }
    private suspend fun webBootstrap():Pair<String,String> = webMutex.withLock {
            if(webConfig==null || now()-webAt !in 0..900_000L) {
                val response=transport.request("$origin/",headers=mapOf("User-Agent" to WEB_AGENT))
                if(response.status !in 200..299)throw HttpFailure(response.status)
                val parsed=withContext(Dispatchers.Default) {
                    val version=Regex("\"INNERTUBE_CLIENT_VERSION\"\\s*:\\s*\"([^\"]+)\"").find(response.text)?.groupValues?.get(1)
                        ?:throw UserMessage("YouTube web data is unavailable.")
                    val visitor=Regex("\"VISITOR_DATA\"\\s*:\\s*\"([^\"]*)\"").find(response.text)?.groupValues?.get(1).orEmpty()
                    version to visitor
                }
                webConfig=parsed;webAt=now()
            }
            webConfig!!
    }
    private fun webContext(config:Pair<String,String>):JSONObject {
        val client=JSONObject().put("clientName","WEB").put("clientVersion",config.first).put("hl","en").put("gl","US")
            .put("visitorData",config.second).put("userAgent",WEB_AGENT)
        return JSONObject().put("client",client)
    }
    suspend fun heatmap(videoId:String):List<YouTubeHeatMarker> {
        require(YouTubeDecode.validId(videoId))
        // Replay markers are supplied by WEB, independently of the TV account. No credentials go to this request.
        val config=webBootstrap()
        val body=JSONObject().put("context",webContext(config)).put("videoId",videoId)
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
        val epoch=accountMutex.withLock { loadLocked();accountEpoch }
        val client=bootstrap(true)
        val result=oauth("token",JSONObject().put("code",code.deviceCode).put("client_id",client.clientId)
            .put("client_secret",client.clientSecret).put("grant_type","http://oauth.net/grant_type/device/1.0"))
        when(val error=result.opt("error") as? String) {
            "authorization_pending" -> return null
            null -> Unit
            else -> throw YouTubeAuthError(error)
        }
        val next=YouTubeSession(result.getString("access_token"),result.getString("refresh_token"),now()+result.optLong("expires_in",3600).coerceIn(60,86400)*1000)
        val pending=accountMutex.withLock {
            if(epoch!=accountEpoch)throw YouTubeAuthError("access_denied")
            withContext(Dispatchers.IO){credentials.write(next)};session=next;loaded=true;accountStorageError=false;accountEpoch++;publishedAccountEpoch=accountEpoch
            accountRequests.toList().also{accountRequests.clear()}
        }
        val own=currentCoroutineContext()[Job];pending.filter{it!==own}.forEach{it.cancel()}
        return next
    }
}
