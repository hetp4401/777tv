@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package xyz.billybishop4.fleet.tv

import android.os.SystemClock
import android.widget.EditText
import androidx.recyclerview.widget.RecyclerView
import androidx.test.uiautomator.By
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

/** Generated portal/media only; exercise the remote's actual OK and Back paths. */
class LiveNavigationTest : NativeTestBase() {
    private val profile = Provider("navigation-fixture", "Navigation fixture", "http://10.0.2.2:8878/stalker_portal/", mac = "00:1A:79:00:00:01")
    private fun control(value: String) = runBlocking { Http().text("http://10.0.2.2:8878/__control?$value") }
    private fun links() = JSONObject(runBlocking { Http().text("http://10.0.2.2:8878/__state") }).getInt("portal_links")
    private fun list() = tagged("channels") as RecyclerView
    private fun count(value: Int) = await { ui { list().adapter!!.itemCount == value } }
    private fun playingName(name: String) = await(45000) {
        ui { a.activePlayer?.mediaMetadata?.title?.toString() == name && JSONObject(a.playbackInfo).optBoolean("playing") }
    }
    private fun previewChannel(id: String, name: String) {
        ui { a.window.decorView.findTag("channel:$id")!!.requestFocus() }
        device.pressDPadCenter(); playingName(name)
        assertTrue("First OK should open the inline preview", tagged("channels")?.isShown == true)
        assertTrue("Inline preview should leave focus on the channel", ui { a.window.decorView.findTag("channel:$id")?.hasFocus() == true })
    }

    @Before fun setup() {
        control("reset=1")
        ui {
            a.model.showProfiles(); a.model.updatePreferences(Preferences()); a.model.server("http://10.0.2.2:8878")
            a.model.providers.filter { it.id == profile.id }.forEach { a.model.removeProvider(it.id) }
            a.model.saveProvider(profile); a.model.makeDefaultProfile(profile.id); a.model.open(Route.Channels(profile))
        }
        await { find(profile.name) != null && ui { !a.model.busy && a.model.channels.size == 2 && (a.model.route as? Route.Channels)?.provider?.id == profile.id } }
        count(2); await { tagged("channel:1")?.isShown == true }
    }

    @After fun cleanup() {
        ui { a.model.showProfiles(); a.model.removeProvider(profile.id) }
        await { ui { a.activePlayer == null } }
    }

    @Test fun toolbarHasOneOptionsActionAndFavoritesBelongsToSidebar() {
        val before = links()
        assertNotNull(find("Live TV options"))
        assertNull(find("Refresh channels")); assertNull(find("Manage IPTV profiles")); assertNull(find("Edit categories"))
        assertNotNull(ui { a.window.decorView.findTag("groups")!!.findTag("category:favorites") })
        ui { a.window.decorView.findTag("channel:1")!!.performLongClick() }
        remote("★ Favorites"); count(1)
        await { ui { a.window.decorView.findTag("channel:1")!!.hasFocus() && a.model.onlyFavoriteChannels } }
        remote("★ Favorites only"); count(1)
        await { ui { a.window.decorView.findTag("channel:1")!!.hasFocus() && a.model.onlyFavoriteChannels } }
        remote("All channels"); count(2)
        assertFalse(ui { a.model.onlyFavoriteChannels })
        SystemClock.sleep(850)
        assertEquals("Sidebar selection must not load a stream", before, links()); assertNull(ui { a.activePlayer })
    }
    @Test fun leavingChannelsDismissesCategoryEditingWithoutChangingTheProfileOrStartingPlayback() {
        val before=links()
        liveAction("Edit categories")
        await{device.findObject(By.text("Visible categories"))!=null && ui{!a.hasWindowFocus()}}
        ui{a.model.top(Section.SEARCH)}
        await{ui{a.model.route==Route.Browse(Section.SEARCH) && a.hasWindowFocus()} && device.findObject(By.text("Visible categories"))==null}
        assertTrue(ui{a.model.providers.any{it.id==profile.id}})
        assertEquals(before,links());assertNull(ui{a.activePlayer})
    }

    @Test fun pictureControlsRequireAnActivePreviewAndPreserveTheLiveStream() {
        click("Live TV options")
        await { device.findObject(By.text("Refresh channels"))!=null && ui { !a.hasWindowFocus() } }
        assertNull(device.findObject(By.text("Picture controls")))
        sendRemoteKey(android.view.KeyEvent.KEYCODE_BACK)
        await { ui { a.hasWindowFocus() } && device.findObject(By.text("Refresh channels"))==null }
        previewChannel("1","Fixture News")
        val preview=ui { a.activePlayer }
        val before=links()
        liveAction("Picture controls")
        await { device.findObject(By.desc("Picture controls"))!=null && ui { !a.hasWindowFocus() } }
        assertSame(preview,ui { a.activePlayer })
        assertTrue(tagged("channels")?.isShown==true)
        assertTrue(tagged("vod-controls")?.isShown!=true)
        click("Done")
        await { device.findObject(By.desc("Picture controls"))==null && ui { a.hasWindowFocus() && find("Live TV options")?.hasFocus()==true } }
        assertSame(preview,ui { a.activePlayer });assertEquals(before,links())
        playingName("Fixture News")
    }

