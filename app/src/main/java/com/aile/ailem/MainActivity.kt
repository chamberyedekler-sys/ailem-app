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

data class FamilyDto(
    val code: String = "",
    val name: String = "",
    val maxMembers: Int = 10,
    val createdAt: Long = 0L,
    val members: Map<String, MemberDto>? = null,
    val messages: Map<String, MessageDto>? = null
)

object CloudSyncService {
    private const val BASE_URL = "https://ailem-chat-default-rtdb.firebaseio.com/families"
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun getFamily(code: String): FamilyDto? = withContext(Dispatchers.IO) {
        try {
            val safeCode = code.replace("#", "CODE_")
            val request = Request.Builder().url("$BASE_URL/$safeCode.json").get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                if (body == "null" || body.isBlank()) return@withContext null
                gson.fromJson(body, FamilyDto::class.java)
            }
        } catch (e: Exception) {
            null
        }
    }

    suspend fun saveFamily(family: FamilyDto): Boolean = withContext(Dispatchers.IO) {
        try {
            val safeCode = family.code.replace("#", "CODE_")
            val json = gson.toJson(family)
            val request = Request.Builder()
                .url("$BASE_URL/$safeCode.json")
                .put(json.toRequestBody(jsonMediaType))
                .build()
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            false
        }
    }

    suspend fun updateMember(code: String, member: MemberDto) = withContext(Dispatchers.IO) {
        try {
            val safeCode = code.replace("#", "CODE_")
            val json = gson.toJson(member)
            val request = Request.Builder()
                .url("$BASE_URL/$safeCode/members/${member.id}.json")
                .put(json.toRequestBody(jsonMediaType))
                .build()
            client.newCall(request).execute().close()
        } catch (_: Exception) {}
    }

    suspend fun sendMessage(code: String, message: MessageDto): Boolean = withContext(Dispatchers.IO) {
        try {
            val safeCode = code.replace("#", "CODE_")
            val json = gson.toJson(message)
            val request = Request.Builder()
                .url("$BASE_URL/$safeCode/messages/${message.id}.json")
                .put(json.toRequestBody(jsonMediaType))
                .build()
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            false
        }
    }
}

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

    var activeTab by mutableStateOf(1)
    var isInCall by mutableStateOf(false)
    var isSyncing by mutableStateOf(false)

    fun initPrefs(context: Context) {
        val prefs = context.getSharedPreferences("ailem_prefs", Context.MODE_PRIVATE)
        val savedNick = prefs.getString("nick", "") ?: ""
        val savedCode = prefs.getString("code", "") ?: ""
        val savedId = prefs.getString("uid", "") ?: ""

        if (savedId.isNotBlank()) userId = savedId else prefs.edit().putString("uid", userId).apply()
        if (savedNick.isNotBlank()) currentUserNickname = savedNick
        if (savedCode.isNotBlank()) {
            currentFamilyCode = savedCode
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

    fun createFamily(context: Context, name: String, maxMembers: Int, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            isSyncing = true
            val code = generateFamilyCode()
            val me = MemberDto(userId, currentUserNickname, System.currentTimeMillis(), myLatitude, myLongitude, myBattery, isSosActive)
            val newFamily = FamilyDto(
                code = code,
                name = name,
                maxMembers = maxMembers,
                createdAt = System.currentTimeMillis(),
                members = mapOf(userId to me),
                messages = emptyMap()
            )
            val success = CloudSyncService.saveFamily(newFamily)
            isSyncing = false
            if (success) {
                currentFamilyCode = code
                currentFamilyName = name
                context.getSharedPreferences("ailem_prefs", Context.MODE_PRIVATE).edit().putString("code", code).apply()
                startLiveSync()
                onResult(code)
            } else {
                onResult(null)
            }
        }
    }

    fun joinFamily(context: Context, code: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            isSyncing = true
            val cleanCode = code.trim().uppercase()
            val family = CloudSyncService.getFamily(cleanCode)
            isSyncing = false
            if (family != null) {
                currentFamilyCode = family.code
                currentFamilyName = family.name
                context.getSharedPreferences("ailem_prefs", Context.MODE_PRIVATE).edit().putString("code", family.code).apply()
                val me = MemberDto(userId, currentUserNickname, System.currentTimeMillis(), myLatitude, myLongitude, myBattery, isSosActive)
                CloudSyncService.updateMember(family.code, me)
                startLiveSync()
                onResult(true)
            } else {
                onResult(false)
            }
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

        messagesList.add(newMsg)
        viewModelScope.launch {
            CloudSyncService.sendMessage(currentFamilyCode, newMsg)
        }
    }

    private fun startLiveSync() {
        viewModelScope.launch {
            while (isActive && currentFamilyCode.isNotBlank()) {
                val me = MemberDto(userId, currentUserNickname, System.currentTimeMillis(), myLatitude, myLongitude, myBattery, isSosActive)
                CloudSyncService.updateMember(currentFamilyCode, me)

                val family = CloudSyncService.getFamily(currentFamilyCode)
                if (family != null) {
                    currentFamilyName = family.name
                    val members = family.members?.values?.toList() ?: emptyList()
                    membersList.clear()
                    membersList.addAll(members.sortedByDescending { it.lastSeen })

                    val msgs = family.messages?.values?.toList() ?: emptyList()
                    val sortedMsgs = msgs.sortedBy { it.timestamp }
                    if (sortedMsgs.size != messagesList.size || sortedMsgs != messagesList.toList()) {
                        messagesList.clear()
                        messagesList.addAll(sortedMsgs)
                    }
                }
                delay(3000)
            }
        }
    }
}

