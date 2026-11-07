package app.gamenative

import android.content.Context
import app.gamenative.utils.ContainerUtils
import com.winlator.box86_64.Box86_64Preset
import com.winlator.container.Container
import com.winlator.core.envvars.EnvVars
import com.winlator.core.KeyValueSet
import com.winlator.fexcore.FEXCorePreset

/**
 * Applies the supplied phone's working defaults to NEW game containers.
 * DOES NOT bypass Steam, fetch private data from GameNative, or modify saves.
 * Keep this separate from installed-game configuration to avoid surprise changes.
 */
object DragonwildsBootstrap {
    const val GAME_ID: String = "STEAM_1374490"

    fun installFirstRunDefaults(context: Context) {
        val prefs = context.getSharedPreferences("dragonwilds_mobile_defaults", Context.MODE_PRIVATE)
        if (prefs.getBoolean("defaults_v4_applied", false)) return

        val original = ContainerUtils.getDefaultContainerData()
        val env = EnvVars(original.envVars)
        env.put("WRAPPER_MAX_IMAGE_COUNT", "0")
        env.put("ZINK_DESCRIPTORS", "lazy")
        env.put("ZINK_DEBUG", "compact")
        env.put("MESA_SHADER_CACHE_DISABLE", "false")
        env.put("MESA_SHADER_CACHE_MAX_SIZE", "256MB")
        env.put("mesa_glthread", "true")
        env.put("WINEESYNC", "1")
        env.put("MESA_VK_WSI_PRESENT_MODE", "mailbox")
        env.put("TU_DEBUG", "noconform")
        env.put("PULSE_LATENCY_MSEC", "144")
        // Avoid the debug FPS overlay during memory-sensitive Steam startup.
        // Performance can be measured after the guest reaches the game menu.

        val driverConfig = KeyValueSet(original.graphicsDriverConfig)
        driverConfig.put("adrenotoolsTurnip", "1")

        // Values retained from the supplied working screenshots.
        val configured = original.copy(
            name = "RuneScape: Dragonwilds",
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
        prefs.edit().putBoolean("defaults_v4_applied", true).apply()
    }
}
