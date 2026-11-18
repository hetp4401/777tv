package xyz.billybishop4.fleet.tv

import android.app.AlertDialog
import android.text.InputFilter
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Account work is lazy and explicit. A successful request, never a click, changes selected state. */
internal class YouTubeVideoActions(private val a:MainActivity,private val video:YouTubeVideo) {
    private enum class Page { ACTIONS,PLAYLISTS,CREATE }
    private val m=a.model.youtube
    private var dialog:AlertDialog?=null
    private var request:Job?=null
    private var signIn:YouTubeSignIn?=null
    private var details:YouTubeWatchDetails?=null
    private var playlists:List<YouTubePlaylist> = emptyList()
    private var page=Page.ACTIONS
    private var busy=false
    private var error:String?=null
    private var notice=""
    private var playlistName=""
    private var generation=0
    private var requireSignIn=false
    private var loadedScope:YouTubeAccountScope?=null
    private var scopeKnown=false
    private var actionAnchor:Pair<String,Int>?=null
    private var nextFocus:String?=null
    private var requestInput=0
    private var inputGeneration=0
    private val rows=a.column().apply { tag="youtube-video-actions";setPadding(a.dp(16),a.dp(4),a.dp(16),a.dp(4)) }
    private val content=ScrollView(a).apply { isFillViewport=false;addView(rows) }
    internal val view:View get()=content
    val visible get()=dialog?.isShowing==true

