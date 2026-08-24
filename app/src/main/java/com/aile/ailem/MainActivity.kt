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
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

// ==========================================
// 1. CANLI VERİ PAKETLERİ
// ==========================================

data class MemberDto(
    val id: String = "",
    val nickname: String = "",
    val lastSeen: Long = 0L,
    val latitude: Double = 41.0082,
    val longitude: Double = 28.9784,
    val battery: Int = 100,
    val isSos: Boolean = false
)

data class MessageDto(
    val id: String = "",
    val senderId: String = "",
    val senderNickname: String = "",
    val text: String = "",
    val type: String = "TEXT",
    val timestamp: Long = 0L
)

// Bulut Paketi (Senkronizasyon Mesajı)
data class CloudPacket(
    val eventType: String = "", // "MEMBER_UPDATE" veya "CHAT_MESSAGE"
    val member: MemberDto? = null,
    val message: MessageDto? = null,
    val familyName: String? = null
)

// ==========================================
// 2. GERÇEK ZAMANLI KÜRESEL BULUT AĞI (NTFY ENGINE)
// ==========================================

object LiveMeshNetwork {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()
    private val mediaType = "text/plain; charset=utf-8".toMediaType()

    private fun getChannelUrl(familyCode: String): String {
        val clean = familyCode.replace("#", "").lowercase().trim()
        return "https://ntfy.sh/ailem_room_$clean"
    }

    // Buluta veri yayınla
    suspend fun publish(familyCode: String, packet: CloudPacket) = withContext(Dispatchers.IO) {
        try {
            val url = getChannelUrl(familyCode)
            val json = gson.toJson(packet)
            val request = Request.Builder().url(url).post(json.toRequestBody(mediaType)).build()
            client.newCall(request).execute().close()
        } catch (_: Exception) {}
    }

