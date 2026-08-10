package dev.tabcode.app.ui.screens.workspace

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.tabcode.app.ui.theme.editorColors

/**
 * Renders a mermaid diagram with the bundled mermaid.js (MIT, v11.16.1) inside
 * a WebView.
 *
 * A WebView is used because mermaid is a JavaScript layout engine - there is no
 * realistic native equivalent, and re-implementing flowchart layout would be a
 * project of its own. The script is bundled as an asset rather than fetched, so
 * diagrams render offline, which is the whole point of an on-device IDE.
 *
 * The diagram's height is not known until it lays out, so the page measures
 * itself and reports back; without that the view would be a fixed box with the
 * diagram clipped or floating in empty space.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MermaidView(source: String, modifier: Modifier = Modifier) {
    val colors = editorColors
    val density = LocalDensity.current
    var heightDp by remember(source) { mutableStateOf(INITIAL_HEIGHT_DP) }

    val html = remember(source, colors) {
        buildHtml(source, colors.background, colors.plainText)
    }

    AndroidView(
        modifier = modifier.fillMaxWidth().height(heightDp.dp),
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                // Needed so the relative <script src> resolves against the
                // android_asset base URL below.
                settings.allowFileAccess = true
                settings.blockNetworkLoads = true
                setBackgroundColor(colors.background.toArgb())
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false

                addJavascriptInterface(
                    object {
                        @JavascriptInterface
                        fun onRendered(cssPixelHeight: Int) {
                            // Arrives on a WebView thread; hop to the main
                            // thread before touching Compose state.
                            post {
                                val dp = cssPixelHeight.coerceIn(MIN_HEIGHT_DP, MAX_HEIGHT_DP)
                                heightDp = dp
                            }
                        }
                    },
                    BRIDGE_NAME,
                )
            }
        },
        update = { webView ->
            webView.loadDataWithBaseURL(ASSET_BASE_URL, html, MIME_TYPE, ENCODING, null)
        },
    )
}

private fun buildHtml(diagram: String, background: Color, foreground: Color): String {
    val theme = if (background.luminance() < DARK_THRESHOLD) "dark" else "default"
    return """
        <!doctype html>
        <html>
          <head>
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <style>
              html, body { margin: 0; padding: 0; background: ${background.toCss()}; }
              #diagram { padding: 8px; color: ${foreground.toCss()}; }
              #diagram svg { max-width: 100%; height: auto; }
              .error { font-family: monospace; font-size: 12px; padding: 8px; }
            </style>
            <script src="$MERMAID_ASSET"></script>
          </head>
          <body>
            <div id="diagram" class="mermaid">${diagram.escapeHtml()}</div>
            <script>
              function report() {
                var h = document.body.scrollHeight;
                if (window.$BRIDGE_NAME) window.$BRIDGE_NAME.onRendered(h);
              }
              try {
                mermaid.initialize({ startOnLoad: false, theme: '$theme', securityLevel: 'strict' });
                mermaid.run({ querySelector: '.mermaid' })
                  .then(function () { setTimeout(report, 50); })
                  .catch(function (e) {
                    document.getElementById('diagram').innerHTML =
                      '<div class="error">mermaid error: ' + (e && e.message ? e.message : e) + '</div>';
                    setTimeout(report, 50);
                  });
              } catch (e) {
                document.getElementById('diagram').innerHTML =
                  '<div class="error">mermaid failed to load</div>';
                setTimeout(report, 50);
              }
            </script>
          </body>
        </html>
    """.trimIndent()
}

private fun Color.toCss(): String =
    "rgb(${(red * COLOR_MAX).toInt()}, ${(green * COLOR_MAX).toInt()}, ${(blue * COLOR_MAX).toInt()})"

/** Rough perceptual luminance, enough to pick mermaid's light or dark theme. */
private fun Color.luminance(): Float = RED_WEIGHT * red + GREEN_WEIGHT * green + BLUE_WEIGHT * blue

private fun String.escapeHtml(): String =
    replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

private const val BRIDGE_NAME = "TabCodeMermaid"
private const val ASSET_BASE_URL = "file:///android_asset/"
private const val MERMAID_ASSET = "web/mermaid.min.js"
private const val MIME_TYPE = "text/html"
private const val ENCODING = "utf-8"
private const val INITIAL_HEIGHT_DP = 160
private const val MIN_HEIGHT_DP = 80
private const val MAX_HEIGHT_DP = 1200
private const val COLOR_MAX = 255
private const val DARK_THRESHOLD = 0.5f
private const val RED_WEIGHT = 0.299f
private const val GREEN_WEIGHT = 0.587f
private const val BLUE_WEIGHT = 0.114f
