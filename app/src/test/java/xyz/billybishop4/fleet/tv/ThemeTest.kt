package xyz.billybishop4.fleet.tv

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ThemeTest {
    private fun luminance(color: Int): Double {
        fun channel(shift: Int): Double {
            val value = ((color ushr shift) and 255) / 255.0
            return if (value <= .04045) value / 12.92 else ((value + .055) / 1.055).pow(2.4)
        }
        return .2126 * channel(16) + .7152 * channel(8) + .0722 * channel(0)
    }

    private fun contrast(a: Int, b: Int): Double {
        val first = luminance(a); val second = luminance(b)
        return (max(first, second) + .05) / (min(first, second) + .05)
    }

    @Test fun classicPreservesTheExistingLookAndIsTheUpgradeDefault() {
        val classic = ThemeCatalog.resolve("classic")
        assertEquals(0xFF080808.toInt(), classic.background)
        assertEquals(0xFF171717.toInt(), classic.panel)
        assertEquals(0xFF29292C.toInt(), classic.raised)
        assertEquals(0xFFFFFFFF.toInt(), classic.text)
        assertEquals(0xFFAAAAAF.toInt(), classic.muted)
        assertEquals(0xFFFFFFFF.toInt(), classic.focusRing)
        assertEquals(0xFF3A3A3D.toInt(), classic.focusFill)
        assertEquals(0xFF48484A.toInt(), classic.pressedFill)
        assertEquals(0xFFF3F3F6.toInt(), classic.prominentFill)
        assertEquals(classic.background, classic.gradientStart)
        assertEquals(classic.background, classic.gradientEnd)
        assertFalse(classic.prefersGradient)
        assertEquals("classic", Preferences().themeId)
        assertTrue(Preferences().themeGradient)
        val upgraded = Preferences.decode(JSONObject("""{"startupScreen":"YouTube","seekSeconds":15}"""))
        assertEquals("classic", upgraded.themeId)
        assertTrue(upgraded.themeGradient)
        assertEquals("YouTube", upgraded.startSection)
        assertEquals(15, upgraded.seekSeconds)
    }

    @Test fun invalidAndMissingThemeIdsFallBackWithoutLosingOtherPreferences() {
        val classic = ThemeCatalog.resolve("classic")
        for (id in listOf("", "missing-preset", "MIDNIGHT", "../midnight")) {
            assertSame(classic, ThemeCatalog.resolve(id))
            val decoded = Preferences.decode(JSONObject().put("themeId", id).put("seekSeconds", 30)
                .put("youtubeAutoplay", false).put("themeGradient", false))
            assertEquals("classic", decoded.themeId)
            assertEquals(30, decoded.seekSeconds)
            assertFalse(decoded.youtubeAutoplay)
            assertFalse(decoded.themeGradient)
        }
        assertEquals("classic", Preferences.decode(JSONObject().put("themeId", JSONObject.NULL)).themeId)
    }

    @Test fun everyPresetAndBackgroundStyleRoundTripsWithPlaybackPreferences() {
        for (theme in ThemeCatalog.all) for (gradient in listOf(false, true)) {
            val before = Preferences(themeId = theme.id, themeGradient = gradient,
                startSection = "YouTube", seekSeconds = 15, youtubeAutoplay = false)
            assertEquals(before, Preferences.decode(before.json()))
        }
    }

    @Test fun presetsUseDistinctOpaqueRolesWithMixedSolidAndGradientBackgrounds() {
        val themes = ThemeCatalog.all
        assertEquals(10, themes.size)
        assertEquals(themes.size, themes.map { it.id }.distinct().size)
        assertEquals(themes.size, themes.map { it.name }.distinct().size)
        assertEquals(themes.size, themes.map { Triple(it.background, it.panel, it.accent) }.distinct().size)
        assertEquals(6, themes.count { it.prefersGradient })
        assertEquals(4, themes.count { !it.prefersGradient })
        for (theme in themes) {
            assertTrue(theme.name.isNotBlank())
            assertTrue(theme.pairing.isNotBlank())
            assertSame("Palette lookup should reuse immutable preset data", theme, ThemeCatalog.resolve(theme.id))
            val colors = listOf(theme.background, theme.panel, theme.raised, theme.text, theme.muted,
                theme.accent, theme.focusRing, theme.gradientStart, theme.gradientEnd, theme.onAccent,
                theme.focusFill, theme.pressedFill, theme.prominentFill, theme.overlaySurface)
            assertTrue("${theme.name} contains a translucent base color", colors.all { it ushr 24 == 255 })
            if (theme.prefersGradient) assertNotEquals(theme.gradientStart, theme.gradientEnd)
        }
    }

    @Test fun areaOverridesPersistIndependentlyAndMissingOrInvalidValuesInheritTheAppTheme() {
        val before = Preferences(themeId = "midnight", onDemandThemeId = "cinema", youtubeThemeId = "tube",
            liveThemeId = "aurora", themeGradient = false, seekSeconds = 15)
        val decoded = Preferences.decode(before.json())
        assertEquals(before, decoded)
        assertEquals("cinema", decoded.themeFor(Section.SEARCH))
        assertEquals("tube", decoded.themeFor(Section.YOUTUBE))
        assertEquals("aurora", decoded.themeFor(Section.LIVE))
        val youtubeLive = Watch(Title("fixture-youtube-live", "Generated YouTube live", type = "youtube"),
            Stream("Generated live", "http://fixture.invalid/live.m3u8"), live = true)
        assertEquals("tube", decoded.appearanceFor(Route.Player(youtubeLive)).id)
        val inherited = decoded.copy(liveThemeId = "", themeId = "rose")
        assertEquals("rose", inherited.themeFor(Section.LIVE))
        assertEquals("cinema", inherited.themeFor(Section.SEARCH))
        assertEquals("tube", inherited.themeFor(Section.YOUTUBE))
        assertEquals("rose", inherited.appearanceFor(Route.Settings).id)
        assertEquals("cinema", inherited.appearanceFor(Route.Detail(Title("fixture-movie", "Fixture movie"))).id)
        assertEquals("tube", inherited.appearanceFor(Route.Browse(Section.YOUTUBE)).id)
        assertEquals("rose", inherited.appearanceFor(Route.Browse(Section.LIVE)).id)
        for (value in listOf<Any>("", "unknown", "iris", JSONObject.NULL)) {
            val raw = JSONObject().put("themeId", "ocean").put("onDemandThemeId", value)
                .put("youtubeThemeId", value).put("liveThemeId", value).put("seekSeconds", 30)
            val fallback = Preferences.decode(raw)
            assertEquals("", fallback.onDemandThemeId)
            assertEquals("", fallback.youtubeThemeId)
            assertEquals("", fallback.liveThemeId)
            assertTrue(Section.entries.all { fallback.themeFor(it) == "ocean" })
            assertEquals(30, fallback.seekSeconds)
        }
        val missing = Preferences.decode(JSONObject().put("themeId", "ocean"))
        assertEquals("", missing.onDemandThemeId)
        assertEquals("", missing.youtubeThemeId)
        assertEquals("", missing.liveThemeId)
        assertTrue(Section.entries.all { missing.themeFor(it) == "ocean" })
    }

    @Test fun textFocusAndAccentLabelsRemainReadableAcrossEveryPreset() {
        for (theme in ThemeCatalog.all) {
            val surfaces = listOf(theme.background, theme.panel, theme.raised, theme.gradientStart, theme.gradientEnd)
            for (surface in surfaces) {
                assertTrue("${theme.name}: primary text contrast ${contrast(theme.text, surface)}",
                    contrast(theme.text, surface) >= 4.5)
                assertTrue("${theme.name}: secondary text contrast ${contrast(theme.muted, surface)}",
                    contrast(theme.muted, surface) >= 4.5)
                assertTrue("${theme.name}: focus contrast ${contrast(theme.focusRing, surface)}",
                    contrast(theme.focusRing, surface) >= 3.0)
            }
            assertTrue("${theme.name}: text on focused controls", contrast(theme.text, theme.focusFill) >= 4.5)
            assertTrue("${theme.name}: text on pressed controls", contrast(theme.text, theme.pressedFill) >= 4.5)
            assertTrue("${theme.name}: dark labels on accent", contrast(theme.onAccent, theme.accent) >= 4.5)
            assertTrue("${theme.name}: selected accent labels", contrast(theme.accent, theme.raised) >= 4.5)
            assertTrue("${theme.name}: prominent button labels", contrast(theme.onAccent, theme.prominentFill) >= 4.5)
        }
    }
}
