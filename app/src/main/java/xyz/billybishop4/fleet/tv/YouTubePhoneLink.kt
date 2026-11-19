@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package xyz.billybishop4.fleet.tv

import android.app.AlertDialog
import android.view.Gravity
import android.view.KeyEvent
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.Player
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONArray
import org.json.JSONObject

/** Foreground-only phone control. One long poll, one conflated status sender, no extra player. */
internal class YouTubePhoneLink(private val a:MainActivity,private val client:YouTubeLoungeClient=YouTubeLoungeClient(
    a.model.store.youTubeScreenStore(),YouTubeLoungeHttp(a.model.youtube.client.origin))) {
    private val scope=a.lifecycleScope
    private var listen:Job?=null;private var updates:Job?=null;private var codeJob:Job?=null
    private var foreground=false;private var closed=false;private var enabled=a.model.store.youTubePhoneEnabled()
    private var dialog:AlertDialog?=null;private var codeView:TextView?=null;private var statusView:TextView?=null
    private var observed:Player?=null
    private var observedWatch:Watch?=null
    private val queue=YouTubeRemoteQueue()
    private val pending=Channel<Unit>(Channel.CONFLATED)
    private var ready=false;private var phones=0
    private var discoveryRequested=false
    private var sentVolume:String?=null;private var sentQueue:String?=null;private var sentCaption:String?=null
    private var phoneVolume=1f;private var phoneMuted=false
    private data class Handoff(val id:String,val route:Route,val startMs:Long,var play:Boolean?=null,var seekMs:Long?=null,var speed:Float?=null,var caption:String?=null)
    private var handoff:Handoff?=null
    private val listener=object:Player.Listener {
        override fun onEvents(player:Player,events:Player.Events) {
            if(events.containsAny(Player.EVENT_PLAYBACK_STATE_CHANGED,Player.EVENT_PLAY_WHEN_READY_CHANGED,Player.EVENT_IS_PLAYING_CHANGED,
                    Player.EVENT_POSITION_DISCONTINUITY,Player.EVENT_VOLUME_CHANGED,Player.EVENT_PLAYBACK_PARAMETERS_CHANGED))pending.trySend(Unit)
            if(events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED) && player.playbackState==Player.STATE_ENDED && handoff==null)next(1)
        }
    }
    fun foreground(){foreground=true;if(enabled)start();if(dialog?.isShowing==true && codeView?.text=="Connecting…" && codeJob?.isActive!=true)newCode()}
    fun background(){foreground=false;stop()}
    private fun start() {
        if(closed || !foreground || !enabled || listen?.isActive==true)return
        listen=scope.launch {
            client.listen({ event->withContext(Dispatchers.Main.immediate){receive(event)} },{ connected->
                withContext(Dispatchers.Main.immediate){ready=connected;if(!connected)phones=0;sentVolume=null;sentQueue=null;sentCaption=null;status();pending.trySend(Unit)}
            })
        }
        updates=scope.launch {
            launch { while(isActive){delay(5000);if(phones>0)pending.trySend(Unit)} }
            for(ignored in pending) {
                if(!ready)continue
                playbackChanged()
                try { sendState() }catch(e:CancellationException){throw e}catch(_:Exception){ /* Reconnection owns recovery. */ }
            }
        }
        playbackChanged()
    }
    private fun stop() {
        if(handoff!=null){a.model.youtube.cancelRemotePlayback(releaseQueue=false);handoff=null;queue.align(observedWatch?.title?.id)}
        listen?.cancel();listen=null;updates?.cancel();updates=null
        codeJob?.cancel();codeJob=null;ready=false;phones=0
        observed?.removeListener(listener);observed=null;observedWatch=null
    }
    fun playbackChanged() {
        if(closed || !enabled || !foreground)return
        val watch=(a.model.route as? Route.Player)?.watch
        val owner=a.playback
        val player=owner?.mediaPlayer?.takeIf{watch?.title?.type=="youtube" && owner.watch===watch}
        if(player!==observed){
            // A local selection owns its next video. Retire a previous phone queue
            // while leaving phone pause/seek controls connected to the new player.
            if(player!=null && watch!==observedWatch && watch?.requestedPositionMs==null){queue.clear();handoff=null}
            observed?.removeListener(listener);observed=player;observedWatch=watch.takeIf{player!=null}
            player?.let{it.volume=if(phoneMuted)0f else phoneVolume;it.addListener(listener)};pending.trySend(Unit)
        }
        val transfer=handoff
        if(transfer!=null && player!=null && player.mediaItemCount>0 && watch?.title?.id==transfer.id && a.model.route!==transfer.route && !a.model.youtube.resolving) {
            handoff=null
            transfer.play?.let{player.playWhenReady=it}
            transfer.seekMs?.let{player.seekTo(if(player.duration>0)it.coerceAtMost(player.duration) else it)}
            transfer.speed?.let{player.setPlaybackSpeed(it)}
            transfer.caption?.let{setCaption(player,it)}
        }
    }
    fun navigationChanged() {
        val transfer=handoff?:return
        val route=a.model.route
        val opened=(route as? Route.Player)?.watch?.title?.id==transfer.id && route!==transfer.route
        if(!opened && (route!=transfer.route || !a.model.youtube.resolving)) {
            a.model.youtube.cancelRemotePlayback(releaseQueue=false);handoff=null;queue.align(observedWatch?.title?.id)
        }
    }
    fun show() {
        if(closed || dialog?.isShowing==true)return
        val code=a.text("Connecting…",30f,bold=true).apply{tag="youtube-phone-code";gravity=Gravity.CENTER;setTextIsSelectable(false)}
        val state=a.text("",13f,MUTED).apply{tag="youtube-phone-status";gravity=Gravity.CENTER;maxLines=3}
        val body=a.column().apply {
            pad(24);add(a.text("Link your phone",23f,bold=true));gap(16)
            add(a.text("On your phone, open YouTube → Cast → Link with TV code. Enter this code.",14f,MUTED).apply{maxLines=3});gap(20)
            add(code);gap(12);add(state)
        }
        val sheet=AlertDialog.Builder(a).setView(body).setPositiveButton("Done",null).setNeutralButton("New code",null)
            .setNegativeButton("Disconnect phones",null).create()
        dialog=sheet;codeView=code;statusView=state;a.ownDialog(sheet)
        sheet.setOnDismissListener {
            a.releaseDialog(sheet);codeJob?.cancel();codeJob=null;dialog=null;codeView=null;statusView=null
        }
        sheet.setOnShowListener {
            a.styleDialog(sheet)
            sheet.window?.setBackgroundDrawable(a.round(PANEL,radius=16));sheet.window?.setLayout(a.dp(600),-2)
            sheet.getButton(AlertDialog.BUTTON_POSITIVE).requestFocusFromTouch()
            sheet.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener{newCode()}
            sheet.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener{disconnect();sheet.dismiss()}
        }
        sheet.show();newCode()
    }
    private fun newCode() {
        codeJob?.cancel();codeView?.text="Connecting…";statusView?.text="777tv must stay open to receive videos."
        codeJob=scope.launch {
            try {
                val code=client.pairingCode()
                if(closed || dialog?.isShowing!=true)return@launch
                codeView?.text=code
                enabled=true;a.model.store.youTubePhoneEnabled(true);a.model.changes.postValue(Unit)
                listen?.cancelAndJoin();listen=null;updates?.cancelAndJoin();updates=null
                start();status()
            }catch(e:CancellationException){throw e}
            catch(_:Exception){codeView?.text="Couldn't connect";statusView?.text="Check your connection, then choose New code."}
        }
    }
    private fun status() {
        statusView?.text=when {phones>0->"Phone connected. Choose a video on your phone.";ready->"Waiting for your phone. 777tv must stay open.";else->"Connecting to YouTube…"}
    }
    fun disconnect() {
        enabled=false;a.model.store.youTubePhoneEnabled(false);stop();queue.clear()
        a.model.youtube.cancelRemotePlayback();a.playback?.refreshYouTubeNext()
        scope.launch{try{client.forget()}catch(e:CancellationException){throw e}catch(_:Exception){}}
    }
    private fun play(id:String,positionMs:Long=0) {
        if(!YouTubeDecode.validId(id))return
        handoff=Handoff(id,a.model.route,positionMs)
        val video=a.model.youtube.videos.firstOrNull{it.id==id}?:YouTubeVideo(id,"YouTube video",thumbnail="https://i.ytimg.com/vi/$id/hqdefault.jpg")
        a.model.youtube.playRemote(video,positionMs)
        pending.trySend(Unit)
    }
    private fun next(direction:Int){queue.step(direction)?.let{play(it)}}
    fun canStep(watch:Watch,direction:Int)=enabled && foreground && watch===observedWatch && handoff==null &&
        queue.ids.getOrNull(queue.index)==watch.title.id && queue.index+direction in queue.ids.indices
    fun step(watch:Watch,direction:Int):Boolean {
        if(!canStep(watch,direction))return false
        next(direction);return true
    }
    private fun receive(event:YouTubeLoungeEvent) {
        if(!enabled || closed || !foreground)return
        val data=event.data
        when(event.type) {
            "setPlaylist"->{queue.replace(data)?.let{play(it,YouTubeRemoteQueue.position(data))}}
            "updatePlaylist"->queue.replace(data)
            "next"->next(1)
            "previous"->next(-1)
            "play"->{
                val transfer=handoff
                if(transfer!=null)transfer.play=true else observed?.let { player->
                    if(a.playback?.reconcileYouTubeScrub(player,play=true)!=true)player.play()
                }
            }
            "pause"->{
                val transfer=handoff
                if(transfer!=null)transfer.play=false else observed?.let { player->
                    if(a.playback?.reconcileYouTubeScrub(player,play=false)!=true)player.pause()
                }
            }
            "stopVideo"->{a.model.youtube.cancelRemotePlayback();handoff=null;if(observed!=null)a.model.top(Section.YOUTUBE);queue.clear()}
            "seekTo"->{
                val at=YouTubeRemoteQueue.position(data);val transfer=handoff
                if(transfer!=null)transfer.seekMs=at else observed?.takeIf{it.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)}?.let { player->
                    if(a.playback?.reconcileYouTubeScrub(player,seekMs=at)!=true)player.seekTo(if(player.duration>0)at.coerceAtMost(player.duration) else at)
                }
            }
            "setVolume"->{
                val volume=data.optDouble("volume",phoneVolume*100.0).takeIf{it.isFinite()}?.coerceIn(0.0,100.0)
                if(volume!=null){phoneVolume=(volume/100).toFloat();phoneMuted=data.optString("muted")=="true";if(handoff==null)observed?.volume=if(phoneMuted)0f else phoneVolume}
            }
            "setSubtitlesTrack"->{
                val vss=data.optString("vss_id")
                val language=data.optString("languageCode").ifBlank{data.optString("sourceLanguageCode")}.ifBlank{vss.removePrefix("a.")}.take(40)
                val enabled=vss.isNotBlank() && language.matches(Regex("[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*"))
                val value=if(enabled)language else "";val transfer=handoff
                if(transfer!=null)transfer.caption=value else observed?.let{setCaption(it,value)}
            }
            "setPlaybackSpeed"->{val value=data.optDouble("playbackSpeed",data.optDouble("speed",1.0));if(value.isFinite()){
                val speed=value.coerceIn(.25,2.0).toFloat();val transfer=handoff;if(transfer!=null)transfer.speed=speed else observed?.setPlaybackSpeed(speed)}}
            "voiceCommand","setSearchQuery"->{
                val query=data.optString("query").ifBlank{data.optString("text")}.take(200)
                if(query.isNotBlank()){a.model.top(Section.YOUTUBE);a.model.youtube.editQuery(query);a.model.youtube.submitSearch()}
            }
            "dpadCommand"->if((a.model.route==Route.Browse(Section.YOUTUBE) || (a.model.route as? Route.Player)?.watch?.title?.type=="youtube") && a.hasWindowFocus()) {
                val key=when(data.optString("key")){"UP"->19;"DOWN"->20;"LEFT"->21;"RIGHT"->22;"ENTER"->23;"BACK"->4;else->null}
                key?.let{a.window.decorView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,it));a.window.decorView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP,it))}
            }
            "remoteConnected"->{phones=(phones+1).coerceAtMost(20);status()}
            "remoteDisconnected"->{phones=(phones-1).coerceAtLeast(0);status()}
            "getDiscoveryDeviceId"->discoveryRequested=true
            "loungeStatus"->{
                phones=runCatching { val devices=JSONArray(data.optString("devices","[]"));(0 until devices.length()).count{
                    devices.optJSONObject(it)?.optString("type")=="REMOTE_CONTROL"} }.getOrDefault(0)
                status()
            }
        }
        if(event.type=="remoteConnected" || event.type=="getNowPlaying"){sentVolume=null;sentQueue=null;sentCaption=null}
        if(event.type!="noop")pending.trySend(Unit)
    }
    private suspend fun sendState() {
        val player=observed
        val transfer=handoff
        val watch=observedWatch
        val duration=if(transfer!=null)0L else (player?.duration?:0L).coerceAtLeast(0)
        val loaded=if(transfer!=null)0L else (player?.bufferedPosition?:0L).coerceAtLeast(0)
        val state=when {transfer!=null->if(transfer.play==false)2 else 3;player==null->-1;player.playbackState==Player.STATE_BUFFERING->3;player.playbackState==Player.STATE_ENDED->0;player.playWhenReady->1;else->2}
        val fields=linkedMapOf("videoId" to (transfer?.id?:watch?.title?.id).orEmpty(),"state" to state.toString(),"currentTime" to ((transfer?.seekMs?:transfer?.startMs?:player?.currentPosition?:0L).coerceAtLeast(0)/1000.0).toString(),
            "duration" to (duration/1000.0).toString(),"loadedTime" to (loaded/1000.0).toString(),
            "seekableStartTime" to "0","seekableEndTime" to (duration/1000.0).toString(),"listId" to queue.listId,
            "currentIndex" to queue.index.toString(),"ctt" to queue.ctt)
        val gain=if(transfer!=null || player==null){if(phoneMuted)0f else phoneVolume}else player.volume
        val volume=(gain*100).toInt().toString();val queueKey="${queue.index}/${queue.ids.size}"
        val text=player?.currentTracks?.groups?.firstOrNull{it.type==C.TRACK_TYPE_TEXT && it.isSelected}
        val caption=if(transfer!=null)transfer.caption.orEmpty() else text?.let{group->(0 until group.length).firstOrNull{group.isTrackSelected(it)}?.let{group.getTrackFormat(it).language}}.orEmpty()
        val commands=ArrayList<Pair<String,Map<String,String>>>(4);commands+="nowPlaying" to fields
        if(sentVolume!=volume)commands+="onVolumeChanged" to mapOf("volume" to volume,"muted" to (volume=="0").toString())
        if(sentQueue!=queueKey)commands+="onHasPreviousNextChanged" to mapOf("hasPrevious" to (queue.index>0).toString(),"hasNext" to (queue.index<queue.ids.size-1).toString())
        if(sentCaption!=caption)commands+="onSubtitlesTrackChanged" to mapOf("vss_id" to caption,"languageCode" to caption)
        val discovery=discoveryRequested
        if(discovery)commands+="setDiscoveryDeviceId" to client.discoveryFields()
        client.sendAll(commands);sentVolume=volume;sentQueue=queueKey;sentCaption=caption
        if(discovery)discoveryRequested=false
    }
    private fun setCaption(player:Player,language:String) {
        player.trackSelectionParameters=player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT,language.isEmpty())
            .setPreferredTextLanguage(language.takeIf{it.isNotEmpty()}).build()
    }
    fun close(){closed=true;stop();dialog?.dismiss();dialog=null;pending.close()}
}
