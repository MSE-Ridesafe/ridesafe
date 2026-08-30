package de.uhi.enia.ridesafe.export

import de.uhi.enia.ridesafe.data.FuelType
import de.uhi.enia.ridesafe.data.Ride
import de.uhi.enia.ridesafe.data.SavedAddress
import de.uhi.enia.ridesafe.data.SavedPlaceKind
import de.uhi.enia.ridesafe.data.Vehicle
import de.uhi.enia.ridesafe.data.summarizeMerge
import de.uhi.enia.ridesafe.ui.screens.rides.LogbookEntry
import de.uhi.enia.ridesafe.ui.screens.rides.RideExportController
import de.uhi.enia.ridesafe.ui.screens.rides.RideExportState
import de.uhi.enia.ridesafe.ui.screens.rides.RideRow
import de.uhi.enia.ridesafe.ui.screens.rides.exportRequests
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class RidePdfExportTest {
    private fun ride(
        id: Long,
        start: Long,
        duration: Long? = 60_000,
        vehicleId: Long? = 1,
        groupId: Long? = null,
        distance: Double? = 1_000.0,
        startAddress: String? = "Start street",
        endAddress: String? = "End street",
    ) = Ride(
        id = id,
        vehicleId = vehicleId,
        mergeGroupId = groupId,
        startedAtEpochMs = start,
        startedElapsedNanos = 0,
        endedAtEpochMs = duration?.let(start::plus),
        distanceMeters = distance,
        sampleFile = "ride_$id.ndjson.gz",
        startAddress = startAddress,
        endAddress = endAddress,
    )

    private val vehicle = Vehicle(1, "Daily", "VW", "Golf", "AB C 1", FuelType.PETROL, 10_000)
    private val homeAlias = SavedAddress(10, "Home", SavedPlaceKind.HOME, 52.0, 9.0, 100, "home", "Alias address")
    private val officeAlias = SavedAddress(11, "Office", SavedPlaceKind.WORK, 52.1, 9.1, 100, "work", "Alias office")

    @Test
    fun standaloneAndMultipleSelectionsKeepDisplayedOrder() {
        val newest = ride(2, 2_000)
        val oldest = ride(1, 1_000)
        val entries = listOf(LogbookEntry.Single(RideRow(newest, "VW")), LogbookEntry.Single(RideRow(oldest, "VW")))

        val requests = exportRequests(entries, entries.map { it.key }.toSet())
        val journeys = buildExportJourneys(requests, listOf(oldest, newest), listOf(vehicle))

        assertEquals(listOf(2L, 1L), requests.map { it.rideIds.single() })
        assertEquals(listOf(2_000L, 1_000L), journeys.map { it.startedAtEpochMs })
        assertTrue(journeys.all { it.individualRides.isEmpty() })
    }

    @Test
    fun mergedEntryBecomesOneResolvedJourney() {
        val first = ride(1, 1_000, duration = 60_000, groupId = 1, distance = 1_000.0, startAddress = "Full start")
        val last = ride(2, 121_000, duration = 120_000, groupId = 1, distance = 2_000.0, endAddress = "Full end")
        val rows = listOf(RideRow(first, "VW", startPlace = homeAlias), RideRow(last, "VW", endPlace = officeAlias))
        val entry = LogbookEntry.Merged(1, rows, summarizeMerge(listOf(first, last)), "VW")
        val requests = exportRequests(listOf(entry), setOf(entry.key))
        val journeys = buildExportJourneys(requests, listOf(last, first), listOf(vehicle))

        assertEquals(1, journeys.size)
        with(journeys.single()) {
            assertEquals("VW Golf \"Daily\"", vehicle)
            assertEquals(first.startedAtEpochMs, startedAtEpochMs)
            assertEquals(last.endedAtEpochMs, endedAtEpochMs)
            assertEquals(180_000L, durationMs)
            assertEquals(3_000.0, distanceMeters!!, 0.001)
            assertEquals("Full start", startAddress)
            assertEquals("Full end", endAddress)
            assertEquals(listOf(first.startedAtEpochMs, last.startedAtEpochMs), individualRides.map { it.startedAtEpochMs })
            assertEquals(listOf(1_000.0, 2_000.0), individualRides.map { it.distanceMeters })
            assertEquals(distanceMeters, individualRides.sumOf { it.distanceMeters ?: 0.0 }, 0.001)
        }
    }

    @Test
    fun mergedChildrenUseActualAddressesInsteadOfSavedPlaceAliases() {
        val home = homeAlias.copy(address = "Leinkampstraße 25\n31141 Hildesheim")
        val office = officeAlias.copy(address = "Bahnhofstraße 1\n30159 Hannover")
        val first =
            ride(1, 1_000, groupId = 1, startAddress = "Home\n31141 Hildesheim", endAddress = "First actual end")
                .copy(startAddressId = home.id)
        val last =
            ride(2, 121_000, groupId = 1, startAddress = "Second actual start", endAddress = "Office\n30159 Hannover")
                .copy(endAddressId = office.id)

        val journey =
            buildExportJourneys(
                listOf(RideExportRequest("g1", listOf(last.id, first.id, first.id))),
                listOf(last, first),
                listOf(vehicle),
                listOf(home, office),
            ).single()

        assertEquals(2, journey.individualRides.size)
        assertEquals("Leinkampstraße 25\n31141 Hildesheim", journey.startAddress)
        assertEquals("Bahnhofstraße 1\n30159 Hannover", journey.endAddress)
        assertEquals("Leinkampstraße 25\n31141 Hildesheim", journey.individualRides.first().startAddress)
        assertEquals("First actual end", journey.individualRides.first().endAddress)
        assertEquals("Second actual start", journey.individualRides.last().startAddress)
        assertEquals("Bahnhofstraße 1\n30159 Hannover", journey.individualRides.last().endAddress)
    }

    @Test
    fun mixedAndMultipleCombinedSelectionsPreserveLogicalTopLevelOrder() {
        val standalone = ride(10, 10_000)
        val firstGroup = listOf(ride(1, 1_000, groupId = 1), ride(2, 2_000, groupId = 1))
        val secondGroup = listOf(ride(3, 3_000, groupId = 3), ride(4, 4_000, groupId = 3))
        val entries =
            listOf(
                LogbookEntry.Single(RideRow(standalone, "VW")),
                LogbookEntry.Merged(1, firstGroup.map { RideRow(it, "VW") }, summarizeMerge(firstGroup), "VW"),
                LogbookEntry.Merged(3, secondGroup.map { RideRow(it, "VW") }, summarizeMerge(secondGroup), "VW"),
            )
        val requests = exportRequests(entries, entries.map { it.key }.toSet())
        val journeys = buildExportJourneys(requests, listOf(standalone) + firstGroup + secondGroup, listOf(vehicle))

        assertEquals(listOf(10_000L, 1_000L, 3_000L), journeys.map { it.startedAtEpochMs })
        assertEquals(listOf(0, 2, 2), journeys.map { it.individualRides.size })
    }

    @Test
    fun persistedAddressesWinOverSavedPlaceAliases() {
        val persisted =
            ride(
                1,
                1_000,
                startAddress = "Leinkampstraße 25, 31141 Hildesheim",
                endAddress = "Bahnhofstraße 1, 30159 Hannover",
            )
        val selectedRow = RideRow(persisted, "VW", startPlace = homeAlias, endPlace = officeAlias)
        val entry = LogbookEntry.Single(selectedRow)

        val request = exportRequests(listOf(entry), setOf(entry.key))
        val journey = buildExportJourneys(request, listOf(persisted), listOf(vehicle)).single()

        assertEquals("Leinkampstraße 25, 31141 Hildesheim", journey.startAddress)
        assertEquals("Bahnhofstraße 1, 30159 Hannover", journey.endAddress)
        assertFalse(journey.startAddress == homeAlias.label)
        assertFalse(journey.endAddress == officeAlias.label)
    }

    @Test
    fun persistedSavedPlaceLabelsResolveToActualAddresses() {
        val home = homeAlias.copy(address = "Leinkampstraße 25\n31141 Hildesheim")
        val office = officeAlias.copy(address = "Bahnhofstraße 1\n30159 Hannover")
        val persisted =
            ride(
                1,
                1_000,
                startAddress = "Home\n31141 Hildesheim",
                endAddress = "Office\n30159 Hannover",
            ).copy(startAddressId = home.id, endAddressId = office.id)

        val journey =
            buildExportJourneys(
                requests = listOf(RideExportRequest("r1", listOf(1))),
                rides = listOf(persisted),
                vehicles = listOf(vehicle),
                savedAddresses = listOf(home, office),
            ).single()

        assertEquals("Leinkampstraße 25\n31141 Hildesheim", journey.startAddress)
        assertEquals("Bahnhofstraße 1\n30159 Hannover", journey.endAddress)
    }

    @Test
    fun savedPlaceLabelWithoutActualAddressIsNotExported() {
        val home = homeAlias.copy(address = null)
        val persisted = ride(1, 1_000, startAddress = "Home\n31141 Hildesheim").copy(startAddressId = home.id)

        val journey =
            buildExportJourneys(
                listOf(RideExportRequest("r1", listOf(1))),
                listOf(persisted),
                listOf(vehicle),
                listOf(home),
            ).single()

        assertNull(journey.startAddress)
        assertEquals("Unavailable", exportAddress(journey.startAddress))
    }

    @Test
    fun missingPersistedAddressDoesNotFallBackToSavedPlaceAlias() {
        val persisted = ride(1, 1_000, startAddress = null, endAddress = null)
        val entry = LogbookEntry.Single(RideRow(persisted, "VW", startPlace = homeAlias, endPlace = officeAlias))

        val request = exportRequests(listOf(entry), setOf(entry.key))
        val journey = buildExportJourneys(request, listOf(persisted), listOf(vehicle)).single()

        assertNull(journey.startAddress)
        assertNull(journey.endAddress)
        assertEquals("Unavailable", exportAddress(journey.startAddress))
        assertEquals("Unavailable", exportAddress(journey.endAddress))
    }

    @Test
    fun missingOptionalDataDegradesGracefully() {
        val missing = ride(1, 1_000, duration = null, vehicleId = 99, distance = null, startAddress = null, endAddress = null)
        val journey = buildExportJourneys(listOf(RideExportRequest("r1", listOf(1))), listOf(missing), emptyList()).single()

        assertEquals("Unknown vehicle", journey.vehicle)
        assertNull(journey.endedAtEpochMs)
        assertNull(journey.durationMs)
        assertNull(journey.startAddress)
        assertNull(journey.endAddress)
        assertNull(journey.distanceMeters)
    }

    @Test
    fun deletedRideAndDuplicatePhysicalIdsAreHandled() {
        val one = ride(1, 1_000)
        val deleted = ride(2, 2_000, groupId = 1)
        val merged =
            LogbookEntry.Merged(
                1,
                listOf(RideRow(one, "VW"), RideRow(deleted, "VW")),
                summarizeMerge(listOf(one, deleted)),
                "VW",
            )
        val duplicateSingle = LogbookEntry.Single(RideRow(one, "VW"))
        val requests = exportRequests(listOf(merged, duplicateSingle), setOf(merged.key, duplicateSingle.key))
        val journeys = buildExportJourneys(requests, listOf(one), listOf(vehicle))

        assertEquals(2, requests.size)
        assertEquals(listOf(1L, 2L), requests[0].rideIds)
        assertEquals(listOf(1L), requests[1].rideIds)
        assertEquals(2, journeys.size)
        assertEquals(one.startedAtEpochMs, journeys[0].startedAtEpochMs)
        assertEquals(1, journeys[0].individualRides.size)
        assertTrue(journeys[1].individualRides.isEmpty())
    }

    @Test
    fun filenameAndDuplicatesAreDeterministic() {
        val desired = exportFileName(LocalDate.of(2026, 8, 22))
        assertEquals("RideSafe_Rides_Export_2026-08-22.pdf", desired)
        assertEquals(desired, duplicateSafeFileName(desired, emptySet()))
        assertEquals("RideSafe_Rides_Export_2026-08-22_2.pdf", duplicateSafeFileName(desired, setOf(desired)))
        assertEquals(
            "RideSafe_Rides_Export_2026-08-22_4.pdf",
            duplicateSafeFileName(
                desired,
                setOf(desired, desired.removeSuffix(".pdf") + "_2.pdf", desired.removeSuffix(".pdf") + "_3.pdf"),
            ),
        )
    }

    @Test
    fun exportMetadataDoesNotRequireRouteFiles() {
        val first = ride(1, 1_000, groupId = 1).copy(sampleFile = "missing_route_1.ndjson.gz")
        val last = ride(2, 2_000, groupId = 1).copy(sampleFile = "corrupt_route_2.ndjson.gz")
        val journey = buildExportJourneys(listOf(RideExportRequest("g1", listOf(1, 2))), listOf(first, last), listOf(vehicle)).single()

        assertEquals(first.startedAtEpochMs, journey.startedAtEpochMs)
        assertEquals(2, journey.individualRides.size)
        assertEquals(listOf(first.distanceMeters, last.distanceMeters), journey.individualRides.map { it.distanceMeters })
    }

    @Test
    fun notificationPermissionAndAppSettingBothGatePosting() {
        assertTrue(notificationsAllowed(permissionGranted = true, notificationsEnabled = true))
        assertFalse(notificationsAllowed(permissionGranted = false, notificationsEnabled = true))
        assertFalse(notificationsAllowed(permissionGranted = true, notificationsEnabled = false))
    }

    @Test
    fun pendingIntentIdentityChangesForEveryExportUri() {
        val first = exportPendingIntentRequestCode("RideSafe_Rides_Export_2026-08-22.pdf", "content://downloads/1")
        val second = exportPendingIntentRequestCode("RideSafe_Rides_Export_2026-08-22_2.pdf", "content://downloads/2")

        assertFalse(first == second)
    }

    @Test
    fun exportControllerReportsSuccessAndConsumesResult() =
        runBlocking {
            val completed = CompletedRideExport("export.pdf", "content://downloads/1")
            val controller = RideExportController(this, operation = { _, _ -> completed })
            assertTrue(controller.start(listOf(RideExportRequest("r1", listOf(1))), RideExportFormat.PDF))
            assertEquals(RideExportState.Exporting, controller.state.value)
            yield()
            assertEquals(RideExportState.Success(completed), controller.state.value)
            controller.consumeResult()
            assertEquals(RideExportState.Idle, controller.state.value)
        }

    @Test
    fun exportControllerReportsFailureAndLeavesLoadingState() =
        runBlocking {
            val controller = RideExportController(this, operation = { _, _ -> error("disk full") })
            assertTrue(controller.start(listOf(RideExportRequest("r1", listOf(1))), RideExportFormat.CSV))
            yield()
            assertEquals(RideExportState.Error, controller.state.value)
            assertFalse(controller.state.value == RideExportState.Exporting)
            controller.consumeResult()
            assertEquals(RideExportState.Idle, controller.state.value)
        }

    @Test
    fun exportControllerRejectsConcurrentRequest() =
        runBlocking {
            val release = CompletableDeferred<Unit>()
            var calls = 0
            val controller =
                RideExportController(
                    this,
                    operation = { _, _ ->
                        calls++
                        release.await()
                        CompletedRideExport("export.pdf", "content://downloads/1")
                    },
                )
            val request = listOf(RideExportRequest("r1", listOf(1)))
            assertTrue(controller.start(request, RideExportFormat.CSV))
            yield()
            assertFalse(controller.start(request, RideExportFormat.PDF))
            assertEquals(1, calls)
            release.complete(Unit)
            yield()
            assertTrue(controller.state.value is RideExportState.Success)
        }
}
