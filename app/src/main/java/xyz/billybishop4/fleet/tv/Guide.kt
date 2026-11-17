package xyz.billybishop4.fleet.tv

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Accept portal timestamps, never infer missing programme times or titles. */
internal fun parseGuide(value: Any, channelId: String, zone: String): List<GuideProgram> {
    fun time(j: JSONObject, vararg keys: String): Long? {
        for (key in keys) {
            val raw = j.optString(key).takeUnless { it.isBlank() || it == "null" } ?: continue
            raw.toLongOrNull()?.let { if (it > 0) return if (it < 100000000000L) it * 1000 else it }
            for (pattern in listOf("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ssXXX")) {
                val format = SimpleDateFormat(pattern, Locale.ROOT).apply { isLenient = false; timeZone = TimeZone.getTimeZone(zone.ifBlank { TimeZone.getDefault().id }) }
                runCatching { format.parse(raw)?.time }.getOrNull()?.let { return it }
            }
        }
        return null
    }
    fun rows(v: Any?): List<JSONObject> = when (v) {
        is JSONArray -> v.objects()
        is JSONObject -> when {
            v.has("data") -> rows(v.opt("data"))
            v.has(channelId) -> rows(v.opt(channelId))
            v.has("start_timestamp") || v.has("start") -> listOf(v)
            else -> v.keys().asSequence().mapNotNull { v.optJSONObject(it)?.takeIf { row -> row.has("start_timestamp") || row.has("start") } }.take(64).toList()
        }
        else -> emptyList()
    }
    return rows(value).take(64).mapNotNull { j ->
        val name = j.optString("name").ifBlank { j.optString("title") }.take(300)
        val start = time(j, "start_timestamp", "start", "time")
        val end = time(j, "stop_timestamp", "end_timestamp", "end", "time_to")
        if (name.isBlank() || start == null || end == null || end <= start) null
        else GuideProgram(name, start, end, j.optString("descr").ifBlank { j.optString("description") }.take(2000))
    }.distinctBy { it.startMs to it.name }.sortedBy { it.startMs }
}
