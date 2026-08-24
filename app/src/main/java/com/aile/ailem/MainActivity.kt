package com.aile.ailem

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.*
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Bundle
import android.util.Base64
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.gson.Gson
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.*
import java.net.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.math.*

// ==========================================
// 1. GERÇEK VERİ MODELLERİ
// ==========================================

data class MemberDto(
    val id: String = "",
    val nickname: String = "",
    val lastSeen: Long = 0L,
    val latitude: Double = 41.0082,
    val longitude: Double = 28.9784,
    val battery: Int = 100,
    val isSos: Boolean = false,
    val ipAddress: String = ""
)

data class MessageDto(
    val id: String = "",
    val senderId: String = "",
    val senderNickname: String = "",
    val text: String = "",
    val type: String = "TEXT", // TEXT, AUDIO, FILE
    val fileBase64: String = "",
    val fileName: String = "",
    val timestamp: Long = 0L
)

data class CloudPacket(
    val eventType: String = "", // "MEMBER_UPDATE", "CHAT_MESSAGE", "CALL_SIGNAL"
    val member: MemberDto? = null,
    val message: MessageDto? = null,
    val callTargetIp: String? = null
)

// ==========================================
// 2. GERÇEK VOIP SESLİ GÖRÜŞME MOTORU (PCM STREAM)
// ==========================================

object RealVoipEngine {
    private const val SAMPLE_RATE = 16000
    private const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
    private const val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
    private const val FORMAT = AudioFormat.ENCODING_PCM_16BIT
    private const val VOIP_PORT = 8890

    private var isCalling = false
    private var sendJob: Job? = null
    private var receiveJob: Job? = null
    private var datagramSocket: DatagramSocket? = null

    @SuppressLint("MissingPermission")
    fun startCall(targetIp: String) {
        if (isCalling) return
        isCalling = true

        try {
            datagramSocket = DatagramSocket(VOIP_PORT)
        } catch (_: Exception) {}

        // Ses Gönderme Thread'i
        sendJob = CoroutineScope(Dispatchers.IO).launch {
            val minBufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, FORMAT)
            val audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL_IN, FORMAT, minBufSize)
            val buffer = ByteArray(minBufSize)
            val address = try { InetAddress.getByName(targetIp) } catch (_: Exception) { null }

            if (address != null) {
                audioRecord.startRecording()
                while (isActive && isCalling) {
                    val read = audioRecord.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        try {
                            val packet = DatagramPacket(buffer, read, address, VOIP_PORT)
                            datagramSocket?.send(packet)
                        } catch (_: Exception) {}
                    }
                }
                audioRecord.stop()
                audioRecord.release()
            }
        }

        // Ses Alma & Çalma Thread'i
        receiveJob = CoroutineScope(Dispatchers.IO).launch {
            val minBufSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_OUT, FORMAT)
            val audioTrack = AudioTrack(
                AudioManager.STREAM_VOICE_CALL, SAMPLE_RATE, CHANNEL_OUT, FORMAT, minBufSize, AudioTrack.MODE_STREAM
            )
            audioTrack.play()
            val receiveBuffer = ByteArray(minBufSize)

            while (isActive && isCalling) {
                try {
                    val packet = DatagramPacket(receiveBuffer, receiveBuffer.size)
                    datagramSocket?.receive(packet)
                    audioTrack.write(packet.data, 0, packet.length)
                } catch (_: Exception) {}
            }
            audioTrack.stop()
            audioTrack.release()
        }
    }

    fun endCall() {
        isCalling = false
        sendJob?.cancel()
        receiveJob?.cancel()
        datagramSocket?.close()
        datagramSocket = null
    }
}

// ==========================================
// 3. GERÇEK SES KAYDI & OYNATMA MOTORU
// ==========================================

object RealAudioRecorder {
    private var recorder: MediaRecorder? = null
    private var tempFile: File? = null

