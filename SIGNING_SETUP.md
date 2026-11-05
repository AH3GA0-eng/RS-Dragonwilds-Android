# Persistent APK signing — one-time setup

Previous Dragonwilds Mobile Android builds used GitHub-hosted runners' automatically
generated temporary debug keystores. A new runner means a new signing certificate:
Android blocks an in-place update, and uninstalling deletes the standalone app's
private imported game files and Wine prefix.

This branch changes the workflow to **refuse to publish another APK** unless the
same private signing key has been restored from the repository's Actions secret.

## One-time owner setup on GitHub

1. Obtain your **private** `Dragonwilds_GitHub_Secret_VALUE.txt` file from the
   conversation artifact. Keep it private, never commit it, and don't post
   its contents in an issue, PR, or chat message.
2. Visit `https://github.com/AH3GA0-eng/RS-Dragonwilds-Android/settings/secrets/actions`.
3. Choose **New repository secret**.
4. Secret name (exact): `DRAGONWILDS_DEBUG_KEYSTORE_B64`.
5. Paste the **entire single line** from the private text file as its value;
   save it. This is the base64 representation of one Android debug keystore.
6. Return to the Build #15 PR and let the GitHub Actions workflow compile
   the new signed APK. If the secret is missing, the workflow fails safely.

The intended signing certificate SHA-256 fingerprint is:
`56:90:96:D4:42:78:84:B6:24:74:A8:F0:79:AF:83:4E:53:D4:50:CA:AF:89:D0:C9:1F:CF:64:80:DF:CF:40:84`.

**Warning:** Builds #8–#14 were signed with different temporary certificates.
Signing #15 with this *new stable* certificate cannot update those existing
installations. Android requires one final clean install to switch to the stable
certificate. Thereafter, as long as the same secret is retained in Actions,
future signed builds can update **without uninstalling, losing private game
files, or repeating the import**. Keep the original GameNative app and the
Download backup until gameplay is verified.

Deleting or changing this secret will break compatibility again. Do not use
these debug signing credentials for a Google Play production release.

This fixes APK update identity, **not** the independent game/Wine/EOS startup
failure. That needs the actual guest exit code/Steam watcher diagnostics added
in Build #14 and an S26 test.
