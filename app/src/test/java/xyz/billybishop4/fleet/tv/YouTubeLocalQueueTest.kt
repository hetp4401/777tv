package xyz.billybishop4.fleet.tv

import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class YouTubeLocalQueueTest {
    private fun video(n:Int,short:Boolean=false)=YouTubeVideo(n.toString().padStart(11,'0'),"Video $n",isShort=short)
    private fun watch(video:YouTubeVideo)=Watch(video.title(),Stream("Generated","https://media.example.test/${video.id}.m3u8"),resolvedAtMs=System.currentTimeMillis(),short=video.isShort)
    private fun next(vararg videos:YouTubeVideo)=YouTubeHttpResponse(200,JSONObject().put("contents",JSONArray(videos.map { v->JSONObject()
        .put("videoRenderer",JSONObject().put("videoId",v.id).put("title",v.name)) })).toString())
    private suspend fun settled(model:YouTubeModel){withTimeout(3000){while(model.resolving)delay(1)}}
    @Test fun explicitQueuePrecedesRealRelatedAndPreviousForwardKeepsPlaybackOrder() {
        val queue=YouTubeLocalQueue();queue.beginLocal();queue.opened(video(1))
        queue.offer(video(1).id,listOf(video(4),video(5,true),video(1)));assertTrue(queue.enqueue(video(2)));assertTrue(queue.enqueue(video(3)))
        assertEquals(video(2),queue.peek(video(1).id,1));queue.opened(video(2));assertEquals(video(3),queue.peek(video(2).id,1))
        assertEquals(video(1),queue.peek(video(2).id,-1));queue.opened(video(1));assertEquals(video(2),queue.peek(video(1).id,1))
        queue.opened(video(2));queue.opened(video(3));queue.offer(video(3).id,listOf(video(1),video(2),video(4),video(5,true)))
        assertEquals(video(4),queue.peek(video(3).id,1));assertNull(queue.peek(video(99).id,1))
    }
    @Test fun localQueueIsBoundedRejectsDuplicatesShortsAndNeverCompetesWithPhone() {
        val queue=YouTubeLocalQueue();queue.beginLocal();queue.opened(video(1))
        assertFalse(queue.enqueue(video(1)));assertFalse(queue.enqueue(video(2,true)))
        for(n in 2..21)assertTrue(queue.enqueue(video(n)))
        assertFalse(queue.enqueue(video(22)));assertFalse(queue.enqueue(video(2)));assertEquals(20,queue.queuedVideos.size)
        assertEquals(YouTubeQueueStatus.QUEUED,queue.status(video(2)));assertEquals(YouTubeQueueStatus.FULL,queue.status(video(22)))
        assertEquals(YouTubeQueueStatus.CURRENT,queue.status(video(1)))
        queue.beginRemote();queue.opened(video(1));queue.offer(video(1).id,listOf(video(2)))
        assertFalse(queue.enqueue(video(3)));assertNull(queue.peek(video(1).id,1));queue.releasePhone();assertEquals(video(2),queue.peek(video(1).id,1))
        queue.beginLocal();assertTrue(queue.queuedVideos.isEmpty());assertNull(queue.peek(video(1).id,1))
    }
    @Test fun regularPlaybackDoesNotFetchNextUntilFirstFrameAndPanelAdoptsSameRequest()=runBlocking {
        val scope=CoroutineScope(coroutineContext+SupervisorJob());val t=FixtureYouTubeTransport();val started=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        t.respond={started.complete(Unit);release.await();next(video(2),video(3))}
        var opened:Watch?=null;var resolves=0;var ready=0
        try {
            val resolver=object:YouTubeStreamResolver{override suspend fun resolve(video:YouTubeVideo):Watch{resolves++;return watch(video)}}
            val model=YouTubeModel(YouTubeClient(MemoryYouTubeCredentials(),t),scope,{}, {w,_->opened=w},{},resolver)
            model.play(video(1));settled(model);assertTrue(t.requests.isEmpty());assertFalse(model.canStepRegular(opened!!,1))
            model.prepareNext(opened!!){ready++};withTimeout(3000){started.await()}
            val panel=scope.async{model.watchDetails(video(1).id)};delay(10);panel.cancelAndJoin()
            val secondPanel=scope.async{model.watchDetails(video(1).id)};release.complete(Unit);secondPanel.await()
            withTimeout(3000){while(ready==0)delay(1)}
            assertEquals(1,t.requests.count{it.path.endsWith("/next")});assertTrue(model.canStepRegular(opened!!,1));assertEquals(1,resolves)
            assertFalse(model.onRegularEnded(opened!!,false));assertEquals(1,resolves)
            assertTrue(model.onRegularEnded(opened!!,true));settled(model);assertEquals(video(2).id,opened!!.title.id);assertEquals(2,resolves)
        }finally{scope.cancel()}
    }
    @Test fun nextAndPreviousUseOneResolverAndRecentSourcesWhileNewManualPlayClearsQueue()=runBlocking {
        val scope=CoroutineScope(coroutineContext+SupervisorJob());var opened:Watch?=null;var resolves=0
        try {
            val resolver=object:YouTubeStreamResolver{override suspend fun resolve(video:YouTubeVideo):Watch{resolves++;return watch(video)}}
            val model=YouTubeModel(YouTubeClient(MemoryYouTubeCredentials(),FixtureYouTubeTransport()),scope,{}, {w,_->opened=w},{},resolver)
            model.play(video(1));settled(model);assertTrue(model.enqueue(video(2)));assertTrue(model.enqueue(video(3)))
            assertTrue(model.stepRegular(opened!!,1));settled(model);assertEquals(video(2).id,opened!!.title.id)
            assertTrue(model.stepRegular(opened!!,-1));settled(model);assertEquals(video(1).id,opened!!.title.id);assertEquals(2,resolves)
            assertTrue(model.stepRegular(opened!!,1));settled(model);assertTrue(model.stepRegular(opened!!,1));settled(model)
            assertEquals(video(3).id,opened!!.title.id);assertEquals(3,resolves)
            assertTrue(model.enqueue(video(4)));model.play(video(5));settled(model);assertFalse(model.canStepRegular(opened!!,1));assertFalse(model.canStepRegular(opened!!,-1))
        }finally{scope.cancel()}
    }
    @Test fun phoneOwnedPlaybackHasNoLocalAutoplayUntilDisconnectReleasesOwnership()=runBlocking {
        val scope=CoroutineScope(coroutineContext+SupervisorJob());val t=FixtureYouTubeTransport();t.respond={next(video(2))};var opened:Watch?=null
        try {
            val resolver=object:YouTubeStreamResolver{override suspend fun resolve(video:YouTubeVideo)=watch(video)}
            val model=YouTubeModel(YouTubeClient(MemoryYouTubeCredentials(),t),scope,{}, {w,_->opened=w},{},resolver)
            model.playRemote(video(1));settled(model);model.prepareNext(opened!!);delay(10);assertTrue(t.requests.isEmpty())
            model.watchDetails(video(1).id);assertFalse(model.canStepRegular(opened!!,1));assertFalse(model.onRegularEnded(opened!!,true));assertFalse(model.enqueue(video(3)))
            model.cancelRemotePlayback();assertTrue(model.canStepRegular(opened!!,1));assertTrue(model.stepRegular(opened!!,1));settled(model)
            assertEquals(video(2).id,opened!!.title.id);assertNull(opened!!.requestedPositionMs)
        }finally{scope.cancel()}
    }
    @Test fun missingOrFailedWatchNextNeverCreatesAnAutoplayCandidate()=runBlocking {
        val scope=CoroutineScope(coroutineContext+SupervisorJob());val t=FixtureYouTubeTransport();t.respond={YouTubeHttpResponse(503,"{}")};var opened:Watch?=null
        val done=CompletableDeferred<Unit>()
        try {
            val resolver=object:YouTubeStreamResolver{override suspend fun resolve(video:YouTubeVideo)=watch(video)}
            val model=YouTubeModel(YouTubeClient(MemoryYouTubeCredentials(),t),scope,{}, {w,_->opened=w},{},resolver)
            model.play(video(1));settled(model);model.prepareNext(opened!!){done.complete(Unit)};withTimeout(3000){done.await()}
            assertFalse(model.canStepRegular(opened!!,1));assertFalse(model.onRegularEnded(opened!!,true));assertEquals(video(1).id,opened!!.title.id)
        }finally{scope.cancel()}
    }
    @Test fun optionalMetadataFailureCannotCancelANonSupervisorApplicationScope()=runBlocking {
        val parent=Job();val scope=CoroutineScope(coroutineContext+parent);val t=FixtureYouTubeTransport();t.respond={YouTubeHttpResponse(503,"{}")}
        var opened:Watch?=null;val ready=CompletableDeferred<Unit>()
        try {
            val resolver=object:YouTubeStreamResolver{override suspend fun resolve(video:YouTubeVideo)=watch(video)}
            val model=YouTubeModel(YouTubeClient(MemoryYouTubeCredentials(),t),scope,{}, {w,_->opened=w},{},resolver)
            model.play(video(1));settled(model);model.prepareNext(opened!!){ready.complete(Unit)}
            withTimeout(3000){ready.await()};assertTrue(parent.isActive);assertFalse(model.canStepRegular(opened!!,1))
        }finally{scope.cancel()}
    }
    @Test fun acknowledgedMutationKeepsCurrentReadOnlyRelatedRequestAlive()=runBlocking {
        val scope=CoroutineScope(coroutineContext+SupervisorJob());val t=FixtureYouTubeTransport();val started=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        t.respond={started.complete(Unit);release.await();next(video(2))};var opened:Watch?=null
        try {
            val resolver=object:YouTubeStreamResolver{override suspend fun resolve(video:YouTubeVideo)=watch(video)}
            val model=YouTubeModel(YouTubeClient(MemoryYouTubeCredentials(),t),scope,{}, {w,_->opened=w},{},resolver)
            model.play(video(1));settled(model);val panel=scope.async{model.watchDetails(video(1).id)}
            withTimeout(3000){started.await()};model.accountMutated(YouTubeAccountMutation.RATING);release.complete(Unit)
            assertEquals(listOf(video(2).id),panel.await().related.videos.map{it.id});assertTrue(model.canStepRegular(opened!!,1))
            assertEquals(1,t.requests.count{it.path.endsWith("/next")})
        }finally{scope.cancel()}
    }
    @Test fun failedPhoneRelatedAndFreshLocalReplacementPreserveThePlayingVideosQueueAndMetadata()=runBlocking {
        for(replacement in listOf("phone","related","local")) {
            val scope=CoroutineScope(coroutineContext+SupervisorJob());val t=FixtureYouTubeTransport();t.respond={next(video(4))};var opened:Watch?=null
            val failureId=video(3).id
            try {
                val resolver=object:YouTubeStreamResolver{override suspend fun resolve(video:YouTubeVideo):Watch {
                    if(video.id==failureId)throw UserMessage("Generated source failure")
                    return watch(video)
                }}
                val model=YouTubeModel(YouTubeClient(MemoryYouTubeCredentials(),t),scope,{}, {w,_->opened=w},{},resolver)
                model.play(video(1));settled(model);assertTrue(model.enqueue(video(2)));model.watchDetails(video(1).id)
                when(replacement){"phone"->model.playRemote(video(3));"related"->model.playRelated(video(3),listOf(video(3),video(4))){};else->model.play(video(3))}
                settled(model);assertEquals(replacement,video(1).id,opened!!.title.id)
                assertEquals(replacement,YouTubeQueueStatus.QUEUED,model.queueStatus(video(2)));assertTrue(model.canStepRegular(opened!!,1))
                model.watchDetails(video(1).id);assertEquals(replacement,1,t.requests.count{it.path.endsWith("/next")})
                assertTrue(model.stepRegular(opened!!,1));settled(model);assertEquals(replacement,video(2).id,opened!!.title.id)
                assertTrue(model.stepRegular(opened!!,-1));settled(model);assertEquals(replacement,video(1).id,opened!!.title.id)
            }finally{scope.cancel()}
        }
    }
    @Test fun cancelledPhoneReplacementLeavesLocalNextAndItsPreparedMetadataIntact()=runBlocking {
        val scope=CoroutineScope(coroutineContext+SupervisorJob());val t=FixtureYouTubeTransport();t.respond={next(video(4))}
        val started=CompletableDeferred<Unit>();val cancelled=CompletableDeferred<Unit>();var opened:Watch?=null
        val failureId=video(3).id
        try {
            val resolver=object:YouTubeStreamResolver{override suspend fun resolve(video:YouTubeVideo):Watch {
                if(video.id==failureId)try{started.complete(Unit);awaitCancellation()}finally{cancelled.complete(Unit)}
                return watch(video)
            }}
            val model=YouTubeModel(YouTubeClient(MemoryYouTubeCredentials(),t),scope,{}, {w,_->opened=w},{},resolver)
            model.play(video(1));settled(model);assertTrue(model.enqueue(video(2)));model.watchDetails(video(1).id)
            model.playRemote(video(3));withTimeout(3000){started.await()};model.cancelRemotePlayback();withTimeout(3000){cancelled.await()}
            assertEquals(video(1).id,opened!!.title.id);assertEquals(YouTubeQueueStatus.QUEUED,model.queueStatus(video(2)))
            model.watchDetails(video(1).id);assertEquals(1,t.requests.count{it.path.endsWith("/next")})
            assertTrue(model.stepRegular(opened!!,1));settled(model);assertEquals(video(2).id,opened!!.title.id)
        }finally{scope.cancel()}
    }
    @Test fun samePlayingVideoRecoversNextAfterAccountSwitchAndSignOutWithoutResolvingAgain()=runBlocking {
        val scope=CoroutineScope(coroutineContext+SupervisorJob());val t=FixtureYouTubeTransport()
        t.respond={r->if(r.path.endsWith("/token"))YouTubeHttpResponse(200,"""{"access_token":"account-b","refresh_token":"refresh-b","expires_in":3600}""") else next(video(2))}
        val store=MemoryYouTubeCredentials(YouTubeSession("account-a","refresh-a",Long.MAX_VALUE));var opened:Watch?=null;var resolutions=0
        try {
            val client=YouTubeClient(store,t)
            val resolver=object:YouTubeStreamResolver{override suspend fun resolve(video:YouTubeVideo):Watch{resolutions++;return watch(video)}}
            val model=YouTubeModel(client,scope,{}, {w,_->opened=w},{},resolver)
            model.syncAccount();model.play(video(1));settled(model);val playing=opened!!
            val first=CompletableDeferred<Unit>();model.prepareNext(playing){first.complete(Unit)};withTimeout(3000){first.await()}
            assertTrue(model.canStepRegular(playing,1))
            client.pollSignIn(YouTubeDeviceCode("device","code","https://www.google.com/device",Long.MAX_VALUE,5));model.syncAccount()
            assertFalse(model.canStepRegular(playing,1))
            val signedIn=CompletableDeferred<Unit>();model.prepareNext(playing){signedIn.complete(Unit)};withTimeout(3000){signedIn.await()}
            assertTrue(model.canStepRegular(playing,1));assertEquals("Bearer account-b",t.requests.last().authorization)
            model.signOut();withTimeout(3000){while(client.account()!=null)delay(1)}
            val signedOut=CompletableDeferred<Unit>();model.prepareNext(playing){signedOut.complete(Unit)};withTimeout(3000){signedOut.await()}
            assertTrue(model.canStepRegular(playing,1));assertNull(t.requests.last().authorization)
            assertSame(playing,opened);assertEquals(1,resolutions);assertEquals(3,t.requests.count{it.path.endsWith("/next")})
        }finally{scope.cancel()}
    }
    @Test fun directPlayingWatchCanPrepareNextWhileOlderWatchCannotReplaceANewerCurrentItem()=runBlocking {
        val scope=CoroutineScope(coroutineContext+SupervisorJob());val t=FixtureYouTubeTransport();t.respond={next(video(2))};var opened:Watch?=null
        try {
            val resolver=object:YouTubeStreamResolver{override suspend fun resolve(video:YouTubeVideo)=watch(video)}
            val model=YouTubeModel(YouTubeClient(MemoryYouTubeCredentials(),t),scope,{}, {w,_->opened=w},{},resolver)
            val direct=watch(video(1));val first=CompletableDeferred<Unit>();model.prepareNext(direct){first.complete(Unit)};withTimeout(3000){first.await()}
            assertTrue(model.canStepRegular(direct,1));assertNull(opened)
            model.play(video(3));settled(model);assertTrue(model.enqueue(video(4)))
            val stale=CompletableDeferred<Unit>();model.prepareNext(direct){stale.complete(Unit)};withTimeout(3000){stale.await()}
            assertTrue(model.canStepRegular(opened!!,1));assertEquals(YouTubeQueueStatus.CURRENT,model.queueStatus(video(3)))
            assertEquals(YouTubeQueueStatus.QUEUED,model.queueStatus(video(4)));assertFalse(model.canStepRegular(direct,1))
            assertEquals(1,t.requests.count{it.path.endsWith("/next")})
        }finally{scope.cancel()}
    }
    @Test fun failedOrCancelledPhoneHandoffPreservesCommittedPhoneOwnershipUntilExplicitDisconnect()=runBlocking {
        for(cancel in listOf(false,true)) {
            val scope=CoroutineScope(coroutineContext+SupervisorJob());val t=FixtureYouTubeTransport();t.respond={next(video(3))};var opened:Watch?=null
            val started=CompletableDeferred<Unit>();val cancelled=CompletableDeferred<Unit>();val failedId=video(2).id
            try {
                val resolver=object:YouTubeStreamResolver{override suspend fun resolve(video:YouTubeVideo):Watch {
                    if(video.id==failedId) {
                        if(cancel)try{started.complete(Unit);awaitCancellation()}finally{cancelled.complete(Unit)}
                        else throw UserMessage("Generated failed handoff")
                    }
                    return watch(video)
                }}
                val model=YouTubeModel(YouTubeClient(MemoryYouTubeCredentials(),t),scope,{}, {w,_->opened=w},{},resolver)
                model.playRemote(video(1));settled(model);model.watchDetails(video(1).id)
                model.playRemote(video(2))
                if(cancel){withTimeout(3000){started.await()};model.cancelRemotePlayback(releaseQueue=false);withTimeout(3000){cancelled.await()}}
                else{settled(model);model.cancelRemotePlayback(releaseQueue=false)}
                assertEquals(video(1).id,opened!!.title.id);assertEquals(YouTubeQueueStatus.PHONE,model.queueStatus(video(1)))
                assertFalse(model.canStepRegular(opened!!,1));assertFalse(model.enqueue(video(4)));assertFalse(model.onRegularEnded(opened!!,true))
                model.cancelRemotePlayback();assertEquals(YouTubeQueueStatus.CURRENT,model.queueStatus(video(1)))
                assertTrue(model.canStepRegular(opened!!,1));assertTrue(model.stepRegular(opened!!,1));settled(model)
                assertEquals(video(3).id,opened!!.title.id)
            }finally{scope.cancel()}
        }
    }
}
