#!/usr/bin/env python3
"""Conservative source-only overlay of an independent GameNative fork.

Patch prerequisites are checked before any edits are written. No private game
files, tokens, passwords, SDK downloads or original GameNative data are used.
"""
from __future__ import annotations

import argparse
from pathlib import Path
import re
import shutil
import sys

ROOT = Path(__file__).resolve().parent
INSERT_LINE = '        DragonwildsBootstrap.installFirstRunDefaults(applicationContext)\n'


def patch_once(text: str, anchor: str, replacement: str, description: str) -> str:
    if replacement in text:
        return text
    matches = text.count(anchor)
    if matches != 1:
        raise ValueError(f'{description}: expected exactly one upstream anchor, found {matches}; refuse patch')
    return text.replace(anchor, replacement, 1)


def prepare(repo: Path, app_id: str) -> dict[Path, str]:
    if not re.fullmatch(r'[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+', app_id):
        raise ValueError(f'Invalid Android applicationId: {app_id!r}')
    if app_id == 'app.gamenative':
        raise ValueError('Refusing to override the installed GameNative applicationId')

    mainfile = repo / 'app/src/main/java/app/gamenative/MainActivity.kt'
    gradle = repo / 'app/build.gradle.kts'
    strings = repo / 'app/src/main/res/values/strings.xml'
    bootstrap = repo / 'app/src/main/java/app/gamenative/DragonwildsBootstrap.kt'
    for file in (mainfile, gradle, strings):
        if not file.is_file():
            raise FileNotFoundError(f'Missing source file {file}; check source tree/revision')

    main = mainfile.read_text(encoding='utf-8')
    main = patch_once(main,
        '        ContainerUtils.setContainerDefaults(applicationContext)\n',
        '        ContainerUtils.setContainerDefaults(applicationContext)\n' + INSERT_LINE,
        'first-run defaults')


    # Keep GameNative's Steam login/game engine, but replace only its generic
    # HomeScreen with a dedicated Dragonwilds-only setup/start screen.
    ui_file = repo / 'app/src/main/java/app/gamenative/ui/PluviaMain.kt'
    ui_src = ui_file.read_text(encoding='utf-8')
    home_start = '                    HomeScreen(\n'
    home_end = '                        isSteamConnected = state.isSteamConnected,\n                    )\n'
    if ui_src.count(home_start) != 1 or ui_src.count(home_end) != 1:
        raise ValueError('GameNative HomeScreen changed; refuse unsafe UI replacement')
    begin = ui_src.index(home_start)
    finish = ui_src.index(home_end, begin) + len(home_end)
    ui_src = ui_src[:begin] + (
        '                    DragonwildsHome(\n'
        '                        onLaunch = { launchIntentApp("STEAM_1374490", false, null) },\n'
        '                        onSignIn = { navController.navigate(PluviaScreen.LoginUser.route) },\n'
        '                        onAdvancedSettings = { navController.navigate(PluviaScreen.Settings.route) },\n'
        '                    )\n'
    ) + ui_src[finish:]

    # Dragonwilds needs to set up its own bundled EOS service in each new
    # Wine prefix. GameNative's general prereq list does not include it.
    marker_file = repo / 'app/src/main/java/app/gamenative/enums/Marker.kt'
    marker_src = marker_file.read_text(encoding='utf-8')
    marker_src = patch_once(
        marker_src,
        '    UBISOFT_CONNECT_INSTALLED(".ubisoft_connect_installed"),\n',
        '    UBISOFT_CONNECT_INSTALLED(".ubisoft_connect_installed"),\n'
        '    DRAGONWILDS_EOS_INSTALLED(".dragonwilds_eos_installed"),\n',
        'Dragonwilds EOS marker',
    )

    prereq_file = repo / 'app/src/main/java/app/gamenative/utils/PreInstallSteps.kt'
    prereq_src = prereq_file.read_text(encoding='utf-8')
    prereq_src = patch_once(
        prereq_src,
        '        UbisoftConnectStep,\n',
        '        UbisoftConnectStep,\n        DragonwildsEosStep,\n',
        'register bundled EOS installer',
    )

    xserver_file = repo / 'app/src/main/java/app/gamenative/ui/screen/xserver/XServerScreen.kt'
    xserver_src = xserver_file.read_text(encoding='utf-8')
    xserver_src = patch_once(
        xserver_src,
        '            PreInstallSteps.markStepDone(container, current.marker)\n',
        '            if (current.marker != Marker.DRAGONWILDS_EOS_INSTALLED || status == 0) {\n'
        '                PreInstallSteps.markStepDone(container, current.marker)\n'
        '            } else {\n'
        '                Timber.w("EOS prerequisite returned exit code %d; retry on next launch", status)\n'
        '            }\n',
        'only mark bundled EOS install on success',
    )

    # Record the actual Wine guest process termination, which does not show up
    # as an Android process-exit. This is the missing diagnostic for screen exits.
    xserver_src = patch_once(
        xserver_src,
        '            return@Callback\n        }\n'
        '        if (status != 0) {\n'
        '            Timber.e("Guest program terminated with status: $status")\n',
        '            return@Callback\n        }\n'
        '        app.gamenative.ui.DragonwildsDiagnostics.recordGuestExit(context, appId, status)\n'
        '        if (status != 0) {\n'
        '            Timber.e("Guest program terminated with status: $status")\n',
        'capture Wine guest exit status for Dragonwilds',
    )
    xserver_src = patch_once(
        xserver_src,
        '        Timber.i("onGuestProgramTerminated")\n'
        '        SteamHostStatusWatch.stop()\n',
        '        Timber.i("onGuestProgramTerminated")\n'
        '        app.gamenative.ui.DragonwildsDiagnostics.recordSessionEnded(context, appId, "guest_terminated")\n'
        '        SteamHostStatusWatch.stop()\n',
        'capture game screen closure',
    )

    steamwatch_file = repo / 'app/src/main/java/app/gamenative/ui/screen/xserver/SteamHostStatusWatch.kt'
    steamwatch_src = steamwatch_file.read_text(encoding='utf-8')
    steamwatch_src = patch_once(
        steamwatch_src,
        '        file.delete()\n'
        '        if (!active.compareAndSet(false, true)) return\n',
        '        file.delete()\n'
        '        app.gamenative.ui.DragonwildsDiagnostics.recordSteamWatcherStart(context)\n'
        '        if (!active.compareAndSet(false, true)) return\n',
        'record Steam watcher initialization',
    )
    steamwatch_src = patch_once(
        steamwatch_src,
        '                        last = status\n'
        '                        val text = when (status.substringBefore(\' \')) {\n',
        '                        last = status\n'
        '                        app.gamenative.ui.DragonwildsDiagnostics.recordSteamStage(context, status)\n'
        '                        val text = when (status.substringBefore(\' \')) {\n',
        'record Steam startup status changes',
    )

    # Persist failures raised by headless Steam/Wine, not just Timber logs.
    ui_src = patch_once(
        ui_src,
        '                        onGameLaunchError = { error ->\n'
        '                            viewModel.onGameLaunchError(error)\n',
        '                        onGameLaunchError = { error ->\n'
        '                            app.gamenative.ui.DragonwildsDiagnostics.recordLaunchError(context, error)\n'
        '                            viewModel.onGameLaunchError(error)\n',
        'capture errors from Wine or headless Steam',
    )

    # Expose a read-only view of Steam's per-game cloud-save lock so this
    # single-game launcher can wait for a legitimate in-flight sync rather than
    # sending a second concurrent launch request. Never clear or bypass the lock.
    steam_file = repo / 'app/src/main/java/app/gamenative/service/SteamService.kt'
    steam_src = steam_file.read_text(encoding='utf-8')
    steam_src = patch_once(
        steam_src,
        '        private val syncInProgressApps = ConcurrentHashMap<Int, AtomicBoolean>()\n',
        '        private val syncInProgressApps = ConcurrentHashMap<Int, AtomicBoolean>()\n'
        '\n'
        '        fun isCloudSyncActiveForApp(appId: Int): Boolean = syncInProgressApps[appId]?.get() == true\n',
        'check whether Steam cloud-save sync is active without modifying it',
    )

    # Upstream's "Wait" button only closes a Sync In Progress dialog, which
    # users rightly expect to actually wait for the active sync. On Dragonwilds
    # only, wait on the read-only lock and retry NORMAL cloud-safe launch.
    ui_src = patch_once(
        ui_src,
        '            onDismissClick = {\n'
        '                setMessageDialogState(MessageDialogState(false))\n'
        '            }\n'
        '            onDismissRequest = {\n'
        '                setMessageDialogState(MessageDialogState(false))\n'
        '            }\n'
        '        }\n\n'
        '        DialogType.PENDING_UPLOAD_IN_PROGRESS -> {\n',
        '            onDismissClick = {\n'
        '                setMessageDialogState(MessageDialogState(false))\n'
        '                if (state.launchedAppId == app.gamenative.DragonwildsBootstrap.GAME_ID) {\n'
        '                    scope.launch {\n'
        '                        viewModel.setLoadingDialogVisible(true)\n'
        '                        viewModel.setLoadingDialogMessage("Waiting for Steam cloud saves to finish safely…")\n'
        '                        viewModel.setLoadingDialogProgress(-1f)\n'
        '                        val clear = withTimeoutOrNull(120_000L) {\n'
        '                            while (SteamService.isCloudSyncActiveForApp(1374490)) delay(1500L)\n'
        '                            true\n'
        '                        } == true\n'
        '                        viewModel.setLoadingDialogVisible(false)\n'
        '                        if (clear) {\n'
        '                            preLaunchApp(\n'
        '                                context = context,\n'
        '                                appId = state.launchedAppId,\n'
        '                                setLoadingDialogVisible = viewModel::setLoadingDialogVisible,\n'
        '                                setLoadingProgress = viewModel::setLoadingDialogProgress,\n'
        '                                setLoadingMessage = viewModel::setLoadingDialogMessage,\n'
        '                                setMessageDialogState = setMessageDialogState,\n'
        '                                onSuccess = viewModel::launchApp,\n'
        '                                isOffline = viewModel.isOffline.value,\n'
        '                            )\n'
        '                        } else {\n'
        '                            setMessageDialogState(MessageDialogState(\n'
        '                                visible = true,\n'
        '                                type = DialogType.SYNC_FAIL,\n'
        '                                title = context.getString(R.string.sync_error_title),\n'
        '                                message = "Cloud-save sync is still running. Return to Dragonwilds and retry after it finishes; your saves were not skipped.",\n'
        '                                dismissBtnText = context.getString(R.string.ok),\n'
        '                            ))\n'
        '                        }\n'
        '                    }\n'
        '                }\n'
        '            }\n'
        '            onDismissRequest = {\n'
        '                setMessageDialogState(MessageDialogState(false))\n'
        '            }\n'
        '        }\n\n'
        '        DialogType.PENDING_UPLOAD_IN_PROGRESS -> {\n',
        'make the Dragonwilds cloud-sync Wait action safely wait and retry',
    )

    # Reconcile the imported Steam app's container at launch time. The upstream
    # Steam game best-config application may overwrite the initial defaults;
    # explicitly restore our Steam/headless and known executable settings just
    # before the pre-launch executable validation.
    launch_anchor = (
        '        val gameSource = ContainerUtils.extractGameSourceFromContainerId(appId)\n'
        '        val isLocalSavesOnly = ContainerUtils.isLocalSavesOnly(context, appId)\n'
    )
    launch_injection = (
        '        if (appId == app.gamenative.DragonwildsBootstrap.GAME_ID && !bootToContainer) {\n'
        '            app.gamenative.DragonwildsBootstrap.ensureSteamGameLaunchConfig(context, container)\n'
        '        }\n'
    )
    ui_src = patch_once(
        ui_src, launch_anchor,
        launch_injection + launch_anchor,
        'repair Dragonwilds Steam launch configuration before executable validation',
    )

    # Upstream contains hard-coded /data/data/app.gamenative paths. Once the
    # Android package name is changed, those paths point to a DIFFERENT app's
    # private storage. Repair them before compiling our standalone build.
    bionic_file = repo / 'app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java'
    bionic_src = bionic_file.read_text(encoding='utf-8')
    bionic_src = patch_once(
        bionic_src,
        'memPath = "/data/data/app.gamenative/files/imagefs/tmp/gamepad.mem";',
        'memPath = new File(new File(environment.getContext().getFilesDir(), "gamepad_shm"), "gamepad.mem").getAbsolutePath();',
        'redirect player-1 controller shared memory to the standalone app',
    )
    bionic_src = patch_once(
        bionic_src,
        'memPath = "/data/data/app.gamenative/files/imagefs/tmp/gamepad" + i + ".mem";',
        'memPath = new File(new File(environment.getContext().getFilesDir(), "gamepad_shm"), "gamepad" + i + ".mem").getAbsolutePath();',
        'redirect other controller shared memory to the standalone app',
    )
    bionic_src = patch_once(
        bionic_src,
        '        Context context = environment.getContext();\n'
        '        ImageFs imageFs = ImageFs.find(context);\n'
        '        File rootDir = imageFs.getRootDir();\n'
        '\n'
        '        PrefManager.init(context);\n',
        '        Context context = environment.getContext();\n'
        '        envVars.put("EVSHIM_BASE_PATH", context.getFilesDir().getAbsolutePath());\n'
        '        ImageFs imageFs = ImageFs.find(context);\n'
        '        File rootDir = imageFs.getRootDir();\n'
        '\n'
        '        PrefManager.init(context);\n',
        'set SDL evshim private shared-memory root',
    )

    wine_file = repo / 'app/src/main/java/com/winlator/core/WineUtils.java'
    wine_src = wine_file.read_text(encoding='utf-8')
    wine_src = patch_once(
        wine_src,
        'missingDrives += "E:/data/data/app.gamenative/storage";',
        'missingDrives += "E:" + new File(context.getApplicationInfo().dataDir, "storage").getAbsolutePath();',
        'map E drive to standalone storage',
    )
    wine_src = patch_once(
        wine_src,
        'path.endsWith("/app.gamenative/storage")',
        'path.endsWith("/" + context.getPackageName() + "/storage")',
        'fix own-app E drive directory creation',
    )

    dxvk_file = repo / 'app/src/main/java/com/winlator/core/DXVKHelper.java'
    dxvk_src = dxvk_file.read_text(encoding='utf-8')
    dxvk_src = patch_once(
        dxvk_src,
        '"/data/data/app.gamenative/files/imagefs"+ImageFs.CACHE_PATH',
        'imageFs.getRootDir().getAbsolutePath()+ImageFs.CACHE_PATH',
        'write DXVK cache to standalone ImageFs root',
    )

    container_file = repo / 'app/src/main/java/com/winlator/container/Container.java'
    container_src = container_file.read_text(encoding='utf-8')
    container_src = container_src.replace(
        '/data/data/app.gamenative/',
        f'/data/data/{app_id}/',
    )
    if container_src == container_file.read_text(encoding='utf-8'):
        raise ValueError('Container.java did not contain expected app-private path literals')

    evshim_file = repo / 'app/src/main/cpp/evshim/evshim.c'
    evshim_src = evshim_file.read_text(encoding='utf-8')
    evshim_src = patch_once(
        evshim_src,
        'base = "/data/data/app.gamenative/files";',
        f'base = "/data/data/{app_id}/files";',
        'fix native evshim fallback for standalone app',
    )

    # No upstream GameNative branding on the game splash in this single-game app.
    splash_file = repo / 'app/src/main/java/app/gamenative/ui/components/BootingSplash.kt'
    splash_src = splash_file.read_text(encoding='utf-8')
    if splash_src.count('text = "GameNative"') != 2:
        raise ValueError('Expected two upstream splash logo texts; abort unsafe change')
    splash_src = splash_src.replace('text = "GameNative"', 'text = "Dragonwilds"', 2)

    # GameNative's upstream default is Wi-Fi-only downloads, including Steam.
    # On this dedicated Dragonwilds fork, enable cellular by default and
    # migrate any saved Wi-Fi-only value from previous APKs exactly once.
    pref_file = repo / 'app/src/main/java/app/gamenative/PrefManager.kt'
    pref_src = pref_file.read_text(encoding='utf-8')
    pref_src = patch_once(
        pref_src,
        '        get() = getPref(DOWNLOAD_ON_WIFI_ONLY, true)\n',
        '        get() = getPref(DOWNLOAD_ON_WIFI_ONLY, false)\n',
        'enable cellular download default',
    )
    pref_src = patch_once(
        pref_src,
        '        dataStore = context.datastore\n',
        '        dataStore = context.datastore\n'
        '        // One-time migration: release the old Wi-Fi-only preference.\n'
        '        // Keep later user choices intact. This runs before SteamService.\n'
        '        runBlocking {\n'
        '            dataStore.edit { values ->\n'
        '                val migrated = booleanPreferencesKey("dragonwilds_cellular_migrated_v16")\n'
        '                if (values[migrated] != true) {\n'
        '                    values[DOWNLOAD_ON_WIFI_ONLY] = false\n'
        '                    values[migrated] = true\n'
        '                }\n'
        '            }\n'
        '        }\n',
        'migrate existing Dragonwilds installs to cellular downloads',
    )

    # Show a dedicated Dragonwilds eye on the Android home screen instead of
    # upstream GameNative's launcher image. This changes only our app variant.
    eye_icon = repo / 'app/src/main/res/drawable/dragonwilds_eye.xml'
    eye_adaptive = repo / 'app/src/main/res/mipmap-anydpi-v26/ic_dragonwilds.xml'
    gradle_src = gradle.read_text(encoding='utf-8')
    gradle_src = patch_once(gradle_src,
        'applicationId = "app.gamenative"',
        f'applicationId = "{app_id}"',
        'independent package name')

    # Fixed signing identity. The actual keystore/password live only in a
    # GitHub Actions repository secret or a private local build environment.
    # PR builds still compile using a temporary debug key, but are NOT
    # distributed by our workflow; only manually triggered signed builds
    # are allowed as installable artifacts.
    gradle_src = patch_once(
        gradle_src,
        '    signingConfigs {\n        create("pluvia") {\n',
        '    signingConfigs {\n'
        '        create("dragonwildsStable") {\n'
        '            val password = System.getenv("DRAGONWILDS_SIGNING_PASSWORD")\n'
        '            if (!password.isNullOrBlank()) {\n'
        '                storeFile = rootProject.file("dragonwilds-signing.p12")\n'
        '                storePassword = password\n'
        '                keyAlias = "dragonwilds-mobile"\n'
        '                keyPassword = password\n'
        '            }\n'
        '        }\n'
        '        create("pluvia") {\n',
        'define stable private APK signing config',
    )
    gradle_src = patch_once(
        gradle_src,
        '        debug {\n'
        '            isDebuggable = true\n'
        '            isMinifyEnabled = false\n'
        '            isShrinkResources = false\n'
        '            signingConfig = signingConfigs.getByName("debug")\n',
        '        debug {\n'
        '            isDebuggable = true\n'
        '            isMinifyEnabled = false\n'
        '            isShrinkResources = false\n'
        '            signingConfig = signingConfigs.getByName(\n'
        '                if (System.getenv("DRAGONWILDS_SIGNING_PASSWORD").isNullOrBlank()) "debug"\n'
        '                else "dragonwildsStable"\n'
        '            )\n',
        'use stable signing config for modern debug builds',
    )
    # Monotonic version codes to allow Android to perform ordinary updates.
    gradle_src = patch_once(
        gradle_src,
        '        versionCode = 24\n',
        '        versionCode = 10000 + (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 15)\n',
        'increment APK version codes',
    )

    gradle_src = patch_once(
        gradle_src,
        '        val iconValue = "@mipmap/ic_launcher"\n',
        '        val iconValue = "@mipmap/ic_dragonwilds"\n',
        'replace GameNative icon with dragon eye',
    )
    gradle_src = patch_once(
        gradle_src,
        '        val iconRoundValue = "@mipmap/ic_launcher_round"\n',
        '        val iconRoundValue = "@mipmap/ic_dragonwilds"\n',
        'replace GameNative round icon with dragon eye',
    )

    strings_src = strings.read_text(encoding='utf-8')
    matches = list(re.finditer(r'<string\s+name="app_name">.*?</string>', strings_src, re.DOTALL))
    if len(matches) != 1:
        raise ValueError('strings.xml: expected exactly one app_name string')
    current = matches[0].group(0)
    strings_src = strings_src.replace(current, '<string name="app_name">Dragonwilds Mobile</string>', 1)

    return {
        mainfile: main,
        gradle: gradle_src,
        strings: strings_src,
        bootstrap: (ROOT / 'DragonwildsBootstrap.kt').read_text(encoding='utf-8'),
        pref_file: pref_src,
        splash_file: splash_src,
        eye_icon: (ROOT / 'dragonwilds_eye.xml').read_text(encoding='utf-8'),
        eye_adaptive: (ROOT / 'ic_dragonwilds.xml').read_text(encoding='utf-8'),
        bionic_file: bionic_src,
        wine_file: wine_src,
        dxvk_file: dxvk_src,
        container_file: container_src,
        evshim_file: evshim_src,
        ui_file: ui_src,
        marker_file: marker_src,
        prereq_file: prereq_src,
        xserver_file: xserver_src,
        steamwatch_file: steamwatch_src,
        steam_file: steam_src,
        repo / 'app/src/main/java/app/gamenative/utils/preInstallSteps/DragonwildsEosStep.kt': (ROOT / 'DragonwildsEosStep.kt').read_text(encoding='utf-8'),
        repo / 'app/src/main/java/app/gamenative/ui/DragonwildsHome.kt': (ROOT / 'DragonwildsHome.kt').read_text(encoding='utf-8'),
        repo / 'app/src/main/java/app/gamenative/ui/DragonwildsDiagnostics.kt': (ROOT / 'DragonwildsDiagnostics.kt').read_text(encoding='utf-8'),
        repo / 'app/src/main/java/app/gamenative/ui/DragonwildsImport.kt': (ROOT / 'DragonwildsImport.kt').read_text(encoding='utf-8'),
    }


