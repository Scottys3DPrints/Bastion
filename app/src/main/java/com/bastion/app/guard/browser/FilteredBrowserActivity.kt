package com.bastion.app.guard.browser

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import com.bastion.app.core.design.BastionColors
import com.bastion.app.core.design.BastionTheme
import com.bastion.app.core.design.SectionLabel
import com.bastion.app.data.BastionGraph
import com.bastion.app.guard.vpn.DomainFilter
import java.io.ByteArrayInputStream

/**
 * A browser that cannot be talked out of its filter.
 *
 * The DNS layer can be bypassed by apps that ship their own resolver; this
 * cannot, because the check happens after the URL is known and before the
 * request is made. It is the one place Bastion can promise clean browsing
 * outright rather than probably.
 */
class FilteredBrowserActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BastionTheme {
                FilteredBrowser(onExit = { finish() })
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun FilteredBrowser(onExit: () -> Unit) {
    val context = LocalContext.current
    val graph = remember { BastionGraph.from(context) }
    var filter by remember { mutableStateOf(DomainFilter.PERMISSIVE) }
    var address by remember { mutableStateOf("") }
    var blockedHost by remember { mutableStateOf<String?>(null) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    LaunchedEffect(Unit) {
        graph.guard.seedIfEmpty()
        val data = graph.guard.filterData()
        filter = DomainFilter(data.blocked, data.allowed, data.keywords)
    }

    BackHandler {
        val view = webView
        if (view != null && view.canGoBack()) view.goBack() else onExit()
    }

    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onExit) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Close", tint = BastionColors.TextSecondary)
            }
            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                placeholder = { Text("Search or type an address", style = MaterialTheme.typography.bodyMedium) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = {
                    blockedHost = null
                    webView?.loadUrl(address.toUrlOrSearch())
                }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = BastionColors.Bronze,
                    unfocusedBorderColor = BastionColors.Outline,
                    focusedTextColor = BastionColors.TextPrimary,
                    unfocusedTextColor = BastionColors.TextPrimary,
                ),
            )
            IconButton(onClick = { webView?.reload() }) {
                Icon(Icons.Default.Refresh, contentDescription = "Reload", tint = BastionColors.TextSecondary)
            }
        }

        val blocked = blockedHost
        if (blocked != null) {
            BlockedNotice(host = blocked, onBack = {
                blockedHost = null
                webView?.let { if (it.canGoBack()) it.goBack() else it.loadUrl(HOME_PAGE) }
            })
        } else {
            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.safeBrowsingEnabled = true
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?,
                            ): Boolean {
                                val host = request?.url?.host ?: return false
                                return if (filter.isBlocked(host)) {
                                    blockedHost = host
                                    true
                                } else false
                            }

                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?,
                            ): WebResourceResponse? {
                                val host = request?.url?.host ?: return null
                                // Catches sub-resources too: an image or iframe
                                // pulled from a blocked host never loads.
                                return if (filter.isBlocked(host)) {
                                    WebResourceResponse(
                                        "text/plain",
                                        "utf-8",
                                        ByteArrayInputStream(ByteArray(0)),
                                    )
                                } else null
                            }

                            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                url?.let { address = it }
                                super.onPageStarted(view, url, favicon)
                            }
                        }
                        loadUrl(HOME_PAGE)
                        webView = this
                    }
                },
            )
        }
    }
}

@Composable
private fun BlockedNotice(host: String, onBack: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(32.dp),
        ) {
            Text("◇", style = MaterialTheme.typography.displaySmall, color = BastionColors.Bronze)
            Spacer(Modifier.height(16.dp))
            Text("Blocked", style = MaterialTheme.typography.headlineMedium, color = BastionColors.TextPrimary)
            Spacer(Modifier.height(10.dp))
            SectionLabel(host)
            Spacer(Modifier.height(14.dp))
            Text(
                "Your boundary is holding.",
                style = MaterialTheme.typography.bodyMedium,
                color = BastionColors.TextSecondary,
            )
            Spacer(Modifier.height(26.dp))
            com.bastion.app.core.design.PrimaryButton("Go back", onBack)
        }
    }
}

private const val HOME_PAGE = "https://duckduckgo.com/"

private fun String.toUrlOrSearch(): String {
    val trimmed = trim()
    return when {
        trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
        trimmed.contains('.') && !trimmed.contains(' ') -> "https://$trimmed"
        else -> "https://duckduckgo.com/?q=${android.net.Uri.encode(trimmed)}"
    }
}
