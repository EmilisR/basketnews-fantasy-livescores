package com.elflive.app.data

import android.content.Context
import android.webkit.WebSettings

/**
 * The WebView's user agent with the WebView markers removed, so it reads like mobile Chrome.
 *
 * BasketNews only offers "Sign in with Google", and Google refuses to sign in when it sees an
 * embedded WebView ("; wv" and "Version/4.0" in the user agent). This goes against Google's
 * policy for embedded browsers, and Google may start blocking it at any time.
 */
object UserAgent {
    private val WV = Regex(";\\s*wv\\)")
    private val BUILD = Regex("\\s+Build/[^;)]*")
    private val VERSION = Regex("Version/\\d+(\\.\\d+)*\\s+")

    fun chromeLike(webViewUa: String): String = webViewUa
        .replace(WV, ")")
        .replace(BUILD, "")
        .replace(VERSION, "")

    fun get(context: Context): String = chromeLike(
        runCatching { WebSettings.getDefaultUserAgent(context) }.getOrDefault("Mozilla/5.0 (Linux; Android)"),
    )
}
