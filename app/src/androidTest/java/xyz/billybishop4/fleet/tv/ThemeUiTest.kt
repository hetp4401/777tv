@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package xyz.billybishop4.fleet.tv

import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.SeekBar
import android.widget.TextView
import androidx.media3.common.Player
import androidx.recyclerview.widget.RecyclerView
import androidx.test.filters.SdkSuppress
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Generated worker-emulator fixtures only. Never run this suite on a configured user TV. */
@SdkSuppress(minSdkVersion = 29)
class ThemeUiTest : NativeTestBase() {
    private val base = "http://10.0.2.2:8878"

    @Before fun prepareThemes() {
        runBlocking { Http().text("$base/youtube/control?reset=1") }
        ui {
            a.model.top(Section.SEARCH)
            a.model.server(base)
            a.model.youtubeFixture(base)
            a.model.updatePreferences(Preferences())
        }
        await { ui { AppTheme.palette.id == "classic" && !a.model.busy } }
    }

    @After fun restoreFixtureAppearance() {
        ui { a.model.top(Section.SEARCH); a.model.updatePreferences(Preferences()) }
        await { ui { a.player == null && AppTheme.palette.id == "classic" } }
    }

    // A dialog is a separate native window; do not search only the Activity decor.
    private fun activeTag(tag: String): View? = ui {
        WindowInspector.getGlobalWindowViews().asSequence().filter { it.isShown && it.hasWindowFocus() }
            .mapNotNull { it.findTag(tag) }.firstOrNull()
    }

