package com.vaishnava.alarm.sequencer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.pow

class MissionSequencer(private val context: Context) {
    companion object {
        const val ACTION_MISSION_COMPLETED = "com.vaishnava.alarm.MISSION_COMPLETED"
        const val EXTRA_MISSION_ID = "mission_id"
        const val EXTRA_MISSION_SUCCESS = "mission_success"
        const val EXTRA_FROM_SEQUENCER = "from_sequencer"
    }
    
    private val queueStore = MissionQueueStore(context)
    private val mutex = Mutex()
    private val handler = Handler(Looper.getMainLooper())
    private var isProcessing = false
    private var currentMission: MissionSpec? = null
    private var lastProcessStartTime = 0L
    private var currentJob: Job? = null
    private var timeoutJob: Job? = null
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    private val missionCompletionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            MissionLogger.log("BROADCAST_RECEIVED_ANY: action=${intent?.action}")
            
            val missionId = intent?.getStringExtra(EXTRA_MISSION_ID)
            val success = intent?.getBooleanExtra(EXTRA_MISSION_SUCCESS, true) ?: true
            val fromSequencer = intent?.getBooleanExtra(EXTRA_FROM_SEQUENCER, false) ?: false
            
            MissionLogger.log("BROADCAST_RECEIVED: missionId=$missionId, success=$success, fromSequencer=$fromSequencer")
            
