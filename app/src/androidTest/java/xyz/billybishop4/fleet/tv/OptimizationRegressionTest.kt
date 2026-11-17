@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package xyz.billybishop4.fleet.tv

import android.content.Context
import android.os.SystemClock
import android.view.View
import android.widget.FrameLayout
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class OptimizationRegressionTest : NativeTestBase() {
    @Test fun historyCacheSeesOtherWritersAndDoesNotRetainEpisodeCatalogs() {
        ui {
            val first=Store(a);val second=Store(a);first.clearHistory()
            val series=Title("cache-series","Series","series",episodes=(1..500).map { Episode("s:1:$it",1,it,"Episode $it") })
            if(a.model.saved.any { it.key==series.key })a.model.toggle(series)
            a.model.toggle(series);assertTrue(a.model.saved.first().episodes.isEmpty())
            a.model.toggle(series)
            first.progress(Progress(series,series.episodes[0],1234,10000,1))
            assertTrue(first.progress().first().title.episodes.isEmpty())
            assertEquals(1234L,first.progress().first().positionMs)
            second.progress(Progress(Title("other","Other"),null,2000,10000,2))
            assertEquals("other",first.progress().first().title.id)
            second.clearHistory();assertTrue(first.progress().isEmpty())
            a.getSharedPreferences("fleet-tv",Context.MODE_PRIVATE).edit().putString("progress","broken json").apply()
            assertTrue(first.progress().isEmpty());assertNotNull(first.readWarning)
            first.clearHistory();assertNull(first.readWarning)
        }
    }
    @Test fun memoryPressureDropsInactiveCatalogAndLiveReloadsWithoutLosingPreferences() {
        runBlocking { Http().text("http://10.0.2.2:8878/__control?reset=1&large_channels=1") }
        val p=Provider("trim-fixture","Trim fixture","http://10.0.2.2:8878/stalker_portal/",mac="00:1A:79:00:00:01")
        ui { a.model.showProfiles();a.model.updatePreferences(Preferences());a.model.server("http://10.0.2.2:8878");a.model.saveProvider(p);a.model.open(Route.Channels(p)) }
        await { find(p.name)!=null && ui { !a.model.busy && a.model.channels.size==6000 } }
        ui {
            a.model.makeDefaultProfile(p.id);a.model.toggleFavorite(p,a.model.channels.first());a.model.setCategoryVisible(p.id,"News",false)
            a.onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
            assertEquals(6000,a.model.channels.size)
            a.model.top(Section.SEARCH)
        }
        await { tagged("search-home")?.isShown==true && ui{!a.model.busy} }
        ui { a.onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW);assertTrue(a.model.channels.isEmpty());a.model.top(Section.LIVE) }
        await { find(p.name)!=null && ui { !a.model.busy && a.model.channels.size==6000 } }
        assertTrue(ui { a.model.favorites.isNotEmpty() && "News" in a.model.hiddenCategories[p.id].orEmpty() })
        await { find("All categories hidden")!=null }
        ui { a.model.showAllCategories(p.id) }
        await { tagged("channel:1")!=null }
    }
    @Test fun singleViewRowKeepsUnicodeLabelsFocusFavoriteAndClickSemanticsWhenRebound() {
        var clicked="";var toggled=""
        val c=Channel("unicode","ગુજરાતી العربية עברית — A very long channel name that should be ellipsized","","News",number="123")
        lateinit var row:LiveChannelRow
        val parent=ui { a.findViewById<android.view.ViewGroup>(android.R.id.content) }
        ui {
            row=LiveChannelRow(a,{v,_->clicked=v.id},{toggled=it.id})
            parent.addView(row,FrameLayout.LayoutParams(a.dp(360),a.dp(64)).apply { topMargin=a.dp(110) })
            row.bind(c,false,true);row.requestFocus();row.performClick();row.performLongClick()
            assertEquals(c.name,row.contentDescription);assertTrue(row.hasFocus());assertEquals("unicode",clicked);assertEquals("unicode",toggled)
        }
        await { ui { row.width>0 && row.height>0 } };screenshot("optimized-unicode-row")
        ui {
            row.layoutDirection=View.LAYOUT_DIRECTION_RTL;row.bind(c.copy(id="rebound",name="Rebound"),true,false)
            row.performClick();assertEquals("rebound",clicked);assertTrue(row.isSelected);assertEquals("Rebound",row.contentDescription)
            row.release();parent.removeView(row)
        }
    }
    @Test fun recycledRowCancelsOldLogoAndPreservesLogoAspectRatio() {
        lateinit var row:LiveChannelRow
        val parent=ui { a.findViewById<android.view.ViewGroup>(android.R.id.content) }
        val c=Channel("logo","Logo fixture","",logo="http://10.0.2.2:8878/logo.png?slow=1")
        try {
            ui {
                row=LiveChannelRow(a,{_,_->},{})
                parent.addView(row,FrameLayout.LayoutParams(a.dp(360),a.dp(64)).apply { topMargin=a.dp(110) })
                row.bind(c,false,false)
            }
            await { ui { row.width>0 } };SystemClock.sleep(100)
            ui { row.bind(c.copy(logo=""),false,false) };SystemClock.sleep(1300)
            fun red(atTop:Boolean=false):Boolean {
                val xy=ui { IntArray(2).also { row.getLocationOnScreen(it) } }
                val bitmap=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()!!
                return try {
                    val pixel=bitmap.getPixel(xy[0]+a.dp(27),xy[1]+if(atTop)a.dp(16) else row.height/2)
                    android.graphics.Color.red(pixel)>200 && android.graphics.Color.green(pixel)<80 && android.graphics.Color.blue(pixel)<80
                } finally { bitmap.recycle() }
            }
            assertFalse("A cancelled logo reappeared on a blank row",red())
            ui { row.bind(c.copy(logo="http://10.0.2.2:8878/logo.png?normal=1"),false,false) }
            await { red() };assertFalse("A 2:1 logo was stretched into a square",red(true))
        } finally { ui { row.release();parent.removeView(row) } }
    }
}

