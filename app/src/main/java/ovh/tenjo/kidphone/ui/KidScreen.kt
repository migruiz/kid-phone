package ovh.tenjo.kidphone.ui

import android.app.Activity
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import android.text.format.DateFormat
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PanTool
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.WindowCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import ovh.tenjo.kidphone.data.Contact
import ovh.tenjo.kidphone.data.PhoneNumbers
import ovh.tenjo.kidphone.hub.Apps
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

val NightTop = Color(0xFF161C36)
val NightBottom = Color(0xFF0A0E1C)

/** The Apps button on the main kid screen: hidden until the hub is set up, dimmed away from home. */
enum class AppsButton { Hidden, Ready, AwayFromHome }

/** The dark night-sky background of the kid screens, with two soft coloured glows. */
@Composable
fun KidBackground(content: @Composable () -> Unit) {
    LightSystemBarIcons()
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(NightTop, NightBottom)))) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.width * 0.9f
            drawCircle(
                Brush.radialGradient(listOf(Color(0x553B6EF5), Color.Transparent), center = Offset.Zero, radius = r),
                radius = r, center = Offset.Zero,
            )
            val corner = Offset(size.width, size.height)
            drawCircle(
                Brush.radialGradient(listOf(Color(0x40E23670), Color.Transparent), center = corner, radius = r),
                radius = r, center = corner,
            )
        }
        content()
    }
}

/** A round see-through button for the kid screen's top line. */
@Composable
fun RoundTopButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
    bright: Boolean,
    size: Dp = 42.dp,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = Color.White.copy(alpha = if (bright) 0.16f else 0.08f),
        modifier = Modifier.size(size).border(1.dp, Color.White.copy(alpha = if (bright) 0.25f else 0.12f), CircleShape),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                icon, contentDescription = description,
                tint = Color.White.copy(alpha = if (bright) 0.95f else 0.45f),
                modifier = Modifier.size(size * 0.48f),
            )
        }
    }
}

/**
 * The main kid screen: "Hi <name>" and the time, the battery, a small padlock for parents, and two
 * big buttons, Contacts and Apps. Calling is one step further in, on [ContactsScreen], so a stray
 * tap here never rings anyone.
 */
@Composable
fun KidHomeScreen(
    contacts: List<Contact>,
    childName: String,
    warning: String?,
    notice: String?,
    apps: AppsButton,
    /** The apps the parents switched on, shown small on the Apps button. */
    allowedApps: Set<String>,
    onContacts: () -> Unit,
    onApps: () -> Unit,
    onParent: () -> Unit,
) {
    val people = contacts.filter { it.isSet }
    val now = rememberNow()
    val context = LocalContext.current
    KidBackground {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            KidHeader(childName, now, onBack = null, onParent = onParent)
            Banners(warning, notice)
            Spacer(Modifier.height(20.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                HomeTile(
                    label = "Contacts",
                    icon = Icons.Rounded.Call,
                    color = CallGreen,
                    onClick = onContacts,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) {
                    if (people.isEmpty()) {
                        TileNote("No one yet")
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { people.forEach { Face(it, 42.dp) } }
                    }
                }
                if (apps != AppsButton.Hidden) {
                    val away = apps == AppsButton.AwayFromHome
                    HomeTile(
                        label = "Apps",
                        icon = Icons.Rounded.Apps,
                        color = Brand,
                        dimmed = away,
                        onClick = {
                            if (away) {
                                Toast.makeText(context, "Apps only work at home, on the home Wi-Fi.", Toast.LENGTH_LONG).show()
                            } else {
                                onApps()
                            }
                        },
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    ) {
                        if (away) TileNote("Only at home") else AppIcons(allowedApps)
                    }
                }
            }
        }
    }
}

