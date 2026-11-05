# Dragonwilds Mobile — Independent GameNative Fork Kit v3

**STATUS: SOURCE BUILD KIT. NOT AN INSTALLABLE APK. NOT YET BUILT OR PHONE-TESTED.**

This is a conservative source overlay for the GPL-3.0 GameNative project. Its goal is an **independent Android app** (`app.dragonwilds.mobile`) that can launch RuneScape: Dragonwilds using its own application sandbox, the necessary Windows compatibility runtime, and the **real headless Steam client**. It does not work by opening the separately installed GameNative app. No DRM/authentication bypass is attempted.

## What v3 changes compared with v2

- Removed an unverified auto-launch injection that used an unknown `SteamUtils.hasStoredCredentials()` method; this could have broken compilation. Future one-tap in-app auto-launch is a **separate unimplemented feature**, not a claim.
- Uses first-run GameNative `ContainerUtils` defaults with conservative overrides matching your screenshots.
- Adds an explicit Adrenotools/Turnip toggle to the graphics configuration.
- Adds a separate Android application ID, launcher label, dry-run validator, fail-safe source patch, revision record and diagnostic build workflow.
- Adds automated Python tests of the patcher's behavior and Android package-ID verification in CI after build.
- Builds only the `modern` ARM64 variant appropriate for a recent S26, not unrelated legacy/XR variants.
- Avoids copying or using old private paths, passwords, or installed-game data from GameNative.

## How to attempt APK build from GitHub (phone-friendly)

1. Sign in to GitHub. Create a **private** repository; choose to initialize with README.
2. Unzip this kit. Upload everything **inside** the `Dragonwilds_Mobile_BuildKit_v3` folder to the repository root, including the hidden `.github/workflows/build-dragonwilds.yml` file and `tests/` directory. Keep the relative paths unchanged.
3. Open the repository **Actions** tab → **Dragonwilds Mobile independent APK (experimental)** → **Run workflow**.
4. If build succeeds, download artifact `dragonwilds-mobile-ARM64-debug-UNTESTED`, unzip and install the `.apk` (do not confuse the GitHub artifact ZIP with an APK).
5. If it fails, inspect the first actual compiler error in the Actions log. The log is actionable; it is not proof the settings are bad.

The cloud runner needs internet access and the **upstream repository**, Android SDK 36, NDK 27.3.13750724, Java 17, Gradle, and compatible GameNative assets. A compiled APK may still fail on-device if upstream binaries contain app-ID/path assumptions. GitHub CI is **not** a substitute for installing, signing in, and running the game.

## Expected first-run workflow

1. Install the separate Dragonwilds Mobile APK **alongside** GameNative.
2. Open the new app, sign in to Steam normally, download/install Dragonwilds and any missing runtime components. You still must legitimately own the game.
3. Launch Dragonwilds from inside its library. Settings defaults are applied to **new** game containers, not existing ones. Auto-start directly on icon tap is **not included in v3**; implement after successful first build and launch.
4. Compare all settings to `dragonwilds_profile.json`. Some screenshot settings aren't mapped into the bootstrap and may require adjusting manually.
5. Test real Steam sign-in, online multiplayer, save behavior, and performance before changing anything in the original GameNative app.

## Screen settings recorded

- Bionic / proton-11.0-1-arm64ec-1; FEXCore 2601, Box64 0.4.2, compatibility presets
- Wrapper-v2, Turnip T26 (@Mr_Purple_666), Adrenotools on, Vulkan/X11
- DXVK 2.7.1, `-dx11`, 854 × 480, PulseAudio
- Steam client enabled / headless, Steam offline mode off
- SDL + XInput + DirectInput, 100% vibration, DRI3 on, Wine CSMT on
- 8 CPU cores for main process, CPU4–7 for 32-bit; Wine environment values in profile

## Limits we cannot responsibly promise away

- This kit has **not been compiled against the full current GameNative tree** here. GitHub/Gradle may reveal further API errors or missing assets.
- A separate app cannot read GameNative's private path `/data/user/0/app.gamenative/`. The A: drive in your screenshot points inside that private directory and is **not** copied into the fork. Don't point it there manually.
- GPU identity override, Win Components DLL overrides, asset version availability, and a few user-specific graphics controls may need manual checks. Refer to the JSON profile; it is a reference, not an import automatically applied in full.
- Steam Cloud may not capture all saved character state, especially in multiplayer worlds. Back up separately before changing installs.
- **Do not uninstall or clear GameNative**, and never upload Steam passwords, tokens, or paid game files to GitHub.
- Distributing a fork of GPL-3.0 code requires complying with GPL-3.0 and upstream third-party notices. Shipping Steam binaries or the commercial Dragonwilds game may not be authorized; this ZIP does not contain them.

Source: https://github.com/utkarshdalal/GameNative
