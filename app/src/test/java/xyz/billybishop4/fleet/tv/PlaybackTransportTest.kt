package xyz.billybishop4.fleet.tv

import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class PlaybackTransportTest {
    @Test fun mediaConnectionsAreSharedButRedirectsNeverForwardPrivateHeadersToAnotherOrigin() {
        MockWebServer().use{source->MockWebServer().use{target->
            source.start();target.start()
            source.enqueue(MockResponse().setResponseCode(302).addHeader("Location",target.url("/media")))
            target.enqueue(MockResponse().setBody("generated media"))
            val request=Request.Builder().url(source.url("/start")).header("Cookie","generated-cookie")
                .header("Authorization","Bearer generated-token").header("Proxy-Authorization","generated-proxy").build()
            YouTubeHttp.mediaClient.newCall(request).execute().use{assertEquals("generated media",it.body!!.string())}
            assertEquals("generated-cookie",source.takeRequest().getHeader("Cookie"))
            val redirected=target.takeRequest()
            for(header in listOf("Cookie","Authorization","Proxy-Authorization"))assertNull(header,redirected.getHeader(header))
            assertSame(YouTubeHttp.client.connectionPool,YouTubeHttp.mediaClient.connectionPool)
            assertEquals(0,YouTubeHttp.mediaClient.callTimeoutMillis)
        }}
    }
    @Test fun redirectsWithinTheProviderRetainRequiredMediaHeaders() {
        MockWebServer().use{server->
            server.start();server.enqueue(MockResponse().setResponseCode(302).addHeader("Location","/media"))
            server.enqueue(MockResponse().setBody("generated media"))
            YouTubeHttp.mediaClient.newCall(Request.Builder().url(server.url("/start")).header("Cookie","generated-cookie")
                .header("Authorization","Bearer generated-token").build()).execute().close()
            server.takeRequest();val next=server.takeRequest()
            assertEquals("generated-cookie",next.getHeader("Cookie"));assertEquals("Bearer generated-token",next.getHeader("Authorization"))
        }
    }
}
