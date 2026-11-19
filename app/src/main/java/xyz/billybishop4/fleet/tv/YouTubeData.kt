package xyz.billybishop4.fleet.tv

import java.net.URI
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

internal enum class YouTubeFeed {
    HOME, SHORTS, SEARCH, SUBSCRIPTIONS, HISTORY, WATCH_LATER, LIKED, CHANNEL, PLAYLISTS, PLAYLIST;
    val library get()=this==HISTORY || this==WATCH_LATER || this==LIKED || this==PLAYLISTS || this==PLAYLIST
    val requiresAccount get()=this==SUBSCRIPTIONS || this==WATCH_LATER || this==LIKED || this==PLAYLISTS || this==PLAYLIST
}
internal data class YouTubeVideo(
    val id: String, val name: String, val thumbnail: String = "", val channel: String = "",
    val metadata: String = "", val duration: String = "", val isShort: Boolean = false, val live: Boolean = false,
    val channelId:String="",val resumePositionMs:Long?=null,val watchedPercent:Int?=null,
) {
    fun title() = Title(id, name, "youtube", poster = thumbnail, description = listOf(channel, metadata).filter(String::isNotBlank).joinToString(" · "))
}
internal data class YouTubeShelf(val key:String, val name:String, val videos:List<YouTubeVideo>, val portrait:Boolean=false)
internal data class YouTubePage(
    val videos: List<YouTubeVideo>, val continuation: String? = null, val message: String = "",
    val shelves:List<YouTubeShelf> = emptyList(),
)
internal data class YouTubeDeviceCode(
    val deviceCode: String, val userCode: String, val verificationUrl: String,
    val expiresAtMs: Long, val intervalSeconds: Int,
) { override fun toString() = "YouTubeDeviceCode(expiresAtMs=$expiresAtMs)" }
internal data class YouTubeSession(val accessToken: String, val refreshToken: String, val expiresAtMs: Long, val name: String = "YouTube account") {
    override fun toString() = "YouTubeSession(signedIn=true)"
    fun json() = JSONObject().put("access", accessToken).put("refresh", refreshToken).put("expires", expiresAtMs).put("name", name)
    companion object {
        fun decode(j: JSONObject) = YouTubeSession(j.getString("access"), j.getString("refresh"), j.getLong("expires"), j.optString("name", "YouTube account"))
            .also { require(it.accessToken.isNotBlank() && it.refreshToken.isNotBlank()) }
    }
}
internal interface YouTubeCredentials {
    fun read(): YouTubeSession?
    fun write(session: YouTubeSession?)
}
internal data class YouTubeBootstrap(val version: String, val visitor: String, val scriptUrl: String, val clientId: String = "", val clientSecret: String = "",val context:JSONObject?=null) {
    override fun toString() = "YouTubeBootstrap(version=$version)"
}
internal class YouTubeAuthError(val reason: String) : UserMessage(when(reason) {
    "expired_token" -> "This code expired. Get a new code to sign in."
    "access_denied" -> "Sign-in was declined. You can try again."
    "invalid_grant", "unauthorized" -> "Your YouTube session expired. Sign in again."
    else -> "YouTube sign-in is unavailable. Try again."
})

