@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package xyz.billybishop4.fleet.tv

import android.os.Bundle
import android.os.Parcelable
import android.view.*
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job

internal interface NativeScreen {
    val view: View
    fun update()
    fun saveState(): Parcelable? = null
    fun restoreState(state: Parcelable?) {}
    fun focusTarget():View?=null
    fun restoreFocus(tag:String,parentTag:String?):Boolean=false
    fun back():Boolean=false
    fun background() {}
    fun foreground() {}
    fun close() {}
}

class MainActivity : ComponentActivity() {
    val model: FleetModel by viewModels()
    private val artworkDelegate = lazy { ArtworkLoader.get(this) }
    internal val artwork get() = artworkDelegate.value
    private var lastLarge: Boolean? = null
    internal val posterPool = androidx.recyclerview.widget.RecyclerView.RecycledViewPool()
    private lateinit var root: FrameLayout
    private lateinit var chrome: LinearLayout
    private lateinit var host: FrameLayout
    private lateinit var error: TextView
    private lateinit var retry: Button
    private lateinit var loading: ProgressBar
    private lateinit var errorRow:LinearLayout
    private lateinit var live: Button
    private lateinit var search: Button
    private lateinit var youtube:Button
    private lateinit var settings: Button
    private var screen: NativeScreen? = null
    private var screenKey = ""
    private var screenRoute:Route?=null
    private var queued = false
    internal var playback: NativePlayback? = null; private set
    @get:androidx.annotation.VisibleForTesting
    val player: androidx.media3.exoplayer.ExoPlayer? get() = playback?.player
    private var previewHost: FrameLayout? = null
    private var previewIdentity: String? = null
    private var inlineFullscreen = false
    private var previewDecoration: FrameLayout? = null
    private var previewHintUntil=0L
    private var previewHintHide:Runnable?=null
    private val previewBounds=android.graphics.Rect()
    private val previewLocation=IntArray(2)
    private val rootLocation=IntArray(2)
    private val measuredPreviewBounds=android.graphics.Rect()
    @get:androidx.annotation.VisibleForTesting
    val activePlayer: androidx.media3.common.Player? get() = playback?.mediaPlayer
    @get:androidx.annotation.VisibleForTesting
    val playbackInfo: String get() = playback?.snapshot?.let { org.json.JSONObject()
        .put("engine",it.engine).put("playing",it.playing).put("positionMs",it.positionMs)
        .put("width",it.width).put("height",it.height).put("audio",it.audio).put("dropped",it.dropped)
        .put("decoder",it.decoder).put("audioTrack",it.audioTrack).put("subtitleText",it.subtitleText)
        .put("frameRateHint",it.frameRateHint).put("forwardCapMiB",it.forwardCapMiB).put("displayFps",it.displayFps).toString() } ?: "{}"
    private val states = linkedMapOf<String, Parcelable>()
    private data class FocusToken(val tag:String,val parent:String?)
    private val focusStates=linkedMapOf<String,FocusToken>()
    private var pendingFocus:String?=null
    private var restoringFocus=false
    internal var focusInputGeneration=0;private set
    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState?.getBundle("screen-states")?.let { saved -> saved.keySet().forEach { key -> saved.getParcelable<Parcelable>(key)?.let { states[key]=it } } }
        savedInstanceState?.getBundle("screen-focus")?.let { saved -> saved.keySet().forEach { key -> saved.getBundle(key)?.let { b ->
            b.getString("tag")?.let { focusStates[key]=FocusToken(it,b.getString("parent")) }
        } } }
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        root = object : FrameLayout(this) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if(event.action==KeyEvent.ACTION_DOWN){pendingFocus=null;focusInputGeneration++}
                if (playback?.dispatchKey(event) == true) return true
                return super.dispatchKeyEvent(event)
            }
        }.apply { setBackgroundColor(INK) }
        chrome = column(); chrome.add(topBar(), height = dp(66))
        val content = column().apply { setPadding(dp(24), 0, dp(24), dp(18)) }
        error = text("", 13f, 0xFFFFC4C4.toInt()).apply { tag = "error";maxLines=3 }
        retry = action("Retry","Try again") { model.retry() }.quiet()
        errorRow=row().apply {
            background=round(PANEL);pad(10);visibility=View.GONE
            addView(error,LinearLayout.LayoutParams(0,-2,1f));button(retry)
        }
        content.add(errorRow)
        host = FrameLayout(this)
        content.addView(host, LinearLayout.LayoutParams(-1, 0, 1f))
        chrome.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(chrome, FrameLayout.LayoutParams(-1, -1)); setContentView(root)
        loading=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate=true;visibility=View.GONE;tag="loading";importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        root.addView(loading,FrameLayout.LayoutParams(-1,dp(2)).apply { topMargin=dp(64);leftMargin=dp(24);rightMargin=dp(24) })
        root.viewTreeObserver.addOnGlobalLayoutListener { alignPreview();restorePendingFocus() }
        root.viewTreeObserver.addOnGlobalFocusChangeListener { _,focused ->
            if(focused!=null && insideHost(focused)) {
                rememberFocus(focused)
                if(!restoringFocus)pendingFocus=null
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= 30) window.insetsController?.hide(WindowInsets.Type.systemBars())
        else @Suppress("DEPRECATION") run { window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY }
        model.changes.observe(this) { scheduleRender() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (playback?.dismissControls()!=true && !collapsePreview() && screen?.back()!=true && !model.back()) finish() }
        })
        render()
    }
    override fun dispatchTouchEvent(event:android.view.MotionEvent):Boolean {
        if(event.actionMasked==android.view.MotionEvent.ACTION_DOWN){pendingFocus=null;focusInputGeneration++}
        return super.dispatchTouchEvent(event)
    }
    private fun topBar(): View {
        val bar = FrameLayout(this).apply { setPadding(dp(24), dp(5), dp(24), dp(5)) }
        bar.addView(text("777tv", 23f, bold = true), FrameLayout.LayoutParams(-2, -2, Gravity.START or Gravity.CENTER_VERTICAL))
        val tabs = row()
        fun tab(label: String, section: Section) = action(label) {
            if(section==Section.SEARCH)focusStates.remove(key(Route.Browse(Section.SEARCH)))
            model.top(section)
            if(section==Section.SEARCH && screenRoute==model.route && model.route==Route.Browse(Section.SEARCH)) {
                pendingFocus=null;screen?.view?.findTag("field:Search movies and shows")?.requestFocus()
            }
        }.apply {
            id=View.generateViewId()
            tag = "navigation:$label"
            background = android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), round(RAISED, WHITE))
                addState(intArrayOf(android.R.attr.state_selected), round(RAISED))
                addState(intArrayOf(), round(android.graphics.Color.TRANSPARENT))
            }
        }
        live = tab("Live TV", Section.LIVE); search = tab("On-Demand", Section.SEARCH);youtube=tab("YouTube",Section.YOUTUBE)
        tabs.addView(live, LinearLayout.LayoutParams(dp(112), dp(42)).apply { marginEnd=dp(6) })
        tabs.addView(search, LinearLayout.LayoutParams(dp(132), dp(42)).apply { marginEnd=dp(6) })
        tabs.addView(youtube,LinearLayout.LayoutParams(dp(112),dp(42)))
        bar.addView(tabs, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        settings = action("Settings") { model.open(Route.Settings) }.quiet()
        bar.addView(settings, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL))
        return bar
    }
    private fun scheduleRender() {
        if (queued || isFinishing || isDestroyed) return
        queued = true
        root.postOnAnimation { queued = false; if (!isDestroyed) render() }
    }
    private fun key(route: Route): String = when (route) {
        is Route.Browse -> "browse:${route.section}:${model.preferences.largePosters}"
        is Route.Channels -> "channels:${route.provider.id}"
        is Route.Detail -> "detail:${route.title.key}"
        is Route.Sources -> "sources:${route.title.key}/${route.episode?.id}"
        is Route.EditProvider -> "profile:${route.provider.id}"
        Route.AddProvider -> "profile:new:${model.providerDraft?.id}"
        Route.Settings -> "settings"
        Route.Addons -> "addons"
        is Route.Player -> "player:${System.identityHashCode(route.watch)}"
    }
    internal fun openTitle(title:Title,episode:Episode?=null) {
        val route=Route.Detail(title,episode)
        // A fresh poster selection follows current resume progress. Returning
        // through Back retains the explicit season and episode already saved.
        val destination=key(route);states.remove(destination);focusStates.remove(destination)
        model.open(route)
    }
    internal fun preview(provider:Provider,channel:Channel,container:FrameLayout,focus:View?) {
        val identity="${provider.id}/${channel.identity}"
        if(previewIdentity==identity && playback!=null)return
        closePlayback();previewHost=container;previewIdentity=identity
        val watch=Watch(Title(channel.id,channel.name,"channel"),Stream(channel.name,""),live=true,provider=provider,channel=channel)
        playback=NativePlayback(this,model,watch,fullscreen=false){collapsePreview()}.also {
            it.view.visibility=View.INVISIBLE
            root.addView(it.view,FrameLayout.LayoutParams(1,1,Gravity.TOP or Gravity.START))
            it.setFullscreen(false)
            if(lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED))it.start()
        }
        previewDecoration=FrameLayout(this).apply {
            addView(text("LIVE",10f,bold=true).apply{background=round(0xFFD43242.toInt(),radius=4);pad(4)},FrameLayout.LayoutParams(-2,-2,Gravity.TOP or Gravity.END).apply{setMargins(dp(8),dp(8),dp(8),0)})
            addView(text("Full screen",11f).apply{tag="preview-fullscreen-hint";background=round(0xB0000000.toInt(),radius=6);pad(5);visibility=View.GONE},FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM or Gravity.END).apply{setMargins(0,0,dp(8),dp(8))})
            foreground=android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_selected),round(android.graphics.Color.TRANSPARENT,WHITE))
                addState(intArrayOf(),round(android.graphics.Color.TRANSPARENT))
            }
            setOnClickListener{expandPreview()};isFocusable=false;isFocusableInTouchMode=false
        }.also{root.addView(it,FrameLayout.LayoutParams(1,1,Gravity.TOP or Gravity.START))}
        previewHintUntil=android.os.SystemClock.uptimeMillis()+5000
        previewFocused(false)
        previewHintHide=Runnable { previewHintUntil=0;previewFocused(previewDecoration?.isSelected==true);previewHintHide=null }.also { root.postDelayed(it,5000) }
        alignPreview()
    }
    /** The SurfaceView never changes parent, so decoder output surfaces survive fullscreen. */
    private fun alignPreview() {
        val container=previewHost?:return;val p=playback?:return
        if(inlineFullscreen || container.width==0 || container.height==0 || !container.isShown)return
        container.getLocationOnScreen(previewLocation);root.getLocationOnScreen(rootLocation)
        val bounds=measuredPreviewBounds.apply { set(previewLocation[0]-rootLocation[0],previewLocation[1]-rootLocation[1],
            previewLocation[0]-rootLocation[0]+container.width,previewLocation[1]-rootLocation[1]+container.height) }
        if(bounds!=previewBounds || p.view.width!=bounds.width() || p.view.height!=bounds.height()) {
            previewBounds.set(bounds)
            val params=FrameLayout.LayoutParams(bounds.width(),bounds.height(),Gravity.TOP or Gravity.START).apply{leftMargin=bounds.left;topMargin=bounds.top}
            p.view.layoutParams=params;previewDecoration?.layoutParams=FrameLayout.LayoutParams(params)
        }
        p.view.visibility=View.VISIBLE;previewDecoration?.visibility=View.VISIBLE
    }
    internal fun previewFocused(focused:Boolean) {
        previewDecoration?.isSelected=focused
        (previewDecoration?.findTag("preview-fullscreen-hint") as? android.widget.TextView)?.apply {
            text=if(focused)"Full screen" else "OK again for full screen"
            visibility=if(focused || android.os.SystemClock.uptimeMillis()<previewHintUntil)View.VISIBLE else View.GONE
        }
    }
    internal fun expandPreview() {
        val p=playback?:return
        if(previewHost==null || inlineFullscreen)return
        inlineFullscreen=true;chrome.visibility=View.GONE
        previewDecoration?.visibility=View.GONE;p.view.visibility=View.VISIBLE
        p.view.layoutParams=FrameLayout.LayoutParams(-1,-1);p.setFullscreen(true)
    }
    internal fun collapsePreview():Boolean {
        if(!inlineFullscreen)return false
        inlineFullscreen=false;val p=playback
        if(p!=null){
            if(!previewBounds.isEmpty)p.view.layoutParams=FrameLayout.LayoutParams(previewBounds.width(),previewBounds.height(),Gravity.TOP or Gravity.START).apply{leftMargin=previewBounds.left;topMargin=previewBounds.top}
            p.setFullscreen(false)
        }
        chrome.visibility=View.VISIBLE;alignPreview()
        if((screen as? ChannelsScreen)?.focusSelectedChannel()!=true)(previewHost?.parent as? View)?.requestFocus()
        return true
    }
    private fun closePlayback() {
        previewHintHide?.let { root.removeCallbacks(it) };previewHintHide=null;previewHintUntil=0
        playback?.let{it.close();(it.view.parent as? ViewGroup)?.removeView(it.view)};playback=null
        previewDecoration?.let{root.removeView(it)};previewDecoration=null;previewBounds.setEmpty()
        previewHost=null;previewIdentity=null;inlineFullscreen=false
    }
    private fun retireScreen() {
        (getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
            .hideSoftInputFromWindow(host.windowToken,0)
        currentFocus?.takeIf(::insideHost)?.let(::rememberFocus)
        screen?.saveState()?.let { states[screenKey] = it; if (states.size > 32) states.remove(states.keys.first()) }
        screen?.close(); screen = null; host.removeAllViews()
    }
    private fun insideHost(view:View):Boolean {
        var parent:android.view.ViewParent?=view.parent
        while(parent is View) { if(parent===host)return true;parent=parent.parent }
        return false
    }
    private fun rememberFocus(view:View) {
        val tag=view.tag as? String?:return
        var parent=view.parent as? View
        var context:String?=null
        while(parent!=null && parent!==host) {
            (parent.tag as? String)?.let { if(context==null)context=it }
            parent=parent.parent as? View
        }
        focusStates[screenKey]=FocusToken(tag,context)
        if(focusStates.size>32)focusStates.remove(focusStates.keys.first())
    }
    private fun restorePendingFocus() {
        val key=pendingFocus?:return
        if(key!=screenKey || inlineFullscreen || !hasWindowFocus() || host.isLayoutRequested)return
        val page=screen?:return
        val token=focusStates[key]
        val context=if(token?.parent!=null)page.view.findTag(token.parent) else page.view
        val previous=token?.tag?.let { context?.findTag(it) }?.takeIf { it.isShown && it.isFocusable && it.width>0 }
        val target=previous?:page.focusTarget()
        restoringFocus=true
        try {
            if(previous==null && token!=null && page.restoreFocus(token.tag,token.parent)) { pendingFocus=null;return }
            if(target!=null && target.isShown && target.isFocusable && target.width>0) {
                if(target.requestFocus())pendingFocus=null
            } else if(!model.busy && model.error!=null && errorRow.isShown) {
                if(retry.requestFocus())pendingFocus=null
            }
        } finally { restoringFocus=false }
    }
    internal fun render() {
        val route = model.route; val next = key(route)
        val changed=screenKey!=next || screenRoute!=route
        if (lastLarge != model.preferences.largePosters) { posterPool.clear(); lastLarge = model.preferences.largePosters }
        if (route is Route.Player) {
            if (changed) {
                retireScreen(); closePlayback(); chrome.visibility = View.GONE
                playback = NativePlayback(this, model, route.watch) { model.back() }.also { root.addView(it.view, FrameLayout.LayoutParams(-1, -1)); if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) it.start() }
                screenKey = next;screenRoute=route
            }
            return
        }
        if(changed)closePlayback()
        chrome.visibility = if(inlineFullscreen)View.GONE else View.VISIBLE
        live.isSelected = model.section == Section.LIVE; search.isSelected = model.section == Section.SEARCH;youtube.isSelected=model.section==Section.YOUTUBE
        settings.visibility = if (route == Route.Settings) View.INVISIBLE else View.VISIBLE
        val localFeedback=route is Route.Channels || route==Route.Settings || route==Route.Addons || route==Route.AddProvider || route is Route.EditProvider || route==Route.Browse(Section.YOUTUBE)
        error.text = model.error.orEmpty();errorRow.visibility=if(!localFeedback && model.error!=null)View.VISIBLE else View.GONE
        loading.visibility = if (model.busy && !localFeedback) View.VISIBLE else View.GONE
        if (changed || screen == null) {
            retireScreen(); screenKey = next;screenRoute=route
            screen = when (route) {
                is Route.Browse -> when(route.section) { Section.SEARCH->SearchScreen(this);Section.LIVE->ProfilesScreen(this);Section.YOUTUBE->YouTubeScreen(this) }
                is Route.Channels -> ChannelsScreen(this, route.provider)
                is Route.Detail -> DetailScreen(this, route.title)
                is Route.Sources -> SourcesScreen(this, route)
                Route.AddProvider, is Route.EditProvider -> ProviderForm(this)
                Route.Settings -> SettingsScreen(this)
                Route.Addons -> AddonSettingsScreen(this)
                else -> kotlin.error("Unexpected route")
            }
            host.addView(screen!!.view, FrameLayout.LayoutParams(-1, -1)); screen!!.restoreState(states[next])
            pendingFocus=next
        }
        screen!!.update()
        if(route==Route.Browse(Section.SEARCH)) {
            screen!!.view.findTag("field:Search movies and shows")?.let { input ->
                search.nextFocusDownId=input.id;input.nextFocusUpId=search.id
            }
        } else search.nextFocusDownId=View.NO_ID
        if(route==Route.Browse(Section.YOUTUBE)) {
            screen!!.view.findTag("field:Search YouTube")?.let { input ->youtube.nextFocusDownId=input.id;input.nextFocusUpId=youtube.id }
        } else youtube.nextFocusDownId=View.NO_ID
        if(pendingFocus!=null)root.postOnAnimation { restorePendingFocus() }
    }
    override fun onSaveInstanceState(outState:Bundle) {
        currentFocus?.takeIf(::insideHost)?.let(::rememberFocus)
        screen?.saveState()?.let { states[screenKey]=it }
        outState.putBundle("screen-states",Bundle().apply { states.forEach { (key,value)->putParcelable(key,value) } })
        outState.putBundle("screen-focus",Bundle().apply { focusStates.forEach { (key,value)->putBundle(key,Bundle().apply {
            putString("tag",value.tag);putString("parent",value.parent)
        }) } })
        super.onSaveInstanceState(outState)
    }
    override fun onStart() { super.onStart(); playback?.start();screen?.foreground() }
    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if(hasFocus)restorePendingFocus() }
    override fun onStop() { screen?.background();model.youTubeBackground(); playback?.stop(); super.onStop() }
    override fun onDestroy() {
        playback?.close(); playback = null; screen?.close(); posterPool.clear(); super.onDestroy()
    }
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW) {
            if(artworkDelegate.isInitialized())artwork.loader.memoryCache?.clear()
            posterPool.clear();model.trimMemory()
        }
    }
}
