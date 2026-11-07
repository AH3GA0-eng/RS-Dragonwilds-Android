package app.gamenative

import android.content.Context
import android.content.Intent
import app.gamenative.service.SteamService
import app.gamenative.utils.IntentLaunchManager
import app.gamenative.utils.ContainerUtils
import com.winlator.box86_64.Box86_64Preset
import com.winlator.container.Container
import com.winlator.core.envvars.EnvVars
import com.winlator.core.KeyValueSet
import com.winlator.fexcore.FEXCorePreset
import java.io.File

/**
 * Applies the supplied phone's working defaults to NEW game containers.
 * DOES NOT bypass Steam, fetch private data from GameNative, or modify saves.
 * Keep this separate from installed-game configuration to avoid surprise changes.
 */
object DragonwildsBootstrap {
    const val GAME_ID: String = "STEAM_1374490"

    private fun rewriteLegacyEdrive(drives: String, context: Context): String {
        val target = context.applicationInfo.dataDir + "/storage"
        return drives.replace("/data/data/app.gamenative/storage", target)
            .replace("/data/user/0/app.gamenative/storage", target)
    }

    fun installFirstRunDefaults(context: Context) {
        val prefs = context.getSharedPreferences("dragonwilds_mobile_defaults", Context.MODE_PRIVATE)
        if (prefs.getBoolean("defaults_v3_applied", false)) {
            migrateTouchControls(context, prefs)
            return
        }

        val original = ContainerUtils.getDefaultContainerData()
        val env = EnvVars(original.envVars)
        env.put("WRAPPER_MAX_IMAGE_COUNT", "0")
        env.put("ZINK_DESCRIPTORS", "lazy")
        env.put("ZINK_DEBUG", "compact")
        env.put("MESA_SHADER_CACHE_DISABLE", "false")
        env.put("MESA_SHADER_CACHE_MAX_SIZE", "512MB")
        env.put("mesa_glthread", "true")
        env.put("WINEESYNC", "1")
        env.put("MESA_VK_WSI_PRESENT_MODE", "mailbox")
        env.put("TU_DEBUG", "noconform")
        env.put("PULSE_LATENCY_MSEC", "144")
        env.put("GALLIUM_HUD", "fps")

        val driverConfig = KeyValueSet(original.graphicsDriverConfig)
        driverConfig.put("adrenotoolsTurnip", "1")

        // Values retained from the supplied working screenshots.
        val configured = original.copy(
            name = "RuneScape: Dragonwilds",
            drives = rewriteLegacyEdrive(original.drives, context),
            screenSize = "854x480",
            containerVariant = Container.BIONIC,
            wineVersion = "proton-11.0-1-arm64ec-1",
            execArgs = "-dx11",
            graphicsDriver = "Wrapper-v2",
            graphicsDriverVersion = "Turnip Adreno Driver T26 (@Mr_Purple_666)",
            graphicsDriverConfig = driverConfig.toString(),
            displayServer = "x11",
            displayRenderer = "vulkan",
            dxwrapper = "dxvk",
            dxwrapperConfig = "version=2.7.1",
            rendererPresentMode = "fifo",
            audioDriver = "pulseaudio",
            pulseaudioLowLatency = false,
            micEnabled = false,
            envVars = env.toString(),
            fexcoreVersion = "2601",
            box64Version = "0.4.2",
            fexcorePreset = FEXCorePreset.COMPATIBILITY,
            box64Preset = Box86_64Preset.COMPATIBILITY,
            launchRealSteam = true,
            launchBionicSteam = false,
            steamType = Container.STEAM_TYPE_HEADLESS,
            steamOfflineMode = false,
            shooterMode = false,
            sdlControllerAPI = true,
            useSteamInput = false,
            enableXInput = true,
            enableDInput = true,
            dinputMapperType = 1.toByte(),
            vibrationIntensity = 100,
            useDRI3 = true,
            csmt = true,
            strictShaderMath = true,
            offScreenRenderingMode = "fbo",
            videoMemorySize = "2048",
            mouseWarpOverride = "disable",
            portraitMode = false,
            forceDlc = false,
            loadMods = false,
            useLegacyDRM = false,
            unpackFiles = false,
            fasterExternalLoading = false,
            disableLibredirect = false,
            cpuList = "0,1,2,3,4,5,6,7",
            cpuListWoW64 = "4,5,6,7",
        )

        // Apply once and then persist the flag. No original app data is touched.
        ContainerUtils.setDefaultContainerData(configured)
        prefs.edit().putBoolean("defaults_v3_applied", true).apply()
        migrateTouchControls(context, prefs)
    }