class MainActivity : ComponentActivity(), LocationListener {
    private lateinit var locationManager: LocationManager
    private var vm: FamilyViewModel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF1E88E5))) {
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
                                isSyncing = viewModel.isSyncing,
                                onFamilyCreated = { name, max, cb -> viewModel.createFamily(context, name, max, cb) },
                                onFamilyJoined = { code, cb -> viewModel.joinFamily(context, code, cb) }
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

@Composable
fun NicknameScreen(onContinue: (String) -> Unit) {
    var name by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(90.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.height(16.dp))
        Text("Ailem Canlı Takip & Sohbet", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("Ailenizin sizi tanıyacağı bir isim girin", color = Color.Gray, textAlign = TextAlign.Center)
        Spacer(modifier = Modifier.height(24.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Adınız / Rumuzunuz") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = { if (name.isNotBlank()) onContinue(name.trim()) },
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("Giriş Yap", fontSize = 16.sp)
        }
    }
}

@Composable
fun FamilyChoiceScreen(
    isSyncing: Boolean,
    onFamilyCreated: (String, Int, (String?) -> Unit) -> Unit,
    onFamilyJoined: (String, (Boolean) -> Unit) -> Unit
) {
    var showCreateDialog by remember { mutableStateOf(false) }
    var showJoinDialog by remember { mutableStateOf(false) }
    var joinError by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (isSyncing) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(16.dp))
            Text("Buluta Bağlanılıyor...")
        } else {
            Text("Aile Grubu", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = { showCreateDialog = true },
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Yeni Aile Grubu Kur")
            }
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedButton(
                onClick = { showJoinDialog = true },
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Person, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Aile Kodunu Gir (#)")
            }
        }
    }

    if (showCreateDialog) {
        var familyName by remember { mutableStateOf("") }
        var memberLimit by remember { mutableStateOf("10") }
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("Yeni Aile Grubu") },
            text = {
                Column {
                    OutlinedTextField(value = familyName, onValueChange = { familyName = it }, label = { Text("Aile İsmi") }, singleLine = true)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(value = memberLimit, onValueChange = { memberLimit = it }, label = { Text("Kişi Sınırı") }, singleLine = true)
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (familyName.isNotBlank()) {
                        val limit = memberLimit.toIntOrNull() ?: 10
                        onFamilyCreated(familyName, limit) { showCreateDialog = false }
                    }
                }) { Text("Oluştur") }
            },
            dismissButton = { TextButton(onClick = { showCreateDialog = false }) { Text("İptal") } }
        )
    }

    if (showJoinDialog) {
        var codeInput by remember { mutableStateOf("#") }
        AlertDialog(
            onDismissRequest = { showJoinDialog = false },
            title = { Text("6 Haneli Aile Kodunu Girin") },
            text = {
                Column {
                    OutlinedTextField(
                        value = codeInput,
                        onValueChange = { if (it.length <= 7) codeInput = it.uppercase() },
                        label = { Text("Aile Kodu") },
                        placeholder = { Text("#A1B2C3") },
                        isError = joinError
                    )
                    if (joinError) {
                        Text("Bu kodla bir aile grubu bulunamadı!", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    onFamilyJoined(codeInput) { success ->
                        if (success) showJoinDialog = false else joinError = true
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
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(viewModel.currentFamilyName.ifBlank { "Ailem" }, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text("Kod: ${viewModel.currentFamilyCode}", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.triggerSos() }) {
                        Icon(Icons.Default.Warning, contentDescription = "SOS", tint = if (viewModel.isSosActive) Color.Red else Color.Gray)
                    }
                    if (viewModel.activeTab == 1) {
                        IconButton(onClick = { viewModel.isInCall = true }) {
                            Icon(Icons.Default.Phone, contentDescription = "Görüşme", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = viewModel.activeTab == 0, onClick = { viewModel.activeTab = 0 }, icon = { Icon(Icons.Default.Person, null) }, label = { Text("Aktifler") })
                NavigationBarItem(selected = viewModel.activeTab == 1, onClick = { viewModel.activeTab = 1 }, icon = { Icon(Icons.Default.Email, null) }, label = { Text("Sohbet") })
                NavigationBarItem(selected = viewModel.activeTab == 2, onClick = { viewModel.activeTab = 2 }, icon = { Icon(Icons.Default.LocationOn, null) }, label = { Text("Harita") })
                NavigationBarItem(selected = viewModel.activeTab == 3, onClick = { viewModel.activeTab = 3 }, icon = { Icon(Icons.Default.Info, null) }, label = { Text("Özet") })
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
            Text("Aile Üyeleri (${members.size} Kişi)", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(modifier = Modifier.height(12.dp))
        }
        items(members) { member ->
            val isOnline = (currentTime - member.lastSeen) < 25_000
            val timeAgoStr = if (isOnline) "Şu an Çevrimiçi" else {
                val diffMins = (currentTime - member.lastSeen) / (1000 * 60)
                if (diffMins < 60) "$diffMins dk önce" else "${diffMins / 60} saat önce"
            }

            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (member.isSos) Color(0xFFFFEBEE) else MaterialTheme.colorScheme.surfaceVariant
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .clip(CircleShape)
                            .background(if (member.isSos) Color.Red else if (isOnline) Color(0xFF4CAF50) else Color.LightGray)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(member.nickname, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                            if (member.isSos) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("🚨 SOS", color = Color.Red, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }
                        Text(timeAgoStr, fontSize = 12.sp, color = if (isOnline) Color(0xFF2E7D32) else Color.Gray)
                    }
                    Text("🔋 %${member.battery}", fontSize = 13.sp, fontWeight = FontWeight.Medium)
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

    Column(modifier = Modifier.fillMaxSize()) {
        if (messages.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("Henüz mesaj yok. İlk mesajı siz yazın! 👋", color = Color.Gray)
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
                                containerColor = if (msg.type == "SOS") Color(0xFFFFCDD2)
                                else if (isMe) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                if (!isMe) {
                                    Text(msg.senderNickname, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                }
                                when (msg.type) {
                                    "TEXT" -> Text(msg.text, fontSize = 15.sp)
                                    "SOS" -> Text(msg.text, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.Red)
                                    "AUDIO" -> Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.PlayArrow, null)
                                        Text("🎤 Sesli Mesaj", fontSize = 14.sp)
                                    }
                                    "FILE" -> Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Share, null)
                                        Text("📎 Belge / Dosya", fontSize = 14.sp)
                                    }
                                }
                                Text(timeStr, fontSize = 10.sp, color = Color.Gray, modifier = Modifier.align(Alignment.End))
                            }
                        }
                    }
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { viewModel.sendMessage("", "AUDIO") }) { Icon(Icons.Default.PlayArrow, null) }
            IconButton(onClick = { viewModel.sendMessage("", "FILE") }) { Icon(Icons.Default.Share, null) }
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Mesaj yazın...") },
                shape = RoundedCornerShape(24.dp),
                maxLines = 3
            )
            Spacer(modifier = Modifier.width(4.dp))
            IconButton(onClick = {
                if (inputText.isNotBlank()) {
                    viewModel.sendMessage(inputText.trim())
                    inputText = ""
                }
            }) {
                Icon(Icons.Default.Send, null, tint = MaterialTheme.colorScheme.primary)
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
        sb.append("<style>body{margin:0;padding:0;}#map{height:100vh;width:100vw;}</style></head><body>")
        sb.append("<div id='map'></div><script>")
        sb.append("var map = L.map('map').setView([" + viewModel.myLatitude + ", " + viewModel.myLongitude + "], 14);")
        sb.append("L.tileLayer('https://tile.openstreetmap.org/' + '{z}/{x}/{y}.png', { maxZoom: 19 }).addTo(map);")
        for (m in members) {
            sb.append("L.marker([" + m.latitude + ", " + m.longitude + "]).addTo(map).bindPopup('<b>" + m.nickname + "</b><br>Pil: %" + m.battery + "');")
        }
        sb.append("</script></body></html>")
        sb.toString()
    }

    Column(modifier = Modifier.fillMaxSize()) {
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
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
        ) {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                item {
                    Text("Aile Konumları & Navigasyon", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                }
                items(members) { m ->
                    val distKm = calculateDistanceInKm(viewModel.myLatitude, viewModel.myLongitude, m.latitude, m.longitude)
                    val distStr = if (m.id == viewModel.userId) "Siz (Buradasınız)" else String.format(Locale.US, "%.1f km uzakta", distKm)

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.LocationOn, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(m.nickname, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text(distStr, fontSize = 12.sp, color = Color.Gray)
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
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text("Google Harita", fontSize = 11.sp)
                        }
                    }
                    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color.LightGray.copy(alpha = 0.4f)))
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

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Gerçek Aile Güvenlik & Etkileşim Raporu", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(16.dp))

        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = RoundedCornerShape(12.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Son Mesaj Gönderen:", fontSize = 12.sp, color = Color.DarkGray)
                val timeFormat = SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault())
                val timeStr = if (lastMsg != null) timeFormat.format(Date(lastMsg.timestamp)) else "-"
                Text(text = "${lastMsg?.senderNickname ?: "Henüz mesaj yok"} ($timeStr)", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                if (lastMsg != null) {
                    Text(text = "\"${lastMsg.text.ifBlank { "[Medya/Ses]" }}\"", fontSize = 14.sp, color = Color.DarkGray)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Son 7 Günlük Güvenlik ve İstatistik", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(modifier = Modifier.height(8.dp))
                Text("• Bu Hafta Atılan Mesaj: ${lastWeekMessages.size} adet")
                Text("• Paylaşılan Medya: $mediaCount adet")
                Text("• Canlı GPS Takipteki Üyeler: ${viewModel.membersList.size} kişi")
                Text("• GPS ve Canlı Harita: Aktif & Senkronize 🟢")
            }
        }
    }
}

@Composable
fun CallOverlay(onEndCall: () -> Unit) {
    var isMicMuted by remember { mutableStateOf(false) }
    var isVideoOn by remember { mutableStateOf(true) }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xE6101010)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(imageVector = Icons.Default.Person, contentDescription = null, modifier = Modifier.size(100.dp), tint = Color.White)
            Spacer(modifier = Modifier.height(16.dp))
            Text("Aile Görüşmesi Sürüyor", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("Ses ve Görüntü Aktif", color = Color.LightGray)
            Spacer(modifier = Modifier.height(48.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                IconButton(onClick = { isMicMuted = !isMicMuted }, modifier = Modifier.background(if (isMicMuted) Color.Red else Color.DarkGray, CircleShape)) {
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