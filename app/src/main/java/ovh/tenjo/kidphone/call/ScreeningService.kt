package ovh.tenjo.kidphone.call

import android.telecom.Call
import android.telecom.CallScreeningService
import android.util.Log
import ovh.tenjo.kidphone.data.Store
import ovh.tenjo.kidphone.kiosk.Loudness

/**
 * Android asks this service about every incoming call before the phone rings (because this app is
 * the phone's calling app). While the phone is locked, anyone not on a call button or the allowed
 * list is rejected: the phone never rings and the caller is cut off. Rejected calls still show in
 * the phone's call history, so parents can see who tried. Withheld numbers are rejected too.
 */
class ScreeningService : CallScreeningService() {
    override fun onScreenCall(details: Call.Details) {
        val store = Store.get(this)
        val incoming = details.callDirection == Call.Details.DIRECTION_INCOMING
        val number = details.handle?.schemeSpecificPart
        val allow = !incoming || !store.locked || store.isAllowedCaller(number)

        val response = if (allow) {
            // An allowed caller in kid mode: make sure it rings loudly, before it starts ringing.
            if (incoming && store.locked) Loudness.makeLoud(this)
            CallResponse.Builder().build()
        } else {
            Log.i("KidPhone", "Rejected call from ${number ?: "withheld number"}")
            CallResponse.Builder()
                .setDisallowCall(true)
                .setRejectCall(true)
                .setSkipNotification(true)
                .setSkipCallLog(false)
                .build()
        }
        respondToCall(details, response)
    }
}
