package ovh.tenjo.kidphone.data

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlin.math.min

/**
 * One of the four big call buttons. A button with no number is hidden. [emoji] is the picture on
 * the button; when empty the first letter of the name is shown. [color] is an index into the
 * button colours, or -1 for someone who has no button.
 */
data class Contact(
    val name: String = "",
    val number: String = "",
    val color: Int = 0,
    val emoji: String = "",
) {
    val isSet: Boolean get() = PhoneNumbers.digits(number).isNotEmpty()
}

/** Someone who may ring the phone but has no call button. */
data class AllowedCaller(val name: String, val number: String)

sealed interface PinCheck {
    data object Ok : PinCheck
    data class Wrong(val triesLeft: Int) : PinCheck
    data class Wait(val seconds: Long) : PinCheck
}

/** Everything the app remembers, kept in the app's private storage on the phone. */
class Store private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("kidphone", Context.MODE_PRIVATE)

    private val _contacts = MutableStateFlow(loadContacts())
    val contacts: StateFlow<List<Contact>> = _contacts

    private val _allowed = MutableStateFlow(loadAllowed())
    val allowed: StateFlow<List<AllowedCaller>> = _allowed

    /** The child's name, for "Hi …" on the kid screen. Empty shows "Good morning" etc. instead. */
    private val _childName = MutableStateFlow(prefs.getString(KEY_CHILD_NAME, DEFAULT_CHILD_NAME) ?: "")
    val childName: StateFlow<String> = _childName

    fun saveChildName(name: String) {
        val clean = name.trim().take(20)
        prefs.edit().putString(KEY_CHILD_NAME, clean).apply()
        _childName.value = clean
    }

    /** True while the phone is in kid mode. Survives restarts, which is how a reboot re-locks. */
    var locked: Boolean
        get() = prefs.getBoolean(KEY_LOCKED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_LOCKED, value).commit()
        }

    /** The first-run setup (PIN and call buttons) has been finished. */
    var setupDone: Boolean
        get() = prefs.getBoolean(KEY_SETUP_DONE, false)
        set(value) {
            prefs.edit().putBoolean(KEY_SETUP_DONE, value).apply()
        }

    /** The phone app that handled calls before this one took over, given back on unlock. */
    var previousDialer: String?
        get() = prefs.getString(KEY_PREVIOUS_DIALER, null)
        set(value) {
            prefs.edit().putString(KEY_PREVIOUS_DIALER, value).apply()
        }

    fun saveContacts(list: List<Contact>) {
        val padded = (list + List(SLOTS) { Contact(color = it) }).take(SLOTS)
        val json = JSONArray()
        padded.forEach {
            json.put(
                JSONObject().put("name", it.name).put("number", it.number)
                    .put("color", it.color).put("emoji", it.emoji)
            )
        }
        prefs.edit().putString(KEY_CONTACTS, json.toString()).apply()
        _contacts.value = padded
    }

    fun saveAllowed(list: List<AllowedCaller>) {
        val json = JSONArray()
        list.forEach { json.put(JSONObject().put("name", it.name).put("number", it.number)) }
        prefs.edit().putString(KEY_ALLOWED, json.toString()).apply()
        _allowed.value = list
    }

    /** Whether a caller may ring while the phone is locked: anyone on a call button or the list. */
    fun isAllowedCaller(number: String?): Boolean {
        if (PhoneNumbers.digits(number).isEmpty()) return false // withheld / private numbers
        return allowedNumbers().any { PhoneNumbers.same(it, number) }
    }

    /**
     * Whether the child may ring this number while the phone is locked: only the call buttons.
     * Other apps the parents allow could try to dial anyone; those calls are hung up.
     */
    fun mayCall(number: String?): Boolean =
        _contacts.value.any { it.isSet && PhoneNumbers.same(it.number, number) }

    /** Who a number belongs to: their call button, or an allowed caller (no colour or picture). */
    fun lookup(number: String?): Contact? {
        _contacts.value.firstOrNull { it.isSet && PhoneNumbers.same(it.number, number) }?.let { return it }
        return _allowed.value.firstOrNull { PhoneNumbers.same(it.number, number) }
            ?.let { Contact(it.name, it.number, color = -1) }
    }

    private fun allowedNumbers(): List<String> =
        _contacts.value.filter { it.isSet }.map { it.number } + _allowed.value.map { it.number }

    // ---- Parent PIN ----

    fun hasPin(): Boolean = prefs.contains(KEY_PIN_HASH)

    fun setPin(pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs.edit()
            .putString(KEY_PIN_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(KEY_PIN_HASH, Base64.encodeToString(hash(pin, salt), Base64.NO_WRAP))
            .putInt(KEY_PIN_FAILS, 0)
            .putLong(KEY_PIN_WAIT_UNTIL, 0)
            .commit()
    }

    /**
     * Checks a PIN typed at the parent screen. Five wrong tries in a row make it wait a minute,
     * and each further wrong try adds another minute (up to 15), so guessing is impractical.
     */
    fun checkPin(pin: String, now: Long = System.currentTimeMillis()): PinCheck {
        val waitUntil = prefs.getLong(KEY_PIN_WAIT_UNTIL, 0)
        if (now < waitUntil) return PinCheck.Wait((waitUntil - now + 999) / 1000)

        val salt = Base64.decode(prefs.getString(KEY_PIN_SALT, "") ?: "", Base64.NO_WRAP)
        val expected = Base64.decode(prefs.getString(KEY_PIN_HASH, "") ?: "", Base64.NO_WRAP)
        if (expected.isNotEmpty() && MessageDigest.isEqual(hash(pin, salt), expected)) {
            prefs.edit().putInt(KEY_PIN_FAILS, 0).putLong(KEY_PIN_WAIT_UNTIL, 0).apply()
            return PinCheck.Ok
        }

        val fails = prefs.getInt(KEY_PIN_FAILS, 0) + 1
        val editor = prefs.edit().putInt(KEY_PIN_FAILS, fails)
        return if (fails >= FREE_TRIES) {
            val minutes = min(fails - FREE_TRIES + 1, 15).toLong()
            editor.putLong(KEY_PIN_WAIT_UNTIL, now + minutes * 60_000).apply()
            PinCheck.Wait(minutes * 60)
        } else {
            editor.apply()
            PinCheck.Wrong(FREE_TRIES - fails)
        }
    }

    private fun hash(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, 20_000, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    private fun loadContacts(): List<Contact> {
        val raw = prefs.getString(KEY_CONTACTS, null) ?: return DEFAULT_CONTACTS
        return runCatching {
            val json = JSONArray(raw)
            List(SLOTS) { i ->
                val o = json.optJSONObject(i)
                if (o == null) Contact(color = i)
                else Contact(o.optString("name"), o.optString("number"), o.optInt("color", i), o.optString("emoji"))
            }
        }.getOrDefault(DEFAULT_CONTACTS)
    }

    private fun loadAllowed(): List<AllowedCaller> {
        val raw = prefs.getString(KEY_ALLOWED, null) ?: return emptyList()
        return runCatching {
            val json = JSONArray(raw)
            List(json.length()) { i ->
                val o = json.getJSONObject(i)
                AllowedCaller(o.optString("name"), o.optString("number"))
            }
        }.getOrDefault(emptyList())
    }

    companion object {
        const val SLOTS = 4
        private const val FREE_TRIES = 5

        private const val KEY_LOCKED = "locked"
        private const val KEY_SETUP_DONE = "setup_done"
        private const val KEY_PREVIOUS_DIALER = "previous_dialer"
        private const val KEY_CONTACTS = "contacts"
        private const val KEY_ALLOWED = "allowed"
        private const val KEY_CHILD_NAME = "child_name"
        private const val DEFAULT_CHILD_NAME = "Alejandro"
        private const val KEY_PIN_HASH = "pin_hash"
        private const val KEY_PIN_SALT = "pin_salt"
        private const val KEY_PIN_FAILS = "pin_fails"
        private const val KEY_PIN_WAIT_UNTIL = "pin_wait_until"

        private val DEFAULT_CONTACTS = listOf(
            Contact("Mum", "", 0, "👩"),
            Contact("Dad", "", 1, "👨"),
            Contact("", "", 2),
            Contact("", "", 3),
        )

        @Volatile private var instance: Store? = null

        fun get(context: Context): Store =
            instance ?: synchronized(this) {
                instance ?: Store(context.applicationContext).also { instance = it }
            }
    }
}
