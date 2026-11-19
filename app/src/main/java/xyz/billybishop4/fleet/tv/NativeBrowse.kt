package xyz.billybishop4.fleet.tv

import android.graphics.Color
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.Parcelable
import android.os.SystemClock
import android.text.TextUtils
import android.view.*
import android.widget.*
import androidx.recyclerview.widget.*
import coil.dispose

/** Match manifest routing without treating a successful empty response as a missing capability. */
internal class BrowseAddonCapabilities(val addons:List<Addon>) {
    private val enabled=addons.filter { it.enabled }
    val anyEnabled=enabled.isNotEmpty()
    val home=enabled.any { catalog(it,search=false) }
    val search=enabled.any { catalog(it,search=true) }
    fun metadata(title:Title)=enabled.any { supports(it,"meta",title.type,title.id) }
    fun streams(title:Title,episode:Episode?)=enabled.any { supports(it,"stream",title.type,episode?.id?:title.id) }
    private fun strings(array:org.json.JSONArray?):List<String> = if(array==null)emptyList() else (0 until array.length()).mapNotNull { array.opt(it) as? String }
    private fun supports(addon:Addon,resource:String,type:String,id:String):Boolean {
        val manifest=addon.document?:return true // Let an uncached manifest load or report its normal fetch error.
        val resources=manifest.optJSONArray("resources")?:return false
        for(i in 0 until resources.length()) {
            val entry=resources.opt(i);val full=entry as? org.json.JSONObject
            if((if(entry is String)entry else full?.text("name"))!=resource)continue
            if(type !in strings(full?.optJSONArray("types")?:manifest.optJSONArray("types")))continue
            val prefixes=if(full!=null)full.optJSONArray("idPrefixes") else manifest.optJSONArray("idPrefixes")
            if(resource=="catalog" || prefixes==null || strings(prefixes).any { id.startsWith(it) })return true
        }
        return false
    }
    private fun catalog(addon:Addon,search:Boolean):Boolean {
        val manifest=addon.document?:return true
        return manifest.optJSONArray("catalogs")?.objects().orEmpty().any { definition ->
            val type=definition.text("type");val id=definition.text("id")
            val extras=definition.optJSONArray("extra")?.objects().orEmpty()
            val supported=strings(definition.optJSONArray("extraSupported"))+extras.map { it.text("name") }
            val required=strings(definition.optJSONArray("extraRequired"))+extras.filter { it.optBoolean("isRequired") }.map { it.text("name") }
            type in listOf("movie","series") && id.isNotBlank() && supports(addon,"catalog",type,id) &&
                (if(search)"search" in supported && required.all { it=="search" } else required.isEmpty())
        }
    }
}
internal fun addonRecovery(m:FleetModel,capabilities:BrowseAddonCapabilities,available:Boolean,purpose:String):String?=when {
    m.addonStorageError -> "Saved add-ons couldn't be read. Manage add-ons to restore the defaults."
    !capabilities.anyEnabled -> "No add-ons are enabled. Enable or install add-ons to $purpose."
    !available -> "Your enabled add-ons can't $purpose. Enable or install an add-on that supports it."
    else -> null
}

internal data class ProfileRow(val provider:Provider,val default:Boolean)

internal class ProfilesScreen(private val a:MainActivity):NativeScreen {
    private val m=a.model
    override val view=a.column()
    private val add=a.action("Add profile",prominent=true) { m.open(Route.AddProvider) }
    private val emptyText=a.text("Add a Stalker portal using its URL and registered MAC address.",14f,MUTED)
    private val repair=a.action("Open settings") { m.open(Route.Settings) }.quiet()
    private val empty=a.column().apply { add(emptyText);gap(12);add(repair,width=-2) }
    private val list=TvRecyclerView(a).apply { layoutManager=LinearLayoutManager(a);tune();tag="profiles" }
    private var restored:Parcelable?=null
    private val focusRestore=BrowseFocusRestore(a,view)
    private val adapter=NativeAdapter<ProfileRow>({it.provider.id},
        { _,_->ProfileCard(a,{p->m.open(if(p.kind=="stalker")Route.Channels(p) else Route.EditProvider(p))},::options) },
        { v,row,_->(v as ProfileCard).bind(row) })
    init {
        view.add(a.row().apply {
            addView(a.text("IPTV profiles",25f,bold=true),LinearLayout.LayoutParams(0,-2,1f))
            add(add,width=-2)
        });view.gap(8);view.add(a.text("Your default profile opens with Live TV.",13f,MUTED));view.gap(18)
        view.add(empty);list.adapter=adapter;view.addView(list,LinearLayout.LayoutParams(-1,0,1f))
    }
    private fun options(p:Provider) {
        val actions=ArrayList<Pair<String,()->Unit>>()
        if(p.kind=="stalker" && p.id!=m.defaultProfile?.id)actions+="Set as default" to { m.makeDefaultProfile(p.id) }
        actions+="Edit profile" to { m.open(Route.EditProvider(p)) }
        actions+="Delete profile" to { a.confirm("Delete "+p.name+" and its saved credentials?","Delete profile") { m.removeProvider(p.id) } }
        a.showActions(p.name,actions)
    }
    override fun update() {
        val broken=m.providerStorageError
        add.visibility=if(broken)View.GONE else View.VISIBLE
        empty.visibility=if(m.providers.isEmpty() || broken)View.VISIBLE else View.GONE
        repair.visibility=if(broken)View.VISIBLE else View.GONE
        emptyText.text=if(broken)"Saved profiles could not be opened. Reset provider storage in Settings → Data & storage." else "Add a Stalker portal using its URL and registered MAC address."
        adapter.submitList(m.providers.map { ProfileRow(it,it.id==m.defaultProfile?.id) }) {
            restored?.let { list.layoutManager?.onRestoreInstanceState(it);restored=null }
        }
    }
    override fun focusTarget():View? = if(m.providerStorageError)repair else if(m.providers.isEmpty())add else {
        val preferred=m.defaultProfile?.id
        (0 until list.childCount).map { list.getChildAt(it) as ProfileCard }.firstOrNull { it.profileId==preferred }?.primary
            ?: (list.getChildAt(0) as? ProfileCard)?.primary
    }
    override fun restoreFocus(tag:String,parentTag:String?):Boolean {
        if(!tag.startsWith("profile:") && !tag.startsWith("profile-options:"))return false
        val id=tag.substringAfter(':')
        return focusRestore.start {
            val index=adapter.currentList.indexOfFirst { it.provider.id==id }
            if(index<0)focusTarget() else list.findTag(tag).also { if(it==null)list.scrollToPosition(index) }
        }
    }
    override fun saveState()=list.layoutManager?.onSaveInstanceState()
    override fun restoreState(state:Parcelable?) { restored=state }
    override fun close() { focusRestore.cancel();list.adapter=null }
}

