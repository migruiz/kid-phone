package ovh.tenjo.kidphone.ui

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import ovh.tenjo.kidphone.hub.Apps
import ovh.tenjo.kidphone.hub.LaunchableApp

/**
 * The apps the parents allow, as big icons. Tapping one opens it; Home or Back comes back here.
 * An app allowed for a while shows the minutes left ([ends]: package → when its time is up).
 */
@Composable
fun AppsScreen(allowed: Set<String>, ends: Map<String, Long>, onBack: () -> Unit) {
    val context = LocalContext.current
    val apps = remember(allowed) { Apps.launchable(context).filter { it.packageName in allowed } }
    val now = rememberClock()
    KidBackground {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RoundTopButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onClick = onBack, bright = true, size = 46.dp)
                Spacer(Modifier.width(14.dp))
                Text("Apps", fontSize = 30.sp, fontWeight = FontWeight.Black, color = Color.White)
            }
            Spacer(Modifier.height(24.dp))
            if (apps.isEmpty()) {
                Column(
                    Modifier.fillMaxWidth().weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("🧩", fontSize = 64.sp)
                    Spacer(Modifier.height(12.dp))
                    Text("No apps yet", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Ask Mum or Dad to choose some.",
                        fontSize = 16.sp, color = Color.White.copy(alpha = 0.6f), textAlign = TextAlign.Center,
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(apps, key = { it.packageName }) { app ->
                        AppTile(app, ends[app.packageName]?.let { minutesLeft(it, now) }) {
                            val intent = Apps.launchIntent(context, app.packageName)
                            val opened = intent != null && runCatching { context.startActivity(intent) }.isSuccess
                            if (!opened) Toast.makeText(context, "${app.label} didn't open", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppTile(app: LaunchableApp, minutesLeft: Long?, onOpen: () -> Unit) {
    val context = LocalContext.current
    val icon: ImageBitmap? = remember(app.packageName, app.updatedAt) {
        runCatching { context.packageManager.getApplicationIcon(app.packageName).toBitmap(192, 192).asImageBitmap() }.getOrNull()
    }
    Column(
        Modifier.fillMaxWidth().bounceClick(onClick = onOpen),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            Box(
                Modifier
                    .size(84.dp)
                    .clip(RoundedCornerShape(26.dp))
                    .background(Color.White.copy(alpha = 0.07f))
                    .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(26.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (icon != null) Image(icon, contentDescription = null, modifier = Modifier.size(64.dp))
            }
            // Minutes left, on the icon's corner; amber in the last five.
            if (minutesLeft != null) {
                Text(
                    "$minutesLeft min",
                    fontSize = 13.sp, fontWeight = FontWeight.Black, color = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 10.dp, y = (-8).dp)
                        .background(if (minutesLeft <= 5) Color(0xFFF59E0B) else CallGreen, CircleShape)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            app.label, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White,
            maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
        )
    }
}
