@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package xyz.billybishop4.fleet.tv

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import android.view.KeyEvent
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Platform voice results are injected: these tests never open a microphone or external recognizer. */
class YouTubeVoiceTest:NativeTestBase() {
    private val base="http://10.0.2.2:8878"
    private var voice:YouTubeVoice?=null
    private val launched=ArrayList<Intent>()
    private fun result(text:String)=Intent().putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS,arrayListOf(text))
    @Before fun prepare() {
        runBlocking{Http().text("$base/youtube/control?reset=1")}
        ui{a.model.top(Section.SEARCH);a.model.server(base);a.model.store.youTubeCredentials().write(null);a.model.youtubeFixture(base);a.model.top(Section.YOUTUBE)}
        await{tagged("youtube-page")?.isShown==true && ui{!a.model.youtube.busy}}
        ui{voice=YouTubeVoice(a,launchForTest={launched.add(it)},availableForTest={true})}
    }
    @After fun cleanup(){ui{voice?.close();voice=null;a.model.top(Section.SEARCH);a.model.store.youTubeCredentials().write(null)}}
    @Test fun platformRecognitionSubmitsOneTrimmedSearchAndIgnoresDuplicateResults() {
        assertNotNull(tagged("youtube-home-voice"))
        ui{voice!!.start()}
        assertEquals(1,launched.size)
        assertEquals(RecognizerIntent.ACTION_RECOGNIZE_SPEECH,launched.single().action)
        assertEquals(RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,launched.single().getStringExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL))
        assertEquals(1,launched.single().getIntExtra(RecognizerIntent.EXTRA_MAX_RESULTS,0))
        ui{voice!!.result(Activity.RESULT_OK,result("  fixture voice  "))}
        await{ui{a.model.youtube.feed==YouTubeFeed.SEARCH && a.model.youtube.query=="fixture voice" && !a.model.youtube.busy}}
        ui{voice!!.result(Activity.RESULT_OK,result("stale result"))}
        assertEquals("fixture voice",ui{a.model.youtube.query})
        assertNotNull(tagged("youtube-voice-search"));assertNull(player())
        val state=JSONObject(runBlocking{Http().text("$base/youtube/state")})
        assertEquals(0,state.getJSONArray("resolved").length())
    }
    @Test fun cancellationAndEmptyResultsKeepTheCurrentPageAndQuery() {
        ui{voice!!.start();voice!!.result(Activity.RESULT_CANCELED,result("should not search"))}
        assertEquals(YouTubeFeed.HOME,ui{a.model.youtube.feed});assertEquals("",ui{a.model.youtube.query})
        ui{voice!!.start();voice!!.result(Activity.RESULT_OK,result(" "))}
        assertEquals(YouTubeFeed.HOME,ui{a.model.youtube.feed});assertEquals("",ui{a.model.youtube.query})
        assertEquals(2,launched.size);assertNull(player())
    }
    @Test fun aNewerTypedQueryWinsOverAnOlderVoiceResult() {
        ui{voice!!.start();a.model.youtube.editQuery("new typed query");voice!!.result(Activity.RESULT_OK,result("old speech"))}
        assertEquals("new typed query",ui{a.model.youtube.query})
        await{ui{a.model.youtube.feed==YouTubeFeed.SEARCH && !a.model.youtube.busy}}
        assertNull(player())
    }
    @Test fun newerRemoteInputInvalidatesSpeechEvenWhenThePageAndQueryStillMatch() {
        ui{tagged("youtube-home-search")!!.requestFocusFromTouch();voice!!.start()}
        sendRemoteKey(KeyEvent.KEYCODE_DPAD_RIGHT)
        ui{voice!!.result(Activity.RESULT_OK,result("obsolete speech"))}
        assertEquals(YouTubeFeed.HOME,ui{a.model.youtube.feed});assertEquals("",ui{a.model.youtube.query});assertNull(player())
    }
    @Test fun leavingAndReturningInvalidatesTheResultAndCannotMixTwoRecognizerRequests() {
        ui{
            voice!!.start();a.model.top(Section.SEARCH);voice!!.navigationChanged(a.model.route)
            a.model.top(Section.YOUTUBE);voice!!.navigationChanged(a.model.route);voice!!.start()
        }
        assertEquals("The old result must be drained before another recognizer request",1,launched.size)
        ui{voice!!.result(Activity.RESULT_OK,result("late speech"))}
        assertEquals("",ui{a.model.youtube.query})
        ui{voice!!.start();voice!!.result(Activity.RESULT_OK,result("fresh speech"))}
        assertEquals(2,launched.size);assertEquals("fresh speech",ui{a.model.youtube.query})
    }
    @Test fun missingSpeechServiceOffersTheKeyboardWithoutLaunchingAnActivity() {
        ui{voice!!.close();voice=YouTubeVoice(a,launchForTest={launched.add(it)},availableForTest={false});voice!!.start()}
        assertTrue(device.wait(Until.hasObject(By.text("Voice search unavailable")),5000))
        assertTrue(launched.isEmpty());click("Done")
        assertEquals(YouTubeFeed.HOME,ui{a.model.youtube.feed});assertNull(player())
    }
}
