package com.harisai.assistant

import android.Manifest
import android.content.Intent
import android.provider.Settings
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.net.URLEncoder
import java.util.Locale

private val Night = Color(0xFF080D18)
private val Panel = Color(0xFF111A2B)
private val Cyan = Color(0xFF55E7F4)
private val Purple = Color(0xFF9877FF)
private val SoftWhite = Color(0xFFEAF2FF)
private val Muted = Color(0xFF9AAAC3)

data class ChatMessage(val text: String, val fromUser: Boolean)
data class ContactMatch(val name: String, val phone: String)
data class PendingAction(val type: String, val contact: ContactMatch, val message: String = "")

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this, this)
        setContent { HarisAIApp(
            onSpeak = { say(it) },
            onConfirmAction = { action -> performPendingAction(action) },
            onRequestContacts = { hasContactsPermission() },
            autoSendEnabled = { getSharedPreferences("haris_ai", MODE_PRIVATE).getBoolean("whatsapp_auto_send", false) },
            onSetAutoSend = { enabled -> getSharedPreferences("haris_ai", MODE_PRIVATE).edit().putBoolean("whatsapp_auto_send", enabled).apply() },
            onOpenAccessibilitySettings = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        ) }
    }

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) tts?.language = Locale("en", "PK")
    }

    private fun say(text: String) { if (ttsReady) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "haris-ai") }

    private fun hasContactsPermission() = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    private fun lookupContact(query: String): ContactMatch? {
        if (!hasContactsPermission()) return null
        val resolver = contentResolver
        resolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC")?.use { cursor ->
            val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val phoneIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIdx) ?: continue
                val phone = cursor.getString(phoneIdx) ?: continue
                if (name.contains(query.trim(), ignoreCase = true)) return ContactMatch(name, phone)
            }
        }
        return null
    }

    private fun performPendingAction(action: PendingAction) {
        when (action.type) {
            "call" -> startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(action.contact.phone)}")))
            "whatsapp" -> {
                val digits = action.contact.phone.filter { it.isDigit() }
                val prefs = getSharedPreferences("haris_ai", MODE_PRIVATE)
                val autoSend = prefs.getBoolean("whatsapp_auto_send", false)
                prefs.edit()
                    .putBoolean("auto_send_armed", autoSend)
                    .putString("expected_message", action.message.trim())
                    .putString("expected_phone_digits", digits.takeLast(10))
                    .apply()
                val url = "https://wa.me/$digits?text=${URLEncoder.encode(action.message, "UTF-8")}"
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
        }
    }

    override fun onDestroy() { tts?.stop(); tts?.shutdown(); super.onDestroy() }

    @Composable
    private fun HarisAIApp(
        onSpeak: (String) -> Unit,
        onConfirmAction: (PendingAction) -> Unit,
        onRequestContacts: () -> Boolean,
        autoSendEnabled: () -> Boolean,
        onSetAutoSend: (Boolean) -> Unit,
        onOpenAccessibilitySettings: () -> Unit
    ) {
        var input by remember { mutableStateOf("") }
        var listening by remember { mutableStateOf(false) }
        var pending by remember { mutableStateOf<PendingAction?>(null) }
        var status by remember { mutableStateOf("Ready when you are") }
        var autoSend by remember { mutableStateOf(autoSendEnabled()) }
        val messages = remember { mutableStateListOf(ChatMessage("Assalam-o-alaikum Haris! Main Haris AI hoon. Mic dabao ya message type karo.", false)) }
        val contactsPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            status = if (granted) "Contacts permission enabled" else "Contacts permission denied"
            onSpeak(status)
        }
        val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) status = "Microphone permission enabled — tap Mic again" else status = "Microphone permission denied"
        }
        val speechLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            listening = false
            val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty()
            if (spoken.isNotBlank()) {
                input = spoken
                messages.add(ChatMessage(spoken, true))
                val command = parseCommand(spoken)
                if (command != null) {
                    if (!onRequestContacts()) {
                        status = "Allow contacts permission to find people by name."
                        contactsPermission.launch(Manifest.permission.READ_CONTACTS)
                    } else {
                        val match = lookupContact(command.first)
                        if (match == null) status = "No contact found for '${command.first}'. Check the name in Contacts."
                        else {
                            val action = PendingAction(command.second, match, command.third)
                            if (command.second == "whatsapp" && autoSend) {
                                onConfirmAction(action)
                                status = "Opening WhatsApp and attempting auto-send for ${match.name}. Check the result."
                                onSpeak("Opening WhatsApp for ${match.name}.")
                            } else {
                                pending = action
                                status = "Please review and confirm this action."
                                onSpeak("I found ${match.name}. Please confirm before I continue.")
                            }
                        }
                    }
                } else {
                    val reply = localReply(spoken)
                    messages.add(ChatMessage(reply, false)); status = "Voice recognized"; onSpeak(reply)
                }
                input = ""
            } else status = "I didn't catch that. Try again."
        }

        fun sendTyped() {
            val clean = input.trim(); if (clean.isEmpty()) return
            messages.add(ChatMessage(clean, true)); input = ""
            val command = parseCommand(clean)
            if (command != null) {
                if (!onRequestContacts()) contactsPermission.launch(Manifest.permission.READ_CONTACTS)
                else {
                    val match = lookupContact(command.first)
                    if (match == null) { val reply = "Contact '${command.first}' nahi mila. Contacts mein naam check karo."; messages.add(ChatMessage(reply, false)); onSpeak(reply) }
                    else {
                        val action = PendingAction(command.second, match, command.third)
                        if (command.second == "whatsapp" && autoSend) {
                            onConfirmAction(action)
                            status = "Opening WhatsApp and attempting auto-send for ${match.name}. Check the result."
                            onSpeak("Opening WhatsApp for ${match.name}.")
                        } else {
                            pending = action; status = "Confirmation required"; onSpeak("${match.name} found. Please confirm.")
                        }
                    }
                }
            } else { val reply = localReply(clean); messages.add(ChatMessage(reply, false)); onSpeak(reply) }
        }

        MaterialTheme(colorScheme = darkColorScheme(primary = Cyan, secondary = Purple, background = Night, surface = Panel, onBackground = SoftWhite, onSurface = SoftWhite)) {
            Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF10172A), Night, Color(0xFF090D16)))).padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text("HARIS AI", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = SoftWhite, letterSpacing = 2.sp)
                        Text("PERSONAL VOICE ASSISTANT", fontSize = 10.sp, color = Cyan, letterSpacing = 1.5.sp)
                    }
                    IconButton(onClick = { status = "Voice recognition uses Android speech services. Wake-word always-on is not enabled." }) { Icon(Icons.Default.Settings, contentDescription = "Status", tint = Muted) }
                }
                Box(Modifier.fillMaxWidth().weight(0.52f), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(142.dp).border(1.dp, Brush.sweepGradient(listOf(Cyan, Purple, Color(0xFF344D83), Cyan)), CircleShape).padding(12.dp).border(1.dp, Color(0x6655E7F4), CircleShape).padding(10.dp).background(Brush.radialGradient(listOf(Color(0xFF335A80), Color(0xFF141B3A), Color(0xFF0A1020))), CircleShape), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(50.dp).background(Brush.radialGradient(listOf(Color.White, Cyan, Color(0xFF3D68BD))), CircleShape))
                    }
                    Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (listening) "LISTENING..." else "VOICE READY", color = Cyan, fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
                        Text(status, color = Muted, fontSize = 11.sp, textAlign = TextAlign.Center)
                    }
                }
                pending?.let { action ->
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1B2940)), modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text("CONFIRM ACTION", color = Cyan, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Text(if (action.type == "call") "Open dialer for ${action.contact.name} (${action.contact.phone})?" else "Open WhatsApp for ${action.contact.name} (${action.contact.phone}) with this message: ${action.message}", color = SoftWhite, fontSize = 13.sp)
                            Text(if (autoSend) "Auto-send is enabled. If Accessibility permission is enabled, Haris AI will attempt to tap Send in WhatsApp. Verify recipient and message first; automation can fail." else "Auto-send is OFF. WhatsApp opens with a draft; you must tap Send yourself.", color = Muted, fontSize = 11.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { onConfirmAction(action); pending = null; status = "Opened requested app" }, modifier = Modifier.weight(1f)) { Text(if (action.type == "call") "Open dialer" else if (autoSend) "Open & auto-send" else "Open WhatsApp") }
                                OutlinedButton(onClick = { pending = null; status = "Action cancelled" }, modifier = Modifier.weight(1f)) { Text("Cancel") }
                            }
                        }
                    }
                }
                Text("CONVERSATION", color = Muted, fontSize = 10.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
                LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(messages) { message ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = if (message.fromUser) Arrangement.End else Arrangement.Start) {
                            Column(Modifier.widthIn(max = 310.dp).background(if (message.fromUser) Color(0xFF183448) else Panel, RoundedCornerShape(16.dp)).border(1.dp, if (message.fromUser) Color(0x6655E7F4) else Color(0x223A4B68), RoundedCornerShape(16.dp)).padding(11.dp)) {
                                Text(if (message.fromUser) "YOU" else "HARIS AI", fontSize = 9.sp, color = if (message.fromUser) Cyan else Purple, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                                Spacer(Modifier.height(3.dp)); Text(message.text, color = SoftWhite, fontSize = 14.sp, lineHeight = 19.sp)
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(24.dp)).border(1.dp, Color(0x3355E7F4), RoundedCornerShape(24.dp)).padding(horizontal = 8.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    BasicTextField(value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f).padding(start = 7.dp, end = 6.dp), textStyle = TextStyle(color = SoftWhite, fontSize = 14.sp), cursorBrush = Brush.verticalGradient(listOf(Cyan, Purple)), singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { sendTyped() }), decorationBox = { inner -> Box { if (input.isEmpty()) Text("Try: call Ali / WhatsApp Ali message Salam", color = Muted, fontSize = 11.sp); inner() } })
                    IconButton(onClick = {
                        if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                            micPermission.launch(Manifest.permission.RECORD_AUDIO)
                        } else {
                            listening = true; status = "Listening — speak now"
                            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply { putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM); putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-PK"); putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to Haris AI") }
                            try { speechLauncher.launch(intent) } catch (_: Exception) { listening = false; status = "Speech service not available on this phone" }
                        }
                    }) { Icon(Icons.Default.Mic, contentDescription = "Speak", tint = Cyan) }
                    IconButton(onClick = { sendTyped() }) { Icon(Icons.Default.Send, contentDescription = "Send", tint = Cyan) }
                }
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF101E30)), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("WHATSAPP AUTO-SEND", color = Cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Text("Optional accessibility automation • can mis-tap after app updates", color = Muted, fontSize = 9.sp)
                        }
                        Switch(checked = autoSend, onCheckedChange = { enabled ->
                            autoSend = enabled
                            onSetAutoSend(enabled)
                            status = if (enabled) "Auto-send enabled in app. Now enable Haris AI service in Android Accessibility settings." else "WhatsApp auto-send disabled."
                        })
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { if (!onRequestContacts()) contactsPermission.launch(Manifest.permission.READ_CONTACTS) else status = "Contacts permission already enabled" }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.Call, null, modifier = Modifier.size(15.dp)); Spacer(Modifier.width(5.dp)); Text("Contacts", fontSize = 11.sp) }
                    OutlinedButton(onClick = { onOpenAccessibilitySettings(); status = "Accessibility settings opened. Enable Haris AI Auto-Send only if you understand the risks." }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.Chat, null, modifier = Modifier.size(15.dp)); Spacer(Modifier.width(5.dp)); Text("Auto-Send setup", fontSize = 11.sp) }
                }
                Text("NATIVE STARTER • WAKE WORD NOT ENABLED • AUTO-SEND OPT-IN", Modifier.fillMaxWidth().padding(top = 6.dp), textAlign = TextAlign.Center, color = Color(0xFF71819B), fontSize = 8.sp, letterSpacing = 0.7.sp)
            }
        }
    }
}

