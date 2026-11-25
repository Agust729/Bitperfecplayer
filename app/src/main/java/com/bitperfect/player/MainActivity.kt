package com.bitperfect.player

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.bitperfect.player.service.PlaybackService
import com.bitperfect.player.ui.PlayerViewModel
import com.bitperfect.player.ui.screens.DacSettingsScreen
import com.bitperfect.player.ui.screens.LibraryScreen
import com.bitperfect.player.ui.screens.NowPlayingScreen
import com.bitperfect.player.ui.theme.BitPerfectGold
import com.bitperfect.player.ui.theme.BitPerfectTheme
import com.bitperfect.player.ui.theme.ObsidianBg
import com.bitperfect.player.ui.theme.SlateElevated
import com.bitperfect.player.ui.theme.SlateSurface
import com.bitperfect.player.ui.theme.TextSecondary

class MainActivity : ComponentActivity() {

    private val viewModel: PlayerViewModel by viewModels()
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? PlaybackService.LocalBinder ?: return
            viewModel.attachEngine(binder.getEngine())
            serviceBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val serviceIntent = Intent(this, PlaybackService::class.java)
        startService(serviceIntent)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        setContent {
            BitPerfectTheme {
                BitPerfectApp(viewModel = viewModel)
            }
        }
    }

    override fun onDestroy() {
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        super.onDestroy()
    }
}

@Composable
fun BitPerfectApp(viewModel: PlayerViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val audioPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    var hasAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, audioPermission) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        hasAudioPermission = results[audioPermission] == true
        if (hasAudioPermission) {
            viewModel.scanLibrary()
        }
    }

    LaunchedEffect(Unit) {
        if (hasAudioPermission) {
            viewModel.scanLibrary()
        } else {
            val toRequest = buildList {
                add(audioPermission)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    add(Manifest.permission.POST_NOTIFICATIONS)
                }
            }.toTypedArray()
            permissionLauncher.launch(toRequest)
        }
    }

    val tracks by viewModel.library.collectAsState()
    val isLoading by viewModel.isLoadingLibrary.collectAsState()
    val currentTrack by viewModel.currentTrack.collectAsState()
    val artwork by viewModel.currentArtwork.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val positionMs by viewModel.positionMs.collectAsState()
    val signalPath by viewModel.signalPath.collectAsState()
    val outputMode by viewModel.outputMode.collectAsState()
    val connectedDacs by viewModel.connectedDacs.collectAsState()

    var selectedTab by remember { mutableIntStateOf(0) }

    Scaffold(
        containerColor = ObsidianBg,
        bottomBar = {
            Column {
                if (currentTrack != null && selectedTab != 1) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(SlateElevated)
                            .clickable { selectedTab = 1 }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = currentTrack!!.title,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${signalPath.status.badgeText} • ${currentTrack!!.formatBadge}",
                                style = MaterialTheme.typography.labelSmall,
                                color = BitPerfectGold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        IconButton(onClick = { viewModel.togglePlayPause() }) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = null,
                                tint = BitPerfectGold
                            )
                        }
                    }
                }
                NavigationBar(containerColor = SlateSurface) {
                    NavigationBarItem(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        icon = { Icon(Icons.Default.LibraryMusic, contentDescription = null) },
                        label = { Text("Biblioteca") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        icon = { Icon(Icons.Default.PlayCircle, contentDescription = null) },
                        label = { Text("Reproductor") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        icon = { Icon(Icons.Default.Usb, contentDescription = null) },
                        label = { Text("DAC / Motor") }
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (!hasAudioPermission && selectedTab == 0) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "Se necesita permiso de audio para leer tu música local.",
                        color = TextSecondary
                    )
                    Spacer(Modifier.size(16.dp))
                    Button(onClick = { permissionLauncher.launch(arrayOf(audioPermission)) }) {
                        Text("Conceder Permiso")
                    }
                }
            } else {
                when (selectedTab) {
                    0 -> LibraryScreen(
                        tracks = tracks,
                        currentTrackId = currentTrack?.id,
                        isLoading = isLoading,
                        onTrackClick = { track ->
                            viewModel.playTrack(track)
                            selectedTab = 1
                        },
                        onRefresh = { viewModel.scanLibrary() }
                    )
                    1 -> NowPlayingScreen(
                        track = currentTrack,
                        artwork = artwork,
                        isPlaying = isPlaying,
                        positionMs = positionMs,
                        signalPath = signalPath,
                        onTogglePlayPause = { viewModel.togglePlayPause() },
                        onNext = { viewModel.nextTrack() },
                        onPrev = { viewModel.previousTrack() },
                        onSeek = { ms -> viewModel.seekTo(ms) }
                    )
                    2 -> DacSettingsScreen(
                        outputMode = outputMode,
                        connectedDacs = connectedDacs,
                        onSelectMode = { viewModel.setOutputMode(it) },
                        onRequestUsbPermission = { viewModel.requestUsbPermission(it) },
                        onRefreshUsb = { viewModel.refreshUsbDacs() }
                    )
                }
            }
        }
    }
}
