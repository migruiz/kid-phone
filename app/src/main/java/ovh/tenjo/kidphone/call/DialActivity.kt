package ovh.tenjo.kidphone.call

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ovh.tenjo.kidphone.data.Store
import ovh.tenjo.kidphone.ui.CallGreen
import ovh.tenjo.kidphone.ui.Ink
import ovh.tenjo.kidphone.ui.KidTheme
import ovh.tenjo.kidphone.ui.ParentPage
import ovh.tenjo.kidphone.ui.RoundKey
import ovh.tenjo.kidphone.ui.ScreenHeader

/**
 * A plain number keypad, for when the phone is unlocked and a parent taps a phone number
 * somewhere. Android requires a calling app to have one. It refuses to open in kid mode.
 */
class DialActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Store.get(this).locked) {
            finish()
            return
        }
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        val initial = intent?.data?.schemeSpecificPart.orEmpty()
        setContent {
            KidTheme {
                var number by remember { mutableStateOf(initial) }
                ParentPage {
                    ScreenHeader("Call", onBack = ::finish)
                    Text(
                        number.ifEmpty { " " }, fontSize = 38.sp, fontWeight = FontWeight.Bold, color = Ink,
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(28.dp))
                    listOf("123", "456", "789", "*0#").forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            row.forEach { key ->
                                RoundKey(onClick = { number += key }) {
                                    Text(key.toString(), fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Ink)
                                }
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Spacer(Modifier.size(76.dp))
                        RoundKey(
                            color = CallGreen,
                            onClick = {
                                if (number.isNotBlank()) {
                                    when (CallManager.placeCall(this@DialActivity, number)) {
                                        CallManager.PlaceResult.Ok -> finish()
                                        CallManager.PlaceResult.NoSim -> Toast.makeText(this@DialActivity, "No SIM card in this phone", Toast.LENGTH_LONG).show()
                                        CallManager.PlaceResult.Failed -> Toast.makeText(this@DialActivity, "Could not place the call", Toast.LENGTH_LONG).show()
                                    }
                                }
                            },
                        ) {
                            Icon(Icons.Rounded.Call, "Call", tint = Color.White, modifier = Modifier.size(34.dp))
                        }
                        RoundKey(onClick = { number = number.dropLast(1) }, color = Color.Transparent) {
                            Icon(Icons.AutoMirrored.Rounded.Backspace, "Delete", tint = Ink)
                        }
                    }
                }
            }
        }
    }
}
