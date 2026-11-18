package xyz.billybishop4.fleet.tv

import org.json.JSONObject

/** Account library collection cards stay separate from playable video IDs. */
internal object YouTubeLibraryDecode {
    fun collection(root:JSONObject):YouTubePlaylistCollection {
        val nodes=YouTubeDecode.find(root,setOf("playlistRenderer","gridPlaylistRenderer","compactPlaylistRenderer","tileRenderer","lockupViewModel"),240)
            .filterIsInstance<JSONObject>()
        val playlists=nodes.mapNotNull { node->
            val type=node.optString("contentType")
            if(type.isNotBlank() && !type.contains("PLAYLIST"))return@mapNotNull null
            val browse=YouTubeDecode.find(node,setOf("browseEndpoint"),2).filterIsInstance<JSONObject>()
                .map{it.optString("browseId")}.firstOrNull{it.startsWith("VL")}
            val id=node.optString("playlistId").ifBlank{if(type.contains("PLAYLIST"))node.optString("contentId") else ""}
                .ifBlank{browse?.removePrefix("VL").orEmpty()}
            if(!YouTubeSocialDecode.validPlaylistId(id))return@mapNotNull null
            val metadata=node.optJSONObject("metadata")?.optJSONObject("lockupMetadataViewModel")
                ?:node.optJSONObject("metadata")?.optJSONObject("tileMetadataRenderer")
            val overlay=YouTubeDecode.find(node.opt("header"),setOf("tileMetadataRenderer"),1).firstOrNull() as? JSONObject
            val name=YouTubeDecode.text(node.opt("title")).ifBlank{YouTubeDecode.text(metadata?.opt("title"))}
                .ifBlank{YouTubeDecode.text(overlay?.opt("title"))}.take(500)
            if(name.isBlank())return@mapNotNull null
            val header=node.optJSONObject("header")?.optJSONObject("tileHeaderRenderer")
            val image=YouTubeDecode.image(node.opt("contentImage")?:node.opt("thumbnail")?:header?.opt("thumbnail"))
            val count=YouTubeDecode.text(node.opt("videoCountText")).ifBlank {
                val badge=YouTubeDecode.find(header?.opt("thumbnailOverlays"),setOf("thumbnailOverlayTimeStatusRenderer"),1).firstOrNull() as? JSONObject
                YouTubeDecode.text(badge?.opt("text"))
            }
            YouTubePlaylist(id,name,false,node.optBoolean("isEditable"),image,count.take(300))
        }.distinctBy{it.id}.take(60)
        val page=YouTubeDecode.page(root)
        return YouTubePlaylistCollection(playlists,page.continuation,page.message)
    }
}
