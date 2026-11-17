package com.msp1974.vacompanion.odysseus.update

// Odysseus fork: a small "Update ready" banner on the clock page (odysseus/home/ClockPage.kt):
// Install, or × to hide it for this version. Shows nothing when no update is downloaded.

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun UpdateBanner(modifier: Modifier = Modifier) {
    val release = UpdateManager.bannerRelease ?: return
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .widthIn(max = 420.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(colors.secondaryContainer)
            .padding(start = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Rounded.SystemUpdate, contentDescription = null,
                tint = colors.onSecondaryContainer, modifier = Modifier.size(20.dp),
            )
            Text(
                "Update ready: ${release.versionName}",
                style = MaterialTheme.typography.labelLarge,
                color = colors.onSecondaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp).weight(1f, fill = false),
            )
            TextButton(onClick = { UpdateManager.install(context) }) { Text("Install") }
            IconButton(onClick = { UpdateManager.dismissBanner() }) {
                Icon(Icons.Rounded.Close, contentDescription = "Hide", tint = colors.onSecondaryContainer)
            }
        }
        // Why an install didn't start (no permission, wrong key…); the details are in the settings.
        UpdateManager.installMessage?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSecondaryContainer,
                modifier = Modifier.padding(end = 12.dp, bottom = 8.dp),
            )
        }
    }
}
