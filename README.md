# NAS EPUB Reader — v0.4.8 online reading and library tabs

## v0.4.8 — Read without saving an EPUB
- Library tabs **本** (all accessible remote and saved books) and **保存済み** (offline saved only) replace the old stacked categories. Tap either tab to switch.
- On the **本** tab, a normal tap on a remote unsaved cover now opens an online reader, rather than requiring a full EPUB download.
- Online reading uses SMB range requests to retrieve only the EPUB ZIP metadata/spine and the **current XHTML/HTML chapter**, plus selected referenced small images and stylesheets. It does **not** transfer the entire EPUB.
- Streamed chapters remain in disposable preview cache, not in the offline/saved bookshelf. A reader may reuse recently accessed chapters while online.
- Navigation, chapter progress, search, theme, bookmarks, and reading-position handling reuse the original reader.
- Long-press an unsaved book to select **オンラインで読む** or **本文をダウンロード（Wi-Fiのみ）**.
- Full EPUB offline download **remains Wi-Fi-only**. Online chapter loading may use mobile data and embedded Tailscale, so network usage may occur while reading.
- This is experimental for ZIP64/encrypted/complex EPUB layouts. Referenced images/CSS are attempted with reasonable per-chapter size limits; some files may not render until a full EPUB is saved. No cross-book global prefetching.
- Cached chapter data can be deleted in cache management, but is not equivalent to an offline download.
- Existing saved books remain available offline. Stable v0.4.6 and the Thor experimental branch remain untouched.

## Previous version: v0.4.7 Bookmark Jewel app icon

## v0.4.7 — Bookmark Jewel launcher icon
- Install the user-selected **Bookmark Jewel** app icon: gold bookmark with an emerald gem, deep forest-green background.
- Source artwork optimized as 256x256 WebP for Android launcher usage and bundled in `app/src/main/res/drawable-nodpi/bookmark_jewel.webp`.
- Configure both `android:icon` and `android:roundIcon` in the Android manifest.
- No changes to EPUB bookshelf, navigation, Wi-Fi-only body downloads, SMB, Tailscale, or reading features.
- Keep the original application ID and private storage; note that GitHub ephemeral debug signing may still block over-installation.
- AYN Thor experimental branch remains separate.

## Previous release: v0.4.6 refined library and reading tools

## v0.4.6 — Reading experience refresh
- Remove the verbose embedded Tailscale route explanation from the top of the library; keep Tailscale settings/diagnostics inside the left navigation drawer.
- Show only the selected NAS folder in a small unobtrusive library breadcrumb. Show transfer progress or errors when meaningful.
- Add library title/author search, keeping the 3-column downloaded/NAS grid design.
- Add **multiple persistent bookmarks per book**, captured at chapter/file and scroll offset, toggle via top-right diamond, list and jump from bottom "しおり" button. Long press bookmark entry to remove it.
- Add a **table of contents/section list** based on the EPUB's spine; jump to the selected internal HTML file. Actual semantic chapter names depend on EPUB content.
- Add **current HTML section text search** via WebView built-in find, with previous/next results.
- Save the current reading section and scroll offset per book, auto-restore on reopening; show approximate chapter-relative reading percentage and position.
- Add reading appearance dialog (Aa): light/sepia/dark backgrounds, font size 70–180%, and narrow/normal/wide text margins, using injected CSS for XHTML/HTML files up to 6MB.
- Keep EPUB JavaScript disabled; external network loads blocked, file URL navigation restricted to the extracted book folder.
- Keep offline reading, SMB/Tailscale, lightweight cover cache and Wi-Fi-only body downloading.
- Limitations: bookmarks track chapter/file plus vertical scroll pixels (may shift when layout/font changes), section search is not whole-book search, publisher CSS can override some appearance details, no cloud synchronization/annotations yet.
- Older debug APK signatures may differ and require uninstalling before installation, which deletes app-private data.

## Previous version: v0.4.5 refreshed library UI

