# Changelog

## v0.3.1
- Fix EPUB import crashing on unsupported Android DocumentBuilderFactory XML security features.
- Use Android-native XmlPullParser to parse EPUB container and OPF metadata.
- Restrict XML event counts/size and reject DTDs and unsupported entities.
- Keep SMB browser, selected folder, and the EPUB library UI unchanged.

## v0.3.0
- Folder picker after SMB connection: directories and files displayed in a navigation list.
- Breadcrumb-like path header, parent navigation, refresh and clear error states.
- Select a directory as the reading root; save choice across launches.
- Automatic EPUB search when the selected folder is saved; switching folder hides unrelated cached NAS books.
- SMB share selection remains manual; no embedded Tailscale yet.

## v0.2.0
- Add SMB2/SMB3 browsing of NAS shares using SMBJ.
- Add editable NAS settings and password storage through Android KeyStore.
- Add remote EPUB discovery, on-demand download, and batch cover caching.
- Preserve v0.1 local EPUB bookshelf and reader functionality.
- Leave embedded Tailscale for a later release.

## v0.1.0
- First debug APK built using public GitHub Actions.
- Local EPUB import, cover extraction, and reader.
