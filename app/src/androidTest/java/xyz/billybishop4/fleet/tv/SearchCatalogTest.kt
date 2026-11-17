package xyz.billybishop4.fleet.tv

import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

/** Installed protocol fixtures only; no production catalogs, account data or TV discovery. */
class SearchCatalogTest : NativeTestBase() {
    private val base = "http://10.0.2.2:8878"
    private val primary = "$base/addons/catalogs/manifest.json?profile=fixture-private-profile"
    private val secondary = "$base/addons/catalogs/manifest.json?profile=fixture-second-profile"
    private fun audit() = JSONObject(runBlocking { Http().text("$base/__state") })
    @Before fun prepare() {
        runBlocking { Http().text("$base/__control?reset=1") }
        ui {
            a.model.showProfiles(); a.model.updatePreferences(Preferences()); a.model.server(base); a.model.clearHistory()
            a.model.saved.toList().forEach(a.model::toggle); a.model.installAddon(primary)
        }
        await { ui { !a.model.busy && a.model.addons.any { it.manifestUrl == primary } } }
        ui { a.model.top(Section.SEARCH); a.model.search("") }
        await { tagged("catalog-filters")?.isShown == true && ui { !a.model.busy && a.model.catalogDefinitions.any { it.addonName == "Fixture Catalogs" } } }
    }
    @After fun finish() {
        ui {
            a.model.showProfiles(); a.model.clearHistory(); a.model.saved.toList().forEach(a.model::toggle)
            a.model.addons.filter { it.manifestUrl in setOf(primary, secondary) }.forEach { a.model.removeAddon(it.manifestUrl) }
        }
    }
    private fun choose(label: String) {
        val selector = By.clazz("android.widget.CheckedTextView").text(label)
        assertTrue("Native choice list was not shown", device.wait(Until.hasObject(By.clazz("android.widget.ListView")), 10000) == true)
        if (device.findObject(selector) == null) {
            UiScrollable(UiSelector().className("android.widget.ListView")).apply {
                setAsVerticalList(); setMaxSearchSwipes(12)
            }.scrollIntoView(UiSelector().className("android.widget.CheckedTextView").text(label))
        }
        val option = device.wait(Until.findObject(selector), 10000)
        if (option == null) {
            val dir = ui { a.getExternalFilesDir(null)!! }
            device.dumpWindowHierarchy(File(dir, "failure-catalog-choice.xml")); screenshot("catalog-choice-missing")
        }
        assertNotNull("Missing advertised choice: $label", option)
        option!!.click()
    }
    private fun definition(name: String, type: String = "movie") = ui {
        a.model.catalogDefinitions.first { it.addonName == "Fixture Catalogs" && it.name == name && it.type == type }
    }
    private fun selectCatalog(catalog: CatalogDefinition) {
        val choices = ui { a.model.catalogDefinitions.filter { it.canBrowse && (a.model.catalogFilter.type.isEmpty() || it.type == a.model.catalogFilter.type) } }
        click("Catalog"); choose(catalogLabel(catalog, choices))
        await { ui { a.model.catalogFilter.catalogKey == catalog.key && !a.model.busy && a.hasWindowFocus() } }
    }
    private fun rows(tag: String): List<HomeRow> = ui {
        ((a.window.decorView.findTag(tag) as RecyclerView).adapter as NativeAdapter<*>).currentList.filterIsInstance<HomeRow>()
    }
    private fun focusedShelf(): String? = ui {
        var view = a.currentFocus
        while (view != null) { if (view is ShelfCard) return@ui view.shelfKey; view = view.parent as? View }
        null
    }

    @Test fun catalogYearAndGenreChoicesUseTheirManifestAndCancelOlderResponses() {
        click("Catalog type"); choose("Movies")
        await { ui { a.model.catalogFilter.type == "movie" && !a.model.busy } }
        selectCatalog(definition("New"))
        assertEquals("2026", ui { a.model.catalogFilter.extras["genre"] })
        click("Year"); choose("2025")
        await { ui { !a.model.busy && a.model.shelves.singleOrNull()?.titles?.all { it.name.contains("2025") } == true } }
        assertTrue(audit().getJSONArray("catalog_requests").objects().any {
            it.optString("kind") == "catalogs" && it.optString("id") == "year" && it.getJSONObject("extras").optString("genre") == "2025"
        })
        screenshot("catalog-year")
        selectCatalog(definition("Popular"))
        click("Genre")
        assertTrue(device.wait(Until.hasObject(By.text("Action")), 10000) == true)
        assertNull(device.findObject(By.text("Horror")))
        val previous = audit().getJSONArray("catalog_requests").length()
        choose("Action")
        await { audit().getJSONArray("catalog_requests").objects().drop(previous).any {
            it.optString("kind") == "catalogs" && it.getJSONObject("extras").optString("genre") == "Action"
        } }
        ui { a.model.selectCatalog(a.model.catalogFilter.copy(extras = mapOf("genre" to "Comedy"))) }
        await { ui { !a.model.busy && a.model.shelves.singleOrNull()?.titles?.all { it.name.contains("Comedy") } == true } }
        android.os.SystemClock.sleep(1200)
        assertTrue(ui { a.model.shelves.flatMap { it.titles }.all { it.name.contains("Comedy") } })
        assertEquals("", ui { a.model.searchText })
        rule.scenario.recreate()
        await { tagged("catalog-filter:genre")?.isShown == true }
        assertEquals("Comedy", ui { a.model.catalogFilter.extras["genre"] })
        val exposed = ui { descendants(a.window.decorView).filter { it.isShown }.joinToString(" ") {
            listOf(it.tag?.toString(), it.contentDescription?.toString(), (it as? TextView)?.text?.toString()).joinToString(" ")
        } }
        assertFalse(exposed.contains("fixture-private-profile"))
        screenshot("catalog-genre")
    }

