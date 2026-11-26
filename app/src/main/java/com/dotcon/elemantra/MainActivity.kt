package com.dotcon.elemantra

import android.annotation.SuppressLint
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Hosts the ELEMANTRA game (assets/www/index.html) in a full-screen WebView.
 *
 * The HTML is served unmodified from the APK under a virtual https origin, which is what
 * lets ES modules, import maps, blob: iframes and localStorage behave exactly as they do
 * in a normal browser. Third-party libraries the page pulls from CDNs (three.js, Tailwind,
 * Google Fonts) are answered from assets/vendor when bundled (see tools/vendor_web_deps.py),
 * and otherwise fall through to the network.
 */
class MainActivity : ComponentActivity() {

    private data class VendorEntry(val file: String, val mime: String)

    private lateinit var root: FrameLayout
    private var webView: WebView? = null
    private var lastBackPress = 0L

    /** normalised CDN URL -> bundled file */
    private val vendor = HashMap<String, VendorEntry>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        root = FrameLayout(this).apply { setBackgroundColor(BG_COLOR) }
        setContentView(root)

        // A browser letterboxes the page around a camera notch unless the page opts in with
        // viewport-fit=cover. This page does not, so pad around the cutout to get the same result.
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val cut = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            v.setPadding(cut.left, cut.top, cut.right, cut.bottom)
            WindowInsetsCompat.CONSUMED
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val now = SystemClock.elapsedRealtime()
                if (now - lastBackPress < 2000L) {
                    finishAndRemoveTask()
                } else {
                    lastBackPress = now
                    Toast.makeText(this@MainActivity, "Press back again to exit", Toast.LENGTH_SHORT).show()
                }
            }
        })

        loadVendorManifest()
        createWebView()
        hideSystemBars()
    }

    // ---------------------------------------------------------------- WebView

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView() {
        val wv = WebView(this)
        wv.setBackgroundColor(BG_COLOR)
        wv.overScrollMode = View.OVER_SCROLL_NEVER
        wv.isVerticalScrollBarEnabled = false
        wv.isHorizontalScrollBarEnabled = false
        wv.isHapticFeedbackEnabled = false
        wv.setOnLongClickListener { true } // no text-selection / context menu in a game

        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true                 // localStorage (game settings)
            mediaPlaybackRequiresUserGesture = false // background music / loading video
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            useWideViewPort = true                   // honour <meta name="viewport">
            loadWithOverviewMode = false
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            textZoom = 100                           // ignore the system font scale: keep the design pixel-identical
            cacheMode = WebSettings.LOAD_DEFAULT
        }

        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                return try {
                    intercept(request)
                } catch (e: Exception) {
                    Log.w(TAG, "Intercept failed for ${request.url}", e)
                    null
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                // Only the top-level page is guarded; iframes (the blob: arena) must load freely.
                if (!request.isForMainFrame) return false
                val u = request.url
                return !(u.scheme == "https" && u.host == APP_HOST)
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                Log.e(TAG, "WebView render process gone (crashed=${detail.didCrash()}); restarting")
                root.post {
                    if (!isFinishing && !isDestroyed) {
                        destroyWebView()
                        createWebView()
                    }
                }
                return true
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                Log.d(TAG, "console: ${m.message()} (${m.sourceId()}:${m.lineNumber()})")
                return true
            }
        }

        root.addView(
            wv,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        webView = wv
        wv.loadUrl("https://$APP_HOST/index.html")
    }

    private fun destroyWebView() {
        webView?.let { wv ->
            root.removeView(wv)
            wv.stopLoading()
            wv.webViewClient = WebViewClient()
            wv.destroy()
        }
        webView = null
    }

    // ---------------------------------------------------------------- request serving

    private fun intercept(request: WebResourceRequest): WebResourceResponse? {
        if (request.method != "GET") return null
        val uri = request.url

        if (uri.scheme == "https" && uri.host == APP_HOST) {
            var path = uri.path ?: "/"
            if (path.isEmpty() || path == "/") path = "/index.html"
            if (path.contains("..")) return notFound()
            return openAsset("www$path", mimeFor(path), cors = false) ?: notFound()
        }

        val entry = vendor[normalize(uri.toString())] ?: return null
        return openAsset("vendor/${entry.file}", entry.mime, cors = true)
    }

    private fun openAsset(assetPath: String, mime: String, cors: Boolean): WebResourceResponse? {
        return try {
            val stream = assets.open(assetPath)
            val headers = HashMap<String, String>()
            if (cors) headers["Access-Control-Allow-Origin"] = "*"
            val isText = mime.startsWith("text/") || mime == "application/json" || mime == "image/svg+xml"
            WebResourceResponse(mime, if (isText) "utf-8" else null, 200, "OK", headers, stream)
        } catch (e: FileNotFoundException) {
            null
        } catch (e: IOException) {
            Log.w(TAG, "Could not open asset $assetPath", e)
            null
        }
    }

    private fun notFound() = WebResourceResponse(
        "text/plain", "utf-8", 404, "Not Found", HashMap(), ByteArrayInputStream(ByteArray(0))
    )

    private fun mimeFor(path: String): String =
        when (path.substringAfterLast('.', "").lowercase()) {
            "html", "htm" -> "text/html"
            "js", "mjs" -> "text/javascript"
            "css" -> "text/css"
            "json" -> "application/json"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "svg" -> "image/svg+xml"
            "woff2" -> "font/woff2"
            "woff" -> "font/woff"
            "ttf" -> "font/ttf"
            "mp3" -> "audio/mpeg"
            "mp4" -> "video/mp4"
            "glb" -> "model/gltf-binary"
            "wasm" -> "application/wasm"
            else -> "application/octet-stream"
        }

    /** Drops the #fragment and gives host-only URLs a trailing slash (https://x.com -> https://x.com/). */
    private fun normalize(url: String): String {
        var u = url.substringBefore('#')
        val schemeEnd = u.indexOf("://")
        if (schemeEnd > 0) {
            val slash = u.indexOf('/', schemeEnd + 3)
            val query = u.indexOf('?', schemeEnd + 3)
            if (slash == -1 && query == -1) u += "/"
        }
        return u
    }

    private fun loadVendorManifest() {
        try {
            val text = assets.open("vendor/manifest.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
            val arr = JSONObject(text).getJSONArray("entries")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                vendor[normalize(o.getString("url"))] =
                    VendorEntry(o.getString("file"), o.optString("mime", "application/octet-stream"))
            }
            Log.i(TAG, "Offline libraries bundled: ${vendor.size}")
        } catch (e: FileNotFoundException) {
            Log.i(TAG, "No bundled libraries; the page will load them from their CDNs")
        } catch (e: Exception) {
            Log.w(TAG, "Could not read vendor manifest; falling back to CDNs", e)
        }
    }

    // ---------------------------------------------------------------- window / lifecycle

    private fun hideSystemBars() {
        val c = WindowCompat.getInsetsController(window, window.decorView)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c.hide(WindowInsetsCompat.Type.systemBars())
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onPause() {
        super.onPause()
        webView?.onPause()
        webView?.pauseTimers()
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
        webView?.resumeTimers()
        hideSystemBars()
    }

    override fun onDestroy() {
        destroyWebView()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "Elemantra"
        const val APP_HOST = "appassets.androidplatform.net"
        const val BG_COLOR = 0xFF130B0F.toInt()
    }
}
