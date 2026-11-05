# Dragonwilds Mobile — stable APK signing

**Problem solved:** Android can only update an installed APK if its signing certificate matches. Earlier CI builds used a fresh throwaway debug signing key, forcing uninstall and deleting the private 40,000-file game installation.

This project now supports one permanent signing key and monotonically increasing APK version codes. **Never share or commit the unencrypted private key or secret value.**

## One-time setup (repository owner)

1. Privately extract `Dragonwilds_Stable_Signing_PRIVATE_RECOVERY.zip`, provided separately to the repository owner. Keep the ZIP in secure personal backup storage.
2. Open `DRAGONWILDS_SIGNING_BUNDLE_secret.txt` and **copy its entire single line**.
3. Navigate to **Repository Settings → Secrets and variables → Actions → New repository secret**.
4. Enter name `DRAGONWILDS_SIGNING_BUNDLE` and paste the single line as its **secret value**. Save.
5. Open **Actions → Dragonwilds Mobile APK → Run workflow**, and choose the branch with the latest approved fixes, starting with `fix/dragonwilds-v15-stable-signing`.
6. Download only the artifact named **Dragonwilds-Mobile-STABLE-SIGNED-APK-UNTESTED** from the successful, manually triggered workflow.

Pull requests still build for compilation verification, but no longer publish installable APKs. Manual builds fail if the secret is absent or invalid. Every manual APK uses the same signing identity. Treat all builds as untested until verified on Android hardware.

**Important transition:** You cannot update an already installed Build #13 signed by its old ephemeral key using the new stable signing key. There will be **one final uninstall** to establish the stable identity. Do this only after a stable-signed APK is verified and your Downloads game backup exists. On all future builds using the retained key, Android can update in place and preserve the imported game files and Wine container. Do **not** delete or regenerate the key.

The separate Dragonwilds Windows-game files are never included in APK artifacts and Steam authentication remains required. APK signing is not a fix for an unknown Wine/Steam launch crash; guest exit diagnostics are being added independently.

## Security notes

The **keystore is not committed**. Its public certificate identifies the developer; the actual private key is stored only in the recovery ZIP and injected at CI runtime from a GitHub Actions repository secret. Never print credentials into job logs, and do not grant untrusted code modifications access to the signing workflow. If a key is compromised, rotate intentionally, understanding Android may then require reinstalling.
