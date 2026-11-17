@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package xyz.billybishop4.fleet.tv

import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

internal fun privatePortal(file:File):Provider {
    val j=JSONObject(file.readText())
    return validatedPortal(Provider("private-device-test",j.optString("name"),j.getString("url"),mac=j.getString("mac"),
        serialNumber=j.optString("serialNumber"),deviceId=j.optString("deviceId"),deviceId2=j.optString("deviceId2"),signature=j.optString("signature"),
        stbType=j.optString("stbType","MAG250"),hwVersion=j.optString("hwVersion"),hwVersion2=j.optString("hwVersion2"),pin=j.optString("pin"),
        version=j.optString("version"),imageVersion=j.optString("imageVersion"),username=j.optString("username"),password=j.optString("password"),userAgent=j.optString("userAgent"),timezone=j.optString("timezone")))
}
class PortalPlaybackTest:NativeTestBase(){
    @Test fun suppliedPortalPlaysThreeChannelsSequentially(){
        val dir=ui{a.getExternalFilesDir(null)!!};val input=File(dir,"portal-test.json");assumeTrue("Private portal settings were not supplied",input.isFile)
        val savedPreferences=ui{a.model.preferences}
        val p=privatePortal(input);val report=JSONObject().put("played",JSONArray()).put("ui","native Android views")
        val output=File(dir,"portal-playback-result.json")
        try{
            ui{a.model.showProfiles()}
            val channels=runBlocking{Repository(emptyList()).channels(p)}
            report.put("channelCount",channels.size)
            val patterns=listOf("\\bCNN\\b","\\bCTV NEWS\\b","\\b(?:ZEE TV|SONY ENTERTAINMENT|STAR PLUS)\\b").map{Regex(it,RegexOption.IGNORE_CASE)}
            val choices=patterns.mapNotNull{pattern->channels.firstOrNull{pattern.containsMatchIn(it.name)&&!it.name.contains("4K",true)&&!it.name.contains("8K",true)}}.distinctBy{it.id}
            assertEquals(3,choices.size)
            for((i,c)in choices.withIndex()){
                report.put("phase","Playing ${c.name}");output.writeText(report.toString(2))
                ui{a.model.updatePreferences(a.model.preferences.copy(liveRetries=1));a.model.open(Route.Player(Watch(Title(c.id,c.name,"channel"),Stream(c.name,""),live=true,provider=p,channel=c)))}
                await(90000){ui{a.player?.let{it.isPlaying&&it.videoSize.width>0&&(it.videoDecoderCounters?.renderedOutputBufferCount?:0)>=200}?:false}}
                val proof=ui{val v=a.player!!;JSONObject().put("channel",c.name).put("width",v.videoSize.width).put("height",v.videoSize.height)
                    .put("renderedFrames",v.videoDecoderCounters!!.renderedOutputBufferCount).put("droppedFrames",v.videoDecoderCounters!!.droppedBufferCount)
                    .put("videoMime",v.videoFormat?.sampleMimeType).put("audioMime",v.audioFormat?.sampleMimeType)
                    .put("audioSelected",v.currentTracks.groups.any{it.type==androidx.media3.common.C.TRACK_TYPE_AUDIO&&it.isSelected}).put("speed",v.playbackParameters.speed)}
                assertTrue(proof.getBoolean("audioSelected"));report.getJSONArray("played").put(proof);output.writeText(report.toString(2))
                device.executeShellCommand("screencap -p ${File(dir,"portal-playback-${i+1}.png").absolutePath}")
                ui{a.model.showProfiles()};await{player()==null}
            }
            report.put("success",true).put("phase","Complete; players stopped")
        }finally{ui{a.model.updatePreferences(savedPreferences);a.model.showProfiles()};await{player()==null};input.delete();output.writeText(report.toString(2))}
    }
}
