package com.alradhy.universalbridge

import android.accessibilityservice.AccessibilityService
import android.app.*
import android.content.*
import android.graphics.Color
import android.os.*
import android.provider.Settings
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.inputmethodservice.InputMethodService
import android.webkit.WebView
import android.widget.*
import android.media.AudioManager
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val PORT = 18080
private val MAIN = Handler(Looper.getMainLooper())

private fun randomToken(): String = ByteArray(32).also { SecureRandom().nextBytes(it) }
    .joinToString("") { "%02x".format(it.toInt() and 0xff) }

data class PendingPair(
    val id: String, val client: String, val created: Long,
    @Volatile var decision: String = "pending",
    @Volatile var token: String = ""
)
data class PairedSession(val client: String, @Volatile var lastUsed: Long)

object BridgeRuntime {
    @Volatile var server: BridgeHttpServer? = null
    @Volatile var pairingPin: String = newPin()
    @Volatile var lastClipboard: String = ""
    @Volatile var clipboardRevision: Long = 0L
    @Volatile var rootEnabled = false
    val pending = ConcurrentHashMap<String, PendingPair>()
    val sessions = ConcurrentHashMap<String, PairedSession>()
    private val tries = ConcurrentHashMap<String, MutableList<Long>>()
    @Volatile var onPairingRequest: ((PendingPair) -> Unit)? = null

    private fun newPin(): String = (100000 + SecureRandom().nextInt(900000)).toString()
    fun rotatePin() { pairingPin = newPin(); pending.clear() }
    fun revokeAll() { sessions.clear(); pending.clear(); rotatePin() }

    fun beginPair(pin: String, name: String, ip: String): PendingPair? {
        val now = System.currentTimeMillis()
        val attempts = tries.computeIfAbsent(ip) { mutableListOf() }
        synchronized(attempts) {
            attempts.removeAll { now - it > 60000L }
            if (attempts.size >= 5) return null
            attempts.add(now)
        }
        if (pin != pairingPin) return null
        val count = pending.values.count { it.decision == "pending" }
        if (count >= 8) return null
        val p = PendingPair(UUID.randomUUID().toString(), name.take(45), now)
        pending[p.id] = p
        onPairingRequest?.invoke(p)
        return p
    }

    fun pairStatus(id: String): PendingPair? {
        val p = pending[id] ?: return null
        if (System.currentTimeMillis() - p.created > 120000L) {
            pending.remove(id)
            return null
        }
        return p
    }

    fun approve(id: String) {
        val p = pairStatus(id) ?: return
        if (p.decision != "pending") return
        val token = randomToken()
        sessions[token] = PairedSession(p.client, System.currentTimeMillis())
        p.token = token
        p.decision = "approved"
    }
    fun reject(id: String) { pending[id]?.decision = "rejected" }

    fun authorized(authHeader: String?): Boolean {
        val text = authHeader ?: return false
        if (!text.startsWith("Bearer ")) return false
        val token = text.removePrefix("Bearer ").trim()
        val current = sessions[token] ?: return false
        val now = System.currentTimeMillis()
        if (now - current.lastUsed > 30L * 60 * 1000) {
            sessions.remove(token)
            return false
        }
        current.lastUsed = now
        return true
    }

