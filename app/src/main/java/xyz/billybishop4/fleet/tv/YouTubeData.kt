package xyz.billybishop4.fleet.tv

import java.net.URI
import org.json.JSONArray
import org.json.JSONObject

internal enum class YouTubeFeed { HOME, SHORTS, SEARCH }
internal data class YouTubeVideo(
    val id: String, val name: String, val thumbnail: String = "", val channel: String = "",
    val metadata: String = "", val duration: String = "", val isShort: Boolean = false, val live: Boolean = false,
) {
    fun title() = Title(id, name, "youtube", poster = thumbnail, description = listOf(channel, metadata).filter(String::isNotBlank).joinToString(" · "))
}
internal data class YouTubePage(val videos: List<YouTubeVideo>, val continuation: String? = null, val message: String = "")
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
internal data class YouTubeBootstrap(val version: String, val visitor: String, val scriptUrl: String, val clientId: String = "", val clientSecret: String = "") {
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
    private fun image(value: Any?): String {
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
    private val skip=setOf("menu", "onLongPress", "adSlotRenderer", "promotedVideoRenderer", "promotedSparklesWebRenderer",
        "adPlacements", "playerAds", "trackingParams", "loggingContext", "accessibility", "frameworkUpdates")
    fun page(j: JSONObject, onlyShorts: Boolean = false): YouTubePage {
        val videos=ArrayList<YouTubeVideo>(); val seen=HashSet<String>()
        val queue=ArrayDeque<Pair<Any,Int>>();queue.add(j to 0);var visited=0
        while(queue.isNotEmpty() && visited++<50_000 && videos.size<80) {
            val (node,depth)=queue.removeLast();if(depth>50)continue
            when(node) {
                is JSONObject -> {
                    var card:YouTubeVideo?=null
                    for(key in listOf("lockupViewModel", "shortsLockupViewModel", "videoRenderer", "gridVideoRenderer", "compactVideoRenderer", "reelItemRenderer", "tileRenderer")) {
                        node.optJSONObject(key)?.let { card=video(it,key) }
                        if(card!=null)break
                    }
                    if(card!=null) { val v=card!!; if((!onlyShorts || v.isShort) && seen.add(v.id))videos.add(v);continue }
                    node.keys().asSequence().toList().asReversed().forEach { key ->
                        if(key !in skip)node.opt(key)?.let { if(it is JSONObject || it is JSONArray)queue.add(it to depth+1) }
                    }
                }
                is JSONArray -> for(i in node.length()-1 downTo 0)node.opt(i)?.let { queue.add(it to depth+1) }
            }
        }
        val token=find(j,setOf("nextContinuationData", "reloadContinuationData", "continuationCommand"),1).firstOrNull() as? JSONObject
        val continuation=(token?.optString("continuation")?.ifBlank { token.optString("token") })?.takeIf { it.isNotBlank() && it.length<20_000 }
        val nudge=find(j,setOf("feedNudgeRenderer", "messageRenderer"),1).firstOrNull() as? JSONObject
        val message=nudge?.let { text(it.opt("subtitle")).ifBlank { text(it.opt("text")) }.ifBlank { text(it.opt("title")) } }.orEmpty()
        return YouTubePage(videos,continuation,message)
    }
    private fun video(j:JSONObject,kind:String):YouTubeVideo? {
        val type=j.optString("contentType")
        if(type.isNotEmpty() && !type.contains("VIDEO") && !type.contains("SHORT"))return null
        val endpoint=(find(j,setOf("watchEndpoint", "reelWatchEndpoint"),1).firstOrNull() as? JSONObject)
        val id=j.optString("videoId").ifBlank { j.optString("contentId") }.takeIf(::validId)
            ?: endpoint?.optString("videoId")?.takeIf(::validId) ?: return null
        val metadata=j.optJSONObject("metadata")?.optJSONObject("lockupMetadataViewModel")
        val overlay=j.optJSONObject("overlayMetadata")
        val name=text(metadata?.opt("title")).ifBlank { text(j.opt("title")) }.ifBlank { text(j.opt("headline")) }
            .ifBlank { text(overlay?.opt("primaryText")) }.ifBlank { text(j.optJSONObject("metadata")?.optJSONObject("tileMetadataRenderer")?.opt("title")) }
        if(name.isBlank())return null
        val parts=find(metadata?.opt("metadata"),setOf("metadataParts")).filterIsInstance<JSONArray>().map { row ->
            row.objects().map { text(it.opt("text")) }.filter(String::isNotBlank).joinToString(" · ") }
        val channel=parts.firstOrNull().orEmpty().ifBlank { text(j.opt("ownerText")) }.ifBlank { text(j.opt("shortBylineText")) }.ifBlank { text(j.opt("longBylineText")) }
        val badges=find(j.opt("contentImage"),setOf("thumbnailBadgeViewModel")).filterIsInstance<JSONObject>().map { text(it.opt("text")) }
        val duration=text(j.opt("lengthText")).ifBlank { badges.firstOrNull { Regex("[0-9]+(?::[0-9]{2}){1,2}").matches(it) }.orEmpty() }
        val short=kind in setOf("shortsLockupViewModel", "reelItemRenderer") || type.contains("SHORT") ||
            find(j,setOf("reelWatchEndpoint"),1).isNotEmpty() || j.optJSONObject("styling")?.optString("contentImageAspectRatio").orEmpty().contains("9_BY_16")
        return YouTubeVideo(id,name.take(500),image(j.opt("contentImage")?:j.opt("thumbnail")),channel.take(500),
            parts.drop(1).joinToString(" · ").ifBlank { text(j.opt("viewCountText")) }.take(500),duration,short,
            badges.any { it.equals("LIVE",true) } || j.optBoolean("isLive"))
    }
    fun bootstrap(html:String,origin:String):YouTubeBootstrap {
        fun match(pattern:String)=Regex(pattern).find(html)?.groupValues?.get(1).orEmpty()
        val version=match("\"INNERTUBE_CLIENT_VERSION\":\"([^\"]+)\"")
        val visitor=match("\"visitorData\":\"([^\"]+)\"")
        val path=match("id=\"base-js\" src=\"([^\"]+)\"").ifBlank { match("\\.src = '([^']+)'[; ]") }
        require(version.isNotBlank()) { "YouTube TV configuration changed." }
        val script=URI(origin).resolve(path.replace("&amp;","&")).toString()
        require(path.isNotBlank() && sameOrigin(origin,script)) { "Invalid YouTube configuration address." }
        return YouTubeBootstrap(version,visitor.replace("\\u003d","="),script)
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
