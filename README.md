# ALRADHY Universal Bridge · v0.1.1 (development)

Android Mobile, Android TV and browser-based LAN bridge. This branch holds a functional *development prototype*, not a production-secure release.

## Already implemented
- Android host with local web control panel.
- Six-digit pairing PIN displayed on the host and explicit host-side approval.
- In-memory 30-minute-idle access tokens; per-IP PIN attempt limiting.
- Clipboard/text transfer requiring user action; incoming text copied to Android clipboard, optionally inserted through user-enabled IME.
- Only the TV APK declares optional Accessibility and IME services; the mobile APK does not declare these sensitive services. TV Back/Home/D-pad require optional Accessibility (D-pad needs Android 13+) or explicit root opt-in.
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

## v0.1.1 reduced-permission mobile flavor
The mobile APK does not declare Accessibility or Android IME services. They are registered only in the TV variant. Launcher adaptive icon is custom ALRADHY artwork. This reduces risk indicators but DOES NOT guarantee that Google Play Protect will allow side-loaded development APKs. Development/debug signing remains unchanged per build and may prevent seamless upgrades. Never disable Play Protect to install this app.

## v0.1.2 TV Safe Mode — Play Protect remediation
The TV APK no longer declares Accessibility or IME services (nor SMS/notification-listener privileges). The mobile APK already omitted them. This responds to Google's documented internet-sideloading protection for APKs declaring ACCESSIBILITY. TV remote navigation now requires explicitly opted-in Root Remote on an owner-controlled TV; without root, clipboard-only and browser panel remain functional. Remote text goes to clipboard, not an input field. Keep Play Protect ON. Installation acceptance is controlled by Google; successful build does not guarantee acceptance. This prototype's HTTP transport is not encrypted and should not carry sensitive data. Debug signatures are not stable for upgrades.

## v0.1.3 — Smart Unicode Paste (TV Safe Mode)
- The tablet/browser Keyboard tab now uses `/api/text` to place the text (Arabic, English, mixed, spaces, emoji and newlines) in Android's clipboard and dispatch `KEYCODE_PASTE=279` using **only** the previously opt-in Root Remote on TV.
- Do NOT use `input text`: it is unreliable for Arabic/Unicode. Shell arguments never contain user-provided clipboard text.
- User must **focus an editable text field on the TV first**. We cannot claim that the receiving app accepted the paste without Accessibility; the acknowledgement is `paste_key_sent` (not `inserted`). Some password/secure apps deny paste. If root or the key event is unavailable, the clipboard remains updated for manual paste.
- Same TV safe manifest without Accessibility or IME permissions; no Play Protect bypass. LAN HTTP is unencrypted. Do not send secrets.

## v0.1.4 — Dual keyboard workflows (Send + Direct)
- **إرسال** / Send: sends the entire textbox via IME commitText when ALRADHY Bridge Keyboard is active; otherwise falls back to existing clipboard/root paste, reporting a distinct result.
- **مباشر** / Direct: live keystroke stream from paired phone/tablet/browser into the focused TV editor; handles Unicode Arabic/English, Delete, Enter, arrows. A separate entry field intentionally clears between committed input events to avoid duplicate characters during composition.
- **One-time TV setup**: enable **ALRADHY Bridge Keyboard** in Android Settings > Languages & input > Current keyboard and select it; then focus the desired TV input field. This is an independent optional IME and does not modify the installed ALRADHY-KB application. Switch back to ALRADHY-KB using the Android keyboard picker when finished.
- **Root alone is insufficient for reliable Arabic**; standard shell `input text` or `input keyevent PASTE` cannot guarantee Unicode direct entry. The official InputConnection.commitText is used for actual focused field edits.
- Neither the TV nor mobile manifest declares Accessibility/notification listener/SMS permissions. TV declares the official BIND_INPUT_METHOD protected service only. Play Protect behavior cannot be guaranteed.
- Remote text is blocked for password fields, and all bridge traffic still uses unencrypted HTTP on trusted LAN. Never submit secrets.

## v0.2.0 Native Wi-Fi Remote (experimental)
The mobile/tablet APK now connects to the Android TV Remote Service v2 directly via TLS ports 6466/6467, using a code displayed on the TV. It can relay commands from a browser via the mobile/tablet HTTP bridge. The TV APK is not required for native control. No additional TV keyboard is installed or selected. Root is not required. Android TV Remote Service may temporarily switch the system IME while receiving text, then restore the old keyboard when the IME session ends. ALRADHY-KB is neither changed nor uninstalled. TV remote v2 text input is an interoperability experiment and needs on-device verification. Never send sensitive text via the prototype's unencrypted HTTP browser relay. Pairing identity is encrypted with Android Keystore; the remote server certificate is pinned after code verification. 

