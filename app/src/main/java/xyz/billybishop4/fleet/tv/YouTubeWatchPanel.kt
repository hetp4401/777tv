package xyz.billybishop4.fleet.tv

import android.app.AlertDialog
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.*
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Related videos sit below playback; comments occupy a TV-style panel on its right. */
internal class YouTubeWatchPanel(
    private val a:MainActivity,private val watch:Watch,private val resized:(Int)->Unit={},private val dismissed:()->Unit,
) {
    private val model=a.model.youtube
    private var details:YouTubeWatchDetails?=null
    private var commentsPage:YouTubeCommentsPage?=null
    private var repliesPage:YouTubeCommentsPage?=null
    private var thread:YouTubeComment?=null
    private var comments=false
    private var busy=false;private var opening=false;private var closed=false
    private var error:String?=null;private var request:Job?=null;private var generation=0
    private var commentDialog:AlertDialog?=null
    private var videoActions:YouTubeVideoActions?=null
    val view=object:LinearLayout(a) {
        init { orientation=VERTICAL;tag="youtube-watch-panel";setBackgroundColor(a.panelOverlay());setPadding(a.dp(28),a.dp(10),a.dp(28),a.dp(16));visibility=View.GONE }
        override fun focusSearch(focused:View,direction:Int):View? {
            val next=super.focusSearch(focused,direction)?:return focused
            var node:View?=next
            while(node!=null && node!==this)node=node.parent as? View
            return if(node===this)next else focused
        }
    }
    val visible get()=view.visibility==View.VISIBLE
    private val relatedButton=a.action("Related","Related videos tab"){select(false)}.quiet()
    private val commentsButton=a.action("Comments","Comments tab"){select(true)}.quiet().apply {
        maxLines=1;ellipsize=TextUtils.TruncateAt.END
    }
    private val backButton=a.action("‹","Back to comments"){backThread()}.quiet().apply{visibility=View.GONE;textSize=24f;setPadding(0,0,0,0)}
    private val heading=a.text(watch.title.name,13f,MUTED).apply { maxLines=1;ellipsize=TextUtils.TruncateAt.END }
    private val commentHeading=a.text("",12f,MUTED).apply {
        tag="youtube-comments-context";maxLines=1;ellipsize=TextUtils.TruncateAt.END;visibility=View.GONE
    }
    private val closeButton=a.action("×","Close video panel"){hidePanel()}.quiet().apply{textSize=24f;setPadding(0,0,0,0)}
    private val actionsButton=a.action("•••","Video panel actions") {
        if(videoActions?.visible!=true)videoActions=YouTubeVideoActions(a,YouTubeVideo(watch.title.id,watch.title.name,watch.title.poster,isShort=watch.short,live=watch.live)).also{it.show()}
    }.quiet().apply{setPadding(0,0,0,0)}
    private val progress=ProgressBar(a,null,android.R.attr.progressBarStyleHorizontal).themeTint().apply {
        tag="youtube-panel-loading";isIndeterminate=true;visibility=View.INVISIBLE;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val status=a.text("",13f,MUTED).apply { tag="youtube-panel-status";maxLines=3 }
    private val retry=a.action("Retry","Retry video details"){load(append=currentPage()!=null)}.quiet()
    private val feedback=a.row().apply { addView(status,LinearLayout.LayoutParams(0,-2,1f));add(retry,width=-2);visibility=View.GONE }
    private val list=RecyclerView(a).apply { tag="youtube-panel-list";tune(cachedViews=1) }
    private data class Position(val first:Int=0,val offset:Int=0,val focusedTag:String?=null)
    private data class FocusRequest(val key:String,val inputGeneration:Int,val position:Position)
    private var relatedPosition=Position();private var commentsPosition=Position()
    private var relatedFocus:String?=null;private var commentsFocus:String?=null
    private var pendingFocus:FocusRequest?=null
    private val restore=BrowseFocusRestore(a,view)
    private val focusChanged=ViewTreeObserver.OnGlobalFocusChangeListener { _,focused->
        focused?.let(list::findContainingItemView)?.tag?.let { tag->
            if(thread==null && tag is String){if(comments)commentsFocus=tag else relatedFocus=tag}
        }
    }
    private val relatedLayout by lazy { LinearLayoutManager(a,RecyclerView.HORIZONTAL,false).apply{initialPrefetchItemCount=1} }
    private val commentsLayout by lazy { LinearLayoutManager(a,RecyclerView.VERTICAL,false).apply{initialPrefetchItemCount=1} }
    private val more=a.action("More comments","Load more comments"){load(append=true)}.quiet().apply { visibility=View.GONE }
    private val related=NativeAdapter<YouTubeVideo>({it.id},{parent,_->YouTubeCard(parent.context,a.artwork,false,a.dp(218),true)},
        {v,value,_->(v as YouTubeCard).bind(value,::play)},type={101},recycle={(it as YouTubeCard).release()})
    private val commentRows=NativeAdapter<YouTubeComment>({it.id},{_,_->CommentRow(a)},
        {v,value,_->(v as CommentRow).bind(value,::readComment,
            replies=if(thread==null && value.repliesToken!=null)({openReplies(value)}) else null,
            back=if(thread!=null)({backThread()}) else null)},type={102},recycle={(it as CommentRow).release()})
    init {
        view.add(a.row().apply {
            add(relatedButton,width=a.dp(84),height=a.dp(40));add(commentsButton,width=a.dp(108),height=a.dp(40))
            add(backButton,width=a.dp(36),height=a.dp(40))
            addView(heading,LinearLayout.LayoutParams(0,-2,1f).apply { marginStart=a.dp(12);marginEnd=a.dp(12) })
            add(actionsButton,width=a.dp(40),height=a.dp(40))
            add(closeButton,width=a.dp(40),height=a.dp(40))
        },height=a.dp(44))
        view.add(progress,height=a.dp(2));view.add(commentHeading,height=a.dp(26));view.add(feedback)
        view.addView(list,LinearLayout.LayoutParams(-1,0,1f));view.add(more,width=a.dp(164),height=a.dp(36))
        relatedButton.nextFocusLeftId=relatedButton.id;closeButton.nextFocusRightId=closeButton.id
        relatedButton.nextFocusRightId=commentsButton.id;commentsButton.nextFocusLeftId=relatedButton.id
        commentsButton.nextFocusRightId=actionsButton.id;actionsButton.nextFocusLeftId=commentsButton.id
        actionsButton.nextFocusRightId=closeButton.id;closeButton.nextFocusLeftId=actionsButton.id
        list.setOnKeyListener { _,key,event ->
            if(event.action==android.view.KeyEvent.ACTION_DOWN && key==android.view.KeyEvent.KEYCODE_DPAD_UP && !comments)relatedButton.requestFocus() else false
        }
        list.recycledViewPool.setMaxRecycledViews(101,2);list.recycledViewPool.setMaxRecycledViews(102,3)
        view.viewTreeObserver.addOnGlobalFocusChangeListener(focusChanged)
    }
    fun show(showComments:Boolean=false) {
        if(closed)return
        rememberPosition();cancelRequest()
        view.visibility=View.VISIBLE;comments=showComments;thread=null;repliesPage=null
        planFocus(savedPosition());bindList();render();load();if(comments)commentsButton.requestFocus() else relatedButton.requestFocus()
    }
    private fun select(value:Boolean) {
        if(comments==value && thread==null)return
        rememberPosition();cancelRequest();comments=value;thread=null;repliesPage=null;error=null
        planFocus(savedPosition());bindList();render();load()
    }
    private fun contentKey()=if(comments)"comments/${thread?.id.orEmpty()}" else "related"
    private fun savedPosition()=if(comments)commentsPosition else relatedPosition
    private fun rememberPosition() {
        if(!visible || thread!=null || list.adapter==null)return
        val layout=list.layoutManager as? LinearLayoutManager?:return
        val first=layout.findFirstVisibleItemPosition().coerceAtLeast(0)
        val child=layout.findViewByPosition(first)
        val offset=if(comments)(child?.top?:list.paddingTop)-list.paddingTop else (child?.left?:list.paddingLeft)-list.paddingLeft
        val position=Position(first,offset,if(comments)commentsFocus else relatedFocus)
        if(comments)commentsPosition=position else relatedPosition=position
    }
    private fun planFocus(position:Position,inputGeneration:Int=a.focusInputGeneration) {
        restore.cancel();pendingFocus=FocusRequest(contentKey(),inputGeneration,position)
    }
    private fun restoreContentFocus() {
        val wanted=pendingFocus?:return
        if(closed || !visible || wanted.key!=contentKey() || wanted.inputGeneration!=a.focusInputGeneration){pendingFocus=null;return}
        if(busy || error!=null || list.adapter?.itemCount==0)return
        val tags=if(comments)commentRows.currentList.map{"youtube-comment:${it.id}"} else related.currentList.map{"youtube-video:${it.id}"}
        if(tags.isEmpty())return
        val first=wanted.position.first.coerceIn(tags.indices)
        val focused=tags.indexOfFirst{it==wanted.position.focusedTag}.takeIf{it>=0}?:first
        val tag=tags[focused];val layout=list.layoutManager as LinearLayoutManager
        pendingFocus=null;var positioned=false
        restore.start(waitForWindowFocus=true) {
            if(!positioned){positioned=true;layout.scrollToPositionWithOffset(first,wanted.position.offset);null}
            else list.findTag(tag)?:run{layout.scrollToPositionWithOffset(focused,0);null}
        }
    }
    private fun currentPage()=if(thread==null)commentsPage else repliesPage
    private fun setPage(page:YouTubeCommentsPage) { if(thread==null)commentsPage=page else repliesPage=page }
    private fun bindList() {
        val parent=view.parent as? View
        val width=parent?.width?.takeIf{it>0}?:a.resources.displayMetrics.widthPixels
        val height=parent?.height?.takeIf{it>0}?:a.resources.displayMetrics.heightPixels
        val sideWidth=minOf(a.dp(360),(width*.44f).toInt())
        view.layoutParams=if(comments)FrameLayout.LayoutParams(sideWidth,-1,Gravity.END)
            else FrameLayout.LayoutParams(-1,minOf(a.dp(320),(height*.6f).toInt()),Gravity.BOTTOM)
        view.setPadding(a.dp(if(comments)14 else 28),a.dp(10),a.dp(if(comments)14 else 28),a.dp(16))
        heading.visibility=if(comments)View.GONE else View.VISIBLE
        commentHeading.visibility=if(comments)View.VISIBLE else View.GONE
        resized(if(comments)sideWidth else 0)
        val adapter=if(comments)commentRows else related
        val layout=if(comments)commentsLayout else relatedLayout
        if(list.adapter!==adapter) {
            // Distinct view types let the sheet reuse plain rows safely across tab switches.
            releaseCards();list.swapAdapter(null,true);list.layoutManager=layout;list.swapAdapter(adapter,true)
        }
    }
    private fun render() {
        if(closed || !visible)return
        relatedButton.isSelected=!comments;commentsButton.isSelected=comments
        backButton.visibility=if(thread!=null)View.VISIBLE else View.GONE
        heading.textIfChanged(thread?.let { "Replies to ${it.author}" }?:watch.title.name)
        commentHeading.textIfChanged(thread?.let { "Replies to ${it.author}" }?:watch.title.name)
        commentsButton.textIfChanged(details?.commentsLabel?.takeIf(String::isNotBlank)?:"Comments")
        progress.visibility=if(busy || opening)View.VISIBLE else View.INVISIBLE
        val page=currentPage()
        val text=error?:if(busy)"" else if(comments)page?.message.orEmpty() else if(details!=null && details!!.related.videos.isEmpty())"No related videos available." else ""
        status.textIfChanged(text);retry.visibility=if(error!=null)View.VISIBLE else View.GONE;feedback.visibility=if(text.isNotBlank())View.VISIBLE else View.GONE
        if(comments)commentRows.submitList(page?.comments.orEmpty(),::restoreContentFocus) else related.submitList(details?.related?.videos.orEmpty(),::restoreContentFocus)
        more.visibility=if(comments && page?.continuation!=null && page.comments.size<120)View.VISIBLE else View.GONE
        more.isEnabled=!busy;more.nextFocusUpId=list.getChildAt(list.childCount-1)?.id?:commentsButton.id
    }
    private fun load(append:Boolean=false) {
        if(closed || !visible || busy)return
        if(!append && details!=null && (!comments || currentPage()!=null))return
        val moveToNewComments=append && more.hasFocus();val inputAt=a.focusInputGeneration
        if(retry.hasFocus())planFocus(savedPosition())
        val version=++generation;busy=true;error=null;render()
        request=a.lifecycleScope.launch {
            try {
                if(details==null)details=model.watchDetails(watch.title.id)
                if(comments && (append || currentPage()==null)) {
                    val previous=currentPage()
                    val token=if(append)previous?.continuation else thread?.repliesToken?:details?.commentsToken
                    val page=if(token==null)YouTubeCommentsPage(emptyList(),message="Comments unavailable for this video.") else model.client.comments(token)
                    if(version!=generation)return@launch
                    val items=if(append)(previous!!.comments+page.comments).distinctBy{it.id}.take(120) else page.comments
                    setPage(page.copy(comments=items,continuation=page.continuation?.takeIf { it!=token && items.size<120 }))
                    if(moveToNewComments && previous!=null && items.size>previous.comments.size)
                        planFocus(Position(previous.comments.size,focusedTag="youtube-comment:${items[previous.comments.size].id}"),inputAt)
                }
            } catch(e:CancellationException){throw e}
            catch(e:Exception){if(version==generation)error=(e as? UserMessage)?.message?:"Couldn't load this. Try again."}
            finally { if(version==generation){busy=false;render()} }
        }
    }
    private fun play(video:YouTubeVideo) {
        if(opening || closed)return
        opening=true;render()
        model.playRelated(video,details?.related?.videos.orEmpty()){if(!closed){opening=false;render()}}
    }
    private fun readComment(comment:YouTubeComment) {
        rememberPosition()
        val builder=AlertDialog.Builder(a).setTitle(comment.author).setMessage(comment.body).setNegativeButton("Close",null)
        if(comment.repliesToken!=null)builder.setPositiveButton(comment.repliesLabel.ifBlank{"View replies"}){_,_->openReplies(comment)}
        commentDialog=builder.create().also { dialog->dialog.setOnDismissListener { commentDialog=null };dialog.setOnShowListener { a.styleDialog(dialog) };dialog.show() }
    }
    private fun openReplies(comment:YouTubeComment) {
        if(closed || !visible || comment.repliesToken==null)return
        rememberPosition();cancelRequest();thread=comment;repliesPage=null;error=null
        planFocus(Position());bindList();render();load();backButton.requestFocus()
    }
    private fun backThread() { cancelRequest();thread=null;repliesPage=null;error=null;planFocus(commentsPosition);bindList();render();commentsButton.requestFocus() }
    private fun cancelRequest(){request?.cancel();request=null;generation++;busy=false;restore.cancel();pendingFocus=null}
    private fun releaseCards(){for(i in 0 until list.childCount)(list.getChildAt(i) as? YouTubeCard)?.release()}
    fun dismiss():Boolean {
        if(!visible)return false
        if(thread!=null){backThread();return true}
        return hidePanel()
    }
    private fun hidePanel():Boolean {
        if(!visible)return false
        rememberPosition();cancelRequest();if(opening){model.cancelPlayback();opening=false}
        releaseCards();list.adapter=null;list.recycledViewPool.clear();view.visibility=View.GONE;resized(0);dismissed();return true
    }
    fun close(){closed=true;cancelRequest();videoActions?.close();videoActions=null;commentDialog?.dismiss();commentDialog=null;if(opening)model.cancelPlayback();releaseCards();list.adapter=null;list.recycledViewPool.clear();details=null;commentsPage=null;repliesPage=null
        if(view.viewTreeObserver.isAlive)view.viewTreeObserver.removeOnGlobalFocusChangeListener(focusChanged)
        resized(0)}
}

private class CommentRow(private val a:MainActivity):LinearLayout(a) {
    private val author=a.text("",13f,bold=true)
    private val body=a.text("",14f).apply { maxLines=4;ellipsize=TextUtils.TruncateAt.END }
    private val meta=a.text("",11f,MUTED)
    init {
        orientation=VERTICAL;isFocusable=true;isFocusableInTouchMode=true;isClickable=true;pad(12)
        if(android.os.Build.VERSION.SDK_INT>=26)defaultFocusHighlightEnabled=false
        background=StateListDrawable().apply { addState(intArrayOf(android.R.attr.state_focused),a.round(PANEL,FOCUS));addState(intArrayOf(),a.round((ACCENT and 0x00FFFFFF) or 0x22000000)) }
        layoutParams=RecyclerView.LayoutParams(-1,ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin=a.dp(8) }
        add(author);gap(5);add(body);gap(5);add(meta)
    }
    fun bind(comment:YouTubeComment,open:(YouTubeComment)->Unit,replies:(()->Unit)?=null,back:(()->Unit)?=null) {
        if(id==View.NO_ID)id=View.generateViewId()
        tag="youtube-comment:${comment.id}";author.textIfChanged(comment.author);body.textIfChanged(comment.body)
        meta.textIfChanged(listOf(comment.metadata,comment.repliesLabel).filter(String::isNotBlank).joinToString(" · "))
        contentDescription="${comment.author}. ${comment.body}. ${meta.text}";setOnClickListener{open(comment)}
        // Remote shortcuts keep reading a thread to one press without allocating
        // hidden reply views or loading any reply text until it is selected.
        setOnKeyListener { _,code,event ->
            val action=when(code){KeyEvent.KEYCODE_DPAD_RIGHT->replies;KeyEvent.KEYCODE_DPAD_LEFT->back;else->null}
            if(action==null)false else {
                if(event.action==KeyEvent.ACTION_DOWN && event.repeatCount==0)action()
                true
            }
        }
    }
    fun release(){setOnClickListener(null);setOnKeyListener(null);contentDescription=null;author.textIfChanged("");body.textIfChanged("");meta.textIfChanged("")}
}
