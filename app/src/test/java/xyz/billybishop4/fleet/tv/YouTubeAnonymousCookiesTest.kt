package xyz.billybishop4.fleet.tv

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class YouTubeAnonymousCookiesTest {
    private val tv="https://www.youtube.com/tv".toHttpUrl()
    private fun cookie(name:String,value:String="generated",expires:Long=Long.MAX_VALUE,domain:String="youtube.com",path:String="/")=Cookie.Builder()
        .name(name).value(value).domain(domain).path(path).expiresAt(expires).secure().build()
    @Test fun onlyAnonymousTvCookiesReturnToTheSameYoutubeHttpsScope() {
        val jar=YouTubeAnonymousCookies()
        jar.saveFromResponse(tv,listOf(cookie("YSC"),cookie("VISITOR_INFO1_LIVE"),cookie("SID"),cookie("SAPISID"),cookie("__Secure-3PSID"),
            cookie("LOGIN_INFO"),cookie("__Secure-YNID"),cookie("__Secure-YT_TVFAS"),cookie("DEVICE_INFO")))
        assertEquals(setOf("YSC","VISITOR_INFO1_LIVE"),jar.loadForRequest(tv).map{it.name}.toSet())
        for(address in listOf("https://accounts.google.com/","https://i.ytimg.com/","https://youtube.com.example.org/","http://www.youtube.com/tv"))
            assertTrue(jar.loadForRequest(address.toHttpUrl()).isEmpty())
        jar.saveFromResponse("https://accounts.google.com/".toHttpUrl(),listOf(cookie("PREF")))
        assertFalse(jar.loadForRequest(tv).any{it.name=="PREF"})
    }
    @Test fun expiryAndDeletionCannotLeaveAnOldVisitorCookieBehind() {
        var now=1000L;val jar=YouTubeAnonymousCookies{now}
        jar.saveFromResponse(tv,listOf(cookie("YSC",expires=2000),cookie("VISITOR_INFO1_LIVE")))
        now=2001;assertEquals(listOf("VISITOR_INFO1_LIVE"),jar.loadForRequest(tv).map{it.name})
        jar.saveFromResponse(tv,listOf(cookie("VISITOR_INFO1_LIVE",expires=1)))
        assertTrue(jar.loadForRequest(tv).isEmpty())
    }
    @Test fun cookieCountAndHeaderBytesStayBoundedPerClient() {
        val jar=YouTubeAnonymousCookies()
        val names=listOf("YSC","VISITOR_INFO1_LIVE","VISITOR_PRIVACY_METADATA","PREF","SOCS","CONSENT")
        val small=names.flatMap{name->listOf("youtube.com","www.youtube.com").flatMap{domain->
            listOf("/","/tv").map{path->cookie(name,domain=domain,path=path)}}}
        jar.saveFromResponse(tv,small);assertEquals(16,jar.loadForRequest(tv).size)
        val cookies=names.flatMap{name->listOf("youtube.com","www.youtube.com").map{domain->cookie(name,"x".repeat(2000),domain=domain)}}
        jar.saveFromResponse(tv,cookies);val retained=jar.loadForRequest(tv)
        assertTrue(retained.isNotEmpty());assertTrue(retained.size<=16)
        assertTrue(retained.joinToString("; "){"${it.name}=${it.value}"}.toByteArray(Charsets.UTF_8).size<=8192)
        // All sixteen variants match /tv. Construct a real Cookie header one
        // byte over the limit, including '=' and every '; ' separator.
        val variants=small.take(16)
        val valueBytes=8193-variants.joinToString("; "){"${it.name}="}.length
        val boundary=variants.mapIndexed { index,item->cookie(item.name,
            "x".repeat(valueBytes/variants.size+if(index<valueBytes%variants.size)1 else 0),domain=item.domain,path=item.path) }
        assertTrue(boundary.all{it.matches(tv)})
        assertEquals(8193,boundary.joinToString("; "){"${it.name}=${it.value}"}.toByteArray(Charsets.UTF_8).size)
        val exact=YouTubeAnonymousCookies();exact.saveFromResponse(tv,boundary)
        assertTrue(exact.loadForRequest(tv).joinToString("; "){"${it.name}=${it.value}"}.toByteArray(Charsets.UTF_8).size<=8192)
        assertTrue(YouTubeAnonymousCookies().loadForRequest(tv).isEmpty())
    }
    @Test fun tvSessionsNeverChangeTheSharedMediaTrackingOrSuggestionCookiePolicies() {
        YouTubeHttp.tvSession()
        for(client in listOf(YouTubeHttp.client,YouTubeHttp.mediaClient,YouTubeHttp.trackingClient,YouTubeHttp.suggestionClient))
            assertSame(CookieJar.NO_COOKIES,client.cookieJar)
    }
}
