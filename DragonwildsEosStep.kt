package app.gamenative.utils

import app.gamenative.data.GameSource
import app.gamenative.enums.Marker
import com.winlator.container.Container
import java.io.File
import timber.log.Timber

/**
 * Restore the game's bundled EOS redistributable in this game's new Wine prefix.
 * Never execute arbitrary batch content: only extract a validated productId
 * from the Steam game's InstallEOSServices.bat then run the bundled installer
 * with a fixed, known set of arguments.
 */
object DragonwildsEosStep : PreInstallStep {
    private const val STEAM_APP_ID = "STEAM_1374490"
    override val marker: Marker = Marker.DRAGONWILDS_EOS_INSTALLED

    private val productPattern =
        Regex("(?i)productId\\s*=\\s*\\\"?([0-9a-f]{32})\\\"?")
    private const val MAX_INSTALL_SCRIPT_BYTES = 8192L

    private fun serviceInstalled(container: Container): Boolean {
        val prefix = File(container.rootDir, ".wine/drive_c")
        return listOf(
            File(prefix, "Program Files (x86)/Epic Games/Epic Online Services"),
            File(prefix, "Program Files/Epic Games/Epic Online Services"),
        ).any { dir ->
            dir.isDirectory &&
                (dir.listFiles()?.any { it.isFile && it.name.equals("EpicOnlineServices.exe", true) } == true)
        }
    }

    override fun appliesTo(
        container: Container,
        gameSource: GameSource,
        gameDirPath: String,
    ): Boolean {
        if (gameSource != GameSource.STEAM || container.id != STEAM_APP_ID) return false
        // Markers are per game directory; the service lives in the Wine prefix.
        // If the prefix is recreated we must install it again.
        return !MarkerUtils.hasMarker(gameDirPath, marker) || !serviceInstalled(container)
    }

    override fun buildCommand(
        container: Container,
        appId: String,
        gameSource: GameSource,
        gameDir: File,
        gameDirPath: String,
    ): String? {
        if (appId != STEAM_APP_ID || gameSource != GameSource.STEAM) return null
        if (!appliesTo(container, gameSource, gameDirPath)) return null

        val bat = gameDir.listFiles()?.firstOrNull {
            it.isFile && it.name.equals("InstallEOSServices.bat", ignoreCase = true)
        }
        if (bat == null || bat.length() !in 1L..MAX_INSTALL_SCRIPT_BYTES) {
            Timber.tag("DragonwildsEOS").w("No usable bundled InstallEOSServices.bat; EOS not installed")
            return null
        }
        val installer = gameDir.listFiles()?.firstOrNull {
            it.isFile && it.name.equals("EpicOnlineServicesInstaller.exe", ignoreCase = true)
        }
        if (installer == null || installer.length() == 0L) {
            Timber.tag("DragonwildsEOS").w("Bundled EpicOnlineServicesInstaller.exe missing")
            return null
        }

        val contents = runCatching { bat.readText(Charsets.ISO_8859_1) }.getOrNull() ?: return null
        if (!contents.contains("EpicOnlineServicesInstaller", ignoreCase = true)) {
            Timber.tag("DragonwildsEOS").w("EOS batch does not refer to the bundled installer")
            return null
        }
        val productId = productPattern.find(contents)?.groupValues?.getOrNull(1)
            ?: run {
                Timber.tag("DragonwildsEOS").w("No validated EOS productId in the game's installation script")
                return null
            }
        // No user-supplied command fragments; product ID is restricted to 32 hex chars.
        Timber.tag("DragonwildsEOS").i("Provisioning bundled EOS service in the Dragonwilds Wine prefix")
        return "A:\\" + installer.name + " /install productId=" + productId + " /quiet"
    }
}