private fun parseCommand(text: String): Triple<String, String, String>? {
    val normalized = text.trim()
    val call = Regex("(?i)^(?:please\\s+)?(?:call|phone|dial)\\s+(.+)$").matchEntire(normalized)
    if (call != null) return Triple(call.groupValues[1].trim(), "call", "")
    val wa = Regex("(?i)^(?:send\\s+)?(?:a\\s+)?whats?app(?:\\s+message)?\\s+(.+?)\\s+(?:message|saying|that\\s+says)\\s+(.+)$").matchEntire(normalized)
    if (wa != null) return Triple(wa.groupValues[1].trim(), "whatsapp", wa.groupValues[2].trim())
    return null
}

private fun localReply(text: String): String = when {
    text.contains("hello", true) || text.contains("salam", true) -> "Walaikum assalam! Main sun rahi hoon. Abhi native starter mode mein hoon."
    text.contains("time", true) -> "Phone ka current time ${java.text.SimpleDateFormat("h:mm a", Locale.getDefault()).format(java.util.Date())} hai."
    text.contains("help", true) -> "Commands: 'call Ali' ya 'WhatsApp Ali message Salam'. Contact permission allow karni hogi. WhatsApp auto-send optional hai; Accessibility permission aur in-app toggle dono chahiye."
    else -> "Tumhari baat samajh aayi: '$text'. Real AI brain aur always-on wake word abhi connected nahi hain."
}