/** A big button on the main kid screen: a round coloured icon, a word, and a small preview. */
@Composable
private fun HomeTile(
    label: String,
    icon: ImageVector,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier,
    dimmed: Boolean = false,
    preview: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(34.dp)
    BoxWithConstraints(
        modifier
            .bounceClick(onClick = onClick)
            .alpha(if (dimmed) 0.5f else 1f)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(color.copy(alpha = 0.32f), Color.White.copy(alpha = 0.04f))))
            .border(1.dp, Color.White.copy(alpha = 0.10f), shape),
        contentAlignment = Alignment.Center,
    ) {
        // On short screens the preview is left out and the icon shrinks, so the word always fits.
        val roomy = maxHeight >= 220.dp
        val circle = minOf(maxHeight * (if (roomy) 0.36f else 0.5f), 100.dp)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .size(circle)
                    .shadow(18.dp, CircleShape, ambientColor = color, spotColor = color)
                    .background(color, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(circle * 0.5f))
            }
            Spacer(Modifier.height(10.dp))
            Text(label, fontSize = 34.sp, fontWeight = FontWeight.Black, color = Color.White, maxLines = 1)
            if (roomy) {
                Spacer(Modifier.height(10.dp))
                Box(Modifier.heightIn(min = 42.dp), contentAlignment = Alignment.Center) { preview() }
            }
        }
    }
}

@Composable
private fun TileNote(text: String) {
    Text(text, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.7f))
}

/** Up to five of the switched-on apps' icons, in the same order as the Apps screen. */
@Composable
private fun AppIcons(allowed: Set<String>) {
    val context = LocalContext.current
    // Loaded off the main thread: listing the phone's apps takes a moment.
    val icons by produceState<List<ImageBitmap>?>(null, allowed) {
        value = withContext(Dispatchers.IO) {
            Apps.launchable(context).filter { it.packageName in allowed }.take(5).mapNotNull { app ->
                runCatching {
                    context.packageManager.getApplicationIcon(app.packageName).toBitmap(120, 120).asImageBitmap()
                }.getOrNull()
            }
        }
    }
    val loaded = icons ?: return
    if (loaded.isEmpty()) {
        TileNote("No apps yet")
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            loaded.forEach { Image(it, contentDescription = null, modifier = Modifier.size(42.dp)) }
        }
    }
}

/**
 * The people he can call: "Hi <name>" and the time, the battery, one wide row per person (picture,
 * name and a big green call button), a back button to the main screen, and the parents' padlock.
 */
@Composable
fun ContactsScreen(
    contacts: List<Contact>,
    childName: String,
    warning: String?,
    notice: String?,
    /** Approved call times for "ask first" buttons: number → when it ends (wall clock). */
    windows: Map<String, Long>,
    onCall: (Contact) -> Unit,
    onBack: () -> Unit,
    onParent: () -> Unit,
) {
    val people = contacts.filter { it.isSet }
    val now = rememberNow()
    KidBackground {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            KidHeader(childName, now, onBack = onBack, onParent = onParent)
            Banners(warning, notice)
            Spacer(Modifier.height(20.dp))
            if (people.isEmpty()) {
                NobodyYet(Modifier.weight(1f))
            } else {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    people.forEach { person ->
                        val windowEnd = if (person.askFirst) {
                            windows.entries.firstOrNull { PhoneNumbers.same(it.key, person.number) }?.value
                                ?.takeIf { it > System.currentTimeMillis() }
                        } else null
                        PersonRow(person, windowEnd, now, Modifier.weight(1f).heightIn(max = 180.dp).fillMaxWidth(), onCall)
                    }
                }
            }
        }
    }
}

/**
 * The top of the kid screens: the greeting with the parents' padlock at the end (and a back button
 * at the start when [onBack] is given), then the clock with the battery at the end.
 */
@Composable
private fun KidHeader(childName: String, now: LocalDateTime, onBack: (() -> Unit)?, onParent: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            RoundTopButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onClick = onBack, bright = true, size = 46.dp)
            Spacer(Modifier.width(12.dp))
        }
        Text(
            if (childName.isNotBlank()) "Hi $childName 👋" else greeting(now),
            fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = Color.White.copy(alpha = 0.92f),
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(10.dp))
        RoundTopButton(Icons.Rounded.Lock, "Parents", onClick = onParent, bright = false)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            timeText(now), fontSize = 46.sp, fontWeight = FontWeight.Black, color = Color.White,
            lineHeight = 50.sp, modifier = Modifier.weight(1f),
        )
        BatteryBadge()
    }
}

