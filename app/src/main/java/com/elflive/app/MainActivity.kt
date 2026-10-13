package com.elflive.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.elflive.app.core.LiveState
import com.elflive.app.data.SITE_ORIGIN
import com.elflive.app.data.UserAgent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** Full-screen WebView on fantasy.basketnews.com with the live-points overlay. */
class MainActivity : ComponentActivity() {
    private lateinit var web: WebView
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        web = WebView(this)
        with(web.settings) {
            javaScriptEnabled = true // only basketnews.com pages are ever loaded in this WebView
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            setGeolocationEnabled(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(false)
            // BasketNews only has "Sign in with Google", which Google blocks in WebViews.
            // Present the WebView as mobile Chrome so that login works (see UserAgent).
            userAgentString = UserAgent.chromeLike(userAgentString)
            javaScriptCanOpenWindowsAutomatically = false
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true) // Google sign-in
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            // Don't send "X-Requested-With: <app id>", which also marks requests as coming from an app.
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(web.settings, emptySet())
        }

        val supported = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
        if (supported) {
            WebViewCompat.addWebMessageListener(web, "elfNative", setOf(SITE_ORIGIN)) { _, message, sourceOrigin, isMainFrame, _ ->
                if (isMainFrame && sourceOrigin.toString().trimEnd('/') == SITE_ORIGIN) onPageMessage(message.data)
            }
            val script = assets.open("elf.js").bufferedReader().use { it.readText() }
            WebViewCompat.addDocumentStartJavaScript(web, script, setOf(SITE_ORIGIN))
        } else {
            Toast.makeText(this, R.string.webview_too_old, Toast.LENGTH_LONG).show()
        }

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (isAllowed(request.url)) return false
                openExternally(request.url)
                return true
            }

            override fun onPageFinished(view: WebView, url: String?) {
                pushState(engine.state.value)
            }
        }
        web.webChromeClient = WebChromeClient()

        val root = FrameLayout(this)
        root.addView(web, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(liveButton(), FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply {
            val m = dp(16)
            setMargins(m, m, m, m)
        })
        setContentView(root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else finish()
            }
        })

        if (savedInstanceState == null || web.restoreState(savedInstanceState) == null) {
            web.loadUrl("$SITE_ORIGIN/")
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { engine.state.collect { pushState(it) } }
                launch(Dispatchers.IO) {
                    engine.loadState()
                    // While the app is open, keep the numbers fresh. The service takes over during games.
                    while (true) {
                        if (!LiveService.running) {
                            val s = engine.tick()
                            if (s.active) LiveService.start(this@MainActivity)
                        }
                        delay(LiveService.POLL_MS)
                    }
                }
            }
        }
    }

    private fun onPageMessage(raw: String?) {
        val msg = runCatching { json.parseToJsonElement(raw ?: return).jsonObject }.getOrNull() ?: return
        when ((msg["type"] as? JsonPrimitive)?.content) {
            "ready" -> pushState(engine.state.value)
            "lineup" -> lifecycleScope.launch(Dispatchers.IO) {
                val stored = engine.handleSeen(
                    msg["variables"] as? JsonObject,
                    msg["data"] as? JsonObject,
                    (msg["path"] as? JsonPrimitive)?.content,
                )
                if (stored) {
                    val s = engine.tick(force = true)
                    if (s.active) LiveService.start(this@MainActivity)
                }
            }
        }
    }

    @Serializable
    private data class PageTeam(
        val id: String,
        val name: String,
        val total: Double,
        val live: Boolean,
        val fantasyRound: Int?,
        val supported: Boolean,
    )

    @Serializable
    private data class PageState(val updated: Long, val teams: List<PageTeam>)

    private fun pushState(s: LiveState?) {
        if (s == null || !::web.isInitialized) return
        val page = PageState(s.updated, s.teams.map { PageTeam(it.id, it.name, it.total, it.live, it.fantasyRound, it.supported) })
        val js = "(function(s){if(location.origin==='$SITE_ORIGIN'&&window.__elfSetState)window.__elfSetState(s);})(" +
            json.encodeToString(page) + ")"
        web.post { web.evaluateJavascript(js, null) }
    }

    private fun isAllowed(uri: Uri): Boolean {
        val host = uri.host?.lowercase() ?: return false
        if (uri.scheme != "https") return false
        return host == "basketnews.com" || host.endsWith(".basketnews.com") ||
            // Google sign-in (the only login BasketNews offers) has to happen in this WebView
            // so that the session cookie ends up here, not in Chrome.
            GOOGLE.matches(host) || host == "accounts.youtube.com"
    }

    private fun openExternally(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, uri.toString(), Toast.LENGTH_SHORT).show()
        }
    }

    private fun liveButton() = TextView(this).apply {
        text = getString(R.string.live_button)
        setTextColor(Color.WHITE)
        textSize = 14f
        setPadding(dp(16), dp(10), dp(16), dp(10))
        background = GradientDrawable().apply {
            cornerRadius = dp(24).toFloat()
            setColor(Color.parseColor("#E53935"))
        }
        elevation = dp(6).toFloat()
        contentDescription = getString(R.string.open_live_points)
        setOnClickListener { startActivity(Intent(this@MainActivity, TeamsActivity::class.java)) }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    private companion object {
        /** google.com, accounts.google.com, google.lt, ... */
        val GOOGLE = Regex("^([a-z0-9-]+\\.)*google\\.[a-z]{2,3}(\\.[a-z]{2})?$")
    }
}
