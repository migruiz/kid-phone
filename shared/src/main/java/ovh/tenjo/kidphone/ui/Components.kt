package ovh.tenjo.kidphone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ovh.tenjo.kidphone.shared.UpdateState
import ovh.tenjo.kidphone.shared.Updater

// Pieces both apps' settings-style screens are built from.

/** A scrolling page with the standard background and padding, used by every parent screen. */
@Composable
fun ParentPage(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(PageBrush)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        content = content,
    )
}

/** A white rounded card that groups related rows. */
@Composable
fun Group(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(24.dp), color = Color.White, shadowElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Column(content = content)
    }
}

@Composable
fun SettingsRow(icon: ImageVector, tint: Color, title: String, subtitle: String?, onClick: () -> Unit) {
    Surface(onClick = onClick, color = Color.Transparent) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon, tint)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Ink)
                if (!subtitle.isNullOrBlank()) {
                    Text(subtitle, fontSize = 14.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = Color(0xFFB4BAC8))
        }
    }
}

@Composable
fun IconBadge(icon: ImageVector, tint: Color, size: Int = 42) {
    Box(
        Modifier.size(size.dp).background(tint.copy(alpha = 0.14f), RoundedCornerShape((size * 0.32f).dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size((size * 0.55f).dp))
    }
}

@Composable
fun RowDivider() = HorizontalDivider(Modifier.padding(start = 72.dp), color = Hairline)

@Composable
fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = Color.White,
    unfocusedContainerColor = Color(0xFFF7F8FB),
    unfocusedBorderColor = Hairline,
)

/**
 * "Version 1.2.0 · Up to date". Tapping it checks GitHub for a newer version and installs it.
 * [waiting] describes when a downloaded update will go in.
 */
@Composable
fun UpdatesRow(waiting: String = "installs soon") {
    val context = LocalContext.current
    val state by Updater.state.collectAsState()
    val version = remember { Updater.currentVersionName(context) }
    val status = when (val s = state) {
        UpdateState.Idle -> "Updates itself. Tap to check now."
        UpdateState.Checking -> "Checking for updates…"
        UpdateState.UpToDate -> "Up to date"
        is UpdateState.Downloading -> "Downloading ${s.version}…"
        is UpdateState.Waiting -> "${s.version} downloaded, $waiting"
        is UpdateState.Installing -> "Installing ${s.version}…"
        is UpdateState.NeedsTap -> "${s.version} is ready: tap Update"
        is UpdateState.Failed -> "Couldn't update: ${s.message}"
    }
    SettingsRow(
        Icons.Rounded.SystemUpdate, Color(0xFF0A9488), "Version $version", status,
        onClick = { Updater.checkNow(context) },
    )
}