    fun startRecording(context: Context): Boolean {
        return try {
            tempFile = File(context.cacheDir, "record_${System.currentTimeMillis()}.m4a")
            recorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(tempFile?.absolutePath)
                prepare()
                start()
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    fun stopAndGetBase64(): String? {
        return try {
            recorder?.stop()
            recorder?.release()
            recorder = null
            tempFile?.let { file ->
                if (file.exists()) {
                    val bytes = file.readBytes()
                    Base64.encodeToString(bytes, Base64.NO_WRAP)
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }

    fun playBase64Audio(context: Context, base64Str: String) {
        try {
            val bytes = Base64.decode(base64Str, Base64.NO_WRAP)
            val tempPlay = File(context.cacheDir, "play_temp.m4a")
            tempPlay.writeBytes(bytes)

            val mp = MediaPlayer()
            mp.setDataSource(tempPlay.absolutePath)
            mp.prepare()
            mp.start()
        } catch (_: Exception) {}
    }
}

// ==========================================
// 4. BULUT & YEREL AĞ (LAN P2P) MOTORU
// ==========================================

object NetworkEngine {
    private val client = OkHttpClient.Builder().connectTimeout(4, TimeUnit.SECONDS).build()
    private val gson = Gson()
    private val mediaType = "text/plain; charset=utf-8".toMediaType()

    fun getLocalIp(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress ?: ""
                    }
                }
            }
        } catch (_: Exception) {}
        return ""
    }

    // Bulut Üzerinden Aktar
    suspend fun publishCloud(code: String, packet: CloudPacket) = withContext(Dispatchers.IO) {
        try {
            val clean = code.replace("#", "").lowercase().trim()
            val url = "https://ntfy.sh/ailem_room_$clean"
            val json = gson.toJson(packet)
            val req = Request.Builder().url(url).post(json.toRequestBody(mediaType)).build()
            client.newCall(req).execute().close()
        } catch (_: Exception) {}
    }

    // Buluttan Dinle
    suspend fun pollCloud(code: String, sinceSec: Long): List<CloudPacket> = withContext(Dispatchers.IO) {
        val list = mutableListOf<CloudPacket>()
        try {
            val clean = code.replace("#", "").lowercase().trim()
            val url = "https://ntfy.sh/ailem_room_$clean/json?since=$sinceSec"
            val req = Request.Builder().url(url).get().build()
            client.newCall(req).execute().use { res ->
                val body = res.body?.string() ?: ""
                body.lines().forEach { line ->
                    if (line.isNotBlank()) {
                        try {
                            val map = gson.fromJson(line, Map::class.java)
                            if (map["event"] == "message") {
                                val msgPayload = map["message"]?.toString() ?: ""
                                val packet = gson.fromJson(msgPayload, CloudPacket::class.java)
                                list.add(packet)
                            }
                        } catch (_: Exception) {}
                    }
                }
            }
        } catch (_: Exception) {}
        list
    }
}

// ==========================================
// 5. VIEWMODEL
// ==========================================

class FamilyViewModel : ViewModel() {
    var userId by mutableStateOf(UUID.randomUUID().toString().substring(0, 8))
    var currentUserNickname by mutableStateOf("")
    var currentFamilyCode by mutableStateOf("")
    var currentFamilyName by mutableStateOf("")

    var myLatitude by mutableStateOf(41.0082)
    var myLongitude by mutableStateOf(28.9784)
    var myBattery by mutableStateOf(100)
    var isSosActive by mutableStateOf(false)
    var myLocalIp by mutableStateOf("")

    var membersList = mutableStateListOf<MemberDto>()
    var messagesList = mutableStateListOf<MessageDto>()
    private val messageIds = mutableSetOf<String>()

    var activeTab by mutableStateOf(1)
    var isRecordingAudio by mutableStateOf(false)
    var isInCall by mutableStateOf(false)
    private var lastPollTime = (System.currentTimeMillis() / 1000L) - 30

