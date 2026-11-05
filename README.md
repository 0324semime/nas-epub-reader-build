# NAS EPUB Thor v0.5.4 alpha5 — LEFT page 1, RIGHT page 2

## Change requested after confirmed image-based EPUB reading
- **Physical lower display = left first page (1P)**; **physical upper display = right second page (2P)**.
- Fixed-layout EPUB display now maps the first image index to the lower/left page and the immediately following index to the upper/right page. Preserves FIT_CENTER full-page rendering, no cropping, screen rotation and page decode.
- Text-only EPUB mode paginates the *lower left first*, then starts the *upper right* exactly at the left page's continuation cursor. Next advances to the end of the upper/right page.
- Next and previous continue moving one pair (two page images or two blocks of text). Previously stored image-page pair index, bookmark and progress remain valid.
- Reader page labels, progress and Aa menu now describe **left ① → right ②** correctly.
- This is a page-order change only. No edits to EPUB parser, Tailscale, SMB, display orientation, cache, or the standard NAS EPUB Reader app.
- Requires on-device check of the two page positions; official image/book details are already confirmed from the user's alpha4 test.

## Previous release notes
# NAS EPUB Thor v0.5.3 alpha4 — fixed-layout picture pages

## Actual on-device bug and confirmed cause
The user reported a photo showing right/top and left/bottom panels filled with repeated "麻雀1年目の教科書" text instead of the book's real prose. The published e-book is a **fixed-layout, non-searchable**, 176-page illustrated work, while the old Thor renderer turned XHTML `<title>` metadata into fake prose and ignored the page images. The ~178 spine pages are consistent with a page-image based EPUB. The photo confirmed that previous rotation/page-area issues were solved.

## This build
- Add `FixedEpubPage.java` to inspect the EPUB's extracted XHTML for page image references (`<img src>`, SVG `<image href/xlink:href>` and CSS image URLs), with safe relative path resolution and bounds.
- Detect an image-based spine by inspecting early chapters. When image layout is detected, **render the actual full-page images**, not extracted text: top screen displays right page, bottom screen the following left page.
- `FIT_CENTER` maintains the original printed-page aspect ratio and prevents cropping on both differently sized displays. Bitmap decoding is sampled to moderate memory usage.
- Each next/previous action advances by **two physical pages**. The page number and restored image position are saved separately from the reflow text cursor; bookmarks point to image page pairs.
- Hide the native vertical text surfaces in image mode; continue to use normal Japanese vertical reflow pagination for text EPUBs.
- Strip the XHTML `<head>` block before invoking Html.fromHtml for genuine text books, so metadata titles are not repeated as content.
- Keep previous lower screen -90°/0°/+90° orientation control, independent font sizes and existing Thor Presentation management.

## Limits and validation
- The **real EPUB file has not been supplied** and the image-mode trigger/reference type is inferred from the screenshot and published book specs. The APK build can confirm compilation, not that the actual scanned pages load. The first real-device check is to confirm the footer says `画像の本` and actual book-page artwork appears.
- Image mode supports raster JPEG, PNG, WebP, GIF and BMP page references; vector-only SVG pages, embedded data URLs and DRM-protected EPUBs need further work. Mixed-layout EPUBs may need fallback handling.
- The full EPUB needs to have been downloaded/extracted within the Thor app (offline).
- For image-based pages, letterboxing on the larger screen is intentional to maintain the printed page aspect; independent text size is not applicable to scanned page images.
- The main branch and stable v0.4.6 are unchanged.

## Earlier releases
# NAS EPUB Thor — v0.5.2 alpha3: visible right/left pages

## Corrected from an actual Thor photograph
- Both displays appeared almost empty. The lower display UI was rendered 90 degrees sideways, with tiny text at an edge.
- Add a `ThorDisplayFrame` that rotates the **entire lower Presentation content** and, importantly, **swaps the measured width and height before rotation**. Default rotation is -90°, based on the user's device photograph.
- In the reading Aa menu, allow setting lower display rotation to -90°, 0° or +90° without changing the main display orientation. The setting persists.
- Minimize the lower screen header/footer, reclaiming the panel for actual text. Next/previous and main settings remain accessible on the upper display.
- Correct page pagination: skip empty/whitespace-only EPUB sections and **fill a page across consecutive XHTML spine files** rather than producing almost-blank physical pages for image/title fragments. A single multi-chapter page has a precise continuation cursor for the other display.
- Show rendered right/left character counts in the footers to help distinguish missing EPUB text from a drawing/layout problem.
- Preserve the upper=right, lower=left Japanese vertical reading sequence with independent 14–26sp sizes; keep the sample display option and local EPUB support.
- Scope: the Thor-specific `feature/thor-dual-screen` branch only. The original Android app is not modified.

## Validation
- GitHub Actions compiles Android app and embedded Tailscale on Android ARM64.
- The orientation fix and visible EPUB text must be confirmed on physical Thor; the build alone cannot validate firmware display rotation.
- Image-rich EPUB chapters still cannot be rendered faithfully by the text-only dual renderer; the ordinary one-screen reader remains available.

