# No-data-loss checklist for testing Dragonwilds Mobile

1. Leave the working GameNative installation and all of its app data untouched.
2. Before testing, document Steam Cloud sync state, character and world progress. Back up local data through a supported user-accessible export if available. Do not assume multiplayer character progress is cloud-saved.
3. Install independent APK alongside GameNative; Android package ID must be `app.dragonwilds.mobile`.
4. Sign into the real Steam client *inside the new app*. It has a separate app-private storage area; Steam tokens from GameNative are not used.
5. Redownload the legally owned game inside the new app unless a supported external-file import path is available.
6. In the new game container, confirm X11, Turnip T26, DXVK 2.7.1, -dx11, Proton 11.0-1 ARM64EC, FEX 2601, 854×480, headless Steam, SDL/XInput/DirectInput.
7. Correct any options not already applied by first-run defaults using `dragonwilds_profile.json` as a reference. Do not reuse the original A: drive's `app.gamenative` private path.
8. Verify online login, multiplayer joining, save/load progress, then confirm correct Steam Cloud state again.
9. Only after all checks pass should you consider changing which launcher you use. No data migration is performed by this ZIP.
