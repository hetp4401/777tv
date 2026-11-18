package xyz.billybishop4.fleet.tv

import org.json.JSONObject

data class Preferences(
    val startSection: String = "On-Demand",
    val largePosters: Boolean = false,
    val showRecent: Boolean = true,
    val showContinue: Boolean = true,
    val showMyList: Boolean = true,
    val resume: Boolean = true,
    val largestFirst: Boolean = true,
    val speed: Float = 1f,
    val seekSeconds: Int = 10,
    val audioLanguage: String = "",
    val subtitleLanguage: String = "en",
    val subtitles: Boolean = false,
    val videoFit: String = "Fit",
    val liveBufferSeconds: Int = 3,
    val liveRetries: Int = 2,
    val liveSort: String = "Provider",
    val defaultProfileId: String = "",
) {
    fun json() = JSONObject().apply {
        put("startupScreen", startSection); put("largePosters", largePosters)
        put("showRecent", showRecent); put("showContinue", showContinue); put("showMyList", showMyList)
        put("resume", resume); put("largestFirst", largestFirst)
        put("speed", speed); put("seekSeconds", seekSeconds)
        put("audioLanguage", audioLanguage); put("subtitleLanguage", subtitleLanguage); put("subtitles", subtitles)
        put("videoFit", videoFit); put("liveBufferSeconds", liveBufferSeconds); put("liveRetries", liveRetries); put("liveSort", liveSort)
        put("defaultProfileId", defaultProfileId)
    }
    companion object {
        fun decode(j: JSONObject) = Preferences(
            // The new key separates a deliberate choice from the old saved
            // Live TV default, so upgrades also start on On-Demand once.
            startSection = j.optString("startupScreen").takeIf { it in listOf("On-Demand", "Live TV", "YouTube") } ?: "On-Demand",
            largePosters = j.optBoolean("largePosters"), showRecent = j.optBoolean("showRecent", true),
            showContinue = j.optBoolean("showContinue", true), showMyList = j.optBoolean("showMyList", true),
            resume = j.optBoolean("resume", true),
            largestFirst = j.optBoolean("largestFirst", true),
            speed = j.optDouble("speed", 1.0).toFloat().takeIf { it in listOf(.75f, 1f, 1.25f, 1.5f, 2f) } ?: 1f,
            seekSeconds = j.optInt("seekSeconds", 10).takeIf { it in listOf(5, 10, 15, 30) } ?: 10,
            audioLanguage = language(j.optString("audioLanguage")), subtitleLanguage = language(j.optString("subtitleLanguage", "en")),
            subtitles = j.optBoolean("subtitles"), videoFit = j.optString("videoFit").takeIf { it in listOf("Fit", "Fill", "Zoom") } ?: "Fit",
            liveBufferSeconds = j.optInt("liveBufferSeconds", 3).takeIf { it in listOf(3, 6, 10) } ?: 3,
            liveRetries = j.optInt("liveRetries", 2).coerceIn(0, 3), liveSort = j.optString("liveSort").takeIf { it in listOf("Provider", "A–Z") } ?: "Provider",
            defaultProfileId = j.optString("defaultProfileId").take(256),
        )
        private fun language(value: String) = value.takeIf { it.isEmpty() || Regex("[a-zA-Z]{2,3}(?:-[a-zA-Z]{2,4})?").matches(it) } ?: ""
    }
}

fun visibleChannels(channels: List<Channel>, providerId: String, group: String, query: String,
    onlyFavorites: Boolean, favorites: Set<String>, alphabetical: Boolean, hiddenGroups: Set<String> = emptySet()): List<Channel> =
    ChannelIndex(channels).select(providerId,group,query,onlyFavorites,favorites,alphabetical,hiddenGroups)

/** Prepared off the main thread once per immutable portal list. Filters share channel objects. */
internal class ChannelIndex(source:List<Channel>) {
    private val channels:List<Channel>
    val categories:List<String>
    private val sorted by lazy { channels.sortedBy { it.searchName } }
    init {
        val identities=HashSet<String>();val groups=LinkedHashSet<String>();val unique=ArrayList<Channel>(source.size)
        for(c in source)if(identities.add(c.identity)) { unique.add(c);groups.add(c.group) }
        channels=if(unique.size==source.size)source else unique
        categories=groups.toList()
    }
    fun select(providerId:String,group:String,query:String,onlyFavorites:Boolean,favorites:Set<String>,alphabetical:Boolean,
        hiddenGroups:Set<String>):List<Channel> {
        val needle=query.trim().lowercase(java.util.Locale.ROOT)
        val ordered=if(alphabetical)sorted else channels
        if(group=="All channels" && needle.isEmpty() && !onlyFavorites && hiddenGroups.isEmpty())return ordered
        val prefix="$providerId/"
        val favoriteIds=if(onlyFavorites)favorites.asSequence().filter{it.startsWith(prefix)}.map{it.removePrefix(prefix)}.toHashSet() else emptySet()
        return ordered.filter { it.group !in hiddenGroups && (group=="All channels" || it.group==group) &&
            (needle.isEmpty() || it.searchName.contains(needle)) && (!onlyFavorites || it.identity in favoriteIds) }
    }
}

open class UserMessage(message: String) : java.io.IOException(message)
fun favoriteKey(providerId: String, channel: Channel): String = "$providerId/${channel.identity}"
internal fun channelIdentity(channelId: String, name: String, address: String, group: String): String {
    val url = if (address.isEmpty()) null else runCatching { java.net.URI(address) }.getOrNull()
    val id = channelId.takeUnless { isHttp(it) }.orEmpty()
    val identity = listOf(id, name, group, url?.host.orEmpty(), url?.path.orEmpty()).joinToString("\n")
    val hex = "0123456789abcdef"
    val digest = channelHasher.get()!!.digest(identity.toByteArray(Charsets.UTF_8))
    val chars = CharArray(digest.size * 2)
    digest.forEachIndexed { i, b -> val v = b.toInt() and 255; chars[i * 2] = hex[v ushr 4]; chars[i * 2 + 1] = hex[v and 15] }
    return String(chars)
}
private val channelHasher = object : ThreadLocal<java.security.MessageDigest>() {
    override fun initialValue() = java.security.MessageDigest.getInstance("SHA-256")
}
