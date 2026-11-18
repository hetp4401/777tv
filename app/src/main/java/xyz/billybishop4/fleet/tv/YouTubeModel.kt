package xyz.billybishop4.fleet.tv

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
    private fun key()="$feed/${if(feed==YouTubeFeed.SEARCH)query.trim() else ""}"
    fun activate() {
        active=true
        if(query.trim().length>=2)feed=YouTubeFeed.SEARCH
        if(!busy && (videos.isEmpty() || displayedKey!=key()))load()
    }
    fun deactivate() {
        active=false;pageJob?.cancel();queryJob?.cancel();pageGeneration++;busy=false
        cancelPlayback();cancelSignIn()
    }
    fun select(next:YouTubeFeed) {
        if(next==feed && query.isEmpty() && error==null) { if(videos.isEmpty() && !busy)load(force=true);return }
        queryJob?.cancel();query="";feed=next;load()
    }
    fun editQuery(value:String) {
        if(query==value)return
        query=value.take(200);queryJob?.cancel();pageJob?.cancel();pageGeneration++;busy=false;cancelPlayback()
        if(query.trim().length<2) {
            feed=YouTubeFeed.HOME;load()
            return
        }
        queryJob=scope.launch { delay(400);feed=YouTubeFeed.SEARCH;load() }
    }
    fun submitSearch() {
        if(query.trim().length<2)return
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
            if(cached!=null) { videos=cached.page.videos;continuation=cached.page.continuation;message=cached.page.message;displayedKey=key;busy=false;return }
            videos=emptyList();continuation=null;message=""
        }
        val cursor=continuation;busy=true
        pageJob=scope.launch {
            try {
                account=client.account()
                val page=client.page(f,q,if(append)cursor else null)
                if(generation!=pageGeneration)return@launch
                account=client.account()
                val combined=if(append)(videos+page.videos).distinctBy { it.id }.take(120) else page.videos
                videos=combined;continuation=if(combined.size>=120 || append && page.continuation==cursor)null else page.continuation;message=page.message;displayedKey=key
                if(f==YouTubeFeed.SEARCH)pages.keys.filter { it.startsWith("SEARCH/") && it!=key }.forEach(pages::remove)
                pages[key]=Cached(YouTubePage(videos,continuation,message),System.currentTimeMillis())
                while(pages.size>3)pages.remove(pages.keys.first())
            } catch(e:CancellationException){throw e}
            catch(e:Exception) { if(generation==pageGeneration) {
                error=failure(e);account=client.account()
                if(e is YouTubeAuthError) { pages.clear();videos=emptyList();continuation=null;displayedKey=null }
            } }
            finally { if(generation==pageGeneration)busy=false }
        }
    }
    fun play(video:YouTubeVideo) {
        queue=videos;queueFeed=feed;queueContinuation=continuation
        resolve(video,false)
    }
    fun playRelated(video:YouTubeVideo,related:List<YouTubeVideo>,done:()->Unit) {
        queue=related;queueContinuation=null;queueFeed=YouTubeFeed.HOME
        resolve(video,true,done)
    }
    private fun resolve(video:YouTubeVideo,replace:Boolean,done:(()->Unit)?=null) {
        resolveJob?.cancel();val generation=++resolveGeneration;resolving=true;requestedVideo=video.id;error=null
        resolveJob=scope.launch {
            try {
                val watch=resolver.resolve(video)
                if(generation==resolveGeneration) { resolving=false;requestedVideo=null;open(watch,replace) }
            } catch(e:CancellationException){throw e}
            catch(e:Exception) { if(generation==resolveGeneration) {
                val text=(e as? UserMessage)?.message?:"YouTube couldn't play this video. Try again or choose another video."
                if(replace)notice(text) else error=text
            } }
            finally { if(generation==resolveGeneration){resolving=false;requestedVideo=null};done?.invoke() }
        }
    }
    fun cancelPlayback() { resolveJob?.cancel();resolveJob=null;resolveGeneration++;resolving=false;requestedVideo=null }
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
    suspend fun refreshWatch(watch:Watch):Watch=resolver.resolve(YouTubeVideo(watch.title.id,watch.title.name,watch.title.poster,isShort=watch.short,live=watch.live)).copy(title=watch.title)
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
        cancelSignIn();cancelPlayback();pageJob?.cancel();pageGeneration++;pages.clear();videos=emptyList();continuation=null
        scope.launch {
            try { client.signOut();account=null;if(active)load(force=true) }
            catch(e:CancellationException){throw e}
            catch(e:Exception) { error="Couldn't remove YouTube sign-in. Try again.";busy=false }
        }
    }
    fun trimMemory(keepPage:Boolean=false) {
        pages.clear()
        if(!keepPage){queue=emptyList();queueContinuation=null}
        if(!active && !keepPage){videos=emptyList();continuation=null;message=""}
    }
    internal fun installFixturePage(feed:YouTubeFeed,page:YouTubePage) {
        pageJob?.cancel();queryJob?.cancel();pageGeneration++;this.feed=feed;query="";videos=page.videos;continuation=page.continuation;message=page.message;busy=false;error=null
    }
}
