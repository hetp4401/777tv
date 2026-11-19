package xyz.billybishop4.fleet.tv

import java.io.StringReader
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class YouTubeLoungeTest {
    private fun frame(value:String)="${value.length}\n$value"
    @Test fun framedCommandsKeepUnicodeAndDoNotDependOnNetworkLineBoundaries() {
        val payload="[[4,[\"setPlaylist\",{\"videoId\":\"V0000000001\",\"name\":\"Télé 📺\"}]]]"
        val reader=StringReader(frame(payload)+frame("[[5,[\"pause\"]]]")).buffered(3)
        val first=YouTubeLoungeFrames.read(reader)!!.single()
        assertEquals(4L,first.index);assertEquals("Télé 📺",first.data.getString("name"))
        assertEquals("pause",YouTubeLoungeFrames.read(reader)!!.single().type)
        assertNull(YouTubeLoungeFrames.read(reader))
    }
    @Test fun malformedOversizedAndTruncatedFramesFailBeforeUnboundedAllocation() {
        for(value in listOf("${YouTubeLoungeFrames.MAX_FRAME+1}\n","-1\n","999999999999999999999\n","20\n[]")) {
            assertTrue(value,runCatching{YouTubeLoungeFrames.read(value.reader().buffered())}.isFailure)
        }
    }
    @Test fun remoteQueueBoundsIdsPreservesTheRequestedItemAndClampsTimes() {
        val ids=(1..150).map{"V"+it.toString().padStart(10,'0')}
        val queue=YouTubeRemoteQueue()
        assertEquals("S0000000001",queue.replace(JSONObject().put("videoIds",("bad,"+ids.joinToString(","))).put("videoId","S0000000001")))
        assertEquals(120,queue.ids.size);assertNull(queue.step(-1));assertEquals(ids[0],queue.step(1))
        assertEquals(0L,YouTubeRemoteQueue.position(JSONObject().put("currentTime","NaN")))
        assertEquals(0L,YouTubeRemoteQueue.position(JSONObject().put("currentTime","-1")))
        assertEquals(12500L,YouTubeRemoteQueue.position(JSONObject().put("newTime","12.5")))
        assertEquals(604_800_000L,YouTubeRemoteQueue.position(JSONObject().put("currentTime","1e30")))
        queue.clear();assertTrue(queue.ids.isEmpty());assertEquals("",queue.listId)
    }
    private class MemoryStore:YouTubeScreenStore {
        var value:YouTubeScreenIdentity?=null
        override fun read()=value
        override fun write(value:YouTubeScreenIdentity?){this.value=value}
    }
    private class Wire:YouTubeLoungeWire {
        data class Call(val path:String,val query:Map<String,String>,val form:Map<String,String>?)
        val calls=ArrayList<Call>();var screens=0;var tokens=0;var codes=0
        var receive:(suspend(YouTubeLoungeEvent)->Unit)?=null
        val streaming=CompletableDeferred<Unit>()
        override suspend fun request(path:String,query:Map<String,String>,form:Map<String,String>?):String {
            calls+=Call(path,query.toMap(),form?.toMap())
            return when(path) {
                "pairing/generate_screen_id"->"screen-identity-${++screens}"
                "pairing/get_lounge_token_batch"->{tokens++;JSONObject().put("screens",org.json.JSONArray().put(JSONObject()
                    .put("screenId",form!!.getValue("screen_ids")).put("loungeToken","token-secret-$tokens"))).toString()}
                "pairing/get_pairing_code"->if(++codes%2==1)"123456789012" else "{\"code\":\"123456789012\"}"
                "bc/bind"->if(form?.get("count")=="0") {
                    val data="[[0,[\"c\",\"session-id\"]],[1,[\"S\",\"group-session\"]],[2,[\"loungeStatus\",{}]],[3,[\"getNowPlaying\"]],[4,[\"getDiscoveryDeviceId\"]]]"
                    "${data.length}\n$data"
                }else ""
                else->error("Unexpected request")
            }
        }
        override suspend fun events(query:Map<String,String>,receive:suspend(YouTubeLoungeEvent)->Unit) {
            assertEquals("4",query["AID"]);this.receive=receive;streaming.complete(Unit);awaitCancellation()
        }
    }
    @Test fun pairingReusesEncryptedIdentityRefreshesOnlyItsTokenAndRedactsDiagnostics()=runBlocking {
        val store=MemoryStore();val wire=Wire();var now=1000L
        val client=YouTubeLoungeClient(store,wire,now={now})
        assertEquals("123 456 789 012",client.pairingCode());val original=store.value
        client.pairingCode();assertEquals(1,wire.screens);assertEquals(1,wire.tokens)
        now+=1_500_001;client.pairingCode();assertEquals(original,store.value);assertEquals(2,wire.tokens)
        assertFalse(store.value.toString().contains("identity-1"))
        assertTrue(wire.calls.none{it.path.contains("oauth")})
        client.forget();assertNull(store.value);client.pairingCode();assertEquals(2,wire.screens)
    }
    @Test fun duplicateCommandsAreDeliveredOnceAndStatusMessagesShareOneOffsetBatch()=runBlocking {
        val wire=Wire();val client=YouTubeLoungeClient(MemoryStore(),wire);val delivered=ArrayList<String>()
        val task=launch{client.listen({delivered+=it.type})}
        try {
            withTimeout(2000){wire.streaming.await()}
            val receive=wire.receive!!
            receive(YouTubeLoungeEvent(5,"pause",JSONObject()));receive(YouTubeLoungeEvent(5,"pause",JSONObject()))
            receive(YouTubeLoungeEvent(6,"play",JSONObject()))
            assertEquals(listOf("loungeStatus","getNowPlaying","getDiscoveryDeviceId","pause","play"),delivered)
            client.sendAll(listOf("nowPlaying" to mapOf("videoId" to "V0000000001"),"onVolumeChanged" to mapOf("volume" to "35")))
            val post=wire.calls.last();assertEquals("2",post.form!!["count"]);assertEquals("0",post.form["ofs"])
            assertEquals("onVolumeChanged",post.form["req1__sc"]);assertEquals("35",post.form["req1_volume"]);assertEquals("6",post.query["AID"])
            client.send("nowPlaying",mapOf("state" to "2"));assertEquals("2",wire.calls.last().form!!["ofs"])
        }finally{task.cancelAndJoin()}
    }
    @Test fun closingTheListenerCancelsItsNetworkStreamAndAllowsAnewSession()=runBlocking {
        val wire=Wire();val client=YouTubeLoungeClient(MemoryStore(),wire)
        val first=launch{client.listen({})};withTimeout(2000){wire.streaming.await()};first.cancelAndJoin()
        assertTrue(first.isCancelled)
        val second=launch{client.listen({})};yield();second.cancelAndJoin()
        assertEquals(2,wire.calls.count{it.path=="bc/bind" && it.form?.get("count")=="0"})
    }
    @Test fun loungeHttpNeverFollowsARedirectAndSendsNoGoogleBearer()=runBlocking {
        MockWebServer().use { server->
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location","https://example.invalid/private"))
            val wire=YouTubeLoungeHttp(server.url("/").toString())
            val error=runCatching{wire.request("pairing/generate_screen_id")}.exceptionOrNull()
            assertTrue(error is HttpFailure && error.status==302)
            val request=server.takeRequest(2,TimeUnit.SECONDS)!!;assertNull(request.getHeader("Authorization"));assertEquals(1,server.requestCount)
        }
    }
    @Test fun cancellingAStalledHttpPollDoesNotLeaveAnIoReaderAlive()=runBlocking {
        MockWebServer().use { server->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val wire=YouTubeLoungeHttp(server.url("/").toString())
            val task=launch{wire.events(mapOf("SID" to "fixture")){}}
            withContext(Dispatchers.IO){assertNotNull(server.takeRequest(3,TimeUnit.SECONDS))}
            withTimeout(1500){task.cancelAndJoin()};assertTrue(task.isCancelled)
        }
    }
    @Test fun unexpectedOriginsAreRejectedAndProtocolDebugTextOmitsPayloads() {
        for(origin in listOf("http://www.youtube.com","https://youtube.com.evil.test","https://u:p@www.youtube.com","https://www.youtube.com/?key=x"))
            assertTrue(runCatching{YouTubeLoungeHttp(origin)}.isFailure)
        val value=YouTubeLoungeEvent(4,"setPlaylist",JSONObject().put("loungeToken","private"))
        assertEquals("YouTubeLoungeEvent(type=setPlaylist)",value.toString())
    }
}
