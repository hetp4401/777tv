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
    @Test fun successfulEmptyGuestHomeIsCachedBrieflyWhileActualTopicShelvesStayFresh()=runBlocking {
        var now=1000L;val t=FixtureYouTubeTransport()
        t.respond={r->if(r.body!!.optString("browseId")=="default")YouTubeHttpResponse(200,"""{"feedNudgeRenderer":{"title":"Make YouTube your own"}}""")
            else response(shelf(when(r.body.optString("browseId")){"FEtopics_music"->"Music videos";"FEtopics_gaming"->"Game highlights";else->"Latest headlines"},
                when(r.body.optString("browseId")){"FEtopics_music"->"M0000000001";"FEtopics_gaming"->"G0000000001";else->"N0000000001"}))}
        val client=YouTubeClient(MemoryYouTubeCredentials(),t,now={now})
        assertEquals(listOf("Music videos","Game highlights","Latest headlines"),client.page(YouTubeFeed.HOME).shelves.map{it.name})
        client.page(YouTubeFeed.HOME);assertEquals(1,t.requests.count{it.body?.optString("browseId")=="default"})
        now+=300_001;client.page(YouTubeFeed.HOME);assertEquals(2,t.requests.count{it.body?.optString("browseId")=="default"})
        assertEquals(9,t.requests.count{it.body?.optString("browseId")?.startsWith("FEtopics_")==true})
    }
    @Test fun signedInHomePreservesPersonalizedServerLabelsAndNeverSubstitutesPublicTopics()=runBlocking {
        val t=FixtureYouTubeTransport();t.respond={response(shelf("Your real server recommendations"),shelf("Your subscriptions","V0000000002"))}
        val client=YouTubeClient(MemoryYouTubeCredentials(YouTubeSession("access","refresh",Long.MAX_VALUE)),t)
        val page=client.page(YouTubeFeed.HOME);assertEquals(listOf("Your real server recommendations","Your subscriptions"),page.shelves.map{it.name})
        assertEquals(1,t.requests.count{it.path.endsWith("/browse")});assertEquals("Bearer access",t.requests.last().authorization)
    }
}
