# Changelog

## v0.5.3-thor-alpha4
- Fix repeated-book-title content by adding an image-based fixed-layout EPUB reader path.
- Render actual printed-page images on upper/right and lower/left displays.
- Page-turn and bookmarks work with image-page pairs; image progress persists.
- Strip HTML head metadata from reflowable text.
- Keep the validated lower-display rotated layout.

# Changelog

## v0.5.2-thor-alpha3
- Fix sideways AYN Thor lower-screen Presentation with a measured, rotated display container.
- Allow the user to choose -90, 0 or +90 degree lower-screen orientation.
- Skip EPUB empty/whitespace-only sections and paginate across short spine documents.
- Maximize page content on the lower display and expose rendered character-count diagnostics.
- Preserve stable NAS EPUB Reader, Tailscale and the Thor alpha2 appearance settings.

# Changelog

## v0.5.1-thor-alpha2
- Add native independent-page Japanese right-to-left vertical text rendering for top/right and bottom/left physical screens.
- Match UTF-16 character pagination to the same drawing layout on both displays, continuing exactly between pages.
- Add no-NAS-required sample spread preview accessible via Thor navigation.
- Add spread/horizontal mode selection and per-display typography adjustments.
- Label page positions and add subtle facing-page edges.
- Keep original Thor alpha1 reader and ordinary stable library isolated from main branch.

# Changelog

## v0.5.0-thor-alpha1
- Branch from stable/v0.4.6 without changing the stable APK.
- Implement Android Presentation lower-screen text reader and independent page splitting, plus per-display text size.
- Add display diagnostics, on-device lower-panel font test and fallbacks.
- Persist upper-page cursor, history and Thor-specific bookmarks.
- Change application ID to permit side-by-side installation with stable v0.4.6.
- Retain Wi-Fi-only EPUB downloading, SMB browsing and embedded Tailscale.

# Changelog
## v0.4.6
- Remove Tailscale route text from library main header; keep network controls in the drawer.
- Add persistent per-book multi-bookmark and bookmark navigation.
- Add EPUB spine-based table of contents and current-section find.
- Restore section + scroll position, report reading progress.
- Add light, sepia and dark reading themes, font size and margin preferences.
- Add library search by title/author and refine reading toolbar.
- Maintain proven 3-column bookshelf, cached covers and Wi-Fi-only EPUB downloads.

## v0.4.5
- New navigation sidebar with common settings and actions.
- Three-column vertical-scroll grid for book covers and metadata.
- Separate saved EPUBs from NAS-only books, with distinct status chips.
- Refresh typography, backgrounds, card appearance and empty-state screens.
- Maintain Tailscale SMB access, Wi-Fi-only download, offline reading and book long-press actions.

## v0.4.4
- Wi-Fi-gate every full EPUB download before and during SMB transfers, leaving cellular data for previews.
- Replace tap-to-download with cover long-press action menu.
- Add read, Wi-Fi download, local body deletion, device-only hide and restore, cached cover deletion, and book information actions.
- Keep local-only deletion safe: never send SMB delete/write calls.
- Preserve embedded Tailscale, cover preview, cached EPUB reading and NAS folder selection.

## v0.4.3
- Lightweight ZIP-range SMB preview: get book cover/title/author without downloading full EPUB.
- Cache book list, covers, metadata persistently; show previous library immediately at launch.
- Auto-fetch up to 12 preview covers in the background after refreshing the NAS index.
- Tap a preview book to download full EPUB only when needed.
- Retain v0.4.2 Go tsnet SMB transport and detailed error reporting.

## v0.4.2
- Expose detailed EPUB transfer failures and progress instead of only success/fail counters.
- Add a copyable diagnostic including nested SMB exceptions and Tailscale transport stats.
- Avoid closing proxied SMB TCP connections on first EOF; complete both transfer directions.
- Retain v0.4.1 saved Tailscale target and SMB connection diagnostics.

## v0.4.1
- Fix confusing Tailscale settings activation: start button saves enabled state and destination.
- Show actual persisted network route in the library and folder browser screens.
- Add Go tsnet direct TCP445 connectivity diagnostic from inside the embedded node.
- Preserve working SMB folder picker, cover cache and EPUB reader.

## v0.4.0 (experimental)
- Add Go tsnet Android AAR build to public GitHub Actions.
- Add in-app Tailscale login/start/stop/status screen.
- Tunnel SMBJ over 127.0.0.1 toward tailnet TCP port 445.
- Keep LAN connectivity and folder selection working without Android VPNService.
- Postpone dual-screen optimization.

## v0.3.1
- Fix EPUB XML parser compatibility on Android.

## v0.3.0
- NAS directory browser and selected EPUB source folder.
