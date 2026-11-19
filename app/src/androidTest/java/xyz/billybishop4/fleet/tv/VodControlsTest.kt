@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package xyz.billybishop4.fleet.tv

import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.view.KeyCharacterMap
import android.widget.SeekBar
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.media3.common.Player
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern

/** Generated loopback video only. Every user key passes through the real TV window. */
class VodControlsTest : NativeTestBase() {
    private var observedPlayer: Player? = null
    private val seekEvents = AtomicInteger()
    private val seekListener = object : Player.Listener {
        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK) seekEvents.incrementAndGet()
        }
    }
    private fun observeSeeks() = ui {
        observedPlayer?.removeListener(seekListener); seekEvents.set(0)
        observedPlayer = a.activePlayer!!.also { it.addListener(seekListener) }
    }
    private fun stopObservingSeeks() = ui { observedPlayer?.removeListener(seekListener); observedPlayer = null }
    private fun seekCount() = seekEvents.get()
    // UiDevice's accessibility-idle wait can outlast an active HUD's auto-hide timer.
    private fun key(code: Int) = InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(code)
    private fun hold(code: Int, repeats: Int) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val down = SystemClock.uptimeMillis()
        instrumentation.sendKeySync(KeyEvent(down, down, KeyEvent.ACTION_DOWN, code, 0))
        repeat(repeats) { index ->
            val time = down + index + 1
            val delay = time - SystemClock.uptimeMillis()
            if (delay > 0) SystemClock.sleep(delay)
            instrumentation.sendKeySync(KeyEvent(down, time, KeyEvent.ACTION_DOWN, code, index + 1))
        }
        instrumentation.sendKeySync(KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0))
    }
    private fun info() = JSONObject(ui { a.playbackInfo })
    private fun pictureDialogSelector() = By.desc("Picture controls").clazz("android.widget.LinearLayout")
    private fun start(url: String = "http://10.0.2.2:8878/hls/fixture/orig.m3u8", title: String = "Fleet player fixture",headers:Map<String,String> = emptyMap(), preferences:Preferences = Preferences(),youtube:Boolean=false) {
        stopObservingSeeks()
        ui { a.model.showProfiles() }; await { ui { a.activePlayer == null } }
        ui {
            a.model.updatePreferences(preferences); a.model.server("http://10.0.2.2:8878"); a.model.clearHistory()
            if(youtube)a.model.youtubeFixture("http://10.0.2.2:8878")
            a.model.open(Route.Player(Watch(Title(if(youtube)"V0000000001" else "vod-ui-fixture",title,if(youtube)"youtube" else "movie"),
                Stream("Fixture stream",url),headers=headers,resolvedAtMs=System.currentTimeMillis())))
        }
        await(45000) { info().optBoolean("playing") && info().optInt("width") > 0 }
        await { ui {
            val player = a.activePlayer
            player != null && player.duration > 0 && player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
        } }
        if (tagged("vod-controls")?.isShown != true) key(KeyEvent.KEYCODE_DPAD_CENTER)
        await { tagged("vod-controls")?.isShown == true && tagged("vod-progress")?.isEnabled == true && ui { a.window.decorView.findTag("vod-play-pause")?.hasFocus() == true } }
    }
    @After fun stop() { stopObservingSeeks(); ui { a.model.showProfiles() }; await { ui { a.activePlayer == null } } }
    private fun choose(label: String) {
        val selector = By.clazz("android.widget.CheckedTextView").text(label)
        assertTrue("Missing native choice: $label", device.wait(Until.hasObject(selector), 10000) == true)
        device.findObject(selector).click()
    }
    private fun chooseMatching(expression: String): String {
        val selector = By.clazz("android.widget.CheckedTextView").text(Pattern.compile(expression, Pattern.CASE_INSENSITIVE))
        assertTrue("Missing track choice matching $expression", device.wait(Until.hasObject(selector), 10000) == true)
        val option = device.findObject(selector)
        val label = option.text.orEmpty(); option.click(); return label
    }
    private fun tracksMenu(item: String) {
        ui { a.window.decorView.findTag("vod-tracks")!!.requestFocus() }
        key(KeyEvent.KEYCODE_DPAD_CENTER); click(item)
    }
    private fun focusedTimeline() {
        try { await { tagged("vod-progress")?.hasFocus() == true } }
        catch (failure: AssertionError) {
            throw AssertionError(ui {
                val target = a.window.decorView.findTag("vod-progress")
                "Timeline focus: engine=${info().optString("engine")}, duration=${a.activePlayer?.duration}, enabled=${target?.isEnabled}, focusable=${target?.isFocusable}, touchFocusable=${target?.isFocusableInTouchMode}, touchMode=${target?.isInTouchMode}, focus=${a.currentFocus?.tag}"
            }, failure)
        }
    }
    private data class VideoRoi(val bright: Double, val colored: Double, val colors: Int)
    private fun videoRoi(): VideoRoi? {
        val rect = ui {
            val player = a.window.decorView.findTag("native-player") ?: return@ui null
            val location = IntArray(2); player.getLocationOnScreen(location)
            Rect(location[0], location[1], location[0] + player.width, location[1] + player.height).takeIf { it.width() > 0 && it.height() > 0 }
        } ?: return null
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return null
        return try {
            var bright = 0; var colored = 0; var samples = 0
            val colors = HashSet<Int>()
            // A video-only band above the center spinner, with HUD and dialog hidden.
            for (row in 0..4) for (column in 0..39) {
                val x = (rect.left + rect.width() * (.12 + column * .76 / 39)).toInt().coerceIn(0, bitmap.width - 1)
                val y = (rect.top + rect.height() * (.25 + row * .0125)).toInt().coerceIn(0, bitmap.height - 1)
                val color = bitmap.getPixel(x, y)
                val high = maxOf(Color.red(color), Color.green(color), Color.blue(color))
                val low = minOf(Color.red(color), Color.green(color), Color.blue(color))
                colors += (Color.red(color) / 32 shl 6) or (Color.green(color) / 32 shl 3) or (Color.blue(color) / 32)
                if (high > 80) bright++
                if (high - low > 35 && high > 80) colored++
                samples++
            }
            VideoRoi(bright.toDouble() / samples, colored.toDouble() / samples, colors.size)
        } finally { bitmap.recycle() }
    }
    private fun assertStableVisiblePausedFrame(position: Long) {
        fun ready() = ui {
            val player = a.activePlayer
            player != null && player.playbackState == Player.STATE_READY && !player.playWhenReady &&
                kotlin.math.abs(player.currentPosition - position) < 1500 && a.model.route is Route.Player &&
                a.window.decorView.findTag("vod-controls")?.isShown != true && a.window.decorView.findTag("playback-buffering")?.isShown != true
        }
        fun picture(frame: VideoRoi?) = frame != null && frame.bright > .5 && frame.colored > .3 && frame.colors >= 4
        var first: VideoRoi? = null
        var sampledAt = 0L
        await(30000) {
            val now = SystemClock.elapsedRealtime()
            if (!ready() || now - sampledAt < 250) false
            else { sampledAt = now; first = videoRoi(); picture(first) }
        }
        SystemClock.sleep(500)
        assertTrue("Paused picture returned to buffering or showed an overlay", ready())
        val second = videoRoi()
        assertTrue("Paused renderer lost its visible video frame", picture(second))
        val firstFrame = first!!; val secondFrame = second!!
        assertTrue("Paused video changed between stable captures", kotlin.math.abs(firstFrame.bright - secondFrame.bright) < .1 && kotlin.math.abs(firstFrame.colored - secondFrame.colored) < .1)
        assertTrue(ready())
        val dir = ui { a.getExternalFilesDir(null)!! }
        File(dir, "vod-rapid-frame.json").writeText(JSONObject().put("positionMs", info().optLong("positionMs"))
            .put("firstBright", firstFrame.bright).put("firstColored", firstFrame.colored).put("firstColors", firstFrame.colors)
            .put("secondBright", secondFrame.bright).put("secondColored", secondFrame.colored).put("secondColors", secondFrame.colors).toString(2))
    }
    @Test fun okRevealsWithoutPausingThenActivatesOnceAndBackDismissesBeforeLeaving() {
        start()
        key(KeyEvent.KEYCODE_BACK); await { tagged("vod-controls")?.isShown == false }
        assertTrue(ui { a.activePlayer!!.playWhenReady })
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        await { tagged("vod-controls")?.isShown == true && tagged("vod-play-pause")?.hasFocus() == true }
        assertTrue("Revealing controls paused ExoPlayer", ui { a.activePlayer!!.playWhenReady })
        key(KeyEvent.KEYCODE_DPAD_CENTER); await { ui { !a.activePlayer!!.playWhenReady } }
        SystemClock.sleep(5200)
        assertTrue("Paused controls auto-hid in ExoPlayer", tagged("vod-controls")?.isShown == true)
        screenshot("ios-vod-exoplayer")
        key(KeyEvent.KEYCODE_BACK); await { tagged("vod-controls")?.isShown == false }
        assertTrue(ui { a.model.route is Route.Player })
        key(KeyEvent.KEYCODE_BACK); await { ui { a.model.route !is Route.Player && a.activePlayer == null } }
    }
    @Test fun timelineAndSkipButtonsSeekImmediatelyThenPlayingControlsAutoHide() {
        start()
        ui { a.activePlayer!!.pause() }; await { !info().optBoolean("playing") && ui { !a.activePlayer!!.playWhenReady } }
        observeSeeks()
        key(KeyEvent.KEYCODE_DPAD_UP); focusedTimeline()
        val initial = info().optLong("positionMs")
        fun at(offset: Long) = kotlin.math.abs(info().optLong("positionMs") - (initial + offset)) < 1200
        fun sought(offset: Long, events: Int) = await(15000) { at(offset) && seekCount() == events }
        val bar = tagged("vod-progress") as SeekBar
        val originalProgress = ui { bar.progress }
        key(KeyEvent.KEYCODE_DPAD_RIGHT); sought(10000, 1)
        await { ui { bar.progress > originalProgress } }
        assertTrue("Timeline direction required an OK confirmation in ExoPlayer", tagged("vod-scrub-hint")?.isShown != true)
        key(KeyEvent.KEYCODE_DPAD_RIGHT); sought(20000, 2)
        key(KeyEvent.KEYCODE_DPAD_LEFT); sought(10000, 3)
        assertFalse(ui { a.activePlayer!!.playWhenReady })

        ui { a.window.decorView.findTag("vod-options")!!.requestFocus() }
        SystemClock.sleep(300)
        assertEquals("Changing focus issued another seek in ExoPlayer", 3, seekCount())
        assertTrue(at(10000))
        ui { bar.requestFocus() }
        key(KeyEvent.KEYCODE_DPAD_DOWN); await { tagged("vod-play-pause")?.hasFocus() == true }
        key(KeyEvent.KEYCODE_DPAD_RIGHT); await { tagged("vod-forward")?.hasFocus() == true }
        key(KeyEvent.KEYCODE_DPAD_CENTER); sought(20000, 4)
        key(KeyEvent.KEYCODE_DPAD_LEFT); key(KeyEvent.KEYCODE_DPAD_LEFT)
        await { tagged("vod-rewind")?.hasFocus() == true }
        key(KeyEvent.KEYCODE_DPAD_CENTER); sought(10000, 5)
        assertFalse("Skipping resumed paused playback in ExoPlayer", ui { a.activePlayer!!.playWhenReady })

        key(KeyEvent.KEYCODE_DPAD_UP); focusedTimeline()
        hold(KeyEvent.KEYCODE_DPAD_RIGHT, repeats = 2)
        await(15000) { at(40000) && seekCount() in 7..8 }
        val heldSeeks = seekCount()
        SystemClock.sleep(400)
        assertEquals("Held seek continued after key-up in ExoPlayer", heldSeeks, seekCount())
        assertTrue(at(40000))
        assertEquals("Player strip height in ExoPlayer", ui { a.dp(48) }, ui { tagged("vod-panel")!!.height })
        screenshot("ios-vod-seek-exoplayer")

        val playingAt = info().optLong("positionMs")
        key(KeyEvent.KEYCODE_MEDIA_PLAY); focusedTimeline()
        await(7500) { tagged("vod-controls")?.isShown == false }
        await(15000) { info().optBoolean("playing") && info().optLong("positionMs") >= playingAt + 3000 }
        assertTrue("Auto-hide left playback in ExoPlayer", ui { a.model.route is Route.Player })
        assertTrue("Auto-hide interrupted playback in ExoPlayer", info().optBoolean("playing") && info().optLong("positionMs") >= playingAt + 3000)
    }
    @Test fun heldTimelineAcceleratesByDurationAndResetsOnReleaseReversalAndFocusLoss() {
        start(preferences=Preferences(seekSeconds=5)); ui { a.activePlayer!!.pause(); a.activePlayer!!.seekTo(0) }
        fun at(position: Long) = kotlin.math.abs(info().optLong("positionMs") - position) < 300
        fun seeked(position: Long) = await(15000) { at(position) && ui { a.activePlayer?.playWhenReady == false } }
        seeked(0)
        key(KeyEvent.KEYCODE_DPAD_UP); focusedTimeline()
        key(KeyEvent.KEYCODE_DPAD_RIGHT); seeked(5000)
        key(KeyEvent.KEYCODE_DPAD_LEFT); seeked(0)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        fun event(code: Int, action: Int, down: Long, time: Long, repeats: Int) {
            val delay = time - SystemClock.uptimeMillis()
            if (delay > 0) SystemClock.sleep(delay)
            instrumentation.sendKeySync(KeyEvent(down, time, action, code, repeats))
        }
        val down = SystemClock.uptimeMillis()
        event(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.ACTION_DOWN, down, down, 0)
        event(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.ACTION_DOWN, down, down + 50, 1); seeked(10000)
        event(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.ACTION_DOWN, down, down + 600, 2); seeked(50000)
        // Keep one held key but make room in the60s fixture to observe each later tier.
        ui { a.activePlayer!!.seekTo(0) }; seeked(0)
        val duration = ui { a.activePlayer!!.duration }
        event(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.ACTION_DOWN, down, down + 1400, 3); seeked(duration)
        ui { a.activePlayer!!.seekTo(0) }; seeked(0)
        event(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.ACTION_DOWN, down, down + 2700, 4)
        seeked(duration)
        await { ui { (tagged("vod-progress") as SeekBar).let { it.progress >= it.max - 100 } } }

        event(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.ACTION_DOWN, down, SystemClock.uptimeMillis(), 5); seeked(duration - 5000)
        event(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.ACTION_UP, down, SystemClock.uptimeMillis(), 0)
        event(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.ACTION_UP, down, SystemClock.uptimeMillis(), 0)
        key(KeyEvent.KEYCODE_DPAD_LEFT); seeked(duration - 10000)
        key(KeyEvent.KEYCODE_DPAD_RIGHT); seeked(duration - 5000)

        val secondDown = SystemClock.uptimeMillis()
        event(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.ACTION_DOWN, secondDown, secondDown, 0); seeked(duration - 10000)
        event(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.ACTION_DOWN, secondDown, secondDown + 600, 1); seeked(duration - 50000)
        ui { a.window.decorView.findTag("vod-options")!!.requestFocus() }
        ui { a.window.decorView.findTag("vod-progress")!!.requestFocus() }
        event(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.ACTION_DOWN, secondDown, SystemClock.uptimeMillis(), 2); seeked(duration - 55000)
        event(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.ACTION_UP, secondDown, SystemClock.uptimeMillis(), 0)
        key(KeyEvent.KEYCODE_DPAD_DOWN); key(KeyEvent.KEYCODE_DPAD_RIGHT)
        await { tagged("vod-forward")?.hasFocus() == true }
        key(KeyEvent.KEYCODE_DPAD_CENTER); seeked(duration - 50000)
        key(KeyEvent.KEYCODE_DPAD_UP); focusedTimeline()
        val finalDown=SystemClock.uptimeMillis()
        event(KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.ACTION_DOWN,finalDown,finalDown,0)
        event(KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.ACTION_DOWN,finalDown,finalDown+3300,1)
        event(KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.ACTION_UP,finalDown,SystemClock.uptimeMillis(),0);seeked(0)
        await { ui { (tagged("vod-progress") as SeekBar).progress <= 100 } }
        assertFalse(ui { a.activePlayer!!.playWhenReady })
    }
    @Test fun hiddenAndVideoSurfaceDirectionsSeekImmediatelyWithoutPausingOrAnExtraPress() {
        for(youtube in listOf(false,true)) {
            start(youtube=youtube);ui{a.activePlayer!!.seekTo(20000)}
            await{ui{a.activePlayer!!.playWhenReady && kotlin.math.abs(a.activePlayer!!.currentPosition-20000)<1200}}
            key(KeyEvent.KEYCODE_BACK);await{tagged("vod-controls")?.isShown==false && tagged("native-player")?.hasFocus()==true}
            observeSeeks();key(KeyEvent.KEYCODE_DPAD_RIGHT)
            focusedTimeline();await{seekCount()==1 && kotlin.math.abs(info().optLong("positionMs")-30000)<1200}
            SystemClock.sleep(250)
            assertTrue("A deferred Play focus stole the timeline",tagged("vod-progress")?.hasFocus()==true)
            assertTrue("A directional seek paused a playing video",ui{a.activePlayer!!.playWhenReady})
            // The visible HUD can also coexist with focus on the video surface.
            ui{tagged("native-player")!!.requestFocus()};key(KeyEvent.KEYCODE_DPAD_LEFT)
            focusedTimeline();await{seekCount()==2 && kotlin.math.abs(info().optLong("positionMs")-20000)<1200}
            assertTrue(ui{a.activePlayer!!.playWhenReady})
        }
    }
    @Test fun oneContinuousHoldFromHiddenControlsAcceleratesAndReleaseResetsIt() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        fun event(code:Int,action:Int,down:Long,time:Long,repeats:Int) {
            val wait=time-SystemClock.uptimeMillis();if(wait>0)SystemClock.sleep(wait)
            instrumentation.sendKeySync(KeyEvent(down,time,action,code,repeats))
        }
        for(youtube in listOf(false,true)) {
            start(youtube=youtube,preferences=Preferences(seekSeconds=5));ui{a.activePlayer!!.pause();a.activePlayer!!.seekTo(0)}
            await{ui{!a.activePlayer!!.playWhenReady && a.activePlayer!!.currentPosition<1200}}
            key(KeyEvent.KEYCODE_BACK);await{tagged("vod-controls")?.isShown==false && tagged("native-player")?.hasFocus()==true}
            observeSeeks();val down=SystemClock.uptimeMillis()
            event(KeyEvent.KEYCODE_DPAD_RIGHT,KeyEvent.ACTION_DOWN,down,down,0)
            focusedTimeline()
            if(youtube) {
                await{ui{(tagged("youtube-seek-preview-time") as? TextView)?.text=="0:05"}}
                assertEquals("YouTube previews must not seek the decoder before release",0,seekCount())
            } else await{seekCount()==1 && kotlin.math.abs(info().optLong("positionMs")-5000)<300}
            event(KeyEvent.KEYCODE_DPAD_RIGHT,KeyEvent.ACTION_DOWN,down,down+600,1)
            if(youtube) {
                await{ui{(tagged("youtube-seek-preview-time") as? TextView)?.text=="0:45"}}
                assertEquals(0,seekCount())
            } else await{kotlin.math.abs(info().optLong("positionMs")-45000)<300}
            event(KeyEvent.KEYCODE_DPAD_RIGHT,KeyEvent.ACTION_DOWN,down,down+800,2)
            event(KeyEvent.KEYCODE_DPAD_RIGHT,KeyEvent.ACTION_UP,down,SystemClock.uptimeMillis(),0)
            val duration=ui{a.activePlayer!!.duration}
            await{seekCount()==(if(youtube)1 else 3) && kotlin.math.abs(info().optLong("positionMs")-duration)<300}
            val released=seekCount();SystemClock.sleep(250);assertEquals("Held seeking continued after UP",released,seekCount())
            key(KeyEvent.KEYCODE_DPAD_LEFT)
            await{seekCount()==(if(youtube)2 else 4) && kotlin.math.abs(info().optLong("positionMs")-(duration-5000))<300}
            assertFalse("Held directional seeking resumed paused playback",ui{a.activePlayer!!.playWhenReady})
            assertTrue(tagged("vod-progress")?.hasFocus()==true)
        }
    }
    @Test fun orphanUpDirectionRevealsOrdinaryControlsWithoutPausingAndNormalUpKeepsItsNavigation() {
        for(youtube in listOf(false,true)) {
            start(youtube=youtube);val original=ui{a.activePlayer!!}
            key(KeyEvent.KEYCODE_BACK);await{tagged("vod-controls")?.isShown==false}
            observeSeeks();val at=SystemClock.uptimeMillis()
            val release=KeyEvent(at,at+1,KeyEvent.ACTION_UP,KeyEvent.KEYCODE_DPAD_UP,0)
            assertTrue(ui{a.playback!!.dispatchKey(release)})
            await{tagged("vod-controls")?.isShown==true && tagged("vod-play-pause")?.hasFocus()==true}
            ui{a.playback!!.dispatchKey(release)}
            assertTrue(ui{original.playWhenReady});assertEquals(0,seekCount())
            key(KeyEvent.KEYCODE_BACK);await{tagged("vod-controls")?.isShown==false}
            val cancelled=KeyEvent(at+10,at+11,KeyEvent.ACTION_UP,KeyEvent.KEYCODE_DPAD_UP,0,0,KeyCharacterMap.VIRTUAL_KEYBOARD,0,KeyEvent.FLAG_CANCELED)
            assertTrue(ui{a.playback!!.dispatchKey(cancelled)});assertFalse(tagged("vod-controls")!!.isShown)
            ui{a.playback!!.dispatchKey(KeyEvent(at+20,at+21,KeyEvent.ACTION_UP,KeyEvent.KEYCODE_DPAD_UP,0))}
            await{tagged("vod-play-pause")?.hasFocus()==true}
            ui{tagged("vod-play-pause")!!.requestFocusFromTouch()};sendRemoteKey(KeyEvent.KEYCODE_DPAD_UP)
            focusedTimeline();SystemClock.sleep(100)
            assertTrue("A matched Up pair stole focus back from the timeline",tagged("vod-progress")!!.hasFocus())
            assertSame(original,ui{a.activePlayer});assertTrue(ui{original.playWhenReady});assertEquals(0,seekCount())
        }
    }
    @Test fun directionsAndPictureButtonOpenSlidersDirectlyAndRetainPlaybackSettings() {
        start(preferences = Preferences(speed = 1.25f, videoFit = "Zoom"))
        ui { a.activePlayer!!.pause() }
        key(KeyEvent.KEYCODE_DPAD_UP); focusedTimeline()
        key(KeyEvent.KEYCODE_DPAD_DOWN); await { tagged("vod-play-pause")?.hasFocus() == true }
        key(KeyEvent.KEYCODE_DPAD_LEFT); await { tagged("vod-rewind")?.hasFocus() == true }
        key(KeyEvent.KEYCODE_DPAD_RIGHT); await { tagged("vod-play-pause")?.hasFocus() == true }
        key(KeyEvent.KEYCODE_DPAD_RIGHT); await { tagged("vod-forward")?.hasFocus() == true }
        key(KeyEvent.KEYCODE_DPAD_RIGHT); await { tagged("vod-tracks")?.hasFocus() == true }
        key(KeyEvent.KEYCODE_DPAD_CENTER); click("Subtitles")
        choose("Off"); await { ui { a.hasWindowFocus() } }
        await { tagged("vod-tracks")?.hasFocus() == true }
        val before = ui { a.activePlayer!!.currentPosition }
        val player = ui { a.activePlayer }
        key(KeyEvent.KEYCODE_DPAD_RIGHT); await { tagged("vod-options")?.hasFocus() == true }
        assertEquals("Picture controls", ui { tagged("vod-options")?.contentDescription?.toString() })
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        assertTrue(device.wait(Until.hasObject(pictureDialogSelector()), 10000) == true)
        assertTrue(device.wait(Until.hasObject(By.desc("Brightness")), 10000) == true)
        assertNull(device.findObject(By.text("Playback speed"))); assertNull(device.findObject(By.text("Video fit")))
        screenshot("ios-vod-settings-exoplayer")
        key(KeyEvent.KEYCODE_BACK)
        await { device.findObject(pictureDialogSelector()) == null && ui { a.hasWindowFocus() } }
        await { tagged("vod-options")?.hasFocus() == true }
        assertSame(player, ui { a.activePlayer })
        assertFalse(ui { a.activePlayer!!.playWhenReady })
        assertTrue(kotlin.math.abs(ui { a.activePlayer!!.currentPosition } - before) < 1500)
        assertEquals(1.25f, ui { a.activePlayer!!.playbackParameters.speed })
        assertEquals(1.25f, ui { a.model.preferences.speed }); assertEquals("Zoom", ui { a.model.preferences.videoFit })
        assertTrue(tagged("vod-controls")?.isShown == true)
    }
    @Test fun endedMovieShowsReplayAndRestartsActualPlayback() {
        start()
        await { ui { a.activePlayer!!.duration > 50000 } }
        ui { a.activePlayer!!.seekTo((a.activePlayer!!.duration - 750).coerceAtLeast(0)) }
        await(25000) { ui { a.activePlayer!!.playbackState == Player.STATE_ENDED } }
        await { tagged("vod-controls")?.isShown == true && tagged("vod-play-pause")?.contentDescription == "Replay" }
        ui { a.window.decorView.findTag("vod-play-pause")!!.requestFocus() }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        await(20000) { info().optBoolean("playing") && ui { a.activePlayer!!.currentPosition < 8000 } }
        assertTrue("Replay did not preserve play intent in ExoPlayer", ui { a.activePlayer!!.playWhenReady })
    }
    @Test fun alternateAudioAndEmbeddedSubtitleActuallyActivate() {
        start("http://10.0.2.2:8878/media/multitrack.mkv", "Fleet audio & subtitle fixture")
        ui { a.activePlayer!!.pause() }
        await { !info().optBoolean("playing") && ui { !a.activePlayer!!.playWhenReady } }
        await { info().optString("audioTrack").isNotBlank() }
        val originalAudio = info().optString("audioTrack")
        tracksMenu("Audio track")
        val frenchLabel = chooseMatching(".*\\b(?:French|fra|fr|fre)\\b.*")
        await(15000) {
            val actual = info().optString("audioTrack")
            actual.lowercase().substringBefore('-') in setOf("fr", "fra", "fre")
        }
        await { ui { a.hasWindowFocus() } }
        tracksMenu("Subtitles"); choose("Off")
        await { info().optString("subtitleText").isBlank() && ui { a.hasWindowFocus() } }
        tracksMenu("Subtitles")
        val englishLabel = chooseMatching(".*\\b(?:English|eng|en)\\b.*")
        await { ui { a.hasWindowFocus() } }
        ui { a.activePlayer!!.seekTo(1500); a.activePlayer!!.play() }
        await(20000) { info().optBoolean("playing") && info().optString("subtitleText").contains("FLEET SUBTITLE FIXTURE") }
        ui { a.activePlayer!!.pause() }
        await { !info().optBoolean("playing") && info().optString("subtitleText").contains("FLEET SUBTITLE FIXTURE") }
        val observed = info()
        val dir = ui { a.getExternalFilesDir(null)!! }
        File(dir, "vod-tracks-exoplayer.json").writeText(JSONObject()
            .put("engine", "ExoPlayer").put("audioBefore", originalAudio).put("audioAfter", observed.optString("audioTrack"))
            .put("audioChoice", frenchLabel).put("subtitleChoice", englishLabel)
            .put("subtitleAfter", observed.optString("subtitleText")).put("offVerifiedEmpty", true).toString(2))
        await { tagged("vod-panel")?.isShown == true }
        SystemClock.sleep(300)
        screenshot("ios-vod-subtitles-controls-exoplayer")
        key(KeyEvent.KEYCODE_BACK); await { tagged("vod-controls")?.isShown == false }
        assertTrue(ui { a.model.route is Route.Player })
        SystemClock.sleep(300)
        screenshot("ios-vod-subtitles-exoplayer")
    }
    @Test fun directRequestHeadersKeepCommasAndTrailingBackslashes() {
        val headers=mapOf("X-Fixture-Comma" to "alpha,beta","X-Fixture-Slash" to "ends\\","X-Fixture-Final" to "last")
        start("http://10.0.2.2:8878/media/headers.m3u8?engine=ExoPlayer",headers=headers)
        val state=JSONObject(runBlocking { Http().text("http://10.0.2.2:8878/__state") })
        val seen=state.getJSONArray("header_checks").objects().filter{it.optString("engine")=="ExoPlayer"}
        assertTrue("No direct media request for ExoPlayer",seen.isNotEmpty())
        assertTrue("Request headers changed in ExoPlayer",seen.all { request -> headers.all{(name,value)->request.getJSONObject("values").getString(name)==value} })
    }
    @Test fun pictureSlidersPreviewStepValuesResetAndReturnFocusWithoutResumingPausedVideo() {
        start()
        ui { a.activePlayer!!.seekTo(7000); a.activePlayer!!.pause() }
        await { ui { a.activePlayer?.playWhenReady == false && a.activePlayer?.playbackState == Player.STATE_READY } && info().optLong("positionMs") in 6500..8500 }
        val before = ui { a.activePlayer!!.currentPosition }
        ui { a.window.decorView.findTag("vod-options")!!.requestFocus() }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        val dialog = pictureDialogSelector()
        assertTrue(device.wait(Until.hasObject(dialog), 10000) == true)
        fun focused(label: String) = await { device.findObject(By.desc(label))?.isFocused == true }
        fun pausedAtSamePosition() = ui {
            a.activePlayer?.let { it.playbackState == Player.STATE_READY && !it.playWhenReady && kotlin.math.abs(it.currentPosition - before) < 1500 } == true
        }
        focused("Brightness"); key(KeyEvent.KEYCODE_DPAD_RIGHT)
        await(30000) { ui { a.playback?.picture?.brightness == 5 && a.playback?.snapshot?.effectsActive == true } && pausedAtSamePosition() }
        assertTrue("Enabling picture effects closed the controls", device.findObject(dialog) != null)
        key(KeyEvent.KEYCODE_DPAD_DOWN); focused("Contrast")
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        await { ui { a.playback?.picture?.contrast == -5 } }
        key(KeyEvent.KEYCODE_DPAD_DOWN); focused("Saturation")
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        await { ui { a.playback?.picture?.saturation == 95 } }
        await(30000) { pausedAtSamePosition() }
        screenshot("picture-controls-exoplayer")
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        await { device.findObject(By.res("android:id/button3"))?.isFocused == true }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        await(30000) { ui { a.playback?.picture == VideoPicture() && a.playback?.snapshot?.effectsActive == false } && pausedAtSamePosition() }
        assertTrue("Reset dismissed picture controls", device.findObject(dialog) != null)
        screenshot("picture-reset-exoplayer")
        key(KeyEvent.KEYCODE_BACK)
        await { device.findObject(dialog) == null && ui { a.hasWindowFocus() } }
        await { tagged("vod-options")?.hasFocus() == true }
        assertTrue(pausedAtSamePosition())
    }
    @Test fun failedPictureChangesRestoreTheActualPictureValues() {
        start(); ui { a.activePlayer!!.pause() }
        var accepted = VideoPicture()
        var calls = 0
        val dialog = ui {
            showPictureControls(a, accepted, { value, done ->
                calls++
                if (calls == 2) { accepted = VideoPicture(); done("Picture controls are unavailable for this video.") }
                else { accepted = value; done(null) }
            }, actualPicture = { accepted })
        }
        try {
            await { device.findObject(By.desc("Brightness"))?.isFocused == true }
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            await { ui { accepted.brightness == 5 && (dialog.window!!.decorView.findTag("picture-value:brightness") as TextView).text.toString() == "+5" } }
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            await { ui {
                val content = dialog.window!!.decorView
                calls == 2 && accepted.brightness == 0 && (content.findTag("picture-brightness") as SeekBar).progress == 10 &&
                    (content.findTag("picture-value:brightness") as TextView).text.toString() == "0" && content.findTag("picture-error")?.isShown == true
            } }
            screenshot("picture-unsupported-rollback")
            key(KeyEvent.KEYCODE_BACK)
            await { ui { !dialog.isShowing && a.hasWindowFocus() } }
            await { tagged("vod-play-pause")?.hasFocus() == true }
        } finally { ui { dialog.dismiss() } }
    }
    @Test fun rapidPictureStepsDuringFirstActivationApplyTheLatestValueWithoutMovingPausedVideo() {
        start()
        ui { a.activePlayer!!.seekTo(7000); a.activePlayer!!.pause() }
        await { ui { a.activePlayer?.playWhenReady == false && a.activePlayer?.playbackState == Player.STATE_READY } && info().optLong("positionMs") in 6500..8500 }
        val before = ui { a.activePlayer!!.currentPosition }
        ui { a.window.decorView.findTag("vod-options")!!.requestFocus() }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        val dialog = pictureDialogSelector()
        assertTrue(device.wait(Until.hasObject(dialog), 10000) == true)
        await { device.findObject(By.desc("Brightness"))?.isFocused == true }
        repeat(3) { key(KeyEvent.KEYCODE_DPAD_RIGHT) }
        await(30000) { ui {
            val player = a.activePlayer
            a.playback?.picture?.brightness == 15 && a.playback?.snapshot?.effectsActive == true &&
                player != null && player.playbackState == Player.STATE_READY && !player.playWhenReady && kotlin.math.abs(player.currentPosition - before) < 1500
        } }
        assertTrue(device.wait(Until.hasObject(By.text("+15")), 10000) == true)
        assertTrue("Rapid picture changes left the dialog closed", device.findObject(dialog) != null)
        assertFalse("First-activation changes were rejected", device.findObject(By.text("Wait for the video to start, then adjust its picture.")) != null)
        screenshot("picture-rapid-activation-exoplayer")
        key(KeyEvent.KEYCODE_BACK)
        await { device.findObject(dialog) == null && ui { a.hasWindowFocus() } }
        await { tagged("vod-options")?.hasFocus() == true }
        assertFalse(ui { a.activePlayer!!.playWhenReady })
        key(KeyEvent.KEYCODE_BACK)
        await { tagged("vod-controls")?.isShown == false }
        assertStableVisiblePausedFrame(before)
        screenshot("picture-rapid-video-exoplayer")
    }
    @Test fun pausedIntentAndPositionSurviveBackgroundThenExplicitPlayResumes() {
        start()
        ui { a.activePlayer!!.seekTo(7000) }
        await { info().optLong("positionMs") >= 6500 }
        ui { a.activePlayer!!.pause() }
        await { !info().optBoolean("playing") && ui { !a.activePlayer!!.playWhenReady } }
        val before = ui { a.activePlayer!!.currentPosition }
        val original = ui { a.activePlayer }
        rule.scenario.moveToState(Lifecycle.State.CREATED)
        await { ui { a.activePlayer == null } }
        rule.scenario.moveToState(Lifecycle.State.RESUMED)
        await(45000) { ui {
            val current = a.activePlayer
            current != null && current !== original && current.playbackState == Player.STATE_READY && !current.playWhenReady
        } }
        await { tagged("vod-controls")?.isShown == true && ui { a.hasWindowFocus() } }
        SystemClock.sleep(500)
        assertFalse("Background return resumed a paused ExoPlayer movie", ui { a.activePlayer!!.playWhenReady })
        val after = ui { a.activePlayer!!.currentPosition }
        assertTrue("Paused ExoPlayer resume lost position: $before -> $after", kotlin.math.abs(after - before) < 1500)
        key(KeyEvent.KEYCODE_MEDIA_PLAY)
        await(15000) { info().optBoolean("playing") && ui { a.activePlayer!!.playWhenReady } }
    }
}