private class ProfileCard(a:MainActivity,open:(Provider)->Unit,options:(Provider)->Unit):LinearLayout(a) {
    private var provider:Provider?=null
    var profileId="";private set
    private val name=a.text("",16f,bold=true).apply { maxLines=1;ellipsize=TextUtils.TruncateAt.END }
    private val subtitle=a.text("",12f,MUTED)
    val primary=a.column().apply {
        gravity=Gravity.CENTER_VERTICAL;pad(12);isFocusable=true;isFocusableInTouchMode=true;isClickable=true
        background=StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused),a.round(RAISED,FOCUS))
            addState(intArrayOf(),a.round(Color.TRANSPARENT))
        }
        add(name);gap(4);add(subtitle)
        setOnClickListener { provider?.let(open) }
    }
    private val more=a.action("⋯") { provider?.let(options) }.quiet()
    init {
        orientation=HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;background=a.round(PANEL)
        layoutParams=RecyclerView.LayoutParams(-1,a.dp(76)).apply { bottomMargin=a.dp(10) }
        addView(primary,LayoutParams(0,-1,1f));addView(more,LayoutParams(a.dp(48),a.dp(48)).apply { marginEnd=a.dp(8) })
    }
    fun bind(row:ProfileRow) {
        provider=row.provider;profileId=row.provider.id;name.text=row.provider.name
        primary.tag="profile:$profileId";primary.contentDescription=row.provider.name
        subtitle.text=if(row.default)"Default profile" else if(row.provider.kind!="stalker")"Setup required" else ""
        subtitle.visibility=if(subtitle.text.isEmpty())View.GONE else View.VISIBLE
        more.contentDescription="Options for "+row.provider.name;more.tag="profile-options:$profileId"
    }
}

internal class SourcesScreen(private val a:MainActivity,private val route:Route.Sources):NativeScreen {
    override val view=a.column()
    private val choices=StreamChoices(a,showErrors=false) { stream ->
        a.model.open(Route.Player(Watch(route.title,stream,route.episode,headers=stream.headers)))
    }
    init {
        view.add(a.text(route.title.name,23f,bold=true).apply { tag="source-title";maxLines=2;ellipsize=TextUtils.TruncateAt.END })
        view.gap(5)
        val context=route.episode?.let { episode ->
            listOf(if(episode.season==0)"Specials" else "Season "+episode.season,"Episode "+episode.number,episode.name).filter { it.isNotBlank() }.joinToString(" · ")
        } ?: listOf(if(route.title.type=="series")"Show" else "Movie",route.title.year).filter { it.isNotBlank() }.joinToString(" · ")
        view.add(a.text(context,13f,MUTED).apply { tag="source-context";maxLines=2;ellipsize=TextUtils.TruncateAt.END })
        view.gap(12);view.add(choices.view)
    }
    override fun update()=choices.update(route.title,route.episode)
    override fun focusTarget():View?=choices.focusTarget()
    override fun restoreFocus(tag:String,parentTag:String?)=choices.restoreFocus(tag,parentTag)
    override fun saveState():Parcelable=choices.saveState()
    override fun restoreState(state:Parcelable?)=choices.restoreState(state)
    override fun close()=choices.close()
}

