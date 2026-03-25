package com.bartokplayer

import android.Manifest
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothAudio
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.text.SimpleDateFormat
import java.util.Locale

class MainActivity : ComponentActivity() {

    private var mediaController: MediaController? = null
    private var pendingBtAction: (() -> Unit)? = null
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    private val bluetoothPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) pendingBtAction?.invoke()
            pendingBtAction = null
        }

    private val prefs by lazy { getSharedPreferences("bartok_prefs", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        val streamUrls = loadStreamUrls()

        setContent {
            BartokPlayerTheme {
                var isPlaying by remember { mutableStateOf(false) }
                var isBuffering by remember { mutableStateOf(false) }
                var currentProgram by remember { mutableStateOf<ProgramInfo?>(null) }
                var nextProgram by remember { mutableStateOf<ProgramInfo?>(null) }
                var btConnected by remember { mutableStateOf(false) }
                var btConnecting by remember { mutableStateOf(false) }
                var sleepTimerSeconds by remember { mutableIntStateOf(0) }
                val fadeDurationSeconds = 180 // 3 minutes

                // Persisted state
                var btDeviceName by remember { mutableStateOf(prefs.getString("bt_device_name", null)) }
                var btDeviceAddress by remember { mutableStateOf(prefs.getString("bt_device_address", null)) }
                var sleepTimerExpanded by remember { mutableStateOf(prefs.getBoolean("sleep_timer_visible", true)) }
                var autoConnectBt by remember { mutableStateOf(prefs.getBoolean("auto_connect_bt", false)) }
                var autoStartPlay by remember { mutableStateOf(prefs.getBoolean("auto_start_play", false)) }
                var showBtPicker by remember { mutableStateOf(false) }

                // Monitor Bluetooth connection state
                DisposableEffect(btDeviceAddress) {
                    val savedAddress = btDeviceAddress
                    if (savedAddress == null) {
                        btConnected = false
                        btConnecting = false
                        return@DisposableEffect onDispose {}
                    }

                    // Check initial A2DP connection state
                    checkA2dpConnectionState(savedAddress) { connected ->
                        btConnected = connected
                    }

                    val receiver = object : BroadcastReceiver() {
                        override fun onReceive(context: Context, intent: Intent) {
                            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                            } else {
                                @Suppress("DEPRECATION")
                                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                            }
                            if (device?.address != savedAddress) return
                            when (intent.action) {
                                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                                    btConnected = true
                                    btConnecting = false
                                }
                                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                                    btConnected = false
                                    btConnecting = false
                                }
                            }
                        }
                    }
                    val filter = IntentFilter().apply {
                        addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                        addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                    }
                    registerReceiver(receiver, filter)

                    onDispose { unregisterReceiver(receiver) }
                }

                // Sleep timer countdown with volume fade
                LaunchedEffect(sleepTimerSeconds) {
                    if (sleepTimerSeconds <= 0) return@LaunchedEffect
                    var remaining = sleepTimerSeconds
                    while (remaining > 0 && isActive) {
                        delay(1000L)
                        remaining--
                        sleepTimerSeconds = remaining
                        // Fade volume in the last 3 minutes
                        if (remaining < fadeDurationSeconds) {
                            val volume = remaining.toFloat() / fadeDurationSeconds
                            mediaController?.volume = volume
                        }
                    }
                    if (remaining == 0) {
                        mediaController?.stop()
                        mediaController?.playWhenReady = false
                        mediaController?.volume = 1f
                        isPlaying = false
                        isBuffering = false
                    }
                }

                // Fetch schedule periodically
                LaunchedEffect(Unit) {
                    while (isActive) {
                        val schedule = ProgramSchedule.fetch()
                        currentProgram = schedule.current
                        nextProgram = schedule.next
                        delay(5 * 60 * 1000L)
                    }
                }

                // Connect to playback service
                DisposableEffect(Unit) {
                    val token = SessionToken(
                        this@MainActivity,
                        ComponentName(this@MainActivity, PlaybackService::class.java)
                    )
                    val future = MediaController.Builder(this@MainActivity, token).buildAsync()
                    future.addListener({
                        try {
                            val controller = future.get()
                            mediaController = controller
                            isPlaying = controller.isPlaying
                            isBuffering = controller.playbackState == Player.STATE_BUFFERING
                            controller.addListener(object : Player.Listener {
                                override fun onIsPlayingChanged(playing: Boolean) {
                                    isPlaying = playing
                                }

                                override fun onPlaybackStateChanged(state: Int) {
                                    isBuffering = state == Player.STATE_BUFFERING
                                }
                            })
                        } catch (_: Exception) {}
                    }, MoreExecutors.directExecutor())

                    onDispose {
                        mediaController?.release()
                        mediaController = null
                    }
                }

                // Auto-connect BT and auto-start on launch
                LaunchedEffect(Unit) {
                    // Wait for MediaController to be ready
                    while (mediaController == null) delay(100L)

                    if (autoConnectBt) {
                        val address = btDeviceAddress
                        if (address != null && !btConnected) {
                            val hasPerm = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                            if (hasPerm) {
                                btConnecting = true
                                connectBluetoothDevice(address)
                            }
                        }
                    }

                    if (autoStartPlay) {
                        val controller = mediaController
                        if (controller != null && !controller.isPlaying && controller.playbackState != Player.STATE_BUFFERING) {
                            val items = streamUrls.map { url ->
                                MediaItem.Builder()
                                    .setUri(url)
                                    .setMediaMetadata(
                                        MediaMetadata.Builder()
                                            .setTitle("Bartók Rádió")
                                            .setArtist("Magyar Rádió")
                                            .build()
                                    )
                                    .build()
                            }
                            controller.setMediaItems(items)
                            controller.prepare()
                            controller.play()
                        }
                    }
                }

                // Bluetooth device picker dialog
                if (showBtPicker) {
                    BluetoothDevicePickerDialog(
                        onDeviceSelected = { name, address ->
                            btDeviceName = name
                            btDeviceAddress = address
                            prefs.edit()
                                .putString("bt_device_name", name)
                                .putString("bt_device_address", address)
                                .apply()
                            showBtPicker = false
                        },
                        onDismiss = { showBtPicker = false },
                        getPairedDevices = { getPairedBluetoothDevices() }
                    )
                }

                BartokPlayerScreen(
                    isPlaying = isPlaying,
                    isBuffering = isBuffering,
                    currentProgram = currentProgram,
                    nextProgram = nextProgram,
                    btConnected = btConnected,
                    btConnecting = btConnecting,
                    sleepTimerSeconds = sleepTimerSeconds,
                    sleepTimerExpanded = sleepTimerExpanded,
                    btDeviceName = btDeviceName,
                    autoConnectBt = autoConnectBt,
                    autoStartPlay = autoStartPlay,
                    onAutoConnectBtToggle = { enabled ->
                        autoConnectBt = enabled
                        prefs.edit().putBoolean("auto_connect_bt", enabled).apply()
                    },
                    onAutoStartPlayToggle = { enabled ->
                        autoStartPlay = enabled
                        prefs.edit().putBoolean("auto_start_play", enabled).apply()
                    },
                    onSleepTimerToggle = {
                        sleepTimerExpanded = !sleepTimerExpanded
                        prefs.edit().putBoolean("sleep_timer_visible", sleepTimerExpanded).apply()
                    },
                    onSleepTimer = { minutes ->
                        val controller = mediaController ?: return@BartokPlayerScreen
                        if (minutes == 0) {
                            sleepTimerSeconds = 0
                            controller.volume = 1f
                        } else {
                            sleepTimerSeconds = minutes * 60
                            // Start playback if not already playing
                            if (!controller.isPlaying && controller.playbackState != Player.STATE_BUFFERING) {
                                val items = streamUrls.map { url ->
                                    MediaItem.Builder()
                                        .setUri(url)
                                        .setMediaMetadata(
                                            MediaMetadata.Builder()
                                                .setTitle("Bartók Rádió")
                                                .setArtist("Magyar Rádió")
                                                .build()
                                        )
                                        .build()
                                }
                                controller.setMediaItems(items)
                                controller.prepare()
                                controller.play()
                            }
                        }
                    },
                    onConnectBt = {
                        val address = btDeviceAddress
                        if (address == null) {
                            // No device configured — open picker
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT)
                                    != PackageManager.PERMISSION_GRANTED
                                ) {
                                    pendingBtAction = { showBtPicker = true }
                                    bluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                                    return@BartokPlayerScreen
                                }
                            }
                            showBtPicker = true
                        } else {
                            // Device configured — connect
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT)
                                    != PackageManager.PERMISSION_GRANTED
                                ) {
                                    pendingBtAction = {
                                        btConnecting = true
                                        connectBluetoothDevice(address)
                                    }
                                    bluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                                    return@BartokPlayerScreen
                                }
                            }
                            btConnecting = true
                            connectBluetoothDevice(address)
                        }
                    },
                    onConfigBt = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT)
                                != PackageManager.PERMISSION_GRANTED
                            ) {
                                pendingBtAction = { showBtPicker = true }
                                bluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                                return@BartokPlayerScreen
                            }
                        }
                        showBtPicker = true
                    },
                    onPlayStop = {
                        val controller = mediaController ?: return@BartokPlayerScreen
                        if (isPlaying || isBuffering) {
                            controller.stop()
                            controller.playWhenReady = false
                            isPlaying = false
                            isBuffering = false
                        } else {
                            val items = streamUrls.map { url ->
                                MediaItem.Builder()
                                    .setUri(url)
                                    .setMediaMetadata(
                                        MediaMetadata.Builder()
                                            .setTitle("Bartók Rádió")
                                            .setArtist("Magyar Rádió")
                                            .build()
                                    )
                                    .build()
                            }
                            controller.setMediaItems(items)
                            controller.prepare()
                            controller.play()
                        }
                    }
                )
            }
        }
    }

    private fun loadStreamUrls(): List<String> {
        return try {
            assets.open("streams.txt").bufferedReader().useLines { lines ->
                lines.filter { it.isNotBlank() && !it.trimStart().startsWith('#') }.toList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Suppress("MissingPermission")
    private fun connectBluetoothDevice(address: String) {
        val bluetoothManager = getSystemService(BluetoothManager::class.java)
        val adapter = bluetoothManager?.adapter ?: run {
            Toast.makeText(this, "Bluetooth not available", Toast.LENGTH_SHORT).show()
            return
        }

        if (!adapter.isEnabled) {
            Toast.makeText(this, "Bluetooth is off", Toast.LENGTH_SHORT).show()
            return
        }

        val device = try {
            adapter.getRemoteDevice(address)
        } catch (_: Exception) {
            Toast.makeText(this, "Device not found", Toast.LENGTH_SHORT).show()
            return
        }

        adapter.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                val a2dp = proxy as BluetoothA2dp
                if (a2dp.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED) {
                    // Already connected — broadcast receiver will handle state
                    adapter.closeProfileProxy(BluetoothProfile.A2DP, proxy)
                    return
                }
                try {
                    val connectMethod = BluetoothA2dp::class.java.getMethod("connect", BluetoothDevice::class.java)
                    connectMethod.invoke(a2dp, device)
                } catch (_: Exception) {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Failed to connect", Toast.LENGTH_SHORT).show()
                    }
                }
                adapter.closeProfileProxy(BluetoothProfile.A2DP, proxy)
            }

            override fun onServiceDisconnected(profile: Int) {}
        }, BluetoothProfile.A2DP)
    }

    @Suppress("MissingPermission")
    private fun checkA2dpConnectionState(address: String, callback: (Boolean) -> Unit) {
        val bluetoothManager = getSystemService(BluetoothManager::class.java)
        val adapter = bluetoothManager?.adapter ?: return
        if (!adapter.isEnabled) return
        val device = try { adapter.getRemoteDevice(address) } catch (_: Exception) { return }

        adapter.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                val connected = proxy.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED
                runOnUiThread { callback(connected) }
                adapter.closeProfileProxy(BluetoothProfile.A2DP, proxy)
            }
            override fun onServiceDisconnected(profile: Int) {}
        }, BluetoothProfile.A2DP)
    }

    @Suppress("MissingPermission")
    private fun getPairedBluetoothDevices(): List<Pair<String, String>> {
        val bluetoothManager = getSystemService(BluetoothManager::class.java)
        val adapter = bluetoothManager?.adapter ?: return emptyList()
        if (!adapter.isEnabled) return emptyList()
        return adapter.bondedDevices
            ?.mapNotNull { device ->
                val name = device.name ?: return@mapNotNull null
                name to device.address
            }
            ?: emptyList()
    }
}

