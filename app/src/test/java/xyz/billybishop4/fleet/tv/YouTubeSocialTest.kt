package xyz.billybishop4.fleet.tv

import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Real protocol shapes with a fake transport: no Google account is ever mutated. */
class YouTubeSocialTest {
    private val id="abcdefghijk"
    private val channelId="UC1111111111111111111111"
    private fun signed()=MemoryYouTubeCredentials(YouTubeSession("account-a","refresh-a",Long.MAX_VALUE))
    private fun ok()=YouTubeHttpResponse(200,"""{"responseContext":{"visitorData":"fixture"}}""")
    private suspend fun authFailure(block:suspend ()->Unit) {
        try{block();fail("Expected an account-bound failure")}catch(_:YouTubeAuthError){}
    }
    @Test fun unsignedActionsNeverBootstrapOrSendAnAccountRpc()=runBlocking {
        val t=FixtureYouTubeTransport();val client=YouTubeClient(MemoryYouTubeCredentials(),t)
        authFailure{client.rate(id,YouTubeRating.LIKE)}
        authFailure{client.subscribe(YouTubeChannel(channelId,"A channel"),true)}
        authFailure{client.playlists(id)}
        authFailure{client.setSaved(id,"WL",true)}
        authFailure{client.createPlaylist("New",id)}
        assertTrue(t.requests.isEmpty())
    }
    @Test fun likeDislikeAndTheirRemovalUseDistinctAuthenticatedEndpoints()=runBlocking {
        val t=FixtureYouTubeTransport();t.respond={ok()};val client=YouTubeClient(signed(),t)
        client.rate(id,YouTubeRating.LIKE);client.rate(id,YouTubeRating.DISLIKE)
        client.rate(id,YouTubeRating.NONE,YouTubeRating.LIKE)
        client.rate(id,YouTubeRating.NONE,YouTubeRating.DISLIKE)
        val calls=t.requests.filter{it.path.startsWith("/youtubei/")}
        assertEquals(listOf("like","dislike","removelike","removedislike"),calls.map{it.path.substringAfterLast('/')})
        for(call in calls){assertEquals(id,call.body!!.getJSONObject("target").getString("videoId"));assertEquals("Bearer account-a",call.authorization)
            assertEquals("TVHTML5",call.body.getJSONObject("context").getJSONObject("client").getString("clientName"))}
    }
    @Test fun unknownRemovalChecksCurrentServerRatingBeforeChangingAnything()=runBlocking {
        val t=FixtureYouTubeTransport();t.respond={r->if(r.path.endsWith("/next"))YouTubeHttpResponse(200,"""{"likeButtonRenderer":{"likeStatus":"DISLIKE"}}""") else ok()}
        val client=YouTubeClient(signed(),t);client.rate(id,YouTubeRating.NONE)
        assertTrue(t.requests.last().path.endsWith("/removedislike"))
        t.respond={YouTubeHttpResponse(200,"{}")};val before=t.requests.count{it.path.contains("/like/")}
        try{client.rate(id,YouTubeRating.NONE);fail("Unknown state must not report removal")}catch(_:UserMessage){}
        assertEquals(before,t.requests.count{it.path.contains("/like/")})
    }
    @Test fun subscriptionsForwardActualServerParamsAndNeverGuessState()=runBlocking {
        val t=FixtureYouTubeTransport();t.respond={ok()};val client=YouTubeClient(signed(),t)
        val channel=YouTubeChannel(channelId,"Creator",subscribeParams="subscribe%3D",unsubscribeParams="unsubscribe%3D")
        client.subscribe(channel,true);client.subscribe(channel,false)
        val calls=t.requests.filter{it.path.contains("subscription/")}
        assertEquals(listOf("subscribe","unsubscribe"),calls.map{it.path.substringAfterLast('/')})
        assertEquals(listOf("subscribe%3D","unsubscribe%3D"),calls.map{it.body!!.getString("params")})
        assertTrue(calls.all{it.body!!.getJSONArray("channelIds").getString(0)==channelId})
    }
    @Test fun http200AccountDenialAndEmptyAcknowledgementAreFailures()=runBlocking {
        val t=FixtureYouTubeTransport();val client=YouTubeClient(signed(),t)
        val denials=listOf("{}","""{"responseContext":{},"error":{"code":403}}""",
            """{"responseContext":{},"alerts":[{"alertRenderer":{"type":"ERROR","text":{"simpleText":"Action unavailable"}}}]}""",
            """{"responseContext":{},"actions":[{"notificationActionRenderer":{"responseText":{"simpleText":"Couldn't save video"}}}]}""")
        for(denial in denials){t.respond={YouTubeHttpResponse(200,denial)}
            try{client.rate(id,YouTubeRating.LIKE);fail("Expected denied mutation")}catch(_:UserMessage){}}
        t.respond={YouTubeHttpResponse(403,"{}")}
        try{client.setSaved(id,"WL",true);fail("Expected HTTP failure")}catch(e:HttpFailure){assertEquals(403,e.status)}
        YouTubeSocialDecode.requireAccepted(JSONObject("""{"responseContext":{},"notificationActionRenderer":{"responseText":{"simpleText":"Added to Watch later"}}}"""))
    }
    @Test fun playlistsUseReturnedSelectionAndBoundedEditableOptions()=runBlocking {
        val nodes=(0..100).map { n->JSONObject().put("playlistAddToOptionRenderer",JSONObject().put("playlistId",if(n==0)"WL" else "PL$n")
            .put("title",JSONObject().put("simpleText","Playlist $n")).put("containsSelectedVideos",if(n%2==0)"ALL" else "NONE").put("isDisabled",n==1)) }
        val t=FixtureYouTubeTransport();t.respond={YouTubeHttpResponse(200,JSONObject().put("responseContext",JSONObject()).put("contents",JSONArray(nodes)).toString())}
        val playlists=YouTubeClient(signed(),t).playlists(id)
        assertEquals(60,playlists.size);assertEquals("WL",playlists.first().id);assertTrue(playlists.first().containsVideo)
        assertFalse(playlists[1].containsVideo);assertFalse(playlists[1].editable)
        assertEquals(id,t.requests.last().body!!.getJSONArray("videoIds").getString(0))
        assertTrue(YouTubeSocialDecode.playlists(JSONObject("""{"playlistAddToOptionRenderer":{"playlistId":"bad/id","title":"Bad"}}""")).isEmpty())
    }
    @Test fun saveAndRemoveUseCanonicalPlaylistEditActions()=runBlocking {
        val t=FixtureYouTubeTransport();t.respond={ok()};val client=YouTubeClient(signed(),t)
        client.setSaved(id,"WL",true);client.setSaved(id,"PLfixture",false)
        val calls=t.requests.filter{it.path.endsWith("/edit_playlist")};assertEquals(2,calls.size)
        val add=calls[0].body!!.getJSONArray("actions").getJSONObject(0)
        val remove=calls[1].body!!.getJSONArray("actions").getJSONObject(0)
        assertEquals("ACTION_ADD_VIDEO",add.getString("action"));assertEquals(id,add.getString("addedVideoId"))
        assertEquals("ACTION_REMOVE_VIDEO_BY_VIDEO_ID",remove.getString("action"));assertEquals(id,remove.getString("removedVideoId"))
    }
    @Test fun playlistCreationUsesWebContextAndReturnsActualNewId()=runBlocking {
        val t=FixtureYouTubeTransport();t.respond={r->when(r.path){"/"->YouTubeHttpResponse(200,"""{"INNERTUBE_CLIENT_VERSION":"2.fixture","VISITOR_DATA":"web-visitor"}""")
            else->YouTubeHttpResponse(200,"""{"responseContext":{},"playlistId":"PLcreated"}""")}}
        val client=YouTubeClient(signed(),t);assertEquals("PLcreated",client.createPlaylist("One \" & Two",id))
        val call=t.requests.last();assertTrue(call.path.endsWith("/playlist/create"));assertEquals("One \" & Two",call.body!!.getString("title"))
        assertEquals("PRIVATE",call.body.getString("privacyStatus"))
        assertEquals("WEB",call.body.getJSONObject("context").getJSONObject("client").getString("clientName"));assertEquals("Bearer account-a",call.authorization)
    }
    @Test fun signOutCancelsAnActiveMutationAndErasesItsAccountScope()=runBlocking {
        val t=FixtureYouTubeTransport();val started=CompletableDeferred<Unit>();val cancelled=CompletableDeferred<Unit>()
        t.respond={try{started.complete(Unit);awaitCancellation()}finally{cancelled.complete(Unit)}}
        val store=signed();val client=YouTubeClient(store,t);val scope=client.accountScope()!!
        val action=async{client.rate(id,YouTubeRating.LIKE,expectedScope=scope)}
        withTimeout(1000){started.await()};client.signOut();withTimeout(1000){cancelled.await()};action.join()
        assertTrue(action.isCancelled);assertNull(client.account());assertNull(store.value);assertFalse(client.accountScopeCurrent(scope))
    }
    @Test fun oldScopeCannotMutateANewlySignedInAccount()=runBlocking {
        val t=FixtureYouTubeTransport();val client=YouTubeClient(signed(),t,now={1000});val old=client.accountScope()!!
        t.respond={YouTubeHttpResponse(200,"""{"access_token":"account-b","refresh_token":"refresh-b","expires_in":3600}""")}
        client.pollSignIn(YouTubeDeviceCode("device","code","https://www.google.com/device",100000,5))
        assertFalse(client.accountScopeCurrent(old));assertNotEquals(old,client.accountScope())
        authFailure{client.rate(id,YouTubeRating.LIKE,expectedScope=old)}
        assertTrue(t.requests.none{it.path.contains("/youtubei/")})
    }
    @Test fun newSignInCancelsOldAccountRequestsInsteadOfReusingTheNewBearer()=runBlocking {
        val t=FixtureYouTubeTransport();val started=CompletableDeferred<Unit>();val client=YouTubeClient(signed(),t,now={1000})
        t.respond={r->if(r.path.endsWith("/token"))YouTubeHttpResponse(200,"""{"access_token":"account-b","refresh_token":"refresh-b","expires_in":3600}""")
            else{started.complete(Unit);awaitCancellation()}}
        val action=async{client.rate(id,YouTubeRating.LIKE)};withTimeout(1000){started.await()}
        client.pollSignIn(YouTubeDeviceCode("device","code","https://www.google.com/device",100000,5));action.join()
        assertTrue(action.isCancelled);assertEquals("account-b",client.account()!!.accessToken)
        assertEquals(listOf("Bearer account-a"),t.requests.filter{it.path.contains("/youtubei/")}.map{it.authorization})
    }
    @Test fun signOutDuringRefreshDoesNotWaitForNetworkOrRestoreOldCredentials()=runBlocking {
        val started=CompletableDeferred<Unit>();val cancelled=CompletableDeferred<Unit>();val t=FixtureYouTubeTransport()
        t.respond={try{started.complete(Unit);awaitCancellation()}finally{cancelled.complete(Unit)}}
        val store=MemoryYouTubeCredentials(YouTubeSession("expired","refresh",0));val client=YouTubeClient(store,t)
        val action=async{client.rate(id,YouTubeRating.LIKE)};withTimeout(1000){started.await()}
        withTimeout(1000){client.signOut();cancelled.await()};action.join();assertTrue(action.isCancelled);assertNull(store.value)
    }
    @Test fun repeatedUnauthorizedResponsesRevokeOnlyThisAccountAndInvalidateScope()=runBlocking {
        val t=FixtureYouTubeTransport();t.respond={r->if(r.path.endsWith("/token"))YouTubeHttpResponse(200,"""{"access_token":"refreshed","expires_in":3600}""") else YouTubeHttpResponse(401,"{}")}
        val client=YouTubeClient(signed(),t);val scope=client.accountScope()!!
        authFailure{client.rate(id,YouTubeRating.LIKE)}
        assertNull(client.account());assertFalse(client.accountScopeCurrent(scope));assertEquals(2,t.requests.count{it.path.contains("/like/")})
    }
    @Test fun authenticatedHistoryStaysInItsEpochAndOnlyAllowedStatsOriginsReceiveABearer()=runBlocking {
        val t=FixtureYouTubeTransport();val client=YouTubeClient(signed(),t);assertNotNull(client.account())
        val epoch=client.historyAccountEpoch()!!;val nonce="1234567890abcdef"
        t.respond={r->if(r.path.endsWith("/player"))YouTubeHttpResponse(200,"""{"videoDetails":{"videoId":"$id"},"playabilityStatus":{"status":"OK"},"playbackTracking":{"videostatsPlaybackUrl":{"baseUrl":"https://www.youtube.com/api/stats/playback?docid=$id"},"videostatsWatchtimeUrl":{"baseUrl":"https://www.youtube.com/api/stats/watchtime?docid=$id"}}}""") else YouTubeHttpResponse(204,"")}
        val session=client.historySession(id,nonce,epoch)!!;assertTrue(client.historySessionCurrent(session))
        val stats=session.playbackUrl+"&cpn=$nonce"
        assertEquals(nonce,t.requests.last().body!!.getString("cpn"));assertTrue(client.sendHistory(session,stats))
        assertEquals("Bearer account-a",t.requests.last().authorization)
        val before=t.requests.size;assertFalse(client.sendHistory(session,"https://other.example/api/stats/playback"));assertFalse(client.sendHistory(session,stats+"&cpn=duplicate"));assertEquals(before,t.requests.size)
        client.signOut();assertFalse(client.historySessionCurrent(session));assertFalse(client.sendHistory(session,stats))
        assertNull(client.historySession(id,nonce,epoch));assertEquals(before,t.requests.size)
    }
    @Test fun historyRejectsRedirectResponsesAndSharesANoRedirectConnectionPool()=runBlocking {
        assertFalse(YouTubeHttp.trackingClient.followRedirects);assertFalse(YouTubeHttp.trackingClient.followSslRedirects)
        assertSame(YouTubeHttp.client.connectionPool,YouTubeHttp.trackingClient.connectionPool)
        val t=FixtureYouTubeTransport();t.respond={YouTubeHttpResponse(302,"")};val client=YouTubeClient(signed(),t);client.account()
        val session=YouTubeHistorySession(client.historyAccountEpoch()!!,id,"1234567890abcdef","https://www.youtube.com/api/stats/playback","https://www.youtube.com/api/stats/watchtime")
        assertFalse(client.sendHistory(session,session.playbackUrl+"?docid=$id&cpn=${session.nonce}"));assertEquals(1,t.requests.size)
    }
    @Test fun watchMetadataKeepsRealCreatorRatingAndCanonicalRemoteResume() {
        val j=JSONObject("""{"videoOwnerRenderer":{"title":{"simpleText":"Actual creator"},"navigationEndpoint":{"browseEndpoint":{"browseId":"$channelId"}},"subscribeButton":{"subscribeButtonRenderer":{"channelId":"$channelId","subscribed":true,"serviceEndpoints":[{"subscribeEndpoint":{"params":"sub"}},{"unsubscribeEndpoint":{"params":"unsub"}}]}}},"likeButtonRenderer":{"likeStatus":"DISLIKE"}}""")
        val details=YouTubeWatchDecode.details(j,id);assertEquals(channelId,details.channel!!.id);assertEquals("Actual creator",details.channel.name)
        assertEquals(true,details.channel.subscribed);assertEquals("unsub",details.channel.unsubscribeParams);assertEquals(YouTubeRating.DISLIKE,details.rating)
        val card=JSONObject("""{"videoRenderer":{"videoId":"$id","title":"Video","ownerText":{"runs":[{"text":"Actual creator","navigationEndpoint":{"browseEndpoint":{"browseId":"$channelId"}}}]},"navigationEndpoint":{"watchEndpoint":{"videoId":"$id","startTimeSeconds":73}}}}""")
        val video=YouTubeDecode.page(card).videos.single();assertEquals(channelId,video.channelId);assertEquals(73000L,video.resumePositionMs)
    }
}
