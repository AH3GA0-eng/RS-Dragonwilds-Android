package app.gamenative.ui

import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Dragonwilds-only launcher. Steam may finish logging in *after* this screen
 * appears; never treat a one-time Steam read as permanent authentication state.
 * The folder URI is persisted before import so reconnects do not lose it.
 */
@Composable
internal fun DragonwildsHome(
    onLaunch: () -> Unit,
    onSignIn: () -> Unit,
    onAdvancedSettings: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember(context) {
        context.getSharedPreferences("dragonwilds_mobile_settings", Context.MODE_PRIVATE)
    }
    val scope = rememberCoroutineScope()

    var signedIn by remember { mutableStateOf(SteamService.isLoggedIn) }
    var installed by remember { mutableStateOf(false) }
    var checked by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var importAttempted by remember { mutableStateOf(false) }
    var selectedFolder by remember { mutableStateOf(prefs.getString("game_tree_uri", null)) }
    var copied by remember { mutableLongStateOf(0L) }
    var currentFile by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var autoLaunched by rememberSaveable { mutableStateOf(false) }

    val holdAutomaticLaunch = remember(context) {
        DragonwildsDiagnostics.shouldHoldAutoLaunch(context)
    }
    val previousLaunchReport = remember(context) {
        if (holdAutomaticLaunch) DragonwildsDiagnostics.report(context) else null
    }

    fun startDragonwilds() {
        DragonwildsDiagnostics.recordLaunch(context)
        onLaunch()
    }
    var resolutionMenu by remember { mutableStateOf(false) }
    var resolution by remember {
        mutableStateOf(prefs.getString("resolution", "854x480") ?: "854x480")
    }

    fun importSelectedFolder() {
        val uri = selectedFolder ?: return
        if (!signedIn || installed || importing || importAttempted) return
        importing = true
        importAttempted = true
        error = null
        copied = 0L
        currentFile = ""
        scope.launch {
            var lastReport = 0L
            val result = DragonwildsImport.copyAndRegister(context, Uri.parse(uri)) { bytes, name ->
                if (bytes - lastReport >= 4L * 1024L * 1024L) {
                    lastReport = bytes
                    // Copying runs on Dispatchers.IO; marshal Compose state to main.
                    scope.launch {
                        copied = bytes
                        currentFile = name
                    }
                }
            }
            importing = false
            result.fold(
                onSuccess = {
                    installed = true
                    error = null
                },
                onFailure = {
                    error = it.message ?: "The game folder could not be imported."
                },
            )
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null && !importing) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
                // Save this *before* beginning Steam-dependent registration.
                prefs.edit().putString("game_tree_uri", uri.toString()).apply()
                selectedFolder = uri.toString()
                importAttempted = false
                error = null
            } catch (e: Exception) {
                error = "Android did not grant access to that folder. Try selecting it again."
            }
        }
    }

    // Poll the actual Steam state while Home is displayed. The prior version
    // awaited one event once and could permanently show Sign in after login.
    LaunchedEffect(Unit) {
        while (true) {
            signedIn = SteamService.isLoggedIn
            if (!importing) {
                installed = withContext(Dispatchers.IO) {
                    runCatching { SteamService.isAppInstalled(1374490) }.getOrDefault(false)
                }
            }
            checked = true
            delay(1400)
        }
    }

    // If Steam was reconnecting when the folder was selected, import once it
    // becomes authenticated without asking the user to select the folder again.
    LaunchedEffect(selectedFolder, signedIn, installed) {
        if (selectedFolder != null && signedIn && !installed) importSelectedFolder()
    }

    // This route is never the generic GameNative library. Enter the real Steam
    // preLaunchApp pipeline only after a registered installation is available.
    LaunchedEffect(signedIn, installed, importing) {
        if (signedIn && installed && !importing && !autoLaunched && !holdAutomaticLaunch) {
            autoLaunched = true
            startDragonwilds()
        }
    }

    fun applyResolution(value: String) {
        resolution = value
        prefs.edit().putString("resolution", value).apply()
        val defaults = ContainerUtils.getDefaultContainerData()
        ContainerUtils.setDefaultContainerData(defaults.copy(screenSize = value))
        runCatching {
            if (ContainerUtils.hasContainer(context, "STEAM_1374490")) {
                val container = ContainerUtils.getContainer(context, "STEAM_1374490")
                val configured = ContainerUtils.toContainerData(container).copy(screenSize = value)
                ContainerUtils.applyToContainer(context, container, configured)
            }
        }.onFailure { error = "Resolution saved. Restart Dragonwilds to apply it." }
    }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(15.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("RuneScape: Dragonwilds", style = MaterialTheme.typography.headlineMedium)

            if (previousLaunchReport != null) {
                Text("Previous session returned unexpectedly", color = MaterialTheme.colorScheme.error)
                Text("Automatic retry is paused. Your imported game files are preserved.",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Dragonwilds launch status", previousLaunchReport))
                        Toast.makeText(context, "Launch status copied", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Copy last launch status")
                }
            }

            if (!checked) {
                CircularProgressIndicator()
                Text("Checking Steam and game files…")
            } else {
                if (signedIn) {
                    Text("Steam connected")
                } else {
                    Text(
                        if (SteamUtils.hasStoredCredentials()) "Reconnecting to Steam…"
                        else "Steam sign-in required",
                    )
                    Button(onClick = onSignIn, modifier = Modifier.fillMaxWidth()) {
                        Text("Sign in / reconnect to Steam")
                    }
                }

                if (importing) {
                    CircularProgressIndicator()
                    Text("Importing Dragonwilds game files…")
                    Text("Copied: " + (copied / 1024L / 1024L) + " MB")
                    if (currentFile.isNotBlank()) {
                        Text(currentFile, style = MaterialTheme.typography.bodySmall)
                    }
                    Text("Keep this app open. Your backup in Downloads is untouched.")
                } else if (!installed) {
                    if (selectedFolder.isNullOrBlank()) {
                        Text("Select the folder containing Engine and RSDragonwilds.")
                        Button(onClick = { picker.launch(null) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Locate Dragonwilds game files")
                        }
                    } else {
                        Text(
                            if (signedIn && error == null) "Game folder selected. Preparing import…"
                            else "Game folder selected. Waiting for Steam registration.",
                        )
                        if (signedIn && importAttempted && error != null) {
                            Button(
                                onClick = {
                                    importAttempted = false
                                    importSelectedFolder()
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Retry game-file import")
                            }
                        }
                        OutlinedButton(
                            onClick = { picker.launch(null) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Choose a different folder")
                        }
                    }
                } else {
                    Text("Dragonwilds game files are registered.")
                }
            }

            if (error != null) {
                Text(error ?: "", color = MaterialTheme.colorScheme.error)
            }

            Button(
                onClick = {
                    if (signedIn) startDragonwilds() else onSignIn()
                },
                enabled = installed && !importing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Play Dragonwilds")
            }
            if (!installed) {
                Text("Play is available after the game folder is imported.",
                    style = MaterialTheme.typography.bodySmall)
            }

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
