package app.gamenative.ui

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.gamenative.service.SteamService
import app.gamenative.utils.ContainerUtils
import app.gamenative.utils.SteamUtils
import kotlinx.coroutines.launch

/**
 * Single-game home screen. Never displays GameNative's general library.
 * Steam login remains the upstream login route. Imported games are copied
 * to private app storage; the Downloads source remains untouched.
 */
@Composable
internal fun DragonwildsHome(
    onLaunch: () -> Unit,
    onSignIn: () -> Unit,
    onAdvancedSettings: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var signedIn by remember { mutableStateOf(SteamService.isLoggedIn) }
    var installed by remember { mutableStateOf(SteamService.isAppInstalled(1374490)) }
    var checking by remember { mutableStateOf(true) }
    var importing by remember { mutableStateOf(false) }
    var copied by remember { mutableLongStateOf(0L) }
    var currentFile by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var launched by rememberSaveable { mutableStateOf(false) }
    var resolutionMenu by remember { mutableStateOf(false) }
    var resolution by remember {
        mutableStateOf(context.getSharedPreferences("dragonwilds_mobile_settings", Context.MODE_PRIVATE)
            .getString("resolution", "854x480") ?: "854x480")
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null && !importing) {
            importing = true
            error = null
            copied = 0L
            scope.launch {
                var reported = 0L
                val result = DragonwildsImport.copyAndRegister(context, uri) { bytes, name ->
                    if (bytes - reported >= 4L * 1024L * 1024L) {
                        reported = bytes
                        copied = bytes
                        currentFile = name
                    }
                }
                importing = false
                result.fold(
                    onSuccess = {
                        installed = true
                        if (!launched) {
                            launched = true
                            onLaunch()
                        }
                    },
                    onFailure = {
                        error = it.message ?: "Could not import the selected folder."
                    },
                )
            }
        }
    }

    LaunchedEffect(Unit) {
        signedIn = SteamService.isLoggedIn || SteamUtils.awaitSteamLogin()
        checking = false
        if (signedIn) {
            installed = SteamService.isAppInstalled(1374490)
            if (installed && !launched) {
                launched = true
                onLaunch()
            }
        }
    }

    fun applyResolution(value: String) {
        resolution = value
        context.getSharedPreferences("dragonwilds_mobile_settings", Context.MODE_PRIVATE)
            .edit().putString("resolution", value).apply()
        val defaults = ContainerUtils.getDefaultContainerData()
        ContainerUtils.setDefaultContainerData(defaults.copy(screenSize = value))
        runCatching {
            if (ContainerUtils.hasContainer(context, "STEAM_1374490")) {
                val container = ContainerUtils.getContainer(context, "STEAM_1374490")
                val updated = ContainerUtils.toContainerData(container).copy(screenSize = value)
                ContainerUtils.applyToContainer(context, container, updated)
            }
        }.onFailure { error = "Resolution saved. Restart the game to apply it." }
    }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("RuneScape: Dragonwilds", style = MaterialTheme.typography.headlineMedium)
            if (checking || importing) {
                CircularProgressIndicator()
                Text(if (importing) "Copying your game into Dragonwilds Mobile…" else "Connecting to Steam…")
                if (importing) {
                    Text("Copied: " + (copied / 1024L / 1024L) + " MB")
                    if (currentFile.isNotBlank()) Text(currentFile, style = MaterialTheme.typography.bodySmall)
                    Text("Keep the app open. Your Downloads backup will not be deleted.")
                }
            } else if (!signedIn) {
                Text("Steam sign-in is needed to play online.")
                Button(onClick = onSignIn, modifier = Modifier.fillMaxWidth()) {
                    Text("Sign in to Steam")
                }
            } else if (!installed) {
                Text("Steam connected. Choose the Dragonwilds backup folder once.")
                Text("Select the folder containing Engine and RSDragonwilds, not a folder inside either one.")
                Button(onClick = { picker.launch(null) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Locate Dragonwilds game files")
                }
            } else {
                Text("Game files found. Steam is connected.")
                Button(onClick = { if (!launched) { launched = true; onLaunch() } else onLaunch() },
                    modifier = Modifier.fillMaxWidth()) {
                    Text("Play Dragonwilds")
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            Text("Display resolution", style = MaterialTheme.typography.titleSmall)
            OutlinedButton(onClick = { resolutionMenu = true }) {
                Text(resolution.replace("x", " × "))
            }
            DropdownMenu(expanded = resolutionMenu, onDismissRequest = { resolutionMenu = false }) {
                listOf("854x480", "960x540", "1280x720", "1600x900", "1920x1080").forEach { size ->
                    DropdownMenuItem(
                        text = { Text(size.replace("x", " × ")) },
                        onClick = { resolutionMenu = false; applyResolution(size) },
                    )
                }
            }
            OutlinedButton(onClick = onAdvancedSettings) {
                Text("Advanced graphics and touch controls")
            }
        }
    }
}
