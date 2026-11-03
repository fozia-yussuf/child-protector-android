package com.childprotector.app.ui.common

import android.graphics.Bitmap
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale

@Composable
fun EmbeddedMap(latitude: Double, longitude: Double, modifier: Modifier = Modifier) {
    var unavailable by remember(latitude, longitude) { mutableStateOf(false) }
    val mapUrl = remember(latitude, longitude) {
        "https://maps.google.com/maps?q=$latitude,$longitude&z=16&output=embed"
    }

    Box(modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.domStorageEnabled = false
                    settings.javaScriptCanOpenWindowsAutomatically = false
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest
                        ): Boolean {
                            val blocked = request.url.host != "maps.google.com"
                            if (blocked) unavailable = true
                            return blocked
                        }

                        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                            unavailable = false
                        }

                        override fun onReceivedError(
                            view: WebView,
                            request: WebResourceRequest,
                            error: WebResourceError
                        ) {
                            if (request.isForMainFrame) unavailable = true
                        }
                    }
                    tag = mapUrl
                    loadUrl(mapUrl)
                }
            },
            update = { view ->
                if (view.tag != mapUrl) {
                    view.tag = mapUrl
                    view.loadUrl(mapUrl)
                }
            },
            onRelease = { view ->
                view.stopLoading()
                view.destroy()
            }
        )
        if (unavailable) {
            Text(
                "Map unavailable. Check your connection and try again.",
                modifier = Modifier.align(Alignment.Center),
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
fun LastUpdated(timestamp: String?, modifier: Modifier = Modifier) {
    val timestampMillis = remember(timestamp) { timestamp?.toEpochMillis() }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(timestamp) {
        while (true) {
            now = System.currentTimeMillis()
            kotlinx.coroutines.delay(30_000)
        }
    }
    val ageMillis = timestampMillis?.let { (now - it).coerceAtLeast(0) }
    val stale = ageMillis == null || ageMillis > 120_000L
    val label = if (timestampMillis == null) {
        "Last updated unavailable · STALE"
    } else {
        "Last updated ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestampMillis))}" +
                " (${ageMillis!! / 60_000} min ago)" + if (stale) " · STALE" else ""
    }
    Text(
        label,
        modifier = modifier,
        color = if (stale) MaterialTheme.colorScheme.error else CutePurplePrimary,
        style = MaterialTheme.typography.bodySmall
    )
}

fun String.toEpochMillis(): Long? {
    toLongOrNull()?.let { return if (it < 10_000_000_000L) it * 1000 else it }
    return try {
        Instant.parse(this).toEpochMilli()
    } catch (_: Exception) {
        try {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).parse(this)?.time
        } catch (_: Exception) {
            null
        }
    }
}
