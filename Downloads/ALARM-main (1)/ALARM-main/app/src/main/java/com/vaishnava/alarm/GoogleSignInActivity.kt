package com.vaishnava.alarm

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import com.google.api.services.drive.DriveScopes
import com.vaishnava.alarm.data.Alarm
import com.vaishnava.alarm.ui.theme.AlarmTheme
import com.vaishnava.alarm.CloudBackupControls

class GoogleSignInActivity : BaseActivity() {
    
    companion object {
        private const val RC_SIGN_IN = 9001
        private const val TAG = "GoogleSignInActivity"
    }
    
    private lateinit var googleSignInClient: GoogleSignInClient
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Configure Google Sign In (no Firebase): request email + Drive appData scope
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestScopes(Scope(DriveScopes.DRIVE_APPDATA))
            .requestEmail()
            .build()

        googleSignInClient = GoogleSignIn.getClient(this, gso)
        
        setContent {
            AlarmTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val account = remember { mutableStateOf(GoogleSignIn.getLastSignedInAccount(this)) }
                    LaunchedEffect(Unit) { account.value = GoogleSignIn.getLastSignedInAccount(this@GoogleSignInActivity) }

                    if (account.value == null) {
                        GoogleSignInScreen(
                            onSignInClick = { signIn() },
                            onBackClick = { finish() }
                        )
                    } else {
                        CloudBackupScreen(
                            email = account.value?.email ?: account.value?.displayName ?: "",
                            onBackup = { /* handled inside CloudBackupControls */ },
                            onRestore = { /* handled inside CloudBackupControls */ },
                            onSignOut = {
                                googleSignInClient.signOut().addOnCompleteListener {
                                    Toast.makeText(this, "Signed out", Toast.LENGTH_SHORT).show()
                                    finish()
                                }
                            }
                        )
                    }
                }
            }
        }
    }
    
    private fun signIn() {
        val signInIntent = googleSignInClient.signInIntent
        startActivityForResult(signInIntent, RC_SIGN_IN)
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        // Result returned from launching the Intent from GoogleSignInApi.getSignInIntent(...);
        if (requestCode == RC_SIGN_IN) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            try {
                // Google Sign In was successful
                val account = task.getResult(ApiException::class.java)!!
                Log.d(TAG, "Google sign-in success for: ${account.email}")
                Toast.makeText(this, "Signed in as ${account.email ?: account.displayName}", Toast.LENGTH_SHORT).show()
                finish()
            } catch (e: ApiException) {
                // Google Sign In failed, expose status code for diagnostics (e.g., 10 = DEVELOPER_ERROR)
                val code = e.statusCode
                val codeName = try { CommonStatusCodes.getStatusCodeString(code) } catch (_: Exception) { code.toString() }
                Log.w(TAG, "Google sign-in failed: code=$code name=$codeName", e)
                Toast.makeText(this, "Google sign-in failed: $code ($codeName)", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

@Composable
private fun CloudBackupScreen(
    email: String,
    onBackup: () -> Unit,
    onRestore: () -> Unit,
    onSignOut: () -> Unit
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Cloud backup",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Text(
            text = "Signed in as $email",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            modifier = Modifier.padding(bottom = 24.dp)
        )

        CloudBackupControls(
            alarmStorage = AlarmStorage(context),
            onRestored = { restoredAlarms ->
                Toast.makeText(context, "Restored ${restoredAlarms.size} alarms", android.widget.Toast.LENGTH_SHORT).show()
                
                // Send broadcast to MainActivity to refresh alarm list
                val refreshIntent = Intent("com.vaishnava.alarm.REFRESH_ALARMS").apply {
                    putExtra("alarms_restored", true)
                    putExtra("alarms_count", restoredAlarms.size)
                }
                context.sendBroadcast(refreshIntent)
                
                // Also set result for completeness
                val resultIntent = Intent().apply {
                    putExtra("alarms_restored", true)
                    putExtra("alarms_count", restoredAlarms.size)
                }
                (context as? Activity)?.setResult(Activity.RESULT_OK, resultIntent)
            }
        )

        Spacer(modifier = Modifier.height(24.dp))
        OutlinedButton(onClick = onSignOut) { Text("Sign out") }
        Spacer(modifier = Modifier.height(8.dp))
        TextButton(onClick = { (context as? android.app.Activity)?.finish() }) { Text("Back") }
    }
}

@Composable
fun GoogleSignInScreen(
    onSignInClick: () -> Unit,
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "Sign in with Google",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(bottom = 32.dp)
            )
            
            Text(
                text = "Save your alarms to the cloud and access them from any device",
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                modifier = Modifier.padding(bottom = 32.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            
            Button(
                onClick = onSignInClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .padding(horizontal = 32.dp, vertical = 8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF4285F4)
                )
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    // Google logo would go here
                    Text(
                        text = "Sign in with Google",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            OutlinedButton(
                onClick = onBackClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .padding(horizontal = 32.dp, vertical = 8.dp)
            ) {
                Text(
                    text = "Back",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}