// --- Theme ---

private val Cyan = Color(0xFF00BCD4)
private val DarkBackground = Color(0xFF121212)
private val CardBackground = Color(0xFF1E1E1E)
private val DimText = Color(0xFF888888)
private val GreenAccent = Color(0xFF4CAF50)
private val YellowAccent = Color(0xFFFFCA28)

private val DarkColorScheme = darkColorScheme(
    primary = Cyan,
    onPrimary = Color.Black,
    surface = DarkBackground,
    background = DarkBackground,
    onSurface = Color.White,
    onBackground = Color.White,
    surfaceVariant = CardBackground
)

@Composable
fun BartokPlayerTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColorScheme, content = content)
}

// --- UI ---

@Composable
fun BartokPlayerScreen(
    isPlaying: Boolean,
    isBuffering: Boolean,
    currentProgram: ProgramInfo?,
    nextProgram: ProgramInfo?,
    btConnected: Boolean,
    btConnecting: Boolean,
    sleepTimerSeconds: Int,
    sleepTimerExpanded: Boolean,
    btDeviceName: String?,
    autoConnectBt: Boolean,
    autoStartPlay: Boolean,
    onAutoConnectBtToggle: (Boolean) -> Unit,
    onAutoStartPlayToggle: (Boolean) -> Unit,
    onSleepTimerToggle: () -> Unit,
    onSleepTimer: (Int) -> Unit,
    onConnectBt: () -> Unit,
    onConfigBt: () -> Unit,
    onPlayStop: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(48.dp))

            // Title
            Text(
                text = "Bartók Rádió",
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = Cyan
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Hungarian Classical Music",
                fontSize = 14.sp,
                color = DimText
            )

            Spacer(Modifier.height(40.dp))

            // Play/Stop button
            FilledIconButton(
                onClick = onPlayStop,
                modifier = Modifier.size(80.dp),
                shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = if (isPlaying) Color(0xFF333333) else Cyan,
                    contentColor = if (isPlaying) Cyan else Color.Black
                )
            ) {
                Icon(
                    imageVector = if (isPlaying || isBuffering) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "Stop" else "Play",
                    modifier = Modifier.size(40.dp)
                )
            }

            Spacer(Modifier.height(16.dp))

            // Status
            Text(
                text = when {
                    isBuffering -> "Buffering..."
                    isPlaying -> "Playing"
                    else -> "Ready"
                },
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = when {
                    isBuffering -> YellowAccent
                    isPlaying -> GreenAccent
                    else -> DimText
                }
            )

            Spacer(Modifier.height(24.dp))

            // Bluetooth connect button
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                val btButtonColor = when {
                    btConnected -> GreenAccent
                    btConnecting -> YellowAccent
                    else -> Cyan
                }
                Button(
                    onClick = onConnectBt,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CardBackground,
                        contentColor = btButtonColor
                    )
                ) {
                    Icon(
                        imageVector = if (btDeviceName != null) Icons.Filled.BluetoothAudio else Icons.Filled.Bluetooth,
                        contentDescription = "Connect Bluetooth",
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(btDeviceName ?: "Bluetooth", fontSize = 14.sp)
                }
                if (btDeviceName != null) {
                    Spacer(Modifier.width(4.dp))
                    IconButton(onClick = onConfigBt, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = "Change Bluetooth device",
                            tint = DimText,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            if (btDeviceName != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = when {
                        btConnected -> "Connected"
                        btConnecting -> "Connecting..."
                        else -> "Not connected"
                    },
                    fontSize = 12.sp,
                    color = when {
                        btConnected -> GreenAccent
                        btConnecting -> YellowAccent
                        else -> DimText
                    }
                )
            }

            Spacer(Modifier.height(16.dp))

            // Auto options
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Auto-play", fontSize = 12.sp, color = DimText)
                    Spacer(Modifier.width(6.dp))
                    Switch(
                        checked = autoStartPlay,
                        onCheckedChange = onAutoStartPlayToggle,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Cyan,
                            checkedTrackColor = Cyan.copy(alpha = 0.3f),
                            uncheckedThumbColor = DimText,
                            uncheckedTrackColor = CardBackground
                        )
                    )
                }
                if (btDeviceName != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Auto-connect", fontSize = 12.sp, color = DimText)
                        Spacer(Modifier.width(6.dp))
                        Switch(
                            checked = autoConnectBt,
                            onCheckedChange = onAutoConnectBtToggle,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Cyan,
                                checkedTrackColor = Cyan.copy(alpha = 0.3f),
                                uncheckedThumbColor = DimText,
                                uncheckedTrackColor = CardBackground
                            )
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Sleep timer
            if (sleepTimerSeconds > 0) {
                // Active timer display — always visible regardless of expanded state
                val mins = sleepTimerSeconds / 60
                val secs = sleepTimerSeconds % 60
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Bedtime,
                        contentDescription = null,
                        tint = YellowAccent,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = String.format(Locale.US, "%d:%02d", mins, secs),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium,
                        color = YellowAccent
                    )
                    Spacer(Modifier.width(12.dp))
                    FilledIconButton(
                        onClick = { onSleepTimer(0) },
                        modifier = Modifier.size(28.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = Color(0xFF333333),
                            contentColor = DimText
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Cancel timer",
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            } else if (sleepTimerExpanded) {
                // Timer options — expanded
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.Bedtime,
                        contentDescription = "Collapse sleep timer",
                        tint = DimText,
                        modifier = Modifier
                            .size(16.dp)
                            .clickable { onSleepTimerToggle() }
                    )
                    listOf(5, 10, 15, 20, 30).forEach { mins ->
                        FilterChip(
                            selected = false,
                            onClick = { onSleepTimer(mins) },
                            label = { Text("${mins}m", fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = CardBackground,
                                labelColor = DimText
                            ),
                            border = null
                        )
                    }
                }
            } else {
                // Collapsed — just the icon
                Icon(
                    imageVector = Icons.Filled.Bedtime,
                    contentDescription = "Expand sleep timer",
                    tint = DimText,
                    modifier = Modifier
                        .size(20.dp)
                        .clickable { onSleepTimerToggle() }
                )
            }

            Spacer(Modifier.height(32.dp))

            // Current program
            if (currentProgram != null) {
                ProgramCard(label = "Now", program = currentProgram, accentColor = YellowAccent)
            }

            if (nextProgram != null) {
                Spacer(Modifier.height(12.dp))
                ProgramCard(label = "Next", program = nextProgram, accentColor = DimText)
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
fun BluetoothDevicePickerDialog(
    onDeviceSelected: (name: String, address: String) -> Unit,
    onDismiss: () -> Unit,
    getPairedDevices: () -> List<Pair<String, String>>
) {
    val devices = remember { getPairedDevices() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Bluetooth Device") },
        text = {
            if (devices.isEmpty()) {
                Text("No paired devices found.\nPair a device in Android Settings first.", color = DimText)
            } else {
                LazyColumn {
                    items(devices) { (name, address) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onDeviceSelected(name, address) }
                                .padding(vertical = 12.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.BluetoothAudio,
                                contentDescription = null,
                                tint = Cyan,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(name, fontSize = 15.sp, color = Color.White)
                                Text(address, fontSize = 11.sp, color = DimText)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
        containerColor = CardBackground
    )
}

@Composable
fun ProgramCard(label: String, program: ProgramInfo, accentColor: Color) {
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardBackground)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = label,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = accentColor
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "${timeFormat.format(program.start)} – ${timeFormat.format(program.end)}",
                    fontSize = 12.sp,
                    color = DimText
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = program.title,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
            if (program.description.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                // Format numbered items onto new lines (same as Windows version)
                val formatted = program.description
                    .replace(Regex(",\\s+(\\d+\\.\\s+\\S)"), "\n$1")
                Text(
                    text = formatted,
                    fontSize = 13.sp,
                    color = DimText,
                    lineHeight = 18.sp
                )
            }
        }
    }
}
