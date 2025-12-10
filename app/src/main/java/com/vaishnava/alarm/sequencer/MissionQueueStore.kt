package com.vaishnava.alarm.sequencer

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class MissionQueueStore(private val context: Context) {
    companion object {
        private const val QUEUE_FILE_NAME = "mission_queue.json"
        private const val CURRENT_MISSION_FILE_NAME = "current_mission.json"
        @Volatile
        private var hasLoggedMissingQueueOnce: Boolean = false
    }
    
    private val dpsContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        context.createDeviceProtectedStorageContext() ?: context
    } else context
    
    private val queueFile = File(dpsContext.filesDir, QUEUE_FILE_NAME)
    private val currentMissionFile = File(dpsContext.filesDir, CURRENT_MISSION_FILE_NAME)
    
    // CRITICAL FIX: Clear corrupted JSON files since we now use DPS SharedPreferences
    fun clearCorruptedFiles() {
        try {
            if (queueFile.exists()) {
                queueFile.delete()
                MissionLogger.log("QUEUE_STORE_CLEAR: Deleted corrupted queue file")
            }
            if (currentMissionFile.exists()) {
                currentMissionFile.delete()
                MissionLogger.log("QUEUE_STORE_CLEAR: Deleted corrupted current mission file")
            }
        } catch (e: Exception) {
            MissionLogger.logError("Failed to clear corrupted files", e)
        }
    }
    
    fun saveQueue(queue: List<MissionSpec>) {
        try {
            MissionLogger.log("QUEUE_STORE_SAVE_START: size=${queue.size} path=${queueFile.absolutePath}")
            val jsonArray = JSONArray()
            queue.forEach { spec ->
                val json = JSONObject().apply {
                    put("id", spec.id)
                    put("params", JSONObject(spec.params))
                    put("timeoutMs", spec.timeoutMs)
                    put("retryCount", spec.retryCount)
                    put("sticky", spec.sticky)
                    put("retryDelayMs", spec.retryDelayMs)
                }
                jsonArray.put(json)
            }
            queueFile.writeText(jsonArray.toString())
            MissionLogger.log("QUEUE_STORE_SAVE_DONE: size=${queue.size}")
        } catch (e: Exception) {
            MissionLogger.logError("Failed to save queue", e)
        }
    }
    
    fun loadQueue(): List<MissionSpec> {
        try {
            if (!queueFile.exists()) {
                // Avoid spamming logs when the app is fresh and the queue file has
                // never been created. We still return an empty list, but only log
                // the missing-file condition once per app process.
                if (!hasLoggedMissingQueueOnce) {
                    hasLoggedMissingQueueOnce = true
                    MissionLogger.log("QUEUE_STORE_LOAD_EMPTY: file_missing path=${queueFile.absolutePath}")
                } else {
                    MissionLogger.logVerbose("QUEUE_STORE_LOAD_EMPTY_VERBOSE: file_missing path=${queueFile.absolutePath}")
                }
                return emptyList()
            }
            
            val fileContent = queueFile.readText()
            
            // Check if file content is valid JSON
            if (!fileContent.trim().startsWith("[") || !fileContent.trim().endsWith("]")) {
                MissionLogger.logError("Queue file corrupted - invalid JSON format", Exception("File content: $fileContent"))
                // Clear corrupted file
                queueFile.delete()
                return emptyList()
            }
            
            val jsonArray = JSONArray(fileContent)
            val queue = mutableListOf<MissionSpec>()
            
            for (i in 0 until jsonArray.length()) {
                val json = jsonArray.getJSONObject(i)
                val paramsMap = mutableMapOf<String, String>()
                val paramsJson = json.getJSONObject("params")
                paramsJson.keys().forEach { key ->
                    paramsMap[key] = paramsJson.getString(key)
                }
                
                val missionId = json.getString("id")
                // CRITICAL FIX: Block "none" missions from being loaded from storage
                if (missionId == "none" || missionId.contains("none_mission") || missionId.contains("mission_type=none")) {
                    MissionLogger.logWarning("QUEUE_LOAD_BLOCKED: Skipping 'none' mission from storage - missionId=$missionId")
                    continue
                }
                
                queue.add(MissionSpec(
                    id = missionId,
                    params = paramsMap,
                    timeoutMs = json.getLong("timeoutMs"),
                    retryCount = json.getInt("retryCount"),
                    sticky = json.getBoolean("sticky"),
                    retryDelayMs = json.getLong("retryDelayMs")
                ))
            }
            
            MissionLogger.logVerbose("QUEUE_STORE_LOAD_DONE: size=${queue.size}")
            return queue
        } catch (e: Exception) {
            MissionLogger.logError("Failed to load queue", e)
            return emptyList()
        }
    }
    
    fun saveCurrentMission(mission: MissionSpec) {
        try {
            MissionLogger.log("CURRENT_STORE_SAVE_START: missionId=${mission.id} path=${currentMissionFile.absolutePath}")
            val json = JSONObject().apply {
                put("id", mission.id)
                put("params", JSONObject(mission.params))
                put("timeoutMs", mission.timeoutMs)
                put("retryCount", mission.retryCount)
                put("sticky", mission.sticky)
                put("retryDelayMs", mission.retryDelayMs)
                put("startTime", System.currentTimeMillis())
            }
            currentMissionFile.writeText(json.toString())
            MissionLogger.log("CURRENT_STORE_SAVE_DONE: missionId=${mission.id}")
        } catch (e: Exception) {
            MissionLogger.logError("Failed to save current mission", e)
        }
    }
    
    fun loadCurrentMission(): Pair<MissionSpec?, Long>? {
        try {
            if (!currentMissionFile.exists()) {
                MissionLogger.log("CURRENT_STORE_LOAD_EMPTY: file_missing path=${currentMissionFile.absolutePath}")
                return null
            }
            
            val json = JSONObject(currentMissionFile.readText())
            val paramsMap = mutableMapOf<String, String>()
            val paramsJson = json.getJSONObject("params")
            paramsJson.keys().forEach { key ->
                paramsMap[key] = paramsJson.getString(key)
            }
            
            val missionId = json.getString("id")
            // CRITICAL FIX: Block "none" missions from being loaded from current mission storage
            if (missionId == "none" || missionId.contains("none_mission") || missionId.contains("mission_type=none")) {
                MissionLogger.logWarning("CURRENT_LOAD_BLOCKED: Skipping 'none' mission from storage - missionId=$missionId")
                return null
            }
            
            val mission = MissionSpec(
                id = missionId,
                params = paramsMap,
                timeoutMs = json.getLong("timeoutMs"),
                retryCount = json.getInt("retryCount"),
                sticky = json.getBoolean("sticky"),
                retryDelayMs = json.getLong("retryDelayMs")
            )
            
            val startTime = json.getLong("startTime")
            MissionLogger.log("CURRENT_STORE_LOAD_DONE: missionId=${mission.id} startTime=$startTime")
            return Pair(mission, startTime)
        } catch (e: Exception) {
            MissionLogger.logError("Failed to load current mission", e)
            return null
        }
    }
    
    fun clearQueue() {
        try {
            MissionLogger.log("QUEUE_STORE_CLEAR_START: path=${queueFile.absolutePath}")
            if (queueFile.exists()) {
                queueFile.delete()
                MissionLogger.log("QUEUE_STORE_CLEAR_DONE: queue file deleted")
            } else {
                MissionLogger.log("QUEUE_STORE_CLEAR_SKIP: queue file does not exist")
            }
        } catch (e: Exception) {
            MissionLogger.logError("Failed to clear queue", e)
        }
    }
    
    fun clearCurrentMission() {
        try {
            MissionLogger.log("CURRENT_STORE_CLEAR_START: path=${currentMissionFile.absolutePath}")
            if (currentMissionFile.exists()) {
                currentMissionFile.delete()
                MissionLogger.log("CURRENT_STORE_CLEAR_DONE: current mission file deleted")
            } else {
                MissionLogger.log("CURRENT_STORE_CLEAR_SKIP: current mission file does not exist")
            }
        } catch (e: Exception) {
            MissionLogger.logError("Failed to clear current mission", e)
        }
    }
    
    fun getQueueFilePath(): String {
        return queueFile.absolutePath
    }
}
