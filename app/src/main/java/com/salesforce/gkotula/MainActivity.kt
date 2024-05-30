package com.salesforce.gkotula

import android.os.Bundle
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.tracing.trace
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.my_web_view)!!
        webView.clearCache(true)
        webView.settings.javaScriptEnabled = true
        webView.webViewClient = buildWebViewClientBlockingResponse(sleepTime = 30_000L)
//        webView.webViewClient = buildWebViewClientBlockingRead(sleepTime = 30_000L)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }

    override fun onResume() {
        super.onResume()
        webView.loadUrl(START_URL)
    }

    private fun buildWebViewClientBlockingResponse(sleepTime: Long) = object : WebViewClient() {
        override fun shouldInterceptRequest(
            view: WebView?,
            request: WebResourceRequest?
        ): WebResourceResponse? {
            val url = request?.url?.toString() ?: return null
            Log.d(TAG, "shouldInterceptRequest blocking response: $url")

            return when {
                url.startsWith(START_URL) -> startingHtmlResponse()

                url.startsWith(FOO_URL) -> {
                    trace(traceName(postfix = "_response")) {
                        val returnStream = object : InputStream() {
                            private val targetStream = SCRIPT_CONTENT.byteInputStream()
                            override fun read(): Int {
                                return targetStream.read()
                            }

                            override fun read(b: ByteArray?, off: Int, len: Int): Int {
                                return trace(traceName(postfix = "_read")) {
                                    super.read(b, off, len)
                                }
                            }
                        }
                        Thread.sleep(sleepTime)
                        WebResourceResponse(
                            "text/javascript",
                            "utf-8",
                            returnStream
                        )
                    }
                }

                else -> super.shouldInterceptRequest(view, request)
            }
        }
    }

    private fun buildWebViewClientBlockingRead(sleepTime: Long) = object : WebViewClient() {
        override fun shouldInterceptRequest(
            view: WebView?,
            request: WebResourceRequest?
        ): WebResourceResponse? {
            val url = request?.url?.toString() ?: return null
            Log.d(TAG, "shouldInterceptRequest blocking read: $url")

            return when {
                url.startsWith(START_URL) -> startingHtmlResponse()

                url.startsWith(FOO_URL) -> {
                    trace(traceName(postfix = "_response")) {
                        val returnStream = object : InputStream() {
                            private val targetStream = SCRIPT_CONTENT.byteInputStream()
                            private val isFirstRead = AtomicBoolean(true)
                            override fun read(): Int {
                                if (isFirstRead.compareAndSet(true, false)) {
                                    Log.d(
                                        TAG,
                                        "shouldInterceptRequest blocking read first time: $url"
                                    )
                                    Thread.sleep(sleepTime)
                                }
                                return targetStream.read()
                            }

                            override fun read(b: ByteArray?, off: Int, len: Int): Int {
                                return trace(traceName(postfix = "_read")) {
                                    super.read(b, off, len)
                                }
                            }
                        }
                        WebResourceResponse("text/javascript", "utf-8", returnStream)
                    }
                }

                else -> super.shouldInterceptRequest(view, request)
            }
        }
    }

    private fun startingHtmlResponse() =
        WebResourceResponse(
            "text/html",
            "utf-8",
            BASIC_HTML.byteInputStream()
        )

    companion object {
        private const val FOO_URL = "https://foo"
        private const val START_URL = "https://nkotula-start"
        private val BASIC_HTML = """
            <!DOCTYPE html>
            <html>
              <head></head>
              <body>Hello, World!</body>
            </html>
        """.trimIndent()
        private const val SCRIPT_CONTENT = "console.log('Script loaded');"
        private const val TAG = "NKotula"

        fun traceName(postfix: String? = null) = buildString {
            append(TAG)
            postfix?.also { append(it) }
        }
    }
}
