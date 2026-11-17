package xyz.billybishop4.fleet.tv

import android.widget.EditText
import android.widget.TextView
import android.view.inputmethod.EditorInfo
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.UUID

/** Remote-emulator settings and form flows; no production accounts or TV discovery. */
class SettingsNavigationTest : NativeTestBase() {
    private val fixtureIds = HashSet<String>()
    private val extra = "http://10.0.2.2:8878/addons/extra/manifest.json?token=fixture-private-token"
    private val otherExtra = "http://10.0.2.2:8878/addons/extra/manifest.json?token=fixture-other-token"
    @Before fun prepare() {
        ui {
            a.model.discardProviderDraft(); a.model.showProfiles()
            a.model.updatePreferences(Preferences()); a.model.server("http://10.0.2.2:8878"); a.model.setAddonDraft("")
        }
    }
    @After fun cleanUp() {
        ui {
            a.model.discardProviderDraft(); a.model.showProfiles()
            fixtureIds.forEach(a.model::removeProvider)
            a.model.addons.filter { it.manifestUrl in listOf(extra, otherExtra) }.forEach { a.model.removeAddon(it.manifestUrl) }
            a.model.setAddonDraft("")
        }
    }
    private fun openSettings() {
        ui { a.model.open(Route.Settings) }
        await { tagged("settings-list")?.isShown == true }
    }
    private fun choose(label: String) {
        val selector = By.clazz("android.widget.CheckedTextView").text(label)
        assertTrue("Choice was not shown: $label", device.wait(Until.hasObject(selector), 10000) == true)
        device.findObject(selector).click()
    }
    private fun profile(name: String): Provider = Provider(UUID.randomUUID().toString(), name,
        "http://10.0.2.2:8878/stalker_portal/", mac = "00:1A:79:00:00:01").also { fixtureIds.add(it.id) }
    private fun openAddons() {
        openSettings(); click("On demand"); click("Manage add-ons")
        await { tagged("addons-screen")?.isShown == true && ui { !a.model.busy } }
    }
    private fun addonOptions(url: String) {
        val tag = addonOptionTag(url)
        await { tagged(tag)?.isShown == true && ui { a.hasWindowFocus() && !a.model.busy } }
        ui { tagged(tag)!!.apply { requestFocus(); performClick() } }
    }
    private fun install(url: String, useIme: Boolean = false) {
        field("Manifest URL", url)
        if (useIme) ui { (tagged("field:Manifest URL") as EditText).onEditorAction(EditorInfo.IME_ACTION_GO) }
        else click("Install add-on")
        await { ui { !a.model.busy && a.model.addons.any { it.manifestUrl == url } } }
        await { ui { (tagged("field:Manifest URL") as EditText).text.isBlank() && a.hasWindowFocus() } }
    }

    @Test fun choiceCancelAndSingleSelectionRestoreTheSettingFocus() {
        openSettings(); click("Playback")
        await { tagged("setting:Video fit")?.isShown==true };android.os.SystemClock.sleep(200);screenshot("settings-playback")
        assertNull(tagged("setting:Video engine"));assertNull(find("Video engine"))
        click("Video fit"); device.pressBack()
        await { ui { a.window.decorView.findTag("setting:Video fit")?.hasFocus() == true } }
        assertEquals("Fit", ui { a.model.preferences.videoFit })
        click("Video fit"); choose("Fill")
        await { ui { a.model.preferences.videoFit == "Fill" && a.window.decorView.findTag("setting:Video fit")?.hasFocus() == true } }
        click("On demand"); click("Playback speed"); choose("1.25×")
        await { ui { a.model.preferences.speed == 1.25f && a.window.decorView.findTag("setting:Playback speed")?.hasFocus() == true } }
        assertEquals(1.25f, ui { Store(a).preferences().speed })
    }

    @Test fun startupScreenDefaultsToOnDemandAndSavedChoiceControlsColdStart() {
        val p=profile("Startup fixture")
        ui {
            a.model.saveProvider(p);a.model.makeDefaultProfile(p.id)
            val legacy=a.model.preferences.json().apply { remove("startupScreen");put("startSection","Live TV") }
            a.getSharedPreferences("fleet-tv",0).edit().putString("preferences",legacy.toString()).commit()
        }
        fun coldStart(section:Section) {
            ui {
                val owner=androidx.lifecycle.ViewModelStore()
                try {
                    val fresh=androidx.lifecycle.ViewModelProvider(owner,
                        androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory(a.application))[FleetModel::class.java]
                    assertEquals(section,fresh.section)
                    if(section==Section.LIVE)assertEquals(p.id,(fresh.route as Route.Channels).provider.id)
                    else assertEquals(Route.Browse(Section.SEARCH),fresh.route)
                    assertEquals(p.id,fresh.defaultProfile?.id)
                } finally { owner.clear() }
            }
        }
        coldStart(Section.SEARCH)
        openSettings();click("Appearance")
        click("Startup screen");choose("Live TV")
        await { ui { a.model.preferences.startSection=="Live TV" && tagged("setting:Startup screen")?.hasFocus()==true } }
        assertEquals(Route.Settings,ui { a.model.route })
        assertEquals("Live TV",ui { Store(a).preferences().startSection })
        coldStart(Section.LIVE)
        click("Startup screen");choose("On-Demand")
        await { ui { a.model.preferences.startSection=="On-Demand" && tagged("setting:Startup screen")?.hasFocus()==true } }
        assertEquals(Route.Settings,ui { a.model.route })
        rule.scenario.recreate()
        await { ui { a.model.route==Route.Settings && find("Appearance")?.isSelected==true && tagged("setting:Startup screen")?.isShown==true } }
        coldStart(Section.SEARCH)
        click("Startup screen")
        assertTrue(device.wait(Until.hasObject(By.clazz("android.widget.CheckedTextView").text("On-Demand").checked(true)),10000)==true)
        device.pressBack()
        await { ui { tagged("setting:Startup screen")?.hasFocus()==true } }
        assertEquals(p.id,ui { a.model.preferences.defaultProfileId })
        screenshot("startup-screen-setting")
    }