    // Buluttan son olayları çek
    suspend fun pollEvents(familyCode: String, sinceTimeSec: Long): List<CloudPacket> = withContext(Dispatchers.IO) {
        val list = mutableListOf<CloudPacket>()
        try {
            val url = getChannelUrl(familyCode) + "/json?since=${sinceTimeSec}"
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val body = response.body?.string() ?: return@withContext emptyList()
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
// 3. VIEWMODEL
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

    var membersList = mutableStateListOf<MemberDto>()
    var messagesList = mutableStateListOf<MessageDto>()
    private val messageIds = mutableSetOf<String>()

    var activeTab by mutableStateOf(1)
    var isInCall by mutableStateOf(false)
    private var lastPollTime = (System.currentTimeMillis() - (6 * 60 * 60 * 1000L)) / 1000L // Son 6 saati al

    fun initPrefs(context: Context) {
        val prefs = context.getSharedPreferences("ailem_prefs", Context.MODE_PRIVATE)
        val savedNick = prefs.getString("nick", "") ?: ""
        val savedCode = prefs.getString("code", "") ?: ""
        val savedName = prefs.getString("name", "") ?: "Ailem"
        val savedId = prefs.getString("uid", "") ?: ""

        if (savedId.isNotBlank()) userId = savedId else prefs.edit().putString("uid", userId).apply()
        if (savedNick.isNotBlank()) currentUserNickname = savedNick
        if (savedCode.isNotBlank()) {
            currentFamilyCode = savedCode
            currentFamilyName = savedName
            ensureSelfInMembers()
            startLiveSync()
        }
        updateBattery(context)
    }

    fun updateBattery(context: Context) {
        val ifilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus: Intent? = context.registerReceiver(null, ifilter)
        val level: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (level >= 0 && scale > 0) {
            myBattery = (level * 100) / scale
        }
    }

    fun saveNickname(context: Context, name: String) {
        currentUserNickname = name
        context.getSharedPreferences("ailem_prefs", Context.MODE_PRIVATE).edit().putString("nick", name).apply()
    }

    fun generateFamilyCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return "#" + (1..6).map { chars.random() }.joinToString("")
    }

    fun createFamily(context: Context, name: String, maxMembers: Int) {
        val code = generateFamilyCode()
        currentFamilyCode = code
        currentFamilyName = name

        context.getSharedPreferences("ailem_prefs", Context.MODE_PRIVATE).edit()
            .putString("code", code)
            .putString("name", name)
            .apply()

        ensureSelfInMembers()
        val welcomeMsg = MessageDto(
            id = UUID.randomUUID().toString(),
            senderId = "system",
            senderNickname = "Sistem 🌟",
            text = "$name grubu kuruldu! Katılma Kodu: $code",
            type = "TEXT",
            timestamp = System.currentTimeMillis()
        )
        addMessageSafely(welcomeMsg)

        viewModelScope.launch {
            val me = MemberDto(userId, currentUserNickname, System.currentTimeMillis(), myLatitude, myLongitude, myBattery, isSosActive)
            LiveMeshNetwork.publish(code, CloudPacket("MEMBER_UPDATE", member = me, familyName = name))
            LiveMeshNetwork.publish(code, CloudPacket("CHAT_MESSAGE", message = welcomeMsg))
            startLiveSync()
        }
    }

    fun joinFamily(context: Context, code: String) {
        val cleanCode = code.trim().uppercase()
        val defaultName = "Aile Grubu"
        currentFamilyCode = cleanCode
        currentFamilyName = defaultName

        context.getSharedPreferences("ailem_prefs", Context.MODE_PRIVATE).edit()
            .putString("code", cleanCode)
            .putString("name", defaultName)
            .apply()

        ensureSelfInMembers()

        viewModelScope.launch {
            val me = MemberDto(userId, currentUserNickname, System.currentTimeMillis(), myLatitude, myLongitude, myBattery, isSosActive)
            LiveMeshNetwork.publish(cleanCode, CloudPacket("MEMBER_UPDATE", member = me))
            startLiveSync()
        }
    }

    private fun ensureSelfInMembers() {
        val me = MemberDto(userId, currentUserNickname, System.currentTimeMillis(), myLatitude, myLongitude, myBattery, isSosActive)
        val idx = membersList.indexOfFirst { it.id == userId }
        if (idx >= 0) {
            membersList[idx] = me
        } else {
            membersList.add(0, me)
        }
    }

    fun triggerSos() {
        isSosActive = !isSosActive
        val text = if (isSosActive) "🚨 ACİL DURUM: Konumumu paylaştım, yardıma ihtiyacım var!" else "✅ Acil durum bildirimi sonlandırıldı."
        sendMessage(text, "SOS")
    }

    fun sendMessage(text: String, type: String = "TEXT") {
        if (text.isBlank() && type == "TEXT") return
        if (currentFamilyCode.isBlank()) return

        val newMsg = MessageDto(
            id = UUID.randomUUID().toString(),
            senderId = userId,
            senderNickname = currentUserNickname,
            text = text,
            type = type,
            timestamp = System.currentTimeMillis()
        )

        addMessageSafely(newMsg)
        viewModelScope.launch {
            LiveMeshNetwork.publish(currentFamilyCode, CloudPacket("CHAT_MESSAGE", message = newMsg))
        }
    }

    private fun addMessageSafely(msg: MessageDto) {
        if (messageIds.add(msg.id)) {
            messagesList.add(msg)
        }
    }

    private fun startLiveSync() {
        viewModelScope.launch {
            while (isActive && currentFamilyCode.isNotBlank()) {
                // 1. Kendi canlılık ve konum sinyalimizi buluta bas
                val me = MemberDto(userId, currentUserNickname, System.currentTimeMillis(), myLatitude, myLongitude, myBattery, isSosActive)
                ensureSelfInMembers()
                LiveMeshNetwork.publish(currentFamilyCode, CloudPacket("MEMBER_UPDATE", member = me, familyName = currentFamilyName))

                // 2. Buluttan diğer aile fertlerinin mesaj ve konumlarını çek
                val packets = LiveMeshNetwork.pollEvents(currentFamilyCode, lastPollTime)
                if (packets.isNotEmpty()) {
                    packets.forEach { packet ->
                        if (!packet.familyName.isNullOrBlank()) {
                            currentFamilyName = packet.familyName
                        }
                        if (packet.eventType == "MEMBER_UPDATE" && packet.member != null) {
                            val incoming = packet.member
                            val existingIndex = membersList.indexOfFirst { it.id == incoming.id }
                            if (existingIndex >= 0) {
                                membersList[existingIndex] = incoming
                            } else {
                                membersList.add(incoming)
                            }
                        } else if (packet.eventType == "CHAT_MESSAGE" && packet.message != null) {
                            addMessageSafely(packet.message)
                        }
                    }
                    lastPollTime = (System.currentTimeMillis() / 1000L) - 5
                }
                delay(3000) // 3 saniyede bir canlı senkronize et
            }
        }
    }
}

// ==========================================
// 4. MAIN ACTIVITY (DARK THEME)
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
                onPrimary = Color.Black,
                primaryContainer = Color(0xFF004D40),
                onPrimaryContainer = Color(0xFFE0F7FA),
                secondary = Color(0xFFFF5252),
                background = Color(0xFF121212),
                surface = Color(0xFF1E1E1E),
                surfaceVariant = Color(0xFF2C2C2C),
                onBackground = Color.White,
                onSurface = Color.White,
                onSurfaceVariant = Color(0xFFE0E0E0)
            )

            MaterialTheme(colorScheme = darkColors) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val viewModel: FamilyViewModel = viewModel()
                    vm = viewModel
                    val context = LocalContext.current

                    val permissionLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.RequestMultiplePermissions()
                    ) { permissions ->
                        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
                            startLocationUpdates()
                        }
                    }

                    LaunchedEffect(Unit) {
                        viewModel.initPrefs(context)
                        permissionLauncher.launch(arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                            Manifest.permission.RECORD_AUDIO
                        ))
                    }

                    when {
                        viewModel.currentUserNickname.isEmpty() -> {
                            NicknameScreen(onContinue = { viewModel.saveNickname(context, it) })
                        }
                        viewModel.currentFamilyCode.isEmpty() -> {
                            FamilyChoiceScreen(
                                onFamilyCreated = { name, max -> viewModel.createFamily(context, name, max) },
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
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {

                val lastKnown = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                    ?: locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                lastKnown?.let {
                    vm?.myLatitude = it.latitude
                    vm?.myLongitude = it.longitude
                }

                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000L, 5f, this)
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 5000L, 5f, this)
            }
        } catch (_: Exception) {}
    }

    override fun onLocationChanged(location: Location) {
        vm?.myLatitude = location.latitude
        vm?.myLongitude = location.longitude
    }
}

