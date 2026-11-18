package xyz.billybishop4.fleet.tv

import org.json.JSONObject

internal data class YouTubeHeatMarker(val startMs:Long,val durationMs:Long,val intensity:Float)
internal data class YouTubeChapter(val title:String,val startMs:Long)
internal data class YouTubeWatchDetails(val related:YouTubePage,val commentsToken:String?,val commentsLabel:String="Comments",
    val channel:YouTubeChannel?=null,val rating:YouTubeRating?=null,val chapters:List<YouTubeChapter> = emptyList())
internal data class YouTubeComment(
    val id:String,val author:String,val body:String,val metadata:String,
    val repliesLabel:String="",val repliesToken:String?=null,
)
internal data class YouTubeCommentsPage(val comments:List<YouTubeComment>,val continuation:String?=null,val message:String="")

/** Watch responses are reduced to bounded, plain data; no renderers or account payloads enter the view tree. */
internal object YouTubeWatchDecode {
    private val chapterNumber=Regex("[0-9]{1,12}")
    private val chapterWhitespace=Regex("\\s+")
    private fun token(root:Any?):String? {
        val command=YouTubeDecode.find(root,setOf("nextContinuationData","reloadContinuationData","continuationCommand"),1).firstOrNull() as? JSONObject
        return command?.optString("continuation")?.ifBlank { command.optString("token") }
            ?.takeIf { it.isNotBlank() && it.length<=20_000 }
    }
    fun details(j:JSONObject,videoId:String):YouTubeWatchDetails {
        val panel=YouTubeDecode.find(j,setOf("engagementPanelSectionListRenderer"),20).filterIsInstance<JSONObject>()
            .firstOrNull { it.optString("panelIdentifier")=="comment-item-section" }
        val header=panel?.optJSONObject("header")?.optJSONObject("engagementPanelTitleHeaderRenderer")
        val label=YouTubeDecode.text(header?.opt("title")).ifBlank { "Comments" }
        val menu=header?.optJSONObject("menu")?.optJSONObject("sortFilterSubMenuRenderer")?.optJSONArray("subMenuItems")
        val comments=token(menu?.optJSONObject(0)?.opt("continuation"))?:token(panel?.opt("content"))
        val pivot=j.optJSONObject("contents")?.optJSONObject("singleColumnWatchNextResults")?.opt("pivot")
        val page=YouTubeDecode.page(if(pivot is JSONObject)pivot else j)
        return YouTubeWatchDetails(page.copy(videos=page.videos.filter { it.id!=videoId }.take(40)),comments,label,
            YouTubeSocialDecode.watchChannel(j),YouTubeSocialDecode.rating(j),chapters(j,videoId))
    }
    /** Real chapter timestamps from the existing watch-next response. Chapter
     * artwork/commands are discarded, and an unrelated watch endpoint is rejected. */
    internal fun chapters(j:JSONObject,videoId:String):List<YouTubeChapter> {
        fun millis(value:Any?,scale:Long):Long? {
            val number=value?.toString()?.takeIf{it.matches(chapterNumber)}?.toLongOrNull()?:return null
            return number.takeIf{it<=604_800_000L/scale}?.times(scale)
        }
        fun chapter(node:JSONObject,start:Long?):YouTubeChapter? {
            val title=YouTubeDecode.text(node.opt("title")).replace(chapterWhitespace," ").trim().take(160)
            return if(start==null || title.isBlank())null else YouTubeChapter(title,start)
        }
        val legacy=YouTubeDecode.find(j.opt("playerOverlays"),setOf("chapterRenderer"),160).filterIsInstance<JSONObject>()
            .mapNotNull{chapter(it,millis(it.opt("timeRangeStartMillis"),1))}
        val panels=YouTubeDecode.find(j.opt("engagementPanels"),setOf("engagementPanelSectionListRenderer"),20)
            .filterIsInstance<JSONObject>().filter{it.optString("panelIdentifier").endsWith("-chapters")}
        val items=panels.asSequence().flatMap{YouTubeDecode.find(it.opt("content"),setOf("macroMarkersListItemRenderer"),160).asSequence()}
            .take(160).filterIsInstance<JSONObject>().mapNotNull { node->
                val endpoint=YouTubeDecode.find(node.opt("onTap"),setOf("watchEndpoint"),1).firstOrNull() as? JSONObject
                if(endpoint?.optString("videoId")!=videoId)null else chapter(node,millis(endpoint.opt("startTimeSeconds"),1000))
            }.toList()
        return (legacy+items).distinctBy{it.startMs}.sortedBy{it.startMs}.take(80)
    }
    private val replyCount=Regex("[0-9][0-9, .\\u00A0\\u202F]*[KMB]?",RegexOption.IGNORE_CASE)
    private fun repliesLabel(value:Any?):String {
        val label=YouTubeDecode.text(value).trim()
        // TV responses sometimes send only a count; keep already localized labels intact.
        if(!label.matches(replyCount))return label
        return "$label "+if(label.toLongOrNull()==1L)"reply" else "replies"
    }
    fun comments(j:JSONObject):YouTubeCommentsPage {
        val section=j.optJSONObject("continuationContents")?.optJSONObject("itemSectionContinuation")
        // A comment contains reply continuations. Only the section continuation advances the top-level page.
        val next=token(section?.opt("continuations"))
        val comments=YouTubeDecode.find(section?.opt("contents")?:j,setOf("commentRenderer"),80).filterIsInstance<JSONObject>()
            .mapNotNull { r ->
                val id=r.optString("commentId").take(256)
                val body=YouTubeDecode.text(r.opt("contentText"))
                if(id.isBlank() || body.isBlank())null else YouTubeComment(id,YouTubeDecode.text(r.opt("authorText")),body,
                    listOf(YouTubeDecode.text(r.opt("publishedTimeText")),YouTubeDecode.text(r.opt("voteCount")).takeIf(String::isNotBlank)?.let { "$it likes" }.orEmpty())
                        .filter(String::isNotBlank).joinToString(" · "),
                    repliesLabel(r.opt("repliesCount")),token(r.opt("detailViewEndpoint")))
            }.distinctBy { it.id }.take(40)
        val message=if(comments.isEmpty())YouTubeDecode.find(j,setOf("messageRenderer"),1).filterIsInstance<JSONObject>()
            .firstOrNull()?.let { YouTubeDecode.text(it.opt("text")) }.orEmpty().ifBlank { "No comments available." } else ""
        return YouTubeCommentsPage(comments,next,message)
    }
    fun heatmap(j:JSONObject):List<YouTubeHeatMarker> {
        // Current WEB responses carry entity mutations. Older responses use heatMarkerRenderer.
        val lists=YouTubeDecode.find(j.opt("frameworkUpdates"),setOf("markersList"),20).filterIsInstance<JSONObject>()
            .filter { it.optString("markerType")=="MARKER_TYPE_HEATMAP" }
        val modern=lists.flatMap { list -> list.optJSONArray("markers")?.objects().orEmpty() }.take(512)
        val legacy=if(modern.isEmpty())YouTubeDecode.find(j,setOf("heatMarkerRenderer"),512).filterIsInstance<JSONObject>() else emptyList()
        return (modern+legacy).mapNotNull { marker ->
            val start=marker.optLong("startMillis",marker.optLong("timeRangeStartMillis",-1))
            val duration=marker.optLong("durationMillis",marker.optLong("markerDurationMillis",0))
            val score=marker.optDouble("intensityScoreNormalized",marker.optDouble("heatMarkerIntensityScoreNormalized",Double.NaN))
            if(start !in 0..604_800_000L || duration !in 1..604_800_000L || !score.isFinite() || score !in 0.0..1.0)null
            else YouTubeHeatMarker(start,duration,score.toFloat())
        }.distinctBy { it.startMs }.sortedBy { it.startMs }.take(256)
    }
}
