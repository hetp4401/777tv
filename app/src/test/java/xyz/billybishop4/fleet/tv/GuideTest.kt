package xyz.billybishop4.fleet.tv

import org.json.JSONObject
import org.json.JSONArray
import org.junit.Test
import org.junit.Assert.*

class GuideTest {
    @Test fun timestampsAndChannelMappedResponsesUseOnlyRequestedSchedule() {
        val response = JSONObject("""{"data":{"10":[{"name":"News","start_timestamp":"1700000000","stop_timestamp":1700003600}],"20":[{"name":"Other","start_timestamp":1700000000,"stop_timestamp":1700003600}]}}""")
        val guide = parseGuide(response, "10", "")
        assertEquals(1, guide.size); assertEquals("News", guide.single().name); assertEquals(1700000000000L, guide.single().startMs)
    }
    @Test fun incompleteOrReversedProgramsAreNotInvented() {
        val guide = parseGuide(JSONArray("""[{"name":"No times"},{"name":"Reversed","start_timestamp":200,"stop_timestamp":100},{"name":"Valid","start_timestamp":1700000000000,"stop_timestamp":1700003600000}]"""), "1", "")
        assertEquals(listOf("Valid"), guide.map { it.name })
    }
    @Test fun providerTimezoneIsUsedForLocalDateTimes() {
        val value = JSONArray("""[{"name":"Morning","start":"2026-11-15 08:00:00","end":"2026-11-15 09:00:00"}]""")
        assertEquals(8 * 3600000L, parseGuide(value, "1", "America/Los_Angeles").single().startMs - parseGuide(value, "1", "UTC").single().startMs)
    }
}
