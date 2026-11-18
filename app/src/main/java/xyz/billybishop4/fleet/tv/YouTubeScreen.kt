package xyz.billybishop4.fleet.tv

import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.Parcelable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.*
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Native TV shelves and search. The overlay rail never resizes the browsing surface. */
internal class YouTubeScreen(private val a:MainActivity):NativeScreen {
    private val m=a.model.youtube
    private var syncing=false;private var closed=false;private var searchCollapsed=false
    private val restore=BrowseFocusRestore(a,a.window.decorView)
    override val view=object:FrameLayout(a) {
        init{tag="youtube-page"}
        override fun dispatchKeyEvent(event:KeyEvent):Boolean {
            if(event.action==KeyEvent.ACTION_DOWN) {
                val focused=findFocus()
                val card=focused?.let(list::findContainingItemView)
                if(card!=null && list.isShown) {
                    val position=list.getChildAdapterPosition(card)
                    when(event.keyCode) {
                        KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT->{
                            if(position>=0) {
                                val column=position%grid.spanCount
                                if(column==0 && event.keyCode==KeyEvent.KEYCODE_DPAD_LEFT)return selectedRail().requestFocus()
                                if(event.keyCode==KeyEvent.KEYCODE_DPAD_RIGHT &&
                                    (column==grid.spanCount-1 || position==adapter.itemCount-1))return true
                                // RecyclerView owns normal movement and repeat delivery. Restarting a
                                // smooth scroll for every key loses presses while new cards attach.
                            }
                        }
                        KeyEvent.KEYCODE_DPAD_UP->if(position in 0 until grid.spanCount) {
                            if(m.feed==YouTubeFeed.SEARCH){expandSearch();return keyboard.requestFocus()}
                            if(m.feed.library)return libraryTarget().requestFocus()
                            return topNavigation().requestFocus()
                        }
                    }
                }
                val shelf=focused?.let { focus->generateSequence(focus){it.parent as? View}.filterIsInstance<YouTubeShelfView>().firstOrNull() }
                if(shelf!=null) {
                    if(event.keyCode==KeyEvent.KEYCODE_DPAD_LEFT && shelf.list.getChildAdapterPosition(focused!!)==0)return home.requestFocus()
                    if(event.keyCode==KeyEvent.KEYCODE_DPAD_UP && shelves.getChildAdapterPosition(shelf)==0)return homeSearch.requestFocus()
                }
            }
            return super.dispatchKeyEvent(event)
        }
    }
    private fun railButton(label:String,description:String,click:()->Unit)=a.action(label,description,click=click).quiet().apply {
        id=View.generateViewId()
        gravity=Gravity.CENTER_VERTICAL or Gravity.START;setPadding(a.dp(13),0,a.dp(8),0)
        setCompoundDrawablesRelativeWithIntrinsicBounds(YouTubeRailIcon(label,a.dp(23)),null,null,null)
        compoundDrawablePadding=a.dp(17);setSingleLine(true)
    }
    private val home=railButton("Home","YouTube home"){restore.cancel();hideKeyboard();m.select(YouTubeFeed.HOME);focusContent()}
    private val shorts=railButton("Shorts","YouTube Shorts"){restore.cancel();hideKeyboard();m.select(YouTubeFeed.SHORTS);focusContent()}
    private val searchNav=railButton("Search","YouTube search"){showSearch();keyboard.requestFocus()}
    private val subscriptions=railButton("Subscriptions","YouTube subscriptions"){browse(YouTubeFeed.SUBSCRIPTIONS)}
    private val library=railButton("Library","YouTube library"){browse(if(m.feed.library)m.feed else YouTubeFeed.HISTORY)}
    private val account=railButton("Sign in","YouTube account"){account()}
    private val rail=a.column().apply {
        tag="youtube-navigation";clipChildren=true;setBackgroundColor(INK)
        add(account,width=a.dp(164),height=a.dp(48));gap(22)
        for(button in listOf(searchNav,home,shorts,subscriptions,library))add(button,width=a.dp(164),height=a.dp(48))
    }
    private val input=EditText(a).apply {
        id=View.generateViewId();tag="field:Search YouTube";contentDescription="Search YouTube";hint="Search YouTube"
        setTextColor(WHITE);setHintTextColor(MUTED);textSize=16f;setSingleLine(true)
        inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        imeOptions=EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI;showSoftInputOnFocus=false
        filters=arrayOf(android.text.InputFilter.LengthFilter(200));setPadding(a.dp(16),a.dp(8),a.dp(16),a.dp(8))
        background=StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused),a.round(PANEL,WHITE,24));addState(intArrayOf(),a.round(PANEL,radius=24))
        }
        setText(m.query);setSelection(text.length)
        val icon=a.getDrawable(R.drawable.ic_search)!!.mutate().apply{setBounds(0,0,a.dp(18),a.dp(18))}
        setCompoundDrawablesRelative(icon,null,null,null);compoundDrawablePadding=a.dp(10)
    }
    private val submit=a.action("Search","Search YouTube videos"){search()}.quiet()
    private val searchHeader=a.row().apply {
        tag="youtube-search-header"
        addView(input,LinearLayout.LayoutParams(0,a.dp(42),1f))
        add(submit,width=a.dp(84),height=a.dp(42))
    }
    private val homeSearch=a.action("Search","Search YouTube from Home"){showSearch();keyboard.requestFocus()}.quiet().apply {
        id=View.generateViewId();tag="youtube-home-search";gravity=Gravity.CENTER_VERTICAL or Gravity.START
        minHeight=0;minimumHeight=0;setPadding(a.dp(13),0,a.dp(13),0)
        background=StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused),a.round(PANEL,WHITE,24));addState(intArrayOf(),a.round(PANEL,radius=24))
        }
        val icon=a.getDrawable(R.drawable.ic_search)!!.mutate().apply{setBounds(0,0,a.dp(16),a.dp(16))}
        setCompoundDrawablesRelative(icon,null,null,null);compoundDrawablePadding=a.dp(10)
    }
    private val homeSearchRow=a.row().apply {
        tag="youtube-home-search-row";gravity=Gravity.CENTER_VERTICAL;add(homeSearch,width=a.dp(232),height=a.dp(36))
    }
    private val heading=a.text("",22f,bold=true).apply { tag="youtube-section-heading";gravity=Gravity.CENTER_VERTICAL }
    private val status=a.text("",13f,MUTED).apply{tag="youtube-status";maxLines=2}
    private val retry=a.action("Retry","Retry YouTube"){m.refresh();focusContent()}.quiet().apply { id=View.generateViewId() }
    private val feedback=a.row().apply { addView(status,LinearLayout.LayoutParams(0,-2,1f));add(retry,width=-2);visibility=View.GONE }
    private val progress=ProgressBar(a,null,android.R.attr.progressBarStyleHorizontal).apply {
        tag="youtube-loading";isIndeterminate=true;visibility=View.INVISIBLE;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val keyboard:YouTubeKeyboard=YouTubeKeyboard(a,::insert,::erase,{input.setText("")},::search,::systemKeyboard) { direction->
        when(direction) { View.FOCUS_UP->input.requestFocus();View.FOCUS_LEFT->recent.getChildAt(1)?.takeIf{it.isFocusable}?.requestFocus()?:searchNav.requestFocus();View.FOCUS_DOWN->firstGridCard()?.requestFocus()?:false;else->false }
    }
    private val recent:LinearLayout=a.column().apply { tag="youtube-recent-searches" }
    private var shownRecent:List<String>?=null
    private val searchTools=a.row().apply {
        gravity=Gravity.TOP;visibility=View.GONE;tag="youtube-search-tools"
        add(recent,width=a.dp(190),height=-1)
        addView(keyboard,LinearLayout.LayoutParams(a.dp(338),a.dp(160)).apply { marginStart=a.dp(24) })
    }
    private val history=a.action("History","YouTube history"){browse(YouTubeFeed.HISTORY)}.quiet().apply { id=View.generateViewId() }
    private val watchLater=a.action("Watch Later","YouTube Watch Later"){browse(YouTubeFeed.WATCH_LATER)}.quiet().apply { id=View.generateViewId() }
    private val liked=a.action("Liked videos","YouTube liked videos"){browse(YouTubeFeed.LIKED)}.quiet().apply { id=View.generateViewId() }
    private val libraryTabs=a.row().apply {
        tag="youtube-library-tabs";visibility=View.GONE
        add(history,width=a.dp(100),height=a.dp(38));add(watchLater,width=a.dp(128),height=a.dp(38));add(liked,width=a.dp(128),height=a.dp(38))
    }
    private val grid=GridLayoutManager(a,if(m.feed==YouTubeFeed.SHORTS)5 else 4).apply { initialPrefetchItemCount=1 }
    private val list=RecyclerView(a).apply { id=View.generateViewId();tag="youtube-results";layoutManager=grid;tune(cachedViews=1) }
    private val homeLayout=LinearLayoutManager(a).apply { initialPrefetchItemCount=1 }
    private val shelves=RecyclerView(a).apply { tag="youtube-home";layoutManager=homeLayout;tune(cachedViews=1) }
    private val pool=RecyclerView.RecycledViewPool().apply { for(type in 10..12)setMaxRecycledViews(type,3) }
    private val shelfStates=linkedMapOf<String,Parcelable>()
    private val shelfAdapter=NativeAdapter<YouTubeShelf>({it.key},{parent,type->YouTubeShelfView(parent.context,a.artwork,pool,type,::play)},
        {v,row,_->(v as YouTubeShelfView).bind(row,shelfStates[row.key])},
        type={row->if(row.portrait)2 else if(row===m.homeShelves.firstOrNull())1 else 0},recycle={v->(v as YouTubeShelfView).let{row->
            row.saveState()?.let{shelfStates[row.shelfKey]=it};while(shelfStates.size>10)shelfStates.remove(shelfStates.keys.first());row.release()
        }})
    private val emptyTitle=a.text("",22f,bold=true)
    private val emptyBody=a.text("",14f,MUTED).apply { maxLines=3;gravity=Gravity.CENTER }
    private val emptySignIn=a.action("Sign in","Sign in to YouTube"){account()}.quiet().apply { id=View.generateViewId();visibility=View.GONE }
    private val emptyRefresh=a.action("Refresh","Refresh YouTube"){m.refresh();focusContent()}.quiet().apply { id=View.generateViewId();tag="youtube-empty-refresh";visibility=View.GONE }
    private val empty=a.column().apply { gravity=Gravity.CENTER;add(emptyTitle,width=-2);gap(10);add(emptyBody,width=-2);gap(12)
        add(emptySignIn,width=a.dp(112),height=a.dp(42));add(emptyRefresh,width=a.dp(112),height=a.dp(42)) }
    private val openVideo:(YouTubeVideo)->Unit={play(it,null)}
    private val adapter=NativeAdapter<YouTubeVideo>({it.id},{parent,type->
        val portrait=type==1
        val contentWidth=a.resources.displayMetrics.widthPixels-a.dp(48+64)
        val width=(contentWidth/(if(portrait)5 else 4)-a.dp(16)).coerceAtLeast(a.dp(96))
        YouTubeCard(parent.context,a.artwork,portrait,width)
    },{v,value,_->(v as YouTubeCard).bind(value,openVideo)},type={if(m.feed==YouTubeFeed.SHORTS)1 else 0},recycle={(it as YouTubeCard).release()})
    private var lastFeed:YouTubeFeed?=null;private var lastQuery=""
    private var submitted:List<YouTubeVideo>?=null;private var submittedShelves:List<YouTubeShelf>?=null
    private val scrollStates=linkedMapOf<YouTubeFeed,Parcelable>()
    private var pendingScroll:Parcelable?=null;private var pendingHomeScroll:Parcelable?=null
    private var signInDialog:AlertDialog?=null
    private var codeText:TextView?=null;private var codeMessage:TextView?=null;private var qr:ImageView?=null
    private var shownCode:YouTubeDeviceCode?=null;private var qrJob:Job?=null
    private data class ContentAnchor(val tag:String,val parent:String?,val feed:YouTubeFeed,val query:String)
    private var contentAnchor:ContentAnchor?=null
    private fun within(child:View?,parent:View):Boolean {
        var node=child
        while(node!=null){if(node===parent)return true;node=node.parent as? View}
        return false
    }
    private fun contentCard(child:View?):View? {
        var node=child
        while(node!=null && node!==view){if(node is YouTubeCard)return node;node=node.parent as? View}
        return null
    }
    private fun anchor()=contentAnchor?.takeIf{it.feed==m.feed && (m.feed!=YouTubeFeed.SEARCH || it.query==m.query)}
    private fun anchoredCard():View?=anchor()?.let { saved->
        if(m.feed==YouTubeFeed.HOME)(saved.parent?.let(shelves::findTag) as? YouTubeShelfView)
            ?.list?.findTag(saved.tag)?.takeIf{it.isShown}
        else list.findTag(saved.tag)?.takeIf{it.isShown}
    }
    private fun returnFromRail(button:View) {
        if(keyboard.isShown){keyboard.requestFocus();return}
        anchoredCard()?.let{it.requestFocus();return}
        anchor()?.let{if(restoreFocus(it.tag,it.parent))return}
        (firstCard()?:emptySignIn.takeIf{it.isShown}?:emptyRefresh.takeIf{it.isShown}?:
            if(m.feed.library)libraryTarget() else if(m.feed==YouTubeFeed.SEARCH)input else button).requestFocus()
    }
    private val railFocus=ViewTreeObserver.OnGlobalFocusChangeListener { previous,focused->
        if(!closed) {
            contentCard(previous)?.takeIf{contentCard(focused)==null}?.let { card->
                val parent=card.parent as? View
                val shelf=parent?.parent as? YouTubeShelfView
                contentAnchor=ContentAnchor(card.tag.toString(),shelf?.tag?.toString(),m.feed,m.query)
            }
            val expanded=within(focused,rail)
            val width=a.dp(if(expanded)164 else 52)
            if(rail.layoutParams.width!=width)rail.layoutParams=rail.layoutParams.apply { this.width=width }
            if(m.feed==YouTubeFeed.SEARCH) {
                if(within(focused,list)){searchCollapsed=true;searchTools.visibility=View.GONE}
                else if(focused===input || within(focused,keyboard) || within(focused,recent))expandSearch()
            }
        }
    }
    init {
        val body=a.column().apply {
            add(homeSearchRow,height=a.dp(44));add(searchHeader,height=a.dp(48));add(heading,height=a.dp(42))
            add(progress,height=a.dp(2));add(feedback);gap(8)
            add(searchTools,height=a.dp(168))
            add(libraryTabs,height=a.dp(44))
            addView(FrameLayout(a).apply {
                addView(shelves,FrameLayout.LayoutParams(-1,-1));addView(list,FrameLayout.LayoutParams(-1,-1));addView(empty,FrameLayout.LayoutParams(-1,-1))
            },LinearLayout.LayoutParams(-1,0,1f))
        }
        view.addView(body,FrameLayout.LayoutParams(-1,-1).apply{marginStart=a.dp(64)})
        view.addView(rail,FrameLayout.LayoutParams(a.dp(52),-1,Gravity.START))
        list.adapter=adapter;shelves.adapter=shelfAdapter
        homeSearch.setOnKeyListener { _,key,event->
            if(event.action!=KeyEvent.ACTION_DOWN)false else when(key) {
                KeyEvent.KEYCODE_DPAD_UP->topNavigation().requestFocus()
                KeyEvent.KEYCODE_DPAD_DOWN->{returnFromRail(homeSearch);true}
                else->false
            }
        }
        input.setOnClickListener{showSearch();keyboard.requestFocus()}
        input.setOnKeyListener { _,key,event->when(key) {
            KeyEvent.KEYCODE_DPAD_CENTER->{if(event.action==KeyEvent.ACTION_UP){showSearch();keyboard.requestFocus()};true}
            KeyEvent.KEYCODE_ENTER->{if(event.action==KeyEvent.ACTION_UP)search();true}
            KeyEvent.KEYCODE_DPAD_UP->{if(event.action==KeyEvent.ACTION_DOWN){hideKeyboard();a.window.decorView.findTag("navigation:YouTube")?.requestFocus()};true}
            KeyEvent.KEYCODE_DPAD_DOWN->{if(event.action==KeyEvent.ACTION_DOWN){if(keyboard.isShown)keyboard.requestFocus() else if(m.feed.library)libraryTarget().requestFocus() else firstCard()?.requestFocus()};true}
            else->false
        } }
        input.setOnEditorActionListener { _,action,event->if(action==EditorInfo.IME_ACTION_SEARCH || action==EditorInfo.IME_ACTION_DONE || event?.keyCode==KeyEvent.KEYCODE_ENTER) {
            if(event==null || event.action==KeyEvent.ACTION_UP)search();true
        }else false }
        input.addTextChangedListener(object:TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){if(!syncing){restore.cancel();m.editQuery(s?.toString().orEmpty())}}
            override fun afterTextChanged(s:Editable?){}
        })
        list.addOnScrollListener(object:RecyclerView.OnScrollListener(){override fun onScrolled(recyclerView:RecyclerView,dx:Int,dy:Int) {
            if(grid.findLastVisibleItemPosition()>=adapter.itemCount-3 && adapter.itemCount>0)m.more()
        }})
        shelves.addOnScrollListener(object:RecyclerView.OnScrollListener(){override fun onScrolled(recyclerView:RecyclerView,dx:Int,dy:Int) {
            if(homeLayout.findLastVisibleItemPosition()>=shelfAdapter.itemCount-2 && shelfAdapter.itemCount>0)m.more()
        }})
        for(button in listOf(home,shorts,searchNav,account,subscriptions,library))button.setOnKeyListener { _,key,event->
            if(event.action==KeyEvent.ACTION_DOWN && key==KeyEvent.KEYCODE_DPAD_RIGHT) {
                returnFromRail(button);true }
            else if(button===account && event.action==KeyEvent.ACTION_DOWN && key==KeyEvent.KEYCODE_DPAD_UP) { a.window.decorView.findTag("navigation:YouTube")?.requestFocus();true }else false
        }
        for(button in listOf(history,watchLater,liked))button.setOnKeyListener { _,key,event->
            if(event.action==KeyEvent.ACTION_DOWN && key==KeyEvent.KEYCODE_DPAD_DOWN){(firstGridCard()?:emptySignIn.takeIf{it.isShown})?.requestFocus()?:true}
            else if(event.action==KeyEvent.ACTION_DOWN && key==KeyEvent.KEYCODE_DPAD_UP)topNavigation().requestFocus() else false
        }
        view.viewTreeObserver.addOnGlobalFocusChangeListener(railFocus)
        m.activate()
    }
    private fun play(video:YouTubeVideo,group:List<YouTubeVideo>?) { hideKeyboard();restore.cancel();m.play(video,group) }
    private fun browse(feed:YouTubeFeed){restore.cancel();hideKeyboard();m.select(feed);focusContent()}
    private fun libraryTarget()=when(m.feed){YouTubeFeed.WATCH_LATER->watchLater;YouTubeFeed.LIKED->liked;else->history}
    private fun topNavigation():View=a.window.decorView.findTag("navigation:YouTube")?:selectedRail()
    private fun selectedRail()=when(m.feed){YouTubeFeed.HOME->home;YouTubeFeed.SHORTS->shorts;YouTubeFeed.SEARCH->searchNav;YouTubeFeed.SUBSCRIPTIONS->subscriptions;else->library}
    private fun expandSearch(){searchCollapsed=false;searchTools.visibility=View.VISIBLE}
    private fun showSearch(){restore.cancel();hideKeyboard();m.showSearch();expandSearch()}
    private fun insert(value:String) {
        val start=input.selectionStart.coerceAtLeast(0);val end=input.selectionEnd.coerceAtLeast(start)
        input.text.replace(start,end,value)
    }
    private fun erase() {
        val start=input.selectionStart.coerceAtLeast(0);val end=input.selectionEnd.coerceAtLeast(start)
        if(start!=end)input.text.delete(start,end) else if(start>0)input.text.delete(Character.offsetByCodePoints(input.text,start,-1),start)
    }
    private fun systemKeyboard() {
        input.requestFocus();input.post { if(input.isAttachedToWindow && input.hasFocus())
            (a.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(input,InputMethodManager.SHOW_IMPLICIT) }
    }
    private fun hideKeyboard(){(a.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(input.windowToken,0)}
    private fun search() {
        if(input.text.toString().trim().length<2){showSearch();keyboard.requestFocus();return}
        m.submitSearch();hideKeyboard();focusContent()
    }
    private fun focusContent() { restore.start(waitForWindowFocus=true) {
        if(m.busy || m.resolving || adapter.currentList!=m.videos && m.feed!=YouTubeFeed.HOME)null
        // A diff can commit before RecyclerView attaches its first card on the next frame.
        else if(m.videos.isEmpty())when {m.error!=null->retry;m.feed.requiresAccount && m.account==null->emptySignIn;
            m.feed.library->libraryTarget();emptyRefresh.isShown->emptyRefresh;m.feed==YouTubeFeed.SEARCH->input;else->selectedRail()} else firstCard()
    } }
    private fun firstGridCard():View?=list.getChildAt(0)?.takeIf { it.isShown && it.isFocusable }
    private fun firstCard():View?=if(m.feed==YouTubeFeed.HOME)(shelves.getChildAt(0) as? YouTubeShelfView)?.list?.getChildAt(0)?.takeIf{it.isShown} else firstGridCard()
    override fun focusTarget():View?=when {
        m.feed==YouTubeFeed.SEARCH && !searchCollapsed->input
        m.busy && m.videos.isEmpty()->selectedRail()
        else->anchoredCard()?:firstCard()?:retry.takeIf{it.isShown}?:emptySignIn.takeIf{it.isShown}?:emptyRefresh.takeIf{it.isShown}?:
            if(m.feed==YouTubeFeed.SEARCH)input else if(m.feed.library)libraryTarget() else selectedRail()
    }
    override fun restoreFocus(tag:String,parentTag:String?):Boolean {
        if(!tag.startsWith("youtube-video:"))return false
        if(m.feed==YouTubeFeed.HOME) {
            val index=m.homeShelves.indexOfFirst { row->("youtube-shelf:${row.key}"==parentTag || parentTag==null) && row.videos.any{"youtube-video:${it.id}"==tag} }
            if(index<0)return false
            shelves.scrollToPosition(index);restore.start { (homeLayout.findViewByPosition(index) as? YouTubeShelfView)?.focusVideo(tag) };return true
        }
        val index=adapter.currentList.indexOfFirst { "youtube-video:${it.id}"==tag };if(index<0)return false
        list.findTag(tag)?.takeIf{it.isShown}?.let{it.requestFocus();return true}
        list.scrollToPosition(index);restore.start { list.findTag(tag) };return true
    }
    override fun back():Boolean {
        if(m.resolving){m.cancelPlayback();return true}
        if(m.feed==YouTubeFeed.SEARCH && !searchCollapsed && m.videos.isNotEmpty()) {
            restore.cancel();hideKeyboard();searchCollapsed=true;searchTools.visibility=View.GONE
            focusContent();return true
        }
        if(m.feed!=YouTubeFeed.HOME){restore.cancel();hideKeyboard();m.select(YouTubeFeed.HOME);focusContent();return true}
        return false
    }
    override fun update() {
        if(closed)return
        home.isSelected=m.feed==YouTubeFeed.HOME;shorts.isSelected=m.feed==YouTubeFeed.SHORTS;searchNav.isSelected=m.feed==YouTubeFeed.SEARCH
        subscriptions.isSelected=m.feed==YouTubeFeed.SUBSCRIPTIONS;library.isSelected=m.feed.library
        history.isSelected=m.feed==YouTubeFeed.HISTORY;watchLater.isSelected=m.feed==YouTubeFeed.WATCH_LATER;liked.isSelected=m.feed==YouTubeFeed.LIKED
        libraryTabs.visibility=if(m.feed.library)View.VISIBLE else View.GONE
        account.textIfChanged(if(m.account==null)"Sign in" else "Account")
        if(input.text.toString()!=m.query) { syncing=true;input.setText(m.query);input.setSelection(input.text.length);syncing=false }
        if(lastFeed!=m.feed || lastQuery!=m.query && m.feed==YouTubeFeed.SEARCH) {
            if(lastFeed==YouTubeFeed.HOME)homeLayout.onSaveInstanceState()?.let { scrollStates[YouTubeFeed.HOME]=it }
            else lastFeed?.let { f->grid.onSaveInstanceState()?.let { scrollStates[f]=it } }
            grid.spanCount=if(m.feed==YouTubeFeed.SHORTS)5 else 4
            list.stopScroll();shelves.stopScroll()
            if(m.feed==YouTubeFeed.HOME)pendingHomeScroll=pendingHomeScroll?:scrollStates[YouTubeFeed.HOME]
            else pendingScroll=pendingScroll?:if(m.feed==YouTubeFeed.SEARCH && lastQuery!=m.query)null else scrollStates[m.feed]
            lastFeed=m.feed;lastQuery=m.query
        }
        searchTools.visibility=if(m.feed==YouTubeFeed.SEARCH && !searchCollapsed)View.VISIBLE else View.GONE
        searchHeader.visibility=if(m.feed==YouTubeFeed.SEARCH)View.VISIBLE else View.GONE
        homeSearchRow.visibility=if(m.feed==YouTubeFeed.HOME)View.VISIBLE else View.GONE
        heading.visibility=if(m.feed==YouTubeFeed.HOME || m.feed==YouTubeFeed.SEARCH)View.GONE else View.VISIBLE
        heading.textIfChanged(when(m.feed){YouTubeFeed.SHORTS->"Shorts";YouTubeFeed.SUBSCRIPTIONS->"Subscriptions";else->"Library"})
        shelves.visibility=if(m.feed==YouTubeFeed.HOME)View.VISIBLE else View.GONE;list.visibility=if(m.feed==YouTubeFeed.HOME)View.GONE else View.VISIBLE
        if(submitted!==m.videos && m.feed!=YouTubeFeed.HOME) {
            submitted=m.videos;val state=pendingScroll;pendingScroll=null
            adapter.submitList(m.videos) { if(state!=null)grid.onRestoreInstanceState(state) }
        }else if(m.feed==YouTubeFeed.HOME && adapter.itemCount>0) { submitted=null;adapter.submitList(emptyList());list.recycledViewPool.clear() }
        if(submittedShelves!==m.homeShelves) {
            submittedShelves=m.homeShelves;val state=pendingHomeScroll;pendingHomeScroll=null
            shelfAdapter.submitList(m.homeShelves) { if(state!=null)homeLayout.onRestoreInstanceState(state) }
        }
        if(shownRecent!=m.recentQueries) {
            shownRecent=m.recentQueries;recent.removeAllViews()
            recent.add(a.text("Recent searches",12f,MUTED),height=a.dp(22))
            if(m.recentQueries.isEmpty())recent.add(a.text("Find something to watch",12f,MUTED).apply{maxLines=2})
            else for(query in m.recentQueries.take(4))recent.add(a.action(query,"Search YouTube for $query"){input.setText(query);search()}.quiet().apply{
                gravity=Gravity.START;setPadding(a.dp(8),0,a.dp(8),0);setSingleLine(true);textSize=13f
            },height=a.dp(32))
        }
        val text=m.error.orEmpty();feedback.visibility=if(text.isNotBlank())View.VISIBLE else View.GONE;status.textIfChanged(text)
        retry.visibility=if(m.error!=null)View.VISIBLE else View.GONE
        progress.visibility=if(m.busy || m.resolving)View.VISIBLE else View.INVISIBLE
        progress.contentDescription=if(m.resolving)"Opening YouTube video" else "Loading YouTube"
        empty.visibility=if(m.videos.isEmpty() && !m.busy && m.error==null)View.VISIBLE else View.GONE
        val blankSearch=m.feed==YouTubeFeed.SEARCH && m.query.trim().length<2
        val needsAccount=m.feed.requiresAccount && m.account==null
        emptySignIn.visibility=if(needsAccount)View.VISIBLE else View.GONE
        emptyRefresh.visibility=if(m.feed in setOf(YouTubeFeed.HOME,YouTubeFeed.SHORTS) && m.videos.isEmpty() && !m.busy && m.error==null)View.VISIBLE else View.GONE
        emptyTitle.textIfChanged(when(m.feed){YouTubeFeed.HOME->"No recommendations right now";YouTubeFeed.SHORTS->"No Shorts right now";YouTubeFeed.SEARCH->if(blankSearch)"Search YouTube" else "No videos found";YouTubeFeed.SUBSCRIPTIONS->if(needsAccount)"Your subscriptions" else "No new videos";YouTubeFeed.HISTORY->"Nothing watched yet";YouTubeFeed.WATCH_LATER->"Watch Later";YouTubeFeed.LIKED->"Liked videos"})
        emptyBody.textIfChanged(when(m.feed){YouTubeFeed.HOME->m.message.ifBlank{"Try refresh or search for a video. Sign-in is optional."};YouTubeFeed.SHORTS->"Try again in a moment, or search for a video.";YouTubeFeed.SEARCH->if(blankSearch)"Use the keyboard above to find something to watch." else "Try a different search.";else->if(needsAccount)"Sign in to see this part of your YouTube account." else if(m.feed==YouTubeFeed.HISTORY && m.account==null)"Videos you watch on this TV appear here." else "This part of your library is empty."})
        updateSignIn()
    }
    private fun account() {
        if(m.account!=null) {
            a.showActions("YouTube account",listOf("Refresh recommendations" to {if(m.feed==YouTubeFeed.HOME)m.refresh() else m.select(YouTubeFeed.HOME)},"Sign out" to {
                a.confirm("Sign out of YouTube on this TV?","Sign out"){m.signOut()}
            }));return
        }
        if(signInDialog!=null)return
        hideKeyboard()
        val image=ImageView(a).apply { contentDescription="Scan to sign in to YouTube";scaleType=ImageView.ScaleType.FIT_CENTER }
        val code=a.text("",28f,bold=true).apply { tag="youtube-device-code";isSaveEnabled=false;letterSpacing=.08f }
        val message=a.text("Getting a sign-in code…",14f,MUTED).apply { tag="youtube-signin-status";maxLines=6 }
        val body=a.row().apply {
            gravity=Gravity.CENTER_VERTICAL;pad(18)
            add(image,width=a.dp(180),height=a.dp(180))
            addView(a.column().apply { add(code);gap(14);add(message) },LinearLayout.LayoutParams(0,-2,1f).apply { marginStart=a.dp(20) })
        }
        qr=image;codeText=code;codeMessage=message
        signInDialog=AlertDialog.Builder(a).setTitle("Sign in with Google").setView(body)
            .setNegativeButton("Cancel",null).setPositiveButton("New code",null).create().apply {
                setOnShowListener {
                    window?.apply { setBackgroundDrawable(a.round(PANEL));setLayout(a.dp(540).coerceAtMost(a.resources.displayMetrics.widthPixels-a.dp(48)),-2) }
                    getButton(AlertDialog.BUTTON_NEGATIVE).quiet().apply { isAllCaps=false;requestFocusFromTouch() }
                    getButton(AlertDialog.BUTTON_POSITIVE).quiet().apply { isAllCaps=false;setOnClickListener { m.startSignIn() } }
                    m.startSignIn()
                }
                setOnDismissListener {
                    qrJob?.cancel();qrJob=null;image.setImageDrawable(null);qr=null;shownCode=null;codeText=null;codeMessage=null;signInDialog=null
                    m.cancelSignIn()
                    if(m.account!=null)focusContent() else {
                        val generation=a.focusInputGeneration
                        account.postOnAnimation { if(!closed && account.isShown && generation==a.focusInputGeneration)account.requestFocus() }
                    }
                }
                show()
            }
    }
    private fun updateSignIn() {
        val dialog=signInDialog?:return
        if(m.account!=null){dialog.dismiss();return}
        val code=m.signInCode
        codeText?.text=code?.userCode.orEmpty();codeText?.textSize=if(code!=null && code.userCode.length>12)16f else 28f
        codeMessage?.text=m.signInError?:if(code==null)"Getting a sign-in code…" else
            "Scan the QR code, or open ${code.verificationUrl.removePrefix("https://")} on your phone and enter this code.\n\nWaiting for Google…"
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.visibility=if(m.signInError!=null)View.VISIBLE else View.GONE
        if(code!=shownCode) {
            shownCode=code;qrJob?.cancel();qr?.setImageDrawable(null)
            if(code!=null)qrJob=a.lifecycleScope.launch {
                val bitmap=withContext(Dispatchers.Default) {
                    val matrix=QRCodeWriter().encode(code.verificationUrl,BarcodeFormat.QR_CODE,220,220)
                    val pixels=IntArray(matrix.width*matrix.height) { i->if(matrix[i%matrix.width,i/matrix.width])Color.BLACK else Color.WHITE }
                    Bitmap.createBitmap(pixels,matrix.width,matrix.height,Bitmap.Config.RGB_565)
                }
                if(!closed && shownCode==code)qr?.setImageBitmap(bitmap)
            }
        }
    }
    override fun saveState():Parcelable=Bundle().apply {
        putBoolean("searchCollapsed",searchCollapsed)
        (if(m.feed==YouTubeFeed.HOME)homeLayout else grid).onSaveInstanceState()?.let { putParcelable("scroll",it) }
        for(i in 0 until shelves.childCount)(shelves.getChildAt(i) as? YouTubeShelfView)?.let { row->row.saveState()?.let{shelfStates[row.shelfKey]=it} }
        putBundle("rows",Bundle().apply { for((key,state) in shelfStates.entries.toList().takeLast(10))putParcelable(key,state) })
    }
    @Suppress("DEPRECATION") override fun restoreState(state:Parcelable?) {
        val bundle=state as? Bundle?:return
        searchCollapsed=bundle.getBoolean("searchCollapsed")
        bundle.getParcelable<Parcelable>("scroll")?.let { if(m.feed==YouTubeFeed.HOME)pendingHomeScroll=it else pendingScroll=it }
        bundle.getBundle("rows")?.let { rows->for(key in rows.keySet().take(10))rows.getParcelable<Parcelable>(key)?.let { shelfStates[key]=it } }
        // The outer list can be attached before Activity restores its saved screen state.
        submitted=null;submittedShelves=null
    }
    override fun foreground(){m.activate()}
    override fun background(){restore.cancel();hideKeyboard();m.deactivate()}
    override fun close(){
        closed=true;restore.cancel();qrJob?.cancel();signInDialog?.dismiss();signInDialog=null;m.deactivate()
        view.viewTreeObserver.removeOnGlobalFocusChangeListener(railFocus)
        for(i in 0 until list.childCount)(list.getChildAt(i) as? YouTubeCard)?.release()
        for(i in 0 until shelves.childCount)(shelves.getChildAt(i) as? YouTubeShelfView)?.release()
        list.adapter=null;shelves.adapter=null;list.recycledViewPool.clear();shelves.recycledViewPool.clear();pool.clear();shelfStates.clear()
    }
}