/** The same explicit source choices appear below movie details and selected episode context. */
private class StreamChoices(private val a:MainActivity,private val showErrors:Boolean,play:(Stream)->Unit) {
    private val m=a.model
    val view=a.column().apply { tag="stream-choices" }
    private var capabilities=BrowseAddonCapabilities(m.addons)
    private val tileHeight=maxOf(a.dp(144),android.text.TextPaint().let { paint ->
        fun lines(size:Float,count:Int):Int {
            paint.textSize=android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP,size,a.resources.displayMetrics)
            return kotlin.math.ceil((paint.fontMetrics.descent-paint.fontMetrics.ascent)*count).toInt()
        }
        lines(16f,2)+lines(13f,2)+lines(12f,1)+a.dp(34)
    })
    private val list=TvRecyclerView(a).apply {
        layoutManager=LinearLayoutManager(a,RecyclerView.HORIZONTAL,false).apply{initialPrefetchItemCount=4};tune(cachedViews=4);tag="streams"
        setPadding(a.dp(2),a.dp(4),a.dp(2),a.dp(4))
    }
    private val emptyText=a.text("",15f,MUTED)
    private var focusFirstGeneration:Int?=null
    private val refresh=a.action("Refresh streams") { focusFirstGeneration=a.focusInputGeneration;m.retryStreams() }.quiet()
    private val retry=a.action("Retry streams") { focusFirstGeneration=a.focusInputGeneration;m.retryStreams() }.quiet()
    private val streamError=a.text("",13f,ERROR_TEXT).apply { tag="streams-error";maxLines=3;ellipsize=TextUtils.TruncateAt.END }
    private val failure=a.row().apply {
        addView(streamError,LinearLayout.LayoutParams(0,-2,1f).apply { marginEnd=a.dp(12) });add(retry,width=-2)
    }
    private val loading=a.text("Finding streams…",14f,MUTED).apply { tag="streams-loading" }
    private val manageAddons=a.action("Manage add-ons") { m.open(Route.Addons) }
    private val empty=a.column().apply { add(emptyText);gap(10);add(refresh,width=-2);add(manageAddons,width=-2) }
    private val detailsHint=a.text("Hold OK for stream details",12f,MUTED)
    private var restored:Parcelable?=null
    private var contextKey:String?=null
    private var submittedStreams:List<Stream> = emptyList()
    private var submittedModelStreams:List<Stream>?=null
    // Only the current source list is prepared/indexed. Unrelated model renders
    // must not sort again or restart an identical diff already in flight.
    private var preparedModelStreams:List<Stream>?=null
    private var preparedLargestFirst:Boolean?=null
    private var preparedWaiting:Boolean?=null
    private var submittedRevision=0
    private var committedRevision=0
    private var committedItems:List<Stream>?=null
    private val sourceIndices=HashMap<String,Int>()
    private val semanticLabels=HashMap<String,String>()
    private var semanticIndices:Map<String,Int>?=null
    private var closed=false
    private var restoredIndex:Int?=null
    private var restoredLabel:String?=null
    private var rememberedIndex:Int?=null
    private var rememberedLabel:String?=null
    private var rememberedContext:String?=null
    private val focusRestore=BrowseFocusRestore(a,view)
    private val adapter:NativeAdapter<Stream> = NativeAdapter({it.identity},
        { _,_->StreamContainer(a,tileHeight,{ stream ->
            rememberedIndex=sourceIndices[stream.identity]
            rememberedLabel=semanticLabel(stream);rememberedContext=streamContext(stream)
        }) { stream ->
            val detail=m.route as? Route.Detail
            val current=detail==null || detail.title.type!="series" || contextKey==detail.title.key+"/"+m.selectedEpisode?.id.orEmpty()
            if(current && !m.streamsBusy && m.streams.any { it.identity==stream.identity })play(stream)
        } },
        { v,s,_->(v as StreamContainer).bind(s) })
    init {
        failure.visibility=View.GONE;empty.visibility=View.GONE;list.visibility=View.GONE;detailsHint.visibility=View.GONE
        list.adapter=adapter;committedItems=adapter.currentList
        val status=a.column().apply { add(loading);add(failure);add(empty) }
        view.add(FrameLayout(a).apply {
            tag="stream-viewport"
            addView(list,FrameLayout.LayoutParams(-1,-1))
            addView(status,FrameLayout.LayoutParams(-1,-2,Gravity.TOP))
        },height=tileHeight+a.dp(8))
        view.gap(8);view.add(detailsHint)
    }
    fun update(title:Title,episode:Episode?) {
        if(closed)return
        val nextContext=title.key+"/"+episode?.id.orEmpty()
        if(contextKey!=null && contextKey!=nextContext) {
            focusRestore.cancel();focusFirstGeneration=null;restored=null;restoredIndex=null;restoredLabel=null
            rememberedIndex=null;rememberedLabel=null;rememberedContext=null
            list.stopScroll();list.scrollToPosition(0)
        }
        contextKey=nextContext
        val waitingForEpisode=title.type=="series" && episode==null
        if(capabilities.addons!==m.addons)capabilities=BrowseAddonCapabilities(m.addons)
        val recovery=if(waitingForEpisode)null else addonRecovery(m,capabilities,capabilities.streams(title,episode),"provide stream links for this title")
        submittedModelStreams=m.streams
        if(preparedModelStreams!==m.streams || preparedLargestFirst!=m.preferences.largestFirst || preparedWaiting!=waitingForEpisode) {
            preparedModelStreams=m.streams;preparedLargestFirst=m.preferences.largestFirst;preparedWaiting=waitingForEpisode
            val unique=if(waitingForEpisode)emptyList() else m.streams.distinctBy { it.identity }
            val next=if(m.preferences.largestFirst)unique else unique.sortedWith(compareBy<Stream>{it.sizeBytes==null}.thenBy{it.sizeBytes})
            if(next!=submittedStreams) {
                submittedStreams=next;val revision=++submittedRevision
                adapter.submitList(next) {
                    if(!closed && submittedRevision==revision) {
                        // Old visible holders can still gain focus while the diff
                        // runs. Keep their committed ordinals until replacement
                        // is accepted, including renewed URLs/headers and labels.
                        sourceIndices.clear();semanticLabels.clear();semanticIndices=null
                        adapter.currentList.forEachIndexed{index,stream->sourceIndices[stream.identity]=index}
                        committedRevision=revision;committedItems=adapter.currentList;restoreCommittedState()
                    }
                }
            }
        }
        restoreCommittedState()
        val streams=submittedStreams
        loading.textIfChanged(if(waitingForEpisode)if(m.busy)"Loading episodes…" else "Select an episode" else "Finding streams…")
        loading.visibility=if(waitingForEpisode || m.streamsBusy && streams.isEmpty() && recovery==null)View.VISIBLE else View.GONE
        streamError.textIfChanged(m.streamsError.orEmpty())
        failure.visibility=if(!waitingForEpisode && showErrors && !m.streamsBusy && m.streamsError!=null && recovery==null)View.VISIBLE else View.GONE
        val needsManagement=recovery!=null || m.streamsError!=null
        empty.visibility=if(!waitingForEpisode && (recovery!=null || !m.streamsBusy && streams.isEmpty()))View.VISIBLE else View.GONE
        emptyText.textIfChanged(recovery?:if(m.streamsError!=null)"Try another add-on." else "No stream links were returned for this title. Try refreshing or check your add-ons in Settings.")
        refresh.visibility=if(needsManagement)View.GONE else View.VISIBLE
        manageAddons.visibility=if(needsManagement)View.VISIBLE else View.GONE
        list.visibility=if(streams.isEmpty() || recovery!=null)View.GONE else View.VISIBLE
        detailsHint.visibility=if(list.visibility==View.VISIBLE)View.VISIBLE else View.INVISIBLE
    }
    private fun restoreCommittedState() {
        if(!streamsCommitted() || submittedStreams.isEmpty())return
        restored?.let { list.layoutManager?.onRestoreInstanceState(it);restored=null }
        focusFirstGeneration?.let { generation ->
            focusFirstGeneration=null
            if(generation==a.focusInputGeneration)focusRestore.start { sourceAt(0) }
        }
    }
    private fun semanticLabel(stream:Stream):String=if(sourceIndices.containsKey(stream.identity))
        semanticLabels.getOrPut(stream.identity){streamSemantic(stream)} else streamSemantic(stream)
    private fun streamsCommitted():Boolean = !m.streamsBusy && submittedModelStreams===m.streams &&
        // currentList is an unmodifiable wrapper, so retain the exact committed
        // wrapper as well as its revision instead of rescanning it every frame.
        committedRevision==submittedRevision && committedItems===adapter.currentList
    private fun sourceAt(index:Int):View? {
        if(!streamsCommitted() || list.isComputingLayout || list.hasPendingAdapterUpdates() || list.isLayoutRequested)return null
        val stream=adapter.currentList.getOrNull(index)?:return null
        val holder=list.findViewHolderForAdapterPosition(index)
        val container=holder?.itemView as? StreamContainer
        if(container==null) { list.scrollToPosition(index);return null }
        // A diff can finish before RecyclerView replaces its old attached children.
        if(holder?.bindingAdapterPosition!=index || container.tag!=streamContext(stream))return null
        return container.primary
    }
    fun focusTarget():View?=if(!streamsCommitted())null else if(list.isShown)sourceAt(0)
        else if(failure.visibility==View.VISIBLE)retry
        else if(empty.visibility==View.VISIBLE)if(manageAddons.visibility==View.VISIBLE)manageAddons else refresh else null
    fun restoreFocus(tag:String,parentTag:String?):Boolean {
        if(!tag.startsWith("stream:"))return false
        var resolvedRevision=-1;var index=-1
        return focusRestore.start {
            if(!streamsCommitted())return@start null
            if(resolvedRevision!=submittedRevision) {
                resolvedRevision=submittedRevision
                index=parentTag?.takeIf{it.startsWith("stream-source:")}?.removePrefix("stream-source:")?.let{sourceIndices[it]}?:-1
                if(index<0 && restoredLabel!=null) {
                    val labels=semanticIndices?:buildMap {
                        submittedStreams.forEachIndexed{position,stream->putIfAbsent(semanticLabel(stream),position)}
                    }.also{semanticIndices=it}
                    index=restoredLabel?.let{labels[it]}?:-1
                }
                if(index<0 && restoredIndex!=null)index=restoredIndex!!.takeIf { it in adapter.currentList.indices }?:-1
                if(index<0)index=adapter.currentList.indexOfFirst { "stream:${streamProvider(it)}"==tag }
            }
            if(index<0)focusTarget()
            else sourceAt(index)
        }
    }
    fun refreshCurrent() {
        restored=list.layoutManager?.onSaveInstanceState();restoredIndex=rememberedIndex;restoredLabel=rememberedLabel
        m.retryStreams()
        restoreFocus("stream:",rememberedContext)
    }
    fun saveState():Parcelable=Bundle().apply {
        putParcelable("list",list.layoutManager?.onSaveInstanceState())
        view.findFocus()?.takeIf { (it.tag as? String)?.startsWith("stream:")==true }?.let { focused ->
            putString("focused",focused.tag as String);putString("context",(focused.parent as? View)?.tag as? String)
            val index=adapter.currentList.indexOfFirst { streamContext(it)==(focused.parent as? View)?.tag }
            if(index>=0) { putInt("index",index);putString("label",streamSemantic(adapter.currentList[index])) }
        }
    }
    @Suppress("DEPRECATION") fun restoreState(state:Parcelable?) {
        if(state is Bundle) {
            restored=state.getParcelable("list")
            restoredIndex=state.getInt("index",-1).takeIf { it>=0 };restoredLabel=state.getString("label")
            state.getString("focused")?.let { restoreFocus(it,state.getString("context")) }
        } else restored=state
    }
    fun close() {
        closed=true;focusRestore.cancel();list.adapter=null;adapter.submitList(emptyList())
        submittedStreams=emptyList();submittedModelStreams=null;preparedModelStreams=null;committedItems=null
        sourceIndices.clear();semanticLabels.clear();semanticIndices=null
    }
}

