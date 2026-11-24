package com.buct.course.update

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.buct.course.ui.system.GlassToaster
import com.buct.course.ui.system.SystemDialog
import com.buct.course.ui.system.SystemDivider
import com.buct.course.ui.system.SystemPrimaryButton
import com.buct.course.ui.system.SystemSecondaryButton
import com.buct.course.ui.system.SystemStatusBadge
import com.buct.course.ui.system.SystemTone
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val MD_IMAGE_REGEX = Regex("""!\[([^\]]*)]\((https://[^)\s]+)\)""")

@Composable
fun AppNoticeDialog(
    notice: AppNotice,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val safeActionUrl = notice.actionUrl.trim().takeIf {
        it.startsWith("https://", ignoreCase = true)
    }

    SystemDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SystemStatusBadge(
                    text = if (notice.level == "warning") "重要提醒" else "官方公告",
                    tone = if (notice.level == "warning") SystemTone.Warning else SystemTone.Info
                )
                Text(
                    text = notice.title,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        },
        confirmButton = {
            SystemPrimaryButton(
                text = "知道了",
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            )
        },
        dismissButton = if (safeActionUrl != null && notice.actionText.isNotBlank()) {
            {
                SystemSecondaryButton(
                    text = notice.actionText,
                    onClick = {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(safeActionUrl)))
                        }.onFailure {
                            GlassToaster.show("无法打开链接")
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        } else {
            null
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (notice.markdown.isNotBlank()) {
                NoticeMarkdownBody(markdown = notice.markdown)
            } else if (notice.summary.isNotBlank()) {
                Text(
                    text = notice.summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun AppNoticeListDialog(
    notices: List<AppNotice>,
    onSelectNotice: (AppNotice) -> Unit,
    onDismiss: () -> Unit
) {
    val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
    SystemDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "官方公告",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
            )
        },
        confirmButton = null,
        dismissButton = {
            SystemSecondaryButton(
                text = "关闭",
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            )
        }
    ) {
        if (notices.isEmpty()) {
            Text(
                text = "暂无有效公告。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                notices.forEach { item ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
                            .clickable { onSelectNotice(item) }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = item.title,
                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f).padding(end = 8.dp),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            SystemStatusBadge(
                                text = if (item.level == "warning") "提醒" else "公告",
                                tone = if (item.level == "warning") SystemTone.Warning else SystemTone.Info
                            )
                        }
                        if (item.summary.isNotBlank()) {
                            Text(
                                text = item.summary,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (item.publishedAt > 0L) {
                            Text(
                                text = formatter.format(Date(item.publishedAt)),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NoticeMarkdownBody(markdown: String) {
    val lines = markdown.replace("\r\n", "\n").split('\n')
    val codeBg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (rawLine in lines) {
            val line = rawLine.trimEnd()
            if (line.isBlank()) {
                Spacer(Modifier.height(2.dp))
                continue
            }
            val imageMatch = MD_IMAGE_REGEX.find(line.trim())
            if (imageMatch != null && imageMatch.value == line.trim()) {
                val alt = imageMatch.groupValues[1].ifBlank { "公告图片" }
                val imgUrl = imageMatch.groupValues[2]
                AsyncImage(
                    model = imgUrl,
                    contentDescription = alt,
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                )
                continue
            }
            val trimmed = line.trimStart()
            when {
                trimmed == "---" || trimmed == "***" -> {
                    SystemDivider(alpha = 0.5f)
                }
                trimmed.startsWith("### ") -> {
                    Text(
                        text = parseInlineMarkdown(trimmed.removePrefix("### "), codeBg),
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                trimmed.startsWith("## ") || trimmed.startsWith("# ") -> {
                    val content = trimmed.removePrefix("## ").removePrefix("# ")
                    Text(
                        text = parseInlineMarkdown(content, codeBg),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "•",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = parseInlineMarkdown(trimmed.drop(2), codeBg),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                else -> {
                    Text(
                        text = parseInlineMarkdown(trimmed, codeBg),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

private fun parseInlineMarkdown(text: String, codeBg: androidx.compose.ui.graphics.Color): AnnotatedString {
    return buildAnnotatedString {
        var idx = 0
        while (idx < text.length) {
            if (text.startsWith("**", idx)) {
                val end = text.indexOf("**", idx + 2)
                if (end > idx + 2) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(text.substring(idx + 2, end))
                    }
                    idx = end + 2
                    continue
                }
            }
            if (text[idx] == '`') {
                val end = text.indexOf('`', idx + 1)
                if (end > idx + 1) {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)) {
                        append(" ${text.substring(idx + 1, end)} ")
                    }
                    idx = end + 1
                    continue
                }
            }
            append(text[idx])
            idx++
        }
    }
}
