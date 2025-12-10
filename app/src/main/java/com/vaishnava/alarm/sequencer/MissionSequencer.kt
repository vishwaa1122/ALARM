package com.vaishnava.alarm.sequencer

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.Context
import android.util.Log
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import com.vaishnava.alarm.AlarmReceiver
import android.widget.Toast
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.pow

class MissionSequencer(private val context: Context) {
    companion object {
        const val ACTION_MISSION_COMPLETED = "com.vaishnava.alarm.MISSION_COMPLETED"
        const val EXTRA_FROM_SEQUENCER = "from_sequencer"
        const val EXTRA_MISSION_ID = "mission_id"
        const val EXTRA_MISSION_SUCCESS = "success"
        const val DEFAULT_GLOBAL_PASSWORD = "IfYouWantYouCanSleep"
        @Volatile private var instance: MissionSequencer? = null
    }
    
    val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())
    private val queueStore = MissionQueueStore(context)
    private val mutex = Mutex()
    
    @Volatile private var currentMission: MissionSpec? = null
    @Volatile private var isProcessing = false
    @Volatile private var currentJob: Job? = null
    @Volatile private var timeoutJob: Job? = null
    @Volatile var isSequencerComplete = false
    @Volatile private var lastProcessStartTime: Long = 0
    @Volatile private var originalRingtoneUri: android.net.Uri? = null
    
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
        
        // CRITICAL FIX: Add immediate notification to prove MissionSequencer is being initialized
        try {
            val alarmStorage = com.vaishnava.alarm.AlarmStorage(context)
            val allAlarms = alarmStorage.getAlarms()
            val sequencerCount = allAlarms.count { it.missionType == "sequencer" }
            MissionLogger.log("INIT_NOTIFICATION: MissionSequencer initialized! Found $sequencerCount sequencer alarms out of ${allAlarms.size} total alarms")
            
            // Show first few sequencer alarms for verification
            allAlarms.filter { it.missionType == "sequencer" }.take(3).forEach { alarm ->
                MissionLogger.log("INIT_SAMPLE_ALARM: ID=${alarm.id}, Type=${alarm.missionType}, Password=${alarm.missionPassword}")
            }
        } catch (e: Exception) {
            MissionLogger.logError("INIT_NOTIFICATION_ERROR: Failed to read alarms in init", e)
        }
        
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
        if (missionSpec.id.contains("none_mission") || missionSpec.id.contains("mission_type=none") || missionSpec.id == "none") {
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
            try {
                MissionLogger.log("ENQUEUE_ALL_START: received=${missionSpecs.size} missions")
                missionSpecs.forEachIndexed { index, spec ->
                    MissionLogger.log("ENQUEUE_ALL_MISSION_$index: id=${spec.id} type=${spec.params["mission_type"]}")
                }
                
                mutex.withLock {
                    val beforeQueue = queueStore.loadQueue()
                    val filteredMissions = missionSpecs.filter { mission ->
                        val isNoneMission = mission.id.contains("none_mission") || mission.id.contains("mission_type=none") || mission.id == "none"
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
            } catch (e: Exception) {
                MissionLogger.logError("ENQUEUE_ALL_ERROR: ${e.message}")
            }
        }
    }
    
    fun startWhenAlarmFires(ringtoneUri: android.net.Uri? = null) {
        // CRITICAL FIX: Store the original ringtone URI for use throughout the sequencer
        originalRingtoneUri = ringtoneUri
        
        // CRITICAL FIX: Reset sequencer complete flag when starting new sequencer
        isSequencerComplete = false
        MissionLogger.log("BASIC_START_WHEN_ALARM_FIRES: Manual start triggered by alarm fire, sequencerComplete reset")
        
        // BASIC DEBUG: This should always appear
                MissionLogger.log("BASIC_START_WHEN_ALARM_FIRES: Manual start triggered by alarm fire")
        
        // CRITICAL: Check if this alarm actually has missions queued
        var queue = queueStore.loadQueue()
        MissionLogger.log("BASIC_QUEUE_CHECK: queueSize=${queue.size}")
        
        // CRITICAL DEBUG: Log all missions in queue to identify "none" missions
        queue.forEachIndexed { index, mission ->
            MissionLogger.log("QUEUE_MISSION_$index: id=${mission.id} type=${mission.params["mission_type"]} params=${mission.params}")
        }
        MissionLogger.log("QUEUE_ORDER: First mission=${queue.firstOrNull()?.id} Last mission=${queue.lastOrNull()?.id}")
        
        if (queue.isEmpty()) {
            MissionLogger.logWarning("BASIC_EMPTY_QUEUE: No missions in queue - launching AlarmActivity with default mission")
            
            // CRITICAL FIX: Launch AlarmActivity with default mission when queue is empty
            try {
                val currentAlarmId = getCurrentAlarmId()
                if (currentAlarmId != -1) {
                    val alarmStorage = com.vaishnava.alarm.AlarmStorage(context)
                    val alarm = alarmStorage.getAlarm(currentAlarmId)
                    if (alarm != null) {
                        // Launch AlarmActivity with default "tap" mission
                        val intent = Intent(context, com.vaishnava.alarm.AlarmActivity::class.java).apply {
                            addFlags(
                                Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT or
                                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                            )
                            putExtra(com.vaishnava.alarm.AlarmReceiver.ALARM_ID, alarm.id)
                            putExtra(com.vaishnava.alarm.AlarmReceiver.EXTRA_RINGTONE_URI, alarm.ringtoneUri)
                            putExtra(com.vaishnava.alarm.AlarmReceiver.EXTRA_REPEAT_DAYS, alarm.days?.toIntArray())
                            putExtra("hour", alarm.hour)
                            putExtra("minute", alarm.minute)
                            putExtra("repeatDaily", alarm.repeatDaily)
                            // Use default mission when sequencer has no missions
                            putExtra("mission_type", "tap")
                            putExtra("mission_password", "")
                            putExtra("is_protected", alarm.isProtected == true)
                        }
                        context.startActivity(intent)
                        MissionLogger.log("EMPTY_QUEUE_LAUNCH: Launched AlarmActivity with default 'tap' mission for alarm $currentAlarmId")
                        return
                    }
                }
            } catch (e: Exception) {
                MissionLogger.logError("EMPTY_QUEUE_LAUNCH_ERROR: Failed to launch AlarmActivity - ${e.message}")
            }
            
            // CRITICAL FIX: Disable the sequencer alarm if queue is empty to prevent re-triggering
            try {
                val alarmStorage = com.vaishnava.alarm.AlarmStorage(context)
                val currentAlarmId = getCurrentAlarmId()
                if (currentAlarmId != -1) {
                    alarmStorage.disableAlarm(currentAlarmId)
                    MissionLogger.log("START_WHEN_ALARM_FIRES_DISABLE_ALARM: Disabled sequencer alarm $currentAlarmId due to empty queue")
                    
                    // Also clear the pending intent
                    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                    val intent = Intent(context, com.vaishnava.alarm.AlarmReceiver::class.java)
                    val pendingIntent = android.app.PendingIntent.getBroadcast(
                        context, 
                        currentAlarmId, 
                        intent, 
                        android.app.PendingIntent.FLAG_NO_CREATE or android.app.PendingIntent.FLAG_IMMUTABLE
                    )
                    if (pendingIntent != null) {
                        alarmManager.cancel(pendingIntent)
                        MissionLogger.log("START_WHEN_ALARM_FIRES_CLEAR_PENDING: Cleared pending intent for alarm $currentAlarmId")
                    }
                }
            } catch (e: Exception) {
                MissionLogger.logError("START_WHEN_ALARM_FIRES_DISABLE_ERROR: Failed to disable alarm - ${e.message}")
            }
            
            // Don't process if queue is empty to prevent false "none" mission issues
            return
        }
        
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
            
            // Check if there are any active sequencer alarms
            val alarmStorage = com.vaishnava.alarm.AlarmStorage(context)
            val activeSequencerAlarms = alarmStorage.getAlarms().any { it.missionType == "sequencer" }
            
            // CRITICAL FIX: Show notification with what data is being read on force restart
            try {
                val allAlarms = alarmStorage.getAlarms()
                val sequencerAlarms = allAlarms.filter { it.missionType == "sequencer" }
                
                if (sequencerAlarms.isNotEmpty()) {
                    val alarmInfo = sequencerAlarms.joinToString("\n") { alarm ->
                        "ID: ${alarm.id}, Type: ${alarm.missionType}, Password: ${alarm.missionPassword}"
                    }
                    MissionLogger.log("FORCE_RESTART_DATA_READ: Found ${sequencerAlarms.size} sequencer alarms:\n$alarmInfo")
                    
                    // Also show DPS data for each alarm
                    sequencerAlarms.forEach { alarm ->
                        val dpsPrefs = context.getSharedPreferences("direct_boot_alarm_prefs", Context.MODE_PRIVATE)
                        val dpsType = dpsPrefs.getString("direct_boot_mission_type_${alarm.id}", "not_found")
                        val dpsPassword = dpsPrefs.getString("direct_boot_mission_password_${alarm.id}", "not_found")
                        MissionLogger.log("DPS_DATA_ALARM_${alarm.id}: Type=$dpsType, Password=$dpsPassword")
                    }
                }
            } catch (e: Exception) {
                MissionLogger.logError("Failed to read alarm data for notification", e)
            }
            
            if (!activeSequencerAlarms) {
                MissionLogger.log("RESTORE_NO_SEQUENCER_ALARMS: No active sequencer alarms found, clearing mission queue")
                queueStore.clearQueue()
                queueStore.clearCurrentMission()
                return@withLock
            }
            
            val currentMissionData = queueStore.loadCurrentMission()
            if (currentMissionData != null) {
                val (mission, startTime) = currentMissionData
                mission?.let { safeMission ->
                    // CRITICAL: Never restore "none" missions from storage
                    if (safeMission.id == "none" || safeMission.id.contains("none_mission") || safeMission.id.contains("mission_type=none")) {
                        MissionLogger.logWarning("RESTORE_BLOCKED: Refusing to restore 'none' mission from storage - missionId=${safeMission.id}")
                        queueStore.clearCurrentMission()
                        return@withLock
                    }
                    
                    val now = System.currentTimeMillis()
                    val elapsed = now - startTime
                    
                    if (elapsed < safeMission.timeoutMs) {
                        MissionLogger.log("RESTORING_CURRENT_MISSION: ${safeMission.id} elapsedMs=$elapsed remainingTimeoutMs=${safeMission.timeoutMs - elapsed}")
                        currentMission = safeMission
                        isProcessing = true
                        startTimeout(safeMission, safeMission.timeoutMs - elapsed)
                    } else {
                        MissionLogger.log("CURRENT_MISSION_TIMEOUT: ${safeMission.id} elapsedMs=$elapsed >= timeoutMs=${safeMission.timeoutMs}")
                        MissionLogger.log("TIMEOUT_RECOVERY: Mission expired, checking queue and potentially disabling sequencer alarm")
                        
                        queueStore.clearCurrentMission()
                        val queue = queueStore.loadQueue().toMutableList()
                        
                        if (queue.isNotEmpty()) {
                            // CRITICAL FIX: Don't filter out "none" missions - let them be processed
                            val filteredQueue = queue
                            
                            if (filteredQueue.isEmpty()) {
                                MissionLogger.log("TIMEOUT_RECOVERY_QUEUE_EMPTY: All missions filtered out, disabling sequencer alarm")
                                handleSequencerComplete()
                                return@withLock
                            }
                            
                            queueStore.saveQueue(filteredQueue)
                            MissionLogger.log("TIMEOUT_RECOVERY_QUEUE_UPDATED: removed expired mission, remaining=${filteredQueue.size}")
                            MissionLogger.log("TIMEOUT_RECOVERY_WAITING: Queue has missions, waiting for alarm trigger to continue")
                        } else {
                            MissionLogger.log("TIMEOUT_RECOVERY_QUEUE_EMPTY: No more missions in queue, disabling sequencer alarm")
                            handleSequencerComplete()
                            return@withLock
                        }
                    }
                }
            }
            
            // Only resume queue if there's a current mission already processing
            // Don't auto-start missions when app launches - only when alarm fires
            if (!isProcessing) {
                MissionLogger.log("RESTORE_NO_AUTO_START: Not auto-starting queue - requires alarm trigger")
            } else {
                MissionLogger.log("RESTORE_ALREADY_PROCESSING: Current mission is processing, not auto-starting")
            }
        }
        
        // CRITICAL FIX: Don't auto-start queue during restore - wait for alarm trigger
        // This prevents the password page from appearing and quitting immediately
        val queue = queueStore.loadQueue()
        if (queue.isNotEmpty()) {
            MissionLogger.log("RESTORE_QUEUE_EXISTS: ${queue.size} missions in queue, waiting for alarm trigger")
            // Don't call startProcessing() - let the alarm trigger handle it
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
        
        // CRITICAL FIX: Don't process if sequencer is complete
        if (isSequencerComplete) {
            MissionLogger.log("PROCESS_QUEUE_INTERNAL_BLOCKED: Sequencer is complete, aborting internal processing")
            return
        }
        
        // Double-check conditions with mutex to prevent race conditions
        mutex.withLock {
            // CRITICAL FIX: Double-check sequencer complete flag inside mutex
            if (isSequencerComplete) {
                MissionLogger.log("PROCESS_QUEUE_INTERNAL_MUTEX_BLOCKED: Sequencer complete detected inside mutex, aborting")
                return@withLock
            }
            
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
                var queue = queueStore.loadQueue()
                MissionLogger.log("PROCESS_QUEUE_STATE: queueSize=${queue.size}, firstItemId=${queue.firstOrNull()?.id ?: "<none>"}")
                
                // CRITICAL FIX: Don't filter out "none" missions - let them be processed
                val originalSize = queue.size
                
                if (queue.size != originalSize) {
                    MissionLogger.logWarning("PROCESS_QUEUE_FILTER: Removed ${originalSize - queue.size} missions, saving cleaned queue")
                    queueStore.saveQueue(queue)
                }
                
                if (queue.isEmpty()) {
                    isProcessing = false
                    MissionLogger.log("PROCESS_QUEUE_EMPTY: No missions to process after filtering")
                    return@withLock
                }

                // Get the next mission
                val nextMission = queue.firstOrNull()
                if (nextMission == null) {
                    MissionLogger.logWarning("PROCESS_QUEUE_FIRST_ITEM_NULL: Queue size=${queue.size}")
                    isProcessing = false
                    return@withLock
                }

                // Remove the mission from the queue when we start it
                val updatedQueue = queue.toMutableList()
                val missionIndex = updatedQueue.indexOfFirst { it.id == nextMission.id }
                if (missionIndex != -1) {
                    updatedQueue.removeAt(missionIndex)
                }
                queueStore.saveQueue(updatedQueue)
                MissionLogger.log("PROCESS_QUEUE_REMOVED_STARTING: missionId=${nextMission.id} index=$missionIndex remaining=${updatedQueue.size}")

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
                    // Restore the mission to the queue on error
                    val restoreQueue = queue.toMutableList()
                    restoreQueue.add(0, nextMission)
                    queueStore.saveQueue(restoreQueue)
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

    suspend fun tryProcessNextMission(): Boolean {
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
        // CRITICAL FIX: Don't process queue if sequencer is complete
        if (isSequencerComplete) {
            MissionLogger.log("PROCESS_QUEUE_BLOCKED: Sequencer is complete, not processing queue")
            return
        }
        
        scope.launch {
            processQueueInternal()
        }
    }
    
    private fun startMission(mission: MissionSpec) {
        try {
            // Convert password missions to use "IfYouWantYouCanSleep" as default password
            val safeMission = if (mission.id == "password") {
                MissionLogger.logWarning("PASSWORD_CONVERT: Converting password mission to use default password")
                mission.copy(
                    id = "password",
                    params = mapOf("mission_type" to "password", "use_default_password" to "true"),
                    timeoutMs = mission.timeoutMs
                )
            } else {
                mission
            }
            
            // Cancel any existing timeout
            timeoutJob?.cancel()

            MissionLogger.log("STARTING_MISSION: ${safeMission.id}")

            // Start timeout for the mission
            startTimeout(safeMission, safeMission.timeoutMs)

            // Create and launch the mission job
            currentJob = scope.launch {
                try {
                    val intent = Intent(context, com.vaishnava.alarm.AlarmActivity::class.java).apply {
                        putExtra("mission_id", safeMission.id)
                        // CRITICAL FIX: Always use mission.id as mission_type for consistency (after conversion)
                        val missionType = safeMission.id
                        
                        // CRITICAL FIX: Block "none" missions from being launched
                        if (missionType == "none" || missionType.contains("none_mission") || missionType.contains("mission_type=none")) {
                            MissionLogger.logWarning("LAUNCH_BLOCKED: Blocking 'none' mission from being launched - missionId=$missionType")
                            return@launch
                        }
                        
                        putExtra("mission_type", missionType)
                        // CRITICAL FIX: Add mission_password for Intent-only approach
                        val missionPassword = if (missionType == "password") DEFAULT_GLOBAL_PASSWORD else ""
                        putExtra("mission_password", missionPassword)
                        putExtra(EXTRA_FROM_SEQUENCER, true)
                        // CRITICAL FIX: Pass alarm ID and ringtone URI for proper ringtone display
                        putExtra(AlarmReceiver.ALARM_ID, getCurrentAlarmId())
                        val currentAlarmId = getCurrentAlarmId()
                        if (currentAlarmId != -1) {
                            val alarmStorage = com.vaishnava.alarm.AlarmStorage(context)
                            val alarm = alarmStorage.getAlarms().find { it.id == currentAlarmId }
                            
                            // Use alarm ringtone URI or fallback to original stored URI
                            val ringtoneUri = alarm?.ringtoneUri ?: originalRingtoneUri
                            
                            // CRITICAL FIX: Always pass ringtone URI if available, never skip
                            ringtoneUri?.let { uri ->
                                putExtra(AlarmReceiver.EXTRA_RINGTONE_URI, uri as android.os.Parcelable)
                            }
                        }
                        // CRITICAL FIX: Use flags that update the existing activity instead of creating a new one
                        // This prevents the alarm from being dismissed between missions
                        addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                        // Don't use NEW_TASK or CLEAR_TOP as they dismiss the current activity
                        safeMission.params.forEach { (key, value) ->
                            putExtra(key, value)
                        }
                        
                        // Debug logging for mission type
                        MissionLogger.log("MISSION_INTENT_DEBUG: missionId=${safeMission.id} missionType=$missionType params=${safeMission.params} alarmId=${getCurrentAlarmId()}")
                    }
                    
                    context.startActivity(intent)
                    
                    // CRITICAL FIX: Add a small delay to ensure the activity has time to process the new intent
                    scope.launch {
                        delay(100)
                        MissionLogger.log("MISSION_STARTED_CONFIRMED: missionId=${safeMission.id} activity should be updated")
                    }
                    
                    MissionLogger.log("MISSION_STARTED: missionId=${safeMission.id}")
                    
                } catch (e: Exception) {
                    MissionLogger.logError("MISSION_START_ERROR: ${e.message}")
                    handleMissionCompletion(safeMission.id, false)
                }
            }

        } catch (e: Exception) {
            MissionLogger.logError("START_MISSION_ERROR: ${e.message}")
            handleMissionCompletion(mission.id, false)
        }
    }
    
    private fun startTimeout(mission: MissionSpec, timeoutMs: Long) {
        // Use mission as-is, no conversion
        val safeMission = mission
        
        // CRITICAL FIX: Don't start timeout for tap missions - they should wait indefinitely
        if (safeMission.id == "tap") {
            MissionLogger.log("TIMEOUT_SKIPPED: missionId=${safeMission.id} - tap missions have no timeout")
            return
        }
        
        timeoutJob = scope.launch {
            MissionLogger.log("TIMEOUT_SCHEDULED: missionId=${safeMission.id} timeoutMs=$timeoutMs reason=mission_start_or_resume")
            delay(timeoutMs)
            
            MissionLogger.log("TIMEOUT_FIRED: missionId=${safeMission.id} action=handleMissionCompletion(success=false)")
            handleMissionCompletion(safeMission.id, false)
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
                    
                    // CRITICAL FIX: Use resolveMissionId to handle ID mismatches
                    val resolvedMissionId = resolveMissionId(missionId)
                    MissionLogger.log("HANDLE_COMPLETION_RESOLVED: id=$completionId original=$missionId resolved=$resolvedMissionId currentId=$currentId")
                    
                    // Only process if the mission ID matches the current mission
                    if (resolvedMissionId != currentId) {
                        MissionLogger.log("HANDLE_COMPLETION_MISMATCH: id=$completionId missionId=$missionId resolved=$resolvedMissionId currentId=$currentId")
                        MissionLogger.log("HANDLE_COMPLETION_DEBUG: missionId length=${missionId?.length} currentId length=${currentId?.length}")
                        MissionLogger.log("HANDLE_COMPLETION_DEBUG: missionId hash=${missionId?.hashCode()} currentId hash=${currentId?.hashCode()}")
                        return@withLock
                    }
                    
                    // CRITICAL FIX: Only complete sequencer if we're not processing any mission and queue is empty
                    if (queueStore.loadQueue().isEmpty() && !isProcessing) {
                        MissionLogger.log("HANDLE_COMPLETION_ALREADY_COMPLETE: id=$completionId missionId=$missionId - Queue empty and not processing, sequencer completed")
                        
                        // CRITICAL: Only call handleSequencerComplete if genuinely no more missions
                        handleSequencerComplete()
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
                        
                        // Load the queue (mission already removed when started)
                        val queue = queueStore.loadQueue().toMutableList()
                        MissionLogger.log("ADVANCE_QUEUE_STATE: id=$advanceId size=${queue.size}")
                        
                        // CRITICAL FIX: Remove completed mission from queue if still present
                        if (queue.isNotEmpty() && queue.any { it.id == currentId }) {
                            queue.removeAll { it.id == currentId }
                            queueStore.saveQueue(queue)
                            MissionLogger.log("ADVANCE_QUEUE_REMOVED_COMPLETED: id=$advanceId removed completed mission=$currentId remaining=${queue.size}")
                        }
                        
                        queueStore.clearCurrentMission()
                        MissionLogger.log("ADVANCE_QUEUE_CLEANUP_DONE: id=$advanceId")
                        
                        if (queue.isEmpty()) {
                            MissionLogger.log("ADVANCE_QUEUE_EMPTY: id=$advanceId - Multi-mission sequence completed")
                            handleSequencerComplete()
                        } else {
                            MissionLogger.log("ADVANCE_QUEUE_NEXT: id=$advanceId remaining=${queue.size}")
                            
                            // CRITICAL FIX: Filter out "none" missions from remaining queue for multi-mission sequences
                            val filteredQueue = queue.filter { mission ->
                                val isNoneMission = mission.id == "none" || 
                                                mission.id.contains("none_mission") || 
                                                mission.id.contains("mission_type=none") ||
                                                mission.params["mission_type"] == "none"
                                
                                if (isNoneMission) {
                                    MissionLogger.logWarning("ADVANCE_QUEUE_FILTER: Filtering out 'none' mission from remaining queue - missionId=${mission.id}")
                                }
                                
                                !isNoneMission
                            }
                            
                            // Save the filtered queue if any "none" missions were removed
                            if (filteredQueue.size != queue.size) {
                                queueStore.saveQueue(filteredQueue)
                                MissionLogger.logWarning("ADVANCE_QUEUE_FILTERED: Removed ${queue.size - filteredQueue.size} 'none' missions from remaining queue, new size=${filteredQueue.size}")
                            }
                            
                            if (filteredQueue.isEmpty()) {
                                MissionLogger.log("ADVANCE_QUEUE_EMPTY_AFTER_FILTER: No more missions after filtering 'none', completing sequencer")
                                handleSequencerComplete()
                                return@withLock
                            }
                            
                            // Start next mission with a small delay to prevent black screen race condition
                            val nextMission = filteredQueue.first()
                            MissionLogger.log("ADVANCE_QUEUE_STARTING_NEXT: id=$advanceId nextMissionId=${nextMission.id} nextMissionType=${nextMission.params["mission_type"]}")
                            
                            // CRITICAL FIX: Remove the next mission from queue before starting it
                            val updatedQueue = filteredQueue.toMutableList()
                            updatedQueue.removeAt(0)
                            queueStore.saveQueue(updatedQueue)
                            MissionLogger.log("ADVANCE_QUEUE_REMOVED_NEXT: id=$advanceId removedMissionId=${nextMission.id} remaining=${updatedQueue.size}")
                            
                            currentMission = nextMission
                            isProcessing = true
                            
                            // Launch next mission with delay to prevent activity state conflicts
                            scope.launch {
                                delay(500) // Increased delay to ensure proper activity cleanup and update
                                startMission(nextMission)
                            }
                        }
                    } else {
                        MissionLogger.log("HANDLE_COMPLETION_FAILURE: id=$completionId missionId=$missionId")
                        // For failure, don't auto-advance - wait for manual completion
                        MissionLogger.log("HANDLE_COMPLETION_FAILURE_WAIT: Mission $missionId failed, waiting for manual completion")
                        
                        // Keep current mission state for manual completion
                        timeoutJob?.cancel()
                        timeoutJob = null
                        
                        // Don't clear current mission or advance queue - wait for manual completion
                        MissionLogger.log("HANDLE_COMPLETION_FAILURE_STATE_KEPT: missionId=$missionId currentMission kept for manual completion")
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
    
    private fun handleSequencerComplete() {
        MissionLogger.log("SEQUENCER_COMPLETE: Multi-mission sequence completed")
        
        // Set sequencer complete flag to prevent duplicate completions
        isSequencerComplete = true
        
        // Disable the original sequencer alarm to prevent re-triggering
        try {
            val alarmStorage = com.vaishnava.alarm.AlarmStorage(context)
            val currentAlarmId = getCurrentAlarmId()
            if (currentAlarmId != -1) {
                alarmStorage.disableAlarm(currentAlarmId)
                MissionLogger.log("SEQUENCER_COMPLETE_ALARM_DISABLED: disabled original alarm $currentAlarmId")
                
                // Additional: Ensure alarm is completely disabled by clearing the specific alarm intent
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val intent = Intent(context, com.vaishnava.alarm.AlarmReceiver::class.java)
                val pendingIntent = android.app.PendingIntent.getBroadcast(
                    context, 
                    currentAlarmId, 
                    intent, 
                    android.app.PendingIntent.FLAG_NO_CREATE or android.app.PendingIntent.FLAG_IMMUTABLE
                )
                if (pendingIntent != null) {
                    alarmManager.cancel(pendingIntent)
                    MissionLogger.log("SEQUENCER_COMPLETE_PENDING_INTENT_CLEARED: cleared pending intent for alarm $currentAlarmId")
                }
            } else {
                MissionLogger.logWarning("SEQUENCER_COMPLETE_NO_ALARM_ID: could not get current alarm id")
            }
        } catch (e: Exception) {
            MissionLogger.logError("SEQUENCER_COMPLETE_ALARM_DISABLE_ERROR: ${e.message}")
        }
        
        // Clear all sequencer state
        try {
            currentMission = null
            isProcessing = false
            currentJob = null
            timeoutJob = null
            queueStore.clearQueue()
            queueStore.clearCurrentMission()
            MissionLogger.log("SEQUENCER_COMPLETE_STATE_CLEARED: All sequencer state cleared")
        } catch (e: Exception) {
            MissionLogger.logError("SEQUENCER_COMPLETE_STATE_CLEAR_ERROR: ${e.message}")
        }
        
        // Dismiss the alarm activity when all missions are completed
        scope.launch {
            delay(0) // Minimal delay for immediate response
            withContext(Dispatchers.Main) {
                try {
                    MissionLogger.log("SEQUENCER_COMPLETE_DISMISS: Sending finish broadcast to dismiss current activity")
                    
                    // CRITICAL FIX: ONLY send finish broadcast - NO direct activity start to prevent flashing
                    val finishIntent = Intent("com.vaishnava.alarm.FINISH_ALARM")
                    finishIntent.putExtra("sequencer_complete", true)
                    finishIntent.putExtra("finish_directly", true)
                    context.sendBroadcast(finishIntent)
                    MissionLogger.log("SEQUENCER_COMPLETE_FINISH_BROADCAST: Finish broadcast sent")
                    
                    // IMMEDIATE: Stop alarm service
                    try {
                        val serviceIntent = Intent(context, com.vaishnava.alarm.AlarmForegroundService::class.java).apply {
                            action = "com.vaishnava.alarm.STOP_ALARM_SERVICE"
                        }
                        context.startService(serviceIntent)
                        MissionLogger.log("SEQUENCER_COMPLETE_SERVICE_STOP: Service stopped immediately")
                    } catch (e: Exception) {
                        MissionLogger.logError("SEQUENCER_COMPLETE_SERVICE_STOP_ERROR: ${e.message}")
                    }
                    
                    MissionLogger.log("SEQUENCER_COMPLETE_DONE: Sequencer completed with broadcast only")
                    
                } catch (e: Exception) {
                    MissionLogger.logError("SEQUENCER_COMPLETE_ERROR: Failed to complete sequencer - ${e.message}")
                }
            }
        }
    }
    
    private fun resolveMissionId(missionId: String): String {
        // ABSOLUTE: No fallbacks for multi-mission - require explicit mission ID
        if (missionId.isBlank()) {
            MissionLogger.logError("RESOLVE_MISSION_ID_BLANK: Mission ID is blank - multi-mission requires explicit ID")
            throw IllegalStateException("Multi-mission requires explicit mission ID, no fallbacks allowed")
        }
        
        if (missionId == "none") {
            MissionLogger.logError("RESOLVE_MISSION_ID_NONE: Mission ID is 'none' - not allowed in multi-mission")
            throw IllegalStateException("Multi-mission cannot use 'none' mission ID")
        }
        
        MissionLogger.log("RESOLVE_MISSION_ID_DIRECT: usingProvidedId=$missionId")
        return missionId
    }
    
    private suspend fun handleMissionFailure(missionId: String) {
        try {
            val mission = currentMission
            if (mission == null) {
                MissionLogger.logWarning("FAIL_HANDLER_NULL_MISSION: missionId=$missionId")
                return
            }
            
            MissionLogger.log("FAIL: missionId=$missionId sticky=${mission.sticky} retryCount=${mission.retryCount} retryDelayMs=${mission.retryDelayMs}")
            
            // CRITICAL FIX: Don't auto-retry or advance on failure - wait for manual completion
            // This allows the user to complete the mission manually after timeout
            MissionLogger.log("FAILURE_WAIT_MANUAL: Mission $missionId failed, waiting for manual completion")
            
            // Keep current mission state so manual completion can be processed
            // DON'T clear currentMission
            // DON'T set isProcessing = false
            // Just cancel the timeout job and wait for manual completion
            timeoutJob?.cancel()
            timeoutJob = null
            
            MissionLogger.log("FAILURE_MANUAL_WAIT: Waiting for user to complete mission $missionId manually (currentMission kept)")
            
        } catch (e: Exception) {
            MissionLogger.logError("Error handling mission failure for $missionId")
        }
    }
    
    /**
     * Convert ensureQueueContinues to a non-suspending helper that schedules
     * a delayed processQueueInternal call. This avoids confusing suspend/launch
     * semantics when called from within mutex.withLock.
     */
    private fun ensureQueueContinues() {
        // CRITICAL FIX: Don't continue queue if sequencer is complete
        if (isSequencerComplete) {
            MissionLogger.log("ENSURE_QUEUE_BLOCKED: Sequencer is complete, not continuing queue")
            return
        }
        
        scope.launch {
            try {
                MissionLogger.log("ENSURE_QUEUE_CONTINUES_START: isProcessing=$isProcessing currentMissionId=${currentMission?.id}")
                
                // Check if we should process the queue
                val shouldProcess = !isProcessing && currentMission == null
                MissionLogger.log("ENSURE_QUEUE_CHECK: shouldProcess=$shouldProcess (isProcessing=$isProcessing, currentMission=${currentMission?.id})")
                
                if (!shouldProcess) {
                    val currentId = currentMission?.id ?: "<none>"
                    MissionLogger.log("ENSURE_QUEUE_SKIP: Already processing or has current mission ($currentId)")
                    return@launch
                }
                
                // Double-check sequencer complete flag after delay
                if (isSequencerComplete) {
                    MissionLogger.log("ENSURE_QUEUE_ABORT: Sequencer complete detected, aborting queue continuation")
                    return@launch
                }
                
                MissionLogger.log("ENSURE_QUEUE_PROCESS: Starting queue processing")
                processQueueInternal()
                
            } catch (e: Exception) {
                MissionLogger.logError("ENSURE_QUEUE_ERROR: ${e.message}")
                
                // Schedule a retry with backoff
                val retryDelay = 500L
                MissionLogger.log("ENSURE_QUEUE_RETRY: Scheduling retry in $retryDelay ms")
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
    
    fun getQueueStore(): MissionQueueStore = queueStore
    
    private fun getCurrentAlarmId(): Int {
        // DPS INVARIANT: current_service_alarm_id always stores alarmId, not missionId
        // For sequencer alarms, this is the sequencer alarm's ID that persists across all missions
        
        // Try to get the current alarm ID from the current mission parameters
        currentMission?.params?.get("alarm_id")?.let { return it.toIntOrNull() ?: -1 }
        
        // Try to get it from the current mission file
        try {
            val current = queueStore.loadCurrentMission()
            current?.first?.params?.get("alarm_id")?.let { return it.toIntOrNull() ?: -1 }
        } catch (_: Exception) { }
        
        // DPS INVARIANT: Fallback to DPS which stores the sequencer alarm ID
        // This ensures we always return the alarm ID, never a mission ID
        return try {
            val prefs = context.getSharedPreferences("alarm_dps", Context.MODE_PRIVATE)
            prefs.getInt("current_service_alarm_id", -1)
        } catch (_: Exception) {
            -1
        }
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
