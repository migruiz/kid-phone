package ovh.tenjo.kidphone.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.launch
import ovh.tenjo.kidphone.hub.Apps
import ovh.tenjo.kidphone.hub.HomeStatus
import ovh.tenjo.kidphone.hub.Hub
import ovh.tenjo.kidphone.hub.LaunchableApp

/** One line about the apps for the parent menu. */
fun appsSummary(allowed: Set<String>, timed: Int, status: HomeStatus): String = when {
    status == HomeStatus.NotSetUp -> "Needs the home hub"
    allowed.isEmpty() -> "None switched on"
    allowed.size == 1 -> "1 switched on" + if (timed > 0) ", for a while" else ""
    else -> "${allowed.size} switched on" + if (timed > 0) ", $timed for a while" else ""
}

/**
 * The same apps list as in Kid Phone Parent, on the child's phone itself (behind the PIN). Switches
 * go through the hub, so both stay the same; that needs the home Wi-Fi.
 */
@Composable
fun AppsEditor(allowed: Set<String>, ends: Map<String, Long>, status: HomeStatus, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val apps = remember { Apps.launchable(context) }
    // Switches flipped here show at once, before the hub confirms.
    val pending = remember { mutableStateMapOf<String, Boolean>() }
    var error by remember { mutableStateOf<String?>(null) }
    var timing by remember { mutableStateOf<LaunchableApp?>(null) }
    val canChange = status == HomeStatus.AtHome

    fun change(app: LaunchableApp, on: Boolean, minutes: Int?) {
        pending[app.packageName] = on
        error = null
        scope.launch {
            runCatching { Hub.setAllowed(app.packageName, on, minutes) }.onFailure { error = it.message }
            pending.remove(app.packageName)
        }
    }

    timing?.let { app ->
        AppTimeDialog(
            label = app.label,
            allowed = app.packageName in allowed,
            endsAt = ends[app.packageName],
            onChoose = { on, minutes -> timing = null; change(app, on, minutes) },
            onDismiss = { timing = null },
        )
    }

    ParentPage {
        ScreenHeader(
            "Apps", onBack,
            subtitle = "Apps switched on here appear when he taps Apps. They only open on the home Wi-Fi. " +
                "Switching one on asks for how long; tap it later to change the time. " +
                "Kid Phone Parent shows the same list.",
        )
        val notice = when (status) {
            HomeStatus.NotSetUp -> "Connect the home hub first (Home hub in the parent menu)."
            HomeStatus.AtHome -> null
            else -> "Not on the home Wi-Fi: switches can only change at home."
        }
        if (notice != null) {
            Surface(shape = RoundedCornerShape(20.dp), color = Color(0xFFFFF1D6), modifier = Modifier.fillMaxWidth()) {
                Text(notice, color = Color(0xFF92400E), fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.padding(16.dp))
            }
            Spacer(Modifier.height(12.dp))
        }
        if (error != null) {
            Text(error!!, color = Danger, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 12.dp))
        }
        Group {
            if (apps.isEmpty()) Text("No apps on this phone.", color = Muted, modifier = Modifier.padding(16.dp))
            apps.forEachIndexed { i, app ->
                if (i > 0) HorizontalDivider(Modifier.padding(start = 76.dp), color = Hairline)
                val icon = remember(app.packageName, app.updatedAt) {
                    runCatching { context.packageManager.getApplicationIcon(app.packageName).toBitmap(96, 96).asImageBitmap() }.getOrNull()
                }
                AppSwitchRow(
                    packageName = app.packageName,
                    label = app.label,
                    installed = true,
                    allowed = pending[app.packageName] ?: (app.packageName in allowed),
                    enabled = canChange && app.packageName !in pending,
                    endsAt = ends[app.packageName],
                    onTime = { timing = app },
                    icon = { icon?.let { Image(it, contentDescription = null, modifier = Modifier.size(42.dp)) } },
                    onToggle = { on -> change(app, on, null) },
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
