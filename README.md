# NAS EPUB Reader for Android

Android EPUB bookshelf app with read-only SMB2/SMB3 network import.

## v0.2 (cloud-built)
- **NAS settings**: hostname/IP address, SMB share, optional subfolder, SMB user/password/domain.
- SMB2/SMB3 enumeration, including EPUB files in nested folders (depth up to 5, max 350 files).
- Tap a remote filename to download it into a local bookshelf and open the book.
- Fetch up to 12 missing EPUB covers at a time by pressing the cover sync button. Network EPUB files are downloaded and cached on the device; there is no direct streaming EPUB view.
- Local bookshelf displays EPUB covers, titles, authors; tap to read chapters; text zoom and chapter-level resume.
- Password is encrypted by AndroidKeyStore AES-GCM and stays on-device. No credentials or private hostnames are embedded in the public source.
- No remote write/delete calls (read-only SMB usage).

## Known limitations
- Embedded Tailscale is **not yet implemented**. LAN-only at present; external connectivity requires separate Tailscale or a different reachable private network.
- AYN Thor dual-screen layout and complete vertical-text rendering are not yet optimized.
- EPUB books up to 150 MB; no DRM support. EPUB extraction can take storage.
- When a NAS EPUB changes, its cached local copy is not automatically refreshed yet.
- First use requires entering the SMB share name and credentials; host IP is pre-filled as an editable example.
- Complex EPUB layouts should be tested on physical devices.

## Download an APK
Go to Actions > Build Android APK > latest successful run > Artifacts > NAS-EPUB-Reader-debug-APK, extract app-debug.apk and install on Android.

## Build
Requires Android SDK 35 and JDK 17. The GitHub Actions build uses Gradle 8.10.2 and Android Gradle Plugin 8.7.3.
Run: gradle :app:assembleDebug

## Source code
Public code must never include secrets, NAS share credentials, Firebase files, Tailscale auth keys or other private data.
