package ovh.tenjo.kidphone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AppSettingsAlt
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChildCare
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Contacts
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Password
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.PhoneInTalk
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material.icons.rounded.WavingHand
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ovh.tenjo.kidphone.data.AllowedCaller
import ovh.tenjo.kidphone.data.Contact
import ovh.tenjo.kidphone.data.PhoneNumbers

/** Pictures a call button can have. The first one ("") means the first letter of the name. */
private val EmojiChoices = listOf("", "👩", "👨", "👵", "👴", "👧", "👦", "🧑", "👶", "🐶", "🐱", "⭐", "❤️", "🏠")

@Composable
fun ParentMenu(
    isDialer: Boolean,
    hasSim: Boolean,
    buttonsSummary: String,
    allowedSummary: String,
    hubSummary: String,
    onHub: () -> Unit,
    appsSummary: String,
    onApps: () -> Unit,
    callTimes: List<CallTime>,
    callTimeBusy: Boolean,
    callTimeError: String?,
    onChangeCallTime: (CallTime, Int) -> Unit,
    onUnlock: () -> Unit,
    onContacts: () -> Unit,
    onAllowed: () -> Unit,
    onChangePin: () -> Unit,
    childName: String,
    onChildName: (String) -> Unit,
    onFixDialer: () -> Unit,
    onBackToKid: () -> Unit,
    onRemove: () -> Unit,
) {
    var confirmRemove by remember { mutableStateOf(false) }
    var editName by remember { mutableStateOf(false) }
    ParentPage {
        ScreenHeader("Parents", onBack = onBackToKid)

        // Status card
        Surface(shape = RoundedCornerShape(24.dp), color = Color(0xFFDDF7E8), modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).background(CallGreen, CircleShape))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Kid mode is on", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF0B5D33))
                    Text("Only the call buttons and the allowed apps work.", fontSize = 15.sp, color = Color(0xFF2F7A52))
                }
            }
        }
        if (!isDialer) {
            Spacer(Modifier.height(12.dp))
            Surface(shape = RoundedCornerShape(20.dp), color = Color(0xFFFFE3E3), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = Danger)
                        Spacer(Modifier.width(8.dp))
                        Text("Calls need fixing", fontWeight = FontWeight.ExtraBold, color = Danger, fontSize = 17.sp)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Kid Phone is no longer the phone app, so calls and call blocking may not work.",
                        color = Color(0xFF8A1C1C), fontSize = 15.sp,
                    )
                    Spacer(Modifier.height(12.dp))
                    BigButton("Fix it", onFixDialer, container = Danger)
                }
            }
        }

        if (!hasSim) {
            Spacer(Modifier.height(12.dp))
            Surface(shape = RoundedCornerShape(20.dp), color = Color(0xFFFFF1D6), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = Color(0xFFB45309))
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("No SIM card", fontWeight = FontWeight.ExtraBold, color = Color(0xFF92400E), fontSize = 17.sp)
                        Text(
                            "Put a SIM card in the phone to make and receive calls.",
                            color = Color(0xFF92400E), fontSize = 15.sp,
                        )
                    }
                }
            }
        }

        if (callTimes.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            CallTimesCard(callTimes, callTimeBusy, callTimeError, onChangeCallTime)
        }

        Spacer(Modifier.height(20.dp))
        BigButton("Unlock phone", onUnlock, icon = Icons.Rounded.LockOpen)
        Text(
            "Works as a normal phone until Kid Phone is opened again.",
            color = Muted, fontSize = 14.sp, modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 16.dp),
        )

        SectionLabel("Settings")
        Group {
            SettingsRow(Icons.Rounded.Contacts, Color(0xFFE23670), "Call buttons", buttonsSummary, onContacts)
            RowDivider()
            SettingsRow(Icons.Rounded.VerifiedUser, Color(0xFF10A36C), "Who can ring this phone", allowedSummary, onAllowed)
            RowDivider()
            SettingsRow(Icons.Rounded.Apps, Color(0xFF0A9488), "Apps he can open", appsSummary, onApps)
            RowDivider()
            SettingsRow(Icons.Rounded.Password, Color(0xFF7442EA), "Change PIN", null, onChangePin)
            RowDivider()
            SettingsRow(
                Icons.Rounded.WavingHand, Color(0xFFF2760B), "Name on screen",
                if (childName.isBlank()) "Not set" else "Hi $childName", onClick = { editName = true },
            )
            RowDivider()
            SettingsRow(Icons.Rounded.Router, Brand, "Home hub", hubSummary, onHub)
            RowDivider()
            UpdatesRow(waiting = "goes in when the screen is off")
        }
        Spacer(Modifier.height(16.dp))
        BigButton(
            "Back to kid mode", onBackToKid,
            container = Color(0xFFE3E8F5), content = Ink, icon = Icons.Rounded.ChildCare,
        )
        Spacer(Modifier.height(32.dp))
        TextButton(onClick = { confirmRemove = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("Remove Kid Phone from this phone…", color = Danger, fontWeight = FontWeight.Bold)
        }
    }
    if (editName) {
        var name by remember { mutableStateOf(childName) }
        AlertDialog(
            onDismissRequest = { editName = false },
            containerColor = Color.White,
            title = { Text("Name on screen", fontWeight = FontWeight.ExtraBold) },
            text = {
                Column {
                    Text("Shown as \"Hi …\" at the top of the kid screen. Leave empty to show \"Good morning\".", color = Muted)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = name, onValueChange = { name = it.take(20) },
                        label = { Text("Name") }, singleLine = true,
                        shape = RoundedCornerShape(14.dp), colors = fieldColors(),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { onChildName(name); editName = false }) { Text("Save", fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { editName = false }) { Text("Cancel") } },
        )
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            containerColor = Color.White,
            title = { Text("Remove Kid Phone?", fontWeight = FontWeight.ExtraBold) },
            text = {
                Text(
                    "The phone is unlocked and Kid Phone stops controlling it, so it can be uninstalled " +
                        "like any other app. To use kid mode again it has to be set up again from a computer."
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmRemove = false; onRemove() }) {
                    Text("Remove", color = Danger, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
        )
    }
}

/** Edit the four call buttons: picture, name, number and colour, with a live preview. */
@Composable
fun ContactsEditor(
    initial: List<Contact>,
    title: String,
    intro: String,
    saveLabel: String,
    requireOne: Boolean,
    onSave: (List<Contact>) -> Unit,
    onBack: (() -> Unit)?,
) {
    val items = remember { initial.toMutableStateList() }
    var error by remember { mutableStateOf<String?>(null) }
    ParentPage {
        ScreenHeader(title, onBack, subtitle = intro)
        items.indices.forEach { i ->
            ContactCard(i, items[i], onChange = { items[i] = it })
            Spacer(Modifier.height(14.dp))
        }
        if (error != null) {
            Text(error!!, color = Danger, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 10.dp))
        }
        BigButton(saveLabel, onClick = {
            val cleaned = items.map { c ->
                val number = c.number.trim()
                c.copy(name = c.name.trim().ifBlank { if (number.isNotEmpty()) "Call" else "" }, number = number)
            }
            if (requireOne && cleaned.none { it.isSet }) {
                error = "Add at least one phone number."
            } else {
                onSave(cleaned)
            }
        })
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ContactCard(index: Int, contact: Contact, onChange: (Contact) -> Unit) {
    val colors = tileColor(contact.color)
    Group {
        Column(Modifier.padding(16.dp)) {
            // Header: a live preview of the button, and what it is
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(60.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(if (contact.isSet) colors.brush else SolidColor(KeySurface)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (contact.isSet || contact.name.isNotBlank() || contact.emoji.isNotBlank()) {
                        Avatar(contact.name, contact.emoji, 44.dp, Color.White.copy(alpha = if (contact.isSet) 0.25f else 0f), if (contact.isSet) Color.White else Muted)
                    } else {
                        Icon(Icons.Rounded.PersonAdd, contentDescription = null, tint = Muted)
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Button ${index + 1}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Muted)
                    Text(
                        when {
                            contact.isSet -> contact.name.ifBlank { "Call" }
                            else -> "Empty"
                        },
                        fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = if (contact.isSet) Ink else Muted,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                if (contact.isSet || contact.name.isNotBlank()) {
                    IconButton(onClick = { onChange(Contact(color = contact.color)) }) {
                        Icon(Icons.Rounded.Close, contentDescription = "Clear button ${index + 1}", tint = Muted)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = contact.name,
                onValueChange = { onChange(contact.copy(name = it.take(20))) },
                label = { Text("Name") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = fieldColors(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = contact.number,
                onValueChange = { onChange(contact.copy(number = it.take(25))) },
                label = { Text("Phone number") },
                placeholder = { Text("e.g. 087 123 4567") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = fieldColors(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(14.dp))
            Text("Picture", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Muted)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EmojiChoices.forEach { e ->
                    val selected = contact.emoji == e
                    Box(
                        Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(if (selected) colors.light.copy(alpha = 0.22f) else Color(0xFFF2F4F8))
                            .border(if (selected) 2.5.dp else 0.dp, if (selected) colors.dark else Color.Transparent, CircleShape)
                            .bounceClick { onChange(contact.copy(emoji = e)) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (e.isEmpty()) {
                            Text(
                                contact.name.trim().take(1).uppercase().ifEmpty { "A" },
                                fontSize = 20.sp, fontWeight = FontWeight.Black, color = Ink,
                            )
                        } else {
                            Text(e, fontSize = 22.sp)
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Text("Colour", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Muted)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TileColors.indices.forEach { c ->
                    val selected = contact.color == c
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(tileColor(c).brush)
                            .bounceClick { onChange(contact.copy(color = c)) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (selected) Icon(Icons.Rounded.Check, contentDescription = "Selected", tint = Color.White)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            RingtoneRow(contact, onChange)
            if (contact.isSet) {
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Ask a parent first", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Ink)
                        Text(
                            "Each call needs a parent's OK in Kid Phone Parent, and only works at home. " +
                                "This person can still ring him any time.",
                            fontSize = 13.sp, color = Muted, lineHeight = 18.sp,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Switch(
                        checked = contact.askFirst,
                        onCheckedChange = { onChange(contact.copy(askFirst = it)) },
                        colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFFF59E0B)),
                    )
                }
            }
        }
    }
}

/** The list of extra people who may ring the phone while it is locked. */
@Composable
fun AllowedEditor(
    contacts: List<Contact>,
    initial: List<AllowedCaller>,
    onSave: (List<AllowedCaller>) -> Unit,
    onBack: () -> Unit,
) {
    val items = remember { initial.toMutableStateList() }
    var name by remember { mutableStateOf("") }
    var number by remember { mutableStateOf("") }
    ParentPage {
        ScreenHeader(
            "Who can ring",
            onBack,
            subtitle = "While the phone is locked, only these people can ring it. Every other call is " +
                "turned away without ringing, including withheld numbers.",
        )
        SectionLabel("Always (the call buttons)")
        Group {
            val buttons = contacts.filter { it.isSet }
            if (buttons.isEmpty()) {
                Text("No call buttons yet.", color = Muted, modifier = Modifier.padding(16.dp))
            }
            buttons.forEachIndexed { i, c ->
                if (i > 0) RowDivider()
                PersonRow(c.name, c.number, c.emoji, tileColor(c.color).brush, onDelete = null)
            }
        }
        Spacer(Modifier.height(8.dp))
        SectionLabel("Also allowed")
        Group {
            if (items.isEmpty()) {
                Text("Nobody else yet.", color = Muted, modifier = Modifier.padding(16.dp))
            }
            items.toList().forEachIndexed { i, a ->
                if (i > 0) RowDivider()
                PersonRow(a.name, a.number, "", SolidColor(Color(0xFF94A3B8)), onDelete = {
                    items.removeAt(i)
                    onSave(items.toList())
                })
            }
        }
        Spacer(Modifier.height(8.dp))
        SectionLabel("Add someone")
        Group {
            Column(Modifier.padding(16.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it.take(20) },
                    label = { Text("Name (optional)") }, singleLine = true,
                    shape = RoundedCornerShape(14.dp), colors = fieldColors(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = number, onValueChange = { number = it.take(25) },
                    label = { Text("Phone number") }, singleLine = true,
                    shape = RoundedCornerShape(14.dp), colors = fieldColors(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(14.dp))
                BigButton(
                    "Add", icon = Icons.Rounded.PersonAdd,
                    enabled = PhoneNumbers.digits(number).length >= 3,
                    onClick = {
                        items.add(AllowedCaller(name.trim(), number.trim()))
                        onSave(items.toList())
                        name = ""
                        number = ""
                    },
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun PersonRow(
    name: String,
    number: String,
    emoji: String,
    brush: Brush,
    onDelete: (() -> Unit)?,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(42.dp).clip(CircleShape).background(brush), contentAlignment = Alignment.Center) {
            Avatar(name, emoji, 42.dp, Color.Transparent)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(name.ifBlank { "No name" }, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Ink)
            Text(number, fontSize = 14.sp, color = Muted)
        }
        if (onDelete != null) {
            IconButton(onClick = onDelete) { Icon(Icons.Rounded.Close, contentDescription = "Remove", tint = Muted) }
        }
    }
}

// ---- First-run setup ----

@Composable
private fun Hero(icon: ImageVector, tint: Color) {
    Spacer(Modifier.height(12.dp))
    IconBadge(icon, tint, size = 72)
    Spacer(Modifier.height(20.dp))
}

/** Shown until the app has been made the phone's device owner from a computer. */
@Composable
fun NotOwnerScreen(packageName: String, onCheckAgain: () -> Unit) {
    ParentPage {
        Hero(Icons.Rounded.Usb, Brand)
        ScreenHeader(
            "One-time setup", onBack = null,
            subtitle = "Before Kid Phone can lock this phone it has to become the phone's \"device owner\". " +
                "This is done once, from a computer.",
        )
        Group {
            Column(Modifier.padding(18.dp)) {
                Step(1, "Remove every account from this phone (Settings → Accounts), including Google and Samsung. They can be added back afterwards.")
                Step(2, "Set the screen lock to None or Swipe (Settings → Security).")
                Step(3, "Turn on USB debugging: Settings → About phone → tap Build number 7 times, then Developer options → USB debugging.")
                Step(4, "Connect the phone to the computer and run:", last = true)
                Spacer(Modifier.height(10.dp))
                Text(
                    "adb shell dpm set-device-owner $packageName/.kiosk.AdminReceiver",
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFFE2E8F0),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF1E2433))
                        .padding(14.dp),
                )
            }
        }
        Spacer(Modifier.height(24.dp))
        BigButton("Check again", onCheckAgain)
    }
}

@Composable
private fun Step(n: Int, text: String, last: Boolean = false) {
    Row(Modifier.padding(bottom = if (last) 0.dp else 14.dp)) {
        Box(Modifier.size(28.dp).background(Brand.copy(alpha = 0.12f), CircleShape), contentAlignment = Alignment.Center) {
            Text("$n", fontWeight = FontWeight.ExtraBold, color = Brand, fontSize = 15.sp)
        }
        Spacer(Modifier.width(12.dp))
        Text(text, fontSize = 16.sp, color = Ink, lineHeight = 22.sp)
    }
}

/** Shown when the app is not yet the phone's calling app. */
@Composable
fun DialerSetupScreen(onRequest: () -> Unit) {
    ParentPage {
        Hero(Icons.Rounded.PhoneInTalk, CallGreen)
        ScreenHeader(
            "Make it the phone app", onBack = null,
            subtitle = "Kid Phone needs to be this phone's calling app. That lets it show its own simple " +
                "call screen and turn away callers who are not on the list.",
        )
        Text("If Android asks, choose Kid Phone.", fontSize = 16.sp, color = Muted)
        Spacer(Modifier.height(24.dp))
        BigButton("Make Kid Phone the phone app", onRequest, icon = Icons.Rounded.AppSettingsAlt)
    }
}

/** Last step of the first-run setup. */
@Composable
fun ReadyScreen(onStart: () -> Unit) {
    ParentPage {
        Hero(Icons.Rounded.RocketLaunch, Color(0xFFF2760B))
        ScreenHeader(
            "All set", onBack = null,
            subtitle = "When you press Start, the phone locks into kid mode. To get out, press the small " +
                "padlock at the top right and type the parent PIN.",
        )
        Spacer(Modifier.height(8.dp))
        BigButton("Start kid mode", onStart, container = CallGreen, icon = Icons.Rounded.ChildCare)
    }
}
