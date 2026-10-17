package ovh.tenjo.kidphone.parent.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.SupervisorAccount
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ovh.tenjo.kidphone.parent.Hub
import ovh.tenjo.kidphone.ui.BigButton
import ovh.tenjo.kidphone.ui.Danger
import ovh.tenjo.kidphone.ui.Group
import ovh.tenjo.kidphone.ui.IconBadge
import ovh.tenjo.kidphone.ui.Ink
import ovh.tenjo.kidphone.ui.Muted
import ovh.tenjo.kidphone.ui.ParentPage
import ovh.tenjo.kidphone.ui.ScreenHeader
import ovh.tenjo.kidphone.ui.fieldColors
import androidx.compose.ui.graphics.Color

/** First run: pair this phone with the child's phone, using the code it shows. */
@Composable
fun PairScreen() {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(Hub.myName) }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    ParentPage {
        Spacer(Modifier.height(12.dp))
        IconBadge(Icons.Rounded.SupervisorAccount, Color(0xFF7442EA), size = 72)
        Spacer(Modifier.height(20.dp))
        ScreenHeader(
            "Pair with your child's phone", onBack = null,
            subtitle = "On the child's phone: tap the padlock, type the parent PIN, then Home hub → " +
                "Add a parent phone. Type the code it shows here.",
        )
        Group {
            Column(Modifier.padding(16.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it.take(20) },
                    label = { Text("Your name") }, placeholder = { Text("e.g. Dad") }, singleLine = true,
                    shape = RoundedCornerShape(14.dp), colors = fieldColors(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Shown to the other parent, e.g. \"Allowed by Dad\".",
                    color = Muted, fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 10.dp),
                )
                OutlinedTextField(
                    value = code, onValueChange = { code = it.filter(Char::isDigit).take(6) },
                    label = { Text("Code") }, singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = Ink, letterSpacing = 6.sp),
                    shape = RoundedCornerShape(14.dp), colors = fieldColors(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(14.dp))
                BigButton(
                    if (busy) "Pairing…" else "Pair", icon = Icons.Rounded.Link,
                    enabled = !busy && code.length == 6 && name.isNotBlank(),
                    onClick = {
                        busy = true
                        error = null
                        scope.launch {
                            runCatching { Hub.pair(code, name.trim()) }.onFailure { error = it.message }
                            busy = false
                        }
                    },
                )
            }
        }
        if (error != null) {
            Spacer(Modifier.height(12.dp))
            Text(error!!, color = Danger, fontWeight = FontWeight.Bold)
        }
    }
}
