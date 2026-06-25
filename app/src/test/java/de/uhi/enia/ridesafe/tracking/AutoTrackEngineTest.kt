package de.uhi.enia.ridesafe.tracking

import org.junit.Assert.assertEquals
import org.junit.Test

private class FakeRecorder : RideRecorder {
    val events = mutableListOf<String>()

    override fun onTripStart(vehicleId: Long?) {
        events += "start:$vehicleId"
    }

    override fun onTripEnd() {
        events += "end"
    }
}

class AutoTrackEngineTest {
    private fun engineWith(recorder: RideRecorder) = AutoTrackEngine { recorder }

    @Test
    fun off_ignores_connect() {
        val rec = FakeRecorder()
        engineWith(rec).deviceConnected(AutoTrackMode.OFF, "AA", 1L)
        assertEquals(emptyList<String>(), rec.events)
    }

    @Test
    fun paired_connect_then_disconnect_is_one_trip() {
        val rec = FakeRecorder()
        val engine = engineWith(rec)
        engine.deviceConnected(AutoTrackMode.PAIRED_ONLY, "AA", 7L)
        engine.deviceDisconnected("AA")
        assertEquals(listOf("start:7", "end"), rec.events)
    }

    @Test
    fun double_connect_dedups_and_waits_for_all_to_drop() {
        val rec = FakeRecorder()
        val engine = engineWith(rec)
        engine.deviceConnected(AutoTrackMode.PAIRED_ONLY, "AA", 7L)
        engine.deviceConnected(AutoTrackMode.PAIRED_ONLY, "BB", 7L) // same car, second MAC
        assertEquals(listOf("start:7"), rec.events)
        engine.deviceDisconnected("AA")
        assertEquals(listOf("start:7"), rec.events) // still connected via BB
        engine.deviceDisconnected("BB")
        assertEquals(listOf("start:7", "end"), rec.events)
    }

    @Test
    fun any_mapped_connect_starts_immediately_assigned() {
        val rec = FakeRecorder()
        engineWith(rec).deviceConnected(AutoTrackMode.ANY, "AA", 3L)
        assertEquals(listOf("start:3"), rec.events)
    }

    @Test
    fun any_activity_with_no_device_starts_unassigned() {
        val rec = FakeRecorder()
        val engine = engineWith(rec)
        engine.activityVehicleStart(AutoTrackMode.ANY, emptyMap())
        engine.activityVehicleEnd(AutoTrackMode.ANY)
        assertEquals(listOf("start:null", "end"), rec.events)
    }

    @Test
    fun any_activity_attributes_to_present_mapped_device() {
        val rec = FakeRecorder()
        val engine = engineWith(rec)
        engine.deviceConnected(AutoTrackMode.OFF, "AA", null) // present, but OFF: no trip yet
        engine.activityVehicleStart(AutoTrackMode.ANY, mapping = mapOf("AA" to 9L))
        assertEquals(listOf("start:9"), rec.events)
    }

    @Test
    fun paired_mode_ignores_activity_recognition() {
        val rec = FakeRecorder()
        engineWith(rec).activityVehicleStart(AutoTrackMode.PAIRED_ONLY, emptyMap())
        assertEquals(emptyList<String>(), rec.events)
    }
}
