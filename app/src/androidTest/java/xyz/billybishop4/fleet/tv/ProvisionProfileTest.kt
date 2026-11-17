package xyz.billybishop4.fleet.tv

import java.io.File
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class ProvisionProfileTest:NativeTestBase(){
    @Test fun installPrivateProfileAsDefault(){
        val dir=ui{a.getExternalFilesDir(null)!!};val input=File(dir,"profile-setup.json");assumeTrue("No profile setup file supplied",input.isFile)
        try{
            val p=privatePortal(input).let{raw->raw.copy(id=ui{a.model.providers.firstOrNull{it.url==raw.url&&it.mac==raw.mac}?.id}?:UUID.randomUUID().toString())}
            val config=JSONObject(input.readText())
            ui{a.model.saveProvider(p);a.model.makeDefaultProfile(p.id);a.model.top(Section.LIVE)}
            await(60000){ui{!a.model.busy}}
            assertNull(ui{a.model.error});assertEquals(p,ui{Store(a).providers().getOrThrow().first{it.id==p.id}});assertEquals(p.id,ui{Store(a).preferences().defaultProfileId});assertTrue(ui{a.model.channels.isNotEmpty()})
            File(dir,"profile-setup-result.json").writeText(JSONObject().put("success",true).put("profile",p.name).put("channelCount",ui{a.model.channels.size}).put("default",true).toString(2))
        }finally{input.delete()}
    }
}
