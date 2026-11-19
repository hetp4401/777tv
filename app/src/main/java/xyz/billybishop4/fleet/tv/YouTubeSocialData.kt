package xyz.billybishop4.fleet.tv

import org.json.JSONObject

internal enum class YouTubeRating { NONE,LIKE,DISLIKE }
internal enum class YouTubeAccountMutation { RATING,SUBSCRIPTION,PLAYLIST }
internal data class YouTubeChannel(val id:String,val name:String,val thumbnail:String="",val subscriberText:String="",
    val description:String="",val subscribed:Boolean?=null,val subscribeParams:String="",val unsubscribeParams:String="")
internal data class YouTubeChannelTab(val id:String,val title:String,val params:String="",val selected:Boolean=false)
internal data class YouTubeChannelPage(val channel:YouTubeChannel,val tabs:List<YouTubeChannelTab>,val selectedTab:String,val page:YouTubePage)
internal data class YouTubePlaylist(val id:String,val name:String,val containsVideo:Boolean,val editable:Boolean=true,
    val thumbnail:String="",val metadata:String="")
internal data class YouTubePlaylistCollection(val playlists:List<YouTubePlaylist>,val continuation:String?=null,val message:String="")
internal data class YouTubeAccountScope internal constructor(internal val epoch:Int)

