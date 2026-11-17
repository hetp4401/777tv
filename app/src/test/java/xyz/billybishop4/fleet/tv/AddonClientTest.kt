package xyz.billybishop4.fleet.tv

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AddonClientTest {
    private fun manifest(id: String = "test.addon", resources: JSONArray = JSONArray().put("catalog").put("meta").put("stream"),
        catalogs: JSONArray = JSONArray().put(JSONObject().put("type", "movie").put("id", "top").put("extra", JSONArray().put(JSONObject().put("name", "search"))))) =
        JSONObject().put("id", id).put("name", id).put("version", "1.0.0").put("types", JSONArray().put("movie").put("series"))
            .put("resources", resources).put("catalogs", catalogs).put("idPrefixes", JSONArray().put("tt"))
    private fun addon(server: MockWebServer, path: String = "/manifest.json", document: JSONObject = manifest(), enabled: Boolean = true) =
        Addon(server.url(path).toString(), document.toString(), enabled)
    private fun reply(value: Any) = MockResponse().setHeader("Content-Type", "application/json").setBody(value.toString())
    private fun meta(id: String = "tt1", name: String = "Fixture movie") = JSONObject().put("id", id).put("name", name).put("type", "movie")
    private fun metas(vararg values: JSONObject) = JSONObject().put("metas", JSONArray(values.toList()))
    private fun streams(vararg values: JSONObject) = JSONObject().put("streams", JSONArray(values.toList()))
    private fun request(server: MockWebServer) = server.takeRequest(3, TimeUnit.SECONDS) ?: error("Expected addon request")
    private fun extra(name: String, options: List<String> = emptyList(), required: Boolean = false) =
        JSONObject().put("name", name).put("options", JSONArray(options)).put("isRequired", required)
    private fun catalog(type: String, id: String, name: String, vararg extras: JSONObject) =
        JSONObject().put("type", type).put("id", id).put("name", name).put("extra", JSONArray(extras.toList()))

    @Test fun `configured manifest URLs and addon JSON preserve opaque paths and queries`() {
        val url = addonManifestUrl(" stremio://ADDONS.example/User%2FKey/manifest.json?token=A%2Bb%3D&x=1 ")
        assertEquals("https://addons.example/User%2FKey/manifest.json?token=A%2Bb%3D&x=1", url)
        val value = Addon(url, manifest().toString(), false)
        assertEquals(value, Addon.fromJson(value.json()))
        assertEquals("test.addon", value.id); assertEquals("1.0.0", value.version)
        assertEquals("https://addons.example/manifest.json", addonManifestUrl("https://addons.example"))
        for (invalid in listOf("file:///manifest.json", "ftp://addons.example/manifest.json", "https://user:pass@addons.example/manifest.json",
            "https://addons.example/configure", "https://addons.example/manifest.json#fragment", "https://addons.example:0/manifest.json", "not a URL")) {
            try { addonManifestUrl(invalid); fail("Accepted invalid manifest URL") } catch (_: UserMessage) {}
        }
        val defaults = defaultAddons()
        assertEquals(listOf("com.linvo.cinemeta", "billybishop.gdrive"), defaults.map { it.id })
        assertEquals("https://gdrive.billybishop4-workers.xyz/gdrive/manifest.json", defaults.last().manifestUrl)
        assertTrue(defaults.all { it.enabled && it.manifest.isNotBlank() && Addon.fromJson(it.json()) == it })
    }

    @Test fun `installation fetches the manifest and rejects HTML or missing resource declarations`() = runBlocking {
        val server = MockWebServer(); server.start()
        server.enqueue(reply(manifest()))
        server.enqueue(MockResponse().setBody("<html>Configure this addon</html>"))
        server.enqueue(reply(JSONObject().put("id", "invalid").put("name", "Invalid")))
        try {
            val installed = fetchAddon(server.url("/configured/manifest.json?key=abc").toString())
            assertEquals("test.addon", installed.name)
            assertEquals("/configured/manifest.json?key=abc", request(server).path)
            repeat(2) {
                try { fetchAddon(server.url("/manifest.json").toString()); fail("Accepted an invalid manifest") }
                catch (_: UserMessage) {}
            }
        } finally { server.shutdown() }
    }

    @Test fun `catalog and metadata use direct configured paths and direct artwork`() = runBlocking {
        val server = MockWebServer(); server.start()
        val movie = meta().put("poster", "/poster.jpg").put("background", "https://images.example/background.jpg")
        server.enqueue(reply(metas(movie))); server.enqueue(reply(JSONObject().put("meta", movie))); server.enqueue(reply(metas(movie)))
        try {
            val repo = Repository(listOf(addon(server, "/Config%2FKey/manifest.json?token=A%2Bb")))
            val list = repo.catalog("movie")
            assertEquals("/Config%2FKey/catalog/movie/top.json?token=A%2Bb", request(server).path)
            assertEquals(server.url("/poster.jpg").toString(), list.single().poster)
            assertEquals("https://images.example/background.jpg", repo.artwork(list.single().background))
            assertSame(list, repo.catalog("movie"))
            assertEquals(1, server.requestCount)
            assertEquals("tt1", repo.detail(list.single()).id)
            assertEquals("/Config%2FKey/meta/movie/tt1.json?token=A%2Bb", request(server).path)
            repo.refresh(); repo.catalog("movie")
            assertEquals("/Config%2FKey/catalog/movie/top.json?token=A%2Bb", request(server).path)
        } finally { server.shutdown() }
    }

    @Test fun `search follows catalog extras skips unmet required fields and deduplicates titles`() = runBlocking {
        val server = MockWebServer(); server.start()
        val definitions = JSONArray()
            .put(JSONObject().put("type", "movie").put("id", "top").put("extra", JSONArray().put(JSONObject().put("name", "search"))))
            .put(JSONObject().put("type", "movie").put("id", "search-only").put("extra", JSONArray().put(JSONObject().put("name", "search").put("isRequired", true))))
            .put(JSONObject().put("type", "movie").put("id", "by-genre").put("extraSupported", JSONArray().put("search").put("genre")).put("extraRequired", JSONArray().put("genre")))
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = reply(metas(meta()))
        }
        try {
            val repo = Repository(listOf(addon(server, document = manifest(catalogs = definitions))))
            assertEquals(1, repo.catalog("movie").size)
            assertEquals("/catalog/movie/top.json", request(server).path)
            assertEquals(listOf("tt1"), repo.search("  planet & stars  ").map { it.id })
            val paths = setOf(request(server).path, request(server).path)
            assertEquals(setOf("/catalog/movie/top/search=planet%20%26%20stars.json", "/catalog/movie/search-only/search=planet%20%26%20stars.json"), paths)
            assertEquals(3, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun `typed resources override global ID prefixes and episode colons remain literal`() = runBlocking {
        val server = MockWebServer(); server.start()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = reply(streams(JSONObject().put("url", "https://media.example${request.requestUrl!!.encodedPath}.mp4")))
        }
        val anyMovie = JSONArray().put(JSONObject().put("name", "stream").put("types", JSONArray().put("movie")))
        val limitedSeries = JSONArray().put(JSONObject().put("name", "stream").put("types", JSONArray().put("series")).put("idPrefixes", JSONArray().put("series:")))
        try {
            val repo = Repository(listOf(
                addon(server, "/global/manifest.json", manifest(resources = JSONArray().put("stream"))),
                addon(server, "/typed/manifest.json", manifest(resources = anyMovie)),
                addon(server, "/series/manifest.json", manifest(resources = limitedSeries)),
                addon(server, "/disabled/manifest.json", manifest(), false),
            ))
            assertEquals(1, repo.streams(Title("custom:42", "Custom"), null).size)
            assertEquals("/typed/stream/movie/custom:42.json", request(server).path)
            val episode = Episode("series:7:1:2", 1, 2, "Episode")
            assertEquals(1, repo.streams(Title("series:7", "Series", "series"), episode).size)
            assertEquals("/series/stream/series/series:7:1:2.json", request(server).path)
            assertEquals(2, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun `direct streams preserve declared request headers and deduplicate only equivalent playback`() = runBlocking {
        val server = MockWebServer(); server.start()
        val header = JSONObject().put("user-agent", "AddonPlayer").put("Referer", "https://referrer.example/").put("Authorization", "Bearer fixture")
        val valid = JSONObject().put("name", "1080p").put("url", "https://media.example/movie.mp4").put("title", "Fixture\n1.5 GB")
            .put("behaviorHints", JSONObject().put("proxyHeaders", JSONObject().put("request", header).put("response", JSONObject().put("X-Ignore", "response-only"))))
        val otherAuth = JSONObject(valid.toString()).apply { getJSONObject("behaviorHints").getJSONObject("proxyHeaders").put("request", JSONObject(header.toString()).put("Authorization", "Bearer other")) }
        val unsafe = JSONObject().put("url", "https://media.example/unsafe.mp4").put("behaviorHints", JSONObject().put("proxyHeaders", JSONObject().put("request", JSONObject().put("X-Bad", "value\r\ninjected: yes"))))
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = if (request.path!!.startsWith("/failed/")) MockResponse().setResponseCode(503) else
                reply(streams(valid, JSONObject(valid.toString()), otherAuth, JSONObject().put("infoHash", "torrent"), JSONObject().put("externalUrl", "https://external.example"), JSONObject().put("url", "magnet:?xt=urn:btih:test"), unsafe))
        }
        try {
            val repo = Repository(listOf(addon(server, "/working/manifest.json", manifest("working")), addon(server, "/failed/manifest.json", manifest("failed"))))
            val result = repo.streams(Title("tt1", "Movie"), null)
            assertEquals(2, result.size)
            val stream = result.first()
            assertEquals("working", stream.addonName); assertEquals(1_500_000_000.0, stream.sizeBytes!!, .1)
            assertEquals("AddonPlayer", stream.headers["User-Agent"]); assertEquals("Bearer fixture", stream.headers["Authorization"])
            assertEquals("https://referrer.example/", stream.headers["Referer"]); assertFalse(stream.headers.containsKey("X-Ignore"))
            assertTrue(result.all { it.url == "https://media.example/movie.mp4" })
        } finally { server.shutdown() }
    }

    @Test fun `unsupported sources are distinct from empty responses and never hide working direct links`() = runBlocking {
        val server = MockWebServer(); server.start()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = when {
                request.path!!.startsWith("/unsupported/") -> reply(streams(
                    JSONObject().put("infoHash", "fixture-torrent"),
                    JSONObject().put("url", "magnet:?xt=urn:btih:fixture"),
                    JSONObject().put("externalUrl", "https://external.example/watch"),
                    JSONObject().put("ytId", "fixture-video"),
                    JSONObject().put("url", "file:///unsupported.mp4"),
                ))
                request.path!!.startsWith("/working/") -> reply(streams(JSONObject().put("url", "https://media.example/movie.mp4")))
                else -> reply(streams())
            }
        }
        try {
            val unsupported = addon(server, "/unsupported/manifest.json", manifest("unsupported"))
            val empty = addon(server, "/empty/manifest.json", manifest("empty"))
            val working = addon(server, "/working/manifest.json", manifest("working"))
            val movie = Title("tt1", "Movie")
            assertTrue(Repository(listOf(empty)).streams(movie, null).isEmpty())
            try {
                Repository(listOf(unsupported, empty)).streams(movie, null)
                fail("Unsupported entries appeared as a genuinely empty response")
            } catch (e: UserMessage) { assertTrue(e.message!!.contains("direct video links")) }
            assertEquals(listOf("https://media.example/movie.mp4"), Repository(listOf(unsupported, working)).streams(movie, null).map { it.url })
        } finally { server.shutdown() }
    }

    @Test fun `partial addon failures preserve good catalogs and all failures remain actionable`() = runBlocking {
        val server = MockWebServer(); server.start()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = if (request.path!!.startsWith("/failed/")) MockResponse().setResponseCode(503) else reply(metas(meta()))
        }
        try {
            val bad = addon(server, "/failed/manifest.json", manifest("failed"))
            val good = addon(server, "/working/manifest.json", manifest("working"))
            assertEquals(listOf("tt1"), Repository(listOf(bad, good)).catalog("movie").map { it.id })
            assertEquals(listOf("tt1"), Repository(listOf(bad, good)).search("planet").map { it.id })
            try { Repository(listOf(bad)).catalog("movie"); fail("All failed addons appeared as a valid empty catalog") }
            catch (e: UserMessage) { assertTrue(e.message!!.contains("addons")) }
        } finally { server.shutdown() }
    }

    @Test fun `an empty successful addon cannot conceal failures without any working data`() = runBlocking {
        val server = MockWebServer(); server.start()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = when {
                request.path!!.startsWith("/failed/") -> MockResponse().setResponseCode(503)
                request.path!!.startsWith("/working/") -> reply(metas(meta()).put("streams", JSONArray().put(JSONObject().put("url", "https://media.example/movie.mp4"))))
                else -> reply(metas().put("streams", JSONArray()))
            }
        }
        try {
            val empty = addon(server, "/empty/manifest.json", manifest("empty"))
            val failed = addon(server, "/failed/manifest.json", manifest("failed"))
            val working = addon(server, "/working/manifest.json", manifest("working"))
            val incomplete = Repository(listOf(empty, failed)); val movie = Title("tt1", "Movie")
            suspend fun recoveryRequired(request: suspend () -> Any) {
                try { request(); fail("An empty provider concealed a failed relevant provider") }
                catch (e: UserMessage) { assertTrue(e.message!!.contains("addons")) }
            }
            recoveryRequired { incomplete.catalog("movie") }
            recoveryRequired { incomplete.search("planet") }
            recoveryRequired { incomplete.streams(movie, null) }
            val partial = Repository(listOf(empty, failed, working))
            assertEquals(listOf("tt1"), partial.catalog("movie").map { it.id })
            assertEquals(listOf("tt1"), partial.search("planet").map { it.id })
            assertEquals(listOf("https://media.example/movie.mp4"), partial.streams(movie, null).map { it.url })
            assertTrue(Repository(listOf(empty)).search("planet").isEmpty())
            assertTrue(Repository(listOf(empty)).streams(movie, null).isEmpty())
        } finally { server.shutdown() }
    }

    @Test fun `cancellation propagates and does not poison the next request`() = runBlocking {
        val server = MockWebServer(); server.start()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)); server.enqueue(reply(metas(meta())))
        try {
            val repo = Repository(listOf(addon(server)))
            val pending = async { repo.catalog("movie") }
            assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(3, TimeUnit.SECONDS) })
            withTimeout(1500) { pending.cancelAndJoin() }
            assertTrue(pending.isCancelled)
            assertEquals(listOf("tt1"), withTimeout(5000) { repo.catalog("movie") }.map { it.id })
        } finally { server.shutdown() }
    }

    @Test fun `addon requests run concurrently with a shared upper bound`() = runBlocking {
        val server = MockWebServer(); server.start()
        val active = AtomicInteger(); val peak = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val running = active.incrementAndGet(); peak.updateAndGet { maxOf(it, running) }
                return try {
                    Thread.sleep(100)
                    reply(streams(JSONObject().put("url", "https://media.example${request.requestUrl!!.encodedPath}.mp4")))
                } finally { active.decrementAndGet() }
            }
        }
        try {
            val addons = (1..12).map { addon(server, "/$it/manifest.json", manifest("addon.$it", JSONArray().put("stream"))) }
            assertEquals(12, Repository(addons).streams(Title("tt1", "Movie"), null).size)
            assertTrue("Requests serialized unexpectedly", peak.get() >= 2)
            assertTrue("Addon request budget exceeded", peak.get() <= 4)
        } finally { server.shutdown() }
    }

    @Test fun `minimal official Cinemeta cache upgrades without changing installed identities or configuration`() {
        val minimal = manifest("com.linvo.cinemeta").toString()
        val official = Addon("https://v3-cinemeta.strem.io/manifest.json", minimal, false)
        val upgraded = Addon.fromJson(official.json())
        assertEquals(official.manifestUrl, upgraded.manifestUrl); assertFalse(upgraded.enabled)
        assertEquals(defaultAddons().first().document!!.getJSONArray("catalogs").toString(), upgraded.document!!.getJSONArray("catalogs").toString())
        assertTrue(upgraded.document!!.getJSONArray("catalogs").objects().any { it.text("id") == "year" })
        for (url in listOf("https://v3-cinemeta.strem.io/manifest.json?profile=private", "https://v3-cinemeta.strem.io/configured/manifest.json")) {
            val configured = Addon(url, minimal, false)
            assertEquals(configured, Addon.fromJson(configured.json()))
        }
        assertEquals(defaultAddons().last(), Addon.fromJson(defaultAddons().last().json()))
    }

    @Test fun `home rows and genre year requests follow advertised catalogs in manifest order`() = runBlocking {
        val server = MockWebServer(); server.start()
        val definitions = JSONArray()
            .put(catalog("movie", "top", "Popular", extra("genre", listOf("Action", "Comedy")), extra("search"), extra("skip")))
            .put(catalog("series", "top", "Popular", extra("genre", listOf("Drama")), extra("search")))
            .put(catalog("movie", "year", "New", extra("genre", listOf("2026", "2025"), true), extra("skip")))
            .put(catalog("movie", "imdbRating", "Featured", extra("genre", listOf("Action", "Comedy"))))
            .put(catalog("series", "calendar-videos", "Calendar videos", extra("calendarVideosIds", required = true)))
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val type = if (request.path!!.contains("/series/")) "series" else "movie"
                val title = JSONObject().put("id", "tt1").put("name", "Fixture").put("type", type)
                return reply(metas(title, title))
            }
        }
        try {
            val repo = Repository(listOf(addon(server, "/User%2FKey/manifest.json?token=A%2Bb", manifest(catalogs = definitions))))
            val home = repo.homeCatalogs()
            assertEquals(listOf("Popular · Movies", "Popular · Series", "Featured · Movies"), home.shelves.map { it.name })
            assertTrue(home.shelves.all { it.titles.size == 1 && it.id.matches(Regex("[0-9a-f]{64}")) })
            assertEquals(setOf("/User%2FKey/catalog/movie/top.json?token=A%2Bb", "/User%2FKey/catalog/series/top.json?token=A%2Bb", "/User%2FKey/catalog/movie/imdbRating.json?token=A%2Bb"), (1..3).map { request(server).path }.toSet())
            val year = home.catalogs.first { it.name == "New" }
            assertTrue(year.canBrowse); assertFalse(year.canSearch)
            assertEquals(listOf("2026", "2025"), year.extras.first { it.name == "genre" }.options)
            assertFalse(home.catalogs.first { it.name == "Calendar videos" }.canBrowse)
            assertEquals(1, repo.homeCatalogs(CatalogFilter(catalogKey = year.key, extras = mapOf("genre" to "2025"))).shelves.size)
            assertEquals("/User%2FKey/catalog/movie/year/genre=2025.json?token=A%2Bb", request(server).path)
            val popular = home.catalogs.first { it.name == "Popular" && it.type == "movie" }
            repo.homeCatalogs(CatalogFilter(catalogKey = popular.key, extras = mapOf("genre" to "Action")))
            assertEquals("/User%2FKey/catalog/movie/top/genre=Action.json?token=A%2Bb", request(server).path)
            assertTrue(repo.homeCatalogs(CatalogFilter(catalogKey = popular.key, extras = mapOf("genre" to "Unadvertised"))).shelves.isEmpty())
            assertEquals(5, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun `grouped search preserves catalog and configured addon identities while legacy results deduplicate`() = runBlocking {
        val server = MockWebServer(); server.start()
        val definitions = JSONArray().put(catalog("movie", "top", "Popular", extra("search")))
            .put(catalog("movie", "find", "Find", extra("search", required = true)))
            .put(catalog("movie", "year", "New", extra("genre", listOf("2026"), true)))
        server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = reply(metas(meta(), meta())) }
        try {
            val document = manifest(catalogs = definitions)
            val repo = Repository(listOf(addon(server, "/one/manifest.json?key=A", document), addon(server, "/two/manifest.json?key=B", document)))
            val page = repo.searchCatalogs(" planet & stars ")
            assertEquals(4, page.shelves.size); assertEquals(4, page.shelves.map { it.id }.distinct().size)
            assertTrue(page.shelves.all { it.titles.map { title -> title.id } == listOf("tt1") })
            val paths = (1..4).map { request(server).path }.toSet()
            assertEquals(setOf("/one/catalog/movie/top/search=planet%20%26%20stars.json?key=A", "/one/catalog/movie/find/search=planet%20%26%20stars.json?key=A",
                "/two/catalog/movie/top/search=planet%20%26%20stars.json?key=B", "/two/catalog/movie/find/search=planet%20%26%20stars.json?key=B"), paths)
            assertTrue(page.shelves.map { it.name }.any { it.contains("Configuration 2") })
            assertEquals(listOf("tt1"), repo.search("planet & stars").map { it.id })
            assertSame(page, repo.searchCatalogs("planet & stars"))
        } finally { server.shutdown() }
    }

    @Test fun `catalog poster summaries stay bounded and page refresh invalidates cached payloads`() = runBlocking {
        val server = MockWebServer(); server.start()
        val definitions = JSONArray().put(catalog("series", "top", "Popular", extra("search")))
        val data = JSONObject().put("metas", JSONArray((1..150).map { index ->
            JSONObject().put("id", "tt$index").put("name", "Show $index").put("type", "series")
                .put("videos", JSONArray().put(JSONObject().put("id", "tt$index:1:1").put("season", 1).put("episode", 1).put("name", "Episode")))
        }))
        server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = reply(data) }
        try {
            val repo = Repository(listOf(addon(server, document = manifest(catalogs = definitions))))
            val first = repo.homeCatalogs()
            assertEquals(100, first.shelves.single().titles.size)
            assertTrue(first.shelves.single().titles.all { it.episodes.isEmpty() })
            assertSame(first, repo.homeCatalogs()); assertEquals(1, server.requestCount)
            repo.refresh(); assertNotSame(first, repo.homeCatalogs()); assertEquals(2, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun `grouped catalog partial failures cannot masquerade as a valid empty page`() = runBlocking {
        val server = MockWebServer(); server.start()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = when {
                request.path!!.startsWith("/failed/") -> MockResponse().setResponseCode(503)
                request.path!!.startsWith("/working/") -> reply(metas(meta()))
                else -> reply(metas())
            }
        }
        try {
            val failed = addon(server, "/failed/manifest.json")
            val empty = addon(server, "/empty/manifest.json")
            val working = addon(server, "/working/manifest.json")
            try { Repository(listOf(empty, failed)).homeCatalogs(); fail("Empty catalog concealed failed provider") } catch (_: UserMessage) {}
            val partial = Repository(listOf(working, failed)).homeCatalogs()
            assertTrue(partial.partial); assertEquals(listOf("tt1"), partial.shelves.flatMap { it.titles }.map { it.id })
            assertFalse(Repository(listOf(empty)).homeCatalogs().partial)
        } finally { server.shutdown() }
    }

    @Test fun `cancelled filtered catalog does not contaminate a newer filter page`() = runBlocking {
        val server = MockWebServer(); server.start()
        server.enqueue(reply(metas(meta())))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.enqueue(reply(metas(meta("tt2", "Comedy result"))))
        try {
            val document = manifest(catalogs = JSONArray().put(catalog("movie", "top", "Popular", extra("genre", listOf("Action", "Comedy")))))
            val repo = Repository(listOf(addon(server, document = document)))
            val key = repo.homeCatalogs().catalogs.single().key; request(server)
            val pending = async(Dispatchers.Default) { repo.homeCatalogs(CatalogFilter(catalogKey = key, extras = mapOf("genre" to "Action"))) }
            assertEquals("/catalog/movie/top/genre=Action.json", request(server).path)
            withTimeout(1500) { pending.cancelAndJoin() }
            val latest = withTimeout(5000) { repo.homeCatalogs(CatalogFilter(catalogKey = key, extras = mapOf("genre" to "Comedy"))) }
            assertEquals("Comedy result", latest.shelves.single().titles.single().name)
            assertEquals("/catalog/movie/top/genre=Comedy.json", request(server).path)
        } finally { server.shutdown() }
    }

    @Test fun `forced metadata refresh bypasses only that title cache and preserves catalog pages`() = runBlocking {
        val server = MockWebServer(); server.start()
        server.enqueue(reply(metas(meta())))
        server.enqueue(reply(JSONObject().put("meta", meta(name = "Old metadata"))))
        server.enqueue(reply(JSONObject().put("meta", meta(name = "New metadata"))))
        try {
            val repo = Repository(listOf(addon(server)))
            val home = repo.homeCatalogs()
            val old = repo.detail(Title("tt1", "Fixture"))
            assertSame(old, repo.detail(Title("tt1", "Fixture")))
            val fresh = repo.detail(Title("tt1", "Fixture"), refresh = true)
            assertEquals("New metadata", fresh.name); assertSame(fresh, repo.detail(fresh))
            assertSame(home, repo.homeCatalogs()); assertEquals(3, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun `older metadata completion cannot replace a forced fresh result`() = runBlocking {
        val server = MockWebServer(); server.start()
        val responses = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = if (responses.incrementAndGet() == 1)
                reply(JSONObject().put("meta", meta(name = "Old metadata"))).setBodyDelay(500, TimeUnit.MILLISECONDS)
            else reply(JSONObject().put("meta", meta(name = "Fresh metadata")))
        }
        try {
            val repo = Repository(listOf(addon(server)))
            val old = async(Dispatchers.Default) { repo.detail(Title("tt1", "Fixture")) }
            request(server)
            val fresh = repo.detail(Title("tt1", "Fixture"), refresh = true)
            assertEquals("Fresh metadata", fresh.name); assertEquals("Old metadata", old.await().name)
            assertSame(fresh, repo.detail(Title("tt1", "Fixture")))
        } finally { server.shutdown() }
    }
}
