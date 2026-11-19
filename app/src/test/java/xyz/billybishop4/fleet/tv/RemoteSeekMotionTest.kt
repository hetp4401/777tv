package xyz.billybishop4.fleet.tv

import org.junit.Assert.*
import org.junit.Test

class RemoteSeekMotionTest {
    @Test fun tapsUseTheConfiguredTimeInsteadOfMediaLength() {
        for(duration in listOf(60000L,10800000L,86400000L))for(step in listOf(5000L,10000L,30000L)) {
            val motion=RemoteSeekMotion(step)
            assertEquals(20000+step,motion.begin(1,100,100,20000,duration))
            assertEquals(20000+step,motion.sample(140,duration,release=true))
        }
    }
    @Test fun theInitialTapWaitsBeforeAContinuousNoticeableRamp() {
        val motion=RemoteSeekMotion(10000)
        assertEquals(10000L,motion.begin(1,0,0,0,10800000))
        assertEquals(10000L,motion.sample(300,10800000))
        assertEquals(16900L,motion.sample(600,10800000))
        assertEquals(28900L,motion.sample(1000,10800000))
        assertEquals(72900L,motion.sample(2000,10800000))
        assertEquals(220900L,motion.sample(4000,10800000))
    }
    @Test fun denseSparseOrMissingRepeatsHaveIdenticalTravel() {
        for(duration in listOf(300000L,10800000L)) {
            val results=listOf(16L,50L,250L,3000L).map { cadence->
                val motion=RemoteSeekMotion(10000);motion.begin(1,100,100,0,duration)
                var time=100L+cadence
                while(time<2100){motion.sample(time,duration);time+=cadence}
                motion.sample(2100,duration,release=true)
            }
            assertEquals(listOf(72900L,72900L,72900L,72900L),results)
        }
    }
    @Test fun frameStepsStayBoundedAfterTheRateHasReachedItsCap() {
        val motion=RemoteSeekMotion(10000);motion.begin(1,0,0,0,10800000)
        var previous=motion.sample(300,10800000)
        for(time in 316L..8000L step 16) {
            val next=motion.sample(time,10800000)
            assertTrue(next>=previous);assertTrue("A frame made a per-repeat jump",next-previous<=1921)
            previous=next
        }
        val first=motion.sample(9000,10800000)
        assertEquals(120000L,motion.sample(10000,10800000)-first)
    }
    @Test fun reversalStartsWithOneFreshStepAndResetsTheRamp() {
        val motion=RemoteSeekMotion(10000);motion.begin(1,0,0,1000000,10800000)
        val origin=motion.sample(4000,10800000)
        assertEquals(origin-10000,motion.begin(-1,4000,4000,origin,10800000))
        assertEquals(origin-10000,motion.sample(4300,10800000))
        assertEquals(origin-28900,motion.sample(5000,10800000))
    }
    @Test fun lateReleaseUsesItsEventTimeAndStoppedMotionCannotContinue() {
        val motion=RemoteSeekMotion(10000);motion.begin(1,100,100,20000,10800000)
        assertTrue(motion.sample(1500,10800000)>30000)
        assertEquals(30000L,motion.sample(200,10800000,release=true))
        motion.stop();assertFalse(motion.active)
        assertEquals(30000L,motion.sample(100000,10800000))
        assertEquals(20000L,motion.begin(-1,100000,100000,30000,10800000))
    }
    @Test fun boundariesClampWithoutOverflowOrChangingTheStep() {
        val motion=RemoteSeekMotion(10000)
        assertEquals(0L,motion.begin(-1,0,0,100,60000))
        assertEquals(60000L,motion.begin(1,0,0,59000,60000))
        assertEquals(Long.MAX_VALUE,motion.begin(1,0,0,Long.MAX_VALUE-100,Long.MAX_VALUE))
        assertEquals(Long.MAX_VALUE,motion.sample(1000000000,Long.MAX_VALUE))
    }
}
