package io.github.oinzc.flamecontractor.execution

import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import io.github.oinzc.flamecontractor.execution.webview.DesktopWebViewFactory

class M1LoginActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var statusLabel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val density = resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density + 0.5f).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#18181B"))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime(),
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }

        statusLabel = TextView(this).apply {
            text = "M1 登录配置"
            textSize = 14f
            setTextColor(Color.WHITE)
        }
        topBar.addView(statusLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        fun addBtn(label: String, onClick: () -> Unit) {
            val btn = Button(this).apply {
                text = label
                isAllCaps = false
                textSize = 12f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#27272A"))
                setOnClickListener { onClick() }
            }
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(8) }
            topBar.addView(btn, lp)
        }

        addBtn("刷新") { webView.loadUrl(DesktopWebViewFactory.CHAT_URL) }
        addBtn("检查登录态") { checkLoginState() }

        root.addView(topBar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        webView = WebView(this)
        root.addView(webView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f,
        ))

        setContentView(root)

        DesktopWebViewFactory.configureDesktopWebView(
            webView = webView,
            blockDeepLinks = true,
            onPageFinished = { checkLoginState() },
        )
        webView.loadUrl(DesktopWebViewFactory.CHAT_URL)
    }

    override fun onPause() {
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    private fun checkLoginState() {
        webView.evaluateJavascript(
            "(function(){ return !!document.querySelector('.conversationConversationListwrapper'); })()",
        ) { raw ->
            val loggedIn = raw == "true"
            statusLabel.text = if (loggedIn) "已登录（可关闭页面测离屏 Service）" else "请扫码登录抖音"
            Log.i("FlameM1", "M1_LOGIN_CHECK loggedIn=$loggedIn")
        }
    }
}
