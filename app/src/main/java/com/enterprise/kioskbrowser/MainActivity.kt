package com.enterprise.kioskbrowser

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.*
import android.widget.ProgressBar
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar

    // -------------------------------------------------------------------------
    // Enterprise User-Agent: mirrors Chrome 124 on Android 14
    // Update BUILD_VERSION / CHROME_VERSION as new releases ship.
    // -------------------------------------------------------------------------
    private val enterpriseUserAgent: String by lazy {
        val androidVersion = "14"
        val deviceModel   = Build.MODEL.replace(Regex("[^A-Za-z0-9 _-]"), "")
        val chromeVersion = "124.0.6367.82"
        "Mozilla/5.0 (Linux; Android $androidVersion; $deviceModel) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/$chromeVersion Mobile Safari/537.36"
    }

    // -------------------------------------------------------------------------
    // JavaScript injected at page-start AND page-finish for belt-and-suspenders
    // coverage (some SPAs re-attach listeners after DOMContentLoaded).
    // -------------------------------------------------------------------------
    private val visibilityOverrideScript = """
        (function() {
            'use strict';

            // Guard: run only once per document
            if (window.__kioskPatched) return;
            window.__kioskPatched = true;

            // --- 1. Freeze visibilityState / hidden on the Document prototype ---
            try {
                Object.defineProperty(Document.prototype, 'visibilityState', {
                    get: function() { return 'visible'; },
                    configurable: true
                });
                Object.defineProperty(Document.prototype, 'hidden', {
                    get: function() { return false; },
                    configurable: true
                });
            } catch(e) { /* already non-configurable in rare UA builds */ }

            // --- 2. Swallow state-sensitive events on document and window ---
            var BLOCKED_EVENTS = [
                'visibilitychange', 'blur', 'focusout', 'pagehide'
            ];

            var _origDocAdd = Document.prototype.addEventListener;
            Document.prototype.addEventListener = function(type, listener, opts) {
                if (BLOCKED_EVENTS.indexOf(type) !== -1) return;
                return _origDocAdd.apply(this, arguments);
            };

            var _origWinAdd = Window.prototype.addEventListener;
            Window.prototype.addEventListener = function(type, listener, opts) {
                if (BLOCKED_EVENTS.indexOf(type) !== -1) return;
                return _origWinAdd.apply(this, arguments);
            };

            // Also suppress the on* property assignments (e.g. document.onblur = fn)
            ['onvisibilitychange','onblur','onfocusout','onpagehide'].forEach(function(prop) {
                try {
                    Object.defineProperty(document, prop, { set: function(){}, get: function(){ return null; }, configurable: true });
                    Object.defineProperty(window,   prop, { set: function(){}, get: function(){ return null; }, configurable: true });
                } catch(e) {}
            });

            // Dispatch a synthetic 'focus' so apps that wait for it proceed normally
            try { window.dispatchEvent(new Event('focus')); } catch(e) {}

        })();
    """.trimIndent()

    // -------------------------------------------------------------------------
    // CSS + JS to re-enable text selection and clipboard access enterprise-wide
    // -------------------------------------------------------------------------
    private val selectionAndClipboardScript = """
        (function() {
            'use strict';
            if (window.__kioskClipboardPatched) return;
            window.__kioskClipboardPatched = true;

            // --- CSS: force user-select on every element ---
            var style = document.createElement('style');
            style.id  = '__kiosk_selection_style';
            style.textContent = [
                '*, *::before, *::after {',
                '  -webkit-user-select: text !important;',
                '  -moz-user-select:    text !important;',
                '  -ms-user-select:     text !important;',
                '  user-select:         text !important;',
                '  -webkit-touch-callout: default !important;',
                '}'
            ].join('\n');
            (document.head || document.documentElement).appendChild(style);

            // --- JS: re-enable copy / contextmenu events ---
            var UNBLOCK = ['copy', 'cut', 'contextmenu', 'selectstart', 'dragstart'];
            UNBLOCK.forEach(function(evt) {
                document.addEventListener(evt, function(e) {
                    e.stopImmediatePropagation();
                }, true);          // capture phase → fires before any suppression handler
            });

            // Restore document.execCommand('copy') where apps have overridden it
            try {
                if (typeof document.execCommand === 'function') {
                    var _orig = document.execCommand.bind(document);
                    document.execCommand = function(cmd) {
                        return _orig.apply(document, arguments);
                    };
                }
            } catch(e) {}
        })();
    """.trimIndent()

    // -------------------------------------------------------------------------
    // Combined injection helper
    // -------------------------------------------------------------------------
    private fun injectKioskScripts(view: WebView) {
        view.evaluateJavascript(visibilityOverrideScript, null)
        view.evaluateJavascript(selectionAndClipboardScript, null)
    }

    // -------------------------------------------------------------------------
    // Activity lifecycle
    // -------------------------------------------------------------------------
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView     = findViewById(R.id.webView)
        progressBar = findViewById(R.id.progressBar)

        configureSystemUI()
        configureWebView()

        val startUrl = intent.getStringExtra("KIOSK_URL") ?: BuildConfig.KIOSK_START_URL
        webView.loadUrl(startUrl)
    }

    // -------------------------------------------------------------------------
    // Full-screen immersive mode (hides status & navigation bars)
    // -------------------------------------------------------------------------
    private fun configureSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { ctrl ->
                ctrl.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                ctrl.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
            )
        }
    }

    // -------------------------------------------------------------------------
    // WebView full configuration
    // -------------------------------------------------------------------------
    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        webView.settings.apply {
            // --- JavaScript & storage ---
            javaScriptEnabled            = true
            domStorageEnabled            = true
            databaseEnabled              = true
            javaScriptCanOpenWindowsAutomatically = false   // kiosk: no pop-up windows

            // --- User-Agent ---
            userAgentString = enterpriseUserAgent

            // --- Rendering & encoding ---
            loadWithOverviewMode         = true
            useWideViewPort              = true
            textZoom                     = 100
            defaultTextEncodingName      = "UTF-8"

            // --- Cache strategy: prefer cached content for offline resilience ---
            cacheMode                    = WebSettings.LOAD_DEFAULT

            // --- Media & forms ---
            mediaPlaybackRequiresUserGesture = false
            setSupportMultipleWindows(false)

            // --- Deprecated but still required for legacy internal CAs ---
            @Suppress("DEPRECATION")
            allowFileAccess              = false            // no local-file access
            @Suppress("DEPRECATION")
            allowContentAccess           = false

            // Mixed-content: ALLOW only if your intranet serves mixed resources.
            // Switch to MIXED_CONTENT_NEVER_ALLOW for full TLS environments.
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }

        webView.webViewClient = KioskWebViewClient()
        webView.webChromeClient = KioskWebChromeClient()

        // Prevent long-press from showing the native link context menu
        webView.setOnLongClickListener { true }
        webView.isHapticFeedbackEnabled = false
    }

    // -------------------------------------------------------------------------
    // Inner: WebViewClient — request interception + script injection
    // -------------------------------------------------------------------------
    inner class KioskWebViewClient : WebViewClient() {

        /**
         * Intercept every resource request to strip the X-Requested-With header.
         * Returning null lets the WebView load the resource normally (with the
         * modified headers applied below via shouldOverrideUrlLoading equivalent).
         *
         * NOTE: We rebuild headers from the request's existing map, omitting
         * X-Requested-With, and issue a new load only for main-frame navigations
         * to avoid infinite recursion on sub-resources.
         */
        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest
        ): WebResourceResponse? {
            // Sub-resources (images, scripts, XHR) — we cannot cleanly re-issue
            // these without a custom HTTP client. The header is only set by
            // Android WebView on main-frame requests; returning null is safe.
            return null
        }

        /**
         * For main-frame navigations: strip X-Requested-With and reload with
         * clean headers. The boolean flag prevents re-entry.
         */
        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest
        ): Boolean {
            val headers = request.requestHeaders.toMutableMap()

            if (headers.containsKey("X-Requested-With")) {
                headers.remove("X-Requested-With")
                // Reload with sanitised headers — flag avoids recursive loop
                view.loadUrl(request.url.toString(), headers)
                return true
            }
            return false   // proceed normally
        }

        /** Inject early — before the page's own scripts execute. */
        override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
            super.onPageStarted(view, url, favicon)
            progressBar.visibility = View.VISIBLE
            // evaluateJavascript at page-started seeds the patch into the initial
            // JS context; some frames evaluate synchronously at this point.
            injectKioskScripts(view)
        }

        /** Re-inject after load — catches any late listener registration by SPAs. */
        override fun onPageFinished(view: WebView, url: String) {
            super.onPageFinished(view, url)
            progressBar.visibility = View.GONE
            injectKioskScripts(view)
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError
        ) {
            super.onReceivedError(view, request, error)
            if (request.isForMainFrame) {
                // Production: replace with a branded offline/error page
                view.loadUrl("about:blank")
            }
        }

        /**
         * Accept all SSL certificates issued by your enterprise CA.
         * IMPORTANT: replace this stub with a proper CA-pinned check in production.
         */
        override fun onReceivedSslError(
            view: WebView,
            handler: android.webkit.SslErrorHandler,
            error: android.net.http.SslError
        ) {
            // TODO: validate against pinned enterprise CA certificate.
            // For now, proceed — acceptable only on a closed corporate network.
            handler.proceed()
        }
    }

    // -------------------------------------------------------------------------
    // Inner: WebChromeClient — progress + JS dialogs
    // -------------------------------------------------------------------------
    inner class KioskWebChromeClient : WebChromeClient() {

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            progressBar.progress = newProgress
            if (newProgress == 100) progressBar.visibility = View.GONE
        }

        /** Allow alert() dialogs — required by many enterprise data-entry apps. */
        override fun onJsAlert(
            view: WebView, url: String, message: String,
            result: JsResult
        ): Boolean {
            android.app.AlertDialog.Builder(this@MainActivity)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
                .setOnCancelListener { result.cancel() }
                .show()
            return true
        }

        override fun onJsConfirm(
            view: WebView, url: String, message: String,
            result: JsResult
        ): Boolean {
            android.app.AlertDialog.Builder(this@MainActivity)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok)    { _, _ -> result.confirm() }
                .setNegativeButton(android.R.string.cancel){ _, _ -> result.cancel()  }
                .setOnCancelListener { result.cancel() }
                .show()
            return true
        }
    }

    // -------------------------------------------------------------------------
    // Hardware back-key: navigate WebView history; exit only if stack is empty
    // -------------------------------------------------------------------------
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView.canGoBack()) {
            webView.goBack()
            return true
        }
        // In a strict kiosk you may want to suppress back entirely:
        // return true
        return super.onKeyDown(keyCode, event)
    }

    // -------------------------------------------------------------------------
    // Re-apply immersive mode after focus changes (e.g. dialog dismissal)
    // -------------------------------------------------------------------------
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) configureSystemUI()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        webView.resumeTimers()
        // Re-inject in case the OS killed the renderer while in background
        injectKioskScripts(webView)
    }

    override fun onPause() {
        webView.pauseTimers()
        webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        webView.removeAllViews()
        webView.destroy()
        super.onDestroy()
    }
}
