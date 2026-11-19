package xyz.billybishop4.fleet.tv

import java.net.URI
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** An optional, memory-only contact-sheet description from an already fetched
 * player response. URLs/signatures deliberately stay out of diagnostic strings. */
class YouTubeStoryboard private constructor(
    private val template:String,internal val frameWidth:Int,internal val frameHeight:Int,
    internal val columns:Int,internal val rows:Int,internal val frameCount:Int,
    private val intervalMs:Long,private val durationMs:Long,
) {
    internal val sheetWidth get()=frameWidth*columns
    internal val sheetHeight get()=frameHeight*rows
    internal fun frame(positionMs:Long):YouTubeStoryboardFrame {
        val at=positionMs.coerceIn(0,durationMs)
        val index=(if(intervalMs>0)at/intervalMs else at*frameCount/durationMs)
            .coerceIn(0,frameCount-1L).toInt()
        val cells=columns*rows
        return YouTubeStoryboardFrame(template.replace("\$M",(index/cells).toString()),index%columns,
            (index%cells)/columns,frameWidth,frameHeight,columns,rows,index)
    }
    override fun toString()="YouTubeStoryboard(frame=${frameWidth}x$frameHeight, frames=$frameCount)"

    companion object {
        private val integer=Regex("[0-9]{1,9}")
        private val imageName=Regex("[A-Za-z0-9_\\-$]{1,80}")
        private val signature=Regex("[A-Za-z0-9_\\-$=+/.%]{0,2048}")
        private val imageHost=Regex("i[0-9]?\\.ytimg\\.com")
        private data class Level(val index:Int,val width:Int,val height:Int,val count:Int,val columns:Int,val rows:Int,
            val interval:Long,val name:String,val signature:String)

        /** Invalid optional metadata never changes playback availability. Only
         * known thumbnail hosts, or the explicitly supplied loopback test origin,
         * are accepted. A sheet is at most one million decoded source pixels. */
        internal fun decode(spec:String,durationMs:Long,fixtureOrigin:String?=null):YouTubeStoryboard?=runCatching {
            if(spec.length !in 1..16_384 || durationMs !in 1..604_800_000L)return null
            val parts=spec.split('|',limit=10)
            if(parts.size !in 2..9)return null
            val base=parts[0].replace("\\/","/")
            val parsed=base.toHttpUrlOrNull()?:return null
            if(parsed.username.isNotBlank() || parsed.password.isNotBlank() || parsed.fragment!=null)return null
            val fixture=fixtureOrigin?.let { value->
                val origin=URI(value)
                origin.host in setOf("10.0.2.2","127.0.0.1","localhost") &&
                    origin.scheme in setOf("http","https") && sameOrigin(base,value) &&
                    parsed.encodedPath.startsWith(origin.path.trimEnd('/')+"/storyboard/")
            }==true
            if(!fixture && (!parsed.isHttps || parsed.port!=443 || !imageHost.matches(parsed.host) || !parsed.encodedPath.startsWith("/sb/")))return null
            val levels=parts.drop(1).mapIndexedNotNull { index,value->
                val fields=value.split('#',limit=9)
                if(fields.size!=8 || fields.take(6).any{!integer.matches(it)})return@mapIndexedNotNull null
                val values=fields.take(6).map{it.toLongOrNull()?:return@mapIndexedNotNull null}
                val (width,height,count,columns,rows)=values.take(5)
                val interval=values[5]
                if(width !in 48..640 || height !in 27..360 || count !in 1..60_000 || columns !in 1..20 || rows !in 1..20 ||
                    width*columns*height*rows>1_000_000 || interval !in 0..604_800_000 ||
                    !imageName.matches(fields[6]) || !signature.matches(fields[7]))return@mapIndexedNotNull null
                if(interval==0L && count>columns*rows)return@mapIndexedNotNull null
                Level(index,width.toInt(),height.toInt(),count.toInt(),columns.toInt(),rows.toInt(),interval,fields[6],fields[7])
            }
            val chosen=levels.filter{it.width<=320}.maxByOrNull{it.width}
                ?:levels.minByOrNull{it.width}?:return null
            val expanded=base.replace("\$L",chosen.index.toString()).replace("\$N",chosen.name)
            if(!expanded.contains("\$M") && chosen.count>chosen.columns*chosen.rows)return null
            val url=expanded.toHttpUrlOrNull()?.newBuilder()?.apply {
                if(chosen.signature.isNotEmpty())setQueryParameter("sigh",chosen.signature)
            }?.build()?.toString()?:return null
            YouTubeStoryboard(url,chosen.width,chosen.height,chosen.columns,chosen.rows,chosen.count,chosen.interval,durationMs)
        }.getOrNull()
    }
}

internal class YouTubeStoryboardFrame(val url:String,val column:Int,val row:Int,val width:Int,val height:Int,
    val columns:Int,val rows:Int,val index:Int) {
    val sheetWidth get()=width*columns
    val sheetHeight get()=height*rows
    override fun toString()="YouTubeStoryboardFrame(index=$index, cell=$column/$row)"
}
