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
    private data class Cached(val page:YouTubePage,val at:Long)
    private val pages=linkedMapOf<String,Cached>()
    private var active=false
    private var queue:List<YouTubeVideo> = emptyList()
    private var queueContinuation:String?=null
    private var queueFeed=YouTubeFeed.HOME
    private var requestedVideo:String?=null
    private var displayedKey:String?=null
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
        sources.remove(watch.title.id);sources[watch.title.id]=watch.copy(playRequestedAtMs=0)
        while(sources.size>3)sources.remove(sources.keys.first())
    }
    private fun key()="$feed/${if(feed==YouTubeFeed.SEARCH)query.trim() else ""}"
    fun activate() {
        active=true
        if(query.trim().length>=2)feed=YouTubeFeed.SEARCH
        if(feed==YouTubeFeed.SEARCH && query.trim().length<2)return
        if(!busy && (videos.isEmpty() || displayedKey!=key()))load()
    }
    fun deactivate() {
        active=false;pageJob?.cancel();queryJob?.cancel();pageGeneration++;busy=false
        cancelPlayback();cancelSignIn()
    }
    fun select(next:YouTubeFeed) {
        if(next==YouTubeFeed.SEARCH){showSearch();return}
        if(next==feed && query.isEmpty() && error==null) { if(videos.isEmpty() && !busy)load(force=true);return }
        queryJob?.cancel();query="";feed=next;load()
    }
    fun showSearch() {
        if(feed==YouTubeFeed.SEARCH)return
        queryJob?.cancel();pageJob?.cancel();pageGeneration++;cancelPlayback()
        query="";feed=YouTubeFeed.SEARCH;videos=emptyList();homeShelves=emptyList();continuation=null;message="";error=null;busy=false
    }
    fun editQuery(value:String) {
        if(query==value)return
        query=value.take(200);queryJob?.cancel();pageJob?.cancel();pageGeneration++;busy=false;cancelPlayback()
        if(query.trim().length<2) {
            feed=YouTubeFeed.SEARCH;videos=emptyList();homeShelves=emptyList();continuation=null;message="";error=null
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
    fun more() { if(!busy && continuation!=null && videos.size<120)load(append=true) }
    private fun failure(e:Exception):String=when(e) {
        is UserMessage -> e.message.orEmpty()
        is HttpFailure -> if(e.status==429)"YouTube is busy. Wait a moment and try again." else "YouTube couldn't load this page. Try again."
        else -> "Couldn't reach YouTube. Check your connection and try again."
    }
    private fun load(force:Boolean=false,append:Boolean=false) {
        pageJob?.cancel()
        // A scroll-triggered continuation must not cancel a video the viewer has just selected.
        if(!append)cancelPlayback()
        val generation=++pageGeneration;val key=key();val q=query;val f=feed
        error=null
        if(!append) {
            val cached=pages[key]?.takeIf { !force && System.currentTimeMillis()-it.at in 0..120_000L }
            if(cached!=null) { videos=cached.page.videos;homeShelves=cached.page.shelves;continuation=cached.page.continuation;message=cached.page.message;displayedKey=key;busy=false;return }
            videos=emptyList();homeShelves=emptyList();continuation=null;message=""
        }
        val cursor=continuation;busy=true
        pageJob=scope.launch {
            try {
                account=client.account()
                val page=client.page(f,q,if(append)cursor else null)
                if(generation!=pageGeneration)return@launch
                account=client.account()
                val combined=if(append)(videos+page.videos).distinctBy { it.id }.take(120) else page.videos.take(120)
                homeShelves=if(f==YouTubeFeed.HOME)mergeShelves(if(append)homeShelves else emptyList(),page.shelves,combined) else emptyList()
                videos=combined;continuation=if(combined.size>=120 || append && page.continuation==cursor)null else page.continuation;message=page.message;displayedKey=key
                if(f==YouTubeFeed.SEARCH)pages.keys.filter { it.startsWith("SEARCH/") && it!=key }.forEach(pages::remove)
                pages[key]=Cached(YouTubePage(videos,continuation,message,homeShelves),System.currentTimeMillis())
                while(pages.size>3)pages.remove(pages.keys.first())
            } catch(e:CancellationException){throw e}
            catch(e:Exception) { if(generation==pageGeneration) {
                error=failure(e);account=client.account()
                if(e is YouTubeAuthError) { pages.clear();videos=emptyList();homeShelves=emptyList();continuation=null;displayedKey=null }
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
        val items=group?:videos;queue=if(video.isShort)items.filter{it.isShort} else items
        queueFeed=feed;queueContinuation=continuation.takeIf{group==null && (!video.isShort || feed==YouTubeFeed.SHORTS)}
        resolve(video,false)
    }
    fun playRelated(video:YouTubeVideo,related:List<YouTubeVideo>,done:()->Unit) {
        queue=if(video.isShort)related.filter{it.isShort} else related;queueContinuation=null;queueFeed=YouTubeFeed.HOME
        resolve(video,true,done)
    }
    private fun resolve(video:YouTubeVideo,replace:Boolean,done:(()->Unit)?=null) {
        val requestedAt=playbackNowMs()
        val warm=preloaded?.takeIf{it.title.id==video.id && System.currentTimeMillis()-it.resolvedAtMs in 0..60_000L}?:cachedSource(video.id)
        // Adopt an already running next-Short request instead of repeating its RPCs.
        // A warmup still in its initial delay is cancelled so explicit Play never waits for that delay.
        val pending=preloadJob?.takeIf{warm==null && preloadId==video.id && preloadStarted}
        if(pending==null)cancelShortPreload() else {preloadJob=null;preloadId=null;preloaded=null;preloadGeneration++;preloadStarted=false}
        resolveJob?.cancel();val generation=++resolveGeneration;resolving=true;requestedVideo=video.id;error=null
        resolveJob=scope.launch {
            try {
                val watch=(warm?:pending?.await()?:resolver.resolve(video)).copy(title=video.title(),short=video.isShort)
                if(generation==resolveGeneration) { rememberSource(watch);resolving=false;requestedVideo=null;open(watch.copy(playRequestedAtMs=requestedAt),replace) }
            } catch(e:CancellationException){throw e}
            catch(e:Exception) { if(generation==resolveGeneration) {
                val text=(e as? UserMessage)?.message?:"YouTube couldn't play this video. Try again or choose another video."
                if(replace)notice(text) else error=text
            } }
            finally { pending?.cancel();if(generation==resolveGeneration){resolving=false;requestedVideo=null};done?.invoke() }
        }
    }
    fun cancelPlayback() { resolveJob?.cancel();resolveJob=null;resolveGeneration++;resolving=false;requestedVideo=null;cancelShortPreload() }
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
            resolveJob?.cancel();val generation=++resolveGeneration;resolving=true
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
                        account=signedIn;signInCode=null;signInBusy=false;pages.clear()
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
        sources.clear()
        cancelSignIn();cancelPlayback();pageJob?.cancel();pageGeneration++;pages.clear();videos=emptyList();homeShelves=emptyList();recentQueries=emptyList();continuation=null
        scope.launch {
            try { client.signOut();account=null;if(active)load(force=true) }
            catch(e:CancellationException){throw e}
            catch(e:Exception) { error="Couldn't remove YouTube sign-in. Try again.";busy=false }
        }
    }
    fun trimMemory(keepPage:Boolean=false) {
        pages.clear();sources.clear();cancelShortPreload()
        if(!keepPage){queue=emptyList();queueContinuation=null}
        if(!active && !keepPage){videos=emptyList();homeShelves=emptyList();continuation=null;message=""}
    }
    fun historyCleared() {
        sources.clear()
        pages.remove("HOME/");pages.remove("HISTORY/")
        if(feed==YouTubeFeed.HOME || feed==YouTubeFeed.HISTORY){videos=emptyList();homeShelves=emptyList();displayedKey=null;if(active)load(force=true)}
    }
    internal fun installFixturePage(feed:YouTubeFeed,page:YouTubePage) {
        pageJob?.cancel();queryJob?.cancel();pageGeneration++;this.feed=feed;query="";videos=page.videos;homeShelves=if(feed==YouTubeFeed.HOME)mergeShelves(emptyList(),page.shelves,page.videos) else emptyList();continuation=page.continuation;message=page.message;busy=false;error=null
    }
}