    fun show() {
        if(visible)return
        val anchor=a.currentFocus
        val sheet=AlertDialog.Builder(a).setTitle("Video actions").setView(content).setNegativeButton("Done",null).create()
        dialog=sheet;a.ownDialog(sheet)
        sheet.setOnKeyListener { _,_,event->if(event.action==android.view.KeyEvent.ACTION_DOWN)inputGeneration++;false }
        sheet.setOnShowListener {
            sheet.window?.apply {
                setBackgroundDrawable(a.round(PANEL,radius=16))
                setLayout(a.dp(460).coerceAtMost(a.resources.displayMetrics.widthPixels-a.dp(48)),-2)
            }
            content.layoutParams=content.layoutParams.apply { height=minOf(a.dp(310),a.resources.displayMetrics.heightPixels-a.dp(160)).coerceAtLeast(a.dp(170)) }
            sheet.getButton(AlertDialog.BUTTON_NEGATIVE).quiet().apply { isAllCaps=false;requestFocusFromTouch() }
            loadDetails()
        }
        sheet.setOnDismissListener {
            if(dialog===sheet)dialog=null
            generation++;request?.cancel();request=null;signIn?.close();signIn=null;a.releaseDialog(sheet)
            val input=a.focusInputGeneration
            anchor?.postOnAnimation { if(input==a.focusInputGeneration && anchor.isAttachedToWindow && anchor.isShown && anchor.hasWindowFocus())anchor.requestFocus() }
        }
        sheet.show()
    }
    private fun failure(e:Exception):String = if(e is UserMessage)e.message.orEmpty() else "YouTube couldn't complete that action. Try again."
    private fun forgetAccount(){details=null;playlists=emptyList();page=Page.ACTIONS;scopeKnown=false;loadedScope=null}
    private fun <T> run(message:String,bound:Boolean=true,action:suspend (YouTubeAccountScope?)->T,complete:(T)->Unit) {
        if(busy || !visible)return
        val key=++generation
        requestInput=inputGeneration
        (rows.findFocus()?.tag as? String)?.let{actionAnchor=it to inputGeneration}
        busy=true;error=null;notice=message;render()
        request=a.lifecycleScope.launch {
            try {
                m.refreshAccountState()
                val scope=m.client.accountScope()
                if(bound && (!scopeKnown || scope!=loadedScope)) {
                    forgetAccount()
                    throw UserMessage("Your account changed. Reload video actions before making changes.")
                }
                if(!bound && scopeKnown && scope!=loadedScope)forgetAccount()
                val value=action(scope)
                if(key!=generation || !visible)return@launch
                if(if(scope==null)m.client.accountScope()!=null else !m.client.accountScopeCurrent(scope)) {
                    forgetAccount()
                    throw UserMessage("Your account changed. Reopen video actions before making changes.")
                }
                if(!bound){loadedScope=scope;scopeKnown=true}
                complete(value)
            } catch(e:CancellationException){
                if(key==generation && visible){forgetAccount();requireSignIn=true;error="Your account changed. Sign in again to make changes.";notice=""}
                throw e
            }
            catch(e:Exception) {
                if(key==generation && visible) {
                    error=failure(e);notice=""
                    if(e is YouTubeAuthError || e is HttpFailure && e.status==401){forgetAccount();requireSignIn=true;m.syncAccount()}
                }
            } finally { if(key==generation){busy=false;request=null;if(visible)render()} }
        }
    }
    private fun loadDetails()=run("Loading video actions…",bound=false,action={m.client.interaction(video.id,expectedScope=it)}) {
        details=it;notice=""
        if(actionAnchor==null)nextFocus=when{m.account==null->"youtube-actions-signin";it.channel!=null || video.channelId.isNotBlank()->"youtube-actions-channel";else->"youtube-actions-like"}
    }
    private fun signedIn(action:()->Unit) {
        if(busy)return
        if(m.account!=null && !requireSignIn){action();return}
        signIn=YouTubeSignIn(a).also { sheet->sheet.show(forceSignIn=requireSignIn) {
            if(visible){requireSignIn=false;loadDetails()}
        } }
    }
    private fun row(label:String,tag:String,enabled:Boolean=!busy,selected:Boolean=false,action:()->Unit):Button = a.action(label,label,click=action).quiet().apply {
        this.tag=tag;id=View.generateViewId();isEnabled=enabled;isSelected=selected
        gravity=Gravity.CENTER_VERTICAL or Gravity.START;maxLines=1;ellipsize=TextUtils.TruncateAt.END
        rows.add(this,height=a.dp(38))
    }
    private fun render() {
        val input=if(nextFocus!=null)requestInput else inputGeneration
        val focused=nextFocus?:(rows.findFocus()?.tag as? String)?:actionAnchor?.takeIf{it.second==inputGeneration}?.first
        nextFocus=null
        rows.removeAllViews()
        rows.add(a.text(video.name,14f,bold=true).apply { maxLines=2;ellipsize=TextUtils.TruncateAt.END },height=-2);rows.gap(7)
        val status=error?:notice
        rows.add(a.text(status,12f,if(error!=null)0xFFFFC4C4.toInt() else MUTED).apply {
            tag="youtube-actions-status";maxLines=3;visibility=if(status.isBlank())View.GONE else View.VISIBLE
        })
        if(m.account==null || requireSignIn)row("Sign in for account actions","youtube-actions-signin") { signedIn{} }
        when(page) {
            Page.ACTIONS->renderActions()
            Page.PLAYLISTS->renderPlaylists()
            Page.CREATE->renderCreate()
        }
        if(error!=null && details==null && page==Page.ACTIONS)row("Retry","youtube-actions-retry"){loadDetails()}
        if(focused!=null && !busy)rows.postOnAnimation {
            if(visible && input==inputGeneration && content.hasWindowFocus())rows.findTag(focused)?.takeIf{it.isEnabled}?.requestFocus()
            actionAnchor=null
        }
    }
    private fun renderActions() {
        val channel=details?.channel
        val channelId=channel?.id?.ifBlank{video.channelId}?:video.channelId
        if(channelId.isNotBlank())row(channel?.name?.takeIf{it.isNotBlank()}?:video.channel.ifBlank{"View channel"},"youtube-actions-channel",enabled=true) {
            close();openYouTubeChannel(a,channelId)
        }
        val signedIn=m.account!=null && !requireSignIn
        val rating=details?.rating?.takeIf{signedIn}
        val ready=details!=null && !busy
        row(if(rating==YouTubeRating.LIKE)"Remove like" else "Like","youtube-actions-like",enabled=!busy && (ready || m.account==null),selected=rating==YouTubeRating.LIKE) {
            signedIn { val next=if(details?.rating==YouTubeRating.LIKE)YouTubeRating.NONE else YouTubeRating.LIKE
                val previous=details?.rating
                run("Updating rating…",action={m.client.rate(video.id,next,previous,expectedScope=it)}){details=details?.copy(rating=next);notice=if(next==YouTubeRating.NONE)"Like removed" else "Liked";m.accountMutated(YouTubeAccountMutation.RATING)} }
        }
        row(if(rating==YouTubeRating.DISLIKE)"Remove dislike" else "Dislike","youtube-actions-dislike",enabled=!busy && (ready || m.account==null),selected=rating==YouTubeRating.DISLIKE) {
            signedIn { val next=if(details?.rating==YouTubeRating.DISLIKE)YouTubeRating.NONE else YouTubeRating.DISLIKE
                val previous=details?.rating
                run("Updating rating…",action={m.client.rate(video.id,next,previous,expectedScope=it)}){details=details?.copy(rating=next);notice=if(next==YouTubeRating.NONE)"Dislike removed" else "Disliked";m.accountMutated(YouTubeAccountMutation.RATING)} }
        }
        row("Save to playlist","youtube-actions-save") { signedIn { loadPlaylists() } }
        if(channel!=null)row(if(channel.subscribed==true && signedIn)"Unsubscribe" else "Subscribe","youtube-actions-subscribe",enabled=!busy && (ready || m.account==null),selected=channel.subscribed==true && signedIn) {
            signedIn {
                val next=channel.subscribed!=true
                run(if(next)"Subscribing…" else "Unsubscribing…",action={m.client.subscribe(channel,next,expectedScope=it)}) {
                    details=details?.copy(channel=channel.copy(subscribed=next));notice=if(next)"Subscribed" else "Unsubscribed"
                    m.accountMutated(YouTubeAccountMutation.SUBSCRIPTION,channel.id)
                }
            }
        }
        row("Link phone","youtube-actions-phone",enabled=true) { close();a.connectYouTubePhone() }
        val playing=(a.model.route as? Route.Player)?.watch
        if(playing?.playbackMode==PlaybackMode.YOUTUBE && !m.phoneControlsQueue && !video.isShort && !video.live) {
            if(playing.title.id!=video.id) {
                val queued=m.queueStatus(video)
                if(queued in listOf(YouTubeQueueStatus.AVAILABLE,YouTubeQueueStatus.QUEUED,YouTubeQueueStatus.FULL)) {
                    val label=when(queued){YouTubeQueueStatus.QUEUED->"In queue";YouTubeQueueStatus.FULL->"Queue full (20 videos)";else->"Add to queue"}
                    row(label,"youtube-actions-queue",enabled=queued==YouTubeQueueStatus.AVAILABLE,selected=queued==YouTubeQueueStatus.QUEUED) {
                        notice=if(m.enqueue(video))"Added to queue" else "The queue changed. Choose another video."
                        render()
                    }
                }
            }
            row("Autoplay next video: "+if(a.model.preferences.youtubeAutoplay)"On" else "Off","youtube-actions-autoplay",enabled=true) {
                a.model.updatePreferences(a.model.preferences.copy(youtubeAutoplay=!a.model.preferences.youtubeAutoplay));render()
            }
        }
    }
    private fun loadPlaylists()=run("Loading playlists…",bound=false,action={m.client.playlists(video.id,expectedScope=it)}) {
        playlists=it.filter{p->p.editable}.take(40);page=Page.PLAYLISTS;notice="";nextFocus=playlists.firstOrNull()?.let{p->"youtube-playlist:${p.id}"}?:"youtube-actions-new-playlist"
    }
    private fun renderPlaylists() {
        row("‹ Video actions","youtube-actions-back",enabled=!busy){page=Page.ACTIONS;error=null;notice="";render()}
        if(playlists.isEmpty() && !busy)rows.add(a.text("No editable playlists",13f,MUTED))
        for(playlist in playlists)row((if(playlist.containsVideo)"✓  " else "    ")+playlist.name,"youtube-playlist:${playlist.id}",selected=playlist.containsVideo) {
            signedIn { val saved=!playlist.containsVideo
                run(if(saved)"Saving…" else "Removing…",action={m.client.setSaved(video.id,playlist.id,saved,expectedScope=it)}) {
                    playlists=playlists.map{if(it.id==playlist.id)it.copy(containsVideo=saved) else it}
                    notice=if(saved)"Saved to ${playlist.name}" else "Removed from ${playlist.name}"
                    m.accountMutated(YouTubeAccountMutation.PLAYLIST)
                }
            }
        }
        row("New playlist","youtube-actions-new-playlist"){page=Page.CREATE;error=null;notice="";render();rows.findTag("field:Playlist name")?.requestFocus()}
        if(error!=null)row("Reload playlists","youtube-actions-playlists-retry"){loadPlaylists()}
    }
    private fun renderCreate() {
        row("‹ Playlists","youtube-actions-back",enabled=!busy){page=Page.PLAYLISTS;error=null;notice="";render()}
        rows.add(a.field("Playlist name",playlistName){playlistName=it.take(100)})
        (rows.findTag("field:Playlist name") as? EditText)?.apply { filters=arrayOf(InputFilter.LengthFilter(100));isEnabled=!busy }
        row("Create private playlist","youtube-actions-create"){signedIn {
            val name=playlistName.trim()
            if(name.isBlank()){error="Enter a playlist name.";render();return@signedIn}
            run("Creating playlist…",action={m.client.createPlaylist(name,video.id,expectedScope=it)}) { id->
                playlists=(listOf(YouTubePlaylist(id,name,true))+playlists).distinctBy{it.id}.take(40)
                playlistName="";page=Page.PLAYLISTS;notice="Saved to $name";nextFocus="youtube-playlist:$id"
                m.accountMutated(YouTubeAccountMutation.PLAYLIST)
            }
        } }
    }
    fun close(){dialog?.dismiss()}
}

/** Leaving playback removes its one route, rather than stacking a hidden decoder under a channel. */
internal fun openYouTubeChannel(a:MainActivity,id:String) {
    if(id.isBlank())return
    if(a.model.route is Route.Player)a.model.back()
    a.model.youtube.openChannel(id)
}
