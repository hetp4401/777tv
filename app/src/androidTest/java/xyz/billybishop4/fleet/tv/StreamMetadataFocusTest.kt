@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package xyz.billybishop4.fleet.tv

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

/** Generated loopback add-on data; the shared worker serves only the test video.
 * Hold layout to make an input arriving between adapter commit and rebind real,
 * then verify the customer's source survives a later refresh and Play/Back. */
class StreamMetadataFocusTest:NativeTestBase() {
    companion object {
        private const val ID="qa-stream-metadata-series"
        private const val UPDATED_BETA="Updated beta 720p"
    }
    private lateinit var feed:GeneratedStreamFeed
    private var held:RecyclerView?=null

    @Before fun prepare() {
        feed=GeneratedStreamFeed()
        ui {
            a.model.showProfiles();a.model.updatePreferences(Preferences(resume=false))
            assertTrue(a.model.server(feed.origin))
            a.model.open(Route.Detail(Title(ID,"Stream metadata QA","series")))
        }
        await { ui {
            val list=a.window.decorView.findTag("streams") as? RecyclerView;
            !a.model.busy && !a.model.streamsBusy && a.model.streams.size==2 && list!=null &&
                !list.isLayoutRequested && !list.hasPendingAdapterUpdates() && primaryOrNull(list,1)!=null
        } }
    }

    @After fun cleanup() {
        try {
            ui { held?.suppressLayout(false);held=null;a.model.showProfiles()
                a.model.server("http://10.0.2.2:8878");a.model.updatePreferences(Preferences()) }
            await { ui { a.activePlayer==null } }
        } finally { if(::feed.isInitialized)feed.close() }
    }

    private fun primaryOrNull(list:RecyclerView,index:Int):View? =
        (list.findViewHolderForAdapterPosition(index)?.itemView as? ViewGroup)?.getChildAt(0)
    private fun primary(list:RecyclerView,index:Int)=primaryOrNull(list,index)!!

    private fun refresh()=click("Refresh streams")
    private fun focusedSource():String?=ui { (a.currentFocus?.parent as? View)?.tag as? String }
    private fun context(stream:Stream)="stream-source:${stream.identity}"

    // Deliver one real root-dispatched key pair without waiting for the layout
    // deliberately held below. A new key also retires the earlier refresh focus.
    private fun directKey(code:Int) {
        val down=SystemClock.uptimeMillis()
        for(action in intArrayOf(KeyEvent.ACTION_DOWN,KeyEvent.ACTION_UP))
            a.dispatchKeyEvent(KeyEvent(down,SystemClock.uptimeMillis(),action,code,0,0,
                KeyCharacterMap.VIRTUAL_KEYBOARD,0,0,InputDevice.SOURCE_DPAD))
    }

