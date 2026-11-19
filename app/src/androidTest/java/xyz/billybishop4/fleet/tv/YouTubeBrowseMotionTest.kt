@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package xyz.billybishop4.fleet.tv

import android.graphics.Rect
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real native geometry and per-frame movement on generated worker shelves.
 * Never install this reset/fixture suite on a configured physical TV. */
class YouTubeBrowseMotionTest:NativeTestBase() {
    private val origin="http://10.0.2.2:8878"
    private val videos=(1..120).map{YouTubeVideo(id(it),"Generated browse title $it")}
    private val rows=(0..9).map{YouTubeShelf("motion-$it","Generated shelf ${it+1}",videos.subList(it*12,(it+1)*12))}
    private fun id(value:Int)="M"+value.toString().padStart(10,'0')
    private fun control()=runBlocking{Http().text("$origin/youtube/control?reset=1")}
    @Before fun prepare() {
        control()
        ui {
            a.model.top(Section.SEARCH);a.model.server(origin);a.model.store.youTubeCredentials().write(null)
            a.model.store.youTubePhoneEnabled(false);a.model.updatePreferences(Preferences(resume=false,youtubeAutoplay=false))
            a.model.youtubeFixture(origin);a.model.top(Section.YOUTUBE)
        }
        await{tagged("youtube-page")?.isShown==true && ui{!a.model.youtube.busy}}
        ui{a.model.youtube.installFixturePage(YouTubeFeed.HOME,YouTubePage(videos,shelves=rows))}
        await{ui{(tagged("youtube-home") as RecyclerView).adapter?.itemCount==10 && tagged("youtube-video:${id(1)}")?.isShown==true}}
        ui{tagged("youtube-video:${id(1)}")!!.requestFocusFromTouch()}
        await{ui{a.currentFocus?.tag=="youtube-video:${id(1)}"}}
    }
    @After fun cleanup(){ui{a.model.top(Section.SEARCH);a.model.updatePreferences(Preferences())};control()}
    private fun key(code:Int) {
        val down=SystemClock.uptimeMillis()
        for(action in intArrayOf(KeyEvent.ACTION_DOWN,KeyEvent.ACTION_UP))
            a.dispatchKeyEvent(KeyEvent(down,SystemClock.uptimeMillis(),action,code,0,0,KeyCharacterMap.VIRTUAL_KEYBOARD,0,0,InputDevice.SOURCE_DPAD))
    }
    private fun rowKey()=ui{generateSequence(a.currentFocus){it.parent as? View}.filterIsInstance<YouTubeShelfView>().firstOrNull()?.shelfKey}
    private fun settled(key:String)=rowKey()==key && ui{tagged("youtube-shelf-focus-anchor")?.isFocusable!=true &&
        (tagged("youtube-home") as RecyclerView).let{!it.isLayoutRequested && !it.hasPendingAdapterUpdates()}}
    private fun bounds(view:View)=Rect().apply{ui{view.getGlobalVisibleRect(this)}}

