@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package xyz.billybishop4.fleet.tv

import android.graphics.Rect
import android.graphics.Color
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real D-pad paths and generated loopback catalog; no production media. */
class BrowseNavigationTest:NativeTestBase() {
    @Before fun setupCatalog() {
        runBlocking { Http().text("http://10.0.2.2:8878/__control?reset=1") }
        ui {
            a.model.showProfiles()
            a.model.providers.toList().forEach { a.model.removeProvider(it.id) }
            a.model.saved.toList().forEach { a.model.toggle(it) }
            a.model.updatePreferences(Preferences())
            a.model.server("http://10.0.2.2:8878");a.model.clearHistory()
            a.model.top(Section.SEARCH);a.model.search("")
        }
        await { shownTag("title:movie/tt0000001",within="shelf:Popular · Movies")!=null && ui { !a.model.busy } }
    }
    @After fun stopPlayback() { ui { a.model.showProfiles() };await { ui { a.activePlayer==null } } }
    private fun shownTag(tag:String,within:String?=null):View?=ui {
        val scope=if(within==null)a.window.decorView else descendants(a.window.decorView).firstOrNull {
            it.isShown && (it.tag==within || it is ShelfCard && within=="shelf:"+it.shelfName)
        }?:return@ui null
        descendants(scope).firstOrNull { it.tag==tag && it.isShown && it.width>0 && it.height>0 }
    }
    private fun activateTag(tag:String,within:String?=null) {
        await { shownTag(tag,within)!=null && ui { a.hasWindowFocus() } }
        val target=shownTag(tag,within)!!
        ui { target.requestFocus() }
        await { ui { target.isAttachedToWindow && a.currentFocus===target } }
        sendRemoteKey(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
    }
    private fun renderedScreen(tag:String,route:Route):Boolean=ui {
        val marker=a.window.decorView.findTag(tag)?.takeIf { it.isShown && it.width>0 && it.height>0 }?:return@ui false
        val scope=marker.parent as? View?:marker
        a.model.route==route && !a.model.busy && a.hasWindowFocus() && !a.window.decorView.isLayoutRequested &&
            descendants(scope).filter { it.isShown }.all { view ->
                !view.isLayoutRequested && (view !is RecyclerView || !view.isComputingLayout && !view.hasPendingAdapterUpdates())
            }
    }
    private data class PosterBounds(val item:Rect,val image:Rect,val label:Rect,val complete:Boolean,val focusStops:Int,val inset:Int)
    private fun assertPosterImageOutline(card:View,focused:Boolean) {
        try {
            fun settledBounds():PosterBounds?=ui {
                if(!card.isAttachedToWindow || !a.hasWindowFocus() || card.hasFocus()!=focused)return@ui null
                var ancestor=card.parent
                while(ancestor is View) {
                    if(ancestor.isLayoutRequested || ancestor is RecyclerView &&
                        (ancestor.scrollState!=RecyclerView.SCROLL_STATE_IDLE || ancestor.isComputingLayout || ancestor.hasPendingAdapterUpdates()))return@ui null
                    ancestor=ancestor.parent
                }
                fun rect(view:View):Rect {
                    val location=IntArray(2);view.getLocationOnScreen(location)
                    return Rect(location[0],location[1],location[0]+view.width,location[1]+view.height)
                }
                val image=descendants(card).filterIsInstance<ImageView>().single()
                val label=descendants(card).filterIsInstance<TextView>().single()
                val imageRect=rect(image);val visible=Rect()
                val complete=image.getGlobalVisibleRect(visible) && imageRect==visible
                if(!complete)return@ui null
                PosterBounds(rect(card),imageRect,rect(label),complete,
                    descendants(card).count { it.isShown && it.isFocusable },maxOf(1,a.dp(1)))
            }
            var capturedBounds:PosterBounds?=null
            var capturedBitmap:android.graphics.Bitmap?=null
            await {
                val before=settledBounds()?:return@await false
                val rendered=CountDownLatch(1)
                ui {
                    if(android.os.Build.VERSION.SDK_INT>=29 && card.isHardwareAccelerated) {
                        card.viewTreeObserver.registerFrameCommitCallback { rendered.countDown() }
                        card.invalidate()
                    } else card.postOnAnimation { card.postOnAnimation { rendered.countDown() } }
                }
                assertTrue("Poster focus frame was not committed",rendered.await(5,TimeUnit.SECONDS))
                if(before!=settledBounds())return@await false
                val frame=checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
                // Focus-scroll can move the outer shelf after an initially idle layout.
                // Sample pixels only when their bounds/focus stayed unchanged across capture.
                if(before!=settledBounds()) { frame.recycle();false }
                else { capturedBounds=before;capturedBitmap=frame;true }
            }
            val bounds=checkNotNull(capturedBounds)
            val bitmap=checkNotNull(capturedBitmap)
            try {
                assertTrue("Poster image was clipped",bounds.complete)
                assertEquals("A poster must remain one remote action",1,bounds.focusStops)
                assertTrue("Caption overlaps the framed image",bounds.label.top>bounds.image.bottom)
                assertTrue(bounds.item.contains(bounds.image));assertTrue(bounds.item.contains(bounds.label))
                fun white(x:Int,y:Int):Boolean {
                    val pixel=bitmap.getPixel(x,y)
                    return Color.red(pixel)>230 && Color.green(pixel)>230 && Color.blue(pixel)>230
                }
                val image=bounds.image;val inset=bounds.inset
                val edges=listOf(white(image.left+inset,image.centerY()),white(image.right-inset-1,image.centerY()),
                    white(image.centerX(),image.top+inset),white(image.centerX(),image.bottom-inset-1))
                assertTrue("Image edge focus did not match $focused: $edges; $bounds",edges.all { it==focused })
                assertFalse("Focus border encloses the caption on the left",white(bounds.item.left+inset,bounds.label.centerY()))
                assertFalse("Focus border encloses the caption on the right",white(bounds.item.right-inset-1,bounds.label.centerY()))
            } catch(failure:Throwable) {
                val directory=ui { a.getExternalFilesDir(null)!! }
                java.io.FileOutputStream(java.io.File(directory,"poster-image-focus-sampled.png")).use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
                }
                java.io.File(directory,"poster-image-focus-sampled.txt").writeText("focused=$focused\n$bounds\n")
                throw failure
            } finally { bitmap.recycle() }
        } catch(failure:Throwable) {
            runCatching { screenshot("poster-image-focus-before-cleanup") }
            runCatching { device.dumpWindowHierarchy(java.io.File(ui { a.getExternalFilesDir(null)!! },"poster-image-focus-before-cleanup.xml")) }
            throw failure
        }
    }
    @Test fun posterFocusFramesOnlyImagesAcrossSearchNavigationAndShelfRecycling() {
        field("Search movies and shows","Fleet")
        await { shownTag("title:series/tt0000002",within="search-results")!=null && ui { !a.model.busy } }
        val movie=shownTag("title:movie/tt0000001",within="search-results")!!
        val series=shownTag("title:series/tt0000002",within="search-results")!!
        ui { movie.requestFocus() };await { ui { a.currentFocus===movie } }
        assertPosterImageOutline(movie,true)
        sendRemoteKey(android.view.KeyEvent.KEYCODE_DPAD_UP)
        await { ui { a.currentFocus?.tag=="field:Search movies and shows" } }
        assertPosterImageOutline(movie,false)
        ui { movie.requestFocus() };await { ui { a.currentFocus===movie } }
        sendRemoteKey(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
        await { ui { a.currentFocus===series } }
        assertPosterImageOutline(series,true)
        screenshot("poster-image-focus-search")
        val saved=(0 until 35).map { Title("poster-frame-$it","Poster frame $it") }
        ui { a.model.search("");saved.forEach(a.model::toggle) }
        val home=tagged("search-home") as RecyclerView
        await { ui { home.isShown && home.adapter?.itemCount==5 && !home.hasPendingAdapterUpdates() && !home.isLayoutRequested } }
        ui { home.scrollToPosition(0) }
        await { shownTag("shelf:My list") is ShelfCard }
        val shelf=shownTag("shelf:My list") as ShelfCard
        await { ui { shelf.list.adapter?.itemCount==35 && !shelf.list.hasPendingAdapterUpdates() } }
        val first=shownTag("title:movie/poster-frame-34",within="shelf:My list")!!
        ui { first.requestFocus() };await { ui { a.currentFocus===first } }
        assertPosterImageOutline(first,true)
        val recycled=ArrayList<Pair<View,Any?>>()
        ui {
            a.window.decorView.findTag("field:Search movies and shows")!!.requestFocus()
            shelf.list.setItemViewCacheSize(0)
            shelf.list.addRecyclerListener { holder -> recycled.add(holder.itemView to holder.itemView.tag) }
        }
        for((position,id) in listOf(34 to 0,0 to 34,34 to 0)) {
            ui { shelf.list.scrollToPosition(position) }
            await { shownTag("title:movie/poster-frame-$id",within="shelf:My list")!=null && ui {
                !shelf.list.hasPendingAdapterUpdates() && !shelf.list.isComputingLayout && !shelf.list.isLayoutRequested
            } }
        }
        val reused=ui {
            (0 until shelf.list.childCount).map { shelf.list.getChildAt(it) }.firstOrNull { card ->
                val visible=Rect()
                card.getGlobalVisibleRect(visible) && visible.width()==card.width && visible.height()==card.height &&
                    recycled.any { it.first===card && it.second!=card.tag }
            }
        }
        assertNotNull("The native RecyclerView did not rebind a recycled poster",reused)
        assertPosterImageOutline(reused!!,false)
        val last=shownTag("title:movie/poster-frame-0",within="shelf:My list")!!
        ui { last.requestFocus() };await { ui { a.currentFocus===last } }
        assertPosterImageOutline(last,true)
        sendRemoteKey(android.view.KeyEvent.KEYCODE_DPAD_LEFT)
        await { ui { a.currentFocus?.tag=="title:movie/poster-frame-1" } }
        val previous=shownTag("title:movie/poster-frame-1",within="shelf:My list")!!
        assertPosterImageOutline(previous,true);assertPosterImageOutline(last,false)
        assertEquals("Poster frame 1",ui { descendants(previous).filterIsInstance<TextView>().single().text.toString() })
        screenshot("poster-image-focus-recycled-shelf")
    }
    @Test fun homeHasOnlyCompactShelvesAndOneOkOpensMovieStreams() {
        val frames=CountDownLatch(1)
        ui { a.window.decorView.postOnAnimation { a.window.decorView.postOnAnimation { frames.countDown() } } }
        assertTrue("Home frame did not settle",frames.await(5,TimeUnit.SECONDS))
        await(10000) { ui {
            val current=a.window.decorView.findTag("search-home") as? RecyclerView?:return@ui false
            current.isShown && !a.model.busy && current.adapter?.itemCount==4 && current.childCount>0 &&
                !a.window.decorView.isLayoutRequested && !current.isLayoutRequested && !current.isComputingLayout &&
                !current.hasPendingAdapterUpdates() && current.scrollState==RecyclerView.SCROLL_STATE_IDLE
        } }
        val home=tagged("search-home") as RecyclerView
        assertEquals(4,ui { home.adapter?.itemCount })
        assertTrue(ui { (0 until home.childCount).all { home.getChildAt(it) is ShelfCard } })
        assertNull(shownTag("featured-title"));assertNull(find("View details"))
        assertNotNull(shownTag("title:movie/tt0000001",within="shelf:Popular · Movies"))
        android.os.SystemClock.sleep(200);screenshot("search-home")
        activateTag("title:movie/tt0000001",within="shelf:Popular · Movies")
        await { shownTag("stream:Fixture GDrive")!=null && ui { a.model.route is Route.Detail && !a.model.busy && !a.model.streamsBusy } }
        assertNull(find("Choose stream"));assertNull(find("Resume · choose stream"));assertNull(ui { a.activePlayer })
        screenshot("browse-title-details")
    }
    @Test fun moviePosterShowsStreamsDirectlyAndPlayerBackRestoresTheChosenTileAndQuery() {
        field("Search movies and shows","Fleet")
        await { shownTag("title:movie/tt0000001",within="search-results")!=null && ui { !a.model.busy } }
        activateTag("title:movie/tt0000001",within="search-results")
        await { shownTag("movie-details")!=null && ui {
            a.model.route is Route.Detail && !a.model.busy && !a.model.streamsBusy &&
                (a.window.decorView.findTag("streams") as? RecyclerView)?.childCount==2
        } }
        assertNull(find("Choose stream"));assertNull(find("Resume · choose stream"));assertNull(ui { a.activePlayer })
        val streams=tagged("streams") as RecyclerView
        assertEquals(RecyclerView.HORIZONTAL,ui { (streams.layoutManager as LinearLayoutManager).orientation })
        val movie=ui { (a.model.route as Route.Detail).title }
        val chosen=ui { a.model.streams[1] }
        val first=ui { (streams.getChildAt(0) as ViewGroup).getChildAt(0) }
        val second=ui { (streams.getChildAt(1) as ViewGroup).getChildAt(0) }
        ui { first.requestFocus() };await { ui { a.currentFocus===first } }
        sendRemoteKey(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
        await { ui { a.currentFocus===second && second.getGlobalVisibleRect(Rect()) } }
        val context=ui { (second.parent as View).tag }
        screenshot("movie-inline-streams")
        sendRemoteKey(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
        await { ui {
            val watch=(a.model.route as? Route.Player)?.watch
            watch!=null && watch.title.key==movie.key && watch.stream.identity==chosen.identity && a.activePlayer!=null && !streams.isShown
        } }
        assertEquals(chosen.headers,ui { (a.model.route as Route.Player).watch.headers })
        back()
        await { ui { a.model.route is Route.Detail && !a.model.streamsBusy && (a.currentFocus?.parent as? View)?.tag==context } }
        assertEquals("Fleet Test Movie",ui { (a.window.decorView.findTag("detail-title") as TextView).text.toString() })
        rule.scenario.recreate()
        await { ui { a.model.route is Route.Detail && (a.currentFocus?.parent as? View)?.tag==context } }
        sendRemoteKey(android.view.KeyEvent.KEYCODE_BACK)
        await { renderedScreen("search-controls",Route.Browse(Section.SEARCH)) && ui {
            a.model.searchText=="Fleet" && (a.window.decorView.findTag("field:Search movies and shows") as EditText).text.toString()=="Fleet" &&
                a.currentFocus?.tag=="title:"+movie.key && (a.window.decorView.findTag("search-results") as RecyclerView).findContainingItemView(a.currentFocus!!)!=null
        } }
    }
    @Test fun movieStreamFailureKeepsDetailsAndRetryRecoversInline() {
        runBlocking { Http().text("http://10.0.2.2:8878/__control?fail_streams=1") }
        activateTag("title:movie/tt0000001",within="shelf:Popular · Movies")
        await { shownTag("movie-details")!=null && shownTag("streams-error")!=null && find("Retry streams")!=null && ui {
            a.model.route is Route.Detail && !a.model.busy && !a.model.streamsBusy && a.model.streamsError!=null
        } }
        assertEquals("Fleet Test Movie",ui { (a.window.decorView.findTag("detail-title") as TextView).text.toString() })
        assertNull(ui { a.model.error });assertNull(find("Choose stream"));assertNull(ui { a.activePlayer })
        screenshot("movie-inline-stream-error")
        runBlocking { Http().text("http://10.0.2.2:8878/__control?fail_streams=0") }
        click("Retry streams")
        await { shownTag("stream:Fixture GDrive")!=null && ui {
            a.model.route is Route.Detail && !a.model.streamsBusy && a.model.streamsError==null && a.currentFocus?.tag=="stream:Fixture GDrive"
        } }
        assertNull(find("Retry streams"));assertNull(ui { a.activePlayer })
    }
    @Test fun pendingMovieStreamsCannotReplaceSelectedEpisodeSources() {
        runBlocking { Http().text("http://10.0.2.2:8878/__control?slow_streams=1") }
        val started=android.os.SystemClock.elapsedRealtime()
        ui { a.model.open(Route.Detail(Title("browse-stream-metadata","Slow fixture movie"))) }
        await { shownTag("movie-details")!=null && shownTag("streams-loading")!=null && ui { !a.model.busy && a.model.streamsBusy } }
        assertEquals("Slow fixture movie",ui { (a.window.decorView.findTag("detail-title") as TextView).text.toString() })
        ui { a.model.open(Route.Detail(resumeSeries())) }
        val episode=resumeEpisode(1,2)
        await { shownTag("episode:"+episode.id)!=null && ui { !a.model.busy } }
        runBlocking { Http().text("http://10.0.2.2:8878/__control?slow_streams=0") }
        activateTag("episode:"+episode.id)
        await { shownTag("stream:Fixture GDrive")!=null && ui {
            a.model.route is Route.Detail && a.model.selectedEpisode?.id==episode.id && !a.model.streamsBusy && a.model.streams.size==2
        } }
        val expected=ui { a.model.streams.map { it.identity } }
        assertTrue(ui { a.model.streams.none { it.url.contains("source=large") || it.url.contains("source=small") } })
        val deadline=maxOf(started+1800,android.os.SystemClock.elapsedRealtime()+500)
        while(android.os.SystemClock.elapsedRealtime()<deadline) {
            assertTrue(ui { a.model.route is Route.Detail });assertEquals(episode.id,ui { a.model.selectedEpisode?.id })
            assertEquals(expected,ui { a.model.streams.map { it.identity } })
            assertNull(ui { a.model.streamsError });assertFalse(ui { a.model.streamsBusy })
            android.os.SystemClock.sleep(40)
        }
        assertNull(shownTag("movie-details"));assertNull(ui { a.activePlayer })
    }
    @Test fun singleSeasonHasNoDeadFocusStopAndStreamChoiceRemainsExplicit() {
        field("Search movies and shows","Fleet")
        await { ui {
            val results=a.window.decorView.findTag("search-results") as? RecyclerView
            results?.isShown==true && results.findTag("title:series/tt0000002")?.isShown==true && !a.model.busy
        } }
        activateTag("title:series/tt0000002")
        await { tagged("episode:tt0000002:1:1")?.isShown==true }
        val season=find("Season 1")!!
        assertFalse(ui { season.isFocusable });assertFalse(ui { season.isClickable })
        await { ui { a.currentFocus?.tag=="episode:tt0000002:1:1" } }
        sendRemoteKey(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
        await { tagged("stream:Fixture GDrive")?.isShown==true && ui { !a.model.busy } }
        assertTrue(ui { a.model.route is Route.Detail });assertEquals("tt0000002:1:1",ui { a.model.selectedEpisode?.id });assertNull(ui { a.activePlayer })
        assertTrue(ui { (a.window.decorView.findTag("source-context") as TextView).text.startsWith("Season 1 · Episode 1") })
        screenshot("browse-stream-choice")
    }
    @Test fun emptyAddonLinksOfferRefreshAndRecoverWithoutAutoplay() {
        runBlocking { Http().text("http://10.0.2.2:8878/__control?emptyStreams=1") }
        ui {
            a.model.open(Route.Sources(Title("browse-no-streams","No links fixture")))
        }
        await { find("Refresh streams")?.isShown==true && ui { !a.model.busy } }
        val streams=tagged("streams") as RecyclerView
        assertEquals(0,ui { streams.adapter!!.itemCount });assertNull(ui { a.activePlayer })
        assertFalse(ui { streams.isShown });assertNull(find("Hold OK for stream details"))
        runBlocking { Http().text("http://10.0.2.2:8878/__control?emptyStreams=0") }
        click("Refresh streams")
        await { ui { streams.adapter!!.itemCount==2 && a.currentFocus?.tag=="stream:Fixture GDrive" } }
        assertTrue(ui { streams.isShown });assertNull(ui { a.activePlayer })
        runBlocking { Http().text("http://10.0.2.2:8878/__control?emptyStreams=1") }
        ui { a.model.open(Route.Detail(Title("browse-no-streams","No links fixture"))) }
        await { shownTag("movie-details")!=null && find("Refresh streams")!=null && ui { !a.model.streamsBusy } }
        assertNull(find("Choose stream"));assertNull(ui { a.activePlayer })
        runBlocking { Http().text("http://10.0.2.2:8878/__control?emptyStreams=0") }
        click("Refresh streams")
        await { shownTag("stream:Fixture GDrive")!=null && ui { a.model.route is Route.Detail && !a.model.streamsBusy && a.currentFocus?.tag=="stream:Fixture GDrive" } }
        assertNull(ui { a.activePlayer })
    }
    @Test fun searchWithoutCatalogCapabilityOpensManagementAndDefaultsRestoreBrowsing() {
        ui { a.model.addons.first { it.id=="com.linvo.cinemeta" }.let { a.model.setAddonEnabled(it.manifestUrl,false) } }
        await { renderedScreen("search-controls",Route.Browse(Section.SEARCH)) && find("Manage add-ons")!=null }
        assertNull(find("Refresh catalog"))
        field("Search movies and shows","Fleet")
        await { renderedScreen("search-controls",Route.Browse(Section.SEARCH)) && find("Manage add-ons")!=null }
        assertNull(find("No results"));assertTrue(ui { a.model.results.isEmpty() })
        val localSeries=Title("tt0000002","Saved fixture series","series")
        ui { a.model.search("");a.model.toggle(localSeries) }
        await { renderedScreen("search-controls",Route.Browse(Section.SEARCH)) && shownTag("title:"+localSeries.key,within="shelf:My list")!=null }
        activateTag("title:"+localSeries.key,within="shelf:My list")
        await { renderedScreen("series-details",Route.Detail(localSeries)) && find("Manage add-ons")!=null }
        assertNull(find("Refresh episodes"))
        activateTag("button:Manage add-ons")
        await { renderedScreen("addons-screen",Route.Addons) }
        ui { a.model.resetAddons() }
        await { renderedScreen("addons-screen",Route.Addons) && ui { (a.window.decorView.findTag("addons-status") as? TextView)?.text=="Default add-ons restored." } }
        sendRemoteKey(android.view.KeyEvent.KEYCODE_BACK)
        await { renderedScreen("series-details",Route.Detail(localSeries)) && shownTag("episode:tt0000002:1:1")!=null }
        assertNull(find("Manage add-ons"))
        sendRemoteKey(android.view.KeyEvent.KEYCODE_BACK)
        await { renderedScreen("search-controls",Route.Browse(Section.SEARCH)) && shownTag("search-home")!=null }
        field("Search movies and shows","Fleet")
        await { renderedScreen("search-controls",Route.Browse(Section.SEARCH)) && shownTag("search-results")!=null && ui {
            a.model.results.size==2 && (a.window.decorView.findTag("search-results") as RecyclerView).adapter?.itemCount==2
        } }
        assertNull(find("Manage add-ons"))
        field("Search movies and shows","")
        await { renderedScreen("search-controls",Route.Browse(Section.SEARCH)) && shownTag("search-home")!=null && ui { a.model.searchText.isEmpty() } }
        ui { a.model.addons.toList().forEach { a.model.setAddonEnabled(it.manifestUrl,false) } }
        await { renderedScreen("search-controls",Route.Browse(Section.SEARCH)) && find("Manage add-ons")!=null }
        assertNull(find("Refresh catalog"));assertNull(find("Nothing to browse yet"))
        activateTag("button:Manage add-ons")
        await { renderedScreen("addons-screen",Route.Addons) }
        ui { a.model.resetAddons() }
        await { renderedScreen("addons-screen",Route.Addons) && ui { (a.window.decorView.findTag("addons-status") as? TextView)?.text=="Default add-ons restored." } }
        sendRemoteKey(android.view.KeyEvent.KEYCODE_BACK)
        await { renderedScreen("search-controls",Route.Browse(Section.SEARCH)) && shownTag("search-home")!=null && ui {
                a.model.shelves.size==4 && a.model.shelves.all { it.titles.isNotEmpty() }
        } }
        assertNull(find("Manage add-ons"))
        // Verify the restored local shelf remains usable alongside the reloaded catalogs.
        ui { (a.window.decorView.findTag("search-home") as RecyclerView).scrollToPosition(0) }
        activateTag("title:"+localSeries.key,within="shelf:My list")
        await { shownTag("episode:tt0000002:1:1")!=null && ui { a.model.route is Route.Detail && !a.model.busy } }
    }
    @Test fun sourcesWithoutStreamCapabilityOpensManagementAndBackLoadsDefaultLinks() {
        ui {
            a.model.addons.first { it.id!="com.linvo.cinemeta" }.let { a.model.setAddonEnabled(it.manifestUrl,false) }
            a.model.open(Route.Sources(Title("tt0000001","Fleet Test Movie")))
        }
        await { find("Manage add-ons")!=null && ui { !a.model.busy } }
        assertNull(find("Refresh streams"));assertNull(find("Hold OK for stream details"))
        activateTag("button:Manage add-ons")
        await { ui { a.model.route==Route.Addons } }
        ui { a.model.resetAddons() }
        sendRemoteKey(android.view.KeyEvent.KEYCODE_BACK)
        await { shownTag("stream:Fixture GDrive")!=null && ui { a.model.route is Route.Sources && !a.model.busy } }
        assertNull(find("Manage add-ons"));assertNull(ui { a.activePlayer })
    }
    @Test fun unreadableAddonStorageOffersManagementFromSearchAndSources() {
        val original=ui { a.getSharedPreferences("fleet-tv",0).getString("addons-encrypted",null) }
        val sources=Route.Sources(Title("tt0000001","Fleet Test Movie"))
        try {
            ui {
                a.getSharedPreferences("fleet-tv",0).edit().putString("addons-encrypted","unreadable-fixture-data").commit()
                a.viewModelStore.clear()
            }
            rule.scenario.recreate()
            ui { a.model.top(Section.SEARCH) }
            await { renderedScreen("search-controls",Route.Browse(Section.SEARCH)) && find("Manage add-ons")!=null && ui { a.model.addonStorageError } }
            assertNull(find("Refresh catalog"));assertNull(find("No results"))
            activateTag("button:Manage add-ons")
            await { renderedScreen("addons-screen",Route.Addons) && ui { a.model.addonStorageError } }
            sendRemoteKey(android.view.KeyEvent.KEYCODE_BACK)
            await { renderedScreen("search-controls",Route.Browse(Section.SEARCH)) && ui { a.model.addonStorageError } }
            ui { a.model.open(sources) }
            await { renderedScreen("source-title",sources) && shownTag("stream-choices")!=null && find("Manage add-ons")!=null && ui { a.model.addonStorageError && !a.model.streamsBusy } }
            assertNull(find("Refresh streams"))
            activateTag("button:Manage add-ons",within="stream-choices")
            await { renderedScreen("addons-screen",Route.Addons) && ui { a.model.addonStorageError } }
            assertEquals("unreadable-fixture-data",ui { a.getSharedPreferences("fleet-tv",0).getString("addons-encrypted",null) })
        } finally {
            ui {
                a.getSharedPreferences("fleet-tv",0).edit().apply { if(original==null)remove("addons-encrypted") else putString("addons-encrypted",original) }.commit()
                a.model.server("http://10.0.2.2:8878")
            }
        }
    }
    @Test fun backRestoresOffscreenPosterWithinItsHorizontalShelf() {
        val titles=(0 until 20).map { Title("browse-saved-$it","Saved title $it") }
        ui { titles.forEach(a.model::toggle) }
        val home=tagged("search-home") as RecyclerView
        await { ui { home.adapter?.itemCount==5 && !home.hasPendingAdapterUpdates() && !home.isComputingLayout && !home.isLayoutRequested } }
        ui { home.scrollToPosition(0) }
        await { shownTag("shelf:My list") is ShelfCard }
        val shelf=tagged("shelf:My list") as ShelfCard
        await { ui { shelf.list.adapter?.itemCount==20 && !shelf.list.hasPendingAdapterUpdates() } }
        ui { shelf.list.scrollToPosition(18) }
        val target="title:movie/browse-saved-1"
        await { tagged(target)?.isShown==true }
        activateTag(target)
        await { shownTag("stream:Fixture GDrive")!=null && renderedScreen("movie-details",Route.Detail(titles[1])) && ui { !a.model.streamsBusy } }
        sendRemoteKey(android.view.KeyEvent.KEYCODE_BACK)
        await { ui { a.model.route==Route.Browse(Section.SEARCH) && a.currentFocus?.tag==target } }
        val restored=tagged("shelf:My list") as ShelfCard
        assertTrue(ui { (restored.list.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition()>0 })
        assertTrue(ui { a.currentFocus!!.getGlobalVisibleRect(Rect()) })
        screenshot("browse-restored-shelf")
    }
    @Test fun profilesHaveOnePrimaryAndOneMenuAndDeleteCancelPreservesData() {
        val first=Provider("browse-room-one","Living room","http://10.0.2.2:8878/stalker_portal/",mac="00:1A:79:00:00:01")
        val second=first.copy(id="browse-room-two",name="Bedroom")
        ui { a.model.showProfiles();a.model.saveProvider(first);a.model.saveProvider(second);a.model.makeDefaultProfile(first.id);a.model.showProfiles() }
        await { tagged("profile:"+second.id)?.isShown==true }
        val primary=tagged("profile:"+second.id)!!
        assertEquals(2,ui { descendants(primary.parent as ViewGroup).count { it.isShown && it.isFocusable } })
        deleteProfile(second.name,confirm=false)
        assertTrue(ui { a.model.providers.any { it.id==second.id } })
        await { tagged("profile-options:"+second.id)?.hasFocus()==true }
        profileAction(second.name,"Set as default")
        await { ui { a.model.defaultProfile?.id==second.id } }
        screenshot("browse-profiles")
        activateTag("profile:"+second.id)
        await { ui { (a.model.route as? Route.Channels)?.provider?.id==second.id } }
    }
    @Test fun searchReselectPreservesResultsAndClearReturnsFocusInOneAction() {
        field("Search movies and shows","Fleet")
        await { ui { !a.model.busy && a.model.results.size==2 } }
        val results=ui { a.model.results }
        activateTag("navigation:On-Demand")
        assertEquals("Fleet",ui { a.model.searchText });assertSame(results,ui { a.model.results })
        sendRemoteKey(android.view.KeyEvent.KEYCODE_BACK)
        await { ui {
            a.model.route==Route.Browse(Section.SEARCH) && a.model.searchText.isEmpty() &&
                (a.window.decorView.findTag("field:Search movies and shows") as EditText).text.isEmpty()
        } && tagged("search-home")?.isShown==true }
        field("Search movies and shows","Fleet")
        await { ui { !a.model.busy && a.model.results.size==2 } }
        click("Clear search")
        await { ui { a.model.searchText.isEmpty() } && tagged("search-home")?.isShown==true }
        assertTrue(ui { (a.window.decorView.findTag("field:Search movies and shows") as EditText).hasFocus() })
    }
    @Test fun nativeSearchKeyboardSubmitsResultsAndControlsStayVisible() {
        val input=tagged("field:Search movies and shows") as EditText
        await { ui { input.isShown && input.getGlobalVisibleRect(Rect()) && input.height>=a.dp(44) } }
        assertNotNull(find("Submit search"))
        activateTag("field:Search movies and shows")
        await { device.executeShellCommand("dumpsys input_method").contains("mInputShown=true") }
        device.executeShellCommand("input text Fleet")
        await { ui { a.model.searchText=="Fleet" && !a.model.busy && a.model.results.size==2 } }
        ui { input.onEditorAction(EditorInfo.IME_ACTION_SEARCH) }
        await { !device.executeShellCommand("dumpsys input_method").contains("mInputShown=true") }
        await { ui {
            val grid=a.window.decorView.findTag("search-results") as RecyclerView
            grid.findContainingItemView(a.currentFocus?:return@ui false)!=null
        } }
        assertTrue(ui { input.isShown && input.getGlobalVisibleRect(Rect()) && input.height>=a.dp(44) })
        assertNotNull(find("Submit search"));assertNotNull(find("Clear search"))
        screenshot("search-submit-results")
    }
    @Test fun searchTabFromSourcesAndDirectionalNavigationReachTheSearchField() {
        ui { a.model.open(Route.Sources(Title("tt0000001","Fleet Test Movie"))) }
        await { tagged("stream:Fixture GDrive")?.isShown==true && ui { !a.model.busy } }
        activateTag("navigation:On-Demand")
        await { ui { a.model.route==Route.Browse(Section.SEARCH) && a.currentFocus?.tag=="field:Search movies and shows" } }
        sendRemoteKey(android.view.KeyEvent.KEYCODE_DPAD_UP)
        await { ui { a.currentFocus?.tag=="navigation:On-Demand" } }
        sendRemoteKey(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
        await { ui { a.currentFocus?.tag=="field:Search movies and shows" } }
        activateTag("title:movie/tt0000001",within="shelf:Popular · Movies")
        await { shownTag("movie-details")!=null && shownTag("stream:Fixture GDrive")!=null && ui { a.model.route is Route.Detail && !a.model.streamsBusy } }
        sendRemoteKey(android.view.KeyEvent.KEYCODE_BACK)
        await { ui { a.currentFocus?.tag=="title:movie/tt0000001" } }
        sendRemoteKey(android.view.KeyEvent.KEYCODE_DPAD_UP)
        await { ui { (a.currentFocus?.tag as? String)?.startsWith("catalog-filter:")==true } }
        sendRemoteKey(android.view.KeyEvent.KEYCODE_DPAD_UP)
        await { ui { a.currentFocus?.tag=="field:Search movies and shows" } }
        screenshot("search-controls")
    }
    @Test fun searchControlsRemainAvailableWhenHomeOnlyHasSavedContentAndServerFails() {
        val saved=Title("browse-error-saved","Saved title")
        ui { a.model.toggle(saved);a.model.server("http://127.0.0.1:1") }
        await { ui {
            val home=a.window.decorView.findTag("search-home") as RecyclerView
            return@ui (!a.model.busy && a.model.error!=null && home.adapter?.itemCount==1)
        } && tagged("shelf:My list")?.isShown==true }
        val input=tagged("field:Search movies and shows") as EditText
        assertTrue(ui { input.isShown && input.getGlobalVisibleRect(Rect()) && input.height>=a.dp(44) })
        assertNotNull(find("Submit search"))
        ui { (a.window.decorView.findTag("shelf:My list") as ShelfCard).list.scrollToPosition(0) }
        await { shownTag("title:"+saved.key)!=null }
        ui { shownTag("title:"+saved.key)!!.requestFocus() }
        sendRemoteKey(android.view.KeyEvent.KEYCODE_DPAD_UP)
        if(tagged("catalog-filters")?.isShown==true) {
            await { ui { (a.currentFocus?.tag as? String)?.startsWith("catalog-filter:")==true } }
            sendRemoteKey(android.view.KeyEvent.KEYCODE_DPAD_UP)
        }
        await { ui { input.hasFocus() } }
        ui { a.model.server("http://10.0.2.2:8878") }
        await { ui { !a.model.busy && a.model.error==null && a.model.shelves.size==4 } }
        field("Search movies and shows","F")
        await { find("Enter at least 2 characters")?.isShown==true }
        field("Search movies and shows","Fleet")
        click("Submit search")
        await { ui { !a.model.busy && a.model.results.size==2 } }
        assertTrue(ui { input.isShown && input.getGlobalVisibleRect(Rect()) })
    }
    private fun resumeSeries()=Title("browse-resume-series","Resume fixture series","series")
    private fun resumeEpisode(season:Int,number:Int)=Episode("browse-resume-series:$season:$number",season,number,"Episode $number")
    private fun openSeriesWithProgress(progress:List<Progress>,resume:Boolean=true) {
        ui {
            val store=Store(a);store.clearHistory();progress.forEach(store::progress)
            a.model.updatePreferences(a.model.preferences.copy(resume=resume))
            a.model.open(Route.Detail(resumeSeries()))
        }
        await { ui { !a.model.busy && a.model.detail?.id=="browse-resume-series" } }
    }
    private fun control(value:String)=runBlocking { Http().text("http://10.0.2.2:8878/__control?$value") }
    private fun resourceCalls(resource:String,id:String)=JSONObject(runBlocking { Http().text("http://10.0.2.2:8878/__state") }).getJSONObject(resource+"_requests").optInt(id)
    private fun awaitEpisodeStreams(episode:Episode) {
        await { shownTag("series-details")!=null && shownTag("stream:Fixture GDrive")!=null && ui {
            a.model.route is Route.Detail && a.model.selectedEpisode?.id==episode.id && !a.model.busy && !a.model.streamsBusy &&
                a.model.streams.isNotEmpty() && a.model.streams.all { it.url.contains("episode="+episode.id) }
        } }
    }
    private fun rectangle(tag:String)=ui {
        val view=a.window.decorView.findTag(tag)!!;val location=IntArray(2);view.getLocationOnScreen(location)
        Rect(location[0],location[1],location[0]+view.width,location[1]+view.height)
    }
    private fun rolloverEpisode(season:Int,number:Int)=Episode("browse-season-rollover:$season:$number",season,number,"Episode $number")
    private fun openRolloverSeries() {
        ui { a.model.open(Route.Detail(Title("browse-season-rollover","Season rollover fixture","series"))) }
        awaitEpisodeStreams(rolloverEpisode(1,1))
        await { ui { a.currentFocus?.tag=="episode:"+rolloverEpisode(1,1).id } }
    }
    private fun moveEpisode(code:Int,episode:Episode) {
        sendRemoteKey(code)
        await { ui {
            a.currentFocus?.tag=="episode:"+episode.id &&
                (a.window.decorView.findTag("season-selector") as TextView).text.toString()==
                    (if(episode.season==0)"Specials" else "Season ${episode.season}")
        } }
    }
    @Test fun episodeRowContinuesBothWaysAcrossSeasonsWithoutLoadingStreamsOnFocus() {
        openRolloverSeries()
        val first=rolloverEpisode(1,1)
        val page=shownTag("series-details")!!;val row=tagged("episodes") as RecyclerView
        val rowBounds=rectangle("episodes");val header=rectangle("series-header")
        val loaded=ui { a.model.streams }
        val order=ui { a.model.detail!!.episodes }
        assertEquals(13,ui { row.adapter?.itemCount });assertTrue(ui { row.childCount<13 })
        assertEquals("On-Demand",ui { (a.window.decorView.findTag("navigation:On-Demand") as TextView).text.toString() })
        moveEpisode(android.view.KeyEvent.KEYCODE_DPAD_LEFT,rolloverEpisode(0,1))
        moveEpisode(android.view.KeyEvent.KEYCODE_DPAD_RIGHT,first)
        order.dropWhile { it.id!=first.id }.drop(1).forEach { episode ->
            moveEpisode(android.view.KeyEvent.KEYCODE_DPAD_RIGHT,episode)
            assertEquals(rowBounds,rectangle("episodes"))
            assertEquals(0,resourceCalls("stream",episode.id))
        }
        // At the series end, Right stays on the last episode rather than jumping out.
        sendRemoteKey(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertEquals("episode:"+rolloverEpisode(12,1).id,ui { a.currentFocus?.tag })
        assertSame(loaded,ui { a.model.streams });assertEquals(first.id,ui { a.model.selectedEpisode?.id })
        assertSame(page,shownTag("series-details"));assertEquals(header,rectangle("series-header"))
        order.drop(1).dropLast(1).asReversed().forEach { episode -> moveEpisode(android.view.KeyEvent.KEYCODE_DPAD_LEFT,episode) }
        assertEquals(first.id,ui { a.model.selectedEpisode?.id });assertSame(loaded,ui { a.model.streams })
        assertEquals(1,resourceCalls("meta","browse-season-rollover"));assertNull(ui { a.activePlayer })
        repeat(8) { index ->
            moveEpisode(android.view.KeyEvent.KEYCODE_DPAD_RIGHT,if(index<7)rolloverEpisode(1,index+2) else rolloverEpisode(3,1))
        }
        sendRemoteKey(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
        awaitEpisodeStreams(rolloverEpisode(3,1))
        assertEquals("Rollover fixture season 3 episode 1.",ui { (tagged("episode-description") as TextView).text.toString() })
        assertSame(page,shownTag("series-details"));assertEquals(header,rectangle("series-header"))
        screenshot("episode-season-rollover")
    }
    @Test fun browsedSeasonSurvivesRecreationAndSeasonPickerTargetsTheChosenSeason() {
        openRolloverSeries()
        repeat(8) { index ->
            moveEpisode(android.view.KeyEvent.KEYCODE_DPAD_RIGHT,if(index<7)rolloverEpisode(1,index+2) else rolloverEpisode(3,1))
        }
        rule.scenario.recreate()
        await { ui {
            a.currentFocus?.tag=="episode:"+rolloverEpisode(3,1).id &&
                (tagged("season-selector") as TextView).text.toString()=="Season 3"
        } }
        assertEquals(rolloverEpisode(1,1).id,ui { a.model.selectedEpisode?.id })
        activateTag("season-selector");click("Season 1")
        await { ui { a.hasWindowFocus() && (tagged("season-selector") as TextView).text.toString()=="Season 1" } }
        sendRemoteKey(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
        await { ui { a.currentFocus?.tag=="episode:"+rolloverEpisode(1,1).id } }
        activateTag("season-selector");click("Season 12")
        awaitEpisodeStreams(rolloverEpisode(12,1))
        await { ui { a.hasWindowFocus() && a.currentFocus?.tag=="season-selector" } }
        moveEpisode(android.view.KeyEvent.KEYCODE_DPAD_DOWN,rolloverEpisode(12,1))
        moveEpisode(android.view.KeyEvent.KEYCODE_DPAD_LEFT,rolloverEpisode(3,9))
        assertEquals(rolloverEpisode(12,1).id,ui { a.model.selectedEpisode?.id })
        assertEquals(0,resourceCalls("stream",rolloverEpisode(3,9).id))
        assertEquals(1,resourceCalls("meta","browse-season-rollover"))
        screenshot("episode-season-picker-return")
    }
    @Test fun episodeRowMatchesStreamRowForRapidAndHeldInput() {
        val id="browse-navigation-fluidity"
        val first=Episode("$id:1:1",1,1,"Episode 1")
        val report=JSONObject().put("fixture",id).put("build",ui { a.packageManager.getPackageInfo(a.packageName,0).versionName })
            .put("eventsPerProfile",18).put("intervalMs",80).put("settleMs",1200).put("deliveryTimeoutMs",12000)
        val profiles=org.json.JSONArray();report.put("profiles",profiles)
        fun saveReport() {
            java.io.File(ui { a.getExternalFilesDir(null)!! },"episode-stream-navigation.json").writeText(report.toString(2))
        }
        fun rowPosition(row:RecyclerView,focus:View?):Int = if(focus==null)-1 else row.findContainingViewHolder(focus)?.bindingAdapterPosition?:-1
        fun focusRowStart(row:RecyclerView) {
            // Cancel any previous remote restoration before resetting this sample.
            sendRemoteKey(KeyEvent.KEYCODE_DPAD_UP)
            ui { row.stopScroll();row.scrollToPosition(0) }
            await { ui {
                row.scrollState==RecyclerView.SCROLL_STATE_IDLE && !row.hasPendingAdapterUpdates() && !row.isLayoutRequested &&
                    row.findViewHolderForAdapterPosition(0)?.itemView?.let { it.left>=row.paddingLeft && it.right<=row.width-row.paddingRight }==true
            } }
            ui {
                val card=row.findViewHolderForAdapterPosition(0)!!.itemView
                descendants(card).first { it.isFocusable && it.isShown }.requestFocus()
            }
            await { ui { rowPosition(row,a.currentFocus)==0 && a.hasWindowFocus() } }
        }
        fun measure(row:RecyclerView,name:String,held:Boolean):JSONObject {
            focusRowStart(row)
            val focusEvents=ArrayList<JSONObject>();val deliveries=ArrayList<JSONObject>();val inputs=org.json.JSONArray()
            val start=SystemClock.uptimeMillis()
            var input=-1;var sentAt=start;var deliveredDowns=0
            val listener=android.view.ViewTreeObserver.OnGlobalFocusChangeListener { _,next ->
                val now=SystemClock.uptimeMillis()
                focusEvents.add(JSONObject().put("atMs",now-start).put("input",input).put("index",rowPosition(row,next))
                    .put("latencyMs",now-sentAt).put("focus",next?.tag?.toString().orEmpty()))
            }
            val observer=ui { a.window.decorView.viewTreeObserver.also { it.addOnGlobalFocusChangeListener(listener) } }
            val instrumentation=InstrumentationRegistry.getInstrumentation()
            val original=ui { checkNotNull(a.window.callback) }
            ui {
                a.window.callback=object:android.view.Window.Callback by original {
                    override fun dispatchKeyEvent(event:KeyEvent):Boolean {
                        if(event.keyCode!=KeyEvent.KEYCODE_DPAD_RIGHT || event.action!=KeyEvent.ACTION_DOWN)return original.dispatchKeyEvent(event)
                        input=deliveredDowns++;sentAt=event.eventTime
                        val before=rowPosition(row,a.currentFocus);val began=SystemClock.uptimeMillis()
                        val handled=original.dispatchKeyEvent(event)
                        deliveries.add(JSONObject().put("input",input).put("atMs",began-start).put("queueMs",began-event.eventTime)
                            .put("handleMs",SystemClock.uptimeMillis()-began).put("before",before).put("after",rowPosition(row,a.currentFocus)))
                        return handled
                    }
                }
            }
            fun key(action:Int,repeat:Int,downTime:Long):Boolean {
                // Do not let synchronous dispatch throttle one row's input profile.
                return instrumentation.uiAutomation.injectInputEvent(KeyEvent(downTime,SystemClock.uptimeMillis(),action,KeyEvent.KEYCODE_DPAD_RIGHT,
                    repeat,0,KeyCharacterMap.VIRTUAL_KEYBOARD,0,KeyEvent.FLAG_FROM_SYSTEM,InputDevice.SOURCE_DPAD),false)
            }
            val profile=JSONObject().put("row",name).put("input",if(held)"held" else "rapid")
            try {
                repeat(18) { index ->
                    val remaining=start+index*80-SystemClock.uptimeMillis()
                    if(remaining>0)SystemClock.sleep(remaining)
                    val sent=SystemClock.uptimeMillis()
                    val accepted=key(KeyEvent.ACTION_DOWN,if(held)index else 0,if(held)start else sent)
                    val released=if(!held)key(KeyEvent.ACTION_UP,0,sent) else true
                    val dispatched=SystemClock.uptimeMillis()
                    inputs.put(JSONObject().put("input",index).put("sentAtMs",sent-start).put("injectMs",dispatched-sent)
                        .put("accepted",accepted && released))
                }
                if(held)key(KeyEvent.ACTION_UP,0,start)
                profile.put("inputTotalMs",SystemClock.uptimeMillis()-start)
                await(12000) { ui { deliveredDowns==18 } }
                profile.put("deliveryTotalMs",SystemClock.uptimeMillis()-start)
                SystemClock.sleep(1200)
            } finally {
                ui { a.window.callback=original;if(observer.isAlive)observer.removeOnGlobalFocusChangeListener(listener) }
                val events=ui { focusEvents.toList() }
                val latencies=events.filter { it.getInt("index")>=0 }.map { it.getLong("latencyMs") }.sorted()
                val indices=listOf(0)+events.map { it.getInt("index") }.filter { it>=0 }
                profile.put("events",org.json.JSONArray(events)).put("inputs",inputs).put("deliveries",org.json.JSONArray(deliveries))
                    .put("totalMs",SystemClock.uptimeMillis()-start).put("achievedIndex",ui { rowPosition(row,a.currentFocus) })
                    .put("maxIndex",indices.max()).put("visitedIndices",org.json.JSONArray(indices.distinct()))
                    .put("focusMoves",events.count { it.getInt("index")>=0 }).put("focusLeftRow",events.count { it.getInt("index")<0 })
                    .put("p95FocusLatencyMs",latencies.getOrNull(((latencies.size*95+99)/100-1).coerceAtLeast(0))?:-1)
                    .put("scrollState",ui { row.scrollState }).put("itemCount",ui { row.adapter!!.itemCount })
                profiles.put(profile);saveReport()
            }
            return profile
        }
        saveReport()
        try {
            ui { a.model.open(Route.Detail(Title(id,"Navigation fluidity fixture","series"))) }
            awaitEpisodeStreams(first)
            val episodes=tagged("episodes") as RecyclerView;val streams=tagged("streams") as RecyclerView
            await { ui { episodes.adapter?.itemCount==40 && streams.adapter?.itemCount==32 && !episodes.hasPendingAdapterUpdates() && !streams.hasPendingAdapterUpdates() } }
            val loaded=ui { a.model.streams }
            assertTrue(ui { episodes.childCount<40 && streams.childCount<32 })
            val nativeRapid=measure(streams,"streams",held=false)
            val episodeRapid=measure(episodes,"episodes",held=false)
            val nativeHeld=measure(streams,"streams",held=true)
            val episodeHeld=measure(episodes,"episodes",held=true)
            val focused=episodeHeld.getInt("achievedIndex")
            report.put("selectedEpisode",ui { a.model.selectedEpisode?.id })
                .put("focusedSeason",ui { (tagged("season-selector") as TextView).text.toString() })
            val state=JSONObject(runBlocking { Http().text("http://10.0.2.2:8878/__state") })
            report.put("streamRequests",state.getJSONObject("stream_requests")).put("metaRequests",state.getJSONObject("meta_requests"))
            saveReport();screenshot("episode-stream-navigation")
            assertEquals(first.id,ui { a.model.selectedEpisode?.id });assertSame(loaded,ui { a.model.streams });assertNull(ui { a.activePlayer })
            val calls=state.getJSONObject("stream_requests")
            assertEquals(1,calls.keys().asSequence().filter { it.startsWith(id+":") }.sumOf { calls.getInt(it) })
            assertEquals(1,resourceCalls("meta",id))
            for((reference,episode) in listOf(nativeRapid to episodeRapid,nativeHeld to episodeHeld)) {
                val label=episode.getString("input")
                val expected=reference.getInt("achievedIndex")
                val summary="episode index=${episode.getInt("achievedIndex")}, stream index=$expected; see episode-stream-navigation.json"
                assertTrue("Native stream $label reference did not traverse its row: $summary",expected>=14)
                assertTrue("Episode $label lost focus: $summary",episode.getInt("achievedIndex")>=0)
                assertTrue("Episode $label dropped too many moves compared with streams: $summary",
                    episode.getInt("achievedIndex")>=maxOf(12,kotlin.math.ceil(expected*.72).toInt()))
                assertTrue("Episode $label focus lagged streams: $summary",
                    episode.getLong("p95FocusLatencyMs")<=maxOf(240L,reference.getLong("p95FocusLatencyMs")*2+100))
                assertEquals("Episode $label is still scrolling after the same settling budget",RecyclerView.SCROLL_STATE_IDLE,episode.getInt("scrollState"))
            }
            assertEquals("Season "+(focused/10+1),ui { (tagged("season-selector") as TextView).text.toString() })
        } catch(failure:Throwable) {
            report.put("failure",failure.message);saveReport();throw failure
        }
    }
    @Test fun seriesResumesNewestUnfinishedEpisodeAndExplicitBackSelectionWins() {
        val series=resumeSeries();val active=resumeEpisode(2,5);val first=resumeEpisode(1,1)
        val progress=listOf(Progress(series,resumeEpisode(1,2),10000,60000,1),Progress(series,active,20000,60000,2),Progress(series,resumeEpisode(2,2),60000,60000,3))
        openSeriesWithProgress(progress)
        awaitEpisodeStreams(active)
        await { ui { a.currentFocus?.tag=="episode:"+active.id } }
        assertTrue(ui { (a.currentFocus as TextView).text.startsWith("Resume · E5") })
        val page=shownTag("series-details")!!;val header=rectangle("series-header")
        assertEquals("Fixture overview for season 2 episode 5.",ui { (a.window.decorView.findTag("episode-description") as TextView).text.toString() })
        assertEquals(1,resourceCalls("meta",series.id))
        activateTag("season-selector");click("Season 1")
        awaitEpisodeStreams(first)
        assertSame(page,shownTag("series-details"));assertEquals(header,rectangle("series-header"))
        assertEquals("Season 1",ui { (a.window.decorView.findTag("season-selector") as TextView).text.toString() })
        assertEquals("Fixture overview for season 1 episode 1.",ui { (a.window.decorView.findTag("episode-description") as TextView).text.toString() })
        val calls=resourceCalls("stream",first.id)
        activateTag("episode:"+first.id)
        assertEquals(calls,resourceCalls("stream",first.id));assertEquals(1,resourceCalls("meta",series.id))
        val streams=tagged("streams") as RecyclerView
        val second=ui { (streams.getChildAt(1) as ViewGroup).getChildAt(0) }
        ui { second.requestFocus() };await { ui { a.currentFocus===second } }
        val context=ui { (second.parent as View).tag };val loaded=ui { a.model.streams }
        screenshot("series-inline-selected-episode")
        sendRemoteKey(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
        await { ui { (a.model.route as? Route.Player)?.watch?.episode?.id==first.id && a.activePlayer!=null && !streams.isShown } }
        back()
        awaitEpisodeStreams(first)
        await { ui { (a.currentFocus?.parent as? View)?.tag==context } }
        assertSame(loaded,ui { a.model.streams });assertEquals(calls,resourceCalls("stream",first.id));assertEquals(1,resourceCalls("meta",series.id))
        assertEquals(header,rectangle("series-header"))
        rule.scenario.recreate()
        await { ui { a.model.selectedEpisode?.id==first.id && (a.currentFocus?.parent as? View)?.tag==context } }
        assertEquals("Season 1",ui { (a.window.decorView.findTag("season-selector") as TextView).text.toString() })
        ui {
            val store=Store(a);store.clearHistory();progress.forEach(store::progress)
            a.model.toggle(series);a.model.top(Section.SEARCH)
        }
        await { tagged("search-home")?.isShown==true && ui { !a.model.busy } }
        ui { (a.window.decorView.findTag("search-home") as RecyclerView).scrollToPosition(0) }
        activateTag("title:"+series.key,within="shelf:Continue watching")
        awaitEpisodeStreams(active)
        assertNull(ui { a.activePlayer })
        sendRemoteKey(android.view.KeyEvent.KEYCODE_BACK)
        await { tagged("search-home")?.isShown==true && ui { a.model.route==Route.Browse(Section.SEARCH) && !a.model.busy } }
        ui { (a.window.decorView.findTag("search-home") as RecyclerView).scrollToPosition(1) }
        activateTag("title:"+series.key,within="shelf:My list")
        awaitEpisodeStreams(active)
        await { ui { a.currentFocus?.tag=="episode:"+active.id } }
        assertEquals("Season 2",ui { (a.window.decorView.findTag("season-selector") as TextView).text.toString() })
    }
    @Test fun disabledResumeUsesNormalFirstSeasonWithoutResumeLabel() {
        openSeriesWithProgress(listOf(Progress(resumeSeries(),resumeEpisode(2,5),20000,60000,1)),resume=false)
        await { ui { a.currentFocus?.tag=="episode:"+resumeEpisode(1,1).id } }
        assertEquals("Season 1",ui { (a.window.decorView.findTag("season-selector") as TextView).text.toString() })
        assertFalse(ui { descendants(a.window.decorView.findTag("episodes")!!).filterIsInstance<TextView>().any { it.text.startsWith("Resume") } })
    }
    @Test fun seriesRefreshRenewsLinksPreservesSourceAndDoesNotStealEpisodeFocus() {
        val series=resumeSeries();val first=resumeEpisode(1,1);val second=resumeEpisode(1,2)
        openSeriesWithProgress(emptyList());awaitEpisodeStreams(first)
        val page=shownTag("series-details")!!;val header=rectangle("series-header")
        val viewport=ui { tagged("stream-viewport")!!.height }
        val list=tagged("streams") as RecyclerView
        val source=ui { (list.getChildAt(1) as ViewGroup).getChildAt(0) }
        ui { source.requestFocus() };await { ui { a.currentFocus===source } }
        val old=ui { a.model.streams[1].identity }
        control("renew_streams=1")
        activateTag("button:Refresh streams",within="series-details")
        awaitEpisodeStreams(first)
        await { ui { (a.currentFocus?.parent as? View)?.tag=="stream-source:"+a.model.streams[1].identity } }
        assertNotEquals(old,ui { a.model.streams[1].identity })
        assertEquals(1,resourceCalls("meta",series.id));assertSame(page,shownTag("series-details"))
        assertEquals(header,rectangle("series-header"));assertEquals(viewport,ui { tagged("stream-viewport")!!.height })
        control("slow_streams=1&renew_streams=1")
        activateTag("button:Refresh streams",within="series-details")
        await { ui { a.model.streamsBusy } }
        ui { a.window.decorView.findTag("episode:"+first.id)!!.requestFocus() }
        sendRemoteKey(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
        await { ui { a.currentFocus?.tag=="episode:"+second.id } }
        awaitEpisodeStreams(first)
        assertEquals("episode:"+second.id,ui { a.currentFocus?.tag });assertEquals(first.id,ui { a.model.selectedEpisode?.id })
        assertEquals(viewport,ui { tagged("stream-viewport")!!.height })
        control("slow_streams=0")
        sendRemoteKey(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
        awaitEpisodeStreams(second)
        assertEquals(1,resourceCalls("meta",series.id));assertSame(page,shownTag("series-details"))
        control("slow_streams=1&fail_streams=1")
        activateTag("button:Refresh streams",within="series-details")
        await { ui { a.model.streamsBusy } }
        assertEquals(viewport,ui { tagged("stream-viewport")!!.height })
        await { find("Retry streams")!=null && ui { !a.model.streamsBusy && a.model.streamsError!=null } }
        assertEquals(header,rectangle("series-header"));assertEquals(viewport,ui { tagged("stream-viewport")!!.height })
        control("slow_streams=0&fail_streams=0")
        click("Retry streams");awaitEpisodeStreams(second)
        await { ui { a.currentFocus?.tag=="stream:Fixture GDrive" } }
        val metadataFocus=ui { (a.currentFocus?.parent as? View)?.tag }
        val currentLinks=ui { a.model.streams }
        control("updated_episodes=1")
        ui { a.model.retry() }
        awaitEpisodeStreams(second)
        await { ui { (a.window.decorView.findTag("episode-description") as TextView).text.toString()=="Updated fixture overview for season 1 episode 2." } }
        assertEquals(2,resourceCalls("meta",series.id));assertEquals(header,rectangle("series-header"))
        assertSame(currentLinks,ui { a.model.streams })
        assertEquals(metadataFocus,ui { (a.currentFocus?.parent as? View)?.tag })
        assertEquals(viewport,ui { tagged("stream-viewport")!!.height })
        assertEquals(ui { a.dp(232) },ui { (tagged("streams") as RecyclerView).getChildAt(0).width })
        assertEquals(ui { a.dp(144) },ui { (tagged("streams") as RecyclerView).getChildAt(0).height })
        val requests=resourceCalls("stream",second.id)
        val addon=ui { a.model.addons.first { it.id!="com.linvo.cinemeta" }.manifestUrl }
        ui { a.model.setAddonEnabled(addon,false) }
        await { find("Manage add-ons")!=null && ui { !a.model.busy && !a.model.streamsBusy && a.model.streams.isEmpty() } }
        assertEquals(second.id,ui { a.model.selectedEpisode?.id });assertSame(page,shownTag("series-details"))
        assertEquals(viewport,ui { tagged("stream-viewport")!!.height })
        ui { a.model.setAddonEnabled(addon,true) }
        awaitEpisodeStreams(second)
        assertEquals(requests+1,resourceCalls("stream",second.id));assertSame(page,shownTag("series-details"))
        assertEquals(header,rectangle("series-header"))
        screenshot("series-inline-renewed-context")
    }
    @Test fun continueWatchingRetainsStoredEpisodeAndPlaysWhenMetadataFails() {
        val series=resumeSeries();val episode=resumeEpisode(2,5).copy(description="Stored fixture episode overview.")
        control("fail_meta=1")
        ui { a.model.saveProgress(Watch(series,Stream("Fixture","http://10.0.2.2:8878/hls/fixture/orig.m3u8"),episode),20000,60000) }
        val home=tagged("search-home") as RecyclerView
        await { ui { home.adapter?.itemCount==5 && !home.hasPendingAdapterUpdates() } }
        ui { home.scrollToPosition(0) }
        activateTag("title:"+series.key,within="shelf:Continue watching")
        await { shownTag("series-details")!=null && shownTag("stream:Fixture GDrive")!=null && ui {
            a.model.route is Route.Detail && a.model.selectedEpisode?.id==episode.id && !a.model.busy && !a.model.streamsBusy && a.model.error!=null
        } }
        assertEquals(episode.description,ui { (a.window.decorView.findTag("episode-description") as TextView).text.toString() })
        assertEquals(episode.description,ui { Store(a).progress().first().episode?.description })
        activateTag("stream:Fixture GDrive",within="stream-choices")
        await { ui { (a.model.route as? Route.Player)?.watch?.episode?.id==episode.id && a.activePlayer!=null } }
        playing()
        assertEquals(episode.description,ui { (a.model.route as Route.Player).watch.episode?.description })
    }
    @Test fun finishedOrMissingEpisodeProgressFallsBackToNormalSelection() {
        val series=resumeSeries()
        openSeriesWithProgress(listOf(
            Progress(series,resumeEpisode(2,5),60000,60000,1),
            Progress(series,resumeEpisode(9,9),10000,60000,2)))
        await { ui { a.currentFocus?.tag=="episode:"+resumeEpisode(1,1).id } }
        assertEquals("Season 1",ui { (a.window.decorView.findTag("season-selector") as TextView).text.toString() })
        assertFalse(ui { descendants(a.window.decorView.findTag("episodes")!!).filterIsInstance<TextView>().any { it.text.startsWith("Resume") } })
    }
    @Test fun sameStreamUrlWithDifferentHeadersKeepsBothChoicesAndRestoresTheChosenOne() {
        ui { a.model.open(Route.Sources(Title("browse-header-variants","Header variants fixture"))) }
        await { ui { !a.model.busy && a.model.streams.size==2 && (a.window.decorView.findTag("streams") as? RecyclerView)?.childCount==2 } }
        val variants=ui { a.model.streams }
        assertEquals(variants[0].url,variants[1].url)
        assertNotEquals(variants[0].headers,variants[1].headers)
        assertNotEquals(variants[0].identity,variants[1].identity)
        val streams=tagged("streams") as RecyclerView
        val first=ui { (streams.getChildAt(0) as ViewGroup).getChildAt(0) }
        val second=ui { (streams.getChildAt(1) as ViewGroup).getChildAt(0) }
        ui { first.requestFocus() }
        await { ui { a.currentFocus===first } }
        sendRemoteKey(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
        await { ui { a.currentFocus===second } }
        val context=ui { (second.parent as View).tag }
        sendRemoteKey(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
        await { ui { (a.model.route as? Route.Player)?.watch?.stream?.identity==variants[1].identity } }
        assertEquals(variants[1].headers,ui { (a.model.route as Route.Player).watch.headers })
        await { ui { a.activePlayer!=null && !streams.isShown } }
        back()
        await { ui { a.model.route is Route.Sources && (a.currentFocus?.parent as? View)?.tag==context } }
        assertEquals(2,ui { (a.window.decorView.findTag("streams") as RecyclerView).adapter?.itemCount })
    }
    @Test fun streamRowsLeadWithKnownMetadataAndLongPressKeepsCompleteDetails() {
        ui { a.model.open(Route.Sources(Title("browse-stream-metadata","Stream metadata fixture",year="2026"))) }
        await { ui { !a.model.busy && (a.window.decorView.findTag("streams") as? RecyclerView)?.childCount==2 } }
        val streams=tagged("streams") as RecyclerView
        assertEquals("Movie · 2026",ui { (a.window.decorView.findTag("source-context") as TextView).text.toString() })
        assertEquals(RecyclerView.HORIZONTAL,ui { (streams.layoutManager as LinearLayoutManager).orientation })
        assertTrue(ui { streams.height<a.window.decorView.height/2 })
        fun headline(index:Int)=ui { descendants(streams.getChildAt(index)).filterIsInstance<TextView>().first().text.toString() }
        val large=headline(0);val small=headline(1)
        assertTrue(large.contains("2160p") && large.contains("55.5 GB") && large.contains("HEVC"))
        assertTrue(small.contains("1080p") && small.contains("8.2 GB") && small.contains("H.264"))
        assertNotEquals(large,small);assertTrue(large.startsWith("Fixture GDrive"));assertTrue(small.startsWith("Fixture GDrive"))
        assertTrue(ui { descendants(streams.getChildAt(0)).filterIsInstance<TextView>().any { it.text.contains("TrueHD Atmos") && it.text.contains("English") } })
        assertNotNull(find("Hold OK for stream details"))
        val primary=ui { (streams.getChildAt(0) as ViewGroup).getChildAt(0) }
        ui { primary.requestFocus() }
        device.executeShellCommand("input keyevent --longpress 23")
        await { device.findObject(androidx.test.uiautomator.By.text("Stream details"))!=null }
        assertNotNull(device.findObject(androidx.test.uiautomator.By.textContains("TrueHD Atmos")))
        assertNotNull(device.findObject(androidx.test.uiautomator.By.textContains("Fixture GDrive")))
        assertTrue(ui { a.model.route is Route.Sources });assertNull(ui { a.activePlayer })
        click("Done");await { ui { a.hasWindowFocus() && a.currentFocus===primary } }
        val second=ui { (streams.getChildAt(1) as ViewGroup).getChildAt(0) }
        sendRemoteKey(android.view.KeyEvent.KEYCODE_DPAD_RIGHT);await { ui { a.currentFocus===second } }
        assertNull(ui { a.activePlayer })
        sendRemoteKey(android.view.KeyEvent.KEYCODE_DPAD_LEFT);await { ui { a.currentFocus===primary } }
        sendRemoteKey(android.view.KeyEvent.KEYCODE_DPAD_RIGHT);await { ui { a.currentFocus===second } }
        screenshot("stream-metadata-summary")
        val context=ui { (second.parent as View).tag }
        sendRemoteKey(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
        await { ui { (a.model.route as? Route.Player)?.watch?.stream?.url?.endsWith("source=small")==true } }
        assertEquals("small",ui { (a.model.route as Route.Player).watch.headers.entries.firstOrNull{it.key.equals("X-Fixture-Source",true)}?.value })
        await { ui { a.activePlayer!=null && !streams.isShown } }
        back()
        await { ui { a.model.route is Route.Sources && (a.currentFocus?.parent as? View)?.tag==context } }
    }
}