/** The red "calls need fixing / no SIM" warning and the amber "not on the home Wi-Fi" notice. */
@Composable
private fun Banners(warning: String?, notice: String?) {
    if (warning != null) {
        Spacer(Modifier.height(12.dp))
        Banner(Icons.Rounded.WarningAmber, warning, Color(0x33EF4444), Color(0xFFFF9B9B), Color(0xFFFFC2C2))
    }
    if (notice != null) {
        Spacer(Modifier.height(12.dp))
        Banner(Icons.Rounded.WifiOff, notice, Color(0x33F59E0B), Color(0xFFFFD27A), Color(0xFFFFE2A8))
    }
}

@Composable
private fun Banner(icon: ImageVector, text: String, background: Color, iconTint: Color, textColor: Color) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(background)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = iconTint)
        Spacer(Modifier.width(10.dp))
        Text(text, color = textColor, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}

/** White status-bar icons while this dark screen shows; the previous colours come back after. */
@Composable
private fun LightSystemBarIcons() {
    val view = LocalView.current
    if (view.isInEditMode) return
    DisposableEffect(view) {
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val status = controller?.isAppearanceLightStatusBars
        val nav = controller?.isAppearanceLightNavigationBars
        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = false
        onDispose {
            if (status != null) controller.isAppearanceLightStatusBars = status
            if (nav != null) controller.isAppearanceLightNavigationBars = nav
        }
    }
}