            if (missionId != null) {
                scope.launch {
                    MissionLogger.log("CALLING_HANDLE_COMPLETION: $missionId")
                    handleMissionCompletion(missionId, success)
                }
            } else {
                MissionLogger.logWarning("Received mission completion broadcast with null missionId")
            }
        }
    }
    
    init {
        MissionLogger.log("INIT: MissionSequencer init starting")
        val filter = IntentFilter(ACTION_MISSION_COMPLETED)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(missionCompletionReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(missionCompletionReceiver, filter)
        }
        
        MissionLogger.log("INIT: MissionSequencer initialized; registered receiver for ACTION_MISSION_COMPLETED")
        
        scope.launch {
            MissionLogger.log("INIT: Calling restoreAndResume from init scope.launch")
            restoreAndResume()
        }
    }
    
    fun enqueue(missionSpec: MissionSpec) {
        // Prevent "none" missions from being added to multi-mission queue
        if (missionSpec.id.contains("none_mission") || missionSpec.id.contains("mission_type=none")) {
            MissionLogger.logWarning("ENQUEUE_BLOCKED: Blocking 'none' mission from multi-mission queue - missionId=${missionSpec.id}")
            return
        }
        
        scope.launch {
            mutex.withLock {
                val beforeQueue = queueStore.loadQueue()
                MissionLogger.log("ENQUEUE_START: missionId=${missionSpec.id} existingSize=${beforeQueue.size}")
                val queue = beforeQueue.toMutableList()
                queue.add(missionSpec)
                queueStore.saveQueue(queue)
                MissionLogger.log("ENQUEUE_DONE: missionId=${missionSpec.id} newSize=${queue.size}")
                
                // Don't auto-start - wait for alarm to fire
                MissionLogger.log("ENQUEUE_WAITING: Mission queued, waiting for alarm to fire")
            }
        }
    }
    
    fun enqueueAll(missionSpecs: List<MissionSpec>) {
        scope.launch {
            mutex.withLock {
                val beforeQueue = queueStore.loadQueue()
                MissionLogger.log("ENQUEUE_ALL_START: count=${missionSpecs.size} existingSize=${beforeQueue.size}")
                
                // Filter out "none" missions from multi-mission queue
                val filteredMissions = missionSpecs.filter { mission ->
                    val isNoneMission = mission.id.contains("none_mission") || mission.id.contains("mission_type=none")
                    if (isNoneMission) {
                        MissionLogger.logWarning("ENQUEUE_ALL_BLOCKED: Blocking 'none' mission from multi-mission queue - missionId=${mission.id}")
                    }
                    !isNoneMission
                }
                
                val queue = beforeQueue.toMutableList()
                queue.addAll(filteredMissions)
                queueStore.saveQueue(queue)
                MissionLogger.log("ENQUEUE_ALL_DONE: added=${filteredMissions.size} (filtered from ${missionSpecs.size}) newSize=${queue.size}")
                
                // Don't auto-start - wait for alarm to fire
                MissionLogger.log("ENQUEUE_ALL_WAITING: Missions queued, waiting for alarm to fire")
            }
        }
    }
    
    fun startWhenAlarmFires() {
        MissionLogger.log("START_WHEN_ALARM_FIRES: Manual start triggered by alarm fire")
        processQueue()
    }
    
    fun cancelQueue() {
        scope.launch {
            mutex.withLock {
                MissionLogger.log("QUEUE_CANCEL_START: currentMissionId=${currentMission?.id} isProcessing=$isProcessing")
                currentJob?.cancel()
                timeoutJob?.cancel()
                currentJob = null
                timeoutJob = null
                currentMission = null
                isProcessing = false
                queueStore.clearQueue()
                queueStore.clearCurrentMission()
                MissionLogger.log("QUEUE_CANCELLED: all jobs cancelled and state cleared")
            }
        }
    }
    
    private suspend fun restoreAndResume() {
        mutex.withLock {
            MissionLogger.log("RESTORE_START: attempting to restore current mission and queue")
            val currentMissionData = queueStore.loadCurrentMission()
            if (currentMissionData != null) {
                val (mission, startTime) = currentMissionData
                mission?.let { safeMission ->
                    val now = System.currentTimeMillis()
                    val elapsed = now - startTime
                    
                    if (elapsed < safeMission.timeoutMs) {
                        MissionLogger.log("RESTORING_CURRENT_MISSION: ${safeMission.id} elapsedMs=$elapsed remainingTimeoutMs=${safeMission.timeoutMs - elapsed}")
                        currentMission = safeMission
                        isProcessing = true
                        startTimeout(safeMission, safeMission.timeoutMs - elapsed)
                    } else {
                        MissionLogger.log("CURRENT_MISSION_TIMEOUT: ${safeMission.id} elapsedMs=$elapsed >= timeoutMs=${safeMission.timeoutMs}")
                        queueStore.clearCurrentMission()
                        val queue = queueStore.loadQueue().toMutableList()
                        if (queue.isNotEmpty()) {
                            queue.removeAt(0)
                            queueStore.saveQueue(queue)
                        }
                    }
                }
            }
            
            // Only resume queue if there's a current mission already processing
            // Don't auto-start missions when app launches - only when alarm fires
            if (!isProcessing) {
                MissionLogger.log("RESTORE_NO_AUTO_START: Not auto-starting queue - requires alarm trigger")
                return@withLock
            }
            
            val queue = queueStore.loadQueue()
            if (queue.isNotEmpty()) {
                MissionLogger.log("RESUMING_QUEUE: ${queue.size} missions and current mission processing")
                startProcessing()
            }
        }
    }
    
    private suspend fun startProcessing() {
        MissionLogger.log("START_PROCESSING_REQUEST: requestedBy=internal isProcessing=$isProcessing currentMissionId=${currentMission?.id ?: "<none>"}")
        if (isProcessing) {
            MissionLogger.log("START_PROCESSING_ABORT: reason=already_processing currentMissionId=${currentMission?.id}")
            return
        }

        // Mark that we're about to process and immediately kick the queue
        isProcessing = false // ensureQueueContinues / processQueueInternal will set this when it actually starts
        ensureQueueContinues()
    }
    
    private suspend fun processQueueInternal() {
        val logTag = "MissionSequencer"
        MissionLogger.log("PROCESS_QUEUE_INTERNAL_ENTRY: isProcessing=$isProcessing, currentMissionId=${currentMission?.id ?: "<none>"}")
        
        // Double-check conditions with mutex to prevent race conditions
        mutex.withLock {
            // If we think we're processing but there's no current mission, fix the state
            if (isProcessing && currentMission == null) {
                MissionLogger.logWarning("PROCESS_QUEUE_RECOVERY: Found isProcessing=true but no current mission. Resetting state.")
                isProcessing = false
            }
            
            if (isProcessing || currentMission != null) {
                val currentId = currentMission?.id ?: "<none>"
                MissionLogger.log("PROCESS_QUEUE_BUSY: Already processing mission $currentId")
                // If we've been stuck for too long, force reset the state
                if (System.currentTimeMillis() - lastProcessStartTime > 30000) { // 30 second timeout
                    MissionLogger.logWarning("PROCESS_QUEUE_STUCK: Resetting processing state after timeout")
                    isProcessing = false
                    currentMission = null
                    queueStore.clearCurrentMission()
                } else {
                    return@withLock
                }
            }
            
            try {
                val queue = queueStore.loadQueue()
                MissionLogger.log("PROCESS_QUEUE_STATE: queueSize=${queue.size}, firstItemId=${queue.firstOrNull()?.id ?: "<none>"}")
                
                if (queue.isEmpty()) {
                    isProcessing = false
                    MissionLogger.log("PROCESS_QUEUE_EMPTY: No missions to process")
                    return@withLock
                }

                // Get the next mission
                val nextMission = queue.firstOrNull()
                if (nextMission == null) {
                    MissionLogger.logWarning("PROCESS_QUEUE_FIRST_ITEM_NULL: Queue size=${queue.size}")
                    isProcessing = false
                    return@withLock
                }

                // Update state before starting the mission
                currentMission = nextMission
                isProcessing = true
                
                try {
                    queueStore.saveCurrentMission(nextMission)
                    MissionLogger.log("PROCESS_QUEUE_STARTING: missionId=${nextMission.id}")
                } catch (e: Exception) {
                    MissionLogger.logError("PROCESS_QUEUE_SAVE_ERROR: ${e.message}")
                    currentMission = null
                    isProcessing = false
                    return@withLock
                }
                
                // Update the last process start time
                lastProcessStartTime = System.currentTimeMillis()
                
                // Start the mission outside the lock
                scope.launch {
                    try {
                        startMission(nextMission)
                    } catch (e: Exception) {
                        MissionLogger.logError("PROCESS_QUEUE_START_ERROR: ${e.message}")
                        mutex.withLock {
                            currentMission = null
                            isProcessing = false
                            queueStore.clearCurrentMission()
                        }
                        ensureQueueContinues()
                    }
                }
                
            } catch (e: Exception) {
                MissionLogger.logError("PROCESS_QUEUE_ERROR: ${e.message}")
                isProcessing = false
                currentMission = null
                ensureQueueContinues()
            }
        }
    }

    private suspend fun tryProcessNextMission(): Boolean {
        val logTag = "MissionSequencer"
        MissionLogger.log("TRY_NEXT_MISSION_ENTRY: thread=${Thread.currentThread().name}")
        // ... rest of the code remains the same ...
        Log.d(logTag, "[tryProcessNextMission] ENTRY")

        // Double-check we're not already processing a mission
        if (currentMission != null && isProcessing) {
            val msg = "TRY_NEXT_MISSION_SKIP: Mission already running: ${currentMission?.id}"
            MissionLogger.log(msg)
            Log.d(logTag, "[tryProcessNextMission] $msg")
            return false
        }

        MissionLogger.log("TRY_NEXT_MISSION_LOADING_QUEUE")
        Log.d(logTag, "[tryProcessNextMission] Loading queue...")
        
        val queue = try {
            queueStore.loadQueue().toMutableList().also { 
                MissionLogger.log("TRY_NEXT_MISSION_QUEUE_LOADED: size=${it.size}, firstId=${it.firstOrNull()?.id ?: "<none>"}")
            }
        } catch (e: Exception) {
            MissionLogger.logError("TRY_NEXT_MISSION_QUEUE_LOAD_ERROR: ${e.message}")
            isProcessing = false
            return false
        }
        
        Log.d(logTag, "[tryProcessNextMission] Queue loaded, size: ${queue.size}")

        if (queue.isEmpty()) {
            val msg = "TRY_NEXT_MISSION_QUEUE_EMPTY: No missions to process"
            MissionLogger.log(msg)
            Log.d(logTag, "[tryProcessNextMission] $msg")
            isProcessing = false
            return false
        }

        // Get next mission from queue
        val nextMission = queue.firstOrNull()
        if (nextMission == null) {
            val msg = "TRY_NEXT_MISSION_FIRST_ITEM_NULL: Queue size=${queue.size}"
            MissionLogger.logWarning(msg)
            Log.w(logTag, "[tryProcessNextMission] $msg")
            isProcessing = false
            return false
        }

        val startMsg = "TRY_NEXT_MISSION_STARTING: missionId=${nextMission.id}"
        MissionLogger.log(startMsg)
        Log.d(logTag, "[tryProcessNextMission] $startMsg")
        
        try {
            // Set current mission BEFORE starting it (but don't remove from queue yet)
            currentMission = nextMission
            isProcessing = true
            
            MissionLogger.log("TRY_NEXT_MISSION_SAVING: missionId=${nextMission.id}")
            queueStore.saveCurrentMission(nextMission)
            
            val setMsg = "TRY_NEXT_MISSION_SET_CURRENT: missionId=${nextMission.id}"
            MissionLogger.log(setMsg)
            Log.d(logTag, "[tryProcessNextMission] $setMsg")

            // track the mission job so we can cancel/inspect it later
            currentJob = scope.launch {
                try {
                    val launchMsg = "MISSION_LAUNCHER_START: missionId=${nextMission.id}"
                    MissionLogger.log(launchMsg)
                    Log.d(logTag, "[MissionLauncher] $launchMsg")
                    
                    startMission(nextMission)
                    
                    val completeMsg = "MISSION_LAUNCHER_COMPLETE: missionId=${nextMission.id}"
                    MissionLogger.log(completeMsg)
                    Log.d(logTag, "[MissionLauncher] $completeMsg")
                } catch (e: Exception) {
                    val errorMsg = "MISSION_LAUNCHER_ERROR: missionId=${nextMission.id}, error=${e.message}"
                    MissionLogger.logError(errorMsg)
                    Log.e(logTag, "[MissionLauncher] $errorMsg", e)
                }
            }
            
            val launchMsg = "TRY_NEXT_MISSION_LAUNCHED: missionId=${nextMission.id}"
            MissionLogger.log(launchMsg)
            Log.d(logTag, "[tryProcessNextMission] $launchMsg")
            
            return true
        } catch (e: Exception) {
            val errorMsg = "TRY_NEXT_MISSION_ERROR: Failed to start mission ${nextMission.id}, error=${e.message}"
            MissionLogger.logError(errorMsg)
            Log.e(logTag, errorMsg, e)
            
            // Reset state on error
            currentMission = null
            isProcessing = false
            queueStore.clearCurrentMission()
            
            return false
        }
    }
    
    fun processQueue() {
        scope.launch {
            processQueueInternal()
        }
    }
    
    private fun startMission(mission: MissionSpec) {
        try {
            // Reject "none" missions completely in multi-mission mode
            if (mission.id.contains("none_mission") || mission.id.contains("mission_type=none")) {
                MissionLogger.logWarning("START_MISSION_BLOCKED: Refusing to start 'none' mission in sequencer - missionId=${mission.id}")
                return
            }
            
            // Cancel any existing timeout
            timeoutJob?.cancel()

            MissionLogger.log("STARTING_MISSION: ${mission.id}")

            // Start timeout for the mission
            startTimeout(mission, mission.timeoutMs)

            // Create and launch the mission job
            currentJob = scope.launch {
                try {
                    val intent = Intent(context, com.vaishnava.alarm.AlarmActivity::class.java).apply {
                        putExtra("mission_id", mission.id)
                        putExtra("mission_type", mission.params["mission_type"] ?: mission.id)
                        putExtra(EXTRA_FROM_SEQUENCER, true)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                        mission.params.forEach { (key, value) ->
                            putExtra(key, value)
                        }
                    }
                    
                    // Show toast on main thread
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Starting mission: ${mission.id}", Toast.LENGTH_SHORT).show()
                        context.startActivity(intent)
                    }
                    
                    MissionLogger.log("MISSION_STARTED: missionId=${mission.id}")
                    
                } catch (e: Exception) {
                    MissionLogger.logError("MISSION_START_ERROR: ${e.message}")
                    handleMissionCompletion(mission.id, false)
                }
            }

        } catch (e: Exception) {
            MissionLogger.logError("START_MISSION_ERROR: ${e.message}")
            handleMissionCompletion(mission.id, false)
        }
    }
    
    private fun startTimeout(mission: MissionSpec, timeoutMs: Long) {
        timeoutJob = scope.launch {
            MissionLogger.log("TIMEOUT_SCHEDULED: missionId=${mission.id} timeoutMs=$timeoutMs reason=mission_start_or_resume")
            delay(timeoutMs)
            
            // Show Toast to confirm timeout is happening (run on main thread)
            handler.post {
                Toast.makeText(context, "Timeout: ${mission.id}", Toast.LENGTH_SHORT).show()
            }
            
            MissionLogger.log("TIMEOUT_FIRED: missionId=${mission.id} action=handleMissionCompletion(success=false)")
            handleMissionCompletion(mission.id, false)
        }
    }
    
    fun handleMissionCompletion(missionId: String, success: Boolean) {
        val completionId = "${System.currentTimeMillis()}_${(0..9999).random()}"
        MissionLogger.log("HANDLE_COMPLETION_START: id=$completionId missionId=$missionId success=$success")
        
        scope.launch {
            try {
                mutex.withLock {
                    val currentId = currentMission?.id
                    MissionLogger.log("HANDLE_COMPLETION_LOCK_ACQUIRED: id=$completionId missionId=$missionId currentId=$currentId")
                    
                    // If we don't have a current mission, ignore the completion
                    if (currentId == null) {
                        MissionLogger.log("HANDLE_COMPLETION_NO_CURRENT: id=$completionId missionId=$missionId - No current mission, ignoring")
                        return@withLock
                    }
                    
                    // Only process if the mission ID matches the current mission
                    if (missionId != currentId) {
                        MissionLogger.log("HANDLE_COMPLETION_MISMATCH: id=$completionId missionId=$missionId currentId=$currentId")
                        return@withLock
                    }
                    
                    MissionLogger.log("HANDLE_COMPLETION_PROCESSING: id=$completionId missionId=$missionId")
                    
                    if (success) {
                        MissionLogger.log("HANDLE_COMPLETION_SUCCESS: id=$completionId missionId=$missionId")
                        // Advance queue within the same synchronized block
                        val advanceId = "${System.currentTimeMillis()}_${(0..9999).random()}"
                        MissionLogger.log("ADVANCE_QUEUE_START: id=$advanceId")
                        MissionLogger.log("ADVANCE_QUEUE_LOCK_ACQUIRED: id=$advanceId")
                        
                        // Clear current mission state
                        currentMission = null
                        isProcessing = false
                        currentJob = null
                        timeoutJob?.cancel()
                        timeoutJob = null
                        
                        // Load and update the queue
                        val queue = queueStore.loadQueue().toMutableList()
                        MissionLogger.log("ADVANCE_QUEUE_STATE: id=$advanceId size=${queue.size}")
                        
                        if (queue.isNotEmpty()) {
                            val removed = queue.removeAt(0)
                            queueStore.saveQueue(queue)
                            MissionLogger.log("ADVANCE_QUEUE_REMOVED: id=$advanceId missionId=${removed.id} remaining=${queue.size}")
                        }
                        
                        queueStore.clearCurrentMission()
                        MissionLogger.log("ADVANCE_QUEUE_CLEANUP_DONE: id=$advanceId")
                        
                        if (queue.isEmpty()) {
                            MissionLogger.log("ADVANCE_QUEUE_EMPTY: id=$advanceId - Multi-mission sequence completed")
                            // Dismiss the alarm activity when all missions are completed
                            scope.launch {
                                withContext(Dispatchers.Main) {
                                    try {
                                        val dismissIntent = Intent(context, com.vaishnava.alarm.AlarmActivity::class.java).apply {
                                            putExtra("sequencer_complete", true)
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                        }
                                        context.startActivity(dismissIntent)
                                    } catch (e: Exception) {
                                        MissionLogger.logError("SEQUENCER_COMPLETE_ERROR: Failed to dismiss activity - ${e.message}")
                                    }
                                }
                            }
                        } else {
                            MissionLogger.log("ADVANCE_QUEUE_NEXT: id=$advanceId remaining=${queue.size}")
                            // Start next mission immediately within the same lock
                            val nextMission = queue.first()
                            MissionLogger.log("ADVANCE_QUEUE_STARTING_NEXT: id=$advanceId nextMissionId=${nextMission.id} nextMissionType=${nextMission.params["mission_type"]}")
                            currentMission = nextMission
                            isProcessing = true
                            startMission(nextMission)
                        }
                    } else {
                        MissionLogger.log("HANDLE_COMPLETION_FAILURE: id=$completionId missionId=$missionId")
                        currentMission = null
                        queueStore.clearCurrentMission()
                        ensureQueueContinues()
                    }
                }
            } catch (e: Exception) {
                MissionLogger.logError("HANDLE_COMPLETION_ERROR: id=$completionId missionId=$missionId error=${e.message}")
                isProcessing = false
                currentMission = null
                ensureQueueContinues()
            }
        }
    }
    
    private fun resolveMissionId(missionId: String): String {
        // Enhanced resolution logic with multiple fallback sources
        if (missionId.isNotBlank() && missionId != "unknown_mission") {
            MissionLogger.log("RESOLVE_MISSION_ID_DIRECT: usingProvidedId=$missionId")
            return missionId
        }

        // Try to get from current mission first
        currentMission?.let {
            MissionLogger.log("RESOLVE_MISSION_ID_FROM_CURRENT: currentMissionId=${it.id}")
            return it.id
        }

        // Then fall back to the front of the queue (if any)
        val queue = queueStore.loadQueue()
        if (queue.isNotEmpty()) {
            val id = queue.first().id
            MissionLogger.log("RESOLVE_MISSION_ID_FROM_QUEUE_FRONT: frontMissionId=$id")
            return id
        }

        MissionLogger.log("RESOLVE_MISSION_ID_FALLBACK: returningOriginal=$missionId")
        return missionId // Return original as last resort
    }
    
    private suspend fun handleMissionFailure(missionId: String) {
        try {
            val mission = currentMission
            if (mission == null) {
                MissionLogger.logWarning("FAIL_HANDLER_NULL_MISSION: missionId=$missionId")
                ensureQueueContinues()
                return
            }
            
            MissionLogger.log("FAIL: missionId=$missionId sticky=${mission.sticky} retryCount=${mission.retryCount} retryDelayMs=${mission.retryDelayMs}")
            
            if (mission.sticky && mission.retryCount > 0) {
                val retrySpec = mission.copy(
                    retryCount = mission.retryCount - 1,
                    retryDelayMs = (mission.retryDelayMs * 2.0).toLong()
                )
                
                MissionLogger.log("RETRY_SCHEDULED: missionId=$missionId retriesLeft=${retrySpec.retryCount} nextDelayMs=${retrySpec.retryDelayMs}")
                
                currentJob = scope.launch {
                    MissionLogger.log("RETRY_DELAY_WAIT: missionId=$missionId delayMs=${retrySpec.retryDelayMs}")
                    delay(retrySpec.retryDelayMs)
                    mutex.withLock {
                        val queue = queueStore.loadQueue().toMutableList()
                        MissionLogger.log("RETRY_QUEUE_STATE_BEFORE: missionId=$missionId queueSize=${queue.size}")
                        if (queue.isNotEmpty()) {
                            queue[0] = retrySpec
                            queueStore.saveQueue(queue)
                            currentMission = retrySpec
                            MissionLogger.log("RETRY_QUEUE_UPDATED: missionId=$missionId newRetryCount=${retrySpec.retryCount}")
                            startMission(retrySpec)
                        } else {
                            MissionLogger.logWarning("RETRY_ABORT: queue empty when scheduling retry for missionId=$missionId")
                        }
                    }
                }
            } else {
                MissionLogger.log("DEQUEUE_NO_RETRY: missionId=$missionId (no more retries or not sticky)")
                // Inline advanceQueue logic to maintain synchronization
                val advanceId = "${System.currentTimeMillis()}_${(0..9999).random()}"
                MissionLogger.log("ADVANCE_QUEUE_START: id=$advanceId")
                
                // Clear current mission state
                currentMission = null
                isProcessing = false
                currentJob = null
                timeoutJob?.cancel()
                timeoutJob = null
                
                // Load and update the queue
                val queue = queueStore.loadQueue().toMutableList()
                MissionLogger.log("ADVANCE_QUEUE_STATE: id=$advanceId size=${queue.size}")
                
                if (queue.isNotEmpty()) {
                    val removed = queue.removeAt(0)
                    queueStore.saveQueue(queue)
                    MissionLogger.log("ADVANCE_QUEUE_REMOVED: id=$advanceId missionId=${removed.id}")
                }
                
                queueStore.clearCurrentMission()
                MissionLogger.log("ADVANCE_QUEUE_CLEANUP_DONE: id=$advanceId")
                
                if (queue.isEmpty()) {
                    MissionLogger.log("ADVANCE_QUEUE_EMPTY: id=$advanceId")
                } else {
                    MissionLogger.log("ADVANCE_QUEUE_NEXT: id=$advanceId remaining=${queue.size}")
                    // Schedule next mission processing
                    scope.launch {
                        ensureQueueContinues()
                    }
                }
            }
        } catch (e: Exception) {
            MissionLogger.logError("Error handling mission failure for $missionId")
            ensureQueueContinues()
        }
    }
    
    /**
     * Convert ensureQueueContinues to a non-suspending helper that schedules
     * a delayed processQueueInternal call. This avoids confusing suspend/launch
     * semantics when called from within mutex.withLock.
     */
    private fun ensureQueueContinues() {
        scope.launch {
            try {
                MissionLogger.log("ENSURE_QUEUE_CONTINUES_START: isProcessing=${'$'}isProcessing currentMissionId=${'$'}{currentMission?.id}")
                
                // Check if we should process the queue
                val shouldProcess = !isProcessing && currentMission == null
                MissionLogger.log("ENSURE_QUEUE_CHECK: shouldProcess=${'$'}shouldProcess (isProcessing=${'$'}isProcessing, currentMission=${'$'}{currentMission?.id})")
                
                if (!shouldProcess) {
                    val currentId = currentMission?.id ?: "<none>"
                    MissionLogger.log("ENSURE_QUEUE_SKIPPED: Already processing mission ${'$'}currentId")
                    return@launch
                }
                
                // Small delay to ensure any pending state changes are complete
                delay(50)
                
                // Double-check conditions after delay
                if (isProcessing || currentMission != null) {
                    MissionLogger.log("ENSURE_QUEUE_ABORT: State changed during delay")
                    return@launch
                }
                
                MissionLogger.log("ENSURE_QUEUE_PROCESSING: Starting processQueueInternal")
                processQueueInternal()
                
            } catch (e: Exception) {
                MissionLogger.logError("ENSURE_QUEUE_ERROR: ${'$'}{e.message}")
                // Reset state and retry
                isProcessing = false
                currentMission = null
                
                // Schedule a retry with backoff
                val retryDelay = 500L
                MissionLogger.log("ENSURE_QUEUE_RETRY: Scheduling retry in ${'$'}retryDelay ms")
                delay(retryDelay)
                ensureQueueContinues()
            }
        }
    }
    
    fun isMissionRunning(): Boolean {
        val running = isProcessing && currentMission != null
        MissionLogger.logVerbose("IS_MISSION_RUNNING: result=$running isProcessing=$isProcessing currentMissionId=${currentMission?.id}")
        return running
    }
    
    fun getCurrentMission(): MissionSpec? {
        MissionLogger.log("GET_CURRENT_MISSION: currentMissionId=${currentMission?.id}")
        return currentMission
    }
    
    fun getQueueSize(): Int {
        val size = queueStore.loadQueue().size
        MissionLogger.logVerbose("GET_QUEUE_SIZE: size=$size")
        return size
    }
    
    fun destroy() {
        try {
            context.unregisterReceiver(missionCompletionReceiver)
        } catch (e: Exception) {
            MissionLogger.logError("Failed to unregister receiver", e)
        }
        
        scope.cancel()
        MissionLogger.log("DESTROY: MissionSequencer destroyed; scope cancelled and receiver unregistered")
    }
}
