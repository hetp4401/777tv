package xyz.billybishop4.fleet.tv

import android.app.AlertDialog
import android.graphics.drawable.BitmapDrawable
import android.os.SystemClock
import android.view.KeyEvent
import android.widget.FrameLayout
import android.widget.ImageView
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

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
        lateinit var dialog:AlertDialog
        ui{anchor.requestFocus();dialog=a.showInfo("Generated info","Generated body")}
        await{ui{dialog.isShowing}}
        ui{
            dialog.dismiss()
            // Keep dismissal and the newer key in one main-thread turn, before the posted restoration.
            a.window.decorView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_LEFT))
            newer.requestFocus()
        }
        await{ui{a.hasWindowFocus() && a.currentFocus===newer}}
        SystemClock.sleep(120);assertSame(newer,ui{a.currentFocus})
    }
}
