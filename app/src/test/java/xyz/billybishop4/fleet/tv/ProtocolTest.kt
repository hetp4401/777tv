package xyz.billybishop4.fleet.tv

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProtocolTest {
    @Test fun `series cache freshness accepts zero and expires links before metadata without clock assumptions`() {
        assertFalse(streamLinksFresh(null,0));assertFalse(streamLinksFresh(-1,0));assertFalse(streamLinksFresh(10,9))
        assertTrue(streamLinksFresh(0,0));assertTrue(streamLinksFresh(0,300_000));assertFalse(streamLinksFresh(0,300_001))
        assertTrue(streamLinksFresh(0,900_000,900_000));assertFalse(streamLinksFresh(0,900_001,900_000))
        assertTrue(streamLinksFresh(3_000_000,3_000_001))
    }
    @Test fun `episode descriptions accept overview fallback and ignore JSON null without changing identity`() {
        val title=Decode.title(JSONObject("""{"id":"series","type":"series","videos":[
            {"id":"series:1:1","season":1,"episode":1,"name":"First","description":"Episode description","overview":"Other"},
            {"id":"series:1:2","season":1,"episode":2,"name":"Second","description":null,"overview":"Episode overview"},
            {"id":"series:1:3","season":1,"episode":3,"name":"Third","description":null,"overview":null}]}"""))
        assertEquals(listOf("Episode description","Episode overview",""),title.episodes.map { it.description })
        val before=Progress(title,title.episodes[0],1000,60000,1)
        assertEquals(before.key,before.copy(episode=title.episodes[0].copy(description="Updated metadata")).key)
    }
    @Test fun `series episode identity and specials survive metadata parsing`() {
        val title = Decode.title(JSONObject("""{"id":"tt0903747","name":"A series","type":"series","videos":[
            {"id":"tt0903747:2:3","season":2,"episode":3,"title":"Third"},
            {"season":0,"episode":0,"name":"Special"},{"season":1,"episode":1,"name":"Pilot"},
            {"name":"Not an episode"}]}"""))
        assertEquals(listOf("tt0903747:0:0", "tt0903747:1:1", "tt0903747:2:3"), title.episodes.map { it.id })
        assertEquals("Third", title.episodes.last().name)
    }
    @Test fun `source ordering uses numeric size and puts unknown last`() {
        val streams = Decode.streams(JSONObject("""{"streams":[
            {"name":"unknown","url":"/hls/a/index.m3u8"},
            {"name":"small","title":"file\n900 MB","url":"/file/b/a.mp4"},
            {"name":"large","title":"file\n12.5 GB","url":"/hls/c/orig.m3u8"},
            {"name":"bad","url":"javascript:alert(1)"}]}"""), "https://fleet.example")
        assertEquals(listOf("large", "small", "unknown"), streams.map { it.name })
        assertEquals(12_500_000_000.0, streams[0].sizeBytes!!, 0.1)
    }
    @Test fun `credentials and search text cannot add extra query parameters`() {
        assertEquals("https://provider.example/api?username=a%26admin%3D1&password=a%2Bb%20c", query("https://provider.example/api", mapOf("username" to "a&admin=1", "password" to "a+b c")))
        assertEquals("hello%20%26%20world", enc("hello & world"))
    }
    @Test fun `server address accepts LAN and removes watch route without losing prefix`() {
        assertEquals("http://192.168.1.2:8080", baseUrl("http://192.168.1.2:8080/watch#/movies"))
        assertEquals("https://example.test/fleet", baseUrl("https://example.test/fleet/"))
        assertFalse(isHttp("file:///etc/passwd"))
        assertFalse(isHttp("https://"))
    }
}
