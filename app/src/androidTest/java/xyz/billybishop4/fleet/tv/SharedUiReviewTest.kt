package xyz.billybishop4.fleet.tv

import android.app.AlertDialog
import android.graphics.drawable.BitmapDrawable
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SharedUiReviewTest:NativeTestBase() {
    private val base="http://10.0.2.2:8878"
    @Before fun setup(){ui{a.model.server(base);a.model.top(Section.SEARCH)};await{ui{!a.model.busy}}}
    @Test fun artworkReloadsTheSameUrlAtItsNewDisplaySizeAndBlankingReleasesIt() {
        val host=ui{a.findViewById<android.view.ViewGroup>(android.R.id.content)}
        val image=ui{ImageView(a).also{host.addView(it,FrameLayout.LayoutParams(240,135))}}
        val loader=ui{a.artwork}
        try{
            ui{loader.show(image,"$base/artwork-sizing.png",96,54)}
            await{ui{(image.drawable as? BitmapDrawable)?.bitmap?.width==96}}
            ui{loader.show(image,"$base/artwork-sizing.png",192,108)}
            await{ui{(image.drawable as? BitmapDrawable)?.bitmap?.width==192}}
            assertEquals(108,ui{(image.drawable as BitmapDrawable).bitmap.height})
            ui{loader.show(image,"",192,108)};assertNull(ui{image.drawable})
        }finally{ui{image.setImageDrawable(null);host.removeView(image)}}
    }
    @Test fun navigatingAwayDismissesSharedDialogsWithoutRunningTheirActions() {
        var confirmed=false;lateinit var dialog:AlertDialog
        for(kind in 0..3) {
            ui{
                a.model.open(Route.Settings)
            }
            await{tagged("settings-list")?.isShown==true}
            ui{
                dialog=when(kind) {
                    0->a.confirm("Generated confirmation","Confirm"){confirmed=true}
                    1->a.showInfo("Generated info","Generated body")
                    2->a.showActions("Generated actions",listOf("Generated action" to {confirmed=true}))!!
                    else->a.chooseOption("Generated choices",listOf("A","B"),0){confirmed=true}!!
                }
            }
            assertTrue(ui{dialog.isShowing});ui{a.model.top(Section.SEARCH)}
            await{ui{!dialog.isShowing} && tagged("search-home")?.isShown==true}
            assertFalse(confirmed)
        }
    }
    @Test fun dismissingADialogNeverOverwritesANewerRemoteFocusChoice() {
        ui{a.model.open(Route.Settings)};await{tagged("settings-list")?.isShown==true}
        val anchor=find("Playback")!!;val newer=tagged("navigation:On-Demand")!!
        var dialog:AlertDialog?=null
        val decor=ui{a.window.decorView}
        val trace=JSONArray()
        val listener=ViewTreeObserver.OnGlobalFocusChangeListener { previous,next->
            val frames=Thread.currentThread().stackTrace.filter{it.className.startsWith("xyz.billybishop4.fleet.tv")}.take(12)
            trace.put(JSONObject().put("atMs",SystemClock.uptimeMillis()).put("from",previous?.tag?.toString())
                .put("to",next?.tag?.toString()).put("generation",a.focusInputGeneration)
                .put("windowFocus",a.hasWindowFocus()).put("applicationStack",JSONArray(frames.map{it.toString()})))
        }
        ui{decor.viewTreeObserver.addOnGlobalFocusChangeListener(listener)}
        try {
            ui {
                assertTrue(anchor.requestFocusFromTouch());assertSame(anchor,a.currentFocus)
                dialog=a.showInfo("Generated info","Generated body")
            }
            await{ui{dialog?.isShowing==true && dialog?.window?.decorView?.hasWindowFocus()==true}}
            ui {
                val generation=a.focusInputGeneration
                checkNotNull(dialog).dismiss()
                // A posted dismiss notification cannot run until this UI turn
                // returns. A newer root input and successful focus choice must
                // invalidate its old anchor even if the listener runs later.
                val down=SystemClock.uptimeMillis()
                for(action in intArrayOf(KeyEvent.ACTION_DOWN,KeyEvent.ACTION_UP))a.dispatchKeyEvent(
                    KeyEvent(down,SystemClock.uptimeMillis(),action,KeyEvent.KEYCODE_DPAD_LEFT,0,0,
                        KeyCharacterMap.VIRTUAL_KEYBOARD,0,0,InputDevice.SOURCE_DPAD))
                assertEquals("A real root DOWN was not delivered",generation+1,a.focusInputGeneration)
                assertTrue("The newer focus request was rejected",newer.requestFocusFromTouch())
                assertSame("The intended new selection was never established",newer,a.currentFocus)
            }
            await{ui{a.hasWindowFocus()}}
            val settled=CountDownLatch(1)
            ui{decor.postOnAnimation{decor.postOnAnimation{settled.countDown()}}}
            assertTrue("Two native frame callbacks did not complete",settled.await(10,TimeUnit.SECONDS))
            assertSame("Delayed dialog restoration overwrote newer input; trace="+ui{trace.toString()},newer,ui{a.currentFocus})
            assertEquals(Route.Settings,ui{a.model.route})
        } finally {
            ui {
                if(decor.viewTreeObserver.isAlive)decor.viewTreeObserver.removeOnGlobalFocusChangeListener(listener)
                File(a.getExternalFilesDir(null)!!,"dialog-focus-cancellation.json").writeText(JSONObject()
                    .put("focus",a.currentFocus?.tag?.toString()).put("windowFocus",a.hasWindowFocus())
                    .put("generation",a.focusInputGeneration).put("events",trace).toString(2))
                dialog?.takeIf{it.isShowing}?.dismiss()
            }
        }
    }
}