    @Test fun settingsCategoryAndManifestDraftSurviveProfilesBackAndActivityRecreation() {
        val p = profile("Settings return fixture")
        ui { a.model.saveProvider(p); a.model.top(Section.SEARCH) }
        openAddons()
        click("Install add-on")
        await { ui { tagged("addons-status")?.isShown == true && tagged("field:Manifest URL")?.hasFocus() == true } }
        assertEquals("Enter a manifest URL.", ui { (tagged("addons-status") as TextView).text.toString() })
        field("Manifest URL", "not a manifest address"); click("Install add-on")
        await { ui { (tagged("addons-status") as TextView).text.contains("HTTP") && tagged("field:Manifest URL")?.hasFocus() == true } }
        assertFalse(ui { a.window.decorView.findTag("error")?.isShown == true })
        val draft = "$extra&draft=unsaved"
        field("Manifest URL", draft)
        await { tagged("addons-status")?.isShown == false }
        device.pressBack()
        await { ui { a.model.route == Route.Settings && find("On demand")?.isSelected == true } }
        click("Live TV settings"); click("Manage IPTV profiles")
        await { ui { a.model.route == Route.Browse(Section.LIVE) && a.model.section == Section.LIVE } }
        device.pressBack()
        await { ui { a.model.route == Route.Settings && a.model.section == Section.SEARCH && find("Live TV settings")?.isSelected == true } }
        rule.scenario.recreate()
        await { ui { a.model.route == Route.Settings && find("Live TV settings")?.isSelected == true } }
        click("On demand"); click("Manage add-ons")
        await { tagged("field:Manifest URL") != null }
        assertEquals(draft, ui { (tagged("field:Manifest URL") as EditText).text.toString() })
        assertTrue(ui { a.model.addons.none { it.manifestUrl == draft } })
        rule.scenario.recreate()
        await { tagged("field:Manifest URL") != null }
        assertEquals(draft, ui { (tagged("field:Manifest URL") as EditText).text.toString() })
        device.pressBack()
        await { ui { a.model.route == Route.Settings && find("On demand")?.isSelected == true } }
        assertNull(find("Fleet server")); assertNull(find("Default stream mode")); assertNull(find("Compatible playback fallback"))
        screenshot("settings-rail")
    }

    @Test fun configuredAddonsEnableAndRemoveIndependentlyAndPersistWithoutExposingSecrets() {
        openAddons(); install(extra, useIme = true); install(otherExtra)
        assertEquals(2, ui { a.model.addons.filter { it.manifestUrl in listOf(extra, otherExtra) }.size })
        assertEquals(1, ui { a.model.addons.filter { it.manifestUrl in listOf(extra, otherExtra) }.map { it.id }.distinct().size })
        await { find("Fixture Extra · Configuration 1") != null && find("Fixture Extra · Configuration 2") != null }
        assertEquals("Options for Fixture Extra · Configuration 1", ui { tagged(addonOptionTag(extra))?.contentDescription?.toString() })
        assertEquals("Options for Fixture Extra · Configuration 2", ui { tagged(addonOptionTag(otherExtra))?.contentDescription?.toString() })
        screenshot("addons-configurations")
        val visible = ui { descendants(a.window.decorView).filterIsInstance<TextView>().filter { it.isShown }.joinToString(" ") { it.text } }
        assertFalse(visible.contains("fixture-private-token")); assertFalse(visible.contains("fixture-other-token"))
        assertFalse(ui { a.getSharedPreferences("fleet-tv", 0).all.values.any { it.toString().contains("fixture-private-token") } })
        addonOptions(extra); click("Disable add-on")
        await { ui { Store(a).addons().getOrThrow().first { it.manifestUrl == extra }.enabled == false && a.hasWindowFocus() } }
        assertTrue(ui { Store(a).addons().getOrThrow().first { it.manifestUrl == otherExtra }.enabled })
        rule.scenario.recreate()
        await { tagged(addonOptionTag(extra))?.isShown == true }
        assertFalse(ui { a.model.addons.first { it.manifestUrl == extra }.enabled })
        addonOptions(extra); click("Enable add-on")
        await { ui { Store(a).addons().getOrThrow().first { it.manifestUrl == extra }.enabled && a.hasWindowFocus() } }
        val previousStatus = ui { a.model.addonStatus }
        addonOptions(extra); click("Refresh add-on")
        await { ui { !a.model.busy && a.model.error == null && a.model.addonStatus != null && a.model.addonStatus != previousStatus && a.hasWindowFocus() } }
        addonOptions(extra); click("Remove add-on")
        assertTrue(device.wait(Until.hasObject(By.text("Remove Fixture Extra · Configuration 1?")), 10000) == true)
        click("Cancel")
        await { ui { a.hasWindowFocus() } }; assertTrue(ui { a.model.addons.any { it.manifestUrl == extra } })
        addonOptions(extra); click("Remove add-on")
        assertTrue(device.wait(Until.hasObject(By.text("Remove Fixture Extra · Configuration 1?")), 10000) == true)
        click("Remove add-on")
        await { ui { a.model.addons.none { it.manifestUrl == extra } && a.hasWindowFocus() } }
        assertTrue(ui { Store(a).addons().getOrThrow().any { it.manifestUrl == otherExtra && it.enabled } })
        assertFalse(ui { Store(a).addons().getOrThrow().any { it.manifestUrl == extra } })
        screenshot("addons-managed")
    }