    private fun composedFrame():String {
        val hardware=ui{a.window.decorView.isHardwareAccelerated}
        if(android.os.Build.VERSION.SDK_INT>=29 && hardware) {
            // Animation callbacks run before traversal. Wait for submitted native
            // frames instead, including one further commit for compositor latch.
            repeat(2) {
                val committed=CountDownLatch(1)
                ui {
                    a.window.decorView.viewTreeObserver.registerFrameCommitCallback{committed.countDown()}
                    a.window.decorView.invalidate()
                }
                assertTrue("Native appearance frame did not commit",committed.await(10,TimeUnit.SECONDS))
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            return "two hardware frame commits"
        }
        val ready=CountDownLatch(1)
        ui{a.window.decorView.postOnAnimation{a.window.decorView.postOnAnimation{ready.countDown()}}}
        assertTrue("Native appearance did not compose",ready.await(5,TimeUnit.SECONDS))
        return "two animation callbacks (hardware frame callbacks unavailable)"
    }
    private fun railBlendsWithBackdrop(gradient:Boolean) {
        ui{a.model.updatePreferences(a.model.preferences.copy(youtubeThemeId="rose",themeGradient=gradient))}
        await{ui{AppTheme.palette.id=="rose" && AppTheme.gradient==gradient && tagged("youtube-page")?.isShown==true && !a.model.youtube.busy}}
        ui{a.model.youtube.installFixturePage(YouTubeFeed.HOME,YouTubePage(videos,shelves=rows))}
        await{ui{((tagged("youtube-home") as RecyclerView).findViewHolderForAdapterPosition(0)?.itemView as? YouTubeShelfView)?.shelfKey=="motion-0"}}
        ui{tagged("youtube-video:${id(1)}")!!.requestFocusFromTouch()}
        val rail=tagged("youtube-navigation")!!;val home=find("YouTube home")!!
        await{ui{rail.clipBounds?.width()==a.dp(52)}}
        val barrier=composedFrame()
        val backdrop=tagged("app-background")!!
        val origin=ui{IntArray(2).also{backdrop.getLocationOnScreen(it)}}
        val reference=ui{
            Bitmap.createBitmap(backdrop.width,backdrop.height,Bitmap.Config.ARGB_8888).also { bitmap->
                val copy=backdrop.background.constantState!!.newDrawable(a.resources).mutate()
                copy.setBounds(0,0,bitmap.width,bitmap.height);copy.draw(Canvas(bitmap))
            }
        }
        val frame=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()!!
        try {
            fun point(view:View,x:Int,y:Int)=ui{IntArray(2).also{view.getLocationOnScreen(it);it[0]+=a.dp(x);it[1]+=a.dp(y)}}
            val gap=point(rail,26,59);val corner=point(home,5,5);val middle=point(home,10,24)
            // This x is in the Activity's empty outer gutter, outside sidebar,
            // content and controls, at exactly the same y as the rail-gap probe.
            val outside=intArrayOf(origin[0]+a.dp(12),gap[1])
            fun rect(value:Rect)=JSONArray(listOf(value.left,value.top,value.right,value.bottom))
            fun color(value:Int)="#"+Integer.toHexString(value).padStart(8,'0')
            fun descriptor(view:View):JSONObject {
                val location=IntArray(2).also{view.getLocationOnScreen(it)}
                val visible=Rect();view.getGlobalVisibleRect(visible)
                return JSONObject().put("class",view.javaClass.name).put("tag",view.tag?.toString())
                    .put("shown",view.isShown).put("alpha",view.alpha.toDouble()).put("z",view.z.toDouble())
                    .put("width",view.width).put("height",view.height).put("screenXY",JSONArray(location.toList()))
                    .put("visibleBounds",rect(visible)).put("backgroundClass",view.background?.javaClass?.name)
                    .put("backgroundBounds",view.background?.bounds?.let(::rect)).put("clipBounds",view.clipBounds?.let(::rect))
                    .put("translationX",view.translationX.toDouble()).put("translationY",view.translationY.toDouble())
            }
            fun sample(name:String,position:IntArray)=JSONObject().put("name",name).put("screenXY",JSONArray(position.toList()))
                .put("expectedBackdrop",color(reference.getPixel(position[0]-origin[0],position[1]-origin[1])))
                .put("actual",color(frame.getPixel(position[0],position[1])))
            val evidence=ui {
                val prefs=a.model.preferences
                val ancestry=JSONArray();var parent:View?=rail
                while(parent!=null){ancestry.put(descriptor(parent));parent=parent.parent as? View}
                val children=JSONArray()
                if(backdrop is ViewGroup)for(index in 0 until backdrop.childCount)children.put(descriptor(backdrop.getChildAt(index)))
                JSONObject().put("capturedUptimeMs",SystemClock.uptimeMillis()).put("barrier",barrier)
                    .put("windowFocused",a.hasWindowFocus()).put("decorWindowFocused",a.window.decorView.hasWindowFocus())
                    .put("hardwareAccelerated",a.window.decorView.isHardwareAccelerated)
                    .put("palette",AppTheme.palette.id).put("paletteBackground",color(AppTheme.palette.background))
                    .put("palettePanel",color(AppTheme.palette.panel)).put("gradientStart",color(AppTheme.palette.gradientStart))
                    .put("gradientEnd",color(AppTheme.palette.gradientEnd)).put("currentGradient",AppTheme.gradient)
                    .put("themeId",prefs.themeId).put("youtubeThemeId",prefs.youtubeThemeId)
                    .put("savedGradient",prefs.themeGradient).put("expectedGradient",gradient)
                    .put("route",a.model.route.javaClass.simpleName).put("youtubeFeed",a.model.youtube.feed.name)
                    .put("playerPresent",a.activePlayer!=null).put("focusedTag",a.currentFocus?.tag?.toString())
                    .put("screenshotWidth",frame.width).put("screenshotHeight",frame.height)
                    .put("backdrop",descriptor(backdrop)).put("railAncestry",ancestry).put("rootChildren",children)
                    .put("samples",JSONArray().put(sample("outside rail pure background",outside))
                        .put(sample("inside rail account/search gap",gap)).put(sample("rounded pill corner",corner))
                        .put(sample("selected pill interior",middle)))
            }
            val folder=ui{a.getExternalFilesDir(null)!!}
            val prefix="youtube-rail-${if(gradient)"gradient" else "solid"}-before-assert"
            // Capture the assertion frame before cleanup can restore Classic.
            File(folder,"$prefix.json").writeText(evidence.toString(2))
            File(folder,"$prefix.png").outputStream().use{frame.compress(Bitmap.CompressFormat.PNG,100,it)}
            File(folder,"$prefix-reference.png").outputStream().use{reference.compress(Bitmap.CompressFormat.PNG,100,it)}
            fun closeColor(message:String,expected:Int,actual:Int) {
                assertTrue("$message expected=${Integer.toHexString(expected)} actual=${Integer.toHexString(actual)}",
                    kotlin.math.abs(Color.red(expected)-Color.red(actual))<=3 &&
                    kotlin.math.abs(Color.green(expected)-Color.green(actual))<=3 &&
                    kotlin.math.abs(Color.blue(expected)-Color.blue(actual))<=3)
            }
            fun matchesBackdrop(message:String,point:IntArray)=closeColor(message,
                reference.getPixel(point[0]-origin[0],point[1]-origin[1]),frame.getPixel(point[0],point[1]))
            assertNull("The sidebar must not draw a full-height backplate",ui{rail.background})
            matchesBackdrop("The account/search gap interrupted the section background",gap)
            matchesBackdrop("The selected icon pill has a square clipped corner",corner)
            closeColor("The selected icon needs its local pill",ui{AppTheme.palette.panel},frame.getPixel(middle[0],middle[1]))
        } finally { frame.recycle();reference.recycle() }
        val measured=ui{tagged("youtube-content")!!.width to tagged("youtube-content")!!.height}
        sendRemoteKey(KeyEvent.KEYCODE_DPAD_LEFT)
        await{ui{rail.clipBounds?.width()==a.dp(164) && kotlin.math.abs(tagged("youtube-content")!!.translationX-a.dp(112))<1}}
        assertTrue("Expanded navigation covered the first title",bounds(tagged("youtube-video:${id(1)}")!!).left>=bounds(rail).right+a.dp(12))
        assertEquals(measured,ui{tagged("youtube-content")!!.width to tagged("youtube-content")!!.height})
        assertEquals("rose",ui{a.model.preferences.youtubeThemeId});assertEquals(gradient,ui{a.model.preferences.themeGradient})
        screenshot("youtube-026-blended-rail-${if(gradient)"gradient" else "solid"}")
        sendRemoteKey(KeyEvent.KEYCODE_DPAD_RIGHT)
        await{ui{rail.clipBounds?.width()==a.dp(52) && a.currentFocus?.tag=="youtube-video:${id(1)}"}}
        assertNull(player())
    }
    @Test fun sidebarBlendsWithSolidThemeAndKeepsLocalRoundedSelection(){railBlendsWithBackdrop(false)}
    @Test fun sidebarBlendsWithGradientThemeAndKeepsLocalRoundedSelection(){railBlendsWithBackdrop(true)}

    @Test fun expandedSidebarKeepsTheFirstTitleReadableWithStableMeasuredContent() {
        val rail=tagged("youtube-navigation")!!;val body=tagged("youtube-content")!!
        val first=tagged("youtube-video:${id(1)}")!!
        val label=ui{descendants(first).filterIsInstance<TextView>().first{it.text.toString()=="Generated browse title 1"}}
        await{ui{rail.clipBounds?.width()==a.dp(52) && kotlin.math.abs(body.translationX)<1}}
        val original=bounds(first);val measured=ui{body.width to body.height}
        var layouts=0
        val listener=View.OnLayoutChangeListener{_,_,_,_,_,_,_,_,_->layouts++}
        ui{body.addOnLayoutChangeListener(listener)}
        try {
            sendRemoteKey(KeyEvent.KEYCODE_DPAD_LEFT)
            await{ui{rail.clipBounds?.width()==a.dp(164) && kotlin.math.abs(body.translationX-a.dp(112))<1}}
            assertEquals("Sidebar entry remeasured content",measured,ui{body.width to body.height})
            assertEquals("Sidebar animation caused layout work",0,ui{layouts})
            assertTrue("Sidebar covered the first card",bounds(first).left>=bounds(rail).right+a.dp(12))
            assertEquals("Sidebar cropped the title",ui{label.width},bounds(label).width())
            assertEquals("Preallocated sidebar geometry changed",a.dp(164),ui{rail.width})
            screenshot("youtube-026-expanded-sidebar")
            sendRemoteKey(KeyEvent.KEYCODE_DPAD_RIGHT)
            await{ui{rail.clipBounds?.width()==a.dp(52) && kotlin.math.abs(body.translationX)<1 && a.currentFocus===first}}
            assertEquals(original,bounds(first));assertEquals(0,ui{layouts})
        } finally { ui{body.removeOnLayoutChangeListener(listener)} }
        assertNull(player())
    }

    @Test fun rapidDownUsesIntermediateFramesInsteadOfJumpingToAnUnattachedShelf() {
        val home=tagged("youtube-home") as RecyclerView
        val deltas=ArrayList<Int>()
        val listener=object:RecyclerView.OnScrollListener(){override fun onScrolled(recyclerView:RecyclerView,dx:Int,dy:Int){if(dy!=0)deltas.add(dy)}}
        ui{home.addOnScrollListener(listener)}
        try {
            ui{repeat(8){key(KeyEvent.KEYCODE_DPAD_DOWN)}}
            await{settled("motion-8")}
            val moved=ui{deltas.toList()};val total=moved.sum()
            assertTrue("Distant shelf navigation did not animate through intermediate frames: $moved",moved.size>=3)
            assertTrue("The first frame jumped most of the distance: $moved",moved.first() in 1 until total/3)
            assertTrue("A frame exceeded the bounded motion step: $moved",moved.all{it in 1..a.dp(205)})
            assertEquals("youtube-video:${id(97)}",ui{a.currentFocus?.tag})
            File(ui{a.getExternalFilesDir(null)!!},"youtube-browse-motion.json").writeText(JSONObject()
                .put("generatedShelves",10).put("downInputs",8).put("dyPerFramePx",JSONArray(moved))
                .put("totalDyPx",total).put("physicalTv",false).toString(2))
            screenshot("youtube-026-scrolled-shelf")
        } finally { ui{home.removeOnScrollListener(listener)} }
        assertNull("Focus scrolling started playback",player())
    }

    @Test fun reversingAnInFlightMoveReturnsThroughIntermediateFramesWithoutLosingFocus() {
        val home=tagged("youtube-home") as RecyclerView
        val deltas=ArrayList<Int>()
        val listener=object:RecyclerView.OnScrollListener(){override fun onScrolled(recyclerView:RecyclerView,dx:Int,dy:Int){if(dy!=0)deltas.add(dy)}}
        ui{home.addOnScrollListener(listener)}
        try {
            ui{repeat(8){key(KeyEvent.KEYCODE_DPAD_DOWN)}}
            await{ui{deltas.sum()>a.dp(150)}}
            ui{repeat(8){key(KeyEvent.KEYCODE_DPAD_UP)}}
            await{settled("motion-0")}
            val moved=ui{deltas.toList()}
            assertTrue("The reversal jumped instead of scrolling: $moved",moved.count{it<0}>=2)
            assertTrue("Returned focus lost the original shelf",ui{a.currentFocus?.tag=="youtube-video:${id(1)}"})
            assertTrue("The reverse gesture did not return to its initial offset: $moved",kotlin.math.abs(moved.sum())<=1)
        } finally { ui{home.removeOnScrollListener(listener)} }
        assertNull(player())
    }
}
