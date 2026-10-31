# Changelog
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
