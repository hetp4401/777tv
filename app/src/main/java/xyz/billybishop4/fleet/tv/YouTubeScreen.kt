package xyz.billybishop4.fleet.tv

import android.content.Context
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
import coil.dispose
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Native TV shelves and search. The overlay rail never resizes the browsing surface. */
internal class YouTubeScreen(private val a:MainActivity):NativeScreen {
    private val m=a.model.youtube
    private var syncing=false;private var closed=false;private var searchCollapsed=false
    private var pendingShelfIndex:Int?=null;private var pendingShelfScope="";private var pendingShelfTag=""
    private val restore=BrowseFocusRestore(a,a.window.decorView)
    override val view=object:FrameLayout(a) {
        init{tag="youtube-page"}
        override fun dispatchKeyEvent(event:KeyEvent):Boolean {
            if(event.action==KeyEvent.ACTION_DOWN) {
                val vertical=event.keyCode==KeyEvent.KEYCODE_DPAD_UP || event.keyCode==KeyEvent.KEYCODE_DPAD_DOWN
                if(!vertical){pendingShelfIndex=null;pendingShelfScope=""}
                val pending=pendingShelfIndex?.takeIf{shelves.isShown && pendingShelfScope==shelfScope()}
                if(pending!=null && vertical) {
                    if(event.keyCode==KeyEvent.KEYCODE_DPAD_UP && pending==0) {
                        pendingShelfIndex=null;restore.cancel()
                        if(m.feed==YouTubeFeed.SEARCH){expandSearch();return keyboard.requestFocus()}
                        return homeSearch.requestFocus()
                    }
                    return focusShelfRow((pending+if(event.keyCode==KeyEvent.KEYCODE_DPAD_DOWN)1 else -1).coerceIn(0,(shelfAdapter.itemCount-1).coerceAtLeast(0)))
                }
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
                                    (column==grid.spanCount-1 || position==gridCount()-1))return true
                                // RecyclerView owns normal movement and repeat delivery. Restarting a
                                // smooth scroll for every key loses presses while new cards attach.
                            }
                        }
                        KeyEvent.KEYCODE_DPAD_UP->if(position in 0 until grid.spanCount) {
                            if(m.feed==YouTubeFeed.SEARCH){expandSearch();return keyboard.requestFocus()}
                            if(m.feed.library)return libraryTarget().requestFocus()
                            if(m.feed==YouTubeFeed.CHANNEL)return channelTarget().requestFocus()
                            return topNavigation().requestFocus()
                        }
                    }
                }
                val shelf=focused?.let { focus->generateSequence(focus){it.parent as? View}.filterIsInstance<YouTubeShelfView>().firstOrNull() }
                if(shelf!=null && shelves.isShown) {
                    if(event.keyCode==KeyEvent.KEYCODE_DPAD_LEFT && shelf.list.getChildAdapterPosition(focused!!)==0)return selectedRail().requestFocus()
                    val row=shelves.getChildAdapterPosition(shelf)
                    if(event.keyCode==KeyEvent.KEYCODE_DPAD_UP && row==0) {
                        if(m.feed==YouTubeFeed.SEARCH){expandSearch();return keyboard.requestFocus()}
                        return homeSearch.requestFocus()
                    }
                    if(event.keyCode==KeyEvent.KEYCODE_DPAD_UP && row>0)return focusShelfRow(row-1)
                    if(event.keyCode==KeyEvent.KEYCODE_DPAD_DOWN && row>=0 && row<shelfAdapter.itemCount-1)return focusShelfRow(row+1)
                    if(event.keyCode==KeyEvent.KEYCODE_DPAD_DOWN && row==shelfAdapter.itemCount-1)return true
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
            addState(intArrayOf(android.R.attr.state_focused),a.round(PANEL,FOCUS,24));addState(intArrayOf(),a.round(PANEL,radius=24))
        }
        setText(m.query);setSelection(text.length)
        val icon=a.getDrawable(R.drawable.ic_search)!!.mutate().apply{setBounds(0,0,a.dp(18),a.dp(18))}
        setCompoundDrawablesRelative(icon,null,null,null);compoundDrawablePadding=a.dp(10)
    }
    private val submit=a.action("Search","Search YouTube videos"){search()}.quiet()
    private val voice=a.action("Voice","Voice search YouTube"){hideKeyboard();a.voiceSearchYouTube()}.quiet().apply { tag="youtube-voice-search" }
    private val searchHeader=a.row().apply {
        tag="youtube-search-header"
        addView(input,LinearLayout.LayoutParams(0,a.dp(42),1f))
        add(voice,width=a.dp(76),height=a.dp(42))
        add(submit,width=a.dp(84),height=a.dp(42))
    }
    private val homeSearch=a.action("Search","Search YouTube from Home"){showSearch();keyboard.requestFocus()}.quiet().apply {
        id=View.generateViewId();tag="youtube-home-search";gravity=Gravity.CENTER_VERTICAL or Gravity.START
        minHeight=0;minimumHeight=0;setPadding(a.dp(13),0,a.dp(13),0)
        background=StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused),a.round(PANEL,FOCUS,24));addState(intArrayOf(),a.round(PANEL,radius=24))
        }
        val icon=a.getDrawable(R.drawable.ic_search)!!.mutate().apply{setBounds(0,0,a.dp(16),a.dp(16))}
        setCompoundDrawablesRelative(icon,null,null,null);compoundDrawablePadding=a.dp(10)
    }
    private val homeSearchRow=a.row().apply {
        tag="youtube-home-search-row";gravity=Gravity.CENTER_VERTICAL;add(homeSearch,width=a.dp(232),height=a.dp(36))
        add(a.action("Voice","Voice search YouTube from Home"){hideKeyboard();a.voiceSearchYouTube()}.quiet().apply{tag="youtube-home-voice"},width=a.dp(76),height=a.dp(36))
    }
    private var channelJob:Job?=null
    private var channelRequest=0
    private var channelPendingId=""
    private var channelBusy=false;private var channelError:String?=null;private var channelNeedsSignIn=false
    private var channelOverride:YouTubeChannel?=null;private var shownChannel="";private var shownChannelImage=""
    private var shownTabs:List<YouTubeChannelTab>?=null
    private val channelImage=FixedArtworkView(a).apply { scaleType=ImageView.ScaleType.CENTER_CROP;clipToOutline=true;background=a.round(PANEL,radius=24) }
    private val channelName=a.text("",22f,bold=true).apply { tag="youtube-channel-name";maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END }
    private val channelInfo=a.text("",12f,MUTED).apply { maxLines=1 }
    private val channelDescription=a.text("",12f,MUTED).apply { maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END }
    private val channelSubscribe=a.action("Subscribe","Subscribe to channel"){subscribeChannel()}.quiet().apply { tag="youtube-channel-subscribe";id=View.generateViewId() }
    private val channelTabs=a.row().apply { tag="youtube-channel-tabs" }
    private val channelHeader=a.column().apply {
        tag="youtube-channel-header";visibility=View.GONE
        add(a.row().apply {
            add(channelImage,width=a.dp(48),height=a.dp(48))
            addView(a.column().apply{add(channelName);gap(5);add(channelInfo)},LinearLayout.LayoutParams(0,-2,1f).apply { marginStart=a.dp(14) })
            add(channelSubscribe,width=a.dp(140),height=a.dp(40))
        },height=a.dp(62))
        add(channelDescription);gap(6)
        add(HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled=false;addView(channelTabs) },height=a.dp(42))
    }
    private val heading=a.text("",22f,bold=true).apply { tag="youtube-section-heading";gravity=Gravity.CENTER_VERTICAL }
    private val status=a.text("",13f,MUTED).apply{tag="youtube-status";maxLines=2}
    private val retry=a.action("Retry","Retry YouTube"){m.refresh();focusContent()}.quiet().apply { id=View.generateViewId() }
    private val feedback=a.row().apply { addView(status,LinearLayout.LayoutParams(0,-2,1f));add(retry,width=-2);visibility=View.GONE }
    private val progress=ProgressBar(a,null,android.R.attr.progressBarStyleHorizontal).themeTint().apply {
        tag="youtube-loading";isIndeterminate=true;visibility=View.INVISIBLE;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val keyboard:YouTubeKeyboard=YouTubeKeyboard(a,::insert,::erase,{input.setText("")},::search,::systemKeyboard) { direction->
        when(direction) { View.FOCUS_UP->input.requestFocus();View.FOCUS_LEFT->recent.getChildAt(1)?.takeIf{it.isFocusable}?.requestFocus()?:searchNav.requestFocus();View.FOCUS_DOWN->firstCard()?.requestFocus()?:false;else->false }
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
    private val playlists=a.action("Playlists","YouTube playlists"){browse(YouTubeFeed.PLAYLISTS)}.quiet().apply { id=View.generateViewId() }
    private val libraryTabs=a.row().apply {
        tag="youtube-library-tabs";visibility=View.GONE
        add(history,width=a.dp(100),height=a.dp(38));add(watchLater,width=a.dp(128),height=a.dp(38));add(liked,width=a.dp(128),height=a.dp(38));add(playlists,width=a.dp(112),height=a.dp(38))
    }
    private val grid=GridLayoutManager(a,if(m.feed==YouTubeFeed.SHORTS)5 else 4).apply { initialPrefetchItemCount=1 }
    private val list=RecyclerView(a).apply { id=View.generateViewId();tag="youtube-results";layoutManager=grid;tune(cachedViews=1) }
    private val homeLayout=LinearLayoutManager(a).apply { initialPrefetchItemCount=1 }
    private val shelves=RecyclerView(a).apply { tag="youtube-home";layoutManager=homeLayout;tune(cachedViews=1) }
    private val pool=RecyclerView.RecycledViewPool().apply { for(type in 10..12)setMaxRecycledViews(type,3) }
    private val shelfStates=linkedMapOf<String,Parcelable>()
    private val shelfFocus=linkedMapOf<String,String>()
    private data class ShelfRow(val data:YouTubeShelf,val identity:String,val kind:Int)
    private val shelfAdapter=NativeAdapter<ShelfRow>({it.identity},{parent,type->YouTubeShelfView(parent.context,a.artwork,pool,type,::play,::shelfEnd)},
        {v,row,_->(v as YouTubeShelfView).bind(row.data,shelfStates[row.identity],row.identity)},
        type={it.kind},recycle={v->(v as YouTubeShelfView).let{row->rememberShelf(row);row.release()}})
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
    },{v,value,_->(v as YouTubeCard).bind(value,openVideo)},type={if(portraitFeed())1 else 0},recycle={(it as YouTubeCard).release()})
    private val playlistAdapter=NativeAdapter<YouTubePlaylist>({it.id},{parent,_->
        val width=((a.resources.displayMetrics.widthPixels-a.dp(48+64))/4-a.dp(16)).coerceAtLeast(a.dp(96))
        YouTubePlaylistCard(parent.context,a.artwork,width)
    },{v,value,_->(v as YouTubePlaylistCard).bind(value,::openPlaylist)},type={3},recycle={(it as YouTubePlaylistCard).release()})
    private var submittedPlaylists:List<YouTubePlaylist>?=null
    private var lastFeed:YouTubeFeed?=null;private var lastQuery=""
    private var submitted:List<YouTubeVideo>?=null;private var submittedShelves:List<YouTubeShelf>?=null;private var submittedShelfScope=""
    private var presentedShelves:List<ShelfRow> = emptyList()
    private val scrollStates=linkedMapOf<YouTubeFeed,Parcelable>()
    private var pendingScroll:Parcelable?=null;private var pendingHomeScroll:Parcelable?=null
    private var signIn:YouTubeSignIn?=null
    private data class ContentAnchor(val tag:String,val parent:String?,val feed:YouTubeFeed,val query:String)
    private var contentAnchor:ContentAnchor?=null
    private var channelReturnAnchor:ContentAnchor?=null
    private var playlistReturnAnchor:ContentAnchor?=null
    private fun ContentAnchor.saved()=Bundle().apply{putString("tag",tag);putString("parent",parent);putString("feed",feed.name);putString("query",query)}
    private fun readAnchor(bundle:Bundle?):ContentAnchor? {
        bundle?:return null
        val tag=bundle.getString("tag")?:return null
        val feed=runCatching{YouTubeFeed.valueOf(bundle.getString("feed").orEmpty())}.getOrNull()?:return null
        return ContentAnchor(tag,bundle.getString("parent"),feed,bundle.getString("query").orEmpty())
    }
    private fun focusedAnchor():ContentAnchor?=contentCard(a.currentFocus)?.let { card->
        val shelf=(card.parent as? View)?.parent as? YouTubeShelfView
        ContentAnchor(card.tag.toString(),shelf?.tag?.toString(),m.feed,m.query)
    }?:anchor()
    private fun rememberShelfFocus(focused:View?) {
        val card=contentCard(focused)?:return
        val row=(card.parent as? View)?.parent as? YouTubeShelfView?:return
        if(pendingShelfScope==shelfScope() && shelfSource().getOrNull(pendingShelfIndex?:-1)?.key==row.shelfKey && card.tag!=pendingShelfTag)return
        if(row.stateKey.isNotBlank())shelfFocus[row.stateKey]=card.tag.toString()
    }
    private fun within(child:View?,parent:View):Boolean {
        var node=child
        while(node!=null){if(node===parent)return true;node=node.parent as? View}
        return false
    }
    private fun contentCard(child:View?):View? {
        var node=child
        while(node!=null && node!==view){if(node is YouTubeCard || node is YouTubePlaylistCard)return node;node=node.parent as? View}
        return null
    }
    private fun anchor()=contentAnchor?.takeIf{it.feed==m.feed && (m.feed!=YouTubeFeed.SEARCH || it.query==m.query)}
    private fun anchoredCard():View?=anchor()?.let { saved->
        if(shelfFeed())(saved.parent?.let(shelves::findTag) as? YouTubeShelfView)
            ?.attachedVideo(saved.tag)
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
            rememberShelfFocus(previous);rememberShelfFocus(focused)
            val row=focused?.let{contentCard(it)?.parent as? View}?.parent as? YouTubeShelfView
            if(row!=null && focused?.tag==pendingShelfTag && pendingShelfScope==shelfScope() && shelfSource().getOrNull(pendingShelfIndex?:-1)?.key==row.shelfKey)pendingShelfIndex=null
            while(shelfFocus.size>10)shelfFocus.remove(shelfFocus.keys.first())
            contentCard(previous)?.takeIf{contentCard(focused)==null}?.let { card->
                val parent=card.parent as? View
                val shelf=parent?.parent as? YouTubeShelfView
                contentAnchor=ContentAnchor(card.tag.toString(),shelf?.tag?.toString(),m.feed,m.query)
            }
            val expanded=within(focused,rail)
            val width=a.dp(if(expanded)164 else 52)
            if(rail.layoutParams.width!=width)rail.layoutParams=rail.layoutParams.apply { this.width=width }
            if(m.feed==YouTubeFeed.SEARCH) {
                if(within(focused,shelves)){searchCollapsed=true;searchTools.visibility=View.GONE}
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
            add(channelHeader)
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
            if(grid.findLastVisibleItemPosition()>=gridCount()-3 && gridCount()>0)m.more()
        }})
        shelves.addOnScrollListener(object:RecyclerView.OnScrollListener(){override fun onScrolled(recyclerView:RecyclerView,dx:Int,dy:Int) {
            if(m.feed==YouTubeFeed.HOME && homeLayout.findLastVisibleItemPosition()>=shelfAdapter.itemCount-2 && shelfAdapter.itemCount>0)m.more()
        }})
        for(button in listOf(home,shorts,searchNav,account,subscriptions,library))button.setOnKeyListener { _,key,event->
            if(event.action==KeyEvent.ACTION_DOWN && key==KeyEvent.KEYCODE_DPAD_RIGHT) {
                returnFromRail(button);true }
            else if(button===account && event.action==KeyEvent.ACTION_DOWN && key==KeyEvent.KEYCODE_DPAD_UP) { a.window.decorView.findTag("navigation:YouTube")?.requestFocus();true }else false
        }
        for(button in listOf(history,watchLater,liked,playlists))button.setOnKeyListener { _,key,event->
            if(event.action==KeyEvent.ACTION_DOWN && key==KeyEvent.KEYCODE_DPAD_DOWN){(firstGridCard()?:emptySignIn.takeIf{it.isShown})?.requestFocus()?:true}
            else if(event.action==KeyEvent.ACTION_DOWN && key==KeyEvent.KEYCODE_DPAD_UP)topNavigation().requestFocus() else false
        }
        view.viewTreeObserver.addOnGlobalFocusChangeListener(railFocus)
        m.activate()
    }
    private fun play(video:YouTubeVideo,group:List<YouTubeVideo>?) { hideKeyboard();restore.cancel();m.play(video,group) }
    private fun openPlaylist(value:YouTubePlaylist) {
        playlistReturnAnchor=ContentAnchor("youtube-playlist-card:${value.id}",null,YouTubeFeed.PLAYLISTS,"")
        restore.cancel();m.openPlaylist(value);focusContent()
    }
    private fun gridCount()=if(m.feed==YouTubeFeed.PLAYLISTS)playlistAdapter.itemCount else adapter.itemCount
    private fun contentEmpty()=if(m.feed==YouTubeFeed.PLAYLISTS)m.playlistCards.isEmpty() else m.videos.isEmpty()
    private fun shelfFeed(feed:YouTubeFeed?=m.feed)=feed==YouTubeFeed.HOME || feed==YouTubeFeed.SEARCH
    private fun shelfSource()=when(m.feed){YouTubeFeed.HOME->m.homeShelves;YouTubeFeed.SEARCH->m.searchShelves;else->emptyList()}
    private fun shelfScope()=if(m.feed==YouTubeFeed.SEARCH)"SEARCH/${m.query.trim()}" else m.feed.name
    private fun rememberShelf(row:YouTubeShelfView) {
        if(row.stateKey.isBlank())return
        row.saveState()?.let{shelfStates[row.stateKey]=it}
        while(shelfStates.size>10)shelfStates.remove(shelfStates.keys.first())
    }
    private fun shelfEnd(key:String,identity:String) {
        if(m.feed!=YouTubeFeed.SEARCH || identity!="${shelfScope()}/$key")return
        val primary=m.searchShelves.firstOrNull{!it.portrait}?:m.searchShelves.firstOrNull()
        if(primary?.key==key)m.more()
    }
    private fun focusShelfRow(index:Int):Boolean {
        val row=shelfSource().getOrNull(index)?:return true
        val tag=shelfFocus["${shelfScope()}/${row.key}"]?.takeIf{saved->row.videos.any{"youtube-video:${it.id}"==saved}}
            ?:row.videos.firstOrNull()?.let{"youtube-video:${it.id}"}?:return true
        pendingShelfIndex=index;pendingShelfScope=shelfScope();pendingShelfTag=tag
        val target=(homeLayout.findViewByPosition(index) as? YouTubeShelfView)?.attachedVideo(tag)
        if(target!=null)return target.requestFocus()
        shelves.scrollToPosition(index)
        restore.start { (homeLayout.findViewByPosition(index) as? YouTubeShelfView)?.focusVideo(tag) }
        return true
    }
    private fun browse(feed:YouTubeFeed){restore.cancel();hideKeyboard();m.select(feed);focusContent()}
    private fun channelTarget():View = channelTabs.findTag("youtube-channel-tab:${m.channelPage?.selectedTab}")
        ?:channelTabs.getChildAt(0)?:channelSubscribe
    private fun subscribeChannel() {
        val channel=channelOverride?:m.channelPage?.channel?:return
        if(channelBusy)return
        if(m.account==null || channelNeedsSignIn) {
            signIn=YouTubeSignIn(a).also { sheet->sheet.show(forceSignIn=channelNeedsSignIn) { if(!closed){channelNeedsSignIn=false;channelOverride=null;m.refresh()} } }
            return
        }
        val key=++channelRequest;val subscribed=channel.subscribed!=true
        channelPendingId=channel.id;channelBusy=true;channelError=null;update()
        channelJob=a.lifecycleScope.launch {
            try {
                val scope=m.client.accountScope()?:throw YouTubeAuthError("unauthorized")
                m.client.subscribe(channel,subscribed,expectedScope=scope)
                if(!m.client.accountScopeCurrent(scope))return@launch
                if(key==channelRequest && m.feed==YouTubeFeed.CHANNEL && m.channelPage?.channel?.id==channel.id)
                    channelOverride=channel.copy(subscribed=subscribed)
            }catch(e:CancellationException){throw e}
            catch(e:Exception){if(key==channelRequest){channelError=(e as? UserMessage)?.message?:"YouTube couldn't update this subscription. Try again."
                if(e is YouTubeAuthError || e is HttpFailure && e.status==401){channelNeedsSignIn=true;m.syncAccount();m.refresh()}}}
            finally{if(key==channelRequest){channelBusy=false;channelPendingId="";channelJob=null;if(!closed)update()}}
        }
    }
    private fun updateChannel() {
        val visible=m.feed==YouTubeFeed.CHANNEL
        channelHeader.visibility=if(visible)View.VISIBLE else View.GONE
        if(channelJob!=null && (!visible || m.channelId!=channelPendingId)) {
            channelRequest++;channelJob?.cancel();channelJob=null;channelPendingId="";channelBusy=false
        }
        if(!visible) {
            channelError=null;channelNeedsSignIn=false;return
        }
        val page=m.channelPage
        val id=page?.channel?.id.orEmpty()
        if(shownChannel!=id){shownChannel=id;channelOverride=null;shownChannelImage="";channelError=null;channelNeedsSignIn=false
            channelImage.dispose();channelImage.setImageDrawable(null)}
        val channel=channelOverride?.takeIf{it.id==id}?:page?.channel
        channelName.textIfChanged(channel?.name?.ifBlank{"Channel"}?:"Channel")
        channelInfo.textIfChanged(channel?.subscriberText.orEmpty())
        channelDescription.textIfChanged(channel?.description.orEmpty())
        channelDescription.visibility=if(channel?.description.isNullOrBlank())View.GONE else View.VISIBLE
        channelSubscribe.isEnabled=channel!=null && !channelBusy
        channelSubscribe.textIfChanged(when{channelBusy->"Updating…";channelNeedsSignIn->"Sign in";channel?.subscribed==true && m.account!=null->"Subscribed";else->"Subscribe"})
        channelSubscribe.contentDescription=if(channel?.subscribed==true && m.account!=null)"Unsubscribe from channel" else "Subscribe to channel"
        channelSubscribe.isSelected=channel?.subscribed==true && m.account!=null
        val image=channel?.thumbnail.orEmpty()
        if(image!=shownChannelImage){shownChannelImage=image;a.artwork.show(channelImage,image,a.dp(48),a.dp(48))}
        if(shownTabs!=page?.tabs) {
            shownTabs=page?.tabs;channelTabs.removeAllViews()
            for(tab in page?.tabs.orEmpty().take(8))channelTabs.add(a.action(tab.title,"${tab.title} channel videos") {
                channelError=null;restore.cancel();m.selectChannelTab(tab.id);focusContent()
            }.quiet().apply {
                tag="youtube-channel-tab:${tab.id}";this.id=View.generateViewId()
                setOnKeyListener { _,key,event->if(event.action!=KeyEvent.ACTION_DOWN)false else when(key) {
                    KeyEvent.KEYCODE_DPAD_UP->channelSubscribe.requestFocus()
                    KeyEvent.KEYCODE_DPAD_DOWN->firstGridCard()?.requestFocus()?:true
                    else->false
                } }
            },width=a.dp(108),height=a.dp(38))
        }
        for(i in 0 until channelTabs.childCount)channelTabs.getChildAt(i).isSelected=
            channelTabs.getChildAt(i).tag=="youtube-channel-tab:${page?.selectedTab}"
        channelSubscribe.setOnKeyListener { _,key,event->if(event.action!=KeyEvent.ACTION_DOWN)false else when(key) {
            KeyEvent.KEYCODE_DPAD_DOWN->channelTarget().takeIf{it!==channelSubscribe}?.requestFocus()?:firstGridCard()?.requestFocus()?:true
            KeyEvent.KEYCODE_DPAD_UP->topNavigation().requestFocus()
            else->false
        } }
    }
    private fun libraryTarget()=when(m.feed){YouTubeFeed.WATCH_LATER->watchLater;YouTubeFeed.LIKED->liked;YouTubeFeed.PLAYLISTS,YouTubeFeed.PLAYLIST->playlists;else->history}
    private fun portraitFeed()=m.feed==YouTubeFeed.SHORTS || m.feed==YouTubeFeed.CHANNEL &&
        m.channelPage?.tabs?.firstOrNull{it.id==m.channelPage?.selectedTab}?.title.equals("Shorts",ignoreCase=true)
    private fun topNavigation():View=a.window.decorView.findTag("navigation:YouTube")?:selectedRail()
    private fun selectedRail()=when(m.feed){YouTubeFeed.HOME,YouTubeFeed.CHANNEL->home;YouTubeFeed.SHORTS->shorts;YouTubeFeed.SEARCH->searchNav;YouTubeFeed.SUBSCRIPTIONS->subscriptions;else->library}
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
    private fun focusContent(preferAnchor:Boolean=false) {
        val wanted=if(preferAnchor)anchor() else null
        restore.start(waitForWindowFocus=true) {
        if(m.busy || m.resolving || !shelfFeed() && m.feed!=YouTubeFeed.PLAYLISTS && adapter.currentList!=m.videos ||
            m.feed==YouTubeFeed.PLAYLISTS && playlistAdapter.currentList!=m.playlistCards ||
            shelfFeed() && (submittedShelves!==shelfSource() || submittedShelfScope!=shelfScope() || shelfAdapter.currentList!=presentedShelves))null
        // A diff can commit before RecyclerView attaches its first card on the next frame.
        else if(contentEmpty())when {m.error!=null->retry;m.feed.requiresAccount && m.account==null->emptySignIn;
            m.feed.library->libraryTarget();m.feed==YouTubeFeed.CHANNEL->channelTarget();emptyRefresh.isShown->emptyRefresh;m.feed==YouTubeFeed.SEARCH->input;else->selectedRail()}
        else if(wanted!=null && wanted.feed==m.feed)seekAnchor(wanted)?.also{contentAnchor=wanted} else firstCard()
    } }
    private fun seekAnchor(saved:ContentAnchor):View? {
        if(shelfFeed()) {
            val index=shelfSource().indexOfFirst{row->("youtube-shelf:${row.key}"==saved.parent || saved.parent==null) && row.videos.any{"youtube-video:${it.id}"==saved.tag}}
            if(index<0)return firstCard()
            shelves.scrollToPosition(index)
            return (homeLayout.findViewByPosition(index) as? YouTubeShelfView)?.focusVideo(saved.tag)
        }
        val index=if(m.feed==YouTubeFeed.PLAYLISTS)m.playlistCards.indexOfFirst{"youtube-playlist-card:${it.id}"==saved.tag}
            else m.videos.indexOfFirst{"youtube-video:${it.id}"==saved.tag}
        if(index<0)return firstCard()
        list.findTag(saved.tag)?.takeIf{it.isShown}?.let{return it}
        list.scrollToPosition(index);return null
    }
    private fun firstGridCard():View?=list.getChildAt(0)?.takeIf { it.isShown && it.isFocusable }
    private fun firstCard():View?=if(shelfFeed())(shelves.getChildAt(0) as? YouTubeShelfView)?.firstVideo() else firstGridCard()
    override fun focusTarget():View?=when {
        m.feed==YouTubeFeed.SEARCH && !searchCollapsed->input
        m.feed==YouTubeFeed.CHANNEL && contentEmpty()->channelTarget()
        m.busy && contentEmpty()->selectedRail()
        else->anchoredCard()?:firstCard()?:retry.takeIf{it.isShown}?:emptySignIn.takeIf{it.isShown}?:emptyRefresh.takeIf{it.isShown}?:
            if(m.feed==YouTubeFeed.SEARCH)input else if(m.feed.library)libraryTarget() else selectedRail()
    }
    override fun restoreFocus(tag:String,parentTag:String?):Boolean {
        if(!tag.startsWith("youtube-video:") && !tag.startsWith("youtube-playlist-card:"))return false
        if(shelfFeed()) {
            val index=shelfSource().indexOfFirst { row->("youtube-shelf:${row.key}"==parentTag || parentTag==null) && row.videos.any{"youtube-video:${it.id}"==tag} }
            if(index<0)return false
            shelves.scrollToPosition(index);restore.start { (homeLayout.findViewByPosition(index) as? YouTubeShelfView)?.focusVideo(tag) };return true
        }
        val index=if(m.feed==YouTubeFeed.PLAYLISTS)playlistAdapter.currentList.indexOfFirst{"youtube-playlist-card:${it.id}"==tag}
            else adapter.currentList.indexOfFirst { "youtube-video:${it.id}"==tag }
        if(index<0)return false
        list.findTag(tag)?.takeIf{it.isShown}?.let{it.requestFocus();return true}
        list.scrollToPosition(index);restore.start { list.findTag(tag) };return true
    }
    override fun back():Boolean {
        if(m.resolving){m.cancelPlayback();return true}
        if(m.feed==YouTubeFeed.PLAYLIST) {
            restore.cancel();m.backPlaylist();contentAnchor=playlistReturnAnchor;focusContent(preferAnchor=true);return true
        }
        if(m.feed==YouTubeFeed.CHANNEL) {
            restore.cancel();hideKeyboard();m.backChannel()
            contentAnchor=channelReturnAnchor;channelReturnAnchor=null;focusContent(preferAnchor=true);return true
        }
        if(m.feed==YouTubeFeed.SEARCH && !searchCollapsed && m.videos.isNotEmpty()) {
            restore.cancel();hideKeyboard();searchCollapsed=true;searchTools.visibility=View.GONE
            focusContent(preferAnchor=true);return true
        }
        if(m.feed!=YouTubeFeed.HOME){restore.cancel();hideKeyboard();m.select(YouTubeFeed.HOME);focusContent();return true}
        return false
    }
    override fun update() {
        if(closed)return
        if(m.feed==YouTubeFeed.CHANNEL && lastFeed!=YouTubeFeed.CHANNEL) {
            val card=contentCard(a.currentFocus)
            val shelf=(card?.parent as? View)?.parent as? YouTubeShelfView
            channelReturnAnchor=card?.tag?.toString()?.let { ContentAnchor(it,shelf?.tag?.toString(),lastFeed?:YouTubeFeed.HOME,lastQuery) }
                ?:contentAnchor?.takeIf{it.feed!=YouTubeFeed.CHANNEL}?:channelReturnAnchor
        }
        updateChannel()
        val columns=if(portraitFeed())5 else 4
        if(grid.spanCount!=columns)grid.spanCount=columns
        home.isSelected=m.feed==YouTubeFeed.HOME;shorts.isSelected=m.feed==YouTubeFeed.SHORTS;searchNav.isSelected=m.feed==YouTubeFeed.SEARCH
        subscriptions.isSelected=m.feed==YouTubeFeed.SUBSCRIPTIONS;library.isSelected=m.feed.library
        history.isSelected=m.feed==YouTubeFeed.HISTORY;watchLater.isSelected=m.feed==YouTubeFeed.WATCH_LATER;liked.isSelected=m.feed==YouTubeFeed.LIKED
        playlists.isSelected=m.feed==YouTubeFeed.PLAYLISTS || m.feed==YouTubeFeed.PLAYLIST
        libraryTabs.visibility=if(m.feed.library)View.VISIBLE else View.GONE
        account.textIfChanged(if(m.account==null)"Sign in" else "Account")
        if(input.text.toString()!=m.query) { syncing=true;input.setText(m.query);input.setSelection(input.text.length);syncing=false }
        if(lastFeed!=m.feed || lastQuery!=m.query && m.feed==YouTubeFeed.SEARCH) {
            pendingShelfIndex=null;pendingShelfScope=""
            if(shelfFeed(lastFeed))lastFeed?.let{feed->homeLayout.onSaveInstanceState()?.let { scrollStates[feed]=it }}
            else lastFeed?.let { f->grid.onSaveInstanceState()?.let { scrollStates[f]=it } }
            list.stopScroll();shelves.stopScroll()
            if(shelfFeed())pendingHomeScroll=pendingHomeScroll?:if(m.feed==YouTubeFeed.SEARCH && lastQuery!=m.query)null else scrollStates[m.feed]
            else pendingScroll=pendingScroll?:if(m.feed==YouTubeFeed.SEARCH && lastQuery!=m.query)null else scrollStates[m.feed]
            lastFeed=m.feed;lastQuery=m.query
        }
        searchTools.visibility=if(m.feed==YouTubeFeed.SEARCH && !searchCollapsed)View.VISIBLE else View.GONE
        searchHeader.visibility=if(m.feed==YouTubeFeed.SEARCH)View.VISIBLE else View.GONE
        homeSearchRow.visibility=if(m.feed==YouTubeFeed.HOME)View.VISIBLE else View.GONE
        heading.visibility=if(m.feed==YouTubeFeed.HOME || m.feed==YouTubeFeed.SEARCH || m.feed==YouTubeFeed.CHANNEL)View.GONE else View.VISIBLE
        heading.textIfChanged(when(m.feed){YouTubeFeed.SHORTS->"Shorts";YouTubeFeed.SUBSCRIPTIONS->"Subscriptions";YouTubeFeed.PLAYLISTS->"Playlists";YouTubeFeed.PLAYLIST->m.selectedPlaylist?.name?:"Playlist";else->"Library"})
        shelves.tag=if(m.feed==YouTubeFeed.SEARCH)"youtube-search-results" else "youtube-home"
        shelves.visibility=if(shelfFeed())View.VISIBLE else View.GONE;list.visibility=if(shelfFeed())View.GONE else View.VISIBLE
        val targetAdapter=if(m.feed==YouTubeFeed.PLAYLISTS)playlistAdapter else adapter
        if(list.adapter!==targetAdapter)list.swapAdapter(targetAdapter,true)
        if(submitted!==m.videos && !shelfFeed() && m.feed!=YouTubeFeed.PLAYLISTS) {
            submitted=m.videos;val state=pendingScroll;pendingScroll=null
            adapter.submitList(m.videos) { if(state!=null)grid.onRestoreInstanceState(state) }
        }else if((shelfFeed() || m.feed==YouTubeFeed.PLAYLISTS) && adapter.itemCount>0) { submitted=null;adapter.submitList(emptyList());list.recycledViewPool.clear() }
        if(m.feed==YouTubeFeed.PLAYLISTS && submittedPlaylists!==m.playlistCards) {
            submittedPlaylists=m.playlistCards;val state=pendingScroll;pendingScroll=null
            playlistAdapter.submitList(m.playlistCards){if(state!=null)grid.onRestoreInstanceState(state)}
        }else if(m.feed!=YouTubeFeed.PLAYLISTS && playlistAdapter.itemCount>0){submittedPlaylists=null;playlistAdapter.submitList(emptyList())}
        val source=shelfSource();val scope=shelfScope()
        if(submittedShelves!==source || submittedShelfScope!=scope) {
            submittedShelves=source;submittedShelfScope=scope;val state=pendingHomeScroll;pendingHomeScroll=null
            presentedShelves=source.mapIndexed { index,row->ShelfRow(row,"$scope/${row.key}",if(row.portrait)2 else if(m.feed==YouTubeFeed.HOME && index==0)1 else 0) }
            shelfAdapter.submitList(presentedShelves) { if(state!=null)homeLayout.onRestoreInstanceState(state) }
        }
        if(shownRecent!=m.recentQueries) {
            shownRecent=m.recentQueries;recent.removeAllViews()
            recent.add(a.text("Recent searches",12f,MUTED),height=a.dp(22))
            if(m.recentQueries.isEmpty())recent.add(a.text("Find something to watch",12f,MUTED).apply{maxLines=2})
            else for(query in m.recentQueries.take(4))recent.add(a.action(query,"Search YouTube for $query"){input.setText(query);search()}.quiet().apply{
                gravity=Gravity.START;setPadding(a.dp(8),0,a.dp(8),0);setSingleLine(true);textSize=13f
            },height=a.dp(32))
        }
        val text=channelError?:m.error.orEmpty();feedback.visibility=if(text.isNotBlank())View.VISIBLE else View.GONE;status.textIfChanged(text)
        retry.visibility=if(m.error!=null)View.VISIBLE else View.GONE
        progress.visibility=if(m.busy || m.resolving || channelBusy)View.VISIBLE else View.INVISIBLE
        progress.contentDescription=if(m.resolving)"Opening YouTube video" else "Loading YouTube"
        empty.visibility=if(contentEmpty() && !m.busy && m.error==null)View.VISIBLE else View.GONE
        val blankSearch=m.feed==YouTubeFeed.SEARCH && m.query.trim().length<2
        val needsAccount=m.feed.requiresAccount && m.account==null
        emptySignIn.visibility=if(needsAccount)View.VISIBLE else View.GONE
        emptyRefresh.visibility=if(m.feed in setOf(YouTubeFeed.HOME,YouTubeFeed.SHORTS) && m.videos.isEmpty() && !m.busy && m.error==null)View.VISIBLE else View.GONE
        emptyTitle.textIfChanged(when(m.feed){YouTubeFeed.HOME->"No recommendations right now";YouTubeFeed.SHORTS->"No Shorts right now";YouTubeFeed.SEARCH->if(blankSearch)"Search YouTube" else "No videos found";YouTubeFeed.SUBSCRIPTIONS->if(needsAccount)"Your subscriptions" else "No new videos";YouTubeFeed.HISTORY->"Nothing watched yet";YouTubeFeed.WATCH_LATER->"Watch Later";YouTubeFeed.LIKED->"Liked videos";YouTubeFeed.CHANNEL->"No videos here";YouTubeFeed.PLAYLISTS->"Your playlists";YouTubeFeed.PLAYLIST->"No videos in this playlist"})
        emptyBody.textIfChanged(when(m.feed){YouTubeFeed.HOME->m.message.ifBlank{"Try refresh or search for a video. Sign-in is optional."};YouTubeFeed.SHORTS->"Try again in a moment, or search for a video.";YouTubeFeed.SEARCH->if(blankSearch)"Use the keyboard above to find something to watch." else "Try a different search.";YouTubeFeed.CHANNEL->m.message.ifBlank{"Try another tab to see more from this channel."};else->if(needsAccount)"Sign in to see this part of your YouTube account." else if(m.feed==YouTubeFeed.HISTORY && m.account==null)"Videos you watch on this TV appear here." else "This part of your library is empty."})
    }
    private fun account() {
        if(m.account!=null) {
            a.showActions("YouTube account",listOf("Refresh recommendations" to {if(m.feed==YouTubeFeed.HOME)m.refresh() else m.select(YouTubeFeed.HOME)},"Link phone" to {a.connectYouTubePhone()},"Sign out" to {
                a.confirm("Sign out of YouTube on this TV?","Sign out"){m.signOut()}
            }));return
        }
        if(signIn?.visible==true)return
        hideKeyboard()
        signIn=YouTubeSignIn(a).also { sheet->sheet.show { if(!closed)focusContent() } }
    }
    override fun saveState():Parcelable=Bundle().apply {
        putBoolean("searchCollapsed",searchCollapsed)
        focusedAnchor()?.let{putBundle("contentAnchor",it.saved())}
        channelReturnAnchor?.let{putBundle("channelReturnAnchor",it.saved())}
        playlistReturnAnchor?.let{putBundle("playlistReturnAnchor",it.saved())}
        putString("scrollScope",shelfScope())
        (if(shelfFeed())homeLayout else grid).onSaveInstanceState()?.let { putParcelable("scroll",it) }
        for(i in 0 until shelves.childCount)(shelves.getChildAt(i) as? YouTubeShelfView)?.let(::rememberShelf)
        putBundle("rows",Bundle().apply { for((key,state) in shelfStates.entries.toList().takeLast(10))putParcelable(key,state) })
        putBundle("rowFocus",Bundle().apply{for((key,tag)in shelfFocus.entries.toList().takeLast(10))putString(key,tag)})
    }
    @Suppress("DEPRECATION") override fun restoreState(state:Parcelable?) {
        val bundle=state as? Bundle?:return
        searchCollapsed=bundle.getBoolean("searchCollapsed")
        contentAnchor=readAnchor(bundle.getBundle("contentAnchor"))
        channelReturnAnchor=readAnchor(bundle.getBundle("channelReturnAnchor"))?:contentAnchor?.takeIf{m.feed==YouTubeFeed.CHANNEL && it.feed!=YouTubeFeed.CHANNEL}
        playlistReturnAnchor=readAnchor(bundle.getBundle("playlistReturnAnchor"))
        if(bundle.getString("scrollScope").let{it==null || it==shelfScope()})bundle.getParcelable<Parcelable>("scroll")?.let { if(shelfFeed())pendingHomeScroll=it else pendingScroll=it }
        bundle.getBundle("rows")?.let { rows->for(key in rows.keySet().take(10))rows.getParcelable<Parcelable>(key)?.let { shelfStates[key]=it } }
        bundle.getBundle("rowFocus")?.let{rows->for(key in rows.keySet().take(10))rows.getString(key)?.let{shelfFocus[key]=it}}
        // The outer list can be attached before Activity restores its saved screen state.
        submitted=null;submittedShelves=null
    }
    override fun foreground(){m.activate()}
    override fun background(){restore.cancel();hideKeyboard();m.deactivate();channelRequest++;channelJob?.cancel();channelJob=null;channelBusy=false;signIn?.close()}
    override fun close(){
        closed=true;restore.cancel();signIn?.close();signIn=null;m.deactivate();channelRequest++;channelJob?.cancel();channelJob=null
        channelImage.dispose();channelImage.setImageDrawable(null)
        view.viewTreeObserver.removeOnGlobalFocusChangeListener(railFocus)
        for(i in 0 until list.childCount)when(val card=list.getChildAt(i)){is YouTubeCard->card.release();is YouTubePlaylistCard->card.release()}
        for(i in 0 until shelves.childCount)(shelves.getChildAt(i) as? YouTubeShelfView)?.release()
        list.adapter=null;shelves.adapter=null;list.recycledViewPool.clear();shelves.recycledViewPool.clear();pool.clear();shelfStates.clear();shelfFocus.clear()
    }
}