    @Test fun browsingNeverTunesAndOkPreviewsBeforeOpeningFullscreen() {
        val before = links()
        ui { a.window.decorView.findTag("channel:1")!!.requestFocus() }
        repeat(4) { device.pressDPadDown(); device.pressDPadUp() }
        remote("Sports")
        count(1)
        await { ui { a.model.channelGroup == "Sports" && a.window.decorView.findTag("channel:2")?.hasFocus() == true } }
        SystemClock.sleep(850)
        assertEquals("Moving focus/category selection opened a stream", before, links()); assertNull(ui { a.activePlayer })
        // The category's OK only moves into its list; the channel's first OK previews.
        previewChannel("2", "Fixture Sports")
        assertEquals(before + 1, links()); val sports = ui { a.activePlayer }
        device.pressDPadCenter()
        await { tagged("channels")?.isShown != true }; assertSame(sports, ui { a.activePlayer })
        device.pressBack(); await { tagged("channels")?.isShown == true }
        remote("All channels"); count(2)
        await { ui { a.window.decorView.findTag("channel:1")?.hasFocus() == true } }
        SystemClock.sleep(850)
        assertSame("Browsing another row replaced the preview", sports, ui { a.activePlayer }); assertEquals(before + 1, links())
        previewChannel("1", "Fixture News")
        assertNotSame(sports, ui { a.activePlayer }); assertEquals(before + 2, links())
    }

    @Test fun searchHasOneClearActionAndHiddenCategoriesHaveDirectRecovery() {
        val before = links()
        ui { (a.window.decorView.findTag("field:Search channels") as EditText).requestFocus() }
        field("Search channels", "no-such-channel"); count(0)
        await { find("No channels found") != null }
        val clearActions = ui { descendants(a.window.decorView).count { it.isShown && it.isClickable && it.contentDescription == "Clear channel search" } }
        assertEquals("Clear was duplicated in the empty state", 1, clearActions)
        remote("Clear channel search"); count(2); assertEquals("", ui { a.model.channelQuery })
        ui {
            a.model.setCategoryVisible(profile.id, "News", false)
            a.model.setCategoryVisible(profile.id, "Sports", false)
        }
        count(0); await { find("All categories hidden") != null }
        remote("Edit categories")
        await { device.findObject(By.clazz("android.widget.CheckedTextView").text("News")) != null }
        click("Show all"); await { ui { a.model.hiddenCategories[profile.id].isNullOrEmpty() } }; click("Done")
        count(2); await { tagged("channel:1")?.isShown == true }
        SystemClock.sleep(850)
        assertEquals("Filtering or restoring categories opened a stream", before, links()); assertNull(ui { a.activePlayer })
    }

    @Test fun favoritesInHiddenCategoriesKeepTheirSavedStateAndOfferRecovery() {
        val before=links()
        ui {
            val channel=a.model.channels.first { it.id=="1" }
            if(favoriteKey(profile.id,channel) !in a.model.favorites)a.model.toggleFavorite(profile,channel)
            a.model.setCategoryVisible(profile.id,"News",false)
        }
        remote("★ Favorites");count(0)
        await { find("Favorites are hidden")!=null };assertNull(find("No favorites yet"))
        remote("Edit categories")
        await { device.findObject(By.clazz("android.widget.CheckedTextView").text("News"))!=null }
        click("Show all");click("Done")
        count(1);await { tagged("channel:1")?.isShown==true }
        assertTrue(ui { favoriteKey(profile.id,a.model.channels.first{it.id=="1"}) in a.model.favorites })
        assertEquals(before,links());assertNull(ui { a.activePlayer })
    }

    @Test fun cancellingOptionsPreservesFocusAndPlayer() {
        previewChannel("1", "Fixture News")
        val player = ui { a.activePlayer }; val before = links()
        remote("Live TV options")
        await { device.findObject(By.text("Refresh channels")) != null }
        device.pressBack()
        await { ui { a.window.decorView.findTag("button:Live TV options")?.hasFocus() == true } }
        SystemClock.sleep(800)
        assertSame(player, ui { a.activePlayer }); assertEquals(before, links())
    }

    @Test fun fullscreenBackFindsThePlayingChannelAfterItsViewWasRecycled() {
        control("large_channels=1"); ui { a.model.retry() }
        await { ui { !a.model.busy && a.model.channels.size == 6000 } }; count(6000)
        await { tagged("channel:1")?.isShown == true }
        previewChannel("1", "Channel 1")
        val player = ui { a.activePlayer }; val before = links()
        ui {
            a.window.decorView.findTag("button:Live TV options")!!.requestFocus()
            list().scrollToPosition(120)
        }
        await { ui { list().findViewHolderForAdapterPosition(0) == null && list().findViewHolderForAdapterPosition(120) != null } }
        remote("Full screen Channel 1")
        device.pressBack()
        await { tagged("channels")?.isShown == true && ui { a.window.decorView.findTag("channel:1")?.hasFocus() == true } }
        SystemClock.sleep(850)
        assertSame(player, ui { a.activePlayer }); assertEquals(before, links())
        assertEquals("Channel 1", ui { a.activePlayer?.mediaMetadata?.title?.toString() })
    }
}