/** The battery in a see-through badge; the fill turns orange below 50% and red below 20%. */
@Composable
private fun BatteryBadge() {
    val battery = rememberBattery()
    val low = battery.percent in 0..20
    Row(
        Modifier
            .clip(CircleShape)
            .background(if (low) Color(0x40EF4444) else Color.White.copy(alpha = 0.08f))
            .border(1.dp, if (low) Color(0x80EF4444) else Color.White.copy(alpha = 0.12f), CircleShape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BatteryGlyph(battery.percent, batteryColor(battery), Color.White.copy(alpha = 0.85f))
        Spacer(Modifier.width(8.dp))
        Text(
            if (battery.percent >= 0) "${battery.percent}%" else "–",
            fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, color = Color.White,
        )
        if (battery.charging) {
            Icon(Icons.Rounded.Bolt, contentDescription = "Charging", tint = Color(0xFFFFC83D), modifier = Modifier.size(20.dp))
        }
    }
}

/** The amber of an "ask first" button that needs a parent's OK. */
private val AskAmber = Color(0xFFF59E0B)

/**
 * One person: picture, name and green call button on top, and underneath their phone number
 * across the whole width, as big as fits on one line, so the child gets to know it by heart.
 * An "ask first" person has an amber hand button instead, until a parent says yes; then the button
 * is green and shows the minutes left.
 */
@Composable
private fun PersonRow(contact: Contact, windowEnd: Long?, now: LocalDateTime, modifier: Modifier, onCall: (Contact) -> Unit) {
    val mustAsk = contact.askFirst && windowEnd == null
    val buttonColor = if (mustAsk) AskAmber else CallGreen
    // Recomputed each minute along with the clock.
    val minutesLeft = remember(now, windowEnd) {
        windowEnd?.let { ((it - System.currentTimeMillis()) / 60_000 + 1).coerceAtLeast(1) }
    }
    val colors = tileColor(contact.color)
    val shape = RoundedCornerShape(30.dp)
    val showNumber = contact.name.isNotBlank()
    BoxWithConstraints(
        modifier
            .bounceClick { onCall(contact) }
            .clip(shape)
            .background(Brush.horizontalGradient(listOf(colors.dark.copy(alpha = 0.45f), Color.White.copy(alpha = 0.04f))))
            .border(1.dp, Color.White.copy(alpha = 0.10f), shape),
        contentAlignment = Alignment.Center,
    ) {
        val padding = 14.dp
        val inner = maxHeight - padding * 2
        // With the number underneath, the top line takes at most half the height.
        val face = minOf(if (showNumber) inner * 0.5f else inner * 0.66f, 66.dp)
        val callSize = minOf(face, 58.dp)
        Column(
            Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = padding),
            verticalArrangement = Arrangement.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Face(contact, face)
                Spacer(Modifier.width(12.dp))
                // The name gets the whole width; an "ask first" badge goes underneath it.
                Column(Modifier.weight(1f)) {
                    Text(
                        contact.name.ifBlank { prettyNumber(contact.number) },
                        fontSize = 28.sp, fontWeight = FontWeight.Black, color = Color.White,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    if (contact.askFirst) {
                        Text(
                            if (minutesLeft != null) "$minutesLeft min left" else "Ask first",
                            fontSize = 14.sp, fontWeight = FontWeight.ExtraBold,
                            color = if (minutesLeft != null) Color(0xFFB7F7CF) else Color(0xFFFFE2A8),
                            modifier = Modifier
                                .padding(top = 2.dp)
                                .clip(CircleShape)
                                .background((if (minutesLeft != null) CallGreen else AskAmber).copy(alpha = 0.22f))
                                .padding(horizontal = 10.dp, vertical = 3.dp),
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .size(callSize)
                        .shadow(18.dp, CircleShape, ambientColor = buttonColor, spotColor = buttonColor)
                        .background(buttonColor, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (mustAsk) Icons.Rounded.PanTool else Icons.Rounded.Call,
                        contentDescription = null, tint = Color.White, modifier = Modifier.size(callSize * 0.45f),
                    )
                }
            }
            if (showNumber) {
                Spacer(Modifier.height(8.dp))
                // Grows to fill the space left, up to 40sp, always on one line.
                BasicText(
                    prettyNumber(contact.number),
                    style = TextStyle(
                        color = Color.White.copy(alpha = 0.92f),
                        fontFamily = Nunito,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.sp,
                    ),
                    maxLines = 1,
                    softWrap = false,
                    autoSize = TextAutoSize.StepBased(minFontSize = 14.sp, maxFontSize = 40.sp, stepSize = 1.sp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}


/** The person's picture in a circle of their colour, with a faint white ring. */
@Composable
private fun Face(contact: Contact, size: Dp) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(tileColor(contact.color).brush)
            .border(3.dp, Color.White.copy(alpha = 0.25f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Avatar(contact.name, contact.emoji, size, Color.Transparent)
    }
}

@Composable
private fun NobodyYet(modifier: Modifier) {
    Column(
        modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("📞", fontSize = 64.sp)
        Spacer(Modifier.height(12.dp))
        Text("No one to call yet", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
        Spacer(Modifier.height(6.dp))
        Text(
            "A parent can add people with the padlock button.",
            fontSize = 16.sp, color = Color.White.copy(alpha = 0.6f), textAlign = TextAlign.Center,
        )
    }
}

/** "0871234567" shown as "087 123 4567", in the style of the SIM's country (Ireland if unknown). */
@Composable
private fun prettyNumber(number: String): String {
    val context = LocalContext.current
    return remember(number) {
        val country = runCatching {
            context.getSystemService(TelephonyManager::class.java)?.simCountryIso
        }.getOrNull()?.uppercase()?.ifBlank { null } ?: "IE"
        PhoneNumberUtils.formatNumber(number.trim(), country) ?: number.trim()
    }
}

// ---- Time ----

/** The current time, updated on the minute. */
@Composable
private fun rememberNow(): LocalDateTime {
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalDateTime.now()
            delay((60 - now.second) * 1000L)
        }
    }
    return now
}

/** "14:05" or "2:05", following the phone's 24-hour setting. */
@Composable
private fun timeText(now: LocalDateTime): String {
    val pattern = if (DateFormat.is24HourFormat(LocalContext.current)) "HH:mm" else "h:mm"
    return now.format(DateTimeFormatter.ofPattern(pattern))
}

private fun greeting(now: LocalDateTime): String = when (now.hour) {
    in 5..11 -> "Good morning"
    in 12..17 -> "Good afternoon"
    else -> "Good evening"
}
