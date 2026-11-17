package xyz.billybishop4.fleet.tv

import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** The configured URL can contain private configuration. Persist it only in encrypted storage. */
data class Addon(val manifestUrl: String, val manifest: String = "", val enabled: Boolean = true) {
    internal val document: JSONObject? = runCatching { JSONObject(manifest.removePrefix("\uFEFF")) }.getOrNull()
    val id: String = document?.text("id").orEmpty()
    val name: String = document?.text("name").orEmpty().ifBlank {
        runCatching { URI(manifestUrl).host }.getOrNull().orEmpty().ifBlank { "Addon" }
    }
    val description: String = document?.text("description").orEmpty()
    val version: String = document?.text("version").orEmpty()
    fun json() = JSONObject().put("manifestUrl", manifestUrl).put("manifest", manifest).put("enabled", enabled)
    companion object {
        fun fromJson(value: JSONObject): Addon {
            val url = addonManifestUrl(value.text("manifestUrl"))
            val manifest = value.text("manifest")
            if (manifest.isNotEmpty()) checkedManifest(manifest)
            return currentOfficialManifest(Addon(url, manifest, value.optBoolean("enabled", true)))
        }
    }
}

data class CatalogExtra(val name: String, val options: List<String> = emptyList(), val required: Boolean = false)
data class CatalogDefinition(val key: String, val name: String, val type: String, val addonName: String, val extras: List<CatalogExtra>) {
    val canBrowse get() = extras.filter { it.required }.all { it.name !in setOf("search", "skip") && it.options.isNotEmpty() }
    val canSearch get() = extras.any { it.name == "search" } && extras.filter { it.required }.all { it.name == "search" }
}
data class CatalogFilter(val type: String = "", val catalogKey: String = "", val extras: Map<String, String> = emptyMap())
data class CatalogPage(val catalogs: List<CatalogDefinition>, val shelves: List<Shelf>, val partial: Boolean = false)

internal fun catalogLabel(value: CatalogDefinition, all: List<CatalogDefinition>): String {
    val peers = all.filter { it.name == value.name && it.type == value.type }
    val type = if (value.type == "movie") "Movies" else "Series"
    val source = if (peers.size > 1) " · ${value.addonName}" else ""
    val configurations = peers.filter { it.addonName == value.addonName }
    val ordinal = if (configurations.size > 1) " · Configuration ${configurations.indexOfFirst { it.key == value.key } + 1}" else ""
    return "${value.name} · $type$source$ordinal"
}

/** Upgrade only the public, unconfigured official endpoint; configured/disabled specs retain their identity. */
private fun currentOfficialManifest(addon: Addon): Addon {
    if (addon.manifestUrl != "https://v3-cinemeta.strem.io/manifest.json") return addon
    val current = addon.document?.optJSONArray("catalogs")?.objects().orEmpty()
    val official = JSONObject(CINEMETA_MANIFEST).getJSONArray("catalogs").objects()
    val outdated = official.any { expected ->
        val found = current.firstOrNull { it.text("id") == expected.text("id") && it.text("type") == expected.text("type") }
        val expectedOptions = expected.optJSONArray("extra")?.objects()?.firstOrNull { it.text("name") == "genre" }?.optJSONArray("options")
        val actualOptions = found?.optJSONArray("extra")?.objects()?.firstOrNull { it.text("name") == "genre" }?.optJSONArray("options")
        found == null || expectedOptions != null && (actualOptions == null || actualOptions.length() == 0 ||
            expected.text("id") == "year" && (actualOptions.optString(0).toIntOrNull() ?: 0) < (expectedOptions.optString(0).toIntOrNull() ?: 0))
    }
    return if (outdated) addon.copy(manifest = CINEMETA_MANIFEST) else addon
}