## v0.2.1 — critical Arabic / UTF-8 transport fix
Root cause: Bridge's web fetch() sent POST `Content-Type: application/json` **without** `charset=UTF-8`. NanoHTTPD 2.3.1 defaults to US-ASCII for this request type, corrupting Arabic *before* protobuf encoding. Now every JSON POST declares `application/json; charset=UTF-8`. On any text endpoint, the server refuses missing UTF-8 instead of silently corrupting text. The mobile Wi-Fi panel includes a self-echo Arabic sample to verify transport *before* sending text to the TV. Neither TV keyboard nor TV configuration is modified. On-device Arabic rendering is not yet independently verified.

## v0.2.2 Wi-Fi browser and Arabic live typing fixes
- The **browser must open** the mobile/tablet Bridge gateway IP (port 18080), not TV Bridge IP. The browser independently pairs with the mobile/tablet Bridge PIN and must be approved in the mobile/tablet host app. The native TV Remote connection is held by the mobile/tablet Bridge app, not by Safari/Chrome.
- A visible gateway URL and a warning on TV-hosted pages make this distinction clear. Connection closure diagnostics surface in the Wi-Fi panel.
- A native TV Remote v2 session disables the seven-second handshake read timeout after activation to prevent disconnects during quiet periods.
- Live typing attempts to stream Arabic composition input before the space bar, including prefix changes, using Unicode codepoint-safe deltas. Some sender keyboards may not emit their composition data before commit; those may still require pressing Space. Stop waits for queued edits.
- Existing ALRADHY-KB on the TV remains untouched. No extra TV keyboard is registered by Bridge.

## v0.2.3: Wi-Fi Remote now easy to find
The mobile/tablet app displays **📡 فتح ريموت Wi-Fi الآن** at the top of its home screen. This always opens its own localhost-native Wi-Fi Remote, not the TV HTTP Bridge (which does not own the native TLS remote connection). The older other-Bridge button is relabeled **Clipboard only**. The dashboard shows a prominent Wi-Fi Remote navigation button with guidance when opened from the TV-hosted Bridge. Native connection status shows directly on the mobile main screen. ALRADHY-KB is untouched.

## v0.2.4 — Wi-Fi Remote status in Devices & Sessions
Wi-Fi Remote now has its **own status stat in the Devices & Sessions section** beside Root Remote and Accessibility: Connected/Paired/Disconnected with TV IPv4. A direct button opens the native Wi-Fi pairing and remote interface on mobile/tablet Bridge hosts, and on TV-hosted pages explains which gateway to use. It does not depend on root or accessibility being ON. The existing ALRADHY-KB TV keyboard is untouched.

## v0.2.5 — Native Wi-Fi Remote Connection reset diagnostics
The Remote v2 TLS connection after successful pairing now uses the standard RemoteDeviceInfo unknown2="1", package_name="atvremote", app_version="1.0.0". This fixes a handshake interoperability mismatch in v0.2.4 (previously unknown2="Android"). All socket writes are serialized on a stable mutex to avoid protobuf framing interleaving. Connection failure messages now identify the stage (TLS port 6466, TV configure, active features, ready) and exception. The TV's RemoteError message only disconnects the app if error.value is true. Pairing keys and TV ALRADHY-KB are preserved unchanged. Device verification is still required.

## v0.2.6 — Browser as native TV Wi-Fi Remote client through tablet Bridge
- The browser (Safari, Chrome, PC, iPhone) connects to `http://<tablet-IP>:18080` on trusted local LAN. **No direct browser-to-TV pairing**: new authorized `/api/remote/{status,key,text,live,direct/end}` routes share the same `AtvWifiRemote` singleton and TV identity already stored in the mobile/tablet Bridge app. TV Android Remote Service v2 connects only from the tablet. No TV APK upgrade or new keyboard.
- Approving a browser on the tablet once grants a 30-day trusted browser session, persisted as a SHA-256 token hash in the tablet app's private SharedPreferences and as a bearer token in browser localStorage; expiry and revocation enforced. A browser can reconnect after closing the browser/app without TV re-pairing. This local HTTP transport is still **unencrypted**; use a trusted local network only, not for secrets.
- External browsers hide TV pairing/disconnect UI and present a clear shared-session explanation; they can directly use the remote D-pad, Home, text and live Arabic typing. The tablet app's local WebView retains advanced TV pairing controls. Existing TV pairing and **ALRADHY-KB remain unchanged**.