    @Test fun groupedSearchKeepsLocalShelvesAndRestoresQueryPosterFocusOnBack() {
        val continuing = Title("tt-local-continue", "Local continue fixture")
        val saved = Title("tt-local-saved", "Local saved fixture")
        ui {
            a.model.saveProgress(Watch(continuing, Stream("Fixture", "$base/hls/fixture/orig.m3u8")), 4000, 60000)
            a.model.toggle(saved)
        }
        await { rows("search-home").map { it.name }.containsAll(listOf("Continue watching", "My list")) }
        field("Search movies and shows", "F")
        await { tagged("catalog-filters")?.isShown == false }
        assertEquals("F", ui { a.model.searchText })
        field("Search movies and shows", "Fleet"); click("Submit search")
        await { ui { !a.model.busy && a.model.searchShelves.count { it.titles.isNotEmpty() } == 4 } && rows("search-results").size == 4 }
        assertEquals(2, ui { a.model.searchShelves.count { it.name.contains("Movies") } })
        assertEquals(2, ui { a.model.searchShelves.count { it.name.contains("Series") } })
        val first = ui { a.model.searchShelves.first().titles.first() }
        await { ui { (a.window.decorView.findTag("search-results") as RecyclerView).getChildAt(0)?.let { it as? ShelfCard }?.focusTitle("title:${first.key}")?.hasFocus() == true } }
        val context = focusedShelf()
        assertNotNull(context); screenshot("catalog-grouped-search")
        sendRemoteKey(KeyEvent.KEYCODE_DPAD_CENTER)
        await { ui { a.model.route is Route.Detail || a.model.route is Route.Sources } }
        sendRemoteKey(KeyEvent.KEYCODE_BACK)
        await { ui { a.model.route == Route.Browse(Section.SEARCH) && a.model.searchText == "Fleet" && !a.model.busy && a.currentFocus?.tag == "title:${first.key}" } }
        assertEquals(context, focusedShelf())
        assertEquals("Fleet", ui { (tagged("field:Search movies and shows") as EditText).text.toString() })
        sendRemoteKey(KeyEvent.KEYCODE_BACK)
        await { ui { a.model.searchText.isEmpty() } && tagged("catalog-filters")?.isShown == true }
        assertTrue(rows("search-home").map { it.name }.containsAll(listOf("Continue watching", "My list")))
    }

    @Test fun sameAddonConfigurationsHaveDistinctCatalogChoicesWithoutExposingTheirUrls() {
        val old = ui { a.model.catalogDefinitions.filter { it.addonName == "Fixture Catalogs" && it.name == "New" }.single() }
        val previousControl = tagged("catalog-filter:catalog")
        ui { a.model.installAddon(secondary) }
        await { ui { !a.model.busy && a.model.catalogDefinitions.count { it.addonName == "Fixture Catalogs" && it.name == "New" } == 2 } }
        await { ui {
            val control = tagged("catalog-filter:catalog")
            control != null && control !== previousControl && control.isShown && control.isLaidOut && !control.isLayoutRequested && a.hasWindowFocus()
        } }
        val candidates = ui { a.model.catalogDefinitions.filter { it.addonName == "Fixture Catalogs" && it.name == "New" } }
        assertEquals(2, candidates.map { it.key }.distinct().size)
        val added = candidates.first { it.key != old.key }
        selectCatalog(added)
        assertTrue(ui { a.model.shelves.single().name.contains("Configuration 2") })
        assertTrue(ui { a.model.addons.filter { it.manifestUrl in setOf(primary, secondary) }.all { it.enabled } })
        assertEquals(setOf(primary, secondary), ui { Store(a).addons().getOrThrow().filter { it.manifestUrl in setOf(primary, secondary) }.map { it.manifestUrl }.toSet() })
        val exposed = ui { descendants(a.window.decorView).filter { it.isShown }.joinToString(" ") { it.contentDescription?.toString().orEmpty() + it.tag.toString() } }
        assertFalse(exposed.contains("fixture-private-profile")); assertFalse(exposed.contains("fixture-second-profile"))
        screenshot("catalog-configurations")
    }
}
