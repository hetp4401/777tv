@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package xyz.billybishop4.fleet.tv

import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import androidx.media3.common.Player
import androidx.media3.common.C
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*

class EngineLayoutTest : NativeTestBase() {
    private val provider = Provider("engine-fixture", "Local TV", "http://10.0.2.2:8878/stalker_portal/", mac = "00:1A:79:00:00:01")
    private fun control(q: String) = runBlocking { Http().text("http://10.0.2.2:8878/__control?$q") }
    private fun audit() = JSONObject(runBlocking { Http().text("http://10.0.2.2:8878/__state") })
    private fun info() = JSONObject(ui { a.playbackInfo })
    private fun previewHasPicture():Boolean {
        val rect=ui { (a.window.decorView.findTag("channel-preview")?:return@ui null).let { v ->
            val xy=IntArray(2);v.getLocationOnScreen(xy);android.graphics.Rect(xy[0],xy[1],xy[0]+v.width,xy[1]+v.height)
        } }?:return false
        val bitmap=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?:return false
        return try {
            var colored=0
            for(x in 1..3)for(y in 1..3){
                val pixel=bitmap.getPixel((rect.left+rect.width()*x/4).coerceIn(0,bitmap.width-1),(rect.top+rect.height()*y/4).coerceIn(0,bitmap.height-1))
                if(maxOf(android.graphics.Color.red(pixel),android.graphics.Color.green(pixel),android.graphics.Color.blue(pixel))>80)colored++
            }
            colored>=5
        } finally { bitmap.recycle() }
    }
    @Before fun setup() {
        control("reset=1")
        ui { a.model.showProfiles(); a.model.updatePreferences(Preferences()); a.model.server("http://10.0.2.2:8878"); a.model.saveProvider(provider); a.model.open(Route.Channels(provider)) }
        await { find("Local TV")!=null && ui{!a.model.busy} && tagged("channel:1")?.isShown==true }
    }
    @After fun stop() { ui { a.model.showProfiles() }; await { ui { a.activePlayer == null } } }
    private fun tune() {
        ui { a.window.decorView.findTag("channel:1")!!.performClick() }
        try { await(45000) { info().optBoolean("playing") && info().optInt("width") > 0 } }
        catch(e:AssertionError) { throw AssertionError(ui { "Playback: ${a.playbackInfo}; ready=${a.activePlayer?.playWhenReady}; state=${a.activePlayer?.playbackState}; error=${a.activePlayer?.playerError?.errorCodeName}; route=${a.model.route.javaClass.simpleName}" },e) }
    }
    @Test fun liveHasNoTimelineAndPreviewFullscreenKeepsPlayer() {
        tune()
        val player = ui { a.activePlayer }
        assertNotNull(player); assertNull(tagged("vod-controls")); assertNull(tagged("exo_progress"))
        assertFalse(ui { player!!.isCommandAvailable(Player.COMMAND_SEEK_BACK) })
        assertFalse(ui { player!!.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) })
        assertTrue(info().getBoolean("audio")); assertEquals("ExoPlayer", info().getString("engine"))
        val links = audit().getInt("portal_links")
        ui { a.window.decorView.findTag("channel:1")!!.performClick() }
        await { tagged("channels")?.isShown==false }
        device.pressKeyCode(KeyEvent.KEYCODE_MEDIA_REWIND); device.pressKeyCode(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)
        device.pressBack(); await { tagged("channels")?.isShown == true }
        assertSame(player, ui { a.activePlayer }); assertEquals(links, audit().getInt("portal_links"))
        try { await(5000) { ui {
            fun bounds(v:View):android.graphics.Rect { val xy=IntArray(2);v.getLocationOnScreen(xy);return android.graphics.Rect(xy[0],xy[1],xy[0]+v.width,xy[1]+v.height) }
            bounds(a.window.decorView.findTag("channel-preview")!!)==bounds(a.window.decorView.findTag("native-player")!!)
        } } } catch(e:AssertionError) {
            throw AssertionError(ui { listOf("channel-preview","preview-video","native-player").joinToString { tag ->
                val v=a.window.decorView.findTag(tag)!!;val xy=IntArray(2);v.getLocationOnScreen(xy)
                val parent=v.parent as? View;val pxy=IntArray(2);parent?.getLocationOnScreen(pxy)
                "$tag: ${xy.toList()} ${v.width}x${v.height}; parent=${pxy.toList()} ${parent?.width}x${parent?.height}; params=${(parent?.layoutParams as? FrameLayout.LayoutParams)?.let{ "${it.leftMargin},${it.topMargin},${it.width},${it.height}" }}"
            } },e)
        }
        await(15000) { previewHasPicture() }
        android.os.SystemClock.sleep(700);assertTrue("Preview lost its rendered picture",previewHasPicture())
        ui {
            val rows=org.json.JSONArray()
            descendants(a.window.decorView).filter { it.tag in listOf("channel-preview","preview-video","native-player") || it is android.view.SurfaceView || (it is android.widget.TextView && it.text.toString()=="LIVE") }.forEach { v ->
                val xy=IntArray(2);v.getLocationOnScreen(xy);val parent=v.parent as? View;val lp=parent?.layoutParams as? FrameLayout.LayoutParams
                rows.put(JSONObject().put("tag",v.tag?.toString()).put("class",v.javaClass.simpleName).put("x",xy[0]).put("y",xy[1]).put("w",v.width).put("h",v.height)
                    .put("parentParams",lp?.let { "${it.leftMargin},${it.topMargin},${it.width},${it.height}" }))
            }
            java.io.File(a.getExternalFilesDir(null),"preview-bounds.json").writeText(rows.toString(2))
        }
        val cats = tagged("groups")!!; val channels = tagged("channels")!!; val preview = tagged("channel-preview")!!
        assertTrue(ui { val x = IntArray(2); val y = IntArray(2); val z = IntArray(2); cats.getLocationOnScreen(x); channels.getLocationOnScreen(y); preview.getLocationOnScreen(z); x[0] < y[0] && y[0] < z[0] })
        await { find("Fixture Morning News") != null }; screenshot("three-pane-exoplayer")
        click("Sports"); await { tagged("channel:2") != null }; ui { a.window.decorView.findTag("channel:2")!!.performClick() }
        await(45000) { info().optBoolean("playing") && audit().getInt("portal_links") > links }
        await { find("No schedule provided for this channel.") != null }
    }
    @Test fun exoLiveAlsoBlocksSeekingAndHasNoController() {
        tune(); assertEquals("ExoPlayer", info().getString("engine"))
        assertFalse(ui { (a.window.decorView.findTag("native-player") as PlayerView).useController })
        assertTrue("Live playback inflated an unused seek controller",ui {
            descendants(a.window.decorView.findTag("native-player")!!).none { it is androidx.media3.ui.PlayerControlView }
        })
        assertFalse(ui { a.activePlayer!!.isCommandAvailable(Player.COMMAND_SEEK_FORWARD) })
        ui { a.window.decorView.findTag("channel:1")!!.performClick() };await { tagged("channels")?.isShown==false }
        device.pressBack(); await { tagged("channels")?.isShown == true }; assertTrue(info().getBoolean("playing"))
    }
    @Test fun onDemandSupportsPauseSeekResumeAndFreshBackgroundPlayer() {
        val title = Title("fixture-vod", "Player test movie")
        val watch = Watch(title, Stream("Local media", "http://10.0.2.2:8878/hls/fixture/orig.m3u8"))
        ui { a.model.open(Route.Player(watch)) }
        await(45000) { info().optBoolean("playing") && info().optInt("width") > 0 }
        assertNotNull(tagged("vod-controls"))
        device.pressKeyCode(KeyEvent.KEYCODE_MEDIA_PAUSE); await { ui { a.activePlayer?.playWhenReady == false } }
        ui { a.activePlayer!!.seekTo(18000) }; device.pressKeyCode(KeyEvent.KEYCODE_MEDIA_PLAY)
        await { info().optLong("positionMs") > 17000 && info().optBoolean("playing") }
        val previous=ui { a.activePlayer }
        rule.scenario.moveToState(Lifecycle.State.CREATED); await { ui { a.activePlayer == null } }
        rule.scenario.moveToState(Lifecycle.State.RESUMED)
        await(45000) { info().optBoolean("playing") && info().optLong("positionMs") > 17000 }
        assertNotSame(previous,ui { a.activePlayer })
        ui { a.model.back() }; await { ui { a.activePlayer == null } }
        assertTrue(ui { a.model.resume(title, null) > 17000 })
    }
    private fun videoPixels():List<Int> {
        val rect=ui { val v=a.window.decorView.findTag("native-player")!!;val xy=IntArray(2);v.getLocationOnScreen(xy)
            android.graphics.Rect(xy[0],xy[1],xy[0]+v.width,xy[1]+v.height) }
        val image=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()!!
        return try { (1..7).flatMap { x -> (2..3).map { y ->
            image.getPixel((rect.left+rect.width()*x/8).coerceIn(0,image.width-1),
                (rect.top+rect.height()*y/8).coerceIn(0,image.height-1))
        } } } finally { image.recycle() }
    }
    private fun mean(pixels:List<Int>)=pixels.map{(android.graphics.Color.red(it)+android.graphics.Color.green(it)+android.graphics.Color.blue(it))/3.0}.average()
    @Test fun pictureEffectsRenderRealPausedPixelsAndResetReturnsToDirectSurface() {
        val watch=Watch(Title("picture-pixels","Picture fixture"),Stream("SDR fixture","http://10.0.2.2:8878/hls/fixture/orig.m3u8"))
        ui { a.model.open(Route.Player(watch)) }
        await(45000) { info().optBoolean("playing") && info().optInt("width")>0 }
        assertFalse("Neutral playback enabled a processing pipeline",ui { a.playback!!.snapshot!!.effectsActive })
        ui { a.activePlayer!!.pause();a.playback!!.dismissControls() }
        await { ui { a.activePlayer?.playWhenReady==false } }
        val at=info().getLong("positionMs");val color=videoPixels()
        assertTrue("Fixture had no chromatic pixels",color.any{maxOf(android.graphics.Color.red(it),android.graphics.Color.green(it),android.graphics.Color.blue(it))-minOf(android.graphics.Color.red(it),android.graphics.Color.green(it),android.graphics.Color.blue(it))>25})
        val result=java.util.concurrent.atomic.AtomicReference<String?>("pending")
        ui { a.playback!!.setPicture(VideoPicture(saturation=0)){ result.set(it?:"ok") } }
        await(45000){result.get()!="pending"};assertEquals("ok",result.get())
        assertFalse(ui { a.activePlayer!!.playWhenReady })
        assertTrue(kotlin.math.abs(info().getLong("positionMs")-at)<1000)
        fun stableGrayFrame()=ui { a.activePlayer?.let{!it.playWhenReady && it.playbackState==Player.STATE_READY}==true &&
            a.window.decorView.findTag("playback-buffering")?.visibility!=View.VISIBLE } && videoPixels().let { pixels ->
                mean(pixels)>30 && pixels.all { maxOf(android.graphics.Color.red(it),android.graphics.Color.green(it),android.graphics.Color.blue(it))-
                    minOf(android.graphics.Color.red(it),android.graphics.Color.green(it),android.graphics.Color.blue(it))<12 }
            }
        await(30000){stableGrayFrame()}
        val firstGray=videoPixels();android.os.SystemClock.sleep(500)
        assertTrue("Paused grayscale never reached a stable nonblack video frame",stableGrayFrame())
        val gray=videoPixels();assertTrue(kotlin.math.abs(mean(gray)-mean(firstGray))<3)
        val effectPlayer=ui { a.activePlayer }
        screenshot("picture-grayscale-paused")
        ui { a.playback!!.setPicture(VideoPicture(brightness=20,saturation=0)){result.set(it?:"ok")} }
        val probes=org.json.JSONArray()
        var brightStableSince=0L
        try {
            await(30000) {
                val ready=ui { a.activePlayer?.let{!it.playWhenReady && it.playbackState==Player.STATE_READY}==true &&
                    a.window.decorView.findTag("playback-buffering")?.visibility!=View.VISIBLE }
                val pixels=videoPixels();val value=mean(pixels);val now=android.os.SystemClock.uptimeMillis()
                val correct=ready && value>mean(gray)+15 && pixels.all{maxOf(android.graphics.Color.red(it),android.graphics.Color.green(it),android.graphics.Color.blue(it))-
                    minOf(android.graphics.Color.red(it),android.graphics.Color.green(it),android.graphics.Color.blue(it))<12}
                probes.put(JSONObject().put("atMs",now).put("readyAndPaused",ready).put("mean",value).put("grayMean",mean(gray)).put("correct",correct))
                if(correct) { if(brightStableSince==0L)brightStableSince=now } else brightStableSince=0L
                correct && now-brightStableSince>=750
            }
        } finally {
            java.io.File(ui{a.getExternalFilesDir(null)!!},"paused-picture-pixel-probes.json").writeText(probes.toString(2))
        }
        assertSame("A slider change recreated the active renderer",effectPlayer,ui { a.activePlayer })
        assertTrue(kotlin.math.abs(info().getLong("positionMs")-at)<1000)
        screenshot("picture-brightness-paused")
        // A delayed paused refresh must not undo a newer remote/media seek.
        ui {
            a.playback!!.setPicture(VideoPicture(brightness=25,saturation=0)){}
            a.activePlayer!!.seekTo(18000)
        }
        android.os.SystemClock.sleep(400)
        await(15000){kotlin.math.abs(info().optLong("positionMs")-18000)<500}
        result.set("pending")
        ui { a.playback!!.setPicture(VideoPicture()){result.set(it?:"ok")} }
        await(45000){result.get()!="pending"};assertEquals("ok",result.get())
        assertFalse(ui { a.playback!!.snapshot!!.effectsActive });assertFalse(ui{a.activePlayer!!.playWhenReady})
        assertNotSame(effectPlayer,ui { a.activePlayer })
        await(15000){videoPixels().any{maxOf(android.graphics.Color.red(it),android.graphics.Color.green(it),android.graphics.Color.blue(it))-minOf(android.graphics.Color.red(it),android.graphics.Color.green(it),android.graphics.Color.blue(it))>25}}
        ui { a.activePlayer!!.play() };await{info().optBoolean("playing")}
    }
    @Test fun processorFailureReturnsOnceToDirectPlaybackAndReportsActualNeutralState() {
        val watch=Watch(Title("picture-failure","Picture failure fixture"),Stream("SDR fixture","http://10.0.2.2:8878/hls/fixture/orig.m3u8"))
        ui { a.model.open(Route.Player(watch)) }
        await(45000){info().optBoolean("playing") && info().optInt("width")>0}
        ui { a.activePlayer!!.pause() };await{ui{a.activePlayer?.playWhenReady==false}}
        val at=info().getLong("positionMs")
        val result=java.util.concurrent.atomic.AtomicReference<String?>("pending")
        ui { a.playback!!.setPicture(VideoPicture(contrast=10)){result.set(it?:"ok")} }
        await(45000){result.get()!="pending"};assertEquals("ok",result.get())
        val processed=ui{a.playback!!.player!!}
        result.set("pending")
        ui {
            val format=processed.videoFormat
            processed.createMessage { _,_ ->
                throw androidx.media3.exoplayer.ExoPlaybackException.createForRenderer(
                    androidx.media3.common.VideoFrameProcessingException("Generated processor failure"),
                    "Generated video processor",0,format,C.FORMAT_HANDLED,false,
                    androidx.media3.common.PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED)
            }.send()
        }
        await(45000){result.get()!="pending" && ui{a.playback?.snapshot?.effectsActive==false && a.activePlayer!=null}}
        assertTrue(result.get().orEmpty().contains("aren't supported"))
        assertEquals(VideoPicture(),ui{a.playback!!.picture})
        assertNotSame(processed,ui{a.playback!!.player})
        assertFalse(ui{a.activePlayer!!.playWhenReady})
        await(45000){info().optInt("width")>0}
        assertTrue(kotlin.math.abs(info().getLong("positionMs")-at)<1000)
        ui{a.activePlayer!!.play()};await{info().optBoolean("playing")}
    }
    @Test fun rapidNavigationDoesNotTuneEveryFocusAndSixThousandRowsStayBounded() {
        control("large_channels=1"); ui { a.model.retry() }
        await { ui { a.model.channels.size == 6000 } }
        val list = tagged("channels") as RecyclerView
        await { ui { list.adapter!!.itemCount == 6000 && list.childCount > 0 } }
        val before = audit().getInt("portal_links")
        ui { list.getChildAt(0).requestFocus() }
        repeat(30) {
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            android.os.SystemClock.sleep(35)
        }
        assertTrue(ui { list.childCount < 20 })
        android.os.SystemClock.sleep(900)
        assertEquals("Browsing opened a stream without OK",before,audit().getInt("portal_links"))
        assertNull(ui { a.activePlayer })
        device.pressDPadCenter();await(45000) { info().optBoolean("playing") }
        assertEquals(before+1,audit().getInt("portal_links"))
        assertTrue(tagged("channels")?.isShown==true)
        screenshot("large-channel-list")
    }
    @Test fun brokenLiveStopsAtRetryLimitThenManualRetryRecovers() {
        control("broken_live=1"); ui { a.model.updatePreferences(a.model.preferences.copy(liveRetries=1)) }
        val before=audit().getInt("portal_links")
        ui { a.window.decorView.findTag("channel:1")!!.apply { performClick();performClick() } }
        await(45000) { find("This channel stopped responding. Retry or choose another channel.")!=null }
        assertEquals(2,audit().getInt("portal_links")-before);assertNull(ui { a.activePlayer })
        control("broken_live=0");remote("Retry")
        await(45000) { info().optBoolean("playing") }
    }
    @Test fun directSourceFailureReleasesPlayerAndCanRetry() {
        control("broken_vod=1")
        val w=Watch(Title("failure-fixture","Failure fixture"),Stream("Fixture stream","http://10.0.2.2:8878/hls/fixture/orig.m3u8"))
        ui { a.model.open(Route.Player(w)) }
        await(45000) { find("This stream couldn't play. Try again or choose another stream.")!=null }
        assertNull(ui { a.activePlayer });await { ui { find("Retry")?.hasFocus()==true } }
        control("broken_vod=0");remote("Retry");await(45000) { info().optBoolean("playing") }
    }
    @Test fun transientAudioFocusLossResumesButManualPauseIsPreserved() {
        org.junit.Assume.assumeTrue(android.os.Build.VERSION.SDK_INT>=26)
        tune()
        val audio=ui { a.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager }
        val request=android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY).build())
            .setOnAudioFocusChangeListener{}.build()
        try {
            ui { audio.requestAudioFocus(request) };await { ui { a.activePlayer?.isPlaying==false && a.activePlayer?.playbackSuppressionReason==Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS } }
            ui { audio.abandonAudioFocusRequest(request) };await { ui { a.activePlayer?.isPlaying==true && a.activePlayer?.playWhenReady==true } }
            ui { a.activePlayer!!.pause();a.activePlayer!!.playWhenReady=false }
            assertTrue("The live-facing player must ignore manual pause",ui { a.activePlayer!!.playWhenReady })
            assertFalse(ui { a.activePlayer!!.isCommandAvailable(Player.COMMAND_PLAY_PAUSE) })
            // Ordinary playback still owns a real manual pause which audio focus cannot undo.
            val movie=Watch(Title("audio-focus-vod","Audio focus fixture"),Stream("Generated","http://10.0.2.2:8878/hls/fixture/orig.m3u8"))
            val live=player()
            ui { a.model.open(Route.Player(movie)) }
            await(45000){ui{a.playback?.watch===movie && a.activePlayer!==live} && info().optBoolean("playing") && info().optInt("width")>0}
            ui { a.activePlayer!!.pause() };assertFalse(ui{a.activePlayer!!.playWhenReady})
            ui { audio.requestAudioFocus(request) }
            ui { audio.abandonAudioFocusRequest(request) };android.os.SystemClock.sleep(350)
            assertFalse(ui { a.activePlayer!!.playWhenReady })
            ui { a.activePlayer!!.play() };await { info().optBoolean("playing") }
        } finally { ui { audio.abandonAudioFocusRequest(request) } }
    }
}
