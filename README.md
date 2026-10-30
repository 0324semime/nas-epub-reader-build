# NAS EPUB Reader (Android)

Experimental Android EPUB reader, built in the cloud using GitHub Actions.

## v0.1
- Import an EPUB using Android's document picker
- Extract title, author and (when included) cover from EPUB2/EPUB3
- Display a cover-based library; tap to start reading
- Read chapters in WebView, change text size, resume the last chapter
- Long-press a book to delete its local copy

## Planned
- SMB access to UGREEN NAS shared folders
- Built-in Tailscale connection
- AYN Thor dual-screen optimization

**This first build is LOCAL-ONLY.** It does not yet support SMB or Tailscale.

## Download APK
Open Actions > Build Android APK > the newest successful run > Artifacts > NAS-EPUB-Reader-debug-APK.
Download the artifact ZIP and extract app-debug.apk.

Sources are published without NAS passwords, Tailscale keys or other private credentials.

## Build locally
Requires JDK 17, Android SDK 35 and Gradle 8.10.2.
Run: gradle :app:assembleDebug
