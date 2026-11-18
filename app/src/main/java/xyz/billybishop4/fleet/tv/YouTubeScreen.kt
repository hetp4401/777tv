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
import android.text.TextUtils
import android.text.TextWatcher
import android.view.*
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class YouTubeScreen(private val a:MainActivity):NativeScreen {
    private val m=a.model.youtube
    private var syncing=false;private var closed=false
    private val restore=BrowseFocusRestore(a,a.window.decorView)
    override val view=object:LinearLayout(a) {
        init{orientation=VERTICAL;tag="youtube-page"}
        override fun dispatchKeyEvent(event:KeyEvent):Boolean {
            if(event.action==KeyEvent.ACTION_DOWN && (event.keyCode==KeyEvent.KEYCODE_DPAD_LEFT || event.keyCode==KeyEvent.KEYCODE_DPAD_RIGHT)) {
                val item=findFocus()?.let(list::findContainingItemView)
                if(item!=null) {
                    val position=requestedFocusIndex?:list.getChildAdapterPosition(item)
                    val next=(position+if(event.keyCode==KeyEvent.KEYCODE_DPAD_RIGHT)1 else -1).coerceIn(0,(adapter.itemCount-1).coerceAtLeast(0))
                    if(position>=0 && next!=position)focusIndex(next)
                    return true
                }
            }
            if(event.action==KeyEvent.ACTION_DOWN && event.keyCode==KeyEvent.KEYCODE_DPAD_UP) {
                val focused=list.findContainingItemView(findFocus()?:return super.dispatchKeyEvent(event))
                val position=focused?.let(list::getChildAdapterPosition)?:-1
                if(position in 0 until grid.spanCount)return input.requestFocus()
            }
            return super.dispatchKeyEvent(event)
        }
    }
    private val home=a.action("Home","YouTube home"){hideKeyboard();m.select(YouTubeFeed.HOME)}.quiet()
    private val shorts=a.action("Shorts","YouTube Shorts"){hideKeyboard();m.select(YouTubeFeed.SHORTS)}.quiet()
    private val input=EditText(a).apply {
        id=View.generateViewId();tag="field:Search YouTube";contentDescription="Search YouTube";hint="Search YouTube"
        setTextColor(WHITE);setHintTextColor(MUTED);textSize=15f;setSingleLine(true)
        inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        imeOptions=EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI;showSoftInputOnFocus=false
        filters=arrayOf(android.text.InputFilter.LengthFilter(200));setPadding(a.dp(14),a.dp(8),a.dp(14),a.dp(8))
        background=StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused),a.round(PANEL,WHITE));addState(intArrayOf(),a.round(PANEL))
        }
        setText(m.query);setSelection(text.length)
        val icon=a.getDrawable(R.drawable.ic_search)!!.mutate().apply{setBounds(0,0,a.dp(18),a.dp(18))}
        setCompoundDrawablesRelative(icon,null,null,null);compoundDrawablePadding=a.dp(8)
    }
    private val submit=a.action("Search","Search YouTube videos",prominent=true){search()}
    private val account=a.action("Sign in","YouTube account"){account()}.quiet()
    private val status=a.text("",13f,MUTED).apply{tag="youtube-status";maxLines=2;visibility=View.GONE}
    private val retry=a.action("Retry","Retry YouTube"){m.refresh()}.quiet()
    private val feedback=a.row().apply {
        addView(status,LinearLayout.LayoutParams(0,-2,1f));add(retry,width=-2);visibility=View.GONE
    }
    private val progress=ProgressBar(a,null,android.R.attr.progressBarStyleHorizontal).apply {
        tag="youtube-loading";isIndeterminate=true;visibility=View.INVISIBLE;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val grid=GridLayoutManager(a,if(m.feed==YouTubeFeed.SHORTS)6 else 4).apply { initialPrefetchItemCount=2 }
    private val list=RecyclerView(a).apply { id=View.generateViewId();tag="youtube-results";layoutManager=grid;tune(cachedViews=2) }
    private val emptyTitle=a.text("",22f,bold=true)
    private val emptyBody=a.text("",14f,MUTED).apply { maxLines=3;gravity=Gravity.CENTER }
    private val empty=a.column().apply { gravity=Gravity.CENTER;add(emptyTitle,width=-2);gap(10);add(emptyBody,width=-2) }
    private val openVideo:(YouTubeVideo)->Unit={hideKeyboard();restore.cancel();m.play(it)}
    private val adapter=NativeAdapter<YouTubeVideo>({it.id},
        {parent,type->YouTubeCard(parent.context,a.artwork,type==1)},
        {v,value,_->(v as YouTubeCard).bind(value,openVideo)},
        type={if(m.feed==YouTubeFeed.SHORTS)1 else 0},recycle={(it as YouTubeCard).release()})
    private var lastFeed:YouTubeFeed?=null;private var lastQuery=""
    private var submitted:List<YouTubeVideo>?=null
    private val scrollStates=linkedMapOf<YouTubeFeed,Parcelable>()
    private var pendingScroll:Parcelable?=null
    private var requestedFocusIndex:Int?=null
    private var signInDialog:AlertDialog?=null
    private var codeText:TextView?=null;private var codeMessage:TextView?=null;private var qr:ImageView?=null
    private var shownCode:YouTubeDeviceCode?=null;private var qrJob:Job?=null
    init {
        view.add(a.row().apply {
            isBaselineAligned=false
            add(home,width=a.dp(72),height=a.dp(44));add(shorts,width=a.dp(80),height=a.dp(44))
            addView(input,LinearLayout.LayoutParams(0,a.dp(44),1f).apply { marginStart=a.dp(8);marginEnd=a.dp(8) })
            add(submit,width=a.dp(86),height=a.dp(44));add(account,width=a.dp(90),height=a.dp(44))
        },height=a.dp(48))
        view.add(progress,height=a.dp(2));view.gap(8);view.add(feedback);view.gap(4)
        view.addView(FrameLayout(a).apply {
            addView(list,FrameLayout.LayoutParams(-1,-1));addView(empty,FrameLayout.LayoutParams(-1,-1))
        },LinearLayout.LayoutParams(-1,0,1f))
        list.adapter=adapter
        input.setOnClickListener{keyboard()}
        input.setOnFocusChangeListener { _,focused->if(!focused)hideKeyboard() }
        input.setOnKeyListener { _,key,event->when(key) {
            KeyEvent.KEYCODE_DPAD_CENTER->{if(event.action==KeyEvent.ACTION_UP)keyboard();true}
            KeyEvent.KEYCODE_ENTER->{if(event.action==KeyEvent.ACTION_UP)search();true}
            KeyEvent.KEYCODE_DPAD_UP->{if(event.action==KeyEvent.ACTION_DOWN){hideKeyboard();a.window.decorView.findTag("navigation:YouTube")?.requestFocus()};true}
            else->false
        } }
        input.setOnEditorActionListener { _,action,event->if(action in setOf(EditorInfo.IME_ACTION_SEARCH,EditorInfo.IME_ACTION_DONE) || event?.keyCode==KeyEvent.KEYCODE_ENTER) {
            if(event==null || event.action==KeyEvent.ACTION_UP)search();true
        }else false }
        input.addTextChangedListener(object:TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){if(!syncing){restore.cancel();m.editQuery(s?.toString().orEmpty())}}
            override fun afterTextChanged(s:Editable?){}
        })
        list.addOnScrollListener(object:RecyclerView.OnScrollListener(){
            override fun onScrolled(recyclerView:RecyclerView,dx:Int,dy:Int) {
                if(grid.findLastVisibleItemPosition()>=adapter.itemCount-3 && adapter.itemCount>0)m.more()
            }
        })
        for(button in listOf(home,shorts,submit,account))button.setOnKeyListener { _,key,event->
            if(event.action==KeyEvent.ACTION_DOWN && key==KeyEvent.KEYCODE_DPAD_UP) {
                a.window.decorView.findTag("navigation:YouTube")?.requestFocus();true
            }else false
        }
        m.activate()
    }
    private fun keyboard() {
        input.requestFocus();input.post { if(input.isAttachedToWindow && input.hasFocus())
            (a.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(input,InputMethodManager.SHOW_IMPLICIT) }
    }
    private fun hideKeyboard(){(a.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(input.windowToken,0)}
    private fun search() {
        if(input.text.toString().trim().length<2){keyboard();return}
        m.submitSearch();hideKeyboard()
        restore.start(waitForWindowFocus=true) { if(m.busy || m.feed!=YouTubeFeed.SEARCH)null else firstCard()?:input }
    }
    private fun firstCard():View?=list.getChildAt(0)?.takeIf { it.isShown && it.isFocusable }
    private fun focusIndex(index:Int) {
        val target=adapter.currentList.getOrNull(index)?:return
        val attached=grid.findViewByPosition(index)
        if(attached!=null) { requestedFocusIndex=null;restore.cancel();attached.requestFocus();return }
        requestedFocusIndex=index;list.smoothScrollToPosition(index)
        restore.start { list.findTag("youtube-video:${target.id}")?.also { requestedFocusIndex=null } }
    }
    override fun focusTarget():View?=firstCard()?:if(m.videos.isEmpty() && m.account==null && m.error==null)account else input
    override fun restoreFocus(tag:String,parentTag:String?):Boolean {
        if(!tag.startsWith("youtube-video:"))return false
        val index=adapter.currentList.indexOfFirst { "youtube-video:${it.id}"==tag };if(index<0)return false
        list.scrollToPosition(index);restore.start { list.findTag(tag) };return true
    }
    override fun back():Boolean {
        if(m.resolving){m.cancelPlayback();return true}
        if(m.query.isNotEmpty() || m.feed!=YouTubeFeed.HOME){restore.cancel();hideKeyboard();m.select(YouTubeFeed.HOME);return true}
        return false
    }
    override fun update() {
        if(closed)return
        home.isSelected=m.feed==YouTubeFeed.HOME;shorts.isSelected=m.feed==YouTubeFeed.SHORTS
        account.textIfChanged(if(m.account==null)"Sign in" else "Account")
        if(input.text.toString()!=m.query) { syncing=true;input.setText(m.query);input.setSelection(input.text.length);syncing=false }
        if(lastFeed!=m.feed || lastQuery!=m.query && m.feed==YouTubeFeed.SEARCH) {
            requestedFocusIndex=null;restore.cancel()
            lastFeed?.let { f->grid.onSaveInstanceState()?.let { scrollStates[f]=it } }
            grid.spanCount=if(m.feed==YouTubeFeed.SHORTS)6 else 4
            list.recycledViewPool.clear();list.stopScroll()
            pendingScroll=if(m.feed==YouTubeFeed.SEARCH && lastQuery!=m.query)null else scrollStates[m.feed]
            lastFeed=m.feed;lastQuery=m.query
        }
        if(submitted!==m.videos) {
            submitted=m.videos;val restoreState=pendingScroll;pendingScroll=null
            adapter.submitList(m.videos) { if(restoreState!=null)grid.onRestoreInstanceState(restoreState) }
        }
        val text=m.error.orEmpty()
        feedback.visibility=if(text.isNotBlank())View.VISIBLE else View.GONE;status.visibility=View.VISIBLE;status.textIfChanged(text)
        retry.visibility=if(m.error!=null)View.VISIBLE else View.GONE
        progress.visibility=if(m.busy || m.resolving)View.VISIBLE else View.INVISIBLE
        progress.contentDescription=if(m.resolving)"Opening YouTube video" else "Loading YouTube"
        empty.visibility=if(m.videos.isEmpty() && !m.busy && m.error==null)View.VISIBLE else View.GONE
        emptyTitle.textIfChanged(when(m.feed){YouTubeFeed.HOME->if(m.account==null)"Make YouTube yours" else "Your YouTube home";YouTubeFeed.SHORTS->"No Shorts right now";YouTubeFeed.SEARCH->"No videos found"})
        emptyBody.textIfChanged(when(m.feed){YouTubeFeed.HOME->if(m.account==null)"Sign in for your recommendations, or search without an account." else m.message.ifBlank{"Try refreshing your home feed."};YouTubeFeed.SHORTS->"Try again in a moment, or search for a video.";YouTubeFeed.SEARCH->"Try a different search."})
        updateSignIn()
    }
    private fun account() {
        if(m.account!=null) {
            a.showActions("YouTube account",listOf("Refresh recommendations" to {m.refresh()},"Sign out" to {
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
                    account.postOnAnimation { if(!closed && account.isShown)account.requestFocus() }
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
    override fun saveState():Parcelable=Bundle().apply { grid.onSaveInstanceState()?.let { putParcelable("scroll",it) } }
    @Suppress("DEPRECATION") override fun restoreState(state:Parcelable?) { (state as? Bundle)?.getParcelable<Parcelable>("scroll")?.let { pendingScroll=it } }
    override fun foreground(){m.activate()}
    override fun background(){m.deactivate()}
    override fun close(){
        closed=true;restore.cancel();qrJob?.cancel();signInDialog?.dismiss();signInDialog=null;m.deactivate()
        for(i in 0 until list.childCount)(list.getChildAt(i) as? YouTubeCard)?.release()
        list.adapter=null;list.recycledViewPool.clear()
    }
}

internal class YouTubeCard(context:Context,private val loader:ArtworkLoader,private val portrait:Boolean):LinearLayout(context) {
    private val image=ImageView(context).apply { scaleType=ImageView.ScaleType.CENTER_CROP;clipToOutline=true;background=context.round(PANEL);setDuplicateParentStateEnabled(true) }
    private val title=context.text("",14f).apply { maxLines=2;ellipsize=TextUtils.TruncateAt.END }
    private val channel=context.text("",12f,MUTED).apply { maxLines=1;ellipsize=TextUtils.TruncateAt.END }
    private val duration=context.text("",11f).apply { background=context.round(0xCC000000.toInt(),radius=5);pad(3) }
    private var video:YouTubeVideo?=null;private var click:((YouTubeVideo)->Unit)?=null
    private val imageWidth=context.dp(if(portrait)142 else 228)
    private val imageHeight=context.dp(if(portrait)245 else 126)
    init {
        orientation=VERTICAL;pad(4);isFocusable=true;isFocusableInTouchMode=true;isClickable=true;stateListAnimator=null
        if(android.os.Build.VERSION.SDK_INT>=26)defaultFocusHighlightEnabled=false
        layoutParams=RecyclerView.LayoutParams(-1,context.dp(if(portrait)310 else 199)).apply { marginEnd=context.dp(6);bottomMargin=context.dp(8) }
        image.foreground=StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused),context.round(Color.TRANSPARENT,WHITE));addState(intArrayOf(),context.round(Color.TRANSPARENT))
        }
        add(FrameLayout(context).apply {
            clipToOutline=true;background=context.round(PANEL);setDuplicateParentStateEnabled(true)
            addView(image,FrameLayout.LayoutParams(-1,-1))
            addView(duration,FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM or Gravity.END).apply { setMargins(context.dp(6),context.dp(6),context.dp(6),context.dp(6)) })
        },height=context.dp(if(portrait)245 else 126))
        gap(6);add(title,height=context.dp(35));gap(3);add(channel,height=context.dp(17))
        setOnClickListener { video?.let { click?.invoke(it) } }
    }
    fun bind(value:YouTubeVideo,open:(YouTubeVideo)->Unit) {
        click=open
        if(video==value)return
        id=if(id==View.NO_ID)View.generateViewId() else id
        video=value;tag="youtube-video:${value.id}";contentDescription=if(value.channel.isBlank())value.name else "${value.name}, ${value.channel}"
        title.textIfChanged(value.name);channel.textIfChanged(value.channel.ifBlank { value.metadata });duration.textIfChanged(if(value.live)"LIVE" else value.duration.ifBlank { if(value.isShort)"Shorts" else "" })
        duration.visibility=if(duration.text.isNotEmpty())View.VISIBLE else View.GONE;image.refreshDrawableState()
        loader.show(image,value.thumbnail,imageWidth,imageHeight)
    }
    fun release(){image.dispose();image.setImageDrawable(null);image.tag=null;video=null;click=null}
}