private fun streamContext(s:Stream)="stream-source:"+s.identity
private fun streamSemantic(s:Stream)=java.util.UUID.nameUUIDFromBytes((s.addonName+"\n"+s.name+"\n"+s.sizeBytes+"\n"+s.description).toByteArray(Charsets.UTF_8)).toString()
private fun streamProvider(s:Stream)=s.addonName.trim().ifEmpty { s.name.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty() }.ifEmpty { "Stream" }
private class StreamContainer(a:MainActivity,height:Int,onFocus:(Stream)->Unit,open:(Stream)->Unit):FrameLayout(a) {
    val primary=StreamCard(a,onFocus,open)
    init {
        layoutParams=RecyclerView.LayoutParams(a.dp(232),height).apply { marginEnd=a.dp(10) }
        addView(primary,LayoutParams(-1,-1))
    }
    fun bind(s:Stream) { tag=streamContext(s);primary.bind(s) }
}

private val sourceResolution=Regex("(?<![A-Za-z0-9])(?:4320|2160|1440|1080|720|576|540|480|360)[pi](?![A-Za-z0-9])",RegexOption.IGNORE_CASE)
private val sourceQuality=Regex("(?<![A-Za-z0-9])(?:8K|4K|2K|UHD|FHD|HD|SD)(?![A-Za-z0-9])",RegexOption.IGNORE_CASE)
private val sourceCodec=Regex("(?<![A-Za-z0-9])(?:HEVC|H[ ._-]?265|x265|AVC|H[ ._-]?264|x264|AV1|VP9|MPEG[ ._-]?2)(?![A-Za-z0-9])",RegexOption.IGNORE_CASE)
private val sourceHdr=Regex("(?<![A-Za-z0-9])(?:Dolby[ ._-]?Vision|DV(?:[ ._-]?P[ ._-]?[578])?|HDR10\\+?|HDR|HLG)(?![A-Za-z0-9])",RegexOption.IGNORE_CASE)
private val sourceFilename=Regex("\\.(?:mkv|mp4|m4v|avi|mov|ts|m2ts|webm|mpg|mpeg)(?:\\s|$)",RegexOption.IGNORE_CASE)
private val sourceSizeOnly=Regex("^[^A-Za-z0-9]*[0-9]+(?:\\.[0-9]+)?\\s*(?:TB|GB|MB|KB|B)$",RegexOption.IGNORE_CASE)
private data class StreamPresentation(val headline:String,val information:String,val filename:String)
private fun streamPresentation(s:Stream):StreamPresentation {
    val raw=s.name+"\n"+s.description
    val quality=sourceResolution.find(raw)?.value?.lowercase(java.util.Locale.ROOT)
        ?:sourceQuality.find(raw)?.value?.uppercase(java.util.Locale.ROOT)
    val size=s.sizeBytes?.takeIf { it>0 && it.isFinite() }?.let { bytes ->
        val (scale,unit)=when { bytes>=1e12->1e12 to "TB";bytes>=1e9->1e9 to "GB";bytes>=1e6->1e6 to "MB";bytes>=1e3->1e3 to "KB";else->1.0 to "B" }
        String.format(java.util.Locale.ROOT,"%.1f",bytes/scale).removeSuffix(".0")+" "+unit
    }
    val codecs=sourceCodec.findAll(raw).map { match ->
        when(val value=match.value.filter { it.isLetterOrDigit() }.lowercase(java.util.Locale.ROOT)) {
            "hevc","h265","x265"->"HEVC";"avc","h264","x264"->"H.264"
            "mpeg2"->"MPEG-2";else->value.uppercase(java.util.Locale.ROOT)
        }
    }.distinct().take(2).toList()
    val hdr=sourceHdr.findAll(raw).map { it.value.replace('.',' ').replace('_',' ').replace('-',' ').uppercase(java.util.Locale.ROOT) }.distinct().toList()
    val parts=raw.lineSequence().flatMap { it.split(" · "," | ").asSequence() }.map { it.trim() }.filter { it.isNotEmpty() }.distinct().toList()
    val filename=parts.firstOrNull { sourceFilename.containsMatchIn(it) }.orEmpty()
    val information=parts.filterNot { chunk ->
        chunk==filename || chunk.equals(streamProvider(s),true) || size!=null && sourceSizeOnly.matches(chunk) ||
            quality!=null && (sourceResolution.matches(chunk) || sourceQuality.matches(chunk)) || sourceCodec.matches(chunk) || sourceHdr.matches(chunk)
    }.joinToString(" · ")
    val headline=(listOf(streamProvider(s))+listOfNotNull(quality,size)+codecs+hdr).joinToString(" · ")
    return StreamPresentation(headline,information,filename)
}

