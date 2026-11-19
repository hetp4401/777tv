@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package xyz.billybishop4.fleet.tv

import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.filters.SdkSuppress
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Generated portal/media only. Cached holders must refresh state without tuning. */
class LiveCachedRowsTest:NativeTestBase() {
    private val base="http://10.0.2.2:8878"
    private val profile=Provider("cached-live-rows-fixture","Cached live rows","$base/stalker_portal/",mac="00:1A:79:00:00:09")
    private fun links()=JSONObject(runBlocking{Http().text("$base/__state")}).getInt("portal_links")
    @Before fun setup() {
        runBlocking{Http().text("$base/__control?reset=1&large_channels=1")}
        ui {
            a.model.showProfiles();a.model.server(base);a.model.updatePreferences(Preferences())
            a.model.providers.filter{it.id==profile.id}.forEach{a.model.removeProvider(it.id)}
            a.model.saveProvider(profile);a.model.open(Route.Channels(profile))
        }
        await{ui{!a.model.busy && a.model.channels.size==6000} && tagged("channel:1")?.isShown==true}
    }
    @After fun cleanup(){ui{a.model.showProfiles();a.model.removeProvider(profile.id)};await{ui{a.activePlayer==null}}}
    private fun preview(channel:Channel) {
        ui{tagged("channel:${channel.id}")!!.requestFocusFromTouch();tagged("channel:${channel.id}")!!.performClick()}
        await(45000){ui{a.activePlayer?.mediaMetadata?.title?.toString()==channel.name && a.activePlayer?.isPlaying==true}}
    }
    private fun frames() {
        val done=CountDownLatch(1)
        ui{a.window.decorView.postOnAnimation{a.window.decorView.postOnAnimation{done.countDown()}}}
        assertTrue("The UI did not render its pending state",done.await(5,TimeUnit.SECONDS))
    }
    @SdkSuppress(minSdkVersion=30)
    @Test fun aCachedChannelRefreshesItsPreviewAndFavoriteStateWhenReattached() {
        val grid=tagged("channels") as RecyclerView
        val layout=ui{grid.layoutManager as LinearLayoutManager}
        // Keep this correctness workload deterministic: retain the real six-row
        // cache, but do not let speculative prefetch evict the holder under test.
        ui{layout.isItemPrefetchEnabled=false}
        val first=ui{a.model.channels[0]};val next=ui{a.model.channels[4]}
        val before=links();preview(first)
        val cached=tagged("channel:${first.id}") as LiveChannelRow
        assertTrue(ui{cached.isSelected})
        ui{find("Live TV options")!!.requestFocusFromTouch();layout.scrollToPositionWithOffset(2,0)}
        await{ui{layout.findFirstVisibleItemPosition()==2 && !cached.isAttachedToWindow} && tagged("channel:${next.id}")?.isShown==true}
        preview(next);val playing=player()
        ui{a.model.toggleFavorite(profile,first)};frames()
        assertTrue("The selected cached holder should still be detached",ui{!cached.isAttachedToWindow && cached.isSelected})
        ui{find("Live TV options")!!.requestFocusFromTouch();layout.scrollToPositionWithOffset(0,0)}
        await{tagged("channel:${first.id}")?.isShown==true}
        val restored=tagged("channel:${first.id}") as LiveChannelRow
        assertSame("The test did not exercise the cached-holder path",cached,restored)
        assertFalse("A recycled preview highlight remained on the old channel",ui{restored.isSelected})
        assertEquals("Favorite",ui{restored.stateDescription?.toString()})
        assertSame("Refreshing row state replaced the current player",playing,player())
        assertEquals("Browsing or favorite state tuned an extra stream",before+2,links())
    }
}
