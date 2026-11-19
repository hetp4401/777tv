package xyz.billybishop4.fleet.tv

import com.grack.nanojson.JsonParser
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.junit.Assert.*
import org.junit.Test

class YouTubeStoryboardTest {
    private val base="https://i.ytimg.com/sb/abcdefghijk/storyboard3_L\$L/\$N.jpg?sqp=fixture"
    private fun level(width:Int=160,height:Int=90,count:Int=90,columns:Int=5,rows:Int=5,interval:Long=2000,
        name:String="M\$M",signature:String="rs\$private-signature")="$width#$height#$count#$columns#$rows#$interval#$name#$signature"

    @Test fun realSpecLevelsPickABoundedSheetAndMapTimestampsToCellsAcrossPages() {
        val board=YouTubeStoryboard.decode("$base|48#27#100#10#10#0#default#small|${level(80,45,columns=10,rows=10)}|${level()}|${level(320,180,columns=3,rows=3)}",180_000)!!
        assertEquals(320,board.frameWidth);assertTrue(board.sheetWidth*board.sheetHeight<=1_000_000)
        assertTrue(board.frame(0).url.contains("storyboard3_L3/M0.jpg"))
        assertEquals(1,board.frame(2000).column);assertEquals(0,board.frame(2000).row)
        assertTrue(board.frame(18_000).url.contains("M1.jpg"));assertEquals(0,board.frame(18_000).column)
        val last=board.frame(Long.MAX_VALUE);assertEquals(89,last.index);assertEquals(2,last.row);assertEquals(2,last.column)
        assertTrue(last.url.contains("M9.jpg"));assertEquals(0,board.frame(-1).index)
    }

    @Test fun oversizedLevelsAreSkippedWithoutDiscardingAUsableSmallerContactSheet() {
        val board=YouTubeStoryboard.decode("$base|${level()}|${level(640,360,columns=20,rows=20)}",180_000)!!
        assertEquals(160,board.frameWidth);assertTrue(board.frame(0).url.contains("storyboard3_L0"))
        assertNull(YouTubeStoryboard.decode("$base|${level(count=60_001)}",180_000))
        assertNull(YouTubeStoryboard.decode("$base|${level(columns=21)}",180_000))
        assertNull(YouTubeStoryboard.decode("$base|80#45#20#5#3#-1#M\$M#signature",180_000))
        assertNull(YouTubeStoryboard.decode("$base|80#45#20#5#3#2#M\$M#signature#extra",180_000))
    }

    @Test fun zeroIntervalDefaultGridUsesActualVideoDurationWithoutInventingExtraSheets() {
        val board=YouTubeStoryboard.decode("$base|${level(80,45,10,2,5,0,"default")}",10_000)!!
        assertEquals(5,board.frame(5000).index);assertEquals(1,board.frame(5000).column);assertEquals(2,board.frame(5000).row)
        assertTrue(board.frame(5000).url.contains("default.jpg"))
        assertNull(YouTubeStoryboard.decode("$base|${level(80,45,11,2,5,0,"default")}",10_000))
        assertNull(YouTubeStoryboard.decode("$base|${level()}",0))
        assertNull(YouTubeStoryboard.decode("$base|${level()}",604_800_001))
    }

    @Test fun signatureQueriesRemainPrivateAndAreEncodedAsData() {
        val board=YouTubeStoryboard.decode("$base|${level(signature="rs\$private-signature")}",180_000)!!
        assertEquals("rs\$private-signature",board.frame(0).url.toHttpUrlOrNull()!!.queryParameter("sigh"))
        assertFalse(board.toString().contains("private-signature"));assertFalse(board.toString().contains("https"))
        assertFalse(board.frame(0).toString().contains("private-signature"));assertFalse(board.frame(0).toString().contains("https"))
        assertNull(YouTubeStoryboard.decode("$base|${level(signature="bad&injected=value")}",180_000))
    }

    @Test fun onlyRealThumbnailHostsOrExplicitLoopbackFixturesCanSupplySprites() {
        for(url in listOf(base.replace("https://","http://"),base.replace("i.ytimg.com","i.ytimg.com.attacker.test"),
            base.replace("i.ytimg.com","user@i.ytimg.com"),base.replace("i.ytimg.com","i.ytimg.com:8443"),
            base.replace("/sb/","/unrelated/"),"$base#fragment"))assertNull(YouTubeStoryboard.decode("$url|${level()}",180_000))
        val fixture="http://10.0.2.2:8878/youtube/storyboard/\$N.png|${level()}"
        assertNull(YouTubeStoryboard.decode(fixture,180_000))
        assertNotNull(YouTubeStoryboard.decode(fixture,180_000,"http://10.0.2.2:8878/youtube"))
        assertNull(YouTubeStoryboard.decode(fixture,180_000,"http://10.0.2.2:9999/youtube"))
        assertNull(YouTubeStoryboard.decode(fixture,180_000,"http://private.example/youtube"))
    }

    @Test fun quickPlaybackAdoptsOnlyOptionalMatchingRegularVideoStoryboardMetadata() {
        val json=JsonParser.`object`().from("""{"playabilityStatus":{"status":"OK"},"videoDetails":{"videoId":"abcdefghijk","lengthSeconds":"180"},"streamingData":{"hlsManifestUrl":"https://media.example.test/master.m3u8"}}""")
        json["storyboards"]=com.grack.nanojson.JsonObject().apply {
            put("playerStoryboardSpecRenderer",com.grack.nanojson.JsonObject().apply{put("spec","$base|${level()}")})
        }
        val video=YouTubeVideo("abcdefghijk","A real video")
        assertNotNull(playerWatch(video,json)!!.youtubeStoryboard)
        assertNull(playerWatch(video.copy(isShort=true),json)!!.youtubeStoryboard)
        json.getObject("storyboards").getObject("playerStoryboardSpecRenderer")["spec"]="malformed optional metadata"
        assertNotNull(playerWatch(video,json));assertNull(playerWatch(video,json)!!.youtubeStoryboard)
    }
}