## v0.4.5 — Design refresh
- Introduce a simple, soft-white/teal bookshelf interface, rounded cards and subtler status area.
- **Hamburger menu** (top left) with library, refresh, NAS settings, folder browser, embedded Tailscale, EPUB import, reading preferences, cover cache manager, hidden-book recovery and app information.
- Simplify header to hamburger, library title and refresh button.
- **Three books per row** in a vertically scrolling grid.
- Split books into **保存済み** (downloaded and local EPUBs) and **NASの本** (remote EPUBs not downloaded).
- Each grid item has cover artwork, local/NAS status chip, up to two title lines, author, tap, and long-press menu.
- Persist old navigation and book controls: local offline reading, lightweight cover preview, SMB over embedded Tailscale, and Wi-Fi-only full book downloads.
- Reading preferences dialog stores preferred text size; cache management can delete preview covers while preserving downloaded books and NAS originals.
- Hide/restore controls are accessible from the sidebar, without requiring a banner.
- EPUB on NAS is still read-only; local deletion never removes the NAS original.

## Previous version: v0.4.4 Wi-Fi-only books and long-press menu

## v0.4.4
- **Full book EPUB transfers are manual and allowed only on an active Wi-Fi network.** Mobile data (5G/LTE) can still browse the NAS, obtain small EPUB cover previews and display previously downloaded books.
- Long-press a book cover to open a context menu. For non-downloaded NAS books: **本文をダウンロード（Wi-Fiのみ）**, **本棚から非表示**, **表紙キャッシュを削除**, **書籍情報**.
- For downloaded books: **本を読む**, **本文を削除（端末のみ）**, **本棚から非表示**, **表紙キャッシュを削除**, **書籍情報**. For locally imported EPUBs, show read, local delete and info only.
- Tapping an already downloaded cover immediately opens its reader. Tapping a cover without a downloaded body explains how to download rather than using mobile data.
- Download eligibility is checked before the network operation and again for every received SMB read chunk. If Wi-Fi disconnects mid-transfer the download aborts and temporary files are cleaned up.
- Delete removes only the **app-local copy** and its local chapter resume position. It **never removes the NAS original**. The lightweight cover preview is retained after a full-book deletion.
- Hide affects only this device's bookshelf; the "非表示の本を戻す" control restores hidden books.
- Cached book covers, metadata and offline EPUBs remain available on mobile data and offline.
- Download over Wi-Fi may still use embedded Tailscale to access the NAS through its private IP.
- One-time full EPUB transfer speed depends on Wi-Fi quality; there is no background automatic download or partial-resume implementation yet.
- Dual-screen support remains postponed.

## Previous version: v0.4.3 low-bandwidth cover preview

## v0.4.3 — Fast bookshelf for throttled mobile data
- Immediately show cached NAS book index on launch, even before contacting NAS.
- On SMB folder refresh, list EPUB filenames first, then automatically fetch preview covers and title/author in the background.
- Preview retrieval uses SMB random-access reads of the ZIP central directory and **only** the EPUB container.xml, package OPF, and cover image. It does NOT download the full EPUB.
- Independently cache lightweight covers and metadata in Android private storage. Tapping a preview downloads the full EPUB and then opens the existing offline reader.
- If partial ZIP extraction is unsupported (ZIP64, special compression or unusual layout), the title-only file list remains usable and tapping downloads the full book.
- Preserve cached full books, separate from lightweight preview entries. An already-downloaded EPUB opens without network traffic.
- Re-read NAS directory index when user taps 本棚更新.
- No new server or NAS-side installation required. Only SMB2/3 over embedded Tailscale.
- First-time full book download is STILL limited by the actual mobile connection speed. This release accelerates bookshelf/cover recognition, not physical transfer bandwidth.
- Public GitHub debug builds currently have ephemeral signatures: uninstall/reinstall may delete cached books/settings. Stable signing requires a securely stored signing key.

