package xyz.billybishop4.fleet.tv

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A small, lazy feature model. Account changes invalidate all recommendation/search pages. */
internal class YouTubeModel(
    internal val client:YouTubeClient,private val scope:CoroutineScope,private val changed:()->Unit,
    private val open:(Watch,Boolean)->Unit,private val notice:(String)->Unit,
    private val resolver:YouTubeStreamResolver=YouTubeResolver(),
) {
    private fun <T> state(initial:T)=kotlin.properties.Delegates.observable(initial) { _,old,value->if(old!=value)changed() }
    var feed by state(YouTubeFeed.HOME);private set
    var query by state("");private set
    var videos by state<List<YouTubeVideo>>(emptyList());private set
    var homeShelves by state<List<YouTubeShelf>>(emptyList());private set
    var searchShelves by state<List<YouTubeShelf>>(emptyList());private set
    var playlistCards by state<List<YouTubePlaylist>>(emptyList());private set
    var selectedPlaylist by state<YouTubePlaylist?>(null);private set
    var channelPage by state<YouTubeChannelPage?>(null);private set
    var channelId:String="";private set
    var recentQueries by state<List<String>>(emptyList());private set
    var continuation by state<String?>(null);private set
    var message by state("");private set
    var busy by state(false);private set
    var error by state<String?>(null);private set
    var account by state<YouTubeSession?>(null);private set
    var resolving by state(false);private set
    var signInCode by state<YouTubeDeviceCode?>(null);private set
    var signInBusy by state(false);private set
    var signInError by state<String?>(null);private set
    private var pageJob:Job?=null;private var queryJob:Job?=null;private var resolveJob:Job?=null;private var signInJob:Job?=null
    private var pageGeneration=0;private var resolveGeneration=0;private var loginGeneration=0
    private var remoteResolveGeneration:Int?=null
    private data class Cached(val page:YouTubePage,val at:Long,val channel:YouTubeChannelPage?=null,val playlists:List<YouTubePlaylist> = emptyList())
    private val pages=linkedMapOf<String,Cached>()
    private var channelTab:YouTubeChannelTab?=null
    private var channelReturn:Pair<YouTubeFeed,String>?=null
    private val historyLazy=lazy{YouTubeHistorySync(client,scope)}
    internal val history get()=historyLazy.value
    private val localQueue=YouTubeLocalQueue()
    internal val phoneControlsQueue get()=localQueue.phoneOwned
    private var nextId:String?=null;private var nextScope:YouTubeAccountScope?=null
    private var nextDetails:YouTubeWatchDetails?=null;private var nextRequest:Deferred<Result<YouTubeWatchDetails>>?=null
    private var prepareJob:Job?=null;private var prepareGeneration=0
    private var active=false
    private var queue:List<YouTubeVideo> = emptyList()
    private var queueContinuation:String?=null
    private var queueFeed=YouTubeFeed.HOME
    private data class QueueReplacement(val items:List<YouTubeVideo>,val continuation:String?,val feed:YouTubeFeed,val phoneOwned:Boolean=false)
    private var requestedVideo:String?=null
    private var displayedKey:String?=null
    private var displayedAccountScope:YouTubeAccountScope?=null
    private var preloadJob:Deferred<Watch?>?=null;private var preloadId:String?=null;private var preloadGeneration=0
    private var preloadStarted=false
    private var preloaded:Watch?=null
    // Three short-lived URL records, never decoded video or a second media buffer.
    private val sources=linkedMapOf<String,Watch>()
    private fun cachedSource(id:String):Watch? {
        val watch=sources.remove(id)?:return null
        return watch.takeIf{System.currentTimeMillis()-it.resolvedAtMs in 0..60_000L}?.also{sources[id]=it}
    }
    private fun rememberSource(watch:Watch) {
        sources.remove(watch.title.id);sources[watch.title.id]=watch.copy(playRequestedAtMs=0,requestedPositionMs=null,remoteResumePositionMs=null)
        while(sources.size>3)sources.remove(sources.keys.first())
    }
    private fun key()="$feed/${when(feed){YouTubeFeed.SEARCH->query.trim();YouTubeFeed.CHANNEL->"$channelId/${channelTab?.id.orEmpty()}";YouTubeFeed.PLAYLIST->selectedPlaylist?.id.orEmpty();else->""}}"
    fun activate() {
        active=true
        if(query.trim().length>=2)feed=YouTubeFeed.SEARCH
        if(feed==YouTubeFeed.SEARCH && query.trim().length<2)return
        if(!busy && ((if(feed==YouTubeFeed.PLAYLISTS)playlistCards.isEmpty() else videos.isEmpty()) || displayedKey!=key()))load()
        else if(!busy)scope.launch { val current=client.accountScope();if(active && current!=displayedAccountScope)syncAccount() }
    }
    fun deactivate() {
        active=false;pageJob?.cancel();queryJob?.cancel();pageGeneration++;busy=false
        cancelPlayback();cancelSignIn()
    }
    fun select(next:YouTubeFeed) {
        if(next==YouTubeFeed.SEARCH){showSearch();return}
        if(next==feed && query.isEmpty() && error==null) { if((if(feed==YouTubeFeed.PLAYLISTS)playlistCards.isEmpty() else videos.isEmpty()) && !busy)load(force=true);return }
        queryJob?.cancel();query="";feed=next;channelPage=null;channelReturn=null;selectedPlaylist=null;load()
    }
    fun openPlaylist(playlist:YouTubePlaylist) {
        if(!YouTubeSocialDecode.validPlaylistId(playlist.id))return
        queryJob?.cancel();query="";selectedPlaylist=playlist;feed=YouTubeFeed.PLAYLIST;channelPage=null;channelReturn=null;load()
    }
    fun backPlaylist():Boolean {
        if(feed!=YouTubeFeed.PLAYLIST)return false
        select(YouTubeFeed.PLAYLISTS);return true
    }
    fun openChannel(id:String) {
        if(!YouTubeSocialDecode.validChannelId(id))return
        if(feed!=YouTubeFeed.CHANNEL)channelReturn=feed to query
        queryJob?.cancel();query="";feed=YouTubeFeed.CHANNEL;channelId=id;channelTab=null;channelPage=null;load()
    }
    fun selectChannelTab(tabId:String) {
        val tab=channelPage?.tabs?.firstOrNull{it.id==tabId}?:return
        if(channelTab?.id==tab.id || channelPage?.selectedTab==tab.id)return
        channelTab=tab;load()
    }
    fun backChannel():Boolean {
        if(feed!=YouTubeFeed.CHANNEL)return false
        val previous=channelReturn?: (YouTubeFeed.HOME to "")
        queryJob?.cancel();feed=previous.first;query=previous.second;channelReturn=null;channelPage=null;channelId="";channelTab=null;load()
        return true
    }
    suspend fun syncAccount() {
        val fresh=client.account();val currentScope=client.accountScope()
        account=fresh
        if(displayedAccountScope!=currentScope) {
            clearNext();localQueue.clear()
            displayedAccountScope=currentScope;pageJob?.cancel();pageGeneration++;busy=false;pages.clear();sources.clear()
            cancelShortPreload()
            videos=emptyList();homeShelves=emptyList();searchShelves=emptyList();playlistCards=emptyList();selectedPlaylist=null;channelPage=null;continuation=null;displayedKey=null;queue=emptyList();queueContinuation=null
            if(historyLazy.isInitialized())historyLazy.value.cancel()
            if(active)load(force=true)
        }
    }
    /** Action sheets may be opened by a phone before browsing was activated. */
    suspend fun refreshAccountState(){account=client.account()}
    private fun clearNext() {
        prepareGeneration++;prepareJob?.cancel();prepareJob=null;nextRequest?.cancel();nextRequest=null;nextDetails=null;nextId=null;nextScope=null
    }
    /** One shared request after first frame or an explicit watch-panel action.
     * Closing a panel cancels its await, not the model's bounded shared request. */
    suspend fun watchDetails(videoId:String):YouTubeWatchDetails {
        require(YouTubeDecode.validId(videoId))
        val accountScope=client.accountScope()
        if(nextId!=videoId || nextScope!=accountScope) {
            nextRequest?.cancel();nextRequest=null;nextDetails=null;nextId=videoId;nextScope=accountScope
        }
        nextDetails?.let{if(localQueue.offer(videoId,it.related.videos))changed();return it}
        val request=nextRequest?:scope.async {
            try{Result.success(client.watchDetails(videoId,expectedScope=accountScope))}
            catch(e:CancellationException){throw e}
            catch(e:Exception){Result.failure(e)}
        }.also{nextRequest=it}
        try {
            val details=request.await().getOrThrow()
            if(nextId!=videoId || nextRequest!==request || client.accountScope()!=accountScope)throw YouTubeAuthError("unauthorized")
            nextDetails=details
            if(localQueue.offer(videoId,details.related.videos))changed()
            return details
        }catch(e:CancellationException){if(request.isCancelled && nextRequest===request){nextRequest=null;nextDetails=null};throw e}
        catch(e:Exception){if(nextRequest===request){nextRequest=null;nextDetails=null};throw e}
    }
    fun prepareNext(watch:Watch,onReady:()->Unit={}) {
        if(watch.short || watch.live || watch.title.type!="youtube" || !YouTubeDecode.validId(watch.title.id) || localQueue.phoneOwned){onReady();return}
        // Account changes discard account-bound choices, but the current
        // decoder keeps playing. A direct/restored player also bypasses resolve.
        // Adopt only an empty current slot; never overwrite a newer local item.
        if(localQueue.currentId==null)localQueue.opened(YouTubeVideo(watch.title.id,watch.title.name,watch.title.poster,metadata=watch.title.description))
        else if(localQueue.currentId!=watch.title.id){onReady();return}
        prepareJob?.cancel();val generation=++prepareGeneration
        prepareJob=scope.launch {
            try{watchDetails(watch.title.id)}catch(e:CancellationException){throw e}catch(_:Exception){/* Optional recommendations never interrupt video. */}
            finally{if(generation==prepareGeneration){prepareJob=null;onReady()}}
        }
    }
    fun enqueue(video:YouTubeVideo):Boolean=localQueue.enqueue(video).also{if(it)changed()}
    fun queueStatus(video:YouTubeVideo):YouTubeQueueStatus=localQueue.status(video)
    fun canStepRegular(watch:Watch,direction:Int):Boolean = watch.title.type=="youtube" && !watch.short && !resolving && localQueue.peek(watch.title.id,direction)!=null
    fun stepRegular(watch:Watch,direction:Int):Boolean {
        if(watch.title.type!="youtube" || watch.short || resolving)return false
        val candidate=localQueue.peek(watch.title.id,direction)?:return false
        resolve(candidate,true);return true
    }
    fun onRegularEnded(watch:Watch,autoplayEnabled:Boolean):Boolean = autoplayEnabled && !watch.live && stepRegular(watch,1)
    /** Called only after an acknowledged mutation. A player keeps its decoder,
     * source and queue; affected browse pages refresh now or upon returning. */
    fun accountMutated(kind:YouTubeAccountMutation,channelId:String?=null) {
        // Current watch-next metadata is read-only. Keep the visible Related
        // panel/request stable; interaction() always obtains fresh action state.
        val prefixes=when(kind) {
            YouTubeAccountMutation.RATING->listOf("HOME/","LIKED/","PLAYLISTS/","PLAYLIST/LL")
            YouTubeAccountMutation.SUBSCRIPTION->listOf("HOME/","SUBSCRIPTIONS/",if(channelId==null)"CHANNEL/" else "CHANNEL/$channelId/")
            YouTubeAccountMutation.PLAYLIST->listOf("HOME/","WATCH_LATER/","PLAYLISTS/","PLAYLIST/")
        }
        fun affected(value:String)=prefixes.any(value::startsWith)
        pages.keys.filter(::affected).forEach(pages::remove)
        if(affected(key())) {
            displayedKey=null
            if(active)load(force=true,preservePlayback=true)
        }
    }
    fun showSearch() {
        if(feed==YouTubeFeed.SEARCH)return
        queryJob?.cancel();pageJob?.cancel();pageGeneration++;cancelPlayback()
        query="";feed=YouTubeFeed.SEARCH;channelPage=null;channelReturn=null;videos=emptyList();homeShelves=emptyList();searchShelves=emptyList();playlistCards=emptyList();continuation=null;message="";error=null;busy=false
    }
    fun editQuery(value:String) {
        if(query==value)return
        query=value.take(200);queryJob?.cancel();pageJob?.cancel();pageGeneration++;busy=false;cancelPlayback()
        if(query.trim().length<2) {
            feed=YouTubeFeed.SEARCH;videos=emptyList();homeShelves=emptyList();searchShelves=emptyList();continuation=null;message="";error=null
            return
        }
        queryJob=scope.launch { delay(400);feed=YouTubeFeed.SEARCH;load() }
    }
    fun submitSearch() {
        if(query.trim().length<2)return
        recentQueries=(listOf(query.trim())+recentQueries).distinct().take(5)
        queryJob?.cancel();feed=YouTubeFeed.SEARCH;load()
    }
    fun refresh(){load(force=true)}
    fun more() { if(!busy && continuation!=null && (if(feed==YouTubeFeed.PLAYLISTS)playlistCards.size<60 else videos.size<120))load(append=true) }
    private fun failure(e:Exception):String=when(e) {
        is UserMessage -> e.message.orEmpty()
        is HttpFailure -> if(e.status==429)"YouTube is busy. Wait a moment and try again." else "YouTube couldn't load this page. Try again."
        else -> "Couldn't reach YouTube. Check your connection and try again."
    }
    private fun load(force:Boolean=false,append:Boolean=false,preservePlayback:Boolean=false) {
        pageJob?.cancel()
        // A scroll-triggered continuation must not cancel a video the viewer has just selected.
        if(!append && !preservePlayback)cancelPlayback()
        val generation=++pageGeneration;val key=key();val q=query;val f=feed;val creator=channelId;val tab=channelTab;val playlist=selectedPlaylist
        error=null
        if(!append) {
            val cached=pages[key]?.takeIf { !force && System.currentTimeMillis()-it.at in 0..120_000L }
            if(cached!=null) { videos=cached.page.videos;homeShelves=if(f==YouTubeFeed.HOME)cached.page.shelves else emptyList();searchShelves=if(f==YouTubeFeed.SEARCH)cached.page.shelves else emptyList();playlistCards=cached.playlists;channelPage=cached.channel;continuation=cached.page.continuation;message=cached.page.message;displayedKey=key;busy=false;return }
            videos=emptyList();homeShelves=emptyList();searchShelves=emptyList();continuation=null;message=""
            playlistCards=emptyList()
        }
        val cursor=continuation;busy=true
        pageJob=scope.launch {
            try {
                account=client.account()
                displayedAccountScope=client.accountScope()
                val channel=if(f==YouTubeFeed.CHANNEL)client.channel(creator,tab,if(append)cursor else null) else null
                val collection=if(f==YouTubeFeed.PLAYLISTS && account!=null)client.playlistCollection(if(append)cursor else null) else null
                val page=when {
                    channel!=null->channel.page
                    collection!=null->YouTubePage(emptyList(),collection.continuation,collection.message)
                    f==YouTubeFeed.PLAYLIST && account!=null->playlist?.let{client.playlistVideos(it.id,if(append)cursor else null)}?:YouTubePage(emptyList(),message="Choose a playlist to see its videos.")
                    else->client.page(f,q,if(append)cursor else null)
                }
                if(generation!=pageGeneration)return@launch
                account=client.account()
                displayedAccountScope=client.accountScope()
                val searchRows=if(f==YouTubeFeed.SEARCH)YouTubeSearchPages.merge(if(append)searchShelves else emptyList(),page.shelves.ifEmpty{YouTubeSearchPages.fromVideos(page.videos)}) else emptyList()
                val combined=if(f==YouTubeFeed.SEARCH)searchRows.flatMap(YouTubeShelf::videos) else if(append)(videos+page.videos).distinctBy { it.id }.take(120) else page.videos.take(120)
                if(channel!=null) {
                    val header=if(channel.tabs.isEmpty())channelPage?:channel else channel
                    channelPage=header.copy(selectedTab=tab?.id?:channel.selectedTab,page=page.copy(videos=combined))
                }
                homeShelves=if(f==YouTubeFeed.HOME)mergeShelves(if(append)homeShelves else emptyList(),page.shelves,combined) else emptyList()
                searchShelves=searchRows
                playlistCards=if(collection!=null)(if(append)playlistCards+collection.playlists else collection.playlists).distinctBy{it.id}.take(60) else emptyList()
                val full=if(f==YouTubeFeed.PLAYLISTS)playlistCards.size>=60 else combined.size>=120
                videos=combined;continuation=if(full || append && page.continuation==cursor)null else page.continuation;message=page.message;displayedKey=key
                if(f==YouTubeFeed.SEARCH)pages.keys.filter { it.startsWith("SEARCH/") && it!=key }.forEach(pages::remove)
                pages[key]=Cached(YouTubePage(videos,continuation,message,if(f==YouTubeFeed.SEARCH)searchShelves else homeShelves),System.currentTimeMillis(),channelPage.takeIf{f==YouTubeFeed.CHANNEL},playlistCards)
                while(pages.size>3)pages.remove(pages.keys.first())
            } catch(e:CancellationException){throw e}
            catch(e:Exception) { if(generation==pageGeneration) {
                error=failure(e);account=client.account()
                if(e is YouTubeAuthError) { pages.clear();videos=emptyList();homeShelves=emptyList();searchShelves=emptyList();playlistCards=emptyList();selectedPlaylist=null;continuation=null;displayedKey=null }
            } }
            finally { if(generation==pageGeneration)busy=false }
        }
    }
    private fun mergeShelves(old:List<YouTubeShelf>,fresh:List<YouTubeShelf>,allowed:List<YouTubeVideo>):List<YouTubeShelf> {
        val ids=allowed.mapTo(HashSet()){it.id};val rows=linkedMapOf<String,YouTubeShelf>();var remaining=120
        for(row in old+fresh) {
            val previous=rows[row.key]
            rows[row.key]=row.copy(videos=(previous?.videos.orEmpty()+row.videos).distinctBy{it.id}.filter{it.id in ids})
        }
        return rows.values.take(10).mapNotNull { row->
            val items=row.videos.take(remaining);remaining-=items.size
            if(items.isEmpty())null else row.copy(videos=items)
        }.ifEmpty { if(allowed.isEmpty())emptyList() else listOf(YouTubeShelf("recommended/0","Recommended",allowed)) }
    }
    fun play(video:YouTubeVideo,group:List<YouTubeVideo>?=null) {
        queryJob?.cancel()
        pages.remove("HOME/");pages.remove("HISTORY/")
        val items=group?:videos
        resolve(video,false,queueReplacement=QueueReplacement(if(video.isShort)items.filter{it.isShort} else items,
            continuation.takeIf{group==null && (!video.isShort || feed==YouTubeFeed.SHORTS)},feed))
    }
    /** Lounge Play uses the same bounded resolver cache and replaces the active
     * YouTube item. A sender's position is never stored in the source cache. */
    internal fun playRemote(video:YouTubeVideo,positionMs:Long=0) {
        queryJob?.cancel();pages.remove("HOME/");pages.remove("HISTORY/")
        resolve(video,true,requestedPositionMs=positionMs.coerceAtLeast(0),
            queueReplacement=QueueReplacement(emptyList(),null,YouTubeFeed.HOME,phoneOwned=true))
    }
    fun playRelated(video:YouTubeVideo,related:List<YouTubeVideo>,done:()->Unit) {
        resolve(video,true,done,queueReplacement=QueueReplacement(if(video.isShort)related.filter{it.isShort} else related,null,YouTubeFeed.HOME))
    }
    private fun resolve(video:YouTubeVideo,replace:Boolean,done:(()->Unit)?=null,requestedPositionMs:Long?=null,queueReplacement:QueueReplacement?=null) {
        val requestedAt=playbackNowMs()
        val warm=preloaded?.takeIf{it.title.id==video.id && System.currentTimeMillis()-it.resolvedAtMs in 0..60_000L}?:cachedSource(video.id)
        // Adopt an already running next-Short request instead of repeating its RPCs.
        // A warmup still in its initial delay is cancelled so explicit Play never waits for that delay.
        val pending=preloadJob?.takeIf{warm==null && preloadId==video.id && preloadStarted}
        if(pending==null)cancelShortPreload() else {preloadJob=null;preloadId=null;preloaded=null;preloadGeneration++;preloadStarted=false}
        resolveJob?.cancel();val generation=++resolveGeneration
        remoteResolveGeneration=generation.takeIf{requestedPositionMs!=null}
        resolving=true;requestedVideo=video.id;error=null
        resolveJob=scope.launch {
            try {
                val resolved=warm?:pending?.await()?:resolver.resolve(video)
                val watch=resolved.copy(title=if(requestedPositionMs!=null)resolved.title else video.title(),short=video.isShort)
                if(generation==resolveGeneration) { rememberSource(watch);resolving=false;requestedVideo=null
                    // Commit ownership and queue reset with the source that won
                    // this generation. A failed/cancelled replacement leaves the
                    // still-playing video's metadata, choices and trail intact.
                    if(queueReplacement!=null) {
                        clearNext()
                        queue=queueReplacement.items;queueContinuation=queueReplacement.continuation;queueFeed=queueReplacement.feed
                        if(queueReplacement.phoneOwned)localQueue.beginRemote() else localQueue.beginLocal()
                    }else if(nextId!=video.id)clearNext()
                    localQueue.opened(video.copy(name=watch.title.name))
                    open(watch.copy(playRequestedAtMs=requestedAt,requestedPositionMs=requestedPositionMs,remoteResumePositionMs=video.resumePositionMs),replace) }
            } catch(e:CancellationException){throw e}
            catch(e:Exception) { if(generation==resolveGeneration) {
                val text=(e as? UserMessage)?.message?:"YouTube couldn't play this video. Try again or choose another video."
                if(replace)notice(text) else error=text
            } }
            finally { pending?.cancel();if(generation==resolveGeneration){resolving=false;requestedVideo=null;remoteResolveGeneration=null};done?.invoke() }
        }
    }
    /** Disconnect only retires the phone request still waiting for a source.
     * Completed playback and a newer local Play belong to their own lifecycle.
     * Retiring a handoff can retain the continuing video's phone queue owner. */
    internal fun cancelRemotePlayback(releaseQueue:Boolean=true) {
        if(releaseQueue){val phoneOwned=localQueue.phoneOwned;localQueue.releasePhone();if(phoneOwned)changed()}
        if(remoteResolveGeneration==resolveGeneration)cancelPlayback()
    }
    fun cancelPlayback() { resolveJob?.cancel();resolveJob=null;resolveGeneration++;remoteResolveGeneration=null;resolving=false;requestedVideo=null;cancelShortPreload() }
    /** One upcoming clip's URL only. No second player, decoder or media buffer is allocated. */
    fun preloadShort(watch:Watch) {
        if(!watch.short)return
        val index=queue.indexOfFirst{it.id==watch.title.id};if(index<0)return
        val next=queue.getOrNull(index+1)?:return
        cachedSource(next.id)?.let{cancelShortPreload();preloaded=it;return}
        if(preloadId==next.id || preloaded?.let{it.title.id==next.id && System.currentTimeMillis()-it.resolvedAtMs in 0..60_000L}==true)return
        cancelShortPreload();preloadId=next.id;val generation=preloadGeneration
        preloadJob=scope.async {
            try { delay(600);if(generation==preloadGeneration)preloadStarted=true
                val resolved=resolver.resolve(next);if(generation==preloadGeneration)preloaded=resolved;resolved }
            catch(e:CancellationException){throw e}
            catch(_:Exception){null /* Optional warmup never interrupts the current clip. */ }
            finally{if(generation==preloadGeneration){preloadId=null;preloadStarted=false}}
        }
    }
    fun cancelShortPreload(){preloadJob?.cancel();preloadJob=null;preloadGeneration++;preloadId=null;preloaded=null;preloadStarted=false}
    fun step(watch:Watch,direction:Int) {
        if(!watch.short || queue.isEmpty())return
        val id=requestedVideo?:watch.title.id
        val index=queue.indexOfFirst { it.id==id };if(index<0)return
        val next=index+direction
        if(next in queue.indices)resolve(queue[next],true)
        else if(direction>0 && queueContinuation!=null && !resolving) {
            resolveJob?.cancel();val generation=++resolveGeneration;remoteResolveGeneration=null;resolving=true
            resolveJob=scope.launch {
                try {
                    val page=client.page(queueFeed,continuation=queueContinuation)
                    if(generation!=resolveGeneration)return@launch
                    val merged=(queue+page.videos).distinctBy { it.id }
                    val added=merged.drop(queue.size)
                    queue=merged.takeLast(120);queueContinuation=page.continuation
                    added.firstOrNull()?.let { resolve(it,true) }?:notice("No more Shorts right now.")
                } catch(e:CancellationException){throw e}
                catch(e:Exception){if(generation==resolveGeneration)notice(failure(e))}
                finally{if(generation==resolveGeneration)resolving=false}
            }
        }
    }
    suspend fun refreshWatch(watch:Watch):Watch {
        sources.remove(watch.title.id)
        return resolver.resolve(YouTubeVideo(watch.title.id,watch.title.name,watch.title.poster,isShort=watch.short,live=watch.live))
            .copy(title=watch.title).also(::rememberSource)
    }
    fun startSignIn() {
        cancelSignIn();val generation=++loginGeneration;signInBusy=true;signInError=null
        signInJob=scope.launch {
            try {
                val code=client.beginSignIn();if(generation!=loginGeneration)return@launch
                signInCode=code;signInBusy=false;var interval=code.intervalSeconds
                while(generation==loginGeneration) {
                    delay(interval*1000L)
                    val signedIn=try { client.pollSignIn(code) } catch(e:YouTubeAuthError) {
                        if(e.reason=="slow_down") { interval=(interval+5).coerceAtMost(60);continue };throw e
                    }
                    if(signedIn!=null && generation==loginGeneration) {
                        clearNext();localQueue.clear()
                        if(historyLazy.isInitialized())historyLazy.value.cancel()
                        queue=emptyList();queueContinuation=null;sources.clear();channelPage=null
                        videos=emptyList();homeShelves=emptyList();searchShelves=emptyList();playlistCards=emptyList();selectedPlaylist=null;continuation=null;displayedKey=null
                        account=signedIn;displayedAccountScope=client.accountScope();signInCode=null;signInBusy=false;pages.clear()
                        if(active)load(force=true)
                        return@launch
                    }
                }
            } catch(e:CancellationException){throw e}
            catch(e:Exception) { if(generation==loginGeneration){signInError=failure(e);signInCode=null;signInBusy=false} }
        }
    }
    fun cancelSignIn(){signInJob?.cancel();signInJob=null;loginGeneration++;signInCode=null;signInBusy=false;signInError=null}
    fun signOut() {
        clearNext();localQueue.clear()
        if(historyLazy.isInitialized())historyLazy.value.cancel()
        queue=emptyList();queueContinuation=null
        sources.clear()
        cancelSignIn();cancelPlayback();pageJob?.cancel();pageGeneration++;pages.clear();videos=emptyList();homeShelves=emptyList();searchShelves=emptyList();playlistCards=emptyList();selectedPlaylist=null;channelPage=null;channelReturn=null;recentQueries=emptyList();continuation=null
        scope.launch {
            try { client.signOut();account=null;displayedAccountScope=null;if(active)load(force=true) }
            catch(e:CancellationException){throw e}
            catch(e:Exception) { error="Couldn't remove YouTube sign-in. Try again.";busy=false }
        }
    }
    fun trimMemory(keepPage:Boolean=false) {
        pages.clear();sources.clear();cancelShortPreload()
        if(!keepPage){queue=emptyList();queueContinuation=null;clearNext();localQueue.clear()}
        if(!active && !keepPage){videos=emptyList();homeShelves=emptyList();searchShelves=emptyList();playlistCards=emptyList();selectedPlaylist=null;continuation=null;message=""}
    }
    fun historyCleared() {
        sources.clear()
        pages.remove("HOME/");pages.remove("HISTORY/")
        if(feed==YouTubeFeed.HOME || feed==YouTubeFeed.HISTORY){videos=emptyList();homeShelves=emptyList();displayedKey=null;if(active)load(force=true)}
    }
    internal fun installFixturePage(feed:YouTubeFeed,page:YouTubePage) {
        pageJob?.cancel();queryJob?.cancel();pageGeneration++;this.feed=feed;query="";playlistCards=emptyList();searchShelves=if(feed==YouTubeFeed.SEARCH)page.shelves.ifEmpty{YouTubeSearchPages.fromVideos(page.videos)} else emptyList();videos=if(feed==YouTubeFeed.SEARCH)searchShelves.flatMap(YouTubeShelf::videos) else page.videos;homeShelves=if(feed==YouTubeFeed.HOME)mergeShelves(emptyList(),page.shelves,page.videos) else emptyList();continuation=page.continuation;message=page.message;busy=false;error=null
    }
}