private class StreamCard(private val a:MainActivity,onFocus:(Stream)->Unit,open:(Stream)->Unit):LinearLayout(a) {
    private var stream:Stream?=null
    private val name=a.text("",16f,bold=true)
    private val detail=a.text("",13f,MUTED).apply { ellipsize=TextUtils.TruncateAt.END }
    private val filename=a.text("",12f,MUTED).apply { maxLines=1;ellipsize=TextUtils.TruncateAt.END }
    private val detailGap=Space(a)
    private val fileGap=Space(a)
    init {
        orientation=VERTICAL;pad(12);isFocusable=true;isFocusableInTouchMode=true;isClickable=true;isLongClickable=true;background=a.focusBackground()
        name.maxLines=2;name.ellipsize=TextUtils.TruncateAt.END;detail.maxLines=2
        add(name);addView(detailGap,LayoutParams(1,a.dp(5)));add(detail);addView(fileGap,LayoutParams(1,a.dp(5)));add(filename)
        setOnClickListener { stream?.let(open) }
        setOnFocusChangeListener { _,focused -> if(focused)stream?.let(onFocus) }
        setOnLongClickListener {
            stream?.let { source ->
                val body=listOf("Add-on: "+streamProvider(source),source.name.trim(),source.description.trim()).filter { it.isNotEmpty() }.distinct().joinToString("\n\n")
                a.showInfo("Stream details",body)
            };true
        }
    }
    fun bind(s:Stream) {
        val previous=stream;stream=s
        if(previous==s)return
        val presentation=streamPresentation(s)
        name.textIfChanged(presentation.headline);detail.textIfChanged(presentation.information);filename.textIfChanged(presentation.filename)
        detail.visibility=if(presentation.information.isEmpty())View.GONE else View.VISIBLE;detailGap.visibility=detail.visibility
        filename.visibility=if(presentation.filename.isEmpty())View.GONE else View.VISIBLE;fileGap.visibility=filename.visibility
        tag="stream:"+streamProvider(s);contentDescription=listOf(presentation.headline,presentation.information,presentation.filename).filter { it.isNotEmpty() }.joinToString(", ")
    }
}

private data class EpisodeRow(val episode:Episode,val selected:Boolean,val resumable:Boolean)

