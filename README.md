# ALRADHY Universal Bridge · v0.1.0 (development)

Android Mobile, Android TV and browser-based LAN bridge. This branch holds a functional *development prototype*, not a production-secure release.

## Already implemented
- Android host with local web control panel.
- Six-digit pairing PIN displayed on the host and explicit host-side approval.
- In-memory 30-minute-idle access tokens; per-IP PIN attempt limiting.
- Clipboard/text transfer requiring user action; incoming text copied to Android clipboard, optionally inserted through user-enabled IME.
- TV Back/Home/D-pad via optional Android Accessibility Service (D-pad needs Android 13+), media/volume best-effort and optional root keyevent fallback.
- Browser remote/clipboard hub with no browser install, a mobile controller, and TV launcher.
- GitHub Actions split APKs + SHA256SUMS + build info; Termux build/download helper.

## Important limitations
**UNENCRYPTED PROTOTYPE:** v0.1 uses HTTP on a LAN. Do not use on guest/public Wi-Fi; do not send passwords or sensitive material. Wi-Fi peers could intercept content. Production requires TLS/WSS, certificate trust and security review. Browser clipboard read/write may be unavailable over HTTP; manual paste remains available. This is **not** yet silent background clipboard synchronization. Air Mouse, audio-to-text and QR are later milestones. No universal hardware Power/CEC guarantee.

## Use
1. Install the TV APK and/or mobile APK from the successful workflow artifact.
2. Open the host app, start Bridge, keep both devices on same trusted LAN.
3. Note the local URL and six-digit PIN from the host screen.
4. From Safari/Chrome/PC, visit the URL, enter PIN, and approve pairing on host.
5. To use D-pad without root, enable the app's Accessibility Service explicitly on the TV (Android 13+). For remote text entry activate ALRADHY IME and focus a field; otherwise incoming text goes to clipboard.

The Android packages are separate variants: mobile=com.alradhy.universalbridge.mobile and tv=com.alradhy.universalbridge.tv. Builds are Android **debug-signed**, not release/update-safe across environments.
