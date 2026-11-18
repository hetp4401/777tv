package xyz.billybishop4.fleet.tv

internal enum class YouTubeQueueStatus { AVAILABLE,QUEUED,FULL,CURRENT,PHONE,UNSUPPORTED }

/** Metadata only: twenty explicit choices, twenty previously opened items and
 * forty real watch-next candidates. The phone's queue remains with Lounge. */
internal class YouTubeLocalQueue {
    private val queued=ArrayList<YouTubeVideo>()
    private val trail=ArrayList<YouTubeVideo>();private var cursor=-1
    private var related:List<YouTubeVideo> = emptyList()
    var phoneOwned=false;private set
    val currentId:String? get()=trail.getOrNull(cursor)?.id
    val queuedVideos:List<YouTubeVideo> get()=queued.toList()
    fun beginLocal(){clear();phoneOwned=false}
    fun beginRemote(){clear();phoneOwned=true}
    fun releasePhone(){phoneOwned=false}
    fun clear(){queued.clear();trail.clear();cursor=-1;related=emptyList()}
    fun status(video:YouTubeVideo):YouTubeQueueStatus=when {
        phoneOwned->YouTubeQueueStatus.PHONE
        video.isShort || !YouTubeDecode.validId(video.id)->YouTubeQueueStatus.UNSUPPORTED
        video.id==currentId->YouTubeQueueStatus.CURRENT
        queued.any{it.id==video.id}->YouTubeQueueStatus.QUEUED
        queued.size>=20->YouTubeQueueStatus.FULL
        else->YouTubeQueueStatus.AVAILABLE
    }
    fun enqueue(video:YouTubeVideo):Boolean {
        if(status(video)!=YouTubeQueueStatus.AVAILABLE)return false
        queued+=video;return true
    }
    fun opened(video:YouTubeVideo) {
        if(video.isShort)return
        when {
            trail.getOrNull(cursor+1)?.id==video.id->cursor++
            trail.getOrNull(cursor-1)?.id==video.id->cursor--
            currentId==video.id->trail[cursor]=video
            else->{while(trail.size>cursor+1)trail.removeAt(trail.lastIndex);trail+=video
                if(trail.size>20)trail.removeAt(0);cursor=trail.lastIndex}
        }
        queued.removeAll{it.id==video.id};related=emptyList()
    }
    fun offer(videoId:String,videos:List<YouTubeVideo>):Boolean {
        if(currentId!=videoId)return false
        val next=videos.asSequence().filter{!it.isShort && YouTubeDecode.validId(it.id) && it.id!=videoId}.distinctBy{it.id}.take(40).toList()
        if(related==next)return false
        related=next;return true
    }
    fun peek(videoId:String,direction:Int):YouTubeVideo? {
        if(phoneOwned || currentId!=videoId || direction != -1 && direction != 1)return null
        if(direction<0)return trail.getOrNull(cursor-1)
        return trail.getOrNull(cursor+1)?:queued.firstOrNull()?:related.firstOrNull{candidate->trail.none{it.id==candidate.id}}
    }
}