## Previous version: v0.4.2 EPUB transfer diagnostics

## Fixes in v0.4.2
- Retain full SMB file download exceptions in a pop-up when a single download or cover batch fails.
- The batch cover importer now reports which EPUB failed and its complete exception-cause chain, with an Android clipboard copy action.
- Show live transferred bytes during EPUB file download, then switch to a parsing status after the download finishes.
- Separate "EPUB received" from "EPUB parsed": transfer errors and metadata errors are distinguishable.
- Add SMB network traffic telemetry to Go tsnet state (accepted connection count, bytes sent to NAS, bytes received from NAS, last transport error).
- Replace premature teardown of bidirectional TCP streams with half-close and full drain: a probable cause for large SMB file transfer failures.
- Keep the working NAS share browser, local bookshelf, and Tailscale login UX unchanged.
- Debug logs never include passwords or Tailscale authorization tokens. The diagnostic may include the configured private NAS address and the filename.
- **Test plan:** on mobile data, keep the embedded Tailscale SMB target 192.168.3.4, refresh shelf, tap the EPUB or cover sync, and if failed copy the new detailed diagnostic instead of sending only the count.

## Previous version: v0.4.1 embedded Tailscale connectivity diagnostic

## Fixes in v0.4.1
- Explicitly display the saved SMB connection route on both bookshelf and folder selection screens.
- A checked but unsaved Tailscale checkbox no longer silently appears to activate the tunnel: UI warns "未保存".
- Pressing the Tailscale start button automatically saves the checkbox state and NAS tailnet destination.
- Add a separate diagnostic action that uses the Go tsnet node to open a TCP connection to the target NAS port 445.
- The error "failed to connect to /100.x.x.x (port 445) from /10.x.x.x" indicates the **direct** SMB route was used, not the embedded loopback tunnel.
- Keep LAN SMB settings separate from Tailscale target: LAN host 192.168.3.4, Tailscale target 100.64.209.92 (verify this is your NAS device).
- For real Tailscale SMB failures, the new probe reports whether port 445 is reachable before SMB user authentication.

## Previous release: v0.4 embedded Tailscale preview
## What works
- Local SMB2/3 browsing and EPUB extraction from previous releases
- Android Java SMBJ connects through Go tsnet's app-local 127.0.0.1-only TCP/445 relay when embedded mode is enabled
- Start/stop/login/connection status UI, without Android VPNService
- NAS SMB credentials stored with AndroidKeyStore
- Tailnet identity stored only in app private files
- Wi-Fi/LAN mode without Tailscale remains available

## Set up embedded Tailscale
1. Keep working LAN settings in 接続設定.
2. Open 内蔵Tailscale設定 and enable SMB接続に内蔵Tailscaleを使用.
3. Enter NAS Tailscale IP (100.x.x.x) or full *.ts.net MagicDNS name. A private 192.168.x.x address only works with the relevant Tailscale subnet route.
4. Tap 設定を保存 and Tailscaleを起動 / 再確認.
5. Tap 接続状態を更新, then ログインURLを開く. Authenticate this app's Tailscale node in a browser.
6. After status shows connected, return to the shelf and tap 本棚更新.
7. Test using mobile data only after Wi-Fi works.

## Technical design
- Native Go tsnet.Server embedded as an arm64 Android AAR via gomobile.
- Only 127.0.0.1 local TCP listener; no system-wide VPN.
- Target limited to tailnet 100.64.0.0/10, *.ts.net, or RFC1918 addresses reachable via subnet routers.
- SMB user/password authentication remains in SMBJ; SMB access is read-only.

## Limitations
- ARM64 only, initially intended for AYN Thor.
- Experimental: real-device login and SMB transit not yet validated.
- Android native Go bridge makes the APK larger.
- Independent node identity from GameTrade Mobile.
- Debug APK may require uninstall of previous builds, clearing private book cache/settings.
- Dual-screen postponed.
- No Tailscale auth keys or NAS secrets are in the public source.
