package com.harisai.assistant

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private val AuthNight = Color(0xFF080D18)
private val AuthCyan = Color(0xFF55E7F4)
private val AuthWhite = Color(0xFFEAF2FF)

object SupabaseAuth {
    suspend fun authenticate(email: String, password: String, signUp: Boolean): Result<String?> =
        withContext(Dispatchers.IO) {
            runCatching {
                val endpoint = if (signUp) "/auth/v1/signup" else "/auth/v1/token?grant_type=password"
                val connection = (URL(BuildConfig.SUPABASE_URL + endpoint).openConnection() as HttpURLConnection)
                try {
                    connection.requestMethod = "POST"
                    connection.connectTimeout = 15000
                    connection.readTimeout = 20000
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.setRequestProperty("apikey", BuildConfig.SUPABASE_PUBLISHABLE_KEY)
                    val payload = JSONObject().put("email", email.trim()).put("password", password).toString()
                    connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                    val code = connection.responseCode
                    val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                    val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    val json = JSONObject(raw.ifBlank { "{}" })
                    if (code !in 200..299) {
                        throw IllegalStateException(json.optString("msg", json.optString("message", json.optString("error_description", "Authentication failed ($code)"))))
                    }
                    val token = json.optString("access_token").takeIf { it.isNotBlank() }
                    if (!signUp && token == null) throw IllegalStateException("Sign-in succeeded but no session token was returned.")
                    token
                } finally {
                    connection.disconnect()
                }
            }
        }
}

@Composable
fun AuthScreen(onAuthenticated: (String) -> Unit, onSpeak: (String) -> Unit) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    MaterialTheme(colorScheme = darkColorScheme(primary = AuthCyan, background = AuthNight, surface = Color(0xFF111A2B))) {
        Column(
            Modifier.fillMaxSize().background(AuthNight).padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("HARIS AI", color = AuthWhite, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp)
            Text("SECURE ACCOUNT", color = AuthCyan, fontSize = 11.sp, letterSpacing = 2.sp)
            Spacer(Modifier.height(28.dp))
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF111A2B)), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Sign in to continue", color = AuthWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    OutlinedTextField(value = email, onValueChange = { email = it; error = "" }, label = { Text("Email") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = password, onValueChange = { password = it; error = "" }, label = { Text("Password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                    if (error.isNotBlank()) Text(error, color = Color(0xFFFF9A9A), fontSize = 12.sp)
                    Button(
                        onClick = {
                            if (email.isBlank() || password.length < 6) {
                                error = "Valid email aur kam az kam 6-character password enter karo."
                            } else {
                                loading = true
                                error = ""
                                scope.launch {
                                    val result = SupabaseAuth.authenticate(email, password, false)
                                    loading = false
                                    result.onSuccess { token -> if (token != null) onAuthenticated(token) }
                                        .onFailure { error = it.message ?: "Sign-in failed." }
                                }
                            }
                        },
                        enabled = !loading,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (loading) "Please wait…" else "Sign in") }
                    OutlinedButton(
                        onClick = {
                            if (email.isBlank() || password.length < 6) {
                                error = "Valid email aur kam az kam 6-character password enter karo."
                            } else {
                                loading = true
                                error = ""
                                scope.launch {
                                    val result = SupabaseAuth.authenticate(email, password, true)
                                    loading = false
                                    result.onSuccess { token ->
                                        if (token != null) onAuthenticated(token)
                                        else error = "Account created. Email confirmation required ho sakti hai; email verify karke Sign in karo."
                                    }.onFailure { error = it.message ?: "Sign-up failed." }
                                }
                            }
                        },
                        enabled = !loading,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Create account") }
                    if (loading) CircularProgressIndicator(color = AuthCyan, modifier = Modifier.align(Alignment.CenterHorizontally))
                    Text("Tumhara password sirf Supabase Auth ko bheja jata hai. API key app mein public publishable key hai; secret key nahi.", color = Color(0xFF9AAAC3), fontSize = 10.sp)
                }
            }
        }
    }
}
