# NAS EPUB Reader for Android

Android EPUB bookshelf app with read-only SMB2/SMB3 network import.

## v0.3.1 (EPUB XML compatibility fix)
- Replaced desktop-JDK-specific XML parser configuration with Android XmlPullParser for EPUB container.xml and OPF manifest.
- Preserved book metadata extraction, cover recognition, chapter ordering, and limited safe ZIP extraction.
- Rejects DTD declarations and nonstandard XML entities, preventing external entity processing.
- Fix targets the Android error `http://apache.org/xml/features/disallow-doctype-decl` after a successful SMB EPUB file listing.
- The fix is source/build verified; real-device EPUB decoding must still be verified.
- Note: current CI debug signing keys are ephemeral. If Android rejects an upgrade, uninstall the old debug build first. This resets cached books and SMB settings.

## v0.3 (cloud-built)
- Configure SMB host, share name, and credentials once. No need to type a subdirectory path.
- Select **フォルダ選択** to open an interactive directory browser after authenticating.
- Browse direct children inside the configured share, open nested directories and move to parent.
- Select **このフォルダを読み取り先に設定** to persist the folder, then scan EPUB only inside that folder.
- Browse files and folders, with EPUB indicators. Browser lists immediate children; EPUB scanning is still recursive (depth 5, up to 350 entries).
- Switch selected folders without altering the NAS. Locally cached EPUB books from other SMB folders remain stored but are hidden from the current NAS library.
- **Limitation:** Shares such as docker/personal_folder/rom still need to be entered by name in the NAS settings. Automatic enumeration of server-level share names is not included in v0.3.

## v0.2
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
