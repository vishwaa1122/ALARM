package com.vaishnava.alarm

import android.content.Context
import android.util.Log
import com.vaishnava.alarm.data.Alarm
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.google.gson.JsonDeserializer
import com.google.gson.JsonSerializer
import com.google.gson.JsonElement
import android.net.Uri
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException
import com.google.api.client.http.ByteArrayContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File
import com.google.api.services.drive.model.FileList
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp

class CloudAlarmStorage(private val context: Context) {
    
    companion object {
        private const val TAG = "CloudAlarmStorage"
        private const val APPDATA_MIME = "application/json"
        private const val APPDATA_FILENAME = "alarms.json"
        
        // Custom Gson that can handle Uri serialization
        private val gson: Gson = GsonBuilder()
            .registerTypeAdapter(Uri::class.java, JsonSerializer<Uri> { src, _, _ ->
                if (src == null) null else com.google.gson.JsonPrimitive(src.toString())
            })
            .registerTypeAdapter(Uri::class.java, JsonDeserializer<Uri> { json, _, _ ->
                val uriString = json.asString
                if (uriString.isEmpty()) null else Uri.parse(uriString)
            })
            .create()
    }
    
    private fun buildDriveService(): Drive? {
        // Use the deprecated API for now since it's still functional and simpler
        val account = GoogleSignIn.getLastSignedInAccount(context) ?: run {
            Log.w(TAG, "No signed-in Google account")
            return null
        }
        val email = account.email
        if (email.isNullOrBlank()) {
            Log.w(TAG, "Signed-in account has no email")
            return null
        }
        val credential = GoogleAccountCredential.usingOAuth2(
            context,
            listOf(
                DriveScopes.DRIVE_APPDATA,
                DriveScopes.DRIVE_FILE,
                DriveScopes.DRIVE_METADATA_READONLY
            )
        )
        credential.selectedAccountName = email
        return Drive.Builder(
            NetHttpTransport(),
            GsonFactory.getDefaultInstance(),
            credential
        )
            .setApplicationName("Chrona Alarm")
            .build()
    }