/** Preserve opaque configuration segments and query bytes; stremio links use HTTPS. */
fun addonManifestUrl(input: String): String {
    val value = input.trim().let { if (it.startsWith("stremio://", true)) "https://" + it.substringAfter("://") else it }
    val uri = runCatching { URI(value) }.getOrNull()
    if (uri == null || uri.scheme?.lowercase(Locale.ROOT) !in listOf("http", "https") || uri.host.isNullOrBlank() ||
        uri.userInfo != null || uri.rawFragment != null || uri.port !in -1..65535 || uri.port == 0)
        throw UserMessage("Enter an HTTP, HTTPS or stremio addon manifest URL.")
    val path = uri.rawPath.orEmpty().trimEnd('/').ifEmpty { "/manifest.json" }
    if (!path.endsWith("/manifest.json")) throw UserMessage("Use the addon's manifest.json URL, including its configuration path.")
    return "${uri.scheme.lowercase(Locale.ROOT)}://${uri.rawAuthority.lowercase(Locale.ROOT)}$path" + (uri.rawQuery?.let { "?$it" } ?: "")
}

suspend fun fetchAddon(input: String, http: Http = Http()): Addon = withContext(Dispatchers.Default) {
    val url = addonManifestUrl(input)
    val raw = http.text(url, timeout = 15000)
    val manifest = checkedManifest(raw)
    Addon(url, manifest.toString())
}

internal fun JSONObject.text(key: String): String = opt(key) as? String ?: ""
private val HEADER_NAME = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
private fun JSONArray.strings(): List<String> = (0 until length()).mapNotNull { opt(it) as? String }
private fun checkedManifest(raw: String): JSONObject {
    val error = "That address did not return a valid Stremio addon manifest."
    if (raw.length > 1024 * 1024) throw UserMessage("The addon manifest is too large.")
    val json = runCatching { JSONObject(raw.removePrefix("\uFEFF")) }.getOrElse { throw UserMessage(error) }
    val resources = json.optJSONArray("resources") ?: throw UserMessage(error)
    if (json.text("id").isBlank() || json.text("name").isBlank() || json.optJSONArray("types")?.strings().isNullOrEmpty() || resources.length() == 0)
        throw UserMessage(error)
    for (i in 0 until resources.length()) {
        when (val resource = resources.opt(i)) {
            is String -> if (resource.isBlank()) throw UserMessage(error)
            is JSONObject -> if (resource.text("name").isBlank()) throw UserMessage(error)
            else -> throw UserMessage(error)
        }
    }
    return json
}

/** Direct Stremio HTTP resources. All addon operations share a four-request budget. */
internal class AddonClient(addons: List<Addon>, private val http: Http) {
    private val installed = addons.filter { it.enabled }.distinctBy { it.manifestUrl }
    private val requests = Semaphore(4)
    private val manifests = mutableMapOf<String, Addon>()
    private data class Providers(val addons: List<Addon>, val failed: Boolean)
    private data class Catalog(val addon: Addon, val type: String, val id: String, val definition: JSONObject)
    private data class StreamReply(val sources: List<Stream>, val hadEntries: Boolean)