// ==========================================
// 5. EKRANLAR (DARK MODE & BEYAZ YAZILAR)
// ==========================================

@Composable
fun NicknameScreen(onContinue: (String) -> Unit) {
    var name by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(90.dp), tint = Color(0xFF00E5FF))
        Spacer(modifier = Modifier.height(16.dp))
        Text("Ailem Canlı İletişim", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Spacer(modifier = Modifier.height(6.dp))
        Text("Ailenizin sizi tanıyacağı bir isim girin", color = Color.LightGray, textAlign = TextAlign.Center, fontSize = 14.sp)
        Spacer(modifier = Modifier.height(28.dp))

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Adınız / Rumuzunuz (örn: Ahmet)", color = Color.LightGray) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = Color(0xFF00E5FF),
                unfocusedBorderColor = Color(0xFF444444),
                focusedContainerColor = Color(0xFF1E1E1E),
                unfocusedContainerColor = Color(0xFF1E1E1E)
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
fun FamilyChoiceScreen(
    onFamilyCreated: (String, Int) -> Unit,
    onFamilyJoined: (String) -> Unit
) {
    var showCreateDialog by remember { mutableStateOf(false) }
    var showJoinDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.Home, contentDescription = null, modifier = Modifier.size(80.dp), tint = Color(0xFF00E5FF))
        Spacer(modifier = Modifier.height(16.dp))
        Text("Aile Alanı", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Spacer(modifier = Modifier.height(8.dp))
        Text("Yeni bir aile grubu kurun veya mevcut bir koda katılın.", color = Color.LightGray, textAlign = TextAlign.Center)

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = { showCreateDialog = true },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF), contentColor = Color.Black)
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Yeni Aile Grubu Kur", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedButton(
            onClick = { showJoinDialog = true },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF00E5FF)),
            border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(Color(0xFF00E5FF)))
        ) {
            Icon(Icons.Default.Person, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Aile Kodunu Gir (#)", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
    }

    if (showCreateDialog) {
        var familyName by remember { mutableStateOf("") }
        var memberLimit by remember { mutableStateOf("10") }
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            containerColor = Color(0xFF1E1E1E),
            title = { Text("Yeni Aile Grubu", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    OutlinedTextField(
                        value = familyName,
                        onValueChange = { familyName = it },
                        label = { Text("Aile İsmi (örn: Bizim Aile)", color = Color.LightGray) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF00E5FF),
                            unfocusedBorderColor = Color.Gray
                        )
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = memberLimit,
                        onValueChange = { memberLimit = it },
                        label = { Text("Kişi Sınırı", color = Color.LightGray) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF00E5FF),
                            unfocusedBorderColor = Color.Gray
                        )
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val name = familyName.ifBlank { "Ailem" }
                        val limit = memberLimit.toIntOrNull() ?: 10
                        onFamilyCreated(name, limit)
                        showCreateDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF), contentColor = Color.Black)
                ) { Text("Hemen Oluştur", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) { Text("İptal", color = Color.Gray) }
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
                Column {
                    OutlinedTextField(
                        value = codeInput,
                        onValueChange = { if (it.length <= 7) codeInput = it.uppercase() },
                        label = { Text("6 Haneli Aile Kodu", color = Color.LightGray) },
                        placeholder = { Text("#A1B2C3", color = Color.DarkGray) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF00E5FF),
                            unfocusedBorderColor = Color.Gray
                        )
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (codeInput.length >= 2) {
                            onFamilyJoined(codeInput)
                            showJoinDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF), contentColor = Color.Black)
                ) { Text("Katıl", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showJoinDialog = false }) { Text("İptal", color = Color.Gray) }
            }
        )
    }
}

