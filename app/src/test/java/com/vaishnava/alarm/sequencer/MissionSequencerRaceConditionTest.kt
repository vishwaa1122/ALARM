package com.vaishnava.alarm.sequencer

import android.content.Context
import android.content.Intent
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MissionSequencerRaceConditionTest {

    private lateinit var context: Context
    private lateinit var sequencer: MissionSequencer
    private lateinit var queueStore: MissionQueueStore
    private lateinit var currentMissionStore: CurrentMissionStore
    private val testScope = TestScope()

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        queueStore = mockk(relaxed = true)
        currentMissionStore = mockk(relaxed = true)
        
        // Mock static methods
        mockkStatic("com.vaishnava.alarm.sequencer.MissionLoggerKt")
        every { MissionLogger.log(any()) } just Runs
        every { MissionLogger.logError(any()) } just Runs
        every { MissionLogger.logWarning(any()) } just Runs
        every { MissionLogger.logVerbose(any()) } just Runs
        
        // Setup queue store behavior
        every { queueStore.loadQueue() } returns mutableListOf()
        every { queueStore.saveQueue(any()) } just Runs
        every { queueStore.clearCurrentMission() } just Runs
        every { currentMissionStore.clear() } just Runs
        
        sequencer = MissionSequencer(context)
    }

    @After
    fun cleanup() {
        sequencer.destroy()
        unmockkStatic("com.vaishnava.alarm.sequencer.MissionLoggerKt")
    }

    @Test
    fun `test multi-mission sequencing without race condition`() = testScope.runTest {
        // Create a queue of 3 missions
        val missions = listOf(
            MissionSpec("mission1", "password", 30000, mapOf("password" to "1234")),
            MissionSpec("mission2", "tap", 30000, emptyMap()),
            MissionSpec("mission3", "password", 30000, mapOf("password" to "5678"))
        )
        
        // Mock queue store to return our test missions
        every { queueStore.loadQueue() } returnsMany listOf(
            missions.toMutableList(), // Initial queue
            missions.drop(1).toMutableList(), // After mission1 completion
            missions.drop(2).toMutableList(), // After mission2 completion
            mutableListOf() // After mission3 completion
        )
        
        // Enqueue missions
        sequencer.enqueueMissions(missions)
        
        // Process queue to start first mission
        sequencer.processQueue()
        
        // Verify first mission is current
        var current = sequencer.getCurrentMission()
        assert(current?.id == "mission1") { "Expected mission1 to be current, got ${current?.id}" }
        
        // Complete mission1 successfully
        sequencer.handleMissionCompletion("mission1", true)
        
        // Give a moment for the sequencer to process
        delay(100)
        
        // Verify mission2 is now current (no race condition)
        current = sequencer.getCurrentMission()
        assert(current?.id == "mission2") { "Expected mission2 to be current after mission1 completion, got ${current?.id}" }
        
        // Complete mission2 successfully
        sequencer.handleMissionCompletion("mission2", true)
        
        // Give a moment for the sequencer to process
        delay(100)
        
        // Verify mission3 is now current
        current = sequencer.getCurrentMission()
        assert(current?.id == "mission3") { "Expected mission3 to be current after mission2 completion, got ${current?.id}" }
        
        // Complete mission3 successfully
        sequencer.handleMissionCompletion("mission3", true)
        
        // Give a moment for the sequencer to process
        delay(100)
        
        // Verify no mission is current (queue empty)
        current = sequencer.getCurrentMission()
        assert(current == null) { "Expected no current mission after all completions, got ${current?.id}" }
    }

    @Test
    fun `test concurrent completion handling does not cause race condition`() = testScope.runTest {
        val missions = listOf(
            MissionSpec("mission1", "password", 30000, mapOf("password" to "1234")),
            MissionSpec("mission2", "tap", 30000, emptyMap())
        )
        
        every { queueStore.loadQueue() } returnsMany listOf(
            missions.toMutableList(),
            missions.drop(1).toMutableList(),
            mutableListOf()
        )
        
        sequencer.enqueueMissions(missions)
        sequencer.processQueue()
        
        // Verify first mission is current
        var current = sequencer.getCurrentMission()
        assert(current?.id == "mission1")
        
        // Launch multiple completion attempts concurrently (simulating race condition)
        val completionJobs = listOf(
            async { sequencer.handleMissionCompletion("mission1", true) },
            async { sequencer.handleMissionCompletion("mission1", true) }, // Duplicate
            async { sequencer.handleMissionCompletion("wrong_mission", true) }, // Wrong ID
            async { sequencer.handleMissionCompletion("mission1", false) } // Wrong success status
        )
        
        // Wait for all completion attempts to finish
        completionJobs.awaitAll()
        
        // Give sequencer time to process
        delay(200)
        
        // Verify only one completion was processed and mission2 is current
        current = sequencer.getCurrentMission()
        assert(current?.id == "mission2") { "Expected mission2 to be current after concurrent completions, got ${current?.id}" }
    }

    @Test
    fun `test mission failure does not affect queue advancement`() = testScope.runTest {
        val missions = listOf(
            MissionSpec("mission1", "password", 30000, mapOf("password" to "1234")),
            MissionSpec("mission2", "tap", 30000, emptyMap())
        )
        
        every { queueStore.loadQueue() } returnsMany listOf(
            missions.toMutableList(),
            missions.drop(1).toMutableList(),
            mutableListOf()
        )
        
        sequencer.enqueueMissions(missions)
        sequencer.processQueue()
        
        // Verify first mission is current
        var current = sequencer.getCurrentMission()
        assert(current?.id == "mission1")
        
        // Fail mission1
        sequencer.handleMissionCompletion("mission1", false)
        
        // Give sequencer time to process
        delay(200)
        
        // Verify mission2 is still started after failure
        current = sequencer.getCurrentMission()
        assert(current?.id == "mission2") { "Expected mission2 to be current after mission1 failure, got ${current?.id}" }
    }

    @Test
    fun `test completion with null current mission is ignored safely`() = testScope.runTest {
        // Don't start any mission, just send a completion
        sequencer.handleMissionCompletion("nonexistent_mission", true)
        
        // Give sequencer time to process
        delay(100)
        
        // Verify no mission is current (completion was ignored)
        val current = sequencer.getCurrentMission()
        assert(current == null) { "Expected no current mission when completion sent with null current mission" }
        
        // Verify queue is still empty
        val queueSize = sequencer.getQueueSize()
        assert(queueSize == 0) { "Expected queue size to be 0, got $queueSize" }
    }

    @Test
    fun `test completion with wrong mission ID is ignored safely`() = testScope.runTest {
        val missions = listOf(
            MissionSpec("mission1", "password", 30000, mapOf("password" to "1234"))
        )
        
        every { queueStore.loadQueue() } returnsMany listOf(
            missions.toMutableList(),
            mutableListOf()
        )
        
        sequencer.enqueueMissions(missions)
        sequencer.processQueue()
        
        // Verify first mission is current
        var current = sequencer.getCurrentMission()
        assert(current?.id == "mission1")
        
        // Send completion for wrong mission ID
        sequencer.handleMissionCompletion("wrong_mission", true)
        
        // Give sequencer time to process
        delay(100)
        
        // Verify original mission is still current (completion was ignored)
        current = sequencer.getCurrentMission()
        assert(current?.id == "mission1") { "Expected mission1 to still be current after wrong ID completion, got ${current?.id}" }
    }
}
