package xyz.billybishop4.fleet.tv

import java.net.URI
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

data class Title(
    val id: String, val name: String, val type: String = "movie", val year: String = "",
    val poster: String = "", val background: String = "", val description: String = "",
    val rating: String = "", val genres: List<String> = emptyList(),
    val episodes: List<Episode> = emptyList(),
) {
    val key get() = "$type/$id"
    fun json() = JSONObject().put("id", id).put("name", name).put("type", type)
        .put("year", year).put("poster", poster).put("background", background)
        .put("description", description).put("imdbRating", rating).put("genres", JSONArray(genres))
}
data class Episode(val id: String, val season: Int, val number: Int, val name: String, val description:String="") {
    val label get() = "S$season · E$number  $name"
}
data class Stream(val name: String, val url: String, val description: String = "", val sizeBytes: Double? = null,
    val addonName:String="",val headers:Map<String,String> = emptyMap()) {
    val identity:String=java.util.UUID.nameUUIDFromBytes((url+"\n"+headers.toSortedMap().entries.joinToString("\n"){it.key+":"+it.value}).toByteArray(Charsets.UTF_8)).toString()
    val label get() = name.lineSequence().firstOrNull()?.ifBlank { addonName.ifBlank { "Stream" } } ?: "Stream"
}
data class Shelf(val name: String, val titles: List<Title>,val id:String=name)
data class Progress(val title: Title, val episode: Episode?, val positionMs: Long, val durationMs: Long, val updated: Long) {
    val key get() = title.key + (episode?.id?.let { "/$it" } ?: "")
    val finished get() = durationMs > 0 && positionMs.toDouble() / durationMs >= .95
}
data class Provider(val id: String, val name: String, val url: String, val kind: String = "stalker", val username: String = "", val password: String = "", val userAgent: String = "",
    val mac: String = "", val serialNumber: String = "", val deviceId: String = "", val deviceId2: String = "", val signature: String = "",
    val stbType: String = "MAG250", val timezone: String = "", val hwVersion: String = "",
    val hwVersion2: String = "", val pin: String = "", val version: String = "", val imageVersion: String = "")
data class Channel(val id: String, val name: String, val url: String, val group: String = "Channels", val logo: String = "", val headers: Map<String, String> = emptyMap(), val command: String = "", val number: String = "") {
    val identity = channelIdentity(id, name, url, group)
    val searchName = name.lowercase(java.util.Locale.ROOT)
}
data class GuideProgram(val name: String, val startMs: Long, val endMs: Long, val description: String = "")
data class Watch(val title: Title, val stream: Stream, val episode: Episode? = null, val headers: Map<String, String> = emptyMap(), val live: Boolean = false,
    val provider: Provider? = null, val channel: Channel? = null,
    val audioUrl:String="", val videoMimeType:String="", val audioMimeType:String="", val resolvedAtMs:Long=0L,
    val short:Boolean=false, val captions:List<VideoCaption> = emptyList(), val playRequestedAtMs:Long=0L)
internal fun playbackNowMs()=System.nanoTime()/1_000_000L
data class VideoCaption(val url:String,val language:String,val mimeType:String)
internal enum class PlaybackMode { SHORTS, YOUTUBE, ON_DEMAND, LIVE }
internal val Watch.playbackMode get()=when { live->PlaybackMode.LIVE;short->PlaybackMode.SHORTS;title.type=="youtube"->PlaybackMode.YOUTUBE;else->PlaybackMode.ON_DEMAND }

object Decode {
    fun title(j: JSONObject, fallbackType: String = "movie"): Title {
        val id = j.optString("id").ifBlank { j.optString("imdb_id") }
        val episodes = j.optJSONArray("videos")?.objects()?.mapNotNull { v ->
            val s = v.optInt("season", -1); val e = v.optInt("episode", v.optInt("number", -1))
            val description=(v.opt("description") as? String).orEmpty().ifBlank { (v.opt("overview") as? String).orEmpty() }
            if (s < 0 || e < 0) null else Episode(v.optString("id").ifBlank { "$id:$s:$e" }, s, e, v.optString("name").ifBlank { v.optString("title") },description)
        } ?: emptyList()
        return Title(id, j.optString("name").ifBlank { id }, j.optString("type").ifBlank { fallbackType },
            j.optString("releaseInfo").ifBlank { j.optString("year") }, j.optString("poster"), j.optString("background"),
            j.optString("description").ifBlank { j.optString("overview") }, j.optString("imdbRating"),
            (j.optJSONArray("genres") ?: j.optJSONArray("genre"))?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(),
            episodes.distinctBy { it.id }.sortedWith(compareBy({ it.season }, { it.number })))
    }
    fun titles(a: JSONArray, type: String = "movie") = a.objects().map { title(it, type) }.filter { it.id.isNotBlank() }.distinctBy { it.key }
    fun streams(j: JSONObject, base: String): List<Stream> = j.optJSONArray("streams")?.objects()?.mapNotNull { s ->
        val url = runCatching { URI(base + "/").resolve(s.optString("url")).toString() }.getOrNull()
        if (s.optString("url").isBlank() || !isHttp(url.orEmpty())) null else {
            val desc = s.optString("title").ifBlank { s.optString("description") }
            Stream(s.optString("name", "Stream"), url!!, desc, size(desc))
        }
    }?.sortedWith(compareBy<Stream> { it.sizeBytes == null }.thenByDescending { it.sizeBytes }) ?: emptyList()
    fun size(s: String): Double? {
        val match = Regex("([0-9]+(?:\\.[0-9]+)?)\\s*(TB|GB|MB|KB|B)\\b", RegexOption.IGNORE_CASE).find(s) ?: return null
        val power = listOf("B", "KB", "MB", "GB", "TB").indexOf(match.groupValues[2].uppercase())
        return match.groupValues[1].toDoubleOrNull()?.times(Math.pow(1000.0, power.toDouble()))?.takeIf { it > 0 && it.isFinite() }
    }
}
fun JSONArray.objects() = (0 until length()).mapNotNull { optJSONObject(it) }
fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
fun isHttp(s: String) = runCatching { val u = URI(s); u.scheme?.lowercase(java.util.Locale.ROOT) in listOf("https", "http") && !u.host.isNullOrBlank() && u.userInfo == null }.getOrDefault(false)
fun baseUrl(s: String): String {
    val u = URI(s.trim())
    require(isHttp(s.trim()) && u.userInfo == null) { "Enter an HTTP or HTTPS server address." }
    val path = u.path.trimEnd('/').removeSuffix("/watch")
    return URI(u.scheme, u.rawAuthority, path, null, null).toString().trimEnd('/')
}
fun query(url: String, args: Map<String, String>): String {
    val u = URI(url)
    val existing = u.rawQuery.orEmpty().split('&').filter { it.isNotBlank() && it.substringBefore('=') !in args }
    val raw = (existing + args.map { "${enc(it.key)}=${enc(it.value)}" }).joinToString("&")
    return url.substringBefore('#').substringBefore('?') + if (raw.isEmpty()) "" else "?$raw"
}