/** Reduce response renderers to bounded public data; never retain commands or account JSON. */
internal object YouTubeSocialDecode {
    private val channelIdPattern=Regex("UC[A-Za-z0-9_-]{22}")
    private val playlistIdPattern=Regex("[A-Za-z0-9_-]{2,128}")
    private val videoTabs=setOf("home","videos","shorts","live","streams","releases")
    fun validChannelId(id:String)=channelIdPattern.matches(id)
    fun validPlaylistId(id:String)=playlistIdPattern.matches(id)
    private fun objects(root:Any?,names:Set<String>,limit:Int=100)=YouTubeDecode.find(root,names,limit).filterIsInstance<JSONObject>()
    private fun subscribed(node:JSONObject?):Boolean?=node?.let {
        when {it.has("subscribed")->it.optBoolean("subscribed");it.has("isSubscribed")->it.optBoolean("isSubscribed");else->null}
    }
    fun watchChannel(root:JSONObject):YouTubeChannel? {
        val owner=objects(root,setOf("videoOwnerRenderer","videoOwnerInfoRenderer"),1).firstOrNull()
        val button=objects(owner?:root,setOf("subscribeButtonRenderer","subscribeButtonViewModel"),1).firstOrNull()
            ?:objects(root,setOf("subscribeButtonRenderer","subscribeButtonViewModel"),1).firstOrNull()
        val id=button?.optString("channelId").orEmpty().ifBlank{
            objects(owner?:root,setOf("browseEndpoint"),20).map{it.optString("browseId")}.firstOrNull(::validChannelId).orEmpty()
        }
        if(!validChannelId(id))return null
        val name=YouTubeDecode.text(owner?.opt("title")).ifBlank{YouTubeDecode.text(owner?.opt("author"))}.ifBlank{"Creator"}
        val subscribe=objects(button,setOf("subscribeEndpoint"),1).firstOrNull()?.optString("params").orEmpty().take(20_000)
        val unsubscribe=objects(button,setOf("unsubscribeEndpoint"),1).firstOrNull()?.optString("params").orEmpty().take(20_000)
        return YouTubeChannel(id,name.take(500),YouTubeDecode.image(owner?.opt("thumbnail")),
            YouTubeDecode.text(owner?.opt("subscriberCountText")),subscribed=subscribed(button),subscribeParams=subscribe,unsubscribeParams=unsubscribe)
    }
    fun rating(root:JSONObject):YouTubeRating? {
        val status=objects(root,setOf("likeButtonRenderer"),2).firstOrNull()?.optString("likeStatus")
        when(status){"LIKE"->return YouTubeRating.LIKE;"DISLIKE"->return YouTubeRating.DISLIKE;"INDIFFERENT"->return YouTubeRating.NONE}
        val buttons=objects(root,setOf("segmentedLikeDislikeButtonRenderer","segmentedLikeDislikeButtonViewModel"),2)
        for(button in buttons) {
            val like=button.optJSONObject("likeButton")?.optJSONObject("toggleButtonRenderer")
            val dislike=button.optJSONObject("dislikeButton")?.optJSONObject("toggleButtonRenderer")
            if(like?.has("isToggled")==true || dislike?.has("isToggled")==true)
                return when{like?.optBoolean("isToggled")==true->YouTubeRating.LIKE;dislike?.optBoolean("isToggled")==true->YouTubeRating.DISLIKE;else->YouTubeRating.NONE}
        }
        return null
    }
    fun channel(root:JSONObject,id:String,tab:YouTubeChannelTab?):YouTubeChannelPage {
        require(validChannelId(id))
        val header=objects(root,setOf("c4TabbedHeaderRenderer","channelHeaderRenderer","channelMetadataRenderer"),1).firstOrNull()
        val button=objects(root,setOf("subscribeButtonRenderer","subscribeButtonViewModel"),1).firstOrNull()
        val title=YouTubeDecode.text(header?.opt("title")).ifBlank{YouTubeDecode.text(header?.opt("titleText"))}.ifBlank{id}
        val subscriber=YouTubeDecode.text(header?.opt("subscriberCountText")).ifBlank {
            objects(header?.opt("subtitle"),setOf("lineItemRenderer"),8).map{YouTubeDecode.text(it.opt("text"))}
                .firstOrNull{it.contains("subscriber",true)}.orEmpty()
        }
        val description=header?.optString("description").orEmpty().ifBlank {
            val selectable=header?.optJSONObject("selectableDescription")?.optJSONObject("selectableTextRenderer")
            YouTubeDecode.text(selectable?.opt("compactDescription"))
        }
        val channel=YouTubeChannel(id,title.take(500),YouTubeDecode.image(header?.opt("avatar")?:header?.opt("thumbnail")),
            subscriber,description.take(3000),subscribed(button),
            objects(button,setOf("subscribeEndpoint"),1).firstOrNull()?.optString("params").orEmpty().take(20_000),
            objects(button,setOf("unsubscribeEndpoint"),1).firstOrNull()?.optString("params").orEmpty().take(20_000))
        val renderers=objects(root,setOf("tabRenderer","expandableTabRenderer"),12)
        val legacyTabs=renderers.mapIndexedNotNull { index,node->
            val endpoint=node.optJSONObject("endpoint")?.optJSONObject("browseEndpoint")
            val label=YouTubeDecode.text(node.opt("title"));val params=endpoint?.optString("params").orEmpty()
            // Community posts and playlist-only grids need different renderers. Present the
            // actual video tabs we can browse, instead of rendering nonfunctional tabs.
            if(label.isBlank() || label.lowercase() !in videoTabs)null
            else YouTubeChannelTab(params.ifBlank{"tab-$index"},label.take(100),params.take(20_000),node.optBoolean("selected"))
        }
        // The current TV Home supplies its category destinations as wormhole
        // tiles, rather than WEB-style tabs. Use their real per-channel params.
        val tvTabs=objects(root,setOf("tileRenderer"),160).mapNotNull { node->
            if(!node.optString("style").contains("WORMHOLE"))return@mapNotNull null
            val endpoint=node.optJSONObject("onSelectCommand")?.optJSONObject("browseEndpoint")?:return@mapNotNull null
            val metadata=objects(node.opt("header"),setOf("tileMetadataRenderer"),1).firstOrNull()
            val label=YouTubeDecode.text(metadata?.opt("title"));val params=endpoint.optString("params")
            if(endpoint.optString("browseId")!=id || label.lowercase() !in videoTabs || params.isBlank() || params.length>20_000)null
            else YouTubeChannelTab(params,label.take(100),params)
        }
        val tabs=(if(legacyTabs.isNotEmpty())legacyTabs else if(tvTabs.isNotEmpty())listOf(YouTubeChannelTab("home","Home",selected=tab==null))+tvTabs else emptyList())
            .distinctBy{it.id}.take(8)
        val selected=tab?.id?:tabs.firstOrNull{it.selected}?.id?:tabs.firstOrNull()?.id.orEmpty()
        // An unselected TV sorting tab contains a reload cursor, not the current
        // grid's continuation. Parse only the selected tab's content when present.
        val content=renderers.firstOrNull{it.optBoolean("selected")}?.optJSONObject("content")
        val page=if(content!=null)YouTubeDecode.page(content) else if(tvTabs.isNotEmpty())YouTubeDecode.home(root) else YouTubeDecode.page(root)
        return YouTubeChannelPage(channel,tabs,selected,page)
    }
    fun playlists(root:JSONObject):List<YouTubePlaylist> = objects(root,setOf("playlistAddToOptionRenderer","playlistAddToOptionViewModel"),100)
        .mapNotNull { node->
            val id=node.optString("playlistId");val title=YouTubeDecode.text(node.opt("title"))
            if(!validPlaylistId(id) || title.isBlank())null else YouTubePlaylist(id,title.take(300),
                node.optString("containsSelectedVideos")=="ALL" || node.optBoolean("isSelected"),!node.optBoolean("isDisabled"))
        }.distinctBy{it.id}.take(60)
    fun requireAccepted(root:JSONObject) {
        val status=root.optString("status")
        if(root.has("error") || root.optJSONArray("errors")?.length()?.let{it>0}==true ||
            status in setOf("FAILED","ERROR","STATUS_FAILED") || !root.has("responseContext") && status !in setOf("OK","SUCCESS","STATUS_SUCCEEDED") ||
            objects(root,setOf("alertRenderer","alertWithButtonRenderer","notificationActionRenderer","playlistEditResult"),30).any { node->
                node.optString("type")=="ERROR" || node.optString("status") in setOf("FAILED","ERROR","STATUS_FAILED") ||
                    node.has("success") && !node.optBoolean("success") ||
                    YouTubeDecode.text(node.opt("text")?:node.opt("responseText")).lowercase().let { text->
                        listOf("couldn't","couldn’t","unable to","not allowed","failed","sign in to","an error occurred").any(text::contains)
                    }
            })
            throw UserMessage("YouTube couldn't complete this action. Try again.")
    }
}
