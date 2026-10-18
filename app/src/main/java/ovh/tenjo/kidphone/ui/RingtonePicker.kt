package ovh.tenjo.kidphone.ui

import android.media.Ringtone
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import ovh.tenjo.kidphone.call.Ringer
import ovh.tenjo.kidphone.call.RingtoneChoice
import ovh.tenjo.kidphone.data.Contact

/** "Ringtone: <name>" on a call button's card; tapping it opens the list of ringtones. */
@Composable
fun RingtoneRow(contact: Contact, onChange: (Contact) -> Unit) {
    val context = LocalContext.current
    val title = remember(contact.ringtone) { Ringer.title(context, contact.ringtone) }
    var picking by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFFF2F4F8))
            .clickable { picking = true }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = Brand)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Ringtone", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Muted)
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = Color(0xFFB4BAC8))
    }
    if (picking) {
        RingtonePicker(
            name = contact.name.trim().ifBlank { "this button" },
            current = contact.ringtone,
            onPick = { onChange(contact.copy(ringtone = it)); picking = false },
            onDismiss = { picking = false },
        )
    }
}

/** The phone's ringtones. Tapping one plays a few seconds of it; Done keeps the last one tapped. */
@Composable
private fun RingtonePicker(name: String, current: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val choices by produceState<List<RingtoneChoice>?>(null) {
        value = withContext(Dispatchers.IO) { Ringer.choices(context) }
    }
    var selected by remember { mutableStateOf(current) }
    // Each tap bumps this, so tapping the same ringtone again plays it again.
    var plays by remember { mutableIntStateOf(0) }
    var preview by remember { mutableStateOf<Ringtone?>(null) }
    LaunchedEffect(plays) {
        preview?.stop()
        preview = null
        if (plays == 0) return@LaunchedEffect
        val ringtone = withContext(Dispatchers.IO) { Ringer.open(context, selected) } ?: return@LaunchedEffect
        runCatching { ringtone.volume = 0.6f; ringtone.play() }
        preview = ringtone
        delay(6_000)
        ringtone.stop()
    }
    DisposableEffect(Unit) { onDispose { preview?.stop() } }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        title = { Text("Ringtone for $name", fontWeight = FontWeight.ExtraBold, color = Ink) },
        text = {
            val list = choices
            if (list == null) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Brand)
                }
            } else {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(list, key = { it.uri }) { choice ->
                        val isSelected = choice.uri == selected
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isSelected) Brand.copy(alpha = 0.10f) else Color.Transparent)
                                .clickable { selected = choice.uri; plays++ }
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .background(if (isSelected) Brand else Color(0xFFE6E9F0)),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (isSelected) Icon(Icons.Rounded.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                            }
                            Spacer(Modifier.width(12.dp))
                            Text(
                                choice.title, fontSize = 16.sp,
                                fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium, color = Ink,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(selected) }) {
                Text("Done", fontWeight = FontWeight.ExtraBold, color = Brand)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", fontWeight = FontWeight.Bold, color = Muted)
            }
        },
    )
}