    private fun <T> postResult(value: T, deliver: (T) -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            deliver(value)
        } else {
            Handler(Looper.getMainLooper()).post { deliver(value) }
        }
    }
    
    private fun postAlarmResult(result: Pair<List<Alarm>?, String?>, deliver: (List<Alarm>?, String?) -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            deliver(result.first, result.second)
        } else {
            Handler(Looper.getMainLooper()).post { deliver(result.first, result.second) }
        }
    }
    
    fun hasCloudBackup(onComplete: (Boolean) -> Unit) {
        Executors.newSingleThreadExecutor().execute {
            try {
                val drive = buildDriveService() ?: return@execute postResult(false, onComplete)
                val file = findAppDataFile(drive, APPDATA_FILENAME)
                postResult(file != null, onComplete)
            } catch (e: UserRecoverableAuthIOException) {
                Log.w(TAG, "User action required for Drive scope", e)
                val act = (context as? android.app.Activity)
                if (act != null) {
                    try { act.startActivity(e.intent) } catch (_: Exception) { }
                }
                postResult(false, onComplete)
            } catch (e: Exception) {
                Log.e(TAG, "Error checking backup", e)
                postResult(false, onComplete)
            }
        }
    }

    fun saveAlarmsToCloud(alarms: List<Alarm>, onComplete: (Boolean) -> Unit) {
        Executors.newSingleThreadExecutor().execute {
            try {
                val drive = buildDriveService() ?: return@execute postResult(false, onComplete)
                val json = gson.toJson(alarms)
                
                // Validate JSON before saving
                if (!json.trim().startsWith("[") || !json.trim().endsWith("]")) {
                    Log.e(TAG, "Generated invalid JSON format: $json")
                    postResult(false, onComplete)
                    return@execute
                }
                Log.d(TAG, "Generated JSON: ${json.take(100)}...")

                // Find existing file in appData
                val existing = findAppDataFile(drive, APPDATA_FILENAME)
                val mediaContent = ByteArrayContent.fromString(APPDATA_MIME, json)

                if (existing != null) {
                    drive.files().update(existing.id, null, mediaContent).execute()
                    Log.d(TAG, "Updated appData/${existing.name}")
                } else {
                    val metadata = File().apply {
                        name = APPDATA_FILENAME
                        parents = listOf("appDataFolder")
                        mimeType = APPDATA_MIME
                    }
                    drive.files().create(metadata, mediaContent).setFields("id,name").execute()
                    Log.d(TAG, "Created appData/$APPDATA_FILENAME")
                }
                postResult(true, onComplete)
            } catch (e: UserRecoverableAuthIOException) {
                Log.w(TAG, "User action required for Drive scope", e)
                // Try to launch consent screen
                val act = (context as? android.app.Activity)
                if (act != null) {
                    try {
                        act.startActivity(e.intent)
                    } catch (_: Exception) { }
                }
                postResult(false, onComplete)
            } catch (e: Exception) {
                val errorMessage = "Network error: ${e.message}"
                Log.e(TAG, errorMessage, e)
                postResult(false, onComplete)
            }
        }
    }
    
    fun loadAlarmsFromCloud(onComplete: (List<Alarm>?, String?) -> Unit) {
        Executors.newSingleThreadExecutor().execute {
            try {
                Log.d(TAG, "Starting cloud restore process...")
                val drive = buildDriveService()
                if (drive == null) {
                    val errorMessage = "No Google account signed in"
                    Log.e(TAG, errorMessage)
                    postAlarmResult(null to errorMessage, onComplete)
                    return@execute
                }
                
                Log.d(TAG, "Drive service built successfully, searching for file...")
                val file = findAppDataFile(drive, APPDATA_FILENAME)
                if (file == null) {
                    val errorMessage = "No backup found in Google Drive"
                    Log.w(TAG, errorMessage)
                    postAlarmResult(emptyList<Alarm>() to errorMessage, onComplete)
                    return@execute
                }
                
                Log.d(TAG, "Found backup file: ${file.id}, downloading content...")
                drive.files().get(file.id).executeMediaAsInputStream().use { input ->
                    val text = input.bufferedReader().use { reader -> reader.readText() }
                    Log.d(TAG, "Downloaded ${text.length} characters of backup data")
                    
                    if (text.isBlank()) {
                        val errorMessage = "Backup file is empty"
                        Log.w(TAG, errorMessage)
                        postAlarmResult(emptyList<Alarm>() to errorMessage, onComplete)
                        return@execute
                    }
                    
                    // Validate JSON format before parsing
                    if (!text.trim().startsWith("[") || !text.trim().endsWith("]")) {
                        val errorMessage = "Backup file corrupted - try creating a new backup"
                        Log.e(TAG, errorMessage)
                        Log.e(TAG, "Backup content: $text")
                        Log.w(TAG, "Corrupted backup file detected: ${file.id}. User should create a new backup.")
                        postAlarmResult(null to errorMessage, onComplete)
                        return@execute
                    }
                    
                    try {
                        val type = object : TypeToken<List<Alarm>>() {}.type
                        val alarms: List<Alarm> = gson.fromJson(text, type)
                        Log.d(TAG, "Successfully parsed ${alarms.size} alarms from backup")
                        postAlarmResult(alarms to null, onComplete)
                    } catch (jsonException: Exception) {
                        val errorMessage = "Backup data corrupted - try creating a new backup"
                        Log.e(TAG, errorMessage, jsonException)
                        Log.e(TAG, "Backup content preview: ${text.take(200)}...")
                        Log.w(TAG, "Corrupted backup file detected: ${file.id}. User should create a new backup.")
                        postAlarmResult(null to errorMessage, onComplete)
                    }
                }
            } catch (e: UserRecoverableAuthIOException) {
                val errorMessage = "Drive permission required: ${e.message}"
                Log.w(TAG, errorMessage, e)
                val act = (context as? android.app.Activity)
                if (act != null) {
                    try {
                        act.startActivity(e.intent)
                    } catch (_: Exception) { }
                }
                postAlarmResult(null to errorMessage, onComplete)
            } catch (e: Exception) {
                val errorMessage = when {
                    e.message?.contains("403", ignoreCase = true) == true -> 
                        "Drive access denied - please re-sign in to Google account"
                    e.message?.contains("401", ignoreCase = true) == true -> 
                        "Authentication expired - please sign in again"
                    e.message?.contains("network", ignoreCase = true) == true -> 
                        "Network connection error - check your internet"
                    else -> "Drive access error: ${e.message}"
                }
                Log.e(TAG, errorMessage, e)
                postAlarmResult(null to errorMessage, onComplete)
            }
        }
    }
    
    fun syncAlarmsWithCloud(localAlarms: List<Alarm>, onComplete: (List<Alarm>) -> Unit) {
        loadAlarmsFromCloud { cloudAlarms, _ ->
            if (cloudAlarms != null) {
                val merged = mergeAlarms(localAlarms, cloudAlarms)
                onComplete(merged)
            } else {
                onComplete(localAlarms)
            }
        }
    }
    
    private fun findAppDataFile(drive: Drive, name: String): File? {
        val q = "name = '${name.replace("'", "\\'")}' and 'appDataFolder' in parents and trashed = false"
        val result: FileList = drive.files().list()
            .setSpaces("appDataFolder")
            .setQ(q)
            .setFields("files(id,name)")
            .execute()
        return result.files?.firstOrNull()
    }

    private fun mergeAlarms(localAlarms: List<Alarm>, cloudAlarms: List<Alarm>): List<Alarm> {
        // Simple merge strategy: combine both lists and remove duplicates based on ID
        val mergedMap = mutableMapOf<Int, Alarm>()
        
        // Add cloud alarms first
        cloudAlarms.forEach { alarm ->
            mergedMap[alarm.id] = alarm
        }
        
        // Add local alarms (will overwrite cloud alarms with same ID)
        localAlarms.forEach { alarm ->
            mergedMap[alarm.id] = alarm
        }
        
        return mergedMap.values.toList()
    }
}

