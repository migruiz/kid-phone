package ovh.tenjo.kidphone

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ovh.tenjo.kidphone.call.CallManager
import ovh.tenjo.kidphone.data.Contact
import ovh.tenjo.kidphone.data.Store
import ovh.tenjo.kidphone.hub.HomeStatus
import ovh.tenjo.kidphone.hub.KidRequest
import ovh.tenjo.kidphone.hub.Hub
import ovh.tenjo.kidphone.kiosk.Kiosk
import ovh.tenjo.kidphone.kiosk.Loudness
import ovh.tenjo.kidphone.ui.AllowedEditor
import ovh.tenjo.kidphone.ui.AppsButton
import ovh.tenjo.kidphone.ui.AppsEditor
import ovh.tenjo.kidphone.ui.appsSummary
import ovh.tenjo.kidphone.ui.AppsScreen
import ovh.tenjo.kidphone.ui.AskScreen
import ovh.tenjo.kidphone.ui.ContactsEditor
import ovh.tenjo.kidphone.ui.DialerSetupScreen
import ovh.tenjo.kidphone.ui.HubScreen
import ovh.tenjo.kidphone.ui.KidScreen
import ovh.tenjo.kidphone.ui.KidTheme
import ovh.tenjo.kidphone.ui.NewPinScreen
import ovh.tenjo.kidphone.ui.NotOwnerScreen
import ovh.tenjo.kidphone.ui.ParentMenu
import ovh.tenjo.kidphone.ui.ParentPinScreen
import ovh.tenjo.kidphone.ui.ReadyScreen
import ovh.tenjo.kidphone.ui.hubSummary

private enum class Screen { Kid, Apps, Pin, Parent, Contacts, Allowed, ChangePin, Hub, AppsEdit }

/** The child asked to ring an "ask first" person; waiting for or showing the answer. */
private data class Asking(
    val contact: Contact,
    val requestId: String? = null,
    val request: KidRequest? = null,
    val error: String? = null,
)

/**
 * The kid screen as the phone's home screen. Kid mode always runs here; it is switched on as the
 * home screen only while the phone is locked. Same screens as [MainActivity], separate task.
 */
class HomeActivity : MainActivity()

/**
 * The first-time setup, the kid screen, and the parent screens behind the PIN.
 *
 * Opening the app always locks the phone into kid mode (once set up). Leaving kid mode is only
 * possible from the parent menu, after typing the PIN.
 */
open class MainActivity : ComponentActivity() {
    private val store by lazy { Store.get(this) }

    private var owner by mutableStateOf(false)
    private var dialer by mutableStateOf(false)
    private var simReady by mutableStateOf(true)
    private var locked by mutableStateOf(false)
    private var hasPin by mutableStateOf(false)
    private var setupDone by mutableStateOf(false)
    private var setupContactsDone by mutableStateOf(false)
    private var screen by mutableStateOf(Screen.Kid)
    private var asking by mutableStateOf<Asking?>(null)

    /** Set while unlocking, so the screen doesn't lock itself again on the way out. */
    private var leaving = false

    /** This is the phone's home screen (the Home button, or a restart), where kid mode runs. */
    private val isHomeScreen: Boolean get() = this is HomeActivity