// ==========================================
// 6. ANA PANEL (4 SEKME & CANLI SENKRON)
// ==========================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppContainer(viewModel: FamilyViewModel) {
    Scaffold(
        containerColor = Color(0xFF121212),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(viewModel.currentFamilyName.ifBlank { "Ailem" }, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color.White)
                        Text("Aile Kodu: ${viewModel.currentFamilyCode}", fontSize = 12.sp, color = Color(0xFF00E5FF), fontWeight = FontWeight.SemiBold)
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.triggerSos() }) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = "SOS",
                            tint = if (viewModel.isSosActive) Color(0xFFFF5252) else Color.Gray,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    if (viewModel.activeTab == 1) {
                        IconButton(onClick = { viewModel.isInCall = true }) {
                            Icon(Icons.Default.Phone, contentDescription = "Görüşme", tint = Color(0xFF00E5FF))
                        }
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
        Box(modifier = Modifier.padding(padding).background(Color(0xFF121212))) {
            when (viewModel.activeTab) {
                0 -> ActiveUsersTab(viewModel)
                1 -> ChatTab(viewModel)
                2 -> LiveMapTab(viewModel)
                3 -> WeeklySummaryTab(viewModel)
            }
            if (viewModel.isInCall) {
                CallOverlay(onEndCall = { viewModel.isInCall = false })
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
            Text("Aile Üyeleri (${members.size} Kişi)", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Color.White)
            Spacer(modifier = Modifier.height(14.dp))
        }
        items(members) { member ->
            val isOnline = (currentTime - member.lastSeen) < 30_000
            val timeAgoStr = if (isOnline) "Şu an Çevrimiçi 🟢" else {
                val diffMins = (currentTime - member.lastSeen) / (1000 * 60)
                if (diffMins < 60) "$diffMins dk önce" else "${diffMins / 60} saat önce"
            }

            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (member.isSos) Color(0xFF3E1214) else Color(0xFF1E1E1E)
                ),
                shape = RoundedCornerShape(14.dp)
            ) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(if (member.isSos) Color(0xFFFF5252) else if (isOnline) Color(0xFF00E676) else Color.Gray)
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(member.nickname, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.White)
                            if (member.isSos) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("🚨 ACİL DURUM", color = Color(0xFFFF5252), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(timeAgoStr, fontSize = 13.sp, color = if (isOnline) Color(0xFF00E676) else Color.LightGray)
                    }
                    Text("🔋 %${member.battery}", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        }
    }
}

