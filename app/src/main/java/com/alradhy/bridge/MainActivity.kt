package com.alradhy.bridge

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private lateinit var clipboardManager: ClipboardManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            addJavascriptInterface(AndroidBridge(), "AndroidBridge")
            webViewClient = WebViewClient()
            loadUrl("https://your-bridge-server.onrender.com")
        }
        setContentView(webView)

        clipboardManager.addPrimaryClipChangedListener {
            val clipText = clipboardManager.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
            if (clipText.isNotEmpty()) {
                webView.evaluateJavascript("javascript:syncClipboardFromApp('$clipText');", null)
            }
        }
    }

    inner class AndroidBridge {
        @JavascriptInterface
        fun setClipboard(text: String) {
            runOnUiThread {
                val clip = ClipData.newPlainText("ALRADHY_CLIP", text)
                clipboardManager.setPrimaryClip(clip)
            }
        }

        @JavascriptInterface
        fun simulateKeyPress(keyCode: Int) {
            val instrumentation = android.app.Instrumentation()
            try {
                instrumentation.sendKeyDownUpSync(keyCode)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
