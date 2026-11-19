package xyz.billybishop4.fleet.tv

import org.junit.Assert.*
import org.junit.Test

class PlaybackStartPositionTest {
    private fun watch()=Watch(Title("abcdefghijk","A YouTube video","youtube"),Stream("Fixture","https://fixture.invalid/video"),remoteResumePositionMs=45000)
    @Test fun explicitPhonePositionWinsIncludingZeroAndResumeDisabled() {
        assertEquals(0,playbackStartPosition(watch().copy(requestedPositionMs=0),true,12000))
        assertEquals(8000,playbackStartPosition(watch().copy(requestedPositionMs=8000),false,12000))
        assertEquals(0,playbackStartPosition(watch().copy(requestedPositionMs=-1000),true,12000))
    }
    @Test fun localResumeWinsAndAccountMetadataIsTheFallback() {
        assertEquals(12000,playbackStartPosition(watch(),true,12000))
        assertEquals(45000,playbackStartPosition(watch(),true,0))
        assertEquals(0,playbackStartPosition(watch(),false,12000))
        assertEquals(0,playbackStartPosition(watch().copy(remoteResumePositionMs=-1),true,0))
    }
    @Test fun shortsStartFreshAndLiveNeverUsesAnyResumeOrPhonePosition() {
        assertEquals(0,playbackStartPosition(watch().copy(short=true),true,12000))
        assertEquals(7000,playbackStartPosition(watch().copy(short=true,requestedPositionMs=7000),true,12000))
        assertEquals(0,playbackStartPosition(watch().copy(live=true,requestedPositionMs=7000),true,12000))
    }
}