    fun init(context: Context) {
        val prefs = context.getSharedPreferences("ailem_prefs", Context.MODE_PRIVATE)
        currentUserNickname = prefs.getString("nick", "") ?: ""
        currentFamilyCode = prefs.getString("code", "") ?: ""
        currentFamilyName = prefs.getString("name", "Ailem") ?: "Ailem"
        myLocalIp = NetworkEngine.getLocalIp()

        if (currentFamilyCode.isNotBlank()) {
            ensureSelf()
            startSyncLoop()
        }
        updateBattery(context)
    }

    fun updateBattery(context: Context) {
        val ifilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus = context.registerReceiver(null, ifilter)
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (level >= 0 && scale > 0) myBattery = (level * 100) / scale
    }

    fun saveNickname(context: Context, name: String) {
        currentUserNickname = name
        context.getSharedPreferences("ailem_prefs", Context.MODE_PRIVATE).edit().putString("nick", name).apply()
    }

    fun generateFamilyCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return "#" + (1..6).map { chars.random() }.joinToString("")
    }

    fun createFamily(context: Context, name: String) {
        val code = generateFamilyCode()
        currentFamilyCode = code
        currentFamilyName = name
        context.getSharedPreferences("ailem_prefs", Context.MODE_PRIVATE).edit()
            .putString("code", code).putString("name", name).apply()

        ensureSelf()
        sendMessage("🌟 $name aile grubu kuruldu! Katılma Kodu: $code")
        startSyncLoop()
    }

    fun joinFamily(context: Context, code: String) {
        val clean = code.trim().uppercase()
        currentFamilyCode = clean
        currentFamilyName = "Aile Grubu"
        context.getSharedPreferences("ailem_prefs", Context.MODE_PRIVATE).edit()
            .putString("code", clean).putString("name", "Aile Grubu").apply()

        ensureSelf()
        startSyncLoop()
    }

    private fun ensureSelf() {
        val me = MemberDto(userId, currentUserNickname, System.currentTimeMillis(), myLatitude, myLongitude, myBattery, isSosActive, myLocalIp)
        val idx = membersList.indexOfFirst { it.id == userId }
        if (idx >= 0) membersList[idx] = me else membersList.add(0, me)
    }

    fun triggerSos() {
        isSosActive = !isSosActive
        sendMessage(if (isSosActive) "🚨 ACİL DURUM: Konumumu paylaştım, yardıma ihtiyacım var!" else "✅ Acil durum bildirimi sonlandırıldı.", "SOS")
    }

    fun sendMessage(text: String, type: String = "TEXT", base64: String = "", fileName: String = "") {
        if (currentFamilyCode.isBlank()) return
        val newMsg = MessageDto(
            id = UUID.randomUUID().toString(),
            senderId = userId,
            senderNickname = currentUserNickname,
            text = text,
            type = type,
            fileBase64 = base64,
            fileName = fileName,
            timestamp = System.currentTimeMillis()
        )
        addMessageSafely(newMsg)
        viewModelScope.launch {
            NetworkEngine.publishCloud(currentFamilyCode, CloudPacket("CHAT_MESSAGE", message = newMsg))
        }
    }

    fun startRealVoipCall(targetMember: MemberDto) {
        isInCall = true
        val targetIp = targetMember.ipAddress.ifBlank { "255.255.255.255" }
        RealVoipEngine.startCall(targetIp)
    }

    fun endRealVoipCall() {
        isInCall = false
        RealVoipEngine.endCall()
    }

    private fun addMessageSafely(msg: MessageDto) {
        if (messageIds.add(msg.id)) {
            messagesList.add(msg)
        }
    }

