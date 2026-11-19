package xyz.billybishop4.fleet.tv

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test

class YouTubeTransportTest {
    @Test fun cancellingARequestDisconnectsAStalledSocket()=runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));server.start()
            val pending=async(start=CoroutineStart.UNDISPATCHED){YouTubeHttp().request(server.url("/slow").toString())}
            assertNotNull(server.takeRequest(3,TimeUnit.SECONDS))
            withTimeout(2000){pending.cancelAndJoin()}
            assertTrue(pending.isCancelled)
        }
    }
    @Test fun oversizedBodiesAreRejectedBeforeTheyCanFillMemory()=runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("x".repeat(8*1024*1024+1)));server.start()
            try{YouTubeHttp().request(server.url("/large").toString());fail("Expected bounded response")}
            catch(e:IOException){assertEquals("YouTube response is too large",e.message)}
        }
    }
    @Test fun crossOriginRedirectsDoNotForwardAccountAuthorization()=runBlocking {
        MockWebServer().use { source ->MockWebServer().use { target ->
            target.start();source.enqueue(MockResponse().setResponseCode(302).addHeader("Location",target.url("/end")))
            target.enqueue(MockResponse().setBody("ok"));source.start()
            val response=YouTubeHttp().request(source.url("/start").toString(),headers=mapOf("Authorization" to "Bearer fixture-private-token"))
            assertEquals("ok",response.text)
            assertEquals("Bearer fixture-private-token",source.takeRequest().getHeader("Authorization"))
            assertNull(target.takeRequest().getHeader("Authorization"))
        } }
    }
    @Test fun oauthErrorBodiesCanBeClassifiedWithoutLoggingTheirContents()=runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(400).setBody("{\"error\":\"authorization_pending\"}"));server.start()
            val response=YouTubeHttp().request(server.url("/token").toString(),"{}")
            assertEquals(400,response.status);assertEquals("authorization_pending",org.json.JSONObject(response.text).getString("error"))
            assertFalse(response.toString().contains("authorization_pending"))
        }
    }
}