    private val dialerRequest =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Dark status-bar icons for the light parent screens, even when the phone is in dark
        // mode. The kid screen switches them to white while it shows.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        onBackPressedDispatcher.addCallback(this) {
            screen = when (screen) {
                Screen.Contacts, Screen.Allowed, Screen.ChangePin, Screen.Hub, Screen.AppsEdit -> Screen.Parent
                else -> Screen.Kid
            }
        }
        refresh()
        if (isHomeScreen) Loudness.watch(this) // kid mode runs here: keep the volume at 100%
        setContent { KidTheme { Content() } }
    }

    override fun onDestroy() {
        if (isHomeScreen) Loudness.unwatch(this)
        super.onDestroy()
    }

    /** In kid mode the volume buttons do nothing, so the ring and call volume stay at 100%. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (store.locked && Loudness.isVolumeKey(event.keyCode)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        screen = Screen.Kid // the Home button always goes back to the call buttons
    }

    override fun onResume() {
        super.onResume()
        // On Android 14+ calls are handed back to the normal phone app on unlock: take them again.
        if (!leaving && store.setupDone && Kiosk.isDeviceOwner(this)) Kiosk.takeDialerRoleSilently(this)
        refresh()
        maybeLock()
    }

    override fun onStop() {
        super.onStop()
        // Screen off, or a call on top: never leave the parent screens open behind it.
        screen = Screen.Kid
    }

    private fun refresh() {
        owner = Kiosk.isDeviceOwner(this)
        dialer = Kiosk.isDefaultDialer(this)
        simReady = CallManager.hasSim(this)
        locked = store.locked
        hasPin = store.hasPin()
        setupDone = store.setupDone
    }

    private fun maybeLock() {
        if (leaving || !owner) return
        // Once locked, stay locked even if something is wrong; a parent can fix it from the menu.
        if (store.locked || (setupDone && hasPin && dialer)) {
            Kiosk.lock(this, isHomeScreen)
            if (!isHomeScreen) {
                // Opened from the app icon: hand over to the home-screen copy, which is now us.
                leaving = true
                runCatching { startActivity(Kiosk.homeIntent()) }
                finish()
                return
            }
            refresh()
        }
    }

    private fun requestDialer() {
        if (Kiosk.takeDialerRoleSilently(this)) {
            refresh()
            maybeLock()
            return
        }
        runCatching { dialerRequest.launch(Kiosk.dialerRoleRequest(this)) }
            .onFailure { Toast.makeText(this, "This phone did not allow it: ${it.message}", Toast.LENGTH_LONG).show() }
    }

    private fun call(contact: Contact) {
        // An "ask first" person needs a parent's OK, unless a call time is running.
        if (store.locked && contact.askFirst && Hub.windowEnd(contact.number) == null) {
            ask(contact)
            return
        }
        val message = when (CallManager.placeCall(this, contact)) {
            CallManager.PlaceResult.Ok -> null
            CallManager.PlaceResult.NoSim -> "No SIM card. This phone can't make calls."
            CallManager.PlaceResult.Failed -> "Could not make the call"
        }
        if (message != null) {
            simReady = CallManager.hasSim(this)
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun ask(contact: Contact) {
        when {
            !Hub.isSetUp -> Toast.makeText(this, "Asking isn't set up on this phone yet. Ask a parent.", Toast.LENGTH_LONG).show()
            Hub.status.value != HomeStatus.AtHome ->
                Toast.makeText(this, "Asking only works at home, on the home Wi-Fi.", Toast.LENGTH_LONG).show()
            else -> {
                asking = Asking(contact)
                lifecycleScope.launch {
                    runCatching { Hub.ask(contact.name, contact.number) }
                        .onSuccess { r -> asking?.takeIf { it.contact == contact }?.let { asking = it.copy(requestId = r.id, request = r) } }
                        .onFailure { e -> asking?.takeIf { it.contact == contact }?.let { asking = it.copy(error = e.message) } }
                }
            }
        }
    }

    private fun unlockAndLeave() {
        leaving = true
        Kiosk.unlock(this)
        goHome()
    }

    private fun removeAndLeave() {
        leaving = true
        Kiosk.removeCompletely(this)
        goHome()
    }

    /** Unlocks just long enough to make this the phone app again, then locks once it is. */
    private fun fixDialer() {
        Kiosk.unlock(this)
        screen = Screen.Kid
        refresh()
    }

    private fun goHome() {
        screen = Screen.Kid
        runCatching {
            startActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        finish()
    }

    @Composable
    private fun Content() {
        val contacts by store.contacts.collectAsState()
        val allowed by store.allowed.collectAsState()
        val childName by store.childName.collectAsState()
        val home by Hub.status.collectAsState()
        val allowedApps by Hub.allowed.collectAsState()
        val appsAvailable by Hub.appsAvailable.collectAsState()
        val requests by Hub.requests.collectAsState()
        val windows by Hub.windows.collectAsState()
        when {
            !owner -> NotOwnerScreen(packageName, onCheckAgain = { refresh(); maybeLock() })

            !hasPin -> NewPinScreen(
                title = "Choose a parent PIN",
                onDone = { store.setPin(it); refresh() },
                onCancel = null,
            )

            !setupDone && !setupContactsDone -> ContactsEditor(
                initial = contacts,
                title = "Call buttons",
                intro = "These are the people he can call. They can always ring him too. Two buttons " +
                    "can stay empty for later.",
                saveLabel = "Next",
                requireOne = true,
                onSave = { store.saveContacts(it); setupContactsDone = true },
                onBack = null,
            )

            !dialer && !locked -> DialerSetupScreen(onRequest = ::requestDialer)

            !setupDone -> ReadyScreen(onStart = { store.setupDone = true; refresh(); maybeLock() })

            else -> InactivityTimeout(active = screen != Screen.Kid, onTimeout = { screen = Screen.Kid }) {
                val ask = asking
                when {
                    screen == Screen.Kid && ask != null -> {
                        val latest = requests.firstOrNull { it.id == ask.requestId }
                        LaunchedEffect(latest) { if (latest != null && latest != ask.request) asking = ask.copy(request = latest) }
                        AskScreen(
                            contact = ask.contact,
                            request = latest ?: ask.request,
                            error = ask.error,
                            onCall = { asking = null; call(ask.contact) },
                            onStopAsking = {
                                ask.requestId?.let { id -> lifecycleScope.launch { Hub.cancel(id) } }
                                asking = null
                            },
                            onClose = { asking = null },
                        )
                    }
                    else -> when (screen) {
                    Screen.Kid -> KidScreen(
                        contacts = contacts,
                        childName = childName,
                        warning = when {
                            !dialer -> "Calls need fixing. Ask a parent."
                            !simReady -> "No SIM card. Calls won't work."
                            else -> null
                        },
                        notice = if (home == HomeStatus.Away) "Not on the home Wi-Fi" else null,
                        apps = when {
                            home == HomeStatus.NotSetUp -> AppsButton.Hidden
                            appsAvailable -> AppsButton.Ready
                            else -> AppsButton.AwayFromHome
                        },
                        onApps = { screen = Screen.Apps },
                        windows = windows,
                        onCall = ::call,
                        onParent = { screen = Screen.Pin },
                    )
                    Screen.Apps -> if (appsAvailable) {
                        AppsScreen(allowed = allowedApps, onBack = { screen = Screen.Kid })
                    } else {
                        LaunchedEffect(Unit) { screen = Screen.Kid } // left home while it was open
                    }
                    Screen.Pin -> ParentPinScreen(
                        store,
                        onUnlocked = { screen = Screen.Parent },
                        onCancel = { screen = Screen.Kid },
                    )
                    Screen.Parent -> ParentMenu(
                        isDialer = dialer,
                        hasSim = simReady,
                        buttonsSummary = contacts.filter { it.isSet }.joinToString(", ") { it.name }
                            .ifEmpty { "Nobody yet" },
                        allowedSummary = when (allowed.size) {
                            0 -> "Only the call buttons"
                            1 -> "The call buttons and 1 more"
                            else -> "The call buttons and ${allowed.size} more"
                        },
                        onUnlock = ::unlockAndLeave,
                        onContacts = { screen = Screen.Contacts },
                        onAllowed = { screen = Screen.Allowed },
                        hubSummary = hubSummary(home),
                        onHub = { screen = Screen.Hub },
                        appsSummary = appsSummary(allowedApps, home),
                        onApps = { screen = Screen.AppsEdit },
                        onChangePin = { screen = Screen.ChangePin },
                        childName = childName,
                        onChildName = store::saveChildName,
                        onFixDialer = ::fixDialer,
                        onBackToKid = { screen = Screen.Kid },
                        onRemove = ::removeAndLeave,
                    )
                    Screen.Contacts -> ContactsEditor(
                        initial = contacts,
                        title = "Call buttons",
                        intro = "People on these buttons can always ring this phone too.",
                        saveLabel = "Save",
                        requireOne = false,
                        onSave = { store.saveContacts(it); screen = Screen.Parent },
                        onBack = { screen = Screen.Parent },
                    )
                    Screen.Allowed -> AllowedEditor(
                        contacts = contacts,
                        initial = allowed,
                        onSave = store::saveAllowed,
                        onBack = { screen = Screen.Parent },
                    )
                    Screen.Hub -> HubScreen(childName = childName, onBack = { screen = Screen.Parent })
                    Screen.AppsEdit -> AppsEditor(allowed = allowedApps, status = home, onBack = { screen = Screen.Parent })
                    Screen.ChangePin -> NewPinScreen(
                        title = "New parent PIN",
                        onDone = { store.setPin(it); screen = Screen.Parent },
                        onCancel = { screen = Screen.Parent },
                    )
                    }
                }
            }
        }
    }
}

/** Goes back to the kid screen if a parent screen is left untouched for three minutes. */
@Composable
private fun InactivityTimeout(active: Boolean, onTimeout: () -> Unit, content: @Composable () -> Unit) {
    var lastTouch by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    if (active) {
        LaunchedEffect(Unit) {
            lastTouch = SystemClock.uptimeMillis()
            while (true) {
                delay(5_000)
                if (SystemClock.uptimeMillis() - lastTouch > 3 * 60_000) {
                    onTimeout()
                    break
                }
            }
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial)
                        lastTouch = SystemClock.uptimeMillis()
                    }
                }
            }
    ) { content() }
}
