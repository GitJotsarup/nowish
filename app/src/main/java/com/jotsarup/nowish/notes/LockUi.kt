package com.jotsarup.nowish.notes

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.launch

fun canUseBiometric(activity: FragmentActivity): Boolean =
    BiometricManager.from(activity).canAuthenticate(BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS

fun promptBiometric(activity: FragmentActivity, onSuccess: () -> Unit) {
    val prompt = BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
        },
    )
    prompt.authenticate(
        BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock notes")
            .setSubtitle("Locked notes stay hidden until you unlock")
            .setNegativeButtonText("Use PIN")
            .setAllowedAuthenticators(BIOMETRIC_WEAK)
            .build()
    )
}

/** Asks for the notes PIN (with a fingerprint shortcut). Calls [onUnlocked] on success. */
@Composable
fun UnlockDialog(
    checkPin: suspend (String) -> Boolean,
    allowBiometric: Boolean,
    onUnlocked: () -> Unit,
    onDismiss: () -> Unit,
) {
    val activity = LocalContext.current as? FragmentActivity
    val biometric = allowBiometric && activity != null && canUseBiometric(activity)
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        if (biometric) promptBiometric(activity!!) { onUnlocked() } else focus.requestFocus()
    }

    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    fun submit() {
        if (checking) return
        checking = true
        scope.launch {
            val ok = checkPin(pin)
            checking = false
            if (ok) onUnlocked() else {
                error = true
                pin = ""
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Lock, null) },
        title = { Text("Locked notes") },
        text = {
            Column {
                OutlinedTextField(
                    value = pin,
                    onValueChange = { v -> pin = v.filter { it.isDigit() }.take(8); error = false },
                    label = { Text("PIN") },
                    isError = error,
                    supportingText = { if (error) Text("Wrong PIN") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                if (biometric) {
                    TextButton(onClick = { promptBiometric(activity!!) { onUnlocked() } }) {
                        Icon(Icons.Rounded.Fingerprint, null)
                        Text("  Use fingerprint")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = ::submit, enabled = pin.length >= 4 && !checking) { Text(if (checking) "Checking…" else "Unlock") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** First-time PIN setup (enter twice). */
@Composable
fun SetPinDialog(onSet: (String) -> Unit, onDismiss: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val mismatch = confirm.length >= 4 && confirm != pin
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Lock, null) },
        title = { Text("Set a notes PIN") },
        text = {
            Column {
                Text(
                    "Locked notes stay hidden until you enter this PIN (or use your fingerprint). " +
                        "The same PIN works on the website.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = pin,
                    onValueChange = { v -> pin = v.filter { it.isDigit() }.take(8) },
                    label = { Text("PIN (4–8 digits)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { v -> confirm = v.filter { it.isDigit() }.take(8) },
                    label = { Text("Confirm PIN") },
                    isError = mismatch,
                    supportingText = { if (mismatch) Text("PINs don't match") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSet(pin) }, enabled = pin.length >= 4 && pin == confirm) { Text("Set PIN") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
