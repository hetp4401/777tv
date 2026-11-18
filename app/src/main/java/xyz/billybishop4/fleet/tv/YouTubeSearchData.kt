package xyz.billybishop4.fleet.tv

import org.json.JSONArray
import org.json.JSONObject

/** youtube-lr returns JSON behind an anti-XSSI prefix, never executable script. */
internal object YouTubeSuggestions {
    private val whitespace=Regex("\\s+")
    private val controls=Regex("[\\p{Cc}]")
    fun clean(value:String):String=value.take(1000).replace(controls," ")
        .replace(whitespace," ").trim().take(200)
    fun decode(body:String,query:String):List<String> {
        if(body.length>64*1024)return emptyList()
        val json=body.trimStart().let{if(it.startsWith(")]}'"))it.substring(4).trimStart() else it}
        val root=JSONArray(json)
        if(!clean(root.optString(0)).equals(query,ignoreCase=true))return emptyList()
        val rows=root.optJSONArray(1)?:return emptyList()
        val values=linkedMapOf<String,String>()
        for(index in 0 until minOf(rows.length(),40)) {
            val raw=(rows.optJSONArray(index)?.opt(0)?:rows.opt(index)) as? String?:continue
            val text=clean(raw)
            if(text.isNotBlank())values.putIfAbsent(text.lowercase(java.util.Locale.ROOT),text)
            if(values.size==8)break
        }
        return values.values.toList()
    }
}

/** Search keeps YouTube's real video sections, with canonical Shorts in one
 * final shelf. Response JSON, action commands and media URLs never enter it. */
internal object YouTubeSearchPages {
    private data class Section(val page:YouTubePage,val name:String)
    fun decode(root:JSONObject):YouTubePage {
        val sections=YouTubeDecode.find(root,setOf("shelfRenderer","richShelfRenderer","reelShelfRenderer"),10)
            .filterIsInstance<JSONObject>().map { Section(YouTubeDecode.page(it,limit=120),YouTubeDecode.shelfTitle(it)) }
        if(sections.isEmpty()) {
            val flat=YouTubeDecode.page(root,limit=120)
            return flat.copy(shelves=fromVideos(flat.videos)).let{it.copy(videos=it.shelves.flatMap(YouTubeShelf::videos))}
        }
        val rows=ArrayList<YouTubeShelf>();val names=HashMap<String,Int>();var primary=false
        for(section in sections) {
            val regular=section.page.videos.filterNot{it.isShort}
            val shorts=section.page.videos.filter{it.isShort}
            if(regular.isNotEmpty()) {
                val name=section.name.ifBlank{"Videos"}
                val number=names.getOrDefault(name,0);names[name]=number+1
                val key=if(!primary){primary=true;"search/videos"} else "search/section/$name/$number"
                rows+=YouTubeShelf(key,name,regular)
            }
            if(shorts.isNotEmpty())rows+=YouTubeShelf("search/shorts",section.name.takeIf{regular.isEmpty()}.orEmpty().ifBlank{"Shorts"},shorts,true)
        }
        val grouped=merge(emptyList(),rows)
        // The main TV results shelf has a horizontal search continuation. A
        // secondary Shorts/topic cursor must not replace that regular feed.
        val regular=sections.filter{it.page.videos.any{v->!v.isShort}}
        val cursor=(if(regular.isNotEmpty())regular else sections).firstNotNullOfOrNull{it.page.continuation}
        val message=if(grouped.isEmpty())YouTubeDecode.page(root).message else ""
        return YouTubePage(grouped.flatMap(YouTubeShelf::videos),cursor,message,grouped)
    }
    fun fromVideos(videos:List<YouTubeVideo>):List<YouTubeShelf> = merge(emptyList(),listOf(YouTubeShelf("search/videos","Videos",videos)))

    /** Keep the regular result cursor stable across bare continuation responses.
     * Twenty Shorts leave room for regular pagination and a stable final shelf;
     * a Shorts-only result can still use the whole 120-item budget. */
    fun merge(old:List<YouTubeShelf>,fresh:List<YouTubeShelf>):List<YouTubeShelf> {
        val regular=linkedMapOf<String,YouTubeShelf>();val shorts=linkedMapOf<String,YouTubeVideo>();var shortsName="Shorts"
        for(row in old+fresh) {
            for(video in row.videos)if(video.isShort)shorts.putIfAbsent(video.id,video)
            if(row.portrait && row.name.isNotBlank() && (shortsName=="Shorts" || row.name!="Shorts"))shortsName=row.name.take(160)
            val items=row.videos.filterNot{it.isShort}
            if(items.isEmpty())continue
            val previous=regular[row.key]
            val name=if(previous!=null && row.key=="search/videos" && row.name=="Videos")previous.name else row.name
            regular[row.key]=row.copy(name=name,videos=(previous?.videos.orEmpty()+items).distinctBy{it.id}.take(120),portrait=false)
        }
        val seen=HashSet<String>();val result=ArrayList<YouTubeShelf>();var remaining=if(shorts.isEmpty())120 else 100
        for(row in regular.values.take(if(shorts.isEmpty())10 else 9)) {
            val items=row.videos.filter{it.id !in shorts && seen.add(it.id)}.take(remaining)
            if(items.isNotEmpty()){result+=row.copy(videos=items);remaining-=items.size}
            if(remaining==0)break
        }
        val shortLimit=if(result.isEmpty())120 else 20
        val clips=shorts.values.take(shortLimit)
        if(clips.isNotEmpty())result+=YouTubeShelf("search/shorts",shortsName,clips,true)
        return result
    }
}