def run() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source', type=Path, help='Cloned GameNative source repository root')
    parser.add_argument('--app-id', default='app.dragonwilds.mobile')
    parser.add_argument('--dry-run', action='store_true', help='Validate only, make no changes')
    opts = parser.parse_args()
    repo = opts.source.expanduser().resolve()
    try:
        edits = prepare(repo, opts.app_id)
        for target, content in edits.items():
            if target.exists() and target.read_text(encoding='utf-8') == content:
                print('unchanged', target.relative_to(repo))
            else:
                print('PATCH', target.relative_to(repo))
        if opts.dry_run:
            print('Dry run passed; no files written.')
            return 0
        # Back up originals before any write; atomic rename for each new result.
        for target, content in edits.items():
            target.parent.mkdir(parents=True, exist_ok=True)
            if target.is_file() and target.read_text(encoding='utf-8') == content:
                continue
            backup = target.with_suffix(target.suffix + '.dw-original')
            if target.is_file() and not backup.exists():
                shutil.copy2(target, backup)
            tmp = target.with_suffix(target.suffix + '.dw-temp')
            tmp.write_text(content, encoding='utf-8')
            tmp.replace(target)
        print('Applied source overlay. Build and Android device validation STILL REQUIRED.')
        return 0
    except (ValueError, OSError) as exc:
        print(f'PATCH FAILED SAFELY: {exc}', file=sys.stderr)
        return 2


if __name__ == '__main__':
    raise SystemExit(run())
