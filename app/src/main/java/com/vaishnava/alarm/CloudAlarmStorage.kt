package com.vaishnava.alarm

import android.content.Context
import android.util.Log
import com.vaishnava.alarm.data.Alarm
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
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
    }
    
    private fun buildDriveService(): Drive? {
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
            listOf(DriveScopes.DRIVE_APPDATA)
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
                val gson = Gson()
                val json = gson.toJson(alarms)

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
                Log.e(TAG, "Error saving to Drive", e)
                postResult(false, onComplete)
            }
        }
    }
    
    fun loadAlarmsFromCloud(onComplete: (List<Alarm>?) -> Unit) {
        Executors.newSingleThreadExecutor().execute {
            try {
                val drive = buildDriveService() ?: return@execute postResult(emptyList(), onComplete)
                val file = findAppDataFile(drive, APPDATA_FILENAME)
                if (file == null) {
                    Log.d(TAG, "No appData/$APPDATA_FILENAME found")
                    postResult(emptyList(), onComplete)
                    return@execute
                }
                drive.files().get(file.id).executeMediaAsInputStream().use { input ->
                    val text = input.readBytes().toString(StandardCharsets.UTF_8)
                    val type = object : TypeToken<List<Alarm>>() {}.type
                    val alarms: List<Alarm> = Gson().fromJson(text, type)
                    postResult(alarms, onComplete)
                }
            } catch (e: UserRecoverableAuthIOException) {
                Log.w(TAG, "User action required for Drive scope", e)
                val act = (context as? android.app.Activity)
                if (act != null) {
                    try {
                        act.startActivity(e.intent)
                    } catch (_: Exception) { }
                }
                postResult(null, onComplete)
            } catch (e: Exception) {
                Log.e(TAG, "Error loading from Drive", e)
                postResult(null, onComplete)
            }
        }
    }
    
    fun syncAlarmsWithCloud(localAlarms: List<Alarm>, onComplete: (List<Alarm>) -> Unit) {
        loadAlarmsFromCloud { cloudAlarms ->
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
                    android.widget.Toast.makeText(ctx, if (success) "Backed up to Drive" else "Backup failed", android.widget.Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                android.widget.Toast.makeText(ctx, "Backup error: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
            }
        }) { Text("Backup to Drive") }

        OutlinedButton(onClick = {
            try {
                CloudAlarmStorage(ctx).loadAlarmsFromCloud { restored ->
                    if (restored != null && restored.isNotEmpty()) {
                        alarmStorage.saveAlarms(restored)
                        onRestored(restored)
                        android.widget.Toast.makeText(ctx, "Restored ${restored.size} alarms", android.widget.Toast.LENGTH_SHORT).show()
                    } else if (restored != null) {
                        android.widget.Toast.makeText(ctx, "No cloud data", android.widget.Toast.LENGTH_SHORT).show()
                    } else {
                        android.widget.Toast.makeText(ctx, "Restore failed", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                android.widget.Toast.makeText(ctx, "Restore error: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
            }
        }) { Text("Restore from Drive") }
    }
}