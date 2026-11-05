package app.gamenative.ui

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import app.gamenative.data.AppInfo
import app.gamenative.enums.Marker
import app.gamenative.service.SteamService
import app.gamenative.utils.MarkerUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext

/**
 * Copy an owned, already-exported Dragonwilds directory into this app's private
 * Steam installation root. Never move or delete the user's Downloads backup.
 * Registration is done only after the complete copy and basic layout checks.
 */
internal object DragonwildsImport {
    private const val APP_ID = 1374490

    suspend fun copyAndRegister(
        context: Context,
        treeUri: Uri,
        onProgress: (Long, String) -> Unit,
    ): Result<String> = withContext(Dispatchers.IO) {
        var staging: File? = null
        var newTarget: File? = null
        try {
            if (!SteamService.isLoggedIn) error("Sign in to Steam first.")
            val service = SteamService.instance ?: error("Steam service is not ready.")
            val steamApp = SteamService.getAppInfoOf(APP_ID)
                ?: error("Dragonwilds metadata isn't ready yet. Reconnect to Steam.")
            val src = DocumentFile.fromTreeUri(context, treeUri)
                ?.takeIf { it.isDirectory }
                ?: error("Choose the Dragonwilds game directory, not an individual file.")
            if (src.findFile("Engine")?.isDirectory != true ||
                src.findFile("RSDragonwilds")?.isDirectory != true) {
                error("Wrong folder: select the folder containing both Engine and RSDragonwilds.")
            }

            val name = SteamService.getAppDirName(steamApp)
                .ifBlank { "RSDragonwilds" }
            if (name == "." || name == ".." || name.contains('/') || name.contains('\\')) {
                error("Steam supplied an invalid installation directory.")
            }
            val base = File(SteamService.internalAppInstallPath)
            if (!base.isDirectory && !base.mkdirs()) error("Cannot create the Steam install directory.")
            val dest = File(base, name)
            if (SteamService.isAppInstalled(APP_ID)) {
                return@withContext Result.success(dest.absolutePath)
            }
            if (dest.exists()) {
                error("A partial installation already exists. Back it up before retrying.")
            }

            val stage = File(base, ".dragonwilds-import-" + System.nanoTime())
            staging = stage
            if (!stage.mkdirs()) error("Cannot create temporary import directory.")
            var copiedBytes = 0L
            suspend fun copyTree(source: DocumentFile, target: File) {
                for (child in source.listFiles()) {
                    coroutineContext.ensureActive()
                    val childName = child.name ?: throw IOException("A file is missing its name.")
                    if (childName.isBlank() || childName == "." || childName == ".." ||
                        childName.contains('/') || childName.contains('\\')) {
                        throw IOException("Unsafe game file name: " + childName)
                    }
                    val output = File(target, childName)
                    if (child.isDirectory) {
                        if (!output.mkdirs()) throw IOException("Cannot create " + childName)
                        copyTree(child, output)
                    } else if (child.isFile) {
                        var written = 0L
                        val input = context.contentResolver.openInputStream(child.uri)
                            ?: throw IOException("Cannot read " + childName)
                        input.use { sourceStream ->
                            output.outputStream().use { destStream ->
                                val buffer = ByteArray(128 * 1024)
                                while (true) {
                                    coroutineContext.ensureActive()
                                    val count = sourceStream.read(buffer)
                                    if (count < 0) break
                                    destStream.write(buffer, 0, count)
                                    written += count
                                    copiedBytes += count
                                    onProgress(copiedBytes, childName)
                                }
                            }
                        }
                        if (child.length() > 0 && written != child.length()) {
                            throw IOException("Copy size mismatch: " + childName)
                        }
                    }
                }
            }
            copyTree(src, stage)

            val binaries = File(stage, "RSDragonwilds/Binaries/Win64")
            val hasExecutable = File(stage, "RSDragonwilds.exe").isFile ||
                binaries.listFiles()?.any { it.isFile && it.name.endsWith(".exe", true) } == true
            if (!File(stage, "Engine").isDirectory ||
                !File(stage, "RSDragonwilds").isDirectory ||
                !hasExecutable) {
                error("Incomplete backup: the game's Windows executable is missing.")
            }
            if (!stage.renameTo(dest)) error("Could not finish the game-file import.")
            staging = null
            newTarget = dest

            // Follow GameNative's own installed-game model rather than merely
            // saving a Downloads path; this does not grant a Steam license.
            service.appInfoDao.insert(
                AppInfo(id = APP_ID, isDownloaded = true, customInstallPath = dest.absolutePath),
            )
            if (!MarkerUtils.addMarker(dest.absolutePath, Marker.DOWNLOAD_COMPLETE_MARKER)) {
                error("Could not register the imported game directory.")
            }
            newTarget = null
            Result.success(dest.absolutePath)
        } catch (e: CancellationException) {
            staging?.deleteRecursively()
            newTarget?.deleteRecursively()
            throw e
        } catch (e: Exception) {
            staging?.deleteRecursively()
            // The original selected Downloads directory is never modified.
            newTarget?.let {
                MarkerUtils.removeMarker(it.absolutePath, Marker.DOWNLOAD_COMPLETE_MARKER)
                // Retain the copied files on a registration failure for diagnosis.
            }
            Result.failure(e)
        }
    }
}
