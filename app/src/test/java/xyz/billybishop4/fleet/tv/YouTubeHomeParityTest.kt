package xyz.billybishop4.fleet.tv

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class YouTubeHomeParityTest {
    private fun shelf(name:String,id:String="V0000000001"):JSONObject=JSONObject().put("shelfRenderer",JSONObject()
        .put("headerRenderer",JSONObject().put("shelfHeaderRenderer",JSONObject().put("avatarLockup",JSONObject()
            .put("avatarLockupRenderer",JSONObject().put("title",JSONObject().put("simpleText",name))))))
        .put("content",JSONObject().put("videoRenderer",JSONObject().put("videoId",id).put("title","A real result"))))
    private fun response(vararg rows:JSONObject,cursor:String?=null):YouTubeHttpResponse {
        val section=JSONObject().put("contents",JSONArray(rows.toList()))
        if(cursor!=null)section.put("continuations",JSONArray().put(JSONObject().put("nextContinuationData",JSONObject().put("continuation",cursor))))
        return YouTubeHttpResponse(200,JSONObject().put("contents",JSONObject().put("sectionListRenderer",section)).toString())
    }
    @Test fun availableGuestHomeUsesRealShelvesAndItsContinuationWithoutTopicRequests()=runBlocking {
        val t=FixtureYouTubeTransport();t.respond={r->if(r.body!!.has("continuation"))response(shelf("More from YouTube","V0000000002")) else response(shelf("A real TV shelf"),cursor="home-next")}
        val client=YouTubeClient(MemoryYouTubeCredentials(),t)
        val home=client.page(YouTubeFeed.HOME);assertEquals(listOf("A real TV shelf"),home.shelves.map{it.name});assertEquals("home-next",home.continuation)
        val next=client.page(YouTubeFeed.HOME,continuation=home.continuation);assertEquals(listOf("More from YouTube"),next.shelves.map{it.name})
        val calls=t.requests.filter{it.path.endsWith("/browse")};assertEquals(2,calls.size);assertTrue(calls.all{it.authorization==null})
        assertEquals("default",calls.first().body!!.getString("browseId"));assertEquals("home-next",calls.last().body!!.getString("continuation"))
        assertTrue(calls.none{it.body!!.optString("browseId").startsWith("FEtopics_")})
    }
    @Test fun signedOutHomeKeepsServerOrderEvenWhenLocalHistoryWouldFavorAnotherRow()=runBlocking {
        val t=FixtureYouTubeTransport();t.respond={response(shelf("Recommended by YouTube","V0000000002"),shelf("From your TV session","V0000000001"))}
        val watched=Progress(Title("V0000000001","Local watched title","youtube"),null,45000,120000,10)
        val home=YouTubeClient(MemoryYouTubeCredentials(),t,localHistory={listOf(watched)}).page(YouTubeFeed.HOME)
        assertEquals(listOf("Recommended by YouTube","From your TV session"),home.shelves.map{it.name})
        assertEquals(listOf("V0000000002","V0000000001"),home.videos.map{it.id})
        assertEquals(1,t.requests.count{it.body!=null});assertFalse(t.requests.any{it.path.endsWith("/next")})
    }
    @Test fun signedInHomePreservesPersonalizedServerLabelsAndNeverSubstitutesPublicTopics()=runBlocking {
        val t=FixtureYouTubeTransport();t.respond={response(shelf("Your real server recommendations"),shelf("Your subscriptions","V0000000002"))}
        val client=YouTubeClient(MemoryYouTubeCredentials(YouTubeSession("access","refresh",Long.MAX_VALUE)),t)
        val page=client.page(YouTubeFeed.HOME);assertEquals(listOf("Your real server recommendations","Your subscriptions"),page.shelves.map{it.name})
        assertEquals(1,t.requests.count{it.path.endsWith("/browse")});assertEquals("Bearer access",t.requests.last().authorization)
    }
}