    @Test fun resetAddonsRestoresFixtureDefaultsOnlyAfterConfirmation() {
        openAddons(); install(extra)
        val prompt = By.text("Restore default add-ons? Custom add-ons will be removed.")
        fun answerReset(buttonId: String) {
            click("Reset add-ons")
            assertTrue(device.wait(Until.hasObject(prompt), 10000) == true)
            await { ui { !a.hasWindowFocus() } }
            val button = By.res("android:id/$buttonId")
            assertTrue(device.wait(Until.hasObject(button), 10000) == true)
            device.findObject(button).click()
            await { device.findObject(prompt) == null && ui { a.hasWindowFocus() } }
        }
        answerReset("button2")
        assertTrue(ui { a.model.addons.any { it.manifestUrl == extra } })
        answerReset("button1")
        val defaults = setOf("http://10.0.2.2:8878/addons/cinemeta/manifest.json", "http://10.0.2.2:8878/addons/gdrive/manifest.json")
        await { ui { !a.model.busy && a.model.addons.map { it.manifestUrl }.toSet() == defaults && a.hasWindowFocus() } }
        assertEquals(defaults, ui { Store(a).addons().getOrThrow().map { it.manifestUrl }.toSet() })
        assertTrue(ui { a.model.addons.all { it.enabled } })
    }

    @Test fun requiredValidationFocusesTheFieldAndAdvancedDraftSurvivesSettings() {
        ui { a.model.open(Route.AddProvider) }
        await { tagged("field:Portal URL") != null }
        click("Save profile")
        await { ui { a.model.error != null && a.window.decorView.findTag("field:Portal URL")?.hasFocus() == true } }
        field("Portal URL", "http://10.0.2.2:8878/stalker_portal/")
        await { ui { a.model.error == null } }
        click("Save profile")
        await { ui { a.model.error != null && a.window.decorView.findTag("field:MAC address")?.hasFocus() == true } }
        field("MAC address", "00:1A:79:00:00:01"); click("Advanced settings")
        field("Device ID (optional)", "fixture-draft-device")
        field("Timezone (optional)", "Invalid/Timezone"); click("Save profile")
        await { ui { a.model.error != null && a.window.decorView.findTag("field:Timezone (optional)")?.hasFocus() == true } }
        val draftId = ui { a.model.providerDraft!!.id }
        click("Settings"); device.pressBack()
        await { tagged("field:Device ID (optional)") != null }
        assertEquals("fixture-draft-device", ui { (a.window.decorView.findTag("field:Device ID (optional)") as EditText).text.toString() })
        assertEquals("Invalid/Timezone", ui { a.model.providerDraft!!.timezone })
        field("Timezone (optional)", ""); await { ui { a.model.error == null } }
        screenshot("provider-required-advanced")
        click("Cancel editing")
        await { ui { a.model.providerDraft == null && a.model.providers.none { it.id == draftId } } }
    }

    @Test fun playbackResetKeepsTheDefaultProfileAndProviderData() {
        val first = profile("Settings first fixture"); val second = profile("Settings default fixture")
        ui {
            a.model.saveProvider(first); a.model.saveProvider(second); a.model.makeDefaultProfile(second.id)
            a.model.updatePreferences(a.model.preferences.copy(largePosters = true, videoFit = "Zoom", speed = 1.5f))
        }
        openSettings(); click("Data & storage"); click("Reset playback and appearance"); click("Confirm reset")
        await { ui { !a.model.preferences.largePosters && a.model.preferences.videoFit == "Fit" && a.model.preferences.speed == 1f } }
        assertEquals(second.id, ui { a.model.preferences.defaultProfileId })
        assertEquals(second.id, ui { a.model.defaultProfile?.id })
        assertTrue(ui { Store(a).providers().getOrThrow().map { it.id }.containsAll(listOf(first.id, second.id)) })
    }
}