@Composable
fun CloudBackupControls(
    alarmStorage: AlarmStorage,
    onRestored: (List<Alarm>) -> Unit
) {
    val ctx = LocalContext.current
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = {
            try {
                val alarms = alarmStorage.getAlarms()
                CloudAlarmStorage(ctx).saveAlarmsToCloud(alarms) { success ->
                    Handler(Looper.getMainLooper()).post {
                        android.widget.Toast.makeText(ctx, if (success) "Backed up to Drive" else "Backup failed", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Handler(Looper.getMainLooper()).post {
                    android.widget.Toast.makeText(ctx, "Backup error: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }) { Text("Backup to Drive") }

        OutlinedButton(onClick = {
            try {
                CloudAlarmStorage(ctx).loadAlarmsFromCloud { restored, errorMessage ->
                    Handler(Looper.getMainLooper()).post {
                        if (restored != null && restored.isNotEmpty()) {
                            try {
                                alarmStorage.saveAlarms(restored)
                                onRestored(restored)
                                android.widget.Toast.makeText(ctx, "Restored ${restored.size} alarms", android.widget.Toast.LENGTH_SHORT).show()
                            } catch (saveException: Exception) {
                                Log.e("CloudBackup", "Failed to save restored alarms", saveException)
                                android.widget.Toast.makeText(ctx, "Save failed: ${saveException.message}", android.widget.Toast.LENGTH_LONG).show()
                            }
                        } else if (restored != null) {
                            // Empty list but no error means no backup found
                            val message = errorMessage ?: "No backup found in Google Drive"
                            android.widget.Toast.makeText(ctx, message, android.widget.Toast.LENGTH_LONG).show()
                        } else {
                            // Null restored list with error message
                            val message = errorMessage ?: "Unknown restore error"
                            android.widget.Toast.makeText(ctx, message, android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                }
            } catch (e: Exception) {
                Handler(Looper.getMainLooper()).post {
                    android.widget.Toast.makeText(ctx, "Restore error: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }) { Text("Restore from Drive") }
    }
}