    /**
     * One-time migration for already-created Dragonwilds Wine containers.
     * The upstream controller overlay defaults to shooter mode, which hides
     * the normal analog sticks. This changes only the Dragonwilds container.
     * Other games and the original GameNative installation are untouched.
     */
    private fun migrateTouchControls(
        context: Context,
        prefs: android.content.SharedPreferences,
    ) {
        if (prefs.getBoolean("touch_controls_v11_applied", false)) return
        val defaults = ContainerUtils.getDefaultContainerData()
        ContainerUtils.setDefaultContainerData(defaults.copy(
            shooterMode = false,
            sdlControllerAPI = true,
            useSteamInput = false,
            enableXInput = true,
        ))
        try {
            if (ContainerUtils.hasContainer(context, GAME_ID)) {
                val container = ContainerUtils.getContainer(context, GAME_ID)
                val existing = ContainerUtils.toContainerData(container)
                ContainerUtils.applyToContainer(context, container, existing.copy(
                    shooterMode = false,
                    sdlControllerAPI = true,
                    useSteamInput = false,
                    enableXInput = true,
                ))
            }
            prefs.edit().putBoolean("touch_controls_v11_applied", true).apply()
        } catch (_: Exception) {
            // Retry on next app launch if a container is not yet ready.
        }
    }

    /**
     * The game folder can be correctly imported while a new Steam container is
     * still missing executable or real-Steam settings. GameNative otherwise
     * displays "The executable could not be auto-selected".
     *
     * Call after creating the container and applying upstream game fixes,
     * before the first launch-executable validation. Never reimport files.
     */
    fun ensureSteamGameLaunchConfig(context: Context, container: Container) {
        if (container.id != GAME_ID) return
        var changed = false
        val updatedDrives = rewriteLegacyEdrive(container.drives, context)
        if (updatedDrives != container.drives) {
            container.drives = updatedDrives
            changed = true
        }
        if (!container.isLaunchRealSteam) {
            container.isLaunchRealSteam = true
            changed = true
        }
        if (container.isLaunchBionicSteam) {
            container.isLaunchBionicSteam = false
            changed = true
        }
        if (container.steamType != Container.STEAM_TYPE_HEADLESS) {
            container.steamType = Container.STEAM_TYPE_HEADLESS
            changed = true
        }
        if (container.executablePath.isBlank()) {
            // Prefer Steam's launcher stub, then the UE shipping binary.
            // The paths are relative to the imported game's own root.
            val gameRoot = File(SteamService.getAppDirPath(1374490))
            val chosen = listOf(
                "RSDragonwilds.exe",
                "RSDragonwilds/Binaries/Win64/RSDragonwilds-Win64-Shipping.exe",
            ).firstOrNull { File(gameRoot, it).isFile }
            if (chosen != null) {
                container.executablePath = chosen
                changed = true
            }
        }
        if (container.execArgs.isBlank()) {
            container.execArgs = "-dx11"
            changed = true
        }
        if (changed) container.saveData()
    }

    /**
     * Only queue the known Steam game when the user launches this app normally.
     * The actual login, install check and game startup remain owned by PluviaMain.
     * An exported folder by itself is NOT a registered Steam installation.
     */
    fun queueAutoLaunchIfInstalled(context: Context, launchIntent: Intent, restoring: Boolean) {
        if (restoring || launchIntent.action != Intent.ACTION_MAIN || MainActivity.hasPendingLaunchRequest()) return
        val installed = runCatching {
            SteamService.isAppInstalled(1374490) ||
                ContainerUtils.hasContainer(context, GAME_ID)
        }.getOrDefault(false)
        if (!installed) return
        MainActivity.setPendingLaunchRequest(IntentLaunchManager.LaunchRequest(GAME_ID))
    }
}