    @Test fun metadataUpdateThenRenewalKeepsTheSourceInsteadOfItsOldOrdinal() {
        val list=tagged("streams") as RecyclerView
        val oldAlpha=ui { primary(list,0) };val oldBeta=ui { primary(list,1) }
        val identities=ui { a.model.streams.map{it.identity} }
        assertTrue(ui { oldBeta.contentDescription.toString().contains("Original beta") })

        ui { list.suppressLayout(true);held=list }
        try {
            feed.phase=1;refresh()
            await { ui {
                val committed=(list.adapter as NativeAdapter<*>).currentList.filterIsInstance<Stream>()
                !a.model.streamsBusy && committed.size==2 && committed[1].name==UPDATED_BETA && list.isShown
            } }
            assertEquals("The metadata phase must retain the same URLs/headers",identities,ui{a.model.streams.map{it.identity}})
            assertTrue("The old holder was already rebound; the timing window was not exercised",
                ui { oldBeta.isAttachedToWindow && oldBeta.contentDescription.toString().contains("Original beta") })
            ui {
                oldAlpha.requestFocusFromTouch();directKey(KeyEvent.KEYCODE_DPAD_RIGHT)
                // FocusFinder may refuse movement while RecyclerView layout is
                // suppressed. Focus the validated old holder directly to induce
                // the same real focus callback during the commit/rebind window.
                assertTrue("The still-attached old beta card rejected focus",oldBeta.requestFocusFromTouch())
            }
            assertSame("The old beta card did not retain focus before rebind",oldBeta,ui{a.currentFocus})
            assertNull("Updating or browsing streams started playback",ui{a.activePlayer})
        } finally { ui { list.suppressLayout(false);held=null } }

        await { ui { !list.isLayoutRequested && !list.hasPendingAdapterUpdates() &&
            primaryOrNull(list,1)?.contentDescription.toString().contains(UPDATED_BETA) } }
        ui { primary(list,0).requestFocusFromTouch() }
        sendRemoteKey(KeyEvent.KEYCODE_DPAD_RIGHT)
        val selected=ui { a.model.streams.single{it.name==UPDATED_BETA} }
        await { focusedSource()==context(selected) }

        // The refreshed beta keeps its visible metadata but receives a renewed
        // URL/header. A new larger source moves beta from ordinal 1 to 2.
        feed.phase=2;refresh()
        await { ui { !a.model.streamsBusy && a.model.streams.size==3 &&
            (list.adapter as NativeAdapter<*>).currentList.filterIsInstance<Stream>().let{rows->
                rows.size==3 && rows.last().name==UPDATED_BETA && rows.last().identity!=selected.identity
            } && !list.isLayoutRequested && !list.hasPendingAdapterUpdates() } }
        val renewed=ui { a.model.streams.single{it.name==UPDATED_BETA} }
        await { ui { a.model.streams.any{context(it)==focusedSource()} } }
        assertEquals("Refresh restored the old ordinal instead of beta; focused ${ui{a.currentFocus?.contentDescription}}",
            context(renewed),focusedSource())
        assertTrue(ui { a.currentFocus!!.contentDescription.toString().contains(UPDATED_BETA) })
        assertNull("Refreshing source links must not autoplay",ui{a.activePlayer})

        sendRemoteKey(KeyEvent.KEYCODE_DPAD_CENTER)
        await { ui { (a.model.route as? Route.Player)?.watch?.stream?.identity==renewed.identity } }
        assertEquals(renewed.headers,ui{(a.model.route as Route.Player).watch.headers})
        playing();back()
        await { ui { a.model.route is Route.Detail && !a.model.streamsBusy } && focusedSource()==context(renewed) }
        assertNull(ui{a.activePlayer})
    }

    /** Test-local HTTP feed avoids changing the shared fixture or private model
     * state. Its only media URLs address generated worker HLS, never a provider. */
    private class GeneratedStreamFeed:Closeable {
        private val server=ServerSocket(0,8,InetAddress.getByName("127.0.0.1"))
        val origin="http://127.0.0.1:${server.localPort}"
        @Volatile var phase=0
        private val worker=thread(name="generated-stream-metadata",isDaemon=true) {
            while(!server.isClosed) {
                try {
                    server.accept().use { socket->
                        socket.soTimeout=5000
                        val input=socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                        val path=input.readLine()?.split(' ')?.getOrNull(1).orEmpty()
                        while(true){val header=input.readLine();if(header.isNullOrEmpty())break}
                        val bytes=response(path).toString().toByteArray(Charsets.UTF_8)
                        socket.getOutputStream().apply {
                            write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                            write(bytes);flush()
                        }
                    }
                } catch(_:IOException) { if(server.isClosed)break }
            }
        }
        private fun response(path:String):JSONObject=when {
            "/stream/" in path -> {
                val current=phase;val epoch=if(current==2)1 else 0
                fun source(key:String,name:String,size:Int)=JSONObject().put("name",name)
                    .put("title","Generated $key copy\n$size GB")
                    .put("url","http://10.0.2.2:8878/hls/fixture/orig.m3u8?source=$key&epoch=$epoch")
                    .put("behaviorHints",JSONObject().put("proxyHeaders",JSONObject().put("request",
                        JSONObject().put("X-Fixture-Source","$key-$epoch"))))
                val rows=JSONArray()
                if(current==2)rows.put(source("gamma","New gamma 2160p",3))
                rows.put(source("alpha",if(current==0)"Original alpha 1080p" else "Updated alpha 1080p",2))
                    .put(source("beta",if(current==0)"Original beta 720p" else UPDATED_BETA,1))
                JSONObject().put("streams",rows)
            }
            "/meta/" in path -> JSONObject().put("meta",JSONObject().put("id",ID).put("name","Stream metadata QA")
                .put("type","series").put("videos",JSONArray().put(JSONObject().put("id","$ID:1:1")
                    .put("season",1).put("episode",1).put("name","Generated episode"))))
            else -> JSONObject().put("metas",JSONArray())
        }
        override fun close(){server.close();worker.join(1000)}
    }
}
