@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package xyz.billybishop4.fleet.tv

import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Layout, remote and return-path checks against generated content on worker emulators. */
class YouTubeTvLayoutTest:NativeTestBase() {
    private val base="http://10.0.2.2:8878"
    private fun control(q:String)=runBlocking{Http().text("$base/youtube/control?$q")}
    @Before fun prepare() {
        control("reset=1")
        ui { a.model.top(Section.SEARCH);a.model.server(base);a.model.store.youTubeCredentials().write(null)
            a.model.youtubeFixture(base);a.model.top(Section.YOUTUBE) }
        await{tagged("youtube-page")?.isShown==true && ui{!a.model.youtube.busy}}
    }
    @After fun cleanup(){ui{a.model.top(Section.SEARCH);a.model.store.youTubeCredentials().write(null)};control("reset=1")}
    private fun home() {
        // Use the account transition: the client intentionally caches credentials after its first read.
        control("approve=1");ui{a.model.youtube.startSignIn()}
        await{ui{!a.model.youtube.busy && a.model.youtube.account!=null && a.model.youtube.homeShelves.size==3} && tagged("youtube-video:V0000000001")?.isShown==true}
    }
    private fun bounds(view:View)=Rect().apply{ui{view.getGlobalVisibleRect(this)}}
    private fun returnFromPlayer() {
        ui{a.playback!!.dismissControls();a.onBackPressedDispatcher.onBackPressed()}
        await{ui{a.player==null && a.model.route==Route.Browse(Section.YOUTUBE)} && tagged("youtube-page")?.isShown==true}
    }
    @Test fun homeUsesRealHorizontalShelvesWithPosterOnlyFocusAndBoundedViews() {
        home()
        assertEquals(listOf("Recommended","New to you","Shorts"),ui{a.model.youtube.homeShelves.map{it.name}})
        val list=tagged("youtube-home") as RecyclerView
        assertEquals(3,ui{list.adapter!!.itemCount});assertTrue(ui{list.childCount<=3})
        val row=ui{list.getChildAt(0) as YouTubeShelfView};assertTrue(ui{row.list.adapter!!.itemCount==12 && row.list.childCount<8})
        val card=tagged("youtube-video:V0000000001")!!;ui{card.requestFocus()}
        val image=ui{descendants(card).filterIsInstance<ImageView>().single()}
        assertTrue(ui{image.drawableState.contains(android.R.attr.state_focused)})
        assertNull(ui{card.background});assertTrue(bounds(card).bottom>bounds(image).bottom)
        assertTrue(bounds(tagged("youtube-video:V0000000002")!!).left>bounds(card).right)
        assertFalse(ui{descendants(a.window.decorView).any{it is android.webkit.WebView}})
        screenshot("youtube-tv-home-shelves")
    }
    @Test fun railExpandsWithoutMovingOrResizingTheHomeContent() {
        home();val first=tagged("youtube-video:V0000000001")!!;ui{first.requestFocus()}
        await{ui{tagged("youtube-navigation")!!.width==a.dp(52)}}
        val before=bounds(tagged("youtube-home")!!)
        sendRemoteKey(KeyEvent.KEYCODE_DPAD_LEFT)
        await{ui{a.currentFocus?.contentDescription=="YouTube home" && tagged("youtube-navigation")!!.width==a.dp(164)}}
        assertEquals(before,bounds(tagged("youtube-home")!!));screenshot("youtube-tv-expanded-rail")
        sendRemoteKey(KeyEvent.KEYCODE_DPAD_RIGHT)
        await{ui{a.currentFocus?.tag=="youtube-video:V0000000001" && tagged("youtube-navigation")!!.width==a.dp(52)}}
        assertEquals(before,bounds(tagged("youtube-home")!!))
    }
    @Test fun homeHasOneCompactSearchActionAndGlobalNavigationReturnsToRememberedContent() {
        home();val first=tagged("youtube-video:V0000000003")!!
        assertFalse(ui{tagged("youtube-search-header")!!.isShown})
        val search=tagged("youtube-home-search")!!
        assertTrue(ui{search.isFocusable && search.width in a.dp(220)..a.dp(240) && search.height in a.dp(32)..a.dp(38)})
        assertFalse(search is EditText);assertTrue(bounds(search).width()<bounds(tagged("youtube-home")!!).width()/2)
        ui{first.requestFocus()};sendRemoteKey(KeyEvent.KEYCODE_DPAD_UP)
        await{ui{a.currentFocus===search}}
        screenshot("youtube-home-search-pill")
        sendRemoteKey(KeyEvent.KEYCODE_DPAD_UP)
        await{ui{a.currentFocus?.tag=="navigation:YouTube"}}
        sendRemoteKey(KeyEvent.KEYCODE_DPAD_DOWN)
        await{ui{a.currentFocus===first && first.isShown}}
        sendRemoteKey(KeyEvent.KEYCODE_DPAD_UP);await{ui{a.currentFocus===search}}
        sendRemoteKey(KeyEvent.KEYCODE_DPAD_DOWN);await{ui{a.currentFocus===first}}
        sendRemoteKey(KeyEvent.KEYCODE_DPAD_UP);await{ui{a.currentFocus===search}}
        sendRemoteKey(KeyEvent.KEYCODE_DPAD_CENTER)
        await{ui{a.model.youtube.feed==YouTubeFeed.SEARCH && tagged("youtube-keyboard")?.isShown==true}}
        assertFalse(ui{search.isShown})
        ui{tagged("navigation:YouTube")!!.requestFocus()};sendRemoteKey(KeyEvent.KEYCODE_DPAD_DOWN)
        await{ui{a.currentFocus?.isShown==true && generateSequence(a.currentFocus){it.parent as? View}.any{it===tagged("youtube-page")}}}
        screenshot("youtube-compact-home-search-navigation")
    }
    @Test fun horizontalSearchKeepsEveryMoveAndTheRailAndKeyboardReturnToTheSameVideo() {
        click("YouTube search");field("Search YouTube","fixture");click("Search YouTube videos")
        await{ui{!a.model.youtube.busy && a.model.youtube.videos.size>=32 && a.currentFocus?.tag=="youtube-video:V0000000001"}}
        repeat(8){sendRemoteKey(KeyEvent.KEYCODE_DPAD_RIGHT)}
        await{ui{a.currentFocus?.tag=="youtube-video:V0000000009"}}
        repeat(8){sendRemoteKey(KeyEvent.KEYCODE_DPAD_LEFT)}
        await{ui{a.currentFocus?.tag=="youtube-video:V0000000001"}}
        sendRemoteKey(KeyEvent.KEYCODE_DPAD_LEFT)
        await{ui{a.currentFocus?.contentDescription=="YouTube search"}}
        sendRemoteKey(KeyEvent.KEYCODE_DPAD_RIGHT)
        await{ui{a.currentFocus?.tag=="youtube-video:V0000000001"}}
        repeat(18){sendRemoteKey(KeyEvent.KEYCODE_DPAD_RIGHT)}
        await{ui{a.currentFocus?.tag=="youtube-video:V0000000019"}}
        sendRemoteKey(KeyEvent.KEYCODE_DPAD_UP);await{tagged("youtube-keyboard")?.hasFocus()==true}
        ui{a.onBackPressedDispatcher.onBackPressed()};await{ui{a.currentFocus?.tag=="youtube-video:V0000000019"}}
        assertNull(player());assertFalse(ui{a.model.youtube.resolving})
        screenshot("youtube-search-horizontal-focus-and-return")
    }
    @Test fun homeCanScrollAcrossUnattachedCardsWithTheRemoteAndReturn() {
        home();ui{tagged("youtube-video:V0000000001")!!.requestFocus()}
        repeat(8){sendRemoteKey(KeyEvent.KEYCODE_DPAD_RIGHT)}
        await{ui{a.currentFocus?.tag=="youtube-video:V0000000009"}}
        repeat(8){sendRemoteKey(KeyEvent.KEYCODE_DPAD_LEFT)}
        await{ui{a.currentFocus?.tag=="youtube-video:V0000000001"}}
        assertNull(player());assertFalse(ui{a.model.youtube.resolving})
    }
    @Test fun homeReturnsToTheSameShelfAndVideoAfterPlayback() {
        home();val home=tagged("youtube-home") as RecyclerView
        ui{home.scrollToPosition(1)}
        await{tagged("youtube-video:V0000000015")?.isShown==true}
        ui{tagged("youtube-video:V0000000015")!!.requestFocus()};sendRemoteKey(KeyEvent.KEYCODE_DPAD_CENTER);playing()
        returnFromPlayer();await{ui{a.currentFocus?.tag=="youtube-video:V0000000015"}}
        assertEquals(YouTubeFeed.HOME,ui{a.model.youtube.feed})
        assertTrue(ui{generateSequence(a.currentFocus){it.parent as? View}.filterIsInstance<YouTubeShelfView>().single().shelfKey.startsWith("New to you")})
    }
    @Test fun searchHasATvKeyboardAndSubmittingMovesDirectlyToResults() {
        click("YouTube search");await{tagged("youtube-keyboard")?.isShown==true}
        val keyboard=tagged("youtube-keyboard")!!;ui{keyboard.requestFocus()}
        sendRemoteKey(KeyEvent.KEYCODE_DPAD_CENTER);sendRemoteKey(KeyEvent.KEYCODE_DPAD_RIGHT);sendRemoteKey(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals("ab",ui{a.model.youtube.query})
        repeat(4){sendRemoteKey(KeyEvent.KEYCODE_DPAD_DOWN)}
        repeat(2){sendRemoteKey(KeyEvent.KEYCODE_DPAD_RIGHT)}
        sendRemoteKey(KeyEvent.KEYCODE_DPAD_CENTER)
        await{ui{!a.model.youtube.busy && a.currentFocus?.tag=="youtube-video:V0000000001"}}
        assertEquals(View.GONE,ui{tagged("youtube-search-tools")!!.visibility})
        assertEquals(listOf("ab"),ui{a.model.youtube.recentQueries});screenshot("youtube-tv-search-results")
        sendRemoteKey(KeyEvent.KEYCODE_DPAD_UP);await{ui{keyboard.isShown && keyboard.hasFocus()}}
        assertTrue(bounds(keyboard).bottom<=bounds(tagged("youtube-search-results")!!).top);screenshot("youtube-tv-search-keyboard")
    }
    @Test fun querySuggestionsAreRemoteSelectableAndDoNotStealKeyboardFocus() {
        click("YouTube search");field("Search YouTube","fixture")
        val keyboard=tagged("youtube-keyboard")!!;ui{keyboard.requestFocus()}
        await{ui{a.model.youtube.searchSuggestions.isNotEmpty()}}
        val suggestion=ui{a.model.youtube.searchSuggestions.first()}
        await{tagged("youtube-search-suggestion:0")?.contentDescription=="Search YouTube for $suggestion"}
        // Start the next query with the current results already attached, so the old and
        // new response's shared V1 ID exercise the asynchronous outer-shelf replacement.
        await{ui{!a.model.youtube.busy && a.model.youtube.query=="fixture" && a.model.youtube.videos.isNotEmpty() &&
            tagged("youtube-video:V0000000001")?.takeIf{it.isShown}?.let { card->
                generateSequence(card){it.parent as? View}.filterIsInstance<YouTubeShelfView>().firstOrNull()?.stateKey==
                    "SEARCH/fixture/${a.model.youtube.searchShelves.firstOrNull()?.key}"
            }==true}}
        assertTrue(ui{keyboard.hasFocus()})
        sendRemoteKey(KeyEvent.KEYCODE_DPAD_LEFT)
        await{ui{a.currentFocus?.tag=="youtube-search-suggestion:0"}}
        screenshot("youtube-tv-live-suggestions")
        sendRemoteKey(KeyEvent.KEYCODE_DPAD_RIGHT);assertTrue(ui{keyboard.hasFocus()})
        sendRemoteKey(KeyEvent.KEYCODE_DPAD_LEFT);sendRemoteKey(KeyEvent.KEYCODE_DPAD_CENTER)
        await{ui{!a.model.youtube.busy && a.model.youtube.query==suggestion && a.currentFocus?.tag=="youtube-video:V0000000001"}}
        assertEquals(suggestion,ui{a.model.youtube.recentQueries.first()})
        assertFalse(ui{tagged("youtube-search-tools")!!.isShown})
        assertNull(player());screenshot("youtube-tv-search-suggestions")
        // Pending completion starts with a matching recent search. Keep that exact choice
        // when it survives, and leave the row if the server replaces it with different text.
        control("slow_suggestions=1")
        sendRemoteKey(KeyEvent.KEYCODE_DPAD_UP);await{ui{keyboard.hasFocus()}}
        field("Search YouTube","fixture")
        await{tagged("youtube-search-suggestion:0")?.contentDescription=="Search YouTube for $suggestion"}
        ui{tagged("youtube-search-suggestion:0")!!.requestFocus()}
        await{ui{a.model.youtube.searchSuggestions.firstOrNull()==suggestion}}
        assertEquals("Search YouTube for $suggestion",ui{a.currentFocus?.contentDescription})
        field("Search YouTube","fi")
        await{tagged("youtube-search-suggestion:0")?.contentDescription=="Search YouTube for $suggestion"}
        ui{tagged("youtube-search-suggestion:0")!!.requestFocus()}
        await{ui{a.model.youtube.searchSuggestions.firstOrNull()=="fi videos" && keyboard.hasFocus()}}
        assertEquals("fi",ui{a.model.youtube.query});assertEquals(suggestion,ui{a.model.youtube.recentQueries.first()})
    }
    @Test fun shelfAndGridEndKeysKeepTheSelectedVideoInsteadOfJumpingAway() {
        home();ui{tagged("youtube-video:V0000000001")!!.requestFocus()}
        repeat(11){sendRemoteKey(KeyEvent.KEYCODE_DPAD_RIGHT)}
        await{ui{a.currentFocus?.tag=="youtube-video:V0000000012"}}
        repeat(3){sendRemoteKey(KeyEvent.KEYCODE_DPAD_RIGHT)}
        assertEquals("youtube-video:V0000000012",ui{a.currentFocus?.tag})
        val clips=(1..17).map{YouTubeVideo("S"+it.toString().padStart(10,'0'),"Generated short $it",isShort=true)}
        ui{a.model.youtube.installFixturePage(YouTubeFeed.SHORTS,YouTubePage(clips))}
        await{ui{!a.model.youtube.busy && a.model.youtube.videos==clips}}
        val last=clips.last().id
        ui{(tagged("youtube-results") as RecyclerView).scrollToPosition(clips.lastIndex)}
        await{tagged("youtube-video:$last")?.isShown==true};ui{tagged("youtube-video:$last")!!.requestFocus()}
        sendRemoteKey(KeyEvent.KEYCODE_DPAD_DOWN);sendRemoteKey(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals("youtube-video:$last",ui{a.currentFocus?.tag});assertNull(player())
    }
    @Test fun clearingAndDeletingUnicodeKeepSearchOpenWithoutLoadingHome() {
        click("YouTube search");field("Search YouTube","café😀")
        ui{(tagged("field:Search YouTube") as EditText).let{it.setSelection(it.text.length)}}
        val keyboard=tagged("youtube-keyboard")!!;ui{keyboard.requestFocus()};sendRemoteKey(KeyEvent.KEYCODE_DEL)
        assertEquals("café",ui{a.model.youtube.query})
        field("Search YouTube","")
        assertEquals(YouTubeFeed.SEARCH,ui{a.model.youtube.feed});assertTrue(ui{keyboard.isShown});assertTrue(ui{a.model.youtube.videos.isEmpty()})
        ui{a.onBackPressedDispatcher.onBackPressed()};await{ui{a.model.youtube.feed==YouTubeFeed.HOME && !a.model.youtube.busy && a.model.youtube.videos.isNotEmpty()}}
        assertEquals(YouTubeFeed.HOME,ui{a.model.youtube.feed})
    }
    @Test fun backClosesAnExpandedSearchKeyboardWithoutDiscardingTheResults() {
        click("YouTube search");field("Search YouTube","fixture");click("Search YouTube videos")
        await{ui{!a.model.youtube.busy && a.currentFocus?.tag=="youtube-video:V0000000001"}}
        val results=ui{a.model.youtube.videos}
        sendRemoteKey(KeyEvent.KEYCODE_DPAD_UP)
        await{ui{tagged("youtube-keyboard")!!.hasFocus()}}
        ui{a.onBackPressedDispatcher.onBackPressed()}
        await{ui{a.currentFocus?.tag=="youtube-video:V0000000001" && !tagged("youtube-search-tools")!!.isShown}}
        assertEquals(YouTubeFeed.SEARCH,ui{a.model.youtube.feed});assertEquals("fixture",ui{a.model.youtube.query})
        assertSame(results,ui{a.model.youtube.videos});assertNull(player())
    }
    @Test fun keyboardSkipsItsUnusedCellInsteadOfOfferingAnActionThatDoesNothing() {
        click("YouTube search");val keyboard=tagged("youtube-keyboard")!!
        ui{keyboard.requestFocus()};repeat(3){sendRemoteKey(KeyEvent.KEYCODE_DPAD_DOWN)}
        repeat(6){sendRemoteKey(KeyEvent.KEYCODE_DPAD_RIGHT)}
        val description=ui{keyboard.contentDescription.toString()}
        sendRemoteKey(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(description,ui{keyboard.contentDescription.toString()})
        assertFalse(description.contains("·"));sendRemoteKey(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals("'",ui{a.model.youtube.query})
    }
    @Test fun shortsKeepPortraitBrowseCardsAndDoNotResolveVideosOnFocus() {
        click("YouTube Shorts");await{ui{!a.model.youtube.busy && a.model.youtube.videos.isNotEmpty()}}
        val id=ui{a.model.youtube.videos.first().id};await{tagged("youtube-video:$id")?.isShown==true}
        val card=tagged("youtube-video:$id")!!;ui{card.requestFocus()}
        val image=ui{descendants(card).filterIsInstance<ImageView>().single()};assertTrue(ui{image.height>image.width})
        repeat(4){sendRemoteKey(KeyEvent.KEYCODE_DPAD_RIGHT)}
        assertNull(player());assertFalse(ui{a.model.youtube.resolving});screenshot("youtube-tv-shorts")
    }
    @Test fun commentsAreBesideTheVideoAndTabSwitchingKeepsTheDecoder() {
        ui{a.model.youtube.play(YouTubeVideo("V0000000001","Generated video"))};playing()
        val player=player();val initial=ui{a.player!!.videoDecoderCounters!!.decoderInitCount}
        click("Video comments");await{tagged("youtube-comment:comment-1")?.isShown==true}
        val panel=tagged("youtube-watch-panel")!!;val parent=ui{panel.parent as View}
        assertEquals(bounds(parent).right,bounds(panel).right);assertEquals(ui{parent.height},ui{panel.height})
        assertTrue(ui{panel.width<parent.width/2})
        val video=ui{descendants(a.window.decorView).filterIsInstance<PlayerView>().single()}
        assertEquals(ui{panel.width},ui{video.paddingRight});screenshot("youtube-tv-right-comments")
        click("Related videos tab");await{tagged("youtube-video:V0000000040")?.isShown==true}
        assertEquals(ui{parent.width},ui{panel.width});assertEquals(0,ui{video.paddingRight});assertTrue(ui{panel.height<parent.height})
        screenshot("youtube-tv-related-row")
        click("Comments tab");await{tagged("youtube-comment:comment-1")?.isShown==true}
        click("Close video panel");assertEquals(0,ui{video.paddingRight});assertSame(player,player())
        assertEquals(initial,ui{a.player!!.videoDecoderCounters!!.decoderInitCount});assertTrue(ui{a.player!!.isPlaying})
    }
    @Test fun fixedArtworkDoesNotRemeasureParentsButStillResizesAndDraws() {
        ui {
            var requests=0
            val root=object:FrameLayout(a){override fun requestLayout(){super.requestLayout();requests++}}
            val image=FixedArtworkView(a);root.addView(image,FrameLayout.LayoutParams(200,110))
            fun layout(){root.measure(View.MeasureSpec.makeMeasureSpec(400,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(240,View.MeasureSpec.EXACTLY));root.layout(0,0,400,240)}
            layout();requests=0
            val drawable=ColorDrawable(Color.RED);image.setImageDrawable(drawable)
            assertSame(drawable,image.drawable);assertEquals(0,requests)
            image.layoutParams=FrameLayout.LayoutParams(240,135);assertTrue(requests>0);layout();assertEquals(240,image.width);assertEquals(135,image.height)
            image.layoutParams=FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,110);layout();requests=0
            image.setImageDrawable(object:ColorDrawable(Color.BLUE){override fun getIntrinsicWidth()=64;override fun getIntrinsicHeight()=36})
            assertTrue("Dynamic artwork must retain normal measurement",requests>0)
        }
    }
    @Test fun watchedThumbnailProgressIsProportionalAndNeverSurvivesAnUnwatchedRebind() {
        ui {
            val card=YouTubeCard(a,a.artwork,false)
            val watched=YouTubeVideo("V0000000001","Watched video",duration="2:00",resumePositionMs=30000)
            card.bind(watched){}
            val progress=card.findTag("youtube-resume-progress")!!
            assertEquals(View.VISIBLE,progress.visibility)
            progress.layout(0,0,100,4)
            val bitmap=Bitmap.createBitmap(100,4,Bitmap.Config.ARGB_8888)
            progress.draw(Canvas(bitmap))
            assertEquals(ACCENT,bitmap.getPixel(10,2));assertNotEquals(ACCENT,bitmap.getPixel(75,2));bitmap.recycle()
            card.bind(watched.copy(resumePositionMs=null)){};assertEquals(View.GONE,progress.visibility)
            card.bind(watched.copy(duration="LIVE",live=true)){};assertEquals(View.GONE,progress.visibility)
            card.bind(watched.copy(duration="2:99")){};assertEquals(View.GONE,progress.visibility)
            card.bind(watched.copy(isShort=true)){};assertEquals(View.GONE,progress.visibility)
            val percentOnly=watched.copy(duration="",resumePositionMs=null,watchedPercent=50)
            card.bind(percentOnly){};assertEquals(View.VISIBLE,progress.visibility)
            val partial=Bitmap.createBitmap(100,4,Bitmap.Config.ARGB_8888);progress.draw(Canvas(partial))
            assertEquals(ACCENT,partial.getPixel(25,2));assertNotEquals(ACCENT,partial.getPixel(75,2));partial.recycle()
            assertNull("A rounded percentage never creates a playback resume timestamp",percentOnly.resumePositionMs)
            card.bind(percentOnly.copy(live=true)){};assertEquals(View.GONE,progress.visibility)
            card.bind(percentOnly.copy(isShort=true)){};assertEquals(View.GONE,progress.visibility)
            card.bind(percentOnly.copy(watchedPercent=101)){};assertEquals(View.GONE,progress.visibility)
            card.bind(watched){};assertEquals(View.VISIBLE,progress.visibility)
            card.release();assertEquals(View.GONE,progress.visibility)
        }
    }
}
