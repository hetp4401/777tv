@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package xyz.billybishop4.fleet.tv

import android.os.SystemClock
import android.graphics.Bitmap
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import androidx.media3.ui.PlayerView
import androidx.media3.common.Player
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Generated videos on isolated worker emulators: real surfaces, input and lifecycle. */
class YouTubeMotionUiTest:NativeTestBase() {
    private val base="http://10.0.2.2:8878"
    private fun control(value:String)=runBlocking{Http().text("$base/youtube/control?$value")}
    @Before fun prepare() {
        control("reset=1")
        ui {
            a.model.top(Section.SEARCH);a.model.server(base);a.model.clearHistory()
            a.model.store.youTubeCredentials().write(null);a.model.store.youTubePhoneEnabled(false)
            a.model.updatePreferences(Preferences(resume=false,youtubeAutoplay=false))
            a.model.youtubeFixture(base);a.model.top(Section.YOUTUBE)
        }
        await{tagged("youtube-page")?.isShown==true && ui{!a.model.youtube.busy}}
    }
    @After fun cleanup() { ui{a.model.top(Section.SEARCH);a.model.updatePreferences(Preferences())};control("reset=1") }
    private fun currentId()=ui{(a.model.route as? Route.Player)?.watch?.title?.id}
    private fun ready(id:String)=ui {
        val watch=(a.model.route as? Route.Player)?.watch
        watch?.title?.id==id && a.playback?.watch===watch && a.playback?.snapshot?.firstFrameMs?.let{it>0}==true &&
            (!watch.short || a.playback?.canRetireShortFrame==true) && a.player?.isPlaying==true
    }
    private fun shorts():List<YouTubeVideo> {
        click("YouTube Shorts");await{ui{!a.model.youtube.busy && a.model.youtube.videos.size>=4}}
        val choices=ui{a.model.youtube.videos.toList()}
        ui{a.model.youtube.play(choices[1])};await{ready(choices[1].id)}
        return choices
    }
    private fun outgoing()=ui{a.window.decorView.findTag("shorts-outgoing-frame")}
    private fun settled(id:String) {
        await{ready(id) && outgoing()==null && ui{a.playback?.view?.translationY==0f}}
        assertEquals(1,ui{descendants(a.window.decorView).filterIsInstance<PlayerView>().count{it.player!=null}})
        assertNull(tagged("playback-buffering"));assertNull(find("Buffering…"));assertNull(find("Connecting…"))
    }
    @Test fun regularAndShortsUseQuietLoadingAndRemoveTheIndicatorAfterTheCorrectFrame() {
        ui{a.model.youtube.play(YouTubeVideo("V0000000001","Generated regular video"))}
        await{ready("V0000000001")}
        assertNull(tagged("playback-buffering"));assertNull(find("Connecting…"));assertNull(find("Buffering…"))
        assertEquals(View.GONE,ui{tagged("youtube-loading-indicator")!!.visibility})
        ui{a.model.back()};await{tagged("youtube-page")?.isShown==true}
        val clips=shorts();settled(clips[1].id)
        assertEquals(View.GONE,ui{tagged("youtube-loading-indicator")!!.visibility})
        screenshot("youtube026-quiet-shorts")
    }
    @Test fun shortLoadingDelaysNeverFlashAndLongLoadingHasOnlyASmallSpinner() {
        val stage=ui{FrameLayout(a).also{(a.window.decorView as ViewGroup).addView(it,FrameLayout.LayoutParams(480,320))}}
        val loading=ui{YouTubeLoading(a,Watch(Title("loading","Loading",type="youtube"),Stream("Fixture",""))).also{stage.addView(it,FrameLayout.LayoutParams(-1,-1))}}
        try {
            SystemClock.sleep(300)
            assertFalse(ui{stage.findTag("youtube-loading-indicator")!!.isShown})
            await(2000){ui{stage.findTag("youtube-loading-indicator")!!.isShown}}
            assertTrue(ui{stage.findTag("youtube-loading-indicator")!!.width<=a.dp(36)})
            assertFalse(ui{descendants(stage).any{it is android.widget.TextView}})
            ui{loading.firstFrame();loading.buffering(true)}
            SystemClock.sleep(300);ui{loading.buffering(false)}
            SystemClock.sleep(1000);assertFalse(ui{stage.findTag("youtube-loading-indicator")!!.isShown})
            ui{loading.buffering(true)};await(2000){ui{stage.findTag("youtube-loading-indicator")!!.isShown}}
            ui{loading.buffering(false)};assertFalse(ui{stage.findTag("youtube-loading-indicator")!!.isShown})
            ui{loading.buffering(true);loading.close()}
            SystemClock.sleep(1000);assertFalse(ui{stage.findTag("youtube-loading-indicator")!!.isShown})
        } finally { ui{loading.close();(stage.parent as? ViewGroup)?.removeView(stage)} }
    }
    @Test fun shortsLoadingArtworkKeepsThePortraitVideoBounds() {
        val stage=ui{FrameLayout(a).also{(a.window.decorView as ViewGroup).addView(it,FrameLayout.LayoutParams(480,320))}}
        val loading=ui{YouTubeLoading(a,Watch(Title("portrait","Portrait",type="youtube"),Stream("Fixture",""),short=true)).also{stage.addView(it,FrameLayout.LayoutParams(-1,-1))}}
        try {
            await{ui{descendants(loading).filterIsInstance<android.widget.ImageView>().single().width==180}}
            val poster=ui{descendants(loading).filterIsInstance<android.widget.ImageView>().single()}
            assertEquals(320,ui{poster.height});assertEquals(150,ui{poster.left})
            assertEquals(android.widget.ImageView.ScaleType.CENTER_CROP,ui{poster.scaleType})
            assertFalse(ui{loading.isFocusable || loading.isClickable})
            ui{loading.firstFrame()};assertEquals(View.GONE,ui{poster.visibility})
        } finally { ui{loading.close();(stage.parent as? ViewGroup)?.removeView(stage)} }
    }
    @Test fun shortsSlideInBothDirectionsWithOnlyOneDecoderAndEndAtTheirOriginalBounds() {
        val clips=shorts();val observations=JSONArray()
        for((key,index,sign) in listOf(Triple(KeyEvent.KEYCODE_DPAD_DOWN,2,1),Triple(KeyEvent.KEYCODE_DPAD_UP,1,-1))) {
            val old=player()!!
            val samples=Collections.synchronizedList(ArrayList<JSONObject>())
            val interior=CountDownLatch(1)
            val observer=ViewTreeObserver.OnDrawListener {
                val prior=a.window.decorView.findTag("shorts-outgoing-frame")
                val next=a.playback?.view
                if(prior!=null && next!=null && prior.translationY*sign< -1f && next.translationY*sign>1f) {
                    if(samples.size<32)samples.add(JSONObject()
                        .put("id",(a.model.route as? Route.Player)?.watch?.title?.id)
                        .put("atMs",SystemClock.uptimeMillis()).put("incomingY",next.translationY).put("outgoingY",prior.translationY)
                        .put("height",a.window.decorView.height).put("direction",sign)
                        .put("oldPlaying",old.isPlaying).put("oldState",old.playbackState)
                        .put("oldSurfaceDetached",descendants(prior).filterIsInstance<PlayerView>().all{it.player==null})
                        .put("attachedPlayers",descendants(a.window.decorView).filterIsInstance<PlayerView>().count{it.player!=null}))
                    interior.countDown()
                }
            }
            ui{a.window.decorView.viewTreeObserver.addOnDrawListener(observer)}
            val inputFailure=AtomicReference<Throwable?>()
            val input=Thread({try{sendRemoteKey(key)}catch(error:Throwable){inputFailure.set(error)}},"shorts-transition-input")
            try {
                // Input injection may wait for a software-rendered frame. Let
                // the observer wake the capture thread independently of that.
                input.start()
                assertTrue("No drawable interior transition frame",interior.await(15,TimeUnit.SECONDS))
                val began=SystemClock.uptimeMillis()
                val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                val captured=SystemClock.uptimeMillis()
                val after=outgoing()!=null
                assertNotNull(bitmap)
                bitmap?.let { image->
                    val name="youtube026-shorts-slide-${if(sign>0)"down" else "up"}.png"
                    File(ui{a.getExternalFilesDir(null)!!},name).outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)}
                    image.recycle()
                }
                input.join(5000);assertFalse("Remote input did not finish",input.isAlive)
                inputFailure.get()?.let{throw AssertionError("Remote injection failed",it)}
                settled(clips[index].id)
                val frames=synchronized(samples){samples.toList()}
                assertTrue(frames.isNotEmpty())
                for(frame in frames) {
                    assertEquals(clips[index].id,frame.getString("id"))
                    assertTrue(kotlin.math.abs(frame.getDouble("incomingY"))<frame.getInt("height"))
                    assertTrue(kotlin.math.abs(frame.getDouble("outgoingY"))<frame.getInt("height"))
                    assertFalse(frame.getBoolean("oldPlaying"));assertEquals(Player.STATE_IDLE,frame.getInt("oldState"))
                    assertTrue(frame.getBoolean("oldSurfaceDetached"));assertEquals(1,frame.getInt("attachedPlayers"))
                }
                observations.put(JSONObject().put("direction",sign).put("frames",JSONArray(frames))
                    .put("captureStartMs",began).put("captureEndMs",captured).put("outgoingStillPresentAfterCapture",after))
                assertNotSame(old,player());assertEquals(1f,ui{a.playback!!.view.alpha})
            } finally { input.join(5000);ui{a.window.decorView.viewTreeObserver.removeOnDrawListener(observer)} }
        }
        File(ui{a.getExternalFilesDir(null)!!},"youtube026-shorts-transitions.json").writeText(observations.toString(2))
    }
    @Test fun rapidShortsRequestsAndReversalsCommitTheFinalClipWithoutAccumulatedMotion() {
        val clips=shorts();control("slow=1")
        ui {
            val original=(a.model.route as Route.Player).watch
            a.model.youtube.step(original,1);a.model.youtube.step(original,1)
            a.model.youtube.step(original,-1);a.model.youtube.step(original,1)
        }
        settled(clips[3].id)
        sendRemoteKey(KeyEvent.KEYCODE_DPAD_UP);settled(clips[2].id)
        assertTrue(ui{a.playback!!.view.hasWindowFocus()})
    }
    @Test fun failedNextShortKeepsTheCurrentDecoderAndNeverStartsATransition() {
        val clips=shorts();val old=player()!!;control("fail_playback=1")
        sendRemoteKey(KeyEvent.KEYCODE_DPAD_UP)
        await{ui{!a.model.youtube.resolving}}
        val state=JSONObject(runBlocking{Http().text("$base/youtube/state")})
        val resolved=state.getJSONArray("resolved")
        assertTrue("The intended previous clip was never attempted",(0 until resolved.length()).any{resolved.optString(it)==clips[0].id})
        assertEquals(clips[1].id,currentId());assertSame(old,player())
        assertNull(outgoing());assertEquals(0f,ui{a.playback!!.view.translationY})
        assertTrue(ui{old.isPlaying});assertNull(tagged("playback-buffering"))
    }
    @Test fun backgroundCancelsTheShortsTransitionAndResumesOneUntranslatedPlayer() {
        val clips=shorts();sendRemoteKey(KeyEvent.KEYCODE_DPAD_DOWN)
        await{outgoing()!=null}
        rule.scenario.moveToState(Lifecycle.State.CREATED)
        await{ui{a.player==null} && outgoing()==null}
        assertEquals(0f,ui{a.playback!!.view.translationY})
        rule.scenario.moveToState(Lifecycle.State.RESUMED);settled(clips[2].id)
    }
    @Test fun backWhileAShortIsResolvingCannotReopenThePlayerOrLeaveAnOutgoingSurface() {
        shorts();control("slow=1")
        sendRemoteKey(KeyEvent.KEYCODE_DPAD_UP)
        await{ui{a.model.youtube.resolving}}
        ui{a.playback!!.dismissControls();a.onBackPressedDispatcher.onBackPressed()}
        await{ui{a.model.route !is Route.Player && a.player==null}}
        SystemClock.sleep(1400)
        assertTrue(ui{a.model.route !is Route.Player});assertNull(player());assertNull(outgoing())
        assertFalse(ui{descendants(a.window.decorView).filterIsInstance<PlayerView>().any{it.player!=null}})
    }
    @Test fun changingSectionsWhileAShortResolvesKeepsTheExplicitDestination() {
        shorts();control("slow=1");sendRemoteKey(KeyEvent.KEYCODE_DPAD_UP)
        await{ui{a.model.youtube.resolving}}
        ui{a.model.top(Section.SEARCH)}
        await{ui{a.model.route==Route.Browse(Section.SEARCH) && a.player==null}}
        SystemClock.sleep(1400)
        assertEquals(Route.Browse(Section.SEARCH),ui{a.model.route})
        assertNull(player());assertNull(outgoing());assertFalse(ui{a.model.youtube.resolving})
    }
    @Test fun leavingBrowseBeforeTheInitialVideoResolvesKeepsTheExplicitDestination() {
        control("slow=1")
        ui {
            a.model.youtube.play(YouTubeVideo("V0000000001","Generated pending video"))
            a.model.top(Section.SEARCH)
        }
        SystemClock.sleep(1400)
        assertEquals(Route.Browse(Section.SEARCH),ui{a.model.route});assertNull(player())
        assertFalse(ui{a.model.youtube.resolving})
    }
}