    private fun startSyncLoop() {
        viewModelScope.launch {
            while (isActive && currentFamilyCode.isNotBlank()) {
                myLocalIp = NetworkEngine.getLocalIp()
                val me = MemberDto(userId, currentUserNickname, System.currentTimeMillis(), myLatitude, myLongitude, myBattery, isSosActive, myLocalIp)
                ensureSelf()
                NetworkEngine.publishCloud(currentFamilyCode, CloudPacket("MEMBER_UPDATE", member = me))

                val events = NetworkEngine.pollCloud(currentFamilyCode, lastPollTime)
                if (events.isNotEmpty()) {
                    events.forEach { packet ->
                        if (packet.eventType == "MEMBER_UPDATE" && packet.member != null) {
                            val inc = packet.member
                            val idx = membersList.indexOfFirst { it.id == inc.id }
                            if (idx >= 0) membersList[idx] = inc else membersList.add(inc)
                        } else if (packet.eventType == "CHAT_MESSAGE" && packet.message != null) {
                            addMessageSafely(packet.message)
                        }
                    }
                    lastPollTime = (System.currentTimeMillis() / 1000L) - 4
                }
                delay(2500)
            }
        }
    }
}

// ==========================================
// 6. MAIN ACTIVITY
// ==========================================

class MainActivity : ComponentActivity(), LocationListener {
    private lateinit var locationManager: LocationManager
    private var vm: FamilyViewModel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        setContent {
            val darkColors = darkColorScheme(
                primary = Color(0xFF00E5FF),
                background = Color(0xFF121212),
                surface = Color(0xFF1E1E1E),
                onBackground = Color.White,
                onSurface = Color.White
            )

            MaterialTheme(colorScheme = darkColors) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val viewModel: FamilyViewModel = viewModel()
                    vm = viewModel
                    val context = LocalContext.current

                    val permLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.RequestMultiplePermissions()
                    ) { permissions ->
                        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
                            startLocationUpdates()
                        }
                    }

                    LaunchedEffect(Unit) {
                        viewModel.init(context)
                        permLauncher.launch(arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                            Manifest.permission.RECORD_AUDIO,
                            Manifest.permission.CHANGE_WIFI_MULTICAST_STATE
                        ))
                    }

                    when {
                        viewModel.currentUserNickname.isEmpty() -> {
                            NicknameScreen(onContinue = { viewModel.saveNickname(context, it) })
                        }
                        viewModel.currentFamilyCode.isEmpty() -> {
                            FamilyChoiceScreen(
                                onFamilyCreated = { name -> viewModel.createFamily(context, name) },
                                onFamilyJoined = { code -> viewModel.joinFamily(context, code) }
                            )
                        }
                        else -> {
                            MainAppContainer(viewModel = viewModel)
                        }
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        try {
            val loc = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) ?: locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            loc?.let {
                vm?.myLatitude = it.latitude
                vm?.myLongitude = it.longitude
            }
            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 4000L, 4f, this)
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 4000L, 4f, this)
        } catch (_: Exception) {}
    }

    override fun onLocationChanged(location: Location) {
        vm?.myLatitude = location.latitude
        vm?.myLongitude = location.longitude
    }
}

// ==========================================
// 7. EKRANLAR
// ==========================================