/** Parse modern TV lockups and older renderer shapes, retaining only lightweight card data. */
internal object YouTubeDecode {
    private val videoId = Regex("[A-Za-z0-9_-]{11}")
    fun validId(value: String) = videoId.matches(value)
    fun text(value: Any?): String = when(value) {
        is String -> value
        is JSONObject -> (value.opt("simpleText") as? String) ?: (value.opt("content") as? String)
            ?: value.optJSONArray("runs")?.let { a -> (0 until a.length()).joinToString("") { a.optJSONObject(it)?.optString("text").orEmpty() } }.orEmpty()
        else -> ""
    }.take(1500)
    private fun url(value: String): String = (if(value.startsWith("//")) "https:$value" else value)
        .takeIf(::isHttp).orEmpty()
    internal fun image(value: Any?): String {
        val images = find(value, setOf("thumbnails", "sources")).filterIsInstance<JSONArray>()
            .flatMap { it.objects() }.filter { it.optString("url").isNotBlank() }
        val pick = images.filter { it.optInt("width", 320) <= 720 }.maxByOrNull { it.optInt("width", 0) }
            ?: images.minByOrNull { it.optInt("width", 100000) }
        return url(pick?.optString("url").orEmpty())
    }
    /** Bounded traversal also skips menus and promoted branches, which contain duplicate video IDs. */
    fun find(root: Any?, names: Set<String>, limit: Int = 1000): List<Any> {
        val queue = ArrayDeque<Pair<Any, Int>>(); if(root!=null)queue.add(root to 0)
        val found = ArrayList<Any>(); var visited=0
        while(queue.isNotEmpty() && visited++<50_000 && found.size<limit) {
            val (node,depth)=queue.removeLast(); if(depth>50)continue
            when(node) {
                is JSONObject -> node.keys().asSequence().toList().asReversed().forEach { key ->
                    val value=node.opt(key)
                    if(key in names && value!=null && value!==JSONObject.NULL)found.add(value)
                    else if(key !in skip && value is JSONObject || key !in skip && value is JSONArray)queue.add(value to depth+1)
                }
                is JSONArray -> for(i in node.length()-1 downTo 0)node.opt(i)?.let { queue.add(it to depth+1) }
            }
        }
        return found.take(limit)
    }
    private val skip=setOf("menu", "onLongPress", "onLongPressCommand", "onFocusCommand", "adSlotRenderer", "promotedVideoRenderer", "promotedSparklesWebRenderer",
        "adPlacements", "playerAds", "trackingParams", "loggingContext", "accessibility", "frameworkUpdates")
    fun page(j: JSONObject, onlyShorts: Boolean = false, limit:Int=80): YouTubePage {
        val videos=ArrayList<YouTubeVideo>(); val seen=HashSet<String>()
        val maximum=limit.coerceIn(1,120)
        val queue=ArrayDeque<Triple<Any,Int,Boolean>>();queue.add(Triple(j,0,false));var visited=0
        while(queue.isNotEmpty() && visited++<50_000 && videos.size<maximum) {
            val (node,depth,shortsContext)=queue.removeLast();if(depth>50)continue
            when(node) {
                is JSONObject -> {
                    var card:YouTubeVideo?=null
                    for(key in listOf("lockupViewModel", "shortsLockupViewModel", "videoRenderer", "gridVideoRenderer", "compactVideoRenderer", "reelItemRenderer", "tileRenderer")) {
                        node.optJSONObject(key)?.let { card=video(it,key,shortsContext) }
                        if(card!=null)break
                    }
                    if(card!=null) {
                        val v=card!!
                        if(!onlyShorts || v.isShort) {
                            if(seen.add(v.id))videos.add(v)
                            else if(v.isShort){val index=videos.indexOfFirst{it.id==v.id};if(index>=0 && !videos[index].isShort)videos[index]=v}
                        }
                        continue
                    }
                    node.keys().asSequence().toList().asReversed().forEach { key ->
                        if(key !in skip)node.opt(key)?.let { if(it is JSONObject || it is JSONArray)queue.add(Triple(it,depth+1,
                            shortsContext || key=="reelShelfRenderer" || node.optString("tvhtml5ShelfRendererType")=="TVHTML5_SHELF_RENDERER_TYPE_SHORTS")) }
                    }
                }
                is JSONArray -> for(i in node.length()-1 downTo 0)node.opt(i)?.let { queue.add(Triple(it,depth+1,shortsContext)) }
            }
        }
        val token=find(j,setOf("nextContinuationData", "reloadContinuationData", "continuationCommand"),1).firstOrNull() as? JSONObject
        val continuation=(token?.optString("continuation")?.ifBlank { token.optString("token") })?.takeIf { it.isNotBlank() && it.length<20_000 }
        val nudge=find(j,setOf("feedNudgeRenderer", "messageRenderer"),1).firstOrNull() as? JSONObject
        val message=nudge?.let { text(it.opt("subtitle")).ifBlank { text(it.opt("text")) }.ifBlank { text(it.opt("title")) } }.orEmpty()
        return YouTubePage(videos,continuation,message)
    }
    internal fun shelfTitle(node:JSONObject):String {
        val header=node.optJSONObject("headerRenderer")?.optJSONObject("shelfHeaderRenderer")
        val avatar=header?.optJSONObject("avatarLockup")?.optJSONObject("avatarLockupRenderer")
        return text(node.opt("title")).ifBlank{text(header?.opt("title"))}.ifBlank{text(avatar?.opt("title"))}.take(160)
    }
    /** Preserve the TV feed's shelves, without retaining its response tree or inventing categories. */
    fun home(j:JSONObject):YouTubePage {
        val rows=ArrayList<YouTubeShelf>();val occurrences=HashMap<String,Int>();var remaining=120
        for(node in find(j,setOf("shelfRenderer","richShelfRenderer"),10).filterIsInstance<JSONObject>()) {
            if(remaining==0)break
            val items=page(node).videos.take(remaining);if(items.isEmpty())continue
            val name=shelfTitle(node).ifBlank { "Recommended" }
            val key=node.optString("identifier").ifBlank { name }
            val number=occurrences.getOrDefault(key,0);occurrences[key]=number+1
            rows+=YouTubeShelf("$key/$number",name,items,items.all{it.isShort});remaining-=items.size
        }
        if(rows.isEmpty()) {
            val flat=page(j)
            return flat.copy(shelves=if(flat.videos.isEmpty())emptyList() else listOf(YouTubeShelf("recommended/0","Recommended",flat.videos)))
        }
        // A shelf's horizontal continuation is not the next page of the vertical Home feed.
        val container=(find(j,setOf("sectionListRenderer","sectionListContinuation"),1).firstOrNull() as? JSONObject)
        val token=container?.optJSONArray("continuations")?.let {
            find(it,setOf("nextContinuationData","reloadContinuationData"),1).firstOrNull() as? JSONObject
        }?.optString("continuation")?.takeIf { it.isNotBlank() && it.length<20_000 }
        val videos=rows.flatMap{it.videos}.distinctBy{it.id}
        return YouTubePage(videos,token,shelves=rows)
    }
    private fun video(j:JSONObject,kind:String,shortsContext:Boolean=false):YouTubeVideo? {
        val type=j.optString("contentType")
        val media=type.contains("MUSIC") || type.contains("MOVIE")
        if(type.isNotEmpty() && !type.contains("VIDEO") && !type.contains("SHORT") && !media)return null
        val endpoint=(find(j,setOf("watchEndpoint", "reelWatchEndpoint"),1).firstOrNull() as? JSONObject)
        // Music and free movie lockups may be ordinary playable videos. Paid
        // browse destinations are not watch endpoints and must not become Play.
        if(media && endpoint?.optString("videoId")?.let(::validId)!=true)return null
        val id=j.optString("videoId").ifBlank { j.optString("contentId") }.takeIf(::validId)
            ?: endpoint?.optString("videoId")?.takeIf(::validId) ?: return null
        val metadata=j.optJSONObject("metadata")?.optJSONObject("lockupMetadataViewModel")
        val overlay=j.optJSONObject("overlayMetadata")
        val header=j.optJSONObject("header")?.optJSONObject("tileHeaderRenderer")
        val tile=j.optJSONObject("metadata")?.optJSONObject("tileMetadataRenderer")
            ?:(find(header?.opt("thumbnailOverlays"),setOf("tileMetadataRenderer"),1).firstOrNull() as? JSONObject)
        val name=text(metadata?.opt("title")).ifBlank { text(j.opt("title")) }.ifBlank { text(j.opt("headline")) }
            .ifBlank { text(overlay?.opt("primaryText")) }.ifBlank { text(tile?.opt("title")) }
        if(name.isBlank())return null
        val parts=find(metadata?.opt("metadata"),setOf("metadataParts")).filterIsInstance<JSONArray>().map { row ->
            row.objects().map { text(it.opt("text")) }.filter(String::isNotBlank).joinToString(" · ") }
        val tileLines=find(tile?.opt("lines"),setOf("lineItemRenderer")).filterIsInstance<JSONObject>()
            .map{text(it.opt("text"))}.filter{it.isNotBlank() && it!="•"}
        val channel=parts.firstOrNull().orEmpty().ifBlank { text(j.opt("ownerText")) }.ifBlank { text(j.opt("shortBylineText")) }.ifBlank { text(j.opt("longBylineText")) }.ifBlank { tileLines.firstOrNull().orEmpty() }
        val badges=find(j.opt("contentImage"),setOf("thumbnailBadgeViewModel")).filterIsInstance<JSONObject>().map { text(it.opt("text")) }
        val time=(find(header?.opt("thumbnailOverlays"),setOf("thumbnailOverlayTimeStatusRenderer"),1).firstOrNull() as? JSONObject)
        val duration=text(j.opt("lengthText")).ifBlank { badges.firstOrNull { Regex("[0-9]+(?::[0-9]{2}){1,2}").matches(it) }.orEmpty() }.ifBlank { text(time?.opt("text")) }
        val short=shortsContext || kind in setOf("shortsLockupViewModel", "reelItemRenderer") || type.contains("SHORT") ||
            find(j,setOf("reelWatchEndpoint"),1).isNotEmpty()
        // The TV progress overlay is rounded display metadata, not an exact
        // seek position. Keep it separate so a completed tile never starts at EOF.
        val watched=(find(j.opt("thumbnailOverlays")?:header?.opt("thumbnailOverlays")?:j.opt("contentImage"),
            setOf("thumbnailOverlayResumePlaybackRenderer"),1).firstOrNull() as? JSONObject)
            ?.opt("percentDurationWatched")?.toString()?.toIntOrNull()?.takeIf{it in 0..100}
        return YouTubeVideo(id,name.take(500),image(j.opt("contentImage")?:j.opt("thumbnail")?:header?.opt("thumbnail")),channel.take(500),
            parts.drop(1).joinToString(" · ").ifBlank { text(j.opt("viewCountText")) }.ifBlank { tileLines.drop(1).joinToString(" · ") }.take(500),duration,short,
            badges.any { it.equals("LIVE",true) } || time?.optString("style")=="LIVE" || j.optBoolean("isLive"),
            find(j,setOf("browseEndpoint"),20).filterIsInstance<JSONObject>().map{it.optString("browseId")}.firstOrNull(YouTubeSocialDecode::validChannelId).orEmpty(),
            endpoint?.optLong("startTimeSeconds",-1)?.takeIf{it in 0L..604_800L}?.times(1000),watched)
    }
    fun bootstrap(html:String,origin:String):YouTubeBootstrap {
        fun match(pattern:String)=Regex(pattern).find(html)?.groupValues?.get(1).orEmpty()
        val context=Regex("\"INNERTUBE_CONTEXT\"\\s*:\\s*").find(html)?.let { marker->
            runCatching{JSONTokener(html.substring(marker.range.last+1)).nextValue() as? JSONObject}.getOrNull()
        }?.takeIf{it.toString().length<=32_768}
        val client=context?.optJSONObject("client")
        val version=match("\"INNERTUBE_CLIENT_VERSION\"\\s*:\\s*\"([^\"]+)\"").ifBlank{client?.optString("clientVersion").orEmpty()}
        val visitor=match("\"VISITOR_DATA\"\\s*:\\s*\"([^\"]*)\"")
            .ifBlank{client?.optString("visitorData").orEmpty()}.ifBlank{match("\"visitorData\":\"([^\"]+)\"")}
        val path=match("id=\"base-js\" src=\"([^\"]+)\"").ifBlank { match("\\.src = '([^']+)'[; ]") }
        require(version.isNotBlank()) { "YouTube TV configuration changed." }
        val script=URI(origin).resolve(path.replace("&amp;","&")).toString()
        require(path.isNotBlank() && sameOrigin(origin,script)) { "Invalid YouTube configuration address." }
        return YouTubeBootstrap(version,visitor.replace("\\u003d","="),script,context=context)
    }
    fun deviceClient(script:String):Pair<String,String> {
        val found=Regex("clientId:\"([-\\w]+\\.apps\\.googleusercontent\\.com)\",\\n?[$\\w]+:\"(\\w+)\"").find(script)
            ?: throw UserMessage("YouTube sign-in changed. Try updating 777tv.")
        return found.groupValues[1] to found.groupValues[2]
    }
    fun reel(j:JSONObject,endpoint:JSONObject?=null):YouTubeVideo? {
        val e=endpoint?:j.optJSONObject("replacementEndpoint")?.optJSONObject("reelWatchEndpoint")
        val id=e?.optString("videoId")?.takeIf(::validId)?:return null
        val header=find(j.opt("overlay"),setOf("reelPlayerHeaderRenderer"),1).firstOrNull() as? JSONObject ?: return null
        val name=text(header.opt("reelTitleText"));if(name.isBlank())return null
        return YouTubeVideo(id,name.take(500),image(e.opt("thumbnail")).ifBlank { "https://i.ytimg.com/vi/$id/hqdefault.jpg" },
            text(header.opt("channelTitleText")),text(header.opt("timestampText")),isShort=true)
    }
}