@Composable
fun ChatTab(viewModel: FamilyViewModel) {
    val messages = viewModel.messagesList
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF121212))) {
        if (messages.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("Henüz mesaj yok. İlk mesajı siz yazın! 👋", color = Color.Gray, fontSize = 16.sp)
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                items(messages) { msg ->
                    val isMe = msg.senderId == viewModel.userId
                    val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
                    val timeStr = timeFormat.format(Date(msg.timestamp))

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = if (isMe) Arrangement.End else Arrangement.Start
                    ) {
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (msg.type == "SOS") Color(0xFFD32F2F)
                                else if (isMe) Color(0xFF005B64)
                                else Color(0xFF262626)
                            )
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                if (!isMe) {
                                    Text(msg.senderNickname, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00E5FF))
                                    Spacer(modifier = Modifier.height(2.dp))
                                }
                                when (msg.type) {
                                    "TEXT" -> Text(msg.text, fontSize = 15.sp, color = Color.White)
                                    "SOS" -> Text(msg.text, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                    "AUDIO" -> Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.PlayArrow, null, tint = Color.White)
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("🎤 Sesli Mesaj (0:12)", fontSize = 14.sp, color = Color.White, fontWeight = FontWeight.Medium)
                                    }
                                    "FILE" -> Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Share, null, tint = Color.White)
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("📎 Belge Paylaşıldı", fontSize = 14.sp, color = Color.White)
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(timeStr, fontSize = 10.sp, color = Color.LightGray, modifier = Modifier.align(Alignment.End))
                            }
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1E1E1E))
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { viewModel.sendMessage("", "AUDIO") }) {
                Icon(Icons.Default.PlayArrow, null, tint = Color(0xFF00E5FF))
            }
            IconButton(onClick = { viewModel.sendMessage("", "FILE") }) {
                Icon(Icons.Default.Share, null, tint = Color(0xFF00E5FF))
            }
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Mesaj yazın...", color = Color.Gray) },
                shape = RoundedCornerShape(24.dp),
                maxLines = 3,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFF00E5FF),
                    unfocusedBorderColor = Color(0xFF333333),
                    focusedContainerColor = Color(0xFF2A2A2A),
                    unfocusedContainerColor = Color(0xFF2A2A2A)
                )
            )
            Spacer(modifier = Modifier.width(4.dp))
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
    val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
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
                update = { webView ->
                    webView.loadDataWithBaseURL(null, htmlMap, "text/html", "UTF-8", null)
                },
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
                    Text("Canlı Aile Radarı", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color.White)
                    Spacer(modifier = Modifier.height(8.dp))
                }
                items(members) { m ->
                    val distKm = calculateDistanceInKm(viewModel.myLatitude, viewModel.myLongitude, m.latitude, m.longitude)
                    val distStr = if (m.id == viewModel.userId) "Siz (Buradasınız)" else String.format(Locale.US, "%.1f km uzakta", distKm)

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.LocationOn, contentDescription = null, tint = Color(0xFF00E5FF))
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(m.nickname, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Color.White)
                            Text(distStr, fontSize = 13.sp, color = Color.LightGray)
                        }

                        Button(
                            onClick = {
                                val gmmIntentUri = Uri.parse("google.navigation:q=" + m.latitude + "," + m.longitude)
                                val mapIntent = Intent(Intent.ACTION_VIEW, gmmIntentUri)
                                mapIntent.setPackage("com.google.android.apps.maps")
                                try {
                                    context.startActivity(mapIntent)
                                } catch (e: Exception) {
                                    val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=" + m.latitude + "," + m.longitude))
                                    context.startActivity(webIntent)
                                }
                            },
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF), contentColor = Color.Black),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text("Haritada Gör", fontSize = 12.sp, fontWeight = FontWeight.Bold)
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
        Text("Aile Güvenlik & İletişim Raporu", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF004D40)),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Son Mesaj Gönderen:", fontSize = 13.sp, color = Color(0xFF80CBC4), fontWeight = FontWeight.SemiBold)
                val timeFormat = SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault())
                val timeStr = if (lastMsg != null) timeFormat.format(Date(lastMsg.timestamp)) else "-"
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = "${lastMsg?.senderNickname ?: "Henüz mesaj yok"} ($timeStr)", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.White)
                if (lastMsg != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(text = "\"${lastMsg.text.ifBlank { "[Medya/Ses]" }}\"", fontSize = 14.sp, color = Color(0xFFE0F2F1))
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Haftalık Güvenlik Özeti", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.White)
                Spacer(modifier = Modifier.height(10.dp))
                Text("• Toplam Atılan Mesaj: ${lastWeekMessages.size} adet", color = Color.LightGray, fontSize = 14.sp)
                Spacer(modifier = Modifier.height(4.dp))
                Text("• Paylaşılan Medya & Ses: $mediaCount adet", color = Color.LightGray, fontSize = 14.sp)
                Spacer(modifier = Modifier.height(4.dp))
                Text("• Radardaki Aile Üyeleri: ${viewModel.membersList.size} kişi", color = Color.LightGray, fontSize = 14.sp)
                Spacer(modifier = Modifier.height(4.dp))
                Text("• Canlı GPS & Şarj Takibi: Aktif 🟢", color = Color(0xFF00E676), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
fun CallOverlay(onEndCall: () -> Unit) {
    var isMicMuted by remember { mutableStateOf(false) }
    var isVideoOn by remember { mutableStateOf(true) }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xF2101010)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(imageVector = Icons.Default.Person, contentDescription = null, modifier = Modifier.size(100.dp), tint = Color.White)
            Spacer(modifier = Modifier.height(16.dp))
            Text("Aile Görüşmesi Sürüyor", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("Ses ve Görüntü Aktif 🟢", color = Color(0xFF00E5FF))
            Spacer(modifier = Modifier.height(48.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                IconButton(onClick = { isMicMuted = !isMicMuted }, modifier = Modifier.background(if (isMicMuted) Color.Red else Color(0xFF333333), CircleShape)) {
                    Icon(Icons.Default.Phone, null, tint = Color.White)
                }
                IconButton(onClick = { isVideoOn = !isVideoOn }, modifier = Modifier.background(if (!isVideoOn) Color.Red else Color.DarkGray, CircleShape)) {
                    Icon(Icons.Default.Share, null, tint = Color.White)
                }
                IconButton(onClick = onEndCall, modifier = Modifier.background(Color.Red, CircleShape)) {
                    Icon(Icons.Default.Close, null, tint = Color.White)
                }
            }
        }
    }
}