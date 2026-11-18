package xyz.billybishop4.fleet.tv

import org.junit.Assert.*
import org.junit.Test

class LiveInfoTest {
    @Test fun currentAndNextProgrammeStayUsefulAcrossGuideGaps() {
        val current=GuideProgram("Current",1000,2000)
        val next=GuideProgram("Next",2500,3500)
        assertEquals("Current  ·  Next: Next",liveProgramLabel(listOf(next,current),1500))
        assertEquals("Next: Next",liveProgramLabel(listOf(current,next),2200))
        assertEquals("",liveProgramLabel(listOf(current,next),4000))
    }
    @Test fun futureOnlyGuideShowsTheSoonestKnownProgramme() {
        assertEquals("Next: Soon",liveProgramLabel(listOf(GuideProgram("Later",5000,6000),GuideProgram("Soon",3000,4000)),2000))
        assertEquals("",liveProgramLabel(emptyList(),2000))
    }
}
