# NAS EPUB Reader — v0.4.1 embedded Tailscale connectivity diagnostic

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