    private fun appRoot(): View = ui { a.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) }

    private fun backgroundSamples(drawable: Drawable?): Pair<Int, Int> = ui {
        val copy = checkNotNull(drawable?.constantState) { "Native theme backgrounds must have a reusable drawable state" }
            .newDrawable(a.resources).mutate()
        val image = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        try {
            copy.setBounds(0, 0, 16, 16); copy.draw(Canvas(image))
            image.getPixel(1, 1) to image.getPixel(14, 14)
        } finally { image.recycle() }
    }

    private fun appearance() {
        ui { a.model.open(Route.Settings) }
        await { tagged("settings-list")?.isShown == true }
        click("Appearance")
        await { tagged("setting:App theme")?.isShown == true }
    }

    private fun openPicker() {
        appearance(); click("App theme")
        await { activeTag("theme-picker")?.isShown == true }
    }

    private fun chooseTheme(id: String) {
        openPicker()
        await { activeTag("theme-choice:$id")?.isShown == true }
        ui { assertTrue(activeTag("theme-choice:$id")!!.requestFocusFromTouch()) }
        sendRemoteKey(KeyEvent.KEYCODE_DPAD_CENTER)
        await {
            ui { AppTheme.palette.id == id && a.model.preferences.themeId == id && a.hasWindowFocus() } &&
                tagged("setting:App theme")?.hasFocus() == true && activeTag("theme-picker") == null
        }
    }

    private fun setTheme(id: String) {
        ui { a.model.updatePreferences(a.model.preferences.copy(themeId = id)) }
        await { ui { AppTheme.palette.id == id } }
    }

    private fun chooseAreaTheme(section: Section, label: String, id: String) {
        appearance(); click(label)
        await { activeTag("theme-choice:$id")?.isShown == true }
        ui { assertTrue(activeTag("theme-choice:$id")!!.requestFocusFromTouch()) }
        sendRemoteKey(KeyEvent.KEYCODE_DPAD_CENTER)
        await {
            ui { a.model.preferences.themeFor(section) == id && AppTheme.palette.id == "classic" && a.hasWindowFocus() } &&
                tagged("setting:$label")?.hasFocus() == true && activeTag("theme-picker") == null
        }
    }

    private fun awaitBoundYouTubeHomeCard() {
        await { tagged("youtube-page")?.isShown == true && ui { !a.model.youtube.busy && a.model.youtube.videos.isNotEmpty() } }
        val firstVideo = ui {
            a.model.youtube.homeShelves.firstOrNull { it.videos.isNotEmpty() }?.videos?.firstOrNull()
                ?: a.model.youtube.videos.first()
        }
        val secondary = listOf(firstVideo.channel, firstVideo.metadata).filter(String::isNotBlank).joinToString(" · ")
        assertTrue("Generated Home cards need secondary metadata for the color check", secondary.isNotBlank())
        // Feed readiness precedes the nested shelf adapter commit. Wait for bound
        // content, not a desired color, so incorrectly themed labels still fail.
        await {
            val card = tagged("youtube-video:${firstVideo.id}") as? YouTubeCard
            ui {
                card != null && card.isAttachedToWindow && card.isShown && card.width > 0 && card.height > 0 &&
                    descendants(card).filterIsInstance<TextView>().any { it.isShown && it.width > 0 && it.text.toString() == firstVideo.name } &&
                    descendants(card).filterIsInstance<TextView>().any { it.isShown && it.width > 0 && it.text.toString() == secondary }
            }
        }
    }

    @Test fun defaultAppearanceAndPickerKeepClassicSelectedWithoutApplyingOnFocus() {
        appearance()
        assertEquals("classic", ui { a.model.preferences.themeId })
        val root = backgroundSamples(ui { appRoot().background })
        assertEquals(0xFF080808.toInt(), root.first); assertEquals(root.first, root.second)
        screenshot("theme-classic-settings")
        click("App theme"); await { activeTag("theme-picker") != null }
        assertTrue(ui { activeTag("theme-choice:classic")!!.isSelected })
        ui { assertTrue(activeTag("theme-choice:midnight")!!.requestFocusFromTouch()) }
        sendRemoteKey(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals("classic", ui { a.model.preferences.themeId })
        assertEquals("classic", ui { AppTheme.palette.id })
        screenshot("theme-picker-classic")
        device.pressBack()
        await { tagged("setting:App theme")?.hasFocus() == true && ui { a.hasWindowFocus() } }
        assertEquals("classic", ui { Store(a).preferences().themeId })
    }

    @Test fun applyingAThemePreservesSettingsFocusPreferencesAndRecreationState() {
        ui { a.model.updatePreferences(a.model.preferences.copy(seekSeconds = 15, youtubeAutoplay = false)) }
        chooseTheme("midnight")
        assertEquals("midnight", ui { Store(a).preferences().themeId })
        assertEquals(15, ui { a.model.preferences.seekSeconds })
        assertFalse(ui { a.model.preferences.youtubeAutoplay })
        assertTrue(tagged("setting:Background")?.isShown == true)
        screenshot("theme-midnight-settings")
        click("App theme"); await { activeTag("theme-choice:midnight") != null }
        assertTrue(ui { activeTag("theme-choice:midnight")!!.isSelected })
        assertFalse(ui { activeTag("theme-choice:classic")!!.isSelected })
        screenshot("theme-picker-midnight")
        device.pressBack(); await { ui { a.hasWindowFocus() } }
        rule.scenario.recreate()
        await {
            ui { AppTheme.palette.id == "midnight" && a.model.preferences.themeId == "midnight" } &&
                tagged("setting:App theme")?.isShown == true && tagged("setting:Background")?.isShown == true
        }
        assertEquals(15, ui { a.model.preferences.seekSeconds })
        assertFalse(ui { a.model.preferences.youtubeAutoplay })
    }

    @Test fun gradientAndSolidBackgroundChoicesPersistAndChangeOnlyAppearance() {
        chooseTheme("ocean")
        val gradient = backgroundSamples(ui { appRoot().background })
        assertNotEquals("A coordinated Ocean backdrop should have two different endpoints", gradient.first, gradient.second)
        click("Background")
        val solid = By.clazz("android.widget.CheckedTextView").text("Solid")
        assertTrue(device.wait(Until.hasObject(solid), 5000) == true); device.findObject(solid).click()
        await { ui { !a.model.preferences.themeGradient && !AppTheme.gradient && a.hasWindowFocus() } }
        val samples = backgroundSamples(ui { appRoot().background })
        assertEquals(ThemeCatalog.resolve("ocean").background, samples.first)
        assertEquals(samples.first, samples.second)
        assertFalse(ui { Store(a).preferences().themeGradient })
        assertEquals("ocean", ui { a.model.preferences.themeId })
        screenshot("theme-ocean-solid")
        click("Background")
        val next = By.clazz("android.widget.CheckedTextView").text("Theme default")
        assertTrue(device.wait(Until.hasObject(next), 5000) == true); device.findObject(next).click()
        await { ui { a.model.preferences.themeGradient && AppTheme.gradient && a.hasWindowFocus() } }
        val restored = backgroundSamples(ui { appRoot().background })
        assertNotEquals(restored.first, restored.second)
        screenshot("theme-ocean-gradient")
    }

    @Test fun browseYouTubeLiveAndNativeDialogsUseTheirSelectedPalette() {
        chooseTheme("cinema")
        ui { a.model.top(Section.YOUTUBE); a.model.youtube.select(YouTubeFeed.HOME) }
        awaitBoundYouTubeHomeCard()
        assertTrue(ui { descendants(a.window.decorView).filterIsInstance<TextView>().any { it.isShown && it.currentTextColor == AppTheme.palette.text } })
        assertTrue(ui { descendants(a.window.decorView).filterIsInstance<TextView>().any { it.isShown && it.currentTextColor == AppTheme.palette.muted } })
        screenshot("theme-cinema-youtube")
        ui { a.model.top(Section.SEARCH) }; await { tagged("search-home")?.isShown == true && ui { !a.model.busy } }
        screenshot("theme-cinema-on-demand")
        setTheme("aurora")
        ui { a.model.showProfiles() }; await { ui { a.model.section == Section.LIVE } && find("Add profile")?.isShown == true }
        assertEquals("aurora", ui { AppTheme.palette.id })
        assertTrue(ui { descendants(a.window.decorView).filterIsInstance<TextView>().any { it.isShown && it.currentTextColor == AppTheme.palette.text } })
        screenshot("theme-aurora-live")
        setTheme("rose")
        lateinit var dialog: AlertDialog
        ui { dialog = a.showInfo("Theme preview", "Readable native dialogs use the same coordinated palette.") }
        await { ui { dialog.isShowing } }
        assertEquals(ThemeCatalog.resolve("rose").text, ui { checkNotNull(dialog.findViewById<TextView>(android.R.id.message)).currentTextColor })
        screenshot("theme-rose-dialog")
        ui { dialog.dismiss() }; await { ui { a.hasWindowFocus() } }
    }

    @Test fun independentAreaThemesKeepTheAppAndOtherSectionsUnchanged() {
        chooseAreaTheme(Section.SEARCH, "On-Demand theme", "cinema")
        chooseAreaTheme(Section.YOUTUBE, "YouTube theme", "tube")
        chooseAreaTheme(Section.LIVE, "Live TV theme", "aurora")
        val saved = ui { Store(a).preferences() }
        assertEquals("classic", saved.themeId)
        assertEquals("cinema", saved.onDemandThemeId)
        assertEquals("tube", saved.youtubeThemeId)
        assertEquals("aurora", saved.liveThemeId)

        nav("On-Demand")
        await { tagged("search-home")?.isShown == true && ui { !a.model.busy && AppTheme.palette.id == "cinema" } }
        val home = tagged("search-home") as RecyclerView
        await { ui { (home.adapter as? NativeAdapter<*>)?.currentList?.filterIsInstance<HomeRow>()?.any { it.name == "Popular · Movies" } == true } }
        ui {
            val rows = home.adapter as NativeAdapter<HomeRow>
            val index = rows.currentList.indexOfFirst { it.name == "Popular · Movies" }
            check(index >= 0) { "Generated movie shelf is missing" }
            home.scrollToPosition(index)
        }
        await {
            val card = firstMovieCard()
            ui { card != null && card.isAttachedToWindow && card.width > 0 && card.height > 0 &&
                card.getGlobalVisibleRect(Rect()) }
        }
        sendRemoteKey(KeyEvent.KEYCODE_DPAD_UP)
        val movieCard = firstMovieCard()!!
        ui { assertTrue(movieCard.requestFocusFromTouch()) }; await { ui { a.currentFocus === movieCard } }
        val cinema = backgroundSamples(ui { appRoot().background })
        assertEquals(0xFF000000.toInt(), cinema.first)
        assertEquals(cinema.first, cinema.second)
        screenshot("theme-area-on-demand")

        nav("YouTube"); awaitBoundYouTubeHomeCard()
        assertEquals("tube", ui { AppTheme.palette.id })
        val firstVideo = ui {
            a.model.youtube.homeShelves.firstOrNull { it.videos.isNotEmpty() }?.videos?.firstOrNull()
                ?: a.model.youtube.videos.first()
        }
        sendRemoteKey(KeyEvent.KEYCODE_DPAD_UP)
        val videoCard = tagged("youtube-video:${firstVideo.id}")!!
        ui { assertTrue(videoCard.requestFocusFromTouch()) }; await { ui { a.currentFocus === videoCard } }
        val tube = backgroundSamples(ui { appRoot().background })
        assertEquals(ThemeCatalog.resolve("tube").background, tube.first)
        assertEquals(tube.first, tube.second)
        screenshot("theme-area-youtube")

        nav("Live TV")
        await { find("Add profile")?.isShown == true && ui { AppTheme.palette.id == "aurora" } }
        val aurora = backgroundSamples(ui { appRoot().background })
        assertNotEquals(aurora.first, aurora.second)
        runBlocking { Http().text("$base/__control?broken_live=0&large_channels=0") }
        val demo = Provider("theme-live-demo", "Live TV", "$base/stalker_portal/", mac = "00:1A:79:00:00:01")
        val profilesBefore = ui { a.model.providers }
        ui { a.model.open(Route.Channels(demo)) }
        await { ui { !a.model.busy && a.model.channels.isNotEmpty() && AppTheme.palette.id == "aurora" } && tagged("channels")?.isShown == true }
        val firstChannel = ui { a.model.channels.first() }
        await {
            val row = tagged("channel:${firstChannel.id}") as? LiveChannelRow
            ui { row != null && row.isAttachedToWindow && row.isShown && row.width > 0 && row.height > 0 && row.contentDescription == firstChannel.name }
        }
        sendRemoteKey(KeyEvent.KEYCODE_DPAD_DOWN)
        val channelRow = tagged("channel:${firstChannel.id}")!!
        ui { assertTrue(channelRow.requestFocusFromTouch()) }; await { ui { a.currentFocus === channelRow } }
        sendRemoteKey(KeyEvent.KEYCODE_DPAD_CENTER)
        await(45000) { ui {
            a.model.route is Route.Channels && a.playback?.watch?.provider?.id == demo.id &&
                a.player?.isPlaying == true && (a.playback?.snapshot?.firstFrameMs ?: 0L) > 0
        } }
        await { ui { tagged("channel-guide")?.let { guide ->
            descendants(guide).filterIsInstance<TextView>().any { it.isShown && it.text.toString() == "Fixture Morning News" }
        } == true } }
        assertTrue(ui { channelRow.hasFocus() })
        assertEquals(profilesBefore, ui { a.model.providers })
        screenshot("theme-area-live")

        click("Settings"); await { tagged("settings-list")?.isShown == true && ui { AppTheme.palette.id == "classic" } }
        click("Appearance"); await { tagged("setting:On-Demand theme")?.isShown == true }
        assertEquals(saved, ui { Store(a).preferences() })
        for ((label, name) in listOf("On-Demand theme" to "Cinema", "YouTube theme" to "Tube", "Live TV theme" to "Aurora")) {
            assertTrue(ui { descendants(checkNotNull(tagged("setting:$label"))).filterIsInstance<TextView>().any { it.text.toString() == name } })
        }
        screenshot("theme-area-settings-classic")
    }

    @Test fun switchingAppearanceKeepsTheActiveDecoderPositionAndPauseIntent() {
        val watch = Watch(Title("theme-player-fixture", "Theme playback fixture"),
            Stream("Generated video", "$base/hls/fixture/orig.m3u8"))
        ui { a.model.open(Route.Player(watch)) }; playing()
        val original = player()!!
        ui { original.pause() }
        await { ui { !original.playWhenReady && original.playbackState == Player.STATE_READY } }
        val position = ui { original.currentPosition }
        val decoderStarts = ui { original.videoDecoderCounters!!.apply { ensureUpdated() }.decoderInitCount }
        setTheme("ember")
        assertSame(original, player())
        assertSame(watch, ui { (a.model.route as Route.Player).watch })
        assertFalse(ui { original.playWhenReady })
        assertTrue(ui { kotlin.math.abs(original.currentPosition - position) < 1000 })
        assertEquals(decoderStarts, ui { original.videoDecoderCounters!!.apply { ensureUpdated() }.decoderInitCount })
        screenshot("theme-ember-paused-player")
        ui { original.play() }; await { ui { original.currentPosition >= position + 300 && original.isPlaying } }
        assertSame(original, player())

        // Preserving an existing decoder and constructing themed transport are
        // separate paths: verify a freshly opened player uses the saved accent.
        ui { a.model.top(Section.SEARCH) }; await { ui { a.player == null } }
        val fresh = watch.copy()
        ui { a.model.open(Route.Player(fresh)) }; playing()
        sendRemoteKey(KeyEvent.KEYCODE_DPAD_UP)
        await { tagged("vod-controls")?.isShown == true && tagged("vod-progress") is SeekBar }
        assertEquals(ThemeCatalog.resolve("ember").accent,
            ui { checkNotNull((tagged("vod-progress") as SeekBar).progressTintList).defaultColor })
        screenshot("theme-ember-player-accent")
    }
}