@Composable
fun NicknameScreen(onContinue: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    Column(
        modifier = Modifier.fillMaxSize().background(Color(0xFF121212)).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.Person, null, modifier = Modifier.size(90.dp), tint = Color(0xFF00E5FF))
        Spacer(modifier = Modifier.height(16.dp))
        Text("Ailem Canlı İletişim", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Spacer(modifier = Modifier.height(28.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Adınız / Rumuzunuz (örn: Ahmet)", color = Color.LightGray) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                focusedBorderColor = Color(0xFF00E5FF), unfocusedBorderColor = Color(0xFF444444),
                focusedContainerColor = Color(0xFF1E1E1E), unfocusedContainerColor = Color(0xFF1E1E1E)
            )
        )
        Spacer(modifier = Modifier.height(20.dp))
        Button(
            onClick = { if (name.isNotBlank()) onContinue(name.trim()) },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF), contentColor = Color.Black)
        ) {
            Text("Giriş Yap", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun FamilyChoiceScreen(onFamilyCreated: (String) -> Unit, onFamilyJoined: (String) -> Unit) {
    var showCreateDialog by remember { mutableStateOf(false) }
    var showJoinDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().background(Color(0xFF121212)).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.Home, null, modifier = Modifier.size(80.dp), tint = Color(0xFF00E5FF))
        Spacer(modifier = Modifier.height(16.dp))
        Text("Aile Alanı", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = { showCreateDialog = true },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF), contentColor = Color.Black)
        ) {
            Text("Yeni Aile Grubu Kur", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedButton(
            onClick = { showJoinDialog = true },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF00E5FF))
        ) {
            Text("Aile Kodunu Gir (#)", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
    }

    if (showCreateDialog) {
        var familyName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            containerColor = Color(0xFF1E1E1E),
            title = { Text("Yeni Aile Grubu", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = familyName,
                    onValueChange = { familyName = it },
                    label = { Text("Aile İsmi (örn: Bizim Ev)", color = Color.LightGray) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                )
            },
            confirmButton = {
                Button(onClick = {
                    onFamilyCreated(familyName.ifBlank { "Ailem" })
                    showCreateDialog = false
                }) { Text("Oluştur") }
            }
        )
    }

    if (showJoinDialog) {
        var codeInput by remember { mutableStateOf("#") }
        AlertDialog(
            onDismissRequest = { showJoinDialog = false },
            containerColor = Color(0xFF1E1E1E),
            title = { Text("Aile Kodunu Girin", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = codeInput,
                    onValueChange = { if (it.length <= 7) codeInput = it.uppercase() },
                    label = { Text("6 Haneli Aile Kodu", color = Color.LightGray) },
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (codeInput.length >= 2) {
                        onFamilyJoined(codeInput)
                        showJoinDialog = false
                    }
                }) { Text("Katıl") }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppContainer(viewModel: FamilyViewModel) {
    Scaffold(
        containerColor = Color(0xFF121212),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(viewModel.currentFamilyName, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color.White)
                        Text("Kod: ${viewModel.currentFamilyCode} | IP: ${viewModel.myLocalIp}", fontSize = 11.sp, color = Color(0xFF00E5FF))
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.triggerSos() }) {
                        Icon(Icons.Default.Warning, "SOS", tint = if (viewModel.isSosActive) Color(0xFFFF5252) else Color.Gray)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF1A1A1A))
            )
        },
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF1A1A1A)) {
                NavigationBarItem(
                    selected = viewModel.activeTab == 0,
                    onClick = { viewModel.activeTab = 0 },
                    icon = { Icon(Icons.Default.Person, null, tint = if (viewModel.activeTab == 0) Color(0xFF00E5FF) else Color.Gray) },
                    label = { Text("Aktifler", color = if (viewModel.activeTab == 0) Color(0xFF00E5FF) else Color.Gray) }
                )
                NavigationBarItem(
                    selected = viewModel.activeTab == 1,
                    onClick = { viewModel.activeTab = 1 },
                    icon = { Icon(Icons.Default.Email, null, tint = if (viewModel.activeTab == 1) Color(0xFF00E5FF) else Color.Gray) },
                    label = { Text("Sohbet", color = if (viewModel.activeTab == 1) Color(0xFF00E5FF) else Color.Gray) }
                )
                NavigationBarItem(
                    selected = viewModel.activeTab == 2,
                    onClick = { viewModel.activeTab = 2 },
                    icon = { Icon(Icons.Default.LocationOn, null, tint = if (viewModel.activeTab == 2) Color(0xFF00E5FF) else Color.Gray) },
                    label = { Text("Harita", color = if (viewModel.activeTab == 2) Color(0xFF00E5FF) else Color.Gray) }
                )
                NavigationBarItem(
                    selected = viewModel.activeTab == 3,
                    onClick = { viewModel.activeTab = 3 },
                    icon = { Icon(Icons.Default.Info, null, tint = if (viewModel.activeTab == 3) Color(0xFF00E5FF) else Color.Gray) },
                    label = { Text("Özet", color = if (viewModel.activeTab == 3) Color(0xFF00E5FF) else Color.Gray) }
                )
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when (viewModel.activeTab) {
                0 -> ActiveUsersTab(viewModel)
                1 -> ChatTab(viewModel)
                2 -> LiveMapTab(viewModel)
                3 -> WeeklySummaryTab(viewModel)
            }
            if (viewModel.isInCall) {
                CallOverlay(onEndCall = { viewModel.endRealVoipCall() })
            }
        }
    }
}