    fun refresh() { synchronized(manifests) { manifests.clear() } }
    private suspend fun <T, R> outcomes(values: List<T>, operation: suspend (T) -> R): List<Result<R>> = supervisorScope {
        values.map { value -> async {
            requests.withPermit {
                try { Result.success(operation(value)) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { Result.failure(e) }
            }
        } }.awaitAll()
    }
    private suspend fun providers(): Providers {
        val resolved = outcomes(installed) { config ->
            val url = addonManifestUrl(config.manifestUrl)
            if (config.manifest.isNotEmpty()) { checkedManifest(config.manifest); currentOfficialManifest(if (config.manifestUrl == url) config else config.copy(manifestUrl = url)) }
            else synchronized(manifests) { manifests[url] } ?: fetchAddon(url, http).also { synchronized(manifests) { manifests[url] = it } }
        }
        return Providers(resolved.mapNotNull { it.getOrNull() }.distinctBy { it.manifestUrl }, resolved.any { it.isFailure })
    }
    private fun supports(addon: Addon, resource: String, type: String, id: String): Boolean {
        val manifest = addon.document ?: return false
        val resources = manifest.optJSONArray("resources") ?: return false
        for (i in 0 until resources.length()) {
            val entry = resources.opt(i)
            val full = entry as? JSONObject
            val name = if (entry is String) entry else full?.text("name")
            if (name != resource) continue
            val types = full?.optJSONArray("types") ?: manifest.optJSONArray("types")
            if (type !in types?.strings().orEmpty()) continue
            // Full resource notation does not inherit manifest-global ID prefixes.
            // Catalog IDs are independent of content ID prefixes in either notation.
            val prefixes = if (full != null) full.optJSONArray("idPrefixes") else manifest.optJSONArray("idPrefixes")
            if (resource == "catalog" || prefixes == null || prefixes.strings().any { id.startsWith(it) }) return true
        }
        return false
    }
    private fun catalogs(addon: Addon): List<Catalog> = addon.document?.optJSONArray("catalogs")?.objects().orEmpty().mapNotNull {
        val type = it.text("type"); val id = it.text("id")
        if (type !in listOf("movie", "series") || id.isBlank() || !supports(addon, "catalog", type, id)) null
        else Catalog(addon, type, id, it)
    }
    private fun extras(catalog: Catalog): Set<String> = catalog.definition.optJSONArray("extraSupported")?.strings().orEmpty().toSet() +
        catalog.definition.optJSONArray("extra")?.objects().orEmpty().map { it.text("name") }
    private fun describe(catalog: Catalog): CatalogDefinition {
        val declared = catalog.definition.optJSONArray("extra")?.objects().orEmpty()
        val required = catalog.definition.optJSONArray("extraRequired")?.strings().orEmpty().toSet() + declared.filter { it.optBoolean("isRequired") }.map { it.text("name") }
        val names = (declared.map { it.text("name") } + extras(catalog) + required).filter { it.isNotBlank() }.distinct()
        val extra = names.map { name ->
            val options = declared.firstOrNull { it.text("name") == name }?.optJSONArray("options")?.strings()
                ?: if (name == "genre") catalog.definition.optJSONArray("genres")?.strings().orEmpty() else emptyList()
            CatalogExtra(name, options.filter { it.isNotBlank() }.distinct(), name in required)
        }
        val key = MessageDigest.getInstance("SHA-256").digest("${catalog.addon.manifestUrl}\u0000${catalog.type}\u0000${catalog.id}".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        fun safe(value: String, fallback: String) = value.trim().replace(Regex("\\s+"), " ")
            .takeUnless { it.contains("://") || Regex("[?&][^\\s=]+=").containsMatchIn(it) }?.take(120)?.ifBlank { fallback } ?: fallback
        return CatalogDefinition(key, safe(catalog.definition.text("name"), "Catalog"), catalog.type, safe(catalog.addon.name, "Add-on"), extra)
    }
    private fun accepts(catalog: Catalog, supplied: Map<String, String>): Boolean {
        val required = catalog.definition.optJSONArray("extraRequired")?.strings().orEmpty().toSet() +
            catalog.definition.optJSONArray("extra")?.objects().orEmpty().filter { it.optBoolean("isRequired") }.map { it.text("name") }
        return required.all { !supplied[it].isNullOrBlank() } && supplied.keys.all { it in extras(catalog) } &&
            describe(catalog).extras.all { field -> supplied[field.name]?.let { field.options.isEmpty() || it in field.options } ?: true }
    }
    private fun url(addon: Addon, resource: String, type: String, id: String, extra: Map<String, String> = emptyMap()): String {
        val manifest = URI(addonManifestUrl(addon.manifestUrl))
        val base = "${manifest.scheme}://${manifest.rawAuthority}${manifest.rawPath.removeSuffix("/manifest.json")}"
        val segment = enc(id).replace("%3A", ":")
        val suffix = if (extra.isEmpty()) "" else "/" + extra.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
        return "$base/$resource/${enc(type)}/$segment$suffix.json" + (manifest.rawQuery?.let { "?$it" } ?: "")
    }
    private suspend fun response(addon: Addon, resource: String, type: String, id: String, extra: Map<String, String> = emptyMap()): JSONObject {
        val json = JSONObject(http.text(url(addon, resource, type, id, extra), timeout = if (resource == "stream") 45000 else 15000).removePrefix("\uFEFF"))
        if (json.has("error") && !json.isNull("error")) throw UserMessage("The addon could not load this content.")
        return json
    }
    private fun <T> merge(results: List<Result<List<T>>>, failedManifest: Boolean, message: String): List<T> {
        val data = results.mapNotNull { it.getOrNull() }.flatten()
        if (data.isEmpty() && (failedManifest || results.any { it.isFailure })) throw UserMessage(message)
        return data
    }
    private fun artwork(value: String, addon: Addon): String {
        if (value.isBlank()) return ""
        val absolute = runCatching { URI(addon.manifestUrl).resolve(value).toString() }.getOrDefault("")
        return absolute.takeIf(::isHttp).orEmpty()
    }
    private fun title(value: JSONObject, type: String, addon: Addon): Title? {
        val decoded = Decode.title(value, type)
        if (decoded.id.isBlank() || decoded.type != type) return null
        return decoded.copy(poster = artwork(value.text("poster"), addon), background = artwork(value.text("background"), addon))
    }
    private suspend fun feed(catalog: Catalog, extra: Map<String, String>): List<Title> {
        val data = response(catalog.addon, "catalog", catalog.type, catalog.id, extra).getJSONArray("metas")
        return (0 until data.length()).asSequence().mapNotNull { data.optJSONObject(it)?.let { value -> title(value, catalog.type, catalog.addon) } }
            .distinctBy { it.key }.take(100).map { if (it.episodes.isEmpty()) it else it.copy(episodes = emptyList()) }.toList()
    }
    private fun catalogPage(descriptions: List<CatalogDefinition>, results: List<Result<Shelf>>, failedManifest: Boolean, message: String): CatalogPage {
        val loaded = results.mapNotNull { it.getOrNull() }
        val failed = failedManifest || results.any { it.isFailure }
        if (loaded.none { it.titles.isNotEmpty() } && failed) throw UserMessage(message)
        return CatalogPage(descriptions, loaded, failed)
    }
    suspend fun homeCatalogs(filter: CatalogFilter): CatalogPage = withContext(Dispatchers.Default) {
        val providers = providers()
        val available = providers.addons.flatMap(::catalogs)
        val descriptions = available.map(::describe)
        val choices = available.filterIndexed { index, catalog ->
            (filter.type.isEmpty() || catalog.type == filter.type) && (filter.catalogKey.isEmpty() || descriptions[index].key == filter.catalogKey) && accepts(catalog, filter.extras)
        }
        val results = outcomes(choices) { catalog ->
            val definition = describe(catalog)
            Shelf(catalogLabel(definition, descriptions), feed(catalog, filter.extras), definition.key)
        }
        catalogPage(descriptions, results, providers.failed, "Couldn't load catalogs. Check your addons in Settings.")
    }
    suspend fun searchCatalogs(query: String): CatalogPage = withContext(Dispatchers.Default) {
        val text = query.trim()
        if (text.isEmpty()) return@withContext CatalogPage(emptyList(), emptyList())
        val providers = providers(); val available = providers.addons.flatMap(::catalogs); val descriptions = available.map(::describe)
        val extra = mapOf("search" to text)
        val choices = available.filter { "search" in extras(it) && accepts(it, extra) }
        val results = outcomes(choices) { catalog ->
            val definition = describe(catalog)
            Shelf(catalogLabel(definition, descriptions), feed(catalog, extra), definition.key)
        }
        catalogPage(descriptions, results, providers.failed, "Search couldn't load. Check your addons in Settings.")
    }
    suspend fun catalog(type: String, id: String, genre: String?): List<Title> = withContext(Dispatchers.Default) {
        val providers = providers()
        val supplied = genre?.let { mapOf("genre" to it) }.orEmpty()
        val choices = providers.addons.mapNotNull { addon ->
            val candidates = catalogs(addon).filter { it.type == type && accepts(it, supplied) }
            candidates.firstOrNull { it.id == id } ?: candidates.firstOrNull().takeIf { id == "top" }
        }
        val results = outcomes(choices) { catalog ->
            val data = response(catalog.addon, "catalog", type, catalog.id, supplied).getJSONArray("metas")
            data.objects().mapNotNull { title(it, type, catalog.addon) }
        }
        merge(results, providers.failed, "Couldn't load catalogs. Check your addons in Settings.").distinctBy { it.key }
    }
    suspend fun search(query: String): List<Title> = withContext(Dispatchers.Default) {
        val search = query.trim()
        if (search.isEmpty()) return@withContext emptyList()
        val providers = providers(); val supplied = mapOf("search" to search)
        val choices = providers.addons.flatMap(::catalogs).filter { "search" in extras(it) && accepts(it, supplied) }
        val results = outcomes(choices) { catalog ->
            response(catalog.addon, "catalog", catalog.type, catalog.id, supplied).getJSONArray("metas").objects().mapNotNull { title(it, catalog.type, catalog.addon) }
        }
        merge(results, providers.failed, "Search couldn't load. Check your addons in Settings.").distinctBy { it.key }
    }
    suspend fun detail(value: Title): Title = withContext(Dispatchers.Default) {
        val providers = providers(); val choices = providers.addons.filter { supports(it, "meta", value.type, value.id) }
        val results = outcomes(choices) { addon ->
            val meta = response(addon, "meta", value.type, value.id).optJSONObject("meta")
            val decoded = meta?.let { title(it, value.type, addon) }
            if (decoded == null || decoded.id != value.id) emptyList() else listOf(decoded)
        }
        merge(results, providers.failed, "Details couldn't load. Check your addons in Settings.").firstOrNull() ?: value
    }
    suspend fun streams(value: Title, episode: Episode?): List<Stream> = withContext(Dispatchers.Default) {
        val providers = providers(); val id = episode?.id ?: value.id
        val choices = providers.addons.filter { supports(it, "stream", value.type, id) }
        val results = outcomes(choices) { addon ->
            val entries = response(addon, "stream", value.type, id).getJSONArray("streams")
            StreamReply(entries.objects().mapNotNull { stream(it, addon) }, entries.length() > 0)
        }
        val sources = merge(results.map { result -> result.map { it.sources } }, providers.failed, "Streams couldn't load. Check your addons in Settings.")
        if (sources.isEmpty() && results.any { it.getOrNull()?.hadEntries == true })
            throw UserMessage("These sources need a player this app doesn't support. Install an add-on with direct video links.")
        sources.distinctBy { it.url to it.headers }
    }
    private fun stream(json: JSONObject, addon: Addon): Stream? {
        val address = json.text("url").trim()
        if (!isHttp(address)) return null // Magnet/infoHash/externalUrl/YouTube entries need other engines.
        val hints = json.optJSONObject("behaviorHints")
        val rawHeaders = hints?.optJSONObject("proxyHeaders")?.optJSONObject("request")
        val headers = linkedMapOf<String, String>()
        if (rawHeaders != null) {
            if (rawHeaders.length() > 64) return null
            for (name in rawHeaders.keys()) {
                val value = rawHeaders.opt(name) as? String ?: return null
                if (!HEADER_NAME.matches(name) || value.any { it == '\r' || it == '\n' || it == '\u0000' }) return null
                val canonical = when (name.lowercase(Locale.ROOT)) {
                    "user-agent" -> "User-Agent"; "referer" -> "Referer"; "origin" -> "Origin"
                    "cookie" -> "Cookie"; "authorization" -> "Authorization"; else -> name.lowercase(Locale.ROOT)
                }
                headers[canonical] = value
            }
        }
        val description = json.text("title").ifBlank { json.text("description") }
        val bytes = hints?.optDouble("videoSize")?.takeIf { it > 0 && it.isFinite() } ?: Decode.size(description)
        return Stream(json.text("name").ifBlank { addon.name }, address, description, bytes, addonName = addon.name, headers = headers)
    }
}

/** Verified manifest snapshots allow an offline launch without changing installed specs. */
fun defaultAddons(): List<Addon> = listOf(
    Addon("https://v3-cinemeta.strem.io/manifest.json", CINEMETA_MANIFEST),
    Addon("https://gdrive.billybishop4-workers.xyz/gdrive/manifest.json", GDRIVE_MANIFEST),
)

private const val CINEMETA_MANIFEST = """{"id":"com.linvo.cinemeta","version":"3.0.14","description":"The official addon for movie and series catalogs","name":"Cinemeta","resources":["catalog","meta","addon_catalog"],"types":["movie","series"],"idPrefixes":["tt"],"addonCatalogs":[{"type":"all","id":"official","name":"Official"},{"type":"movie","id":"official","name":"Official"},{"type":"series","id":"official","name":"Official"},{"type":"channel","id":"official","name":"Official"},{"type":"all","id":"community","name":"Community"},{"type":"movie","id":"community","name":"Community"},{"type":"series","id":"community","name":"Community"},{"type":"channel","id":"community","name":"Community"},{"type":"tv","id":"community","name":"Community"},{"type":"Podcasts","id":"community","name":"Community"},{"type":"other","id":"community","name":"Community"}],"catalogs":[{"type":"movie","id":"top","genres":["Action","Adventure","Animation","Biography","Comedy","Crime","Documentary","Drama","Family","Fantasy","History","Horror","Mystery","Romance","Sci-Fi","Sport","Thriller","War","Western"],"extra":[{"name":"genre","options":["Action","Adventure","Animation","Biography","Comedy","Crime","Documentary","Drama","Family","Fantasy","History","Horror","Mystery","Romance","Sci-Fi","Sport","Thriller","War","Western"]},{"name":"search"},{"name":"skip"}],"extraSupported":["search","genre","skip"],"name":"Popular"},{"type":"series","id":"top","genres":["Action","Adventure","Animation","Biography","Comedy","Crime","Documentary","Drama","Family","Fantasy","History","Horror","Mystery","Romance","Sci-Fi","Sport","Thriller","War","Western","Reality-TV","Talk-Show","Game-Show"],"extra":[{"name":"genre","options":["Action","Adventure","Animation","Biography","Comedy","Crime","Documentary","Drama","Family","Fantasy","History","Horror","Mystery","Romance","Sci-Fi","Sport","Thriller","War","Western","Reality-TV","Talk-Show","Game-Show"]},{"name":"search"},{"name":"skip"}],"extraSupported":["search","genre","skip"],"name":"Popular"},{"type":"movie","id":"year","genres":["2026","2025","2024","2023","2022","2021","2020","2019","2018","2017","2016","2015","2014","2013","2012","2011","2010","2009","2008","2007","2006","2005","2004","2003","2002","2001","2000","1999","1998","1997","1996","1995","1994","1993","1992","1991","1990","1989","1988","1987","1986","1985","1984","1983","1982","1981","1980","1979","1978","1977","1976","1975","1974","1973","1972","1971","1970","1969","1968","1967","1966","1965","1964","1963","1962","1961","1960","1959","1958","1957","1956","1955","1954","1953","1952","1951","1950","1949","1948","1947","1946","1945","1944","1943","1942","1941","1940","1939","1938","1937","1936","1935","1934","1933","1932","1931","1930","1929","1928","1927","1926","1925","1924","1923","1922","1921","1920"],"extra":[{"name":"genre","options":["2026","2025","2024","2023","2022","2021","2020","2019","2018","2017","2016","2015","2014","2013","2012","2011","2010","2009","2008","2007","2006","2005","2004","2003","2002","2001","2000","1999","1998","1997","1996","1995","1994","1993","1992","1991","1990","1989","1988","1987","1986","1985","1984","1983","1982","1981","1980","1979","1978","1977","1976","1975","1974","1973","1972","1971","1970","1969","1968","1967","1966","1965","1964","1963","1962","1961","1960","1959","1958","1957","1956","1955","1954","1953","1952","1951","1950","1949","1948","1947","1946","1945","1944","1943","1942","1941","1940","1939","1938","1937","1936","1935","1934","1933","1932","1931","1930","1929","1928","1927","1926","1925","1924","1923","1922","1921","1920"],"isRequired":true},{"name":"skip"}],"extraSupported":["genre","skip"],"extraRequired":["genre"],"name":"New"},{"type":"series","id":"year","genres":["2026","2025","2024","2023","2022","2021","2020","2019","2018","2017","2016","2015","2014","2013","2012","2011","2010","2009","2008","2007","2006","2005","2004","2003","2002","2001","2000","1999","1998","1997","1996","1995","1994","1993","1992","1991","1990","1989","1988","1987","1986","1985","1984","1983","1982","1981","1980","1979","1978","1977","1976","1975","1974","1973","1972","1971","1970","1969","1968","1967","1966","1965","1964","1963","1962","1961","1960"],"extra":[{"name":"genre","options":["2026","2025","2024","2023","2022","2021","2020","2019","2018","2017","2016","2015","2014","2013","2012","2011","2010","2009","2008","2007","2006","2005","2004","2003","2002","2001","2000","1999","1998","1997","1996","1995","1994","1993","1992","1991","1990","1989","1988","1987","1986","1985","1984","1983","1982","1981","1980","1979","1978","1977","1976","1975","1974","1973","1972","1971","1970","1969","1968","1967","1966","1965","1964","1963","1962","1961","1960"],"isRequired":true},{"name":"skip"}],"extraSupported":["genre","skip"],"extraRequired":["genre"],"name":"New"},{"type":"movie","id":"imdbRating","genres":["Action","Adventure","Animation","Biography","Comedy","Crime","Documentary","Drama","Family","Fantasy","History","Horror","Mystery","Romance","Sci-Fi","Sport","Thriller","War","Western"],"extra":[{"name":"genre","options":["Action","Adventure","Animation","Biography","Comedy","Crime","Documentary","Drama","Family","Fantasy","History","Horror","Mystery","Romance","Sci-Fi","Sport","Thriller","War","Western"]},{"name":"skip"}],"extraSupported":["genre","skip"],"name":"Featured"},{"type":"series","id":"imdbRating","genres":["Action","Adventure","Animation","Biography","Comedy","Crime","Documentary","Drama","Family","Fantasy","History","Horror","Mystery","Romance","Sci-Fi","Sport","Thriller","War","Western","Reality-TV","Talk-Show","Game-Show"],"extra":[{"name":"genre","options":["Action","Adventure","Animation","Biography","Comedy","Crime","Documentary","Drama","Family","Fantasy","History","Horror","Mystery","Romance","Sci-Fi","Sport","Thriller","War","Western","Reality-TV","Talk-Show","Game-Show"]},{"name":"skip"}],"extraSupported":["genre","skip"],"name":"Featured"},{"type":"series","id":"last-videos","extra":[{"name":"lastVideosIds","isRequired":true,"optionsLimit":100}],"extraSupported":["lastVideosIds"],"extraRequired":["lastVideosIds"],"name":"Last videos"},{"type":"series","id":"calendar-videos","extra":[{"name":"calendarVideosIds","isRequired":true,"optionsLimit":100}],"extraSupported":["calendarVideosIds"],"extraRequired":["calendarVideosIds"],"name":"Calendar videos"}],"behaviorHints":{"newEpisodeNotifications":true}}"""
private const val GDRIVE_MANIFEST = """{"behaviorHints":{},"catalogs":[],"description":"Aggregate of Torrentio + StremThru, resolved via TorBox","id":"billybishop.gdrive","idPrefixes":["tt"],"name":"GDrive","resources":["stream"],"types":["movie","series"],"version":"1.0.0"}"""
