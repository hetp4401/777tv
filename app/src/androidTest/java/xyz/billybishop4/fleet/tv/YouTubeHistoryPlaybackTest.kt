@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package xyz.billybishop4.fleet.tv

import android.view.KeyEvent
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Worker-emulator generated account/media fixtures only. No physical TV or Google account. */
class YouTubeHistoryPlaybackTest:NativeTestBase() {
    private val base="http://10.0.2.2:8878"
    private fun control(args:String)=runBlocking{Http().text("$base/youtube/control?$args")}
    private fun requests()=JSONObject(runBlocking{Http().text("$base/youtube/state")}).getJSONArray("requests").objects()
    private fun stats()=requests().filter{it.optString("path").contains("/api/stats/")}
    @Before fun prepare() {
        control("reset=1")
        ui {
            a.model.top(Section.SEARCH);a.model.server(base);a.model.updatePreferences(Preferences())
            a.model.store.youTubeCredentials().write(null);a.model.clearHistory();a.model.youtubeFixture(base);a.model.top(Section.YOUTUBE)
        }
        await{ui{!a.model.youtube.busy && a.model.youtube.videos.isNotEmpty()}}
    }
    @After fun cleanup() {
        ui{a.model.top(Section.SEARCH);a.model.youtube.signOut();a.model.clearHistory()}
        await{ui{a.model.youtube.account==null}}
        control("reset=1")
    }
    private fun signIn(){control("approve=1");ui{a.model.youtube.startSignIn()};await{ui{a.model.youtube.account!=null && !a.model.youtube.busy}}}
    private fun play(){ui{a.model.youtube.play(YouTubeVideo("V0000000001","History playback fixture"))};playing()}
    @Test fun unsignedFocusAndRealPlaybackNeverSendAccountTracking() {
        val first=ui{a.model.youtube.videos.first()}
        await{tagged("youtube-video:${first.id}")?.isShown==true}
        ui{tagged("youtube-video:${first.id}")!!.requestFocus()};sendRemoteKey(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertNull(player());assertTrue(stats().isEmpty())
        play();await{ui{a.player!!.currentPosition>=5000}};ui{a.player!!.pause()}
        assertTrue(stats().isEmpty());assertFalse(requests().any{it.optString("path").endsWith("/player") && it.optBoolean("authenticated")})
    }
    @Test fun signedInPlaybackRecordsActualProgressAndSignOutStopsFurtherWrites() {
        signIn();play()
        await(15000){stats().any{it.optString("path").endsWith("/watchtime")}}
        val record=stats().first{it.optString("path").endsWith("/playback")}
        assertTrue(record.optBoolean("authenticated"));assertEquals("V0000000001",record.optString("docid"))
        val nonce=record.optString("cpn");assertEquals(16,nonce.length)
        val initial=stats().last{it.optString("path").endsWith("/watchtime")}.optString("cmt").toDouble()
        ui{a.player!!.seekTo(10000)};await{ui{a.player!!.currentPosition>=11000}};ui{a.player!!.pause()}
        await{stats().any{it.optString("path").endsWith("/watchtime") && (it.optString("cmt").toDoubleOrNull()?:0.0)>=10.0}}
        val updates=stats();assertEquals(1,updates.count{it.optString("path").endsWith("/playback")})
        assertTrue(updates.all{it.optString("cpn")==nonce && it.optBoolean("authenticated")})
        assertTrue(updates.last().optString("cmt").toDouble()>initial)
        ui{a.model.youtube.signOut()};await{ui{a.model.youtube.account==null}}
        val before=stats().size;val position=ui{a.player!!.currentPosition};ui{a.player!!.play()}
        await{ui{a.player!!.currentPosition>=position+2500}};ui{a.player!!.pause()}
        assertEquals(before,stats().size)
    }
    @Test fun googlePausedHistoryDoesNotReceiveRecordsAndPlaybackContinues() {
        signIn();control("history_paused=1");play()
        await{ui{a.player!!.currentPosition>=6000}};ui{a.player!!.pause()}
        assertTrue(requests().any{it.optString("path").endsWith("/player") && it.optBoolean("authenticated")})
        assertTrue(stats().isEmpty());assertNotNull(player())
        assertFalse(requests().any{it.optString("path").contains("resume_watch_history")})
    }
    @Test fun signingInWhileTheSameVideoPlaysStartsAFreshCurrentPositionBaseline() {
        play();await{ui{a.player!!.currentPosition>=3000}}
        val existing=player();assertTrue(stats().isEmpty());signIn()
        assertSame("Signing in must not rebuild the playing video",existing,player())
        await(18000){stats().any{it.optString("path").endsWith("/watchtime")}}
        val update=stats().first{it.optString("path").endsWith("/watchtime")}
        assertTrue("Unsigned viewing must not be replayed into the account",update.optString("st").substringBefore(',').toDouble()>=3.0)
        await{tagged("youtube-next")?.isEnabled==true}
        assertTrue(ui{a.player!!.isPlaying})
    }
    @Test fun aDirectPlayerWithSavedCredentialsLoadsTheAccountOnlyAfterAFrame() {
        ui {
            a.model.top(Section.SEARCH)
            a.model.store.youTubeCredentials().write(YouTubeSession("fixture-saved-access","fixture-saved-refresh",Long.MAX_VALUE))
            a.model.youtubeFixture(base)
            assertNull("No YouTube screen/account request has run",a.model.youtube.client.historyAccountEpoch())
            a.model.open(Route.Player(Watch(Title("V0000000001","Direct phone-style playback","youtube"),
                Stream("Generated video","$base/hls/fixture/orig.m3u8"),resolvedAtMs=System.currentTimeMillis())))
        }
        playing()
        await(15000){stats().any{it.optString("path").endsWith("/watchtime")}}
        assertTrue(stats().all{it.optBoolean("authenticated")})
        await{tagged("youtube-next")?.isEnabled==true}
        assertTrue(ui{a.playback!!.snapshot!!.firstFrameMs>0})
    }
}