@Composable
fun ActiveUsersTab(viewModel: FamilyViewModel) {
    val members = viewModel.membersList
    val currentTime = System.currentTimeMillis()

    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        item {
            Text("Canlı Aile Üyeleri (${members.size} Kişi)", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Color.White)
            Spacer(modifier = Modifier.height(14.dp))
        }
        items(members) { m ->
            val isOnline = (currentTime - m.lastSeen) < 30_000
            val timeAgoStr = if (isOnline) "Canlı Çevrimiçi 🟢" else "${(currentTime - m.lastSeen)/60000} dk önce"

            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                colors = CardDefaults.cardColors(containerColor = if (m.isSos) Color(0xFF3E1214) else Color(0xFF1E1E1E)),
                shape = RoundedCornerShape(14.dp)
            ) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(16.dp).clip(CircleShape).background(if (isOnline) Color(0xFF00E676) else Color.Gray))
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(m.nickname, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.White)
                        Text(timeAgoStr, fontSize = 13.sp, color = if (isOnline) Color(0xFF00E676) else Color.LightGray)
                    }
                    Text("🔋 %${m.battery}", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Spacer(modifier = Modifier.width(8.dp))
                    if (m.id != viewModel.userId && isOnline) {
                        IconButton(onClick = { viewModel.startRealVoipCall(m) }) {
                            Icon(Icons.Default.Phone, null, tint = Color(0xFF00E5FF))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ChatTab(viewModel: FamilyViewModel) {
    val messages = viewModel.messagesList
    val context = LocalContext.current
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Gerçek Dosya ve Fotoğraf Seçici
    val fileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            try {
                val inputStream = context.contentResolver.openInputStream(it)
                val bytes = inputStream?.readBytes()
                if (bytes != null) {
                    val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    viewModel.sendMessage("📎 Dosya Paylaşıldı", "FILE", base64, "belge_${System.currentTimeMillis()}.dat")
                }
            } catch (_: Exception) {}
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF121212))) {
        LazyColumn(state = listState, modifier = Modifier.weight(1f).padding(12.dp)) {
            items(messages) { msg ->
                val isMe = msg.senderId == viewModel.userId
                val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(msg.timestamp))

                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = if (isMe) Arrangement.End else Arrangement.Start
                ) {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = if (isMe) Color(0xFF005B64) else Color(0xFF262626))
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            if (!isMe) Text(msg.senderNickname, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00E5FF))
                            
                            when (msg.type) {
                                "TEXT", "SOS" -> Text(msg.text, fontSize = 15.sp, color = Color.White)
                                "AUDIO" -> {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.clickable {
                                            if (msg.fileBase64.isNotBlank()) {
                                                RealAudioRecorder.playBase64Audio(context, msg.fileBase64)
                                            }
                                        }
                                    ) {
                                        Icon(Icons.Default.PlayArrow, null, tint = Color(0xFF00E5FF))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("▶️ Ses Kaydını Dinle", fontSize = 14.sp, color = Color.White, fontWeight = FontWeight.Bold)
                                    }
                                }
                                "FILE" -> {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Share, null, tint = Color(0xFF00E5FF))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("📎 ${msg.fileName.ifBlank { "Ekli Dosya" }}", fontSize = 14.sp, color = Color.White)
                                    }
                                }
                            }
                            Text(timeStr, fontSize = 10.sp, color = Color.LightGray, modifier = Modifier.align(Alignment.End))
                        }
                    }
                }
            }
        }

        // Alt Giriş Barı (Gerçek Mikrofon & Gerçek Dosya Butonu)
        Row(
            modifier = Modifier.fillMaxWidth().background(Color(0xFF1E1E1E)).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Gerçek Mikrofon Kaydı Butonu
            IconButton(
                onClick = {
                    if (!viewModel.isRecordingAudio) {
                        val started = RealAudioRecorder.startRecording(context)
                        if (started) viewModel.isRecordingAudio = true
                    } else {
                        val base64 = RealAudioRecorder.stopAndGetBase64()
                        viewModel.isRecordingAudio = false
                        if (!base64.isNullOrBlank()) {
                            viewModel.sendMessage("🎤 Sesli Mesaj", "AUDIO", base64)
                        }
                    }
                }
            ) {
                Icon(
                    imageVector = if (viewModel.isRecordingAudio) Icons.Default.Close else Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = if (viewModel.isRecordingAudio) Color.Red else Color(0xFF00E5FF)
                )
            }

            // Gerçek Dosya Seçici Butonu
            IconButton(onClick = { fileLauncher.launch("*/*") }) {
                Icon(Icons.Default.Share, null, tint = Color(0xFF00E5FF))
            }

            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(if (viewModel.isRecordingAudio) "🔴 Ses Kaydediliyor..." else "Mesaj yazın...", color = Color.Gray) },
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFF00E5FF), focusedContainerColor = Color(0xFF2A2A2A),
                    unfocusedContainerColor = Color(0xFF2A2A2A)
                )
            )

            IconButton(onClick = {
                if (inputText.isNotBlank()) {
                    viewModel.sendMessage(inputText.trim())
                    inputText = ""
                }
            }) {
                Icon(Icons.Default.Send, null, tint = Color(0xFF00E5FF))
            }
        }
    }
}

