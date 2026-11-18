package xyz.billybishop4.fleet.tv

import android.app.AlertDialog
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** A single native sheet over the existing player. Comments and replies load only when opened. */
internal class YouTubeWatchPanel(
    private val a:MainActivity,private val watch:Watch,private val dismissed:()->Unit,
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
    val view=object:LinearLayout(a) {
        init { orientation=VERTICAL;tag="youtube-watch-panel";setBackgroundColor(0xF20A0A0A.toInt());setPadding(a.dp(28),a.dp(10),a.dp(28),a.dp(16));visibility=View.GONE }
        override fun focusSearch(focused:View,direction:Int):View? {
            val next=super.focusSearch(focused,direction)?:return focused
            var node:View?=next
            while(node!=null && node!==this)node=node.parent as? View
            return if(node===this)next else focused
        }
    }
    val visible get()=view.visibility==View.VISIBLE
    private val relatedButton=a.action("Related","Related videos tab"){select(false)}.quiet()
    private val commentsButton=a.action("Comments","Comments tab"){select(true)}.quiet()
    private val backButton=a.action("Back","Back to comments"){backThread()}.quiet().apply{visibility=View.GONE}
    private val heading=a.text(watch.title.name,13f,MUTED).apply { maxLines=1;ellipsize=TextUtils.TruncateAt.END }
    private val closeButton=a.action("Close","Close video panel"){hidePanel()}.quiet()
    private val progress=ProgressBar(a,null,android.R.attr.progressBarStyleHorizontal).apply {
        tag="youtube-panel-loading";isIndeterminate=true;visibility=View.INVISIBLE;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val status=a.text("",13f,MUTED).apply { tag="youtube-panel-status";maxLines=3 }
    private val retry=a.action("Retry","Retry video details"){load(append=currentPage()!=null)}.quiet()
    private val feedback=a.row().apply { addView(status,LinearLayout.LayoutParams(0,-2,1f));add(retry,width=-2);visibility=View.GONE }
    private val list=RecyclerView(a).apply { tag="youtube-panel-list";tune(cachedViews=1) }
    private val relatedLayout by lazy { LinearLayoutManager(a,RecyclerView.HORIZONTAL,false).apply{initialPrefetchItemCount=1} }
    private val commentsLayout by lazy { LinearLayoutManager(a,RecyclerView.VERTICAL,false).apply{initialPrefetchItemCount=1} }
    private val more=a.action("More comments","Load more comments"){load(append=true)}.quiet().apply { visibility=View.GONE }
    private val related=NativeAdapter<YouTubeVideo>({it.id},{parent,_->YouTubeCard(parent.context,a.artwork,false).apply {
        layoutParams=RecyclerView.LayoutParams(a.dp(220),a.dp(199)).apply { marginEnd=a.dp(8) }
    }},{v,value,_->(v as YouTubeCard).bind(value,::play)},type={101},recycle={(it as YouTubeCard).release()})
    private val commentRows=NativeAdapter<YouTubeComment>({it.id},{_,_->CommentRow(a)},
        {v,value,_->(v as CommentRow).bind(value,::readComment)},type={102},recycle={(it as CommentRow).release()})
    init {
        view.add(a.row().apply {
            add(relatedButton,width=a.dp(92),height=a.dp(40));add(commentsButton,width=a.dp(118),height=a.dp(40))
            add(backButton,width=a.dp(76),height=a.dp(40))
            addView(heading,LinearLayout.LayoutParams(0,-2,1f).apply { marginStart=a.dp(12);marginEnd=a.dp(12) })
            add(closeButton,width=a.dp(78),height=a.dp(40))
        },height=a.dp(44))
        view.add(progress,height=a.dp(2));view.add(feedback)
        view.addView(list,LinearLayout.LayoutParams(-1,0,1f));view.add(more,width=a.dp(164),height=a.dp(36))
        relatedButton.nextFocusLeftId=relatedButton.id;closeButton.nextFocusRightId=closeButton.id
        relatedButton.nextFocusRightId=commentsButton.id;commentsButton.nextFocusLeftId=relatedButton.id
        list.setOnKeyListener { _,key,event ->
            if(event.action==android.view.KeyEvent.ACTION_DOWN && key==android.view.KeyEvent.KEYCODE_DPAD_UP && !comments)relatedButton.requestFocus() else false
        }
        list.recycledViewPool.setMaxRecycledViews(101,2);list.recycledViewPool.setMaxRecycledViews(102,3)
    }
    fun show(showComments:Boolean=false) {
        if(closed)return
        view.visibility=View.VISIBLE;comments=showComments;thread=null;repliesPage=null
        bindList();render();load();if(comments)commentsButton.requestFocus() else relatedButton.requestFocus()
    }
    private fun select(value:Boolean) {
        if(comments==value && thread==null)return
        cancelRequest();comments=value;thread=null;repliesPage=null;error=null;bindList();render();load()
    }
    private fun currentPage()=if(thread==null)commentsPage else repliesPage
    private fun setPage(page:YouTubeCommentsPage) { if(thread==null)commentsPage=page else repliesPage=page }
    private fun bindList() {
        val adapter=if(comments)commentRows else related
        val layout=if(comments)commentsLayout else relatedLayout
        if(list.adapter!==adapter) {
            // Distinct view types let the sheet reuse plain rows safely across tab switches.
            releaseCards();list.swapAdapter(null,true);list.layoutManager=layout;list.swapAdapter(adapter,true)
        }
        layout.scrollToPositionWithOffset(0,0)
    }
    private fun render() {
        if(closed || !visible)return
        relatedButton.isSelected=!comments;commentsButton.isSelected=comments
        backButton.visibility=if(thread!=null)View.VISIBLE else View.GONE
        heading.textIfChanged(thread?.let { "Replies to ${it.author}" }?:watch.title.name)
        progress.visibility=if(busy || opening)View.VISIBLE else View.INVISIBLE
        val page=currentPage()
        val text=error?:if(busy)"" else if(comments)page?.message.orEmpty() else if(details!=null && details!!.related.videos.isEmpty())"No related videos available." else ""
        status.textIfChanged(text);retry.visibility=if(error!=null)View.VISIBLE else View.GONE;feedback.visibility=if(text.isNotBlank())View.VISIBLE else View.GONE
        if(comments)commentRows.submitList(page?.comments.orEmpty()) else related.submitList(details?.related?.videos.orEmpty())
        more.visibility=if(comments && page?.continuation!=null && page.comments.size<120)View.VISIBLE else View.GONE
        more.isEnabled=!busy;more.nextFocusUpId=list.getChildAt(list.childCount-1)?.id?:commentsButton.id
    }
    private fun load(append:Boolean=false) {
        if(closed || !visible || busy)return
        if(!append && details!=null && (!comments || currentPage()!=null))return
        val version=++generation;busy=true;error=null;render()
        request=a.lifecycleScope.launch {
            try {
                if(details==null)details=model.client.watchDetails(watch.title.id)
                if(comments && (append || currentPage()==null)) {
                    val previous=currentPage()
                    val token=if(append)previous?.continuation else thread?.repliesToken?:details?.commentsToken
                    val page=if(token==null)YouTubeCommentsPage(emptyList(),message="Comments unavailable for this video.") else model.client.comments(token)
                    if(version!=generation)return@launch
                    val items=if(append)(previous!!.comments+page.comments).distinctBy{it.id}.take(120) else page.comments
                    setPage(page.copy(comments=items,continuation=page.continuation?.takeIf { it!=token && items.size<120 }))
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
        val builder=AlertDialog.Builder(a).setTitle(comment.author).setMessage(comment.body).setNegativeButton("Close",null)
        if(comment.repliesToken!=null)builder.setPositiveButton(comment.repliesLabel.ifBlank{"View replies"}){_,_->
            cancelRequest();thread=comment;repliesPage=null;bindList();render();load();backButton.requestFocus()
        }
        commentDialog=builder.create().also { dialog->dialog.setOnDismissListener { commentDialog=null };dialog.show() }
    }
    private fun backThread() { cancelRequest();thread=null;repliesPage=null;error=null;bindList();render();commentsButton.requestFocus() }
    private fun cancelRequest(){request?.cancel();request=null;generation++;busy=false}
    private fun releaseCards(){for(i in 0 until list.childCount)(list.getChildAt(i) as? YouTubeCard)?.release()}
    fun dismiss():Boolean {
        if(!visible)return false
        if(thread!=null){backThread();return true}
        return hidePanel()
    }
    private fun hidePanel():Boolean {
        if(!visible)return false
        cancelRequest();if(opening){model.cancelPlayback();opening=false}
        releaseCards();list.adapter=null;list.recycledViewPool.clear();view.visibility=View.GONE;dismissed();return true
    }
    fun close(){closed=true;cancelRequest();commentDialog?.dismiss();commentDialog=null;if(opening)model.cancelPlayback();releaseCards();list.adapter=null;list.recycledViewPool.clear();details=null;commentsPage=null;repliesPage=null}
}

private class CommentRow(private val a:MainActivity):LinearLayout(a) {
    private val author=a.text("",13f,bold=true)
    private val body=a.text("",14f).apply { maxLines=4;ellipsize=TextUtils.TruncateAt.END }
    private val meta=a.text("",11f,MUTED)
    init {
        orientation=VERTICAL;isFocusable=true;isFocusableInTouchMode=true;isClickable=true;pad(12)
        if(android.os.Build.VERSION.SDK_INT>=26)defaultFocusHighlightEnabled=false
        background=StateListDrawable().apply { addState(intArrayOf(android.R.attr.state_focused),a.round(PANEL,WHITE));addState(intArrayOf(),a.round(0x22FFFFFF)) }
        layoutParams=RecyclerView.LayoutParams(-1,ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin=a.dp(8) }
        add(author);gap(5);add(body);gap(5);add(meta)
    }
    fun bind(comment:YouTubeComment,open:(YouTubeComment)->Unit) {
        if(id==View.NO_ID)id=View.generateViewId()
        tag="youtube-comment:${comment.id}";author.textIfChanged(comment.author);body.textIfChanged(comment.body)
        meta.textIfChanged(listOf(comment.metadata,comment.repliesLabel).filter(String::isNotBlank).joinToString(" · "))
        contentDescription="${comment.author}. ${comment.body}. ${meta.text}";setOnClickListener{open(comment)}
    }
    fun release(){setOnClickListener(null);contentDescription=null;author.textIfChanged("");body.textIfChanged("");meta.textIfChanged("")}
}
