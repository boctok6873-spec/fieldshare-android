package com.youngsu.fieldshare

import android.text.SpannableString
import android.text.style.URLSpan
import android.text.util.Linkify
import android.widget.Toast
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import androidx.core.text.util.LinkifyCompat
import com.youngsu.fieldshare.ui.theme.Ink
import com.youngsu.fieldshare.ui.theme.SamsungBlue

@Composable
internal fun LinkedDocumentText(content: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val linkedContent = remember(content, context, uriHandler) {
        val spannable = SpannableString(content)
        LinkifyCompat.addLinks(spannable, Linkify.WEB_URLS)
        buildAnnotatedString {
            append(content)
            spannable.getSpans(0, spannable.length, URLSpan::class.java).forEach { span ->
                // Open web addresses only; document text must not launch arbitrary URI schemes.
                if (span.url.startsWith("https://", ignoreCase = true) ||
                    span.url.startsWith("http://", ignoreCase = true)
                ) {
                    addLink(
                        LinkAnnotation.Url(
                            url = span.url,
                            styles = TextLinkStyles(
                                style = SpanStyle(
                                    color = SamsungBlue,
                                    textDecoration = TextDecoration.Underline
                                )
                            ),
                            linkInteractionListener = {
                                try {
                                    uriHandler.openUri(span.url)
                                } catch (_: IllegalArgumentException) {
                                    Toast.makeText(context, "링크를 열 수 없습니다.", Toast.LENGTH_SHORT).show()
                                } catch (_: SecurityException) {
                                    Toast.makeText(context, "링크를 열 수 없습니다.", Toast.LENGTH_SHORT).show()
                                }
                            }
                        ),
                        start = spannable.getSpanStart(span),
                        end = spannable.getSpanEnd(span)
                    )
                }
            }
        }
    }
    Text(
        text = linkedContent,
        modifier = modifier,
        style = MaterialTheme.typography.bodyLarge,
        color = Ink,
        lineHeight = 25.sp
    )
}