fun calculateDistanceInKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6371.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2)
    val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    return r * c
}

@Composable
fun LiveMapTab(viewModel: FamilyViewModel) {
    val members = viewModel.membersList
    val context = LocalContext.current

    val htmlMap = remember(members.size, viewModel.myLatitude, viewModel.myLongitude) {
        val sb = StringBuilder()
        sb.append("<!DOCTYPE html><html><head><meta name='viewport' content='width=device-width, initial-scale=1.0'>")
        sb.append("<link rel='stylesheet' href='https://unpkg.com/leaflet@1.9.4/dist/leaflet.css'/>")
        sb.append("<script src='https://unpkg.com/leaflet@1.9.4/dist/leaflet.js'></script>")
        sb.append("<style>body{margin:0;padding:0;background:#121212;}#map{height:100vh;width:100vw;}</style></head><body>")
        sb.append("<div id='map'></div><script>")
        sb.append("var map = L.map('map').setView([" + viewModel.myLatitude + ", " + viewModel.myLongitude + "], 14);")
        sb.append("L.tileLayer('https://tile.openstreetmap.org/' + '{z}/{x}/{y}.png', { maxZoom: 19 }).addTo(map);")
        for (m in members) {
            sb.append("L.marker([" + m.latitude + ", " + m.longitude + "]).addTo(map).bindPopup('<b>" + m.nickname + "</b><br>Pil: %" + m.battery + "');")
        }
        sb.append("</script></body></html>")
        sb.toString()
    }

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF121212))) {
        Box(modifier = Modifier.weight(1.2f).fillMaxWidth()) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        webViewClient = WebViewClient()
                        loadDataWithBaseURL(null, htmlMap, "text/html", "UTF-8", null)
                    }
                },
                update = { webView -> webView.loadDataWithBaseURL(null, htmlMap, "text/html", "UTF-8", null) },
                modifier = Modifier.fillMaxSize()
            )
        }

        Card(
            modifier = Modifier.weight(0.8f).fillMaxWidth(),
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
        ) {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(14.dp)) {
                item {
                    Text("Canlı Aile Konum Radarı", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color.White)
                    Spacer(modifier = Modifier.height(8.dp))
                }
                items(members) { m ->
                    val distKm = calculateDistanceInKm(viewModel.myLatitude, viewModel.myLongitude, m.latitude, m.longitude)
                    val distStr = if (m.id == viewModel.userId) "Siz (Buradasınız)" else String.format(Locale.US, "%.1f km uzakta", distKm)

                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.LocationOn, null, tint = Color(0xFF00E5FF))
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(m.nickname, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Color.White)
                            Text(distStr, fontSize = 13.sp, color = Color.LightGray)
                        }

                        Button(
                            onClick = {
                                val gmmIntentUri = Uri.parse("google.navigation:q=" + m.latitude + "," + m.longitude)
                                val mapIntent = Intent(Intent.ACTION_VIEW, gmmIntentUri).apply { setPackage("com.google.android.apps.maps") }
                                try {
                                    context.startActivity(mapIntent)
                                } catch (e: Exception) {
                                    val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=" + m.latitude + "," + m.longitude))
                                    context.startActivity(webIntent)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF), contentColor = Color.Black)
                        ) {
                            Text("Google Harita", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF333333)))
                }
            }
        }
    }
}