internal class DetailScreen(private val a:MainActivity,private val initial:Title):NativeScreen {
    private val m=a.model
    private val series=initial.type=="series"
    private val content=a.column()
    private val body=a.column()
    private val scroll=ScrollView(a).apply {
        tag=if(series)"series-body" else "movie-details";isFillViewport=true;isFocusable=false
        descendantFocusability=ViewGroup.FOCUS_AFTER_DESCENDANTS
        addView(if(series)body else content,FrameLayout.LayoutParams(-1,-2))
    }
    override val view:View=if(series)a.column().apply { tag="series-details" } else scroll
    private var capabilities=BrowseAddonCapabilities(m.addons)
    private val posterWidth=if(series)88 else 108
    private val posterHeight=posterWidth*3/2
    private val title=a.text("",23f,bold=true).apply { tag="detail-title";maxLines=2;ellipsize=TextUtils.TruncateAt.END }
    private val metadata=a.text("",if(series)12f else 13f,MUTED).apply { maxLines=if(series)1 else 2;ellipsize=TextUtils.TruncateAt.END }
    private val episodeHeading=a.text("",13f).apply { tag="source-context";maxLines=1;ellipsize=TextUtils.TruncateAt.END }
    private val description=a.text("",if(series)13f else 14f).apply { tag="episode-description";maxLines=if(series)2 else 3;ellipsize=TextUtils.TruncateAt.END }
    private val poster=FixedArtworkView(a).apply { tag="detail-poster";scaleType=ImageView.ScaleType.CENTER_CROP;background=a.round(PANEL);clipToOutline=true }
    private val saved=a.action("My list","Save to my list") { m.toggle(current) }.quiet()
    private val streams=StreamChoices(a,showErrors=true) { stream ->
        m.open(Route.Player(Watch(current,stream,if(series)m.selectedEpisode else null,headers=stream.headers)))
    }
    private val refreshStreams=a.action("Refresh streams") { streams.refreshCurrent() }.quiet()
    private val seasons=a.column()
    private val episodes=TvRecyclerView(a).apply {
        tag="episodes";layoutManager=LinearLayoutManager(a,RecyclerView.HORIZONTAL,false).apply{initialPrefetchItemCount=4};tune(cachedViews=6)
    }
    private val chooser=a.row().apply {
        addView(seasons,LinearLayout.LayoutParams(-2,-2).apply { marginEnd=a.dp(10) })
        addView(episodes,LinearLayout.LayoutParams(0,a.dp(56),1f))
    }
    private val refreshEpisodes=a.action("Refresh episodes") { m.retry() }.quiet()
    private val manageAddons=a.action("Manage add-ons") { m.open(Route.Addons) }
    private val emptyMessage=a.text("",14f,MUTED)
    private val empty=a.column().apply { add(emptyMessage);gap(8);add(refreshEpisodes,width=-2);add(manageAddons,width=-2) }
    private var current=initial
    private var last:Title?=null
    // These caches belong to one series screen and are discarded on close.
    // Selection/progress changes reuse its catalog/index and only rebuild rows.
    private var sourceEpisodes:List<Episode>?=null
    private var orderedEpisodes:List<Episode> = emptyList()
    private var episodeIndices:Map<String,Int> = emptyMap()
    private var episodesById:Map<String,Episode> = emptyMap()
    private var availableSeasons:List<Int> = emptyList()
    private var lastEpisodeTag:String?=null
    private var progressSource:List<Progress>?=null
    private var progressEpisodeSource:List<Episode>?=null
    private var resumeEnabled:Boolean?=null
    private var resumableEpisodeId:String?=null
    private var episodeRows:List<EpisodeRow> = emptyList()
    private var rowEpisodeSource:List<Episode>?=null
    private var rowSelectedId:String?=null
    private var rowResumableId:String?=null
    private var submittedEpisodeRows:List<EpisodeRow>?=null
    private var episodeRevision=0
    private var committedEpisodeRevision=0
    private var afterEpisodeCommit:(()->Unit)?=null
    private var scrollEpisodeId:String?=null
    private var scrollEpisodeGeneration=0
    private var shownSeason:Int?=null
    private var selectedEpisodeId:String?=null
    private var seasonChoices:List<Int>?=null
    private var seasonButton:Button?=null
    private var episodeState:Parcelable?=null
    private var restoredEpisodeId:String?=null
    private var restored=false
    private var initialFocusStarted=false
    private var scrollState:Int?=null
    private var closed=false
    private val entryInputGeneration=a.focusInputGeneration
    private val entryEpisode=(m.route as? Route.Detail)?.episode
    private val focusRestore=BrowseFocusRestore(a,view)
    private val adapter=NativeAdapter<EpisodeRow>({it.episode.id},
        { parent,_->parent.context.action("") {}.apply {
            layoutParams=RecyclerView.LayoutParams(a.dp(200),a.dp(48)).apply { marginEnd=a.dp(8) }
            gravity=Gravity.START or Gravity.CENTER_VERTICAL;maxLines=2;ellipsize=TextUtils.TruncateAt.END;textSize=13f
            background=StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused),a.round(RAISED,FOCUS))
                addState(intArrayOf(android.R.attr.state_selected),a.round(RAISED))
                addState(intArrayOf(),a.round(PANEL))
            }
            // Listeners belong to the recycled button, not each selected-state
            // bind. Resolve its current tag against this series' small index.
            setOnFocusChangeListener { view,focused ->
                if(focused)(view.tag as? String)?.removePrefix("episode:")?.let{episodesById[it]}?.let{showSeason(it.season)}
            }
            setOnKeyListener { view,code,_ -> code==KeyEvent.KEYCODE_DPAD_RIGHT && view.tag==lastEpisodeTag }
            setOnClickListener { view->
                (view.tag as? String)?.removePrefix("episode:")?.let{episodesById[it]}?.let{episode->focusRestore.cancel();m.selectEpisode(episode)}
            }
        } },
        { v,row,_->(v as Button).apply {
            val e=row.episode
            textIfChanged((if(row.resumable)"Resume · " else "")+"E${e.number}  ${e.name}")
            contentDescription=(if(row.resumable)"Resume · " else "")+e.label
            tag="episode:"+e.id;isSelected=row.selected
        } })
    init {
        val header=a.row().apply {
            tag=if(series)"series-header" else "movie-header";gravity=Gravity.TOP
            addView(poster,LinearLayout.LayoutParams(a.dp(posterWidth),a.dp(posterHeight)).apply { marginEnd=a.dp(if(series)16 else 22) })
            addView(a.column().apply {
                add(title);gap(if(series)5 else 8);add(metadata)
                if(series) { gap(5);add(episodeHeading);gap(5);add(description) }
                else { gap(12);add(description);gap(16);add(saved,width=-2) }
            },LinearLayout.LayoutParams(0,-2,1f))
            if(series)addView(saved,LinearLayout.LayoutParams(-2,-2).apply { marginStart=a.dp(12) })
        }
        if(series) {
            val headerHeight=maxOf(a.dp(132),title.lineHeight*2+metadata.lineHeight+episodeHeading.lineHeight+description.lineHeight*2+a.dp(15))
            (view as LinearLayout).apply {
                add(header,height=headerHeight);gap(12);addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
            }
            episodes.adapter=adapter;body.add(chooser);body.gap(10);body.add(empty)
            body.add(a.row().apply {
                addView(a.text("Streams",18f,bold=true),LinearLayout.LayoutParams(0,-2,1f));add(refreshStreams,width=-2)
            });body.gap(6);body.add(streams.view)
        } else {
            content.add(header);content.gap(16);content.add(a.text("Streams",18f,bold=true));content.gap(8);content.add(streams.view)
        }
    }
    override fun update() {
        current=m.detail?.takeIf { it.key==initial.key }?:initial
        if(capabilities.addons!==m.addons)capabilities=BrowseAddonCapabilities(m.addons)
        val inList=m.saved.any { it.key==current.key }
        saved.textIfChanged(if(inList)"✓ In My list" else "+ My list")
        saved.contentDescription=if(inList)"Remove from my list" else "Save to my list";saved.isSelected=inList
        if(series && !m.busy)restoredEpisodeId?.let { id ->
            current.episodes.firstOrNull { it.id==id }?.let { episode ->
                restoredEpisodeId=null
                if(m.selectedEpisode?.id!=id)m.selectEpisode(episode)
            }
        }
        val selected=if(series)m.selectedEpisode else null
        refreshStreams.visibility=if(series && selected!=null && (m.streams.isNotEmpty() || m.streamsBusy))View.VISIBLE else View.INVISIBLE
        refreshStreams.isEnabled=!m.streamsBusy
        if(last!=current) {
            if(sourceEpisodes!==current.episodes) {
                sourceEpisodes=current.episodes
                var sorted=true
                for(index in 1 until current.episodes.size) {
                    val previous=current.episodes[index-1];val next=current.episodes[index]
                    if(previous.season>next.season || previous.season==next.season && previous.number>next.number){sorted=false;break}
                }
                orderedEpisodes=if(sorted)current.episodes else current.episodes.sortedWith(compareBy({it.season},{it.number}))
                episodeIndices=buildMap {orderedEpisodes.forEachIndexed{index,episode->putIfAbsent(episode.id,index)}}
                episodesById=buildMap {orderedEpisodes.forEach{episode->putIfAbsent(episode.id,episode)}}
                availableSeasons=orderedEpisodes.map{it.season}.distinct()
                lastEpisodeTag=orderedEpisodes.lastOrNull()?.let{"episode:${it.id}"}
            }
            last=current;title.textIfChanged(current.name)
            metadata.textIfChanged(listOf(current.year,current.rating.takeIf { it.isNotBlank() }?.let { "★ $it" }.orEmpty(),current.genres.joinToString(" · ")).filter { it.isNotBlank() }.joinToString(" · "))
            metadata.visibility=if(series)View.VISIBLE else if(metadata.text.isEmpty())View.GONE else View.VISIBLE
            a.artwork.show(poster,m.repository.artwork(current.poster),a.dp(posterWidth),a.dp(posterHeight))
        }
        if(series) {
            episodeHeading.textIfChanged(selected?.let { listOf(if(it.season==0)"Specials" else "Season ${it.season}","Episode ${it.number}",it.name).filter { value->value.isNotBlank() }.joinToString(" · ") }.orEmpty())
            val overview=selected?.description.orEmpty()
            description.textIfChanged(overview.ifBlank { current.description.takeIf { it.isNotBlank() }?.let { "About the show: $it" }.orEmpty() })
            val recovery=addonRecovery(m,capabilities,capabilities.metadata(current),"load episodes for this show")
            empty.visibility=if(current.episodes.isEmpty() && (!m.busy || recovery!=null))View.VISIBLE else View.GONE
            emptyMessage.textIfChanged(recovery?:"Episodes are not available for this title.")
            refreshEpisodes.visibility=if(recovery==null)View.VISIBLE else View.GONE
            manageAddons.visibility=if(recovery!=null)View.VISIBLE else View.GONE
            chooser.visibility=if(current.episodes.isEmpty())View.GONE else View.VISIBLE
            val selectionChanged=selectedEpisodeId!=selected?.id
            selectedEpisodeId=selected?.id
            val choices=availableSeasons
            val focusedId=(episodes.findFocus()?.tag as? String)?.removePrefix("episode:")
            val focusedSeason=episodesById[focusedId]?.season
            val season=focusedSeason?:shownSeason?.takeIf { (!selectionChanged || episodeState!=null) && it in choices }
                ?:selected?.season?:choices.firstOrNull { it>0 }?:choices.firstOrNull()
            showSeasons(choices,season)
            if(progressSource!==m.progress || progressEpisodeSource!==sourceEpisodes || resumeEnabled!=m.preferences.resume) {
                progressSource=m.progress;progressEpisodeSource=sourceEpisodes;resumeEnabled=m.preferences.resume
                resumableEpisodeId=if(m.preferences.resume)m.progress.asSequence()
                    .filter{it.title.key==current.key && it.positionMs>0 && !it.finished && episodesById.containsKey(it.episode?.id)}
                    .maxByOrNull{it.updated}?.episode?.id else null
            }
            // One virtualized row lets native D-pad focus cross season boundaries
            // without replacing the adapter or loading another episode's streams.
            if(rowEpisodeSource!==sourceEpisodes || rowSelectedId!=selected?.id || rowResumableId!=resumableEpisodeId) {
                rowEpisodeSource=sourceEpisodes;rowSelectedId=selected?.id;rowResumableId=resumableEpisodeId
                episodeRows=orderedEpisodes.map{EpisodeRow(it,it.id==selected?.id,it.id==resumableEpisodeId)}
            }
            if(selectionChanged && !episodes.hasFocus()) {scrollEpisodeId=selected?.id;scrollEpisodeGeneration=a.focusInputGeneration}
            val position:()->Unit={
                val state=episodeState
                val target=scrollEpisodeId;scrollEpisodeId=null
                if(state!=null) { episodes.layoutManager?.onRestoreInstanceState(state);episodeState=null }
                else if(target!=null && a.focusInputGeneration==scrollEpisodeGeneration)scrollToEpisode(target)
            }
            afterEpisodeCommit=position
            if(submittedEpisodeRows!==episodeRows) {
                submittedEpisodeRows=episodeRows;val revision=++episodeRevision
                adapter.submitList(episodeRows) {
                    if(!closed && revision==episodeRevision){committedEpisodeRevision=revision;afterEpisodeCommit?.invoke();afterEpisodeCommit=null}
                }
            } else if(committedEpisodeRevision==episodeRevision){afterEpisodeCommit=null;position()}
        } else {
            description.textIfChanged(current.description);description.visibility=if(current.description.isBlank())View.GONE else View.VISIBLE
        }
        streams.update(current,selected)
        if(!restored && !initialFocusStarted && (if(series)!m.busy else !m.streamsBusy)) {
            initialFocusStarted=true
            if(a.focusInputGeneration==entryInputGeneration)focusRestore.start { focusTarget() }
        }
        if(!m.streamsBusy)scrollState?.let { y -> scrollState=null;view.postOnAnimation { if(!closed)scroll.scrollTo(0,y) } }
    }
    private fun seasonName(season:Int)=if(season==0)"Specials" else "Season $season"
    private fun showSeasons(choices:List<Int>,selected:Int?) {
        if(seasonChoices!=choices) {
            seasonChoices=choices;seasons.removeAllViews();seasonButton=null
            if(choices.size==1)seasons.add(a.text(seasonName(choices[0]),14f,bold=true),width=-2)
            else if(choices.isNotEmpty()) {
                val button=a.action("") {
                    a.chooseOption("Season",choices.map(::seasonName),choices.indexOf(shownSeason)) { index ->
                        focusRestore.cancel();episodeState=null;restoredEpisodeId=null
                        m.selectSeason(choices[index]);showSeason(choices[index])
                        scrollToEpisode(m.selectedEpisode?.id)
                    }
                }.quiet().apply { tag="season-selector";maxLines=1 }
                seasonButton=button
                // Fixed dimensions let TextView redraw its label without
                // remeasuring this row while native focus scrolling is active.
                val width=kotlin.math.ceil(choices.maxOf { button.paint.measureText(seasonName(it)) }.toDouble()).toInt()+button.paddingLeft+button.paddingRight
                seasons.add(button,width=width,height=a.dp(48))
                button.setOnKeyListener { _,code,event ->
                    if(code!=KeyEvent.KEYCODE_DPAD_DOWN && code!=KeyEvent.KEYCODE_DPAD_RIGHT)false
                    else {
                        if(event.action==KeyEvent.ACTION_DOWN) {
                            val target=m.selectedEpisode?.takeIf { it.season==shownSeason }
                                ?:orderedEpisodes.firstOrNull { it.season==shownSeason }
                            target?.let { episode -> focusRestore.start { episodeTarget(episode.id) } }
                        }
                        true
                    }
                }
            }
        }
        showSeason(selected)
    }
    private fun showSeason(season:Int?) {
        shownSeason=season
        val label=season?.let(::seasonName).orEmpty()
        seasonButton?.apply { if(text.toString()!=label) { text=label;contentDescription=label } }
    }
    private fun scrollToEpisode(id:String?) {
        val index=episodeIndices[id]?.takeIf{adapter.currentList.getOrNull(it)?.episode?.id==id}
            ?:adapter.currentList.indexOfFirst { it.episode.id==id }
        if(index>=0)episodes.scrollToPosition(index)
    }
    private fun episodeTarget(id:String):View? {
        if(committedEpisodeRevision!=episodeRevision)return null
        if(episodes.isComputingLayout || episodes.hasPendingAdapterUpdates() || episodes.isLayoutRequested)return null
        if(episodes.scrollState!=RecyclerView.SCROLL_STATE_IDLE)return null
        val index=episodeIndices[id]?:-1
        if(index<0 || adapter.currentList.getOrNull(index)?.episode?.id!=id)return null
        val holder=episodes.findViewHolderForAdapterPosition(index)
        val child=holder?.itemView
        val left=episodes.paddingLeft;val right=episodes.width-episodes.paddingRight
        val visible=child!=null && if(child.width<=right-left)child.left>=left && child.right<=right
            else child.left<=left && child.right>=right
        // A prefetched or clipped child can gain focus and then be recycled by
        // the ongoing reveal scroll. Hand focus over only after it has settled.
        if(!visible) {
            episodes.scrollToPosition(index)
            return null
        }
        return child?.takeIf { holder?.bindingAdapterPosition==index && it.tag=="episode:"+id }
    }
    override fun focusTarget():View? {
        if(!series || entryEpisode!=null && !restored)return streams.focusTarget()
        if(m.busy && m.selectedEpisode==null)return null
        return m.selectedEpisode?.let { episodeTarget(it.id) }
            ?:if(empty.visibility==View.VISIBLE)if(manageAddons.visibility==View.VISIBLE)manageAddons else refreshEpisodes else null
    }
    override fun restoreFocus(tag:String,parentTag:String?):Boolean {
        restored=true;focusRestore.cancel()
        if(tag.startsWith("stream:"))return streams.restoreFocus(tag,parentTag)
        if(!tag.startsWith("episode:"))return false
        return focusRestore.start { episodeTarget(tag.removePrefix("episode:"))?:if(m.busy)null else focusTarget() }
    }
    override fun saveState():Parcelable=Bundle().apply {
        putString("selected-episode",if(series)m.selectedEpisode?.id else null)
        putInt("episode-season",shownSeason?:-1);putParcelable("episodes",episodes.layoutManager?.onSaveInstanceState())
        putParcelable("streams",streams.saveState());putInt("scroll",scroll.scrollY)
    }
    @Suppress("DEPRECATION") override fun restoreState(state:Parcelable?) {
        if(state is Bundle) {
            restored=true;restoredEpisodeId=state.getString("selected-episode")
            shownSeason=state.getInt("episode-season",-1).takeIf { it>=0 };episodeState=state.getParcelable("episodes")
            streams.restoreState(state.getParcelable("streams"));scrollState=state.getInt("scroll")
        }
    }
    override fun close() {
        closed=true;focusRestore.cancel();streams.close();episodes.adapter=null;adapter.submitList(emptyList());afterEpisodeCommit=null
        sourceEpisodes=null;orderedEpisodes=emptyList();episodeIndices=emptyMap();episodesById=emptyMap();availableSeasons=emptyList()
        progressSource=null;progressEpisodeSource=null;episodeRows=emptyList();rowEpisodeSource=null;submittedEpisodeRows=null
        poster.dispose();poster.setImageDrawable(null)
    }
}

/** Restore recycled content only while the user has not started another action. */
internal class BrowseFocusRestore(private val a:MainActivity,private val view:View) {
    private var pending:Runnable?=null
    fun start(waitForWindowFocus:Boolean=false,target:()->View?):Boolean {
        cancel()
        val generation=a.focusInputGeneration
        val deadline=SystemClock.uptimeMillis()+30000
        val task=object:Runnable {
            override fun run() {
                if(pending!==this || !view.isAttachedToWindow || a.focusInputGeneration!=generation || SystemClock.uptimeMillis()>deadline) { pending=null;return }
                if(!a.hasWindowFocus()) {
                    if(waitForWindowFocus)view.postOnAnimation(this) else pending=null
                    return
                }
                val next=target()
                if(next!=null && next.isShown && next.isFocusable && next.width>0 && next.requestFocus())pending=null
                else view.postOnAnimation(this)
            }
        }
        pending=task;view.postOnAnimation(task)
        return true
    }
    fun cancel() { pending?.let(view::removeCallbacks);pending=null }
}