    fun updateClipboard(context: Context, text: String): Boolean {
        if (text.length > 12000) return false
        lastClipboard = text
        clipboardRevision++
        return try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("ALRADHY Bridge", text))
            true
        } catch (_: Exception) { false }
    }

    fun localIp(): String {
        return try {
            val addresses = mutableListOf<String>()
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val n = interfaces.nextElement()
                if (!n.isUp || n.isLoopback) continue
                val addrs = n.inetAddresses
                while (addrs.hasMoreElements()) {
                    val a = addrs.nextElement()
                    if (a is Inet4Address && !a.isLoopbackAddress) addresses.add(a.hostAddress ?: "")
                }
            }
            addresses.firstOrNull { it.startsWith("192.168.") }
                ?: addresses.firstOrNull { it.startsWith("10.") }
                ?: addresses.firstOrNull { it.startsWith("172.") }
                ?: "127.0.0.1"
        } catch (_: Exception) { "127.0.0.1" }
    }

    /**
     * TV Safe Mode direct paste. Android clipboard handles Unicode including Arabic;
     * never interpolate the payload into an Android shell command.
     *
     * KEYCODE_PASTE dispatch success is NOT proof that the target app accepted
     * the text. Only a foreground focused editable field can consume this event.
     */
    fun pasteToFocusedField(context: Context, text: String): Pair<String, String> {
        val copied = updateClipboard(context, text)
        if (!copied) return Pair("failed", "Failed to put Unicode text on Android clipboard")
        if (BuildConfig.FLAVOR != "tv" || !rootEnabled)
            return Pair("clipboard_only", "Text copied to TV clipboard. Root Remote is disabled.")
        return try {
            val command = "input keyevent " + KeyEvent.KEYCODE_PASTE.toString()
            // Constant command; no user data is passed to su, preserving Arabic text.
            val process = ProcessBuilder("su", "-c", command).start()
            val finished = process.waitFor(3, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                Pair("clipboard_only", "Paste command timed out. Text remains on TV clipboard.")
            } else if (process.exitValue() == 0) {
                Pair("paste_key_sent", "Paste key sent to focused field (target acceptance cannot be verified)")
            } else {
                Pair("clipboard_only", "Paste key rejected by root. Text remains on TV clipboard.")
            }
        } catch (_: Exception) {
            Pair("clipboard_only", "Paste key unavailable. Text remains on TV clipboard.")
        }
    }

    fun sendKey(context: Context, key: String): Pair<Boolean, String> {
        val actions = mapOf(
            "BACK" to AccessibilityService.GLOBAL_ACTION_BACK,
            "HOME" to AccessibilityService.GLOBAL_ACTION_HOME,
            "UP" to 16, "DOWN" to 17, "LEFT" to 18, "RIGHT" to 19, "OK" to 20
        )
        val eventCodes = mapOf(
            "BACK" to 4, "HOME" to 3, "UP" to 19, "DOWN" to 20,
            "LEFT" to 21, "RIGHT" to 22, "OK" to 23,
            "PLAY_PAUSE" to 85, "VOL_UP" to 24,
            "VOL_DOWN" to 25, "MUTE" to 164, "POWER" to 26, "MENU" to 82
        )
        if (!eventCodes.containsKey(key)) return Pair(false, "Unknown key")
        val accessibility = BridgeAccessibilityService.instance
        val action = actions[key]
        if (accessibility != null && action != null &&
            (key == "BACK" || key == "HOME" || Build.VERSION.SDK_INT >= 33)) {
            val latch = CountDownLatch(1)
            var result = false
            MAIN.post {
                result = try { accessibility.performGlobalAction(action) } catch (_: Exception) { false }
                latch.countDown()
            }
            latch.await(1500, TimeUnit.MILLISECONDS)
            if (result) return Pair(true, "Accessibility action executed")
        }
        if (key == "PLAY_PAUSE") {
            return try {
                val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
                audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
                Pair(true, "Media event dispatched (player response depends on app)")
            } catch (_: Exception) { Pair(false, "Media dispatch failed") }
        }
        if (key == "VOL_UP" || key == "VOL_DOWN" || key == "MUTE") {
            try {
                val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val actionVol = when (key) {
                    "VOL_UP" -> AudioManager.ADJUST_RAISE
                    "VOL_DOWN" -> AudioManager.ADJUST_LOWER
                    else -> AudioManager.ADJUST_TOGGLE_MUTE
                }
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, actionVol, 0)
                return Pair(true, "Android stream adjustment requested (HDMI volume may not change)")
            } catch (_: Exception) { /* try root fallback below */ }
        }
        if (!rootEnabled) return Pair(false, "Remote navigation requires TV Root Remote opt-in; not available without it")
        val code = eventCodes[key] ?: return Pair(false, "Unsupported")
        return try {
            val process = ProcessBuilder("su", "-c", "input keyevent " + code).start()
            val finished = process.waitFor(3, TimeUnit.SECONDS)
            if (!finished) { process.destroyForcibly(); Pair(false, "Root command timeout") }
            else if (process.exitValue() == 0) Pair(true, "Root keyevent executed")
            else Pair(false, "Root command failed or permission denied")
        } catch (_: Exception) { Pair(false, "Root command unavailable") }
    }
}

