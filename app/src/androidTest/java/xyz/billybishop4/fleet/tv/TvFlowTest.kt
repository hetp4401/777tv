@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package xyz.billybishop4.fleet.tv

import android.os.SystemClock
import android.view.KeyEvent
import android.widget.EditText
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class TvFlowTest:NativeTestBase(){
    private fun control(args:String)=runBlocking{Http().text("http://10.0.2.2:8878/__control?$args")}
    private fun audit()=JSONObject(runBlocking{Http().text("http://10.0.2.2:8878/__state")})
    private fun fixture(name:String="Fixture IPTV")=Provider(java.util.UUID.randomUUID().toString(),name,"http://10.0.2.2:8878/stalker_portal/",mac="00:1A:79:00:00:01")
    @Before fun fixtureSetup(){
        control("reset=1&reset_sessions=1")
        ui{a.model.updatePreferences(Preferences());a.model.server("http://10.0.2.2:8878");a.model.providers.filter{it.url.startsWith("http://10.0.2.2:8878") || it.name in listOf("Fixture IPTV","Edited fixture","Second fixture")}.forEach{a.model.removeProvider(it.id)};a.model.clearHistory();a.model.top(Section.SEARCH)}
        await{ui{!a.model.busy&&a.model.shelves.size==4}};await{firstMovieCard()!=null}
    }
    @After fun releasePlayback(){ui{a.model.showProfiles()};await{ui{a.player==null}};assertEquals(0,audit().getJSONObject("sessions").length())}
    private fun openMovie(waitForPlayback:Boolean=true) {
        nav("On-Demand");await{firstMovieCard()!=null}
        await{ui{
            val card=firstMovieCard()?:return@ui false
            if(!card.hasFocus()){card.requestFocus();return@ui false}
            if(android.os.Build.VERSION.SDK_INT>=30 && a.window.decorView.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime())==true)return@ui false
            var ancestor:android.view.View?=card
            while(ancestor!=null){
                if(ancestor.isLayoutRequested || ancestor is RecyclerView &&
                    (ancestor.scrollState!=RecyclerView.SCROLL_STATE_IDLE || ancestor.isComputingLayout || ancestor.hasPendingAdapterUpdates()))return@ui false
                ancestor=ancestor.parent as? android.view.View
            }
            a.hasWindowFocus() && card.isAttachedToWindow
        }}
        await{!device.executeShellCommand("dumpsys input_method").contains("mInputShown=true")}
        device.waitForIdle()
        sendRemoteKey(KeyEvent.KEYCODE_DPAD_CENTER)
        await{tagged("stream:Fixture GDrive")?.isShown==true && ui{!a.model.streamsBusy}}
        assertNull(find("Choose stream"))
        ui{a.window.decorView.findTag("stream:Fixture GDrive")!!.performClick()}
        if(waitForPlayback)playing()
    }
    private fun addFixture(p:Provider=fixture()){ui{a.model.saveProvider(p);a.model.showProfiles()};await{find(p.name)!=null}}
    private fun openChannels(){if(ui{a.model.route !is Route.Channels})click("Fixture IPTV");await{ui{!a.model.busy&&a.model.channels.size>=2}};await{tagged("channel:1")!=null}}
    @Test fun remoteSearchPlaybackHistoryAndResume(){
        ui{a.window.decorView.findTag("navigation:Live TV")!!.requestFocus()};device.pressDPadRight();device.pressDPadCenter();await{ui{a.model.section==Section.SEARCH}}
        openMovie();await{ui{a.player?.currentPosition?:0L>5000}}
        device.pressKeyCode(KeyEvent.KEYCODE_MEDIA_PAUSE);await{ui{a.player?.isPlaying==false}}
        device.pressKeyCode(KeyEvent.KEYCODE_MEDIA_PLAY);playing();ui{a.player!!.seekTo(12000)};await{ui{a.player!!.currentPosition>12000}}
        back();await{ui{a.player==null}};assertTrue(ui{a.model.resume(Title("tt0000001","Fixture"),null)>8000})
        nav("On-Demand");assertTrue(ui{a.model.progress.any{it.title.id=="tt0000001"}});openMovie();await{ui{a.player!!.currentPosition>8000}}
    }
    @Test fun searchAndSeriesEpisodeNavigation(){
        field("Search movies and shows","Fleet");await{ui{a.model.results.any{it.type=="series"}}};await{tagged("title:series/tt0000002")?.isShown==true};ui{a.window.decorView.findTag("search-results")!!.findTag("title:series/tt0000002")!!.performClick()}
        await{tagged("episode:tt0000002:1:2")!=null};ui{a.window.decorView.findTag("episode:tt0000002:1:2")!!.performClick()}
        await{tagged("stream:Fixture GDrive")!=null && ui{a.model.route is Route.Detail && a.model.selectedEpisode?.id=="tt0000002:1:2" && !a.model.streamsBusy}}
        assertNull(player())
        back();await{tagged("field:Search movies and shows")!=null && ui{a.model.route==Route.Browse(Section.SEARCH)}};assertEquals("Fleet",ui{(a.window.decorView.findTag("field:Search movies and shows")as EditText).text.toString()})
        field("Search movies and shows","");await{firstMovieCard()!=null}
    }
    @Test fun stalkerProfileAdvancedFieldsEncryptAndLiveChannelsPlay(){
        nav("Live TV");click("Add profile");field("Profile name (optional)","Fixture IPTV");field("Portal URL","http://10.0.2.2:8878/stalker_portal/?secret=fixture-credential");field("MAC address","00:1A:79:00:00:01");click("Advanced settings")
        val values=mapOf("Serial number (optional)" to "fixture-serial","Device ID (optional)" to "fixture-device-id","Device ID 2 (optional)" to "fixture-device-id-2","HW Version (optional)" to "2.20-IB-00","HW Version 2 (optional)" to "hardware-two","PIN (optional)" to "1234","Version (optional)" to "fixture-firmware","Image Version (optional)" to "218")
        values.forEach{(label,value)->field(label,value)};screenshot("stalker-advanced");click("Save profile");await{find("Fixture IPTV")!=null}
        val p=ui{Store(a).providers().getOrThrow().first{it.name=="Fixture IPTV"}}
        assertEquals("fixture-device-id",p.deviceId);assertEquals("hardware-two",p.hwVersion2);assertEquals("1234",p.pin);assertEquals("fixture-firmware",p.version);assertEquals("218",p.imageVersion);assertEquals("",p.signature)
        val blob=ui{a.getSharedPreferences("fleet-tv",0).getString("providers-encrypted","")!!};assertFalse(blob.contains("fixture-device-id"));assertFalse(blob.contains("fixture-credential"))
        openChannels();ui{a.window.decorView.findTag("channel:1")!!.performLongClick()};click("★ Favorites");await{ui{(a.window.decorView.findTag("channels")as RecyclerView).adapter!!.itemCount==1}}
        field("Search channels","News");await{tagged("channel:1")!=null};field("Search channels","");click("All channels");click("Sports");await{tagged("channel:2")!=null};ui{a.window.decorView.findTag("channel:2")!!.performClick()};playing()
        await{ui{a.player!!.isCurrentMediaItemLive&&a.player!!.currentPosition>30000}};ui{a.window.decorView.findTag("channel:2")!!.performClick()};await{tagged("channels")?.isShown==false};device.pressBack();await{tagged("channels")?.isShown==true};assertEquals("Sports",ui{a.model.channelGroup})
    }
    @Test fun nativeTvKeyboardAcceptsMacAndReturnsToForm(){
        nav("Live TV");click("Add profile");await{tagged("field:MAC address")!=null};ui{a.window.decorView.findTag("field:MAC address")!!.requestFocus()};device.pressDPadCenter()
        await{device.executeShellCommand("dumpsys input_method").contains("mInputShown=true")}
        device.executeShellCommand("input text 00:1A:79:00:00:01");device.pressBack()
        assertEquals("00:1A:79:00:00:01",ui{(a.window.decorView.findTag("field:MAC address")as EditText).text.toString()})
        field("Portal URL","http://10.0.2.2:8878/stalker_portal/");click("Save profile");await{ui{a.model.providers.any{it.mac=="00:1A:79:00:00:01"}}}
    }
    @Test fun unavailableAddonsRecoverAfterTheirAddressIsCorrected(){
        ui{a.model.server("http://127.0.0.1:1");a.model.top(Section.SEARCH)};await{ui{a.model.error!=null}}
        ui{a.model.server("http://10.0.2.2:8878")};await{ui{!a.model.busy&&a.model.shelves.size==4}}
        await{firstMovieCard()!=null}
    }
    @Test fun twoSectionsAndCompletedHistoryAreVisible(){
        assertNotNull(tagged("navigation:Live TV"));assertNotNull(tagged("navigation:On-Demand"))
        ui{a.model.saveProgress(Watch(Title("tt0000003","Finished fixture movie"),Stream("Fixture","https://fixture.example/video.mp4")),60000,60000)}
        nav("Live TV");nav("On-Demand");assertTrue(ui{a.model.progress.first().finished});assertEquals(0L,ui{a.model.resume(Title("tt0000003","Fixture"),null)})
    }
    @Test fun sharedSettingsPersistAndLiveIgnoresOnDemandSpeed(){
        nav("Live TV");click("Settings");click("Live TV settings");click("Automatic live reconnects");click("3");await{ui{Store(a).preferences().liveRetries==3}}
        click("Appearance");click("Poster size");click("Large");await{ui{a.hasWindowFocus()&&a.model.preferences.largePosters}};click("Show recently watched");assertFalse(ui{Store(a).preferences().showRecent})
        click("On demand");click("Playback speed");click("1.25×");await{ui{a.hasWindowFocus()&&Store(a).preferences().speed==1.25f}};nav("On-Demand");openMovie();assertEquals(1.25f,ui{a.player!!.playbackParameters.speed});back();await{ui{a.player==null}}
        addFixture();openChannels();ui{a.window.decorView.findTag("channel:1")!!.performClick()};playing();assertEquals(1f,ui{a.player!!.playbackParameters.speed})
    }
    @Test fun profileEditTestCancelAndConfirmedDeletion(){
        addFixture();profileAction("Fixture IPTV","Edit profile");field("Profile name (optional)","Edited fixture");click("Settings");back();await{tagged("field:Profile name (optional)")!=null};assertEquals("Edited fixture",ui{(a.window.decorView.findTag("field:Profile name (optional)")as EditText).text.toString()})
        ui{a.model.showProfiles()};click("Add profile");await{tagged("field:Profile name (optional)")!=null};assertEquals("",ui{(a.window.decorView.findTag("field:Profile name (optional)")as EditText).text.toString()});click("Cancel editing");profileAction("Fixture IPTV","Edit profile");field("Profile name (optional)","Edited fixture")
        click("Test connection");await{ui{a.model.connectionStatus!=null||a.model.error!=null}};assertEquals("Connected · 2 channels",ui{a.model.connectionStatus});click("Save profile");await{find("Edited fixture")!=null}
        deleteProfile("Edited fixture",false);assertNotNull(find("Edited fixture"));deleteProfile("Edited fixture");await{ui{a.model.providers.none{it.name=="Edited fixture"}}}
    }
    @Test fun defaultProfilePersistsAndDeletingDefaultSelectsRemaining(){
        val first=fixture();val second=fixture("Second fixture");addFixture(first);addFixture(second);profileAction("Second fixture","Set as default");await{ui{Store(a).preferences().defaultProfileId==second.id&&a.hasWindowFocus()}}
        ui{a.model.updatePreferences(a.model.preferences.copy(startSection="Live TV"));val store=androidx.lifecycle.ViewModelStore();try{val fresh=androidx.lifecycle.ViewModelProvider(store,androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory(a.application))[FleetModel::class.java];assertEquals(second.id,(fresh.route as Route.Channels).provider.id)}finally{store.clear()}}
        deleteProfile("Second fixture");await{ui{a.model.defaultProfile?.id==first.id}};deleteProfile("Fixture IPTV");await{ui{a.model.defaultProfile==null}}
    }
    @Test fun corruptDataDoesNotCrashOrOverwriteCredentials(){
        ui{val prefs=a.getSharedPreferences("fleet-tv",0);val keys=listOf("saved","progress","providers-encrypted");val old=keys.associateWith{prefs.getString(it,null)}
            try{prefs.edit().putString("saved","bad-json").putString("progress","bad-json").putString("providers-encrypted","unreadable-credential-data").commit();val store=Store(a);assertTrue(store.saved().isEmpty());assertTrue(store.progress().isEmpty());assertTrue(store.providers().isFailure)
                val life=androidx.lifecycle.ViewModelStore();try{val f=androidx.lifecycle.ViewModelProvider(life,androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory(a.application))[FleetModel::class.java];f.saveProvider(fixture());assertEquals("unreadable-credential-data",prefs.getString("providers-encrypted",null))}finally{life.clear()}
            }finally{prefs.edit().apply{old.forEach{(k,v)->if(v==null)remove(k)else putString(k,v)}}.commit()}}
    }
    @Test fun partialCatalogFailureKeepsWorkingShelfAndRetryRestoresShows(){
        control("partial_catalog=1");ui{a.model.retry()};await{ui{!a.model.busy&&a.model.error!=null}};assertTrue(ui{a.model.shelves.any{it.name=="Popular · Movies"}});control("partial_catalog=0");click("Try again");await{ui{!a.model.busy&&a.model.shelves.size==4}}
    }
    @Test fun brokenDirectStreamFocusesRetryAndRecovers(){
        control("broken_vod=1");openMovie(waitForPlayback=false)
        await(45000){find("This stream couldn't play. Try again or choose another stream.")!=null}
        await{ui{find("Retry")?.hasFocus()==true}};assertNull(player())
        control("broken_vod=0");remote("Retry");playing()
    }
    @Test fun brokenLiveStopsAtConfiguredReconnectLimit(){
        control("broken_live=1");ui{a.model.updatePreferences(a.model.preferences.copy(liveRetries=1))};addFixture();openChannels();val before=audit().getInt("portal_links");ui{a.window.decorView.findTag("channel:1")!!.performClick()}
        await(45000){find("This channel stopped responding. Retry or choose another channel.")!=null};assertEquals(2,audit().getInt("portal_links")-before);assertNull(player())
    }
    @Test fun backgroundReleasesPlayerAndForegroundResumesDirectStream(){
        openMovie();await{ui{a.player!!.currentPosition>5000}};val before=player();val at=ui{before!!.currentPosition}
        rule.scenario.moveToState(Lifecycle.State.CREATED);await{player()==null}
        rule.scenario.moveToState(Lifecycle.State.RESUMED);playing()
        assertNotSame(before,player());await{ui{a.player!!.currentPosition>=at-1500}}
    }
    @Test fun thousandsOfChannelsUseRecycledViewsAndCachedReturn(){
        control("large_channels=1");addFixture();openChannels();await{ui{a.model.channels.size==6000}};val grid=tagged("channels")as RecyclerView
        await{ui{grid.adapter!!.itemCount==6000}};assertTrue(ui{grid.childCount<40});val before=audit().getInt("portal_loads")
        field("Search channels","Channel 59");field("Search channels","Channel 42");await{ui{grid.adapter!!.itemCount in 1..200}};assertTrue(ui{a.model.channelQuery=="Channel 42"})
        field("Search channels","");await{ui{grid.adapter!!.itemCount==6000}}
        val preview=player()
        ui{a.model.open(Route.Player(Watch(Title("1","Channel","channel"),Stream("Fixture",""),live=true,provider=a.model.providers.first{it.name=="Fixture IPTV"},channel=a.model.channels.first())))}
        await{tagged("channels")?.isShown!=true&&ui{a.player!=null&&a.player!==preview}};playing()
        val fullscreen=player();back();await{tagged("channels")?.isShown==true&&ui{a.player!==fullscreen}};assertEquals(before,audit().getInt("portal_loads"))
    }
}