## Earlier notes
# NAS EPUB Thor — v0.5.1 alpha2 (right/left spread)

## First priority: complete the two physical page surfaces
- **Top physical display = RIGHT page (read first)**, **bottom physical display = LEFT page (read next)**, the Japanese right-to-left spread arrangement.
- New `ThorPageView` computes how many characters fit on each individual physical screen. Japanese text is painted top-to-bottom, with columns progressing right-to-left. No fake equal-width split: the smaller bottom screen displays fewer characters, without automatically shrinking them.
- The same native character-placement stepper drives both rendering and pagination, avoiding text overlaps/omissions between the right and left pages.
- Previous/next still traverse a *pair* of physical pages; long-term reading history uses chapter+UTF-16 position for continuity.
- A new **no-NAS-required spread preview** is available from the Thor mode drawer: it shows test novel text simultaneously on the two displays. The preview always restarts from the first page.
- Both layouts are available in the reading Aa control: **right/left vertical** (default) or **top/bottom horizontal** (legacy). Font sizes are individually adjustable for the top and bottom panels from 14–26sp.
- The top page uses a subtle left seam; the bottom page uses a right seam, like facing pages of a book. Page labels explicitly indicate RIGHT/LEFT.
- Android DisplayManager+Presentation controls the actual separate lower display; if it cannot be opened, return to the ordinary single-screen reader.
- Rooting is not required. Actual lower-screen mapping, touch controls and Japanese punctuation layout still require a physical Thor test.

## Current known limitations
- Alpha 2 is **text-first**, not a fully faithful EPUB renderer: ruby, illustrations, vertical punctuation variants, Latin tate-chu-yoko, exact kinsoku, publisher CSS and professional Japanese layout are not yet guaranteed. Standard horizontal reader is preserved for books with complex formatting.
- The two-screen reader expects a **downloaded** EPUB in this branch; unlike the later mainline versions, it does not yet support reading an undownloaded remote book through the two-screen renderer.
- This is an independent app ID `com.semime.nasepub.thor` and APK; it can coexist with the main NAS EPUB Reader. It has independent book storage and settings.
- Main branch and stable/v0.4.6 are not changed by these experimental commits.

## Previous release details
# NAS EPUB Thor — Mode A preview v0.5.0-alpha1

This is an **experimental branch** based on verified stable v0.4.6; stable is preserved under `stable/v0.4.6`.

## Main feature: top page → bottom page
- AYN Thor's second Android display is discovered using the public DisplayManager Presentation category, not a hardcoded display ID.
- Top/main display shows the first chunk of prose. Bottom/Presentation display shows the next chunk from the same EPUB chapter or its successor.
- **Next** advances past both chunks. **Previous** returns to the prior pair (page history is retained between app launches).
- **Aa** allows separate sizes (14–26sp) for top and bottom. Defaults top 20sp, bottom 18sp; bottom text is not squeezed to fit the same number of characters.
- Each chunk fits the actual available measured width/height using Android StaticLayout.
- Thor-specific bookmarks save chapter and text offset. Reading progress persists separately from one-screen WebView progress.
- Navigation drawer offers mode switch and a bottom display identification/font legibility test.
- On phones without a second usable presentation display, opens the existing one-screen reader.
- If a presentation cannot be shown, offers the one-screen reader as a fallback.
- Existing SMB/Tailscale, bookshelf, metadata previews and Wi-Fi-only EPUB body downloads are retained.

## Separate installation
This experimental APK uses a different applicationId, `com.semime.nasepub.thor`, and a visible name `NAS EPUB Thor`. It can coexist with the original stable app and does not erase its books. The new APK has **separate private app storage**, so NAS credentials, Tailscale login and downloaded EPUBs must be set up or imported again in this test app.

## Limits and testing
- This first milestone **renders prose text only**. Embedded illustrations, ruby, author CSS and vertical writing are not preserved in dual mode. The original one-screen WebView remains for rich formatting.
- Pages are generated from HTML text (Android Html.fromHtml) and the EPUB spine; the "chapter" count is the number of internal HTML resources.
- Test that the lower panel is enabled and not covered by another app or Android system panel.
- On-device confirmation is required for display mapping, lower touch controls, fit, page continuity and font legibility.
- Android's display-presentation policy may reject the second window on some firmware; a diagnostic and single-screen fallback are provided.
- The GitHub debug signature is not stable. The separate app ID protects stable, but future test APKs may need a reinstall.

## Next
Add ruby/vertical layout and embedded images with a DOM-aware pagination engine after validating dual-display discovery and text continuity on physical Thor.

## Earlier stable changes
# NAS EPUB Reader — v0.4.6 refined library and reading tools

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
- Replace premature teardown of bidirectional TCP streams with half-close and full drain: a probable cause for larg