class BridgeHttpServer(private val app: Context) : NanoHTTPD(PORT) {
    private fun reply(code: Response.Status, data: JSONObject): Response =
        NanoHTTPD.newFixedLengthResponse(code, "application/json; charset=utf-8", data.toString()).apply {
            addHeader("Cache-Control", "no-store")
            addHeader("X-Content-Type-Options", "nosniff")
        }
    private fun okay(vararg parts: Pair<String, Any?>): JSONObject {
        val j = JSONObject().put("ok", true)
        for (part in parts) j.put(part.first, part.second)
        return j
    }
    private fun error(code: Response.Status, message: String): Response =
        reply(code, JSONObject().put("ok", false).put("error", message))

    override fun serve(s: IHTTPSession): Response {
        return try {
            val path = s.uri
            if (s.method == Method.GET && (path == "/" || path == "/index.html")) {
                val html = app.assets.open("web/index.html").bufferedReader().use { it.readText() }
                return NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
                    .apply { addHeader("Cache-Control", "no-store"); addHeader("X-Content-Type-Options", "nosniff") }
            }
            if (s.method == Method.GET && path == "/api/status") {
                return reply(Response.Status.OK, okay(
                    "name" to "ALRADHY Universal Bridge",
                    "platform" to if (BuildConfig.FLAVOR == "tv") "Android TV" else "Android",
                    "version" to "0.1.4",
                    "accessibility" to (BridgeAccessibilityService.instance != null),
                    "imeReady" to BridgeImeService.isReady(),
                    "root" to BridgeRuntime.rootEnabled
                ))
            }
            val obj: JSONObject = if (s.method == Method.POST) {
                val len = s.headers["content-length"]?.toLongOrNull() ?: 0L
                if (len > 18000L) return error(Response.Status.BAD_REQUEST, "Request too large")
                val parsed = HashMap<String, String>()
                s.parseBody(parsed)
                JSONObject(parsed["postData"] ?: "{}")
            } else JSONObject()

            if (s.method == Method.POST && path == "/api/pair/request") {
                val request = BridgeRuntime.beginPair(
                    obj.optString("pin"), obj.optString("name", "Browser"),
                    s.remoteIpAddress ?: "unknown"
                ) ?: return error(Response.Status.FORBIDDEN, "Invalid PIN or pairing limit reached")
                return reply(Response.Status.OK, okay("requestId" to request.id, "status" to "pending"))
            }
            if (s.method == Method.GET && path == "/api/pair/status") {
                val pair = BridgeRuntime.pairStatus(s.parameters["id"]?.firstOrNull().orEmpty())
                    ?: return error(Response.Status.NOT_FOUND, "Pair request expired")
                return reply(Response.Status.OK, okay("status" to pair.decision,
                    "token" to if (pair.decision == "approved") pair.token else ""))
            }
            if (!BridgeRuntime.authorized(s.headers["authorization"]))
                return error(Response.Status.UNAUTHORIZED, "Pairing required or session expired")

            when (path) {
                "/api/state" -> {
                    if (s.method != Method.GET) return error(Response.Status.BAD_REQUEST, "GET expected")
                    val devices = JSONArray()
                    for (device in BridgeRuntime.sessions.values) {
                        devices.put(JSONObject().put("client", device.client).put("lastUsed", device.lastUsed))
                    }
                    reply(Response.Status.OK, okay(
                        "devices" to devices, "clipboard" to BridgeRuntime.lastClipboard,
                        "revision" to BridgeRuntime.clipboardRevision,
                        "accessibility" to (BridgeAccessibilityService.instance != null),
                        "imeReady" to BridgeImeService.isReady(),
                        "root" to BridgeRuntime.rootEnabled
                    ))
                }
                "/api/clipboard" -> {
                    if (s.method != Method.POST) return error(Response.Status.BAD_REQUEST, "POST expected")
                    val text = obj.optString("text", "")
                    if (text.isEmpty() || text.length > 12000)
                        return error(Response.Status.BAD_REQUEST, "Text must be 1-12000 chars")
                    val sent = BridgeRuntime.updateClipboard(app, text)
                    reply(Response.Status.OK, okay("deliveredToClipboard" to sent,
                        "revision" to BridgeRuntime.clipboardRevision))
                }
                "/api/text" -> {
                    if (s.method != Method.POST) return error(Response.Status.BAD_REQUEST, "POST expected")
                    val text = obj.optString("text", "")
                    if (text.isEmpty() || text.length > 12000)
                        return error(Response.Status.BAD_REQUEST, "Text must be 1-12000 chars")
                    val direct = if (BridgeImeService.isReady()) BridgeImeService.remoteEdit("type", text)
                                 else Pair(false, "IME not ready")
                    if (direct.first) {
                        reply(Response.Status.OK, okay(
                            "delivery" to "ime",
                            "message" to direct.second,
                            "unicode" to true,
                            "inputConnectionAccepted" to true
                        ))
                    } else {
                        val delivery = BridgeRuntime.pasteToFocusedField(app, text)
                        reply(Response.Status.OK, okay(
                            "delivery" to delivery.first,
                            "message" to delivery.second,
                            "unicode" to true,
                            "inputConnectionAccepted" to false
                        ))
                    }
                }
                "/api/keyboard" -> {
                    if (s.method != Method.POST) return error(Response.Status.BAD_REQUEST, "POST expected")
                    if (BuildConfig.FLAVOR != "tv") return error(Response.Status.FORBIDDEN, "TV receiver only")
                    val op = obj.optString("op", "")
                    val inserted = obj.optString("text", "")
                    if (op !in setOf("type", "backspace", "enter", "left", "right"))
                        return error(Response.Status.BAD_REQUEST, "Invalid keyboard operation")
                    if (inserted.length > 256 || (op == "type" && inserted.isEmpty()))
                        return error(Response.Status.BAD_REQUEST, "Invalid text length")
                    val response = BridgeImeService.remoteEdit(op, inserted)
                    if (response.first) reply(Response.Status.OK, okay(
                        "accepted" to true, "message" to response.second))
                    else error(Response.Status.FORBIDDEN, response.second)
                }
                "/api/key" -> {
                    if (s.method != Method.POST) return error(Response.Status.BAD_REQUEST, "POST expected")
                    val result = BridgeRuntime.sendKey(app, obj.optString("key"))
                    if (result.first) reply(Response.Status.OK, okay("result" to result.second))
                    else error(Response.Status.FORBIDDEN, result.second)
                }
                "/api/disconnect" -> {
                    if (s.method != Method.POST) return error(Response.Status.BAD_REQUEST, "POST expected")
                    val token = s.headers["authorization"]?.removePrefix("Bearer ")?.trim().orEmpty()
                    BridgeRuntime.sessions.remove(token)
                    reply(Response.Status.OK, okay("disconnected" to true))
                }
                else -> error(Response.Status.NOT_FOUND, "Unknown endpoint")
            }
        } catch (_: Exception) {
            error(Response.Status.BAD_REQUEST, "Invalid request")
        }
    }
}

class BridgeService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        val channel = "bridge_session"
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(
                channel, "ALRADHY Bridge", NotificationManager.IMPORTANCE_LOW))
        }
        val intent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val note = Notification.Builder(this, channel)
            .setContentTitle("ALRADHY Universal Bridge")
            .setContentText("Local bridge active. Open to approve device pairing.")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(intent).build()
        startForeground(1201, note)
        try {
            BridgeRuntime.server?.stop()
            BridgeRuntime.server = BridgeHttpServer(applicationContext).also { it.start(2500, false) }
        } catch (_: Exception) { stopSelf() }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onDestroy() { BridgeRuntime.server?.stop(); BridgeRuntime.server = null; super.onDestroy() }
}

class BridgeAccessibilityService : AccessibilityService() {
    companion object { @Volatile var instance: BridgeAccessibilityService? = null }
    override fun onServiceConnected() { super.onServiceConnected(); instance = this }
    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }
}

class BridgeImeService : InputMethodService() {
    @Volatile private var inputStarted = false

    companion object {
        @Volatile var instance: BridgeImeService? = null

        fun isReady(): Boolean {
            val ime = instance ?: return false
            return ime.inputStarted && ime.currentInputConnection != null
        }

        /**
         * Must be enabled by the user as the TV's current Android input method.
         * This uses InputConnection so Arabic/mixed Unicode is not shell-escaped
         * and is committed to the ACTUAL focused editor, not to the clipboard.
         */
        fun remoteEdit(op: String, text: String = ""): Pair<Boolean, String> {
            val ime = instance ?: return Pair(false, "Select ALRADHY Bridge Keyboard in Android TV input method settings first")
            if (!ime.inputStarted) return Pair(false, "Open and focus a text field on TV while ALRADHY Bridge Keyboard is selected")
            val latch = CountDownLatch(1)
            var succeeded = false
            var details = "No active editor"
            MAIN.post {
                try {
                    val conn = ime.currentInputConnection
                    if (conn == null || !ime.inputStarted) {
                        details = "Input field is no longer focused"
                    } else {
                        val info = ime.currentInputEditorInfo
                        val inputType = info?.inputType ?: 0
                        val kind = inputType and android.text.InputType.TYPE_MASK_CLASS
                        val variation = inputType and android.text.InputType.TYPE_MASK_VARIATION
                        val secureField = (kind == android.text.InputType.TYPE_CLASS_TEXT &&
                            (variation == android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                             variation == android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                             variation == android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)) ||
                            (kind == android.text.InputType.TYPE_CLASS_NUMBER &&
                             variation == android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD)
                        if (secureField) {
                            details = "Remote input is disabled in password fields"
                        } else {
                            succeeded = when (op) {
                                "type" -> text.isNotEmpty() && text.length <= 12000 && conn.commitText(text, 1)
                                "backspace" -> {
                                    conn.deleteSurroundingTextInCodePoints(1, 0) ||
                                        (conn.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL)) &&
                                         conn.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL)))
                                }
                                "enter" -> {
                                    val action = info?.imeOptions?.and(android.view.inputmethod.EditorInfo.IME_MASK_ACTION)
                                        ?: android.view.inputmethod.EditorInfo.IME_ACTION_NONE
                                    if (action != android.view.inputmethod.EditorInfo.IME_ACTION_NONE &&
                                        action != android.view.inputmethod.EditorInfo.IME_ACTION_UNSPECIFIED)
                                        conn.performEditorAction(action)
                                    else conn.commitText("\n", 1)
                                }
                                "left", "right" -> {
                                    val code = if (op == "left") KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
                                    val down = conn.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
                                    val up = conn.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
                                    down && up
                                }
                                else -> false
                            }
                            details = if (succeeded) "Android InputConnection accepted the edit request"
                                      else "Focused editor rejected the edit request"
                        }
                    }
                } catch (_: Exception) {
                    details = "Input method error; try focusing the TV text field again"
                } finally {
                    latch.countDown()
                }
            }
            if (!latch.await(2000, TimeUnit.MILLISECONDS)) return Pair(false, "Input method timed out")
            return Pair(succeeded, details)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }
    override fun onStartInput(attribute: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        inputStarted = true
    }
    override fun onFinishInput() {
        inputStarted = false
        super.onFinishInput()
    }
    override fun onDestroy() {
        inputStarted = false
        if (instance === this) instance = null
        super.onDestroy()
    }
    override fun onCreateInputView(): View {
        return TextView(this).apply {
            text = "ALRADHY Bridge Remote Keyboard ✓\nType from your paired tablet or browser"
            setPadding(24, 16, 24, 16)
            setBackgroundColor(Color.rgb(17, 23, 34))
            setTextColor(Color.WHITE)
            textSize = 16f
        }
    }
}

class MainActivity : Activity() {
    private lateinit var container: LinearLayout
    private lateinit var statusText: TextView
    private var showingId: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            if (::statusText.isInitialized) {
                statusText.text = (if (BridgeRuntime.server != null) "● Bridge online" else "○ Bridge offline") +
                    "\nhttp://" + BridgeRuntime.localIp() + ":" + PORT +
                    "\nPairing PIN: " + BridgeRuntime.pairingPin +
                    "\nConnected sessions: " + BridgeRuntime.sessions.size +
                    (if (BuildConfig.FLAVOR == "tv") "\nRemote Keyboard: " +
                        (if (BridgeImeService.isReady()) "Ready ✓" else "Not active (choose Bridge Keyboard & focus a text field)") else "")
            }
            checkPending()
            handler.postDelayed(this, 1500)
        }
    }

    private fun label(value: String, size: Float = 16f): TextView =
        TextView(this).apply {
            this.text = value
            textSize = size
            setTextColor(Color.WHITE)
            setPadding(10, 13, 10, 13)
            isFocusable = true
        }
    private fun button(text: String, action: () -> Unit) {
        val b = Button(this).apply {
            this.text = text
            isAllCaps = false
            setOnClickListener { action() }
            isFocusable = true
        }
        container.addView(b, LinearLayout.LayoutParams(-1, -2))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BridgeRuntime.rootEnabled = BuildConfig.FLAVOR == "tv" &&
            getSharedPreferences("bridge", MODE_PRIVATE).getBoolean("root", false)
        window.decorView.setBackgroundColor(Color.rgb(8, 11, 18))
        val scroller = ScrollView(this)
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(30, 26, 30, 30)
        }
        scroller.addView(container)
        setContentView(scroller)
        container.addView(label("ALRADHY\nUNIVERSAL BRIDGE", 28f))
        container.addView(label("Local-first • Trusted Wi-Fi only • v0.1.4", 14f))
        statusText = label("")
        container.addView(statusText)
        container.addView(label("Security: This prototype uses unencrypted local HTTP. Never share passwords or sensitive text.", 13f))
        button("▶ Start / Restart Local Bridge") { startHost() }
        button("■ Stop Bridge") { stopService(Intent(this, BridgeService::class.java)) }
        button("⟳ Rotate PIN and revoke all sessions") {
            BridgeRuntime.revokeAll()
            Toast.makeText(this, "All sessions revoked", Toast.LENGTH_SHORT).show()
        }
        button("☷ Open This Device's Web Dashboard") {
            openController("http://127.0.0.1:" + PORT)
        }
        val address = EditText(this).apply {
            hint = "TV address: 192.168.1.15"
            setText(getSharedPreferences("bridge", MODE_PRIVATE).getString("last_host", ""))
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
        }
        container.addView(address)
        button("🎮 Connect to TV / Other Bridge") {
            val ip = address.text.toString().trim()
            if (!Regex("^(192\\.168\\.|10\\.|172\\.(1[6-9]|2[0-9]|3[01])\\.)[0-9.]+$").matches(ip)) {
                Toast.makeText(this, "Enter a private LAN IPv4 address", Toast.LENGTH_LONG).show()
            } else {
                getSharedPreferences("bridge", MODE_PRIVATE).edit().putString("last_host", ip).apply()
                openController("http://" + ip + ":" + PORT)
            }
        }
        if (BuildConfig.FLAVOR == "tv") {
        container.addView(label("Bridge Remote Keyboard: choose it once as Android TV input method. This allows Arabic/English typing directly in a focused field. You can switch back to ALRADHY-KB anytime.", 14f))
        button("1 · Enable ALRADHY Bridge Keyboard in Android Settings") {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        button("2 · Select TV Keyboard (choose ALRADHY Bridge Keyboard)") {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        }
        container.addView(label("IME Ready: requires ALRADHY Bridge Keyboard selected AND a text field focused. Root Remote continues to control TV arrows independently.", 13f))
        button(if (BridgeRuntime.rootEnabled) "Root Remote: ON (tap to disable)" else "Root Remote: OFF (tap to opt in)") {
            val next = !BridgeRuntime.rootEnabled
            AlertDialog.Builder(this)
                .setTitle("Root Remote")
                .setMessage("Allow paired devices to send whitelisted Android key events with root? Use only on your own TV on trusted LAN. New status: " + (if (next) "ON" else "OFF"))
                .setPositiveButton(if (next) "Enable" else "Disable") { _, _ ->
                    BridgeRuntime.rootEnabled = next
                    getSharedPreferences("bridge", MODE_PRIVATE).edit().putBoolean("root", next).apply()
                    recreate()
                }.setNegativeButton("Cancel", null).show()
        }
        }
        startHost()
    }

    private fun openController(url: String) {
        startActivity(Intent(this, ControllerActivity::class.java).putExtra("url", url))
    }
    private fun startHost() {
        try {
            val service = Intent(this, BridgeService::class.java)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(service) else startService(service)
        } catch (ex: Exception) {
            Toast.makeText(this, "Bridge start failed: " + ex.message, Toast.LENGTH_LONG).show()
        }
    }

    private fun checkPending() {
        if (showingId != null) return
        val p = BridgeRuntime.pending.values.firstOrNull { it.decision == "pending" &&
            System.currentTimeMillis() - it.created < 120000L } ?: return
        showingId = p.id
        AlertDialog.Builder(this).setTitle("Device pairing request")
            .setMessage("Allow '" + p.client + "' to control this device and share clipboard text?\nOnly approve devices you recognize.")
            .setPositiveButton("Allow") { _, _ ->
                BridgeRuntime.approve(p.id); showingId = null
            }
            .setNegativeButton("Reject") { _, _ ->
                BridgeRuntime.reject(p.id); showingId = null
            }
            .setOnCancelListener { showingId = null }
            .show()
    }
    override fun onResume() {
        super.onResume()
        BridgeRuntime.onPairingRequest = { MAIN.post { checkPending() } }
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }
    override fun onPause() {
        BridgeRuntime.onPairingRequest = null
        handler.removeCallbacks(ticker)
        super.onPause()
    }
}

class ControllerActivity : Activity() {
    private lateinit var browser: WebView
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra("url") ?: "http://127.0.0.1:" + PORT
        val host = url.substringAfter("http://").substringBefore(":")
        if (!(host == "127.0.0.1" ||
              Regex("^(192\\.168\\.|10\\.|172\\.(1[6-9]|2[0-9]|3[01])\\.)[0-9.]+$").matches(host))) {
            finish(); return
        }
        browser = WebView(this)
        browser.settings.javaScriptEnabled = true
        browser.settings.domStorageEnabled = true
        browser.settings.allowFileAccess = false
        browser.settings.allowContentAccess = false
        setContentView(browser)
        browser.loadUrl(url)
    }
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::browser.isInitialized && browser.canGoBack()) browser.goBack() else super.onBackPressed()
    }
    override fun onDestroy() {
        if (::browser.isInitialized) browser.destroy()
        super.onDestroy()
    }
}
