package xyz.billybishop4.fleet.tv

import androidx.recyclerview.widget.RecyclerView
import androidx.test.uiautomator.By
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

class CategoryVisibilityTest : NativeTestBase() {
    private val primary = Provider("visibility-primary","Category fixture","http://10.0.2.2:8878/stalker_portal/",mac="00:1A:79:00:00:01")
    private val second = primary.copy(id="visibility-second",name="Second category fixture")
    @Before fun setup() {
        runBlocking { Http().text("http://10.0.2.2:8878/__control?reset=1") }
        ui {
            a.model.showProfiles();a.model.server("http://10.0.2.2:8878")
            a.model.providers.filter { it.id.startsWith("visibility-") }.forEach { a.model.removeProvider(it.id) }
            a.model.saveProvider(primary);a.model.saveProvider(second)
        }
        open(primary)
    }
    @After fun cleanup() { ui { a.model.showProfiles();a.model.providers.filter { it.id.startsWith("visibility-") }.forEach { a.model.removeProvider(it.id) } } }
    private fun open(provider:Provider) {
        ui { a.model.open(Route.Channels(provider)) }
        await { find(provider.name)!=null && ui { !a.model.busy && a.model.channels.size==2 } && tagged("channels")?.isShown==true }
    }
    private fun count(expected:Int) { await { ui { (a.window.decorView.findTag("channels") as? RecyclerView)?.adapter?.itemCount==expected } } }
    private fun category(name:String) = device.findObject(By.clazz("android.widget.CheckedTextView").text(name))
    private fun editor() { liveAction("Edit categories");await { category("News")!=null && category("Sports")!=null } }
    private fun dialogButton(name:String) { val selector=By.text(java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(name),java.util.regex.Pattern.CASE_INSENSITIVE));await { device.findObject(selector)!=null };device.findObject(selector).click() }

    @Test fun hideUnhidePersistsAndDoesNotAffectAnotherProfile() {
        count(2)
        ui { a.model.channels.forEach { a.model.toggleFavorite(primary,it) } }
        editor();assertTrue(category("News").isChecked)
        // The list initially selects News; a real D-pad activation toggles it.
        device.pressDPadCenter()
        await { ui { "News" in a.model.hiddenCategories[primary.id].orEmpty() } }
        assertFalse(category("News").isChecked);screenshot("category-visibility");dialogButton("Done")
        count(1);await { tagged("button:News")==null && tagged("button:Sports")!=null }
        field("Search channels","Morning");count(0);field("Search channels","");count(1)
        click("★ Favorites");count(1)
        rule.scenario.recreate()
        await { find(primary.name)!=null };count(1)
        assertEquals(setOf("News"),ui { Store(a).hiddenCategories()[primary.id] })
        open(second);count(2);await { tagged("button:News")!=null };assertNull(ui { Store(a).hiddenCategories()[second.id] })
        open(primary);count(1);editor();assertFalse(category("News").isChecked)
        device.pressDPadCenter();await { ui { a.model.hiddenCategories[primary.id].isNullOrEmpty() } };dialogButton("Done")
        count(2);await { tagged("button:News")!=null };assertNull(ui { Store(a).hiddenCategories()[primary.id] })
    }
    @Test fun hideSelectedAndAllCategoriesThenRestoreAndCleanUpProfileData() {
        remote("Sports");await { ui { a.model.channelGroup=="Sports" } };count(1)
        editor();category("Sports").click()
        await { ui { a.model.channelGroup=="All channels" } }
        category("News").click();dialogButton("Done");count(0)
        await { find("All categories hidden")!=null };assertNotNull(find("Edit categories"))
        editor();assertFalse(category("News").isChecked);assertFalse(category("Sports").isChecked)
        dialogButton("Show all");await { category("News").isChecked && category("Sports").isChecked };dialogButton("Done")
        count(2);await { tagged("button:News")!=null && tagged("button:Sports")!=null }
        ui {
            a.model.setCategoryVisible(primary.id,"News",false);a.model.setCategoryVisible(second.id,"Sports",false)
            a.model.updatePreferences(Preferences())
            assertEquals(setOf("News"),Store(a).hiddenCategories()[primary.id])
            a.model.removeProvider(primary.id)
            assertNull(Store(a).hiddenCategories()[primary.id]);assertEquals(setOf("Sports"),Store(a).hiddenCategories()[second.id])
            assertTrue(a.model.providers.all { it.url.startsWith("http://10.0.2.2:8878") })
            a.model.resetProviders();assertTrue(a.model.hiddenCategories.isEmpty());assertTrue(Store(a).hiddenCategories().isEmpty())
        }
    }
}
