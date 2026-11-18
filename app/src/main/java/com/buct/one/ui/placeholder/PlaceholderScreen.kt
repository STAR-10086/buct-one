package com.buct.one.ui.placeholder

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.buct.one.ui.system.Capsules
import com.buct.one.ui.system.GlassCapsule
import com.buct.one.ui.theme.LocalGlass

/** 尚未实现的页面。写清楚缺什么、什么时候补，而不是一片空白。 */
@Composable
fun PlaceholderScreen(title: String, note: String) {
    val glass = LocalGlass.current
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        GlassCapsule(spec = Capsules.Card, modifier = Modifier.padding(horizontal = 8.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                color = glass.ink,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                note,
                style = MaterialTheme.typography.bodyMedium,
                color = glass.inkTertiary,
                textAlign = TextAlign.Center,
            )
        }
    }
}
