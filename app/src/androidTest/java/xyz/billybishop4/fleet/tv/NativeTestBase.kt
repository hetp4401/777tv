@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package xyz.billybishop4.fleet.tv

import android.os.SystemClock
import android.view.*
import android.widget.*
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Before
import org.junit.Rule
import org.junit.Assert.*
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.json.JSONObject
import java.io.File

open class NativeTestBase {
    @get:Rule(order=0) val rule = ActivityScenarioRule(MainActivity::class.java)
    private var testName="unknown"
    private var capturedFailure=false
    @get:Rule(order=1) val diagnostics=object:TestWatcher() {
        override fun starting(description:Description) { testName=description.methodName.replace(Regex("[^A-Za-z0-9_]"),"_");capturedFailure=false }
        override fun failed(error:Throwable,description:Description) {
            captureDiagnostics("after-cleanup")
        }
    }
    private fun captureDiagnostics(phase:String) {
        if(capturedFailure)return
        runCatching {
                val dir=ui { a.getExternalFilesDir(null)!! }
                val name=testName
                val safe=ui { a.model.providers.all{it.url.startsWith("http://10.0.2.2:8878")} &&
                    a.model.providerDraft?.url.orEmpty().let { it.isBlank() || it.startsWith("http://10.0.2.2:8878") } }
                File(dir,"failure-$name.json").writeText(ui { JSONObject().put("phase",phase).put("route",a.model.route.javaClass.simpleName)
                    .put("section",a.model.section.name).put("busy",a.model.busy).put("channels",a.model.channels.size)
                    .put("results",a.model.results.size).put("focus",a.currentFocus?.tag?.toString())
                    .put("youtubeFeed",a.model.youtube.feed.name).put("youtubeBusy",a.model.youtube.busy)
                    .put("youtubeError",a.model.youtube.error).put("youtubeVideos",a.model.youtube.videos.size)
                    .put("playbackMatchesRoute",a.playback?.watch===(a.model.route as? Route.Player)?.watch)
                    .put("windowFocus",a.hasWindowFocus()).put("touchMode",a.window.decorView.isInTouchMode)
                    .put("playback",JSONObject(a.playbackInfo)).toString(2) })
                if(safe) { device.dumpWindowHierarchy(File(dir,"failure-$name.xml"));screenshot("failure-$name") }
                capturedFailure=true
        }
    }
    protected lateinit var a: MainActivity
    protected val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    @Before fun acquireActivity() { rule.scenario.onActivity { a=it };await{ui{a.hasWindowFocus()}} }
    protected fun <T> ui(block:()->T):T {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return block()
        var result:Any?=null
        InstrumentationRegistry.getInstrumentation().runOnMainSync{
            androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).filterIsInstance<MainActivity>().lastOrNull()?.let{a=it}
            result=block()
        }
        @Suppress("UNCHECKED_CAST") return result as T
    }
    protected fun await(timeout:Long=30000,condition:()->Boolean){val end=SystemClock.elapsedRealtime()+timeout;while(SystemClock.elapsedRealtime()<end){if(condition())return;SystemClock.sleep(40)};if(condition())return;captureDiagnostics("await-timeout");fail("Timed out waiting for native UI")}
    protected fun tagged(tag:String):View?=ui{descendants(a.window.decorView).firstOrNull { it.tag==tag && it.isShown }?:a.window.decorView.findTag(tag)}
    protected fun descendants(v:View):Sequence<View> = sequence {yield(v);if(v is ViewGroup)for(i in 0 until v.childCount)yieldAll(descendants(v.getChildAt(i)))}
    protected fun find(label:String):View?=ui{descendants(a.window.decorView).firstOrNull{it.isShown&&it.width>0&&it.height>0&&(it.contentDescription?.toString()==label||(it is TextView&&it.text.toString()==label))}}
    protected fun click(label:String){
        fun target():View?=ui{if(!a.hasWindowFocus())null else descendants(a.window.decorView).firstOrNull{it.isShown&&it.width>0&&it.height>0&&it.isClickable&&it !is EditText&&(it.contentDescription?.toString()==label||(it is TextView&&it.text.toString()==label))}}
        val selector=androidx.test.uiautomator.By.text(java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(label),java.util.regex.Pattern.CASE_INSENSITIVE))
        await{target()!=null||device.findObject(selector)!=null}
        val found=target()
        if(found!=null)ui{found.requestFocus();found.performClick()}else device.findObject(selector).click()
    }
    protected fun sendRemoteKey(code:Int) {
        val instrumentation=InstrumentationRegistry.getInstrumentation();instrumentation.waitForIdleSync()
        val down=SystemClock.uptimeMillis()
        // A real remote keeps one downTime across DOWN/UP; avoid synthetic zero-time pairs.
        for(action in intArrayOf(KeyEvent.ACTION_DOWN,KeyEvent.ACTION_UP))instrumentation.sendKeySync(
            KeyEvent(down,SystemClock.uptimeMillis(),action,code,0,0,KeyCharacterMap.VIRTUAL_KEYBOARD,0,0,InputDevice.SOURCE_DPAD))
    }
    @Suppress("UNCHECKED_CAST") protected fun firstMovieCard():View?=ui {
        val home=a.window.decorView.findTag("search-home") as? androidx.recyclerview.widget.RecyclerView?:return@ui null
        val shelf=descendants(home).filterIsInstance<ShelfCard>().firstOrNull { it.shelfName=="Popular · Movies" }
        if(shelf==null) {
            val rows=home.adapter as? NativeAdapter<HomeRow>
            val index=rows?.currentList?.indexOfFirst { it.name=="Popular · Movies" }?:-1
            if(index>=0)home.scrollToPosition(index)
        }
        shelf?.list?.getChildAt(0)?.takeIf{it.isShown && it.width>0 && it.height>0}
    }
    protected fun remote(label:String){await{find(label)!=null};ui{find(label)!!.requestFocus()};sendRemoteKey(KeyEvent.KEYCODE_DPAD_CENTER)}
    protected fun field(label:String,value:String){await{tagged("field:$label") is EditText};ui{(a.window.decorView.findTag("field:$label") as EditText).setText(value)}}
    protected fun liveAction(action:String){click("Live TV options");click(action)}
    protected fun profileAction(name:String,action:String){click("Options for $name");click(action)}
    protected fun deleteProfile(name:String,confirm:Boolean=true){
        profileAction(name,"Delete profile")
        await{device.findObject(androidx.test.uiautomator.By.text("Delete $name and its saved credentials?"))!=null}
        click(if(confirm)"Delete profile" else "Cancel")
    }
    protected fun nav(label:String){await{tagged("navigation:$label")!=null};ui{a.window.decorView.findTag("navigation:$label")!!.performClick()};if(label=="Live TV"){await{ui{a.model.route is Route.Channels||a.model.route is Route.Browse}};if(ui{a.model.route is Route.Channels})liveAction("Manage IPTV profiles")}}
    protected fun player()=ui{a.player}
    protected fun playing(timeout:Long=30000){await(timeout){ui{a.player?.let{it.isPlaying&&it.videoSize.width>0}?:false}}}
    protected fun back(){ui{a.model.back()}}
    protected fun screenshot(name:String){device.executeShellCommand("screencap -p /sdcard/Download/fleet-tv-$name.png")}
}
