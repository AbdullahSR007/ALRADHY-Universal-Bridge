package com.alradhy.bridge

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.KeyEvent
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
            
            loadDataWithBaseURL(
                null,
                """
                <!DOCTYPE html>
                <html lang="ar" dir="rtl">
                <head>
                    <meta charset="UTF-8">
                    <title>ALRADHY Universal Bridge</title>
                    <style>
                        body { font-family: Tahoma, sans-serif; background: #080B12; color: #F5F7FA; padding: 20px; text-align: center; margin: 0; }
                        h1 { color: #4F7CFF; font-size: 24px; margin-bottom: 5px; }
                        .subtitle { color: #98A2B3; font-size: 14px; margin-bottom: 20px; }
                        .card { background: #111722; padding: 20px; border-radius: 16px; margin: 15px auto; max-width: 500px; box-shadow: 0 4px 15px rgba(0,0,0,0.6); border: 1px solid #1f293d; text-align: right; }
                        textarea { width: 100%; height: 80px; background: #080B12; color: #fff; border: 1px solid #2a364f; border-radius: 8px; padding: 10px; font-size: 14px; box-sizing: border-box; resize: none; }
                        .btn { background: #4F7CFF; color: #fff; border: none; padding: 10px 20px; font-weight: bold; border-radius: 8px; cursor: pointer; font-size: 14px; margin-top: 10px; display: inline-block; }
                        .remote-grid { display: grid; grid-template-columns: repeat(3, 70px); gap: 8px; justify-content: center; margin-top: 15px; }
                        .remote-btn { background: #1f293d; color: #F5F7FA; border: 1px solid #2a364f; padding: 12px; font-weight: bold; border-radius: 8px; cursor: pointer; text-align: center; font-size: 16px; }
                    </style>
                </head>
                <body>
                    <h1>ALRADHY Universal Bridge</h1>
                    <div class="subtitle">نصوصك .. أجهزتك .. في مكان واحد</div>
                    
                    <div class="card">
                        <h3 style="margin-top:0; color:#20C997;">مركز الحافظة المحلية</h3>
                        <textarea id="clipText" placeholder="اكتب أو الصق النص هنا لمزامنته..."></textarea>
                        <button class="btn" onclick="syncClipboard()">مزامنة وإرسال النص</button>
                    </div>

                    <div class="card" style="text-align: center;">
                        <h3 style="margin-top:0; color:#845EF7;">ريموت التحكم</h3>
                        <div class="remote-grid">
                            <div></div><div class="remote-btn" onclick="sendKey(19)">▲</div><div></div>
                            <div class="remote-btn" onclick="sendKey(21)">◀</div><div class="remote-btn" onclick="sendKey(66)" style="background:#4F7CFF;">OK</div><div class="remote-btn" onclick="sendKey(22)">▶</div>
                            <div></div><div class="remote-btn" onclick="sendKey(20)">▼</div><div></div>
                        </div>
                    </div>

                    <script>
                        function syncClipboard() {
                            const val = document.getElementById('clipText').value;
                            if(val) {
                                AndroidBridge.setClipboard(val);
                                alert("تم حفظ النص في حافظة الجهاز بنجاح!");
                            }
                        }
                        function sendKey(keyCode) {
                            AndroidBridge.simulateKeyPress(keyCode);
                        }
                    </script>
                </body>
                </html>
                """,
                "text/html",
                "UTF-8",
                null
            )
        }
        setContentView(webView)
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
                Thread {
                    try {
                        instrumentation.sendKeyDownUpSync(keyCode)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }.start()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