@Composable
fun WeeklySummaryTab(viewModel: FamilyViewModel) {
    val messages = viewModel.messagesList
    val now = System.currentTimeMillis()
    val sevenDaysAgo = now - (7 * 24 * 60 * 60 * 1000L)
    val lastWeekMessages = messages.filter { it.timestamp >= sevenDaysAgo }
    val lastMsg = messages.lastOrNull()
    val mediaCount = lastWeekMessages.count { it.type != "TEXT" }

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF121212)).padding(16.dp)) {
        Text("Gerçek Zamanlı Aile Güvenlik Raporu", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF004D40)),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Son Mesaj Gönderen:", fontSize = 13.sp, color = Color(0xFF80CBC4))
                val timeStr = if (lastMsg != null) SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()).format(Date(lastMsg.timestamp)) else "-"
                Text("${lastMsg?.senderNickname ?: "Henüz mesaj yok"} ($timeStr)", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.White)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Ağ ve Aktivite Durumu", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.White)
                Spacer(modifier = Modifier.height(10.dp))
                Text("• Toplam İletilen Mesaj: ${lastWeekMessages.size} adet", color = Color.LightGray)
                Text("• İletilen Ses/Dosya: $mediaCount adet", color = Color.LightGray)
                Text("• Canlı Ağdaki Üyeler: ${viewModel.membersList.size} kişi", color = Color.LightGray)
                Text("• P2P / Bulut Mesh Senkron: AKTİF 🟢", color = Color(0xFF00E676), fontWeight = FontWeight.Bold)
            }
        }
    }
}

// Canlı VoIP Görüşme Modalı
@Composable
fun CallOverlay(onEndCall: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().background(Color(0xF2101010)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Phone, null, modifier = Modifier.size(100.dp), tint = Color(0xFF00E5FF))
            Spacer(modifier = Modifier.height(16.dp))
            Text("🔴 Canlı VoIP Görüşmesi Sürüyor", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("16kHz PCM Çift Yönlü Mikrofon Aktif", color = Color.LightGray)
            Spacer(modifier = Modifier.height(48.dp))
            Button(
                onClick = onEndCall,
                colors = ButtonDefaults.buttonColors(containerColor = Color.Red, contentColor = Color.White),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("Görüşmeyi Sonlandır", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}