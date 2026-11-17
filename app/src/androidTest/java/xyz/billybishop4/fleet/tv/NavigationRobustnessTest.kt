@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package xyz.billybishop4.fleet.tv

import android.os.SystemClock
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

/** Navigation contracts that used to add presses, erase state or return stale profiles. */
class NavigationRobustnessTest:NativeTestBase() {
    private val p=Provider("navigation-fixture","Navigation fixture","http://10.0.2.2:8878/stalker_portal/",mac="00:1A:79:00:00:01")
    private fun audit()=JSONObject(runBlocking { Http().text("http://10.0.2.2:8878/__state") })
    @Before fun setup() {
        runBlocking { Http().text("http://10.0.2.2:8878/__control?reset=1") }
        ui {
            a.model.showProfiles();a.model.resetProviders();a.model.clearHistory()
            a.model.saved.toList().forEach { a.model.toggle(it) }
            a.model.updatePreferences(Preferences());a.model.server("http://10.0.2.2:8878")
        }
    }
    @After fun stop() { ui { a.model.showProfiles();a.model.discardProviderDraft() } }
    private fun rootChannels() {
        ui { a.model.saveProvider(p);a.model.makeDefaultProfile(p.id);a.model.top(Section.LIVE) }
        await { find(p.name)!=null && tagged("channel:1")?.isShown==true && ui{!a.model.busy && (a.model.route as? Route.Channels)?.provider==p} }
    }
    @Test fun savingFirstProfileOpensItsChannelsWithoutASecondSelection() {
        click("Add profile");field("Portal URL",p.url);field("MAC address",p.mac);field("Profile name (optional)",p.name)
        click("Save profile")
        await { find(p.name)!=null && tagged("channel:1")?.isShown==true && ui{!a.model.busy} }
        assertEquals(p.name,ui { (a.model.route as Route.Channels).provider.name })
        assertEquals(ui { (a.model.route as Route.Channels).provider.id },ui { a.model.preferences.defaultProfileId })
    }
    @Test fun reselectingRootLiveTabKeepsThePlayerAndRootBackDoesNotOpenProfiles() {
        rootChannels();ui { a.window.decorView.findTag("channel:1")!!.apply { requestFocus();performClick() } }
        await(45000) { JSONObject(ui { a.playbackInfo }).optBoolean("playing") }
        val player=ui { a.activePlayer };val links=audit().getInt("portal_links")
        ui { a.window.decorView.findTag("navigation:Live TV")!!.requestFocus() };device.pressDPadCenter();SystemClock.sleep(850)
        assertSame(player,ui { a.activePlayer });assertEquals(links,audit().getInt("portal_links"))
        assertFalse("Root Back unexpectedly opened profile management",ui { a.model.back() })
        assertTrue(ui { a.model.route is Route.Channels })
    }
    @Test fun editedProfileReturnsToChannelsWithUpdatedIdentity() {
        rootChannels();liveAction("Manage IPTV profiles");profileAction(p.name,"Edit profile")
        field("Profile name (optional)","Updated navigation fixture");click("Advanced settings");field("Device ID (optional)","updated-fixture-identity")
        click("Save profile");await { find("Updated navigation fixture")!=null && ui { a.model.route==Route.Browse(Section.LIVE) } }
        device.pressBack()
        await { ui { (a.model.route as? Route.Channels)?.provider?.name=="Updated navigation fixture" } }
        assertEquals("updated-fixture-identity",ui { (a.model.route as Route.Channels).provider.deviceId })
        await { find("Updated navigation fixture")!=null && tagged("channels")?.isShown==true }
    }
    @Test fun deletingAnEditedProfileClearsItsDraftAndBackHasAValidDestination() {
        ui { a.model.saveProvider(p) };await { find(p.name)!=null };profileAction(p.name,"Edit profile")
        await { ui { a.model.providerDraft?.id==p.id } };click("Settings");click("Live TV settings");click("Manage IPTV profiles")
        deleteProfile(p.name);await { ui { a.model.providers.isEmpty() && a.model.providerDraft==null } }
        device.pressBack();await { ui { a.model.route==Route.Settings } }
        device.pressBack();await { ui { a.model.route==Route.Browse(Section.LIVE) } }
        assertNotNull(find("Add profile"))
    }
    @Test fun emptySearchResultsSurviveBackWithoutAnotherRequestAndClearInOneAction() {
        ui { a.model.top(Section.SEARCH) };await { firstMovieCard()!=null && ui{!a.model.busy} }
        field("Search movies and shows","No results fixture");await { find("No results")!=null && ui{!a.model.busy} }
        val searches=audit().getInt("search_requests")
        click("Settings");device.pressBack();await { tagged("search-results")?.isShown==true && find("No results")!=null }
        SystemClock.sleep(500);assertEquals(searches,audit().getInt("search_requests"))
        click("Clear search");await { tagged("search-home")?.isShown==true && firstMovieCard()!=null }
        assertEquals("",ui { a.model.searchText })
    }
}
