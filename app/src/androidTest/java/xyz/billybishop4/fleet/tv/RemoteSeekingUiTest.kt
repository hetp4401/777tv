@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package xyz.billybishop4.fleet.tv

import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.SeekBar
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Real native timeline/window keys with controlled timeline durations. This
 * verifies cursor math/UI ownership; actual media/decoder tests remain separate. */
class RemoteSeekingUiTest:NativeTestBase() {
    private class TimelinePlayer(val engine:ExoPlayer,private val length:Long):ForwardingPlayer(engine) {
        var at=20000L
        val seeks=ArrayList<Long>()
        override fun getDuration()=length
        override fun getCurrentPosition()=at
        override fun getBufferedPosition()=length
        override fun getPlaybackState()=Player.STATE_READY
        override fun isPlaying()=playWhenReady
        override fun isLoading()=false
        override fun isCurrentMediaItemSeekable()=true
        override fun isCommandAvailable(command:Int)=command==Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM || engine.isCommandAvailable(command)
        override fun seekTo(positionMs:Long){at=positionMs.coerceIn(0,length);seeks.add(at)}
    }
    private var clock=0L
    @Before fun prepare() {
        ui{a.model.top(Section.SEARCH);a.model.store.youTubeCredentials().write(null);a.model.store.youTubePhoneEnabled(false)
            a.model.server("http://10.0.2.2:8878");a.model.youtubeFixture("http://10.0.2.2:8878")}
        await{ui{a.player==null && a.hasWindowFocus()} && tagged("search-home")?.isShown==true}
    }
    private fun time():Long {clock=maxOf(clock+1,SystemClock.uptimeMillis());return clock}
    private fun event(code:Int,action:Int,down:Long,at:Long=down,repeats:Int=0,flags:Int=0)=ui {
        a.window.callback!!.dispatchKeyEvent(KeyEvent(down,at,action,code,repeats,0,KeyCharacterMap.VIRTUAL_KEYBOARD,0,flags,InputDevice.SOURCE_DPAD))
    }
    private fun tap(code:Int){val down=time();event(code,KeyEvent.ACTION_DOWN,down);event(code,KeyEvent.ACTION_UP,down,down+1)}
    private fun controls(youtube:Boolean,duration:Long,block:(VodControls,TimelinePlayer,SeekBar)->Unit) {
        val host=ui{a.findViewById<ViewGroup>(android.R.id.content)}
        lateinit var player:TimelinePlayer;lateinit var controls:VodControls
        ui {
            player=TimelinePlayer(ExoPlayer.Builder(a).build(),duration)
            val watch=Watch(Title("V0000000001","Generated timeline",if(youtube)"youtube" else "movie"),Stream("Generated","http://10.0.2.2:8878/media"))
            controls=VodControls(a,watch,player,10,{}, {},{_,done->done(emptyList())},{_,_->},youtubePanel=if(youtube)({_:Boolean->}) else null)
            host.addView(controls.view,FrameLayout.LayoutParams(-1,-1));controls.show(focus=false)
        }
        val bar=ui{controls.view.findTag("vod-progress") as SeekBar}
        try {
            await{ui{bar.isShown && bar.width>0 && bar.isEnabled && a.hasWindowFocus()}}
            ui{bar.requestFocusFromTouch()};await{ui{bar.hasFocus()}}
            block(controls,player,bar)
        }finally{ui{controls.close();host.removeView(controls.view);player.engine.release()}}
    }
    @Test fun tapTimeIsIndependentOfDurationAndKeyboardFallbacks() {
        for(youtube in listOf(false,true))for(duration in listOf(60000L,10800000L))controls(youtube,duration){_,player,bar->
            for((key,target) in listOf(KeyEvent.KEYCODE_DPAD_RIGHT to 30000L,KeyEvent.KEYCODE_DPAD_LEFT to 20000L,
                KeyEvent.KEYCODE_PLUS to 30000L,KeyEvent.KEYCODE_MINUS to 20000L)) {
                tap(key);assertEquals("Remote time changed with media length",target,ui{player.at})
            }
            ui{assertTrue(bar.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,null))}
            assertEquals(30000L,ui{player.at})
            ui{assertTrue(bar.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD,null))}
            assertEquals(20000L,ui{player.at});assertEquals(6,ui{player.seeks.size})
            // Absolute positioning remains proportional, as a direct touch drag is.
            ui{assertTrue(bar.performAccessibilityAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
                Bundle().apply{putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,bar.max*.25f)}))}
            assertEquals(duration/4,ui{player.at})
        }
    }
    @Test fun holdTravelIgnoresDurationAndRemoteRepeatDensity() {
        for(youtube in listOf(false,true))for(duration in listOf(60000L,10800000L))for(cadence in listOf(0,100,400))controls(youtube,duration){_,player,_->
            val down=time();event(KeyEvent.KEYCODE_DPAD_RIGHT,KeyEvent.ACTION_DOWN,down)
            if(cadence>0)for(offset in cadence until 1000 step cadence)
                event(KeyEvent.KEYCODE_DPAD_RIGHT,KeyEvent.ACTION_DOWN,down,down+offset,offset/cadence)
            if(youtube)assertEquals(0,ui{player.seeks.size})
            event(KeyEvent.KEYCODE_DPAD_RIGHT,KeyEvent.ACTION_UP,down,down+1000)
            assertEquals("Held travel used repeats or duration",48900L,ui{player.at})
            if(youtube)assertEquals(1,ui{player.seeks.size})
            assertFalse(ui{player.playWhenReady})
        }
    }
    @Test fun heldCursorMovesBetweenRepeatsAndReleaseCommitsOnce() {
        controls(true,10800000){_,player,bar->
            val down=time();event(KeyEvent.KEYCODE_DPAD_RIGHT,KeyEvent.ACTION_DOWN,down)
            val samples=ArrayList<Pair<Long,Long>>()
            repeat(25) {
                SystemClock.sleep(50)
                samples.add(ui{SystemClock.uptimeMillis() to (bar.progress.toDouble()/bar.max*player.duration).toLong()})
            }
            assertTrue("Cursor waited for another repeat packet",samples.map{it.second}.distinct().size>=6)
            for((before,after) in samples.zipWithNext()) {
                assertTrue(after.second>=before.second)
                assertTrue("Cursor made an unbounded jump",after.second-before.second<=120*(after.first-before.first)+250)
            }
            assertEquals(0,ui{player.seeks.size})
            event(KeyEvent.KEYCODE_DPAD_RIGHT,KeyEvent.ACTION_UP,down,SystemClock.uptimeMillis())
            assertEquals(1,ui{player.seeks.size});val stopped=ui{player.at}
            SystemClock.sleep(200);assertEquals(stopped,ui{player.at})
            tap(KeyEvent.KEYCODE_DPAD_RIGHT);assertEquals(stopped+10000,ui{player.at});assertEquals(2,ui{player.seeks.size})
        }
    }
    @Test fun canceledReversedAndSupersededYouTubeGesturesDoNotLeakSeeks() {
        for(wasPlaying in listOf(false,true))controls(true,10800000){controls,player,_->
            ui{player.playWhenReady=wasPlaying}
            val canceled=time();event(KeyEvent.KEYCODE_DPAD_RIGHT,KeyEvent.ACTION_DOWN,canceled)
            event(KeyEvent.KEYCODE_DPAD_RIGHT,KeyEvent.ACTION_UP,canceled,canceled+500,flags=KeyEvent.FLAG_CANCELED)
            assertEquals(0,ui{player.seeks.size});assertEquals(20000L,ui{player.at});assertEquals(wasPlaying,ui{player.playWhenReady})
            val down=time();event(KeyEvent.KEYCODE_DPAD_RIGHT,KeyEvent.ACTION_DOWN,down)
            event(KeyEvent.KEYCODE_DPAD_RIGHT,KeyEvent.ACTION_DOWN,down,down+600,1)
            val reverse=down+600;event(KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.ACTION_DOWN,reverse)
            event(KeyEvent.KEYCODE_DPAD_RIGHT,KeyEvent.ACTION_UP,down,reverse+20)
            assertEquals(0,ui{player.seeks.size})
            event(KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.ACTION_UP,reverse,reverse+50)
            assertEquals(26900L,ui{player.at});assertEquals(1,ui{player.seeks.size});assertEquals(wasPlaying,ui{player.playWhenReady})
            ui{player.at=20000;player.seeks.clear()};val superseded=time()
            event(KeyEvent.KEYCODE_DPAD_RIGHT,KeyEvent.ACTION_DOWN,superseded)
            ui{assertTrue(controls.reconcileExternalSeek(!wasPlaying,45000))}
            event(KeyEvent.KEYCODE_DPAD_RIGHT,KeyEvent.ACTION_DOWN,superseded,superseded+800,1)
            event(KeyEvent.KEYCODE_DPAD_RIGHT,KeyEvent.ACTION_UP,superseded,superseded+1000)
            assertEquals(listOf(45000L),ui{player.seeks.toList()});assertEquals(!wasPlaying,ui{player.playWhenReady})
        }
    }
}
