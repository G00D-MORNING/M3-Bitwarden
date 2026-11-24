package com.framepersecond.m3bitwarden.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import com.framepersecond.m3bitwarden.ui.components.SpinningShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.framepersecond.m3bitwarden.App
import com.framepersecond.m3bitwarden.AppSettings
import com.framepersecond.m3bitwarden.android.autofill.AutofillHome
import com.framepersecond.m3bitwarden.android.vault.AndroidVault
import com.framepersecond.m3bitwarden.android.vault.M3BConfig
import com.framepersecond.m3bitwarden.data.VaultSession
import com.framepersecond.m3bitwarden.platform.openUrl
import com.framepersecond.m3bitwarden.ui.icons.Symbols
import com.framepersecond.m3bitwarden.ui.theme.M3BitwardenTheme
import kotlinx.coroutines.launch

/** Certificate → sign in → unlock → vault, all in Material 3. */
@Composable
fun AndroidRoot(demo: Boolean = false) {
    if (demo) {
        // Debug builds only: the vault UI with sample data, without server or certificate.
        M3BitwardenTheme(themeMode = AppSettings.themeMode, dynamicColor = AppSettings.dynamicColor) { App(platformSettings = { AutofillHome() }) }
        return
    }
    val context = LocalContext.current
    val vault = remember { AndroidVault.get(context) }
    // Locking from the vault UI goes through VaultSession; mirror it into the phase.
    LaunchedEffect(VaultSession.isUnlocked) { vault.refreshPhase() }

    M3BitwardenTheme(themeMode = AppSettings.themeMode, dynamicColor = AppSettings.dynamicColor) {
        AnimatedContent(vault.phase, label = "phase") { phase ->
            when (phase) {
                AndroidVault.Phase.NeedsCertificate -> CertificateScreen(vault)
                AndroidVault.Phase.SignedOut -> LoginScreen(vault)
                AndroidVault.Phase.Locked -> UnlockScreen(vault)
                AndroidVault.Phase.Unlocked -> App(platformSettings = { AutofillHome() })
            }
        }
    }
}

@Composable
private fun SetupPage(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String?, busy: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(32.dp))
            SpinningShape(MaterialShapes.Cookie12Sided, MaterialTheme.colorScheme.primaryContainer, 176.dp, busy = busy) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(56.dp))
            }
            Spacer(Modifier.height(24.dp))
            Text(title, style = MaterialTheme.typography.displaySmallEmphasized, textAlign = TextAlign.Center)
            if (subtitle != null) {
                Spacer(Modifier.height(8.dp))
                Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(32.dp))
            Column(Modifier.widthIn(max = 480.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
        }
    }
}

@Composable
private fun ErrorText(text: String?) {
    if (text == null) return
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Text(text, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun BusyButton(label: String, busy: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled && !busy, modifier = Modifier.fillMaxWidth().height(56.dp)) {
        if (busy) LoadingIndicator(Modifier.size(32.dp), color = MaterialTheme.colorScheme.onPrimary) else Text(label, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun PasswordField(label: String, value: String, onChange: (String) -> Unit, onDone: () -> Unit) {
    var revealed by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (revealed) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done, autoCorrectEnabled = false),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        trailingIcon = {
            androidx.compose.material3.IconButton(onClick = { revealed = !revealed }) {
                Icon(if (revealed) Symbols.VisibilityOff else Symbols.Visibility, if (revealed) "Verbergen" else "Anzeigen")
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

// MARK: Certificate

@Composable
private fun CertificateScreen(vault: AndroidVault) {
    val context = LocalContext.current
    var csr by rememberSaveable { mutableStateOf("") }
    var cert by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf(false) }

    fun makeCsr() {
        error = null
        runCatching { csr = vault.certificate.makeSigningRequest("M3B ${Build.MANUFACTURER} ${Build.MODEL}") }
            .onFailure { error = it.message }
    }
    LaunchedEffect(Unit) { if (vault.certificate.hasKey && csr.isEmpty()) makeCsr() }

    SetupPage(Symbols.VpnKey, "Gerät freischalten", "Der Server lässt nur Geräte mit eigenem Zertifikat herein. Der Schlüssel dazu entsteht auf diesem Gerät und verlässt es nie.") {
        Step("1", "Anfrage erzeugen") {
            if (csr.isEmpty()) {
                Button(onClick = ::makeCsr, modifier = Modifier.fillMaxWidth()) { Text("Zertifikatsanfrage erzeugen") }
            } else {
                Text(csr, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), maxLines = 6, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = {
                        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("CSR", csr))
                        copied = true
                    }, modifier = Modifier.weight(1f)) { Text(if (copied) "Kopiert" else "Kopieren") }
                    OutlinedButton(onClick = {
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, csr)
                        context.startActivity(Intent.createChooser(send, "Anfrage teilen"))
                    }, modifier = Modifier.weight(1f)) { Text("Teilen") }
                }
            }
        }
        Step("2", "In Cloudflare ausstellen") {
            Text(
                "Cloudflare → jochenderrochen.de → SSL/TLS → Client-Zertifikate → Zertifikat erstellen → „Eigenen privaten Schlüssel und CSR verwenden“ → Anfrage einfügen → Erstellen → Zertifikat kopieren.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Step("3", "Zertifikat einfügen") {
            OutlinedTextField(
                value = cert,
                onValueChange = { cert = it },
                label = { Text("-----BEGIN CERTIFICATE-----") },
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                minLines = 4,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    cert = context.getSystemService(ClipboardManager::class.java)?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                }, modifier = Modifier.weight(1f)) { Text("Einfügen") }
                Button(onClick = {
                    error = null
                    runCatching { vault.certificate.install(cert) }
                        .onSuccess { vault.refreshPhase() }
                        .onFailure { error = it.message }
                }, enabled = cert.contains("BEGIN CERTIFICATE"), modifier = Modifier.weight(1f)) { Text("Speichern") }
            }
        }
        ErrorText(error)
    }
}

@Composable
private fun Step(number: String, title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp)) {
                    Text(number, color = MaterialTheme.colorScheme.onPrimary, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 3.dp), style = MaterialTheme.typography.labelLarge)
                }
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 12.dp))
            }
            content()
        }
    }
}

// MARK: Login

@Composable
private fun LoginScreen(vault: AndroidVault) {
    var register by rememberSaveable { mutableStateOf(false) }
    if (register) return RegisterScreen(vault) { register = false }
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    val twoFactor = vault.twoFactorProviders.isNotEmpty()

    SetupPage(
        Symbols.ShieldLockFill,
        if (twoFactor) "Zwei-Faktor-Anmeldung" else "Anmelden",
        if (twoFactor) {
            if (vault.twoFactorProviders.contains(0)) "Gib den Code aus deiner Authenticator-App ein." else "Wir haben dir einen Code per E-Mail geschickt."
        } else {
            "Server: ${M3BConfig.serverHost}"
        },
    ) {
        if (!twoFactor) {
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("E-Mail") },
                singleLine = true,
                leadingIcon = { Icon(Symbols.Mail, null) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
            )
            PasswordField("Master-Passwort", password, { password = it }) { if (email.isNotBlank() && password.isNotEmpty()) vault.login(email, password) }
            ErrorText(vault.error)
            BusyButton("Anmelden", vault.busy, email.isNotBlank() && password.isNotEmpty()) { vault.login(email, password) }
            androidx.compose.material3.FilledTonalButton(onClick = { register = true; vault.cancelRegistration() }, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Neues Konto erstellen") }
        } else {
            OutlinedTextField(
                value = code,
                onValueChange = { code = it.filter(Char::isLetterOrDigit).take(8) },
                label = { Text("Code") },
                singleLine = true,
                textStyle = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (code.isNotEmpty()) vault.login(email, password, code) }),
                modifier = Modifier.fillMaxWidth(),
            )
            ErrorText(vault.error)
            BusyButton("Bestätigen", vault.busy, code.isNotEmpty()) { vault.login(email, password, code) }
            TextButton(onClick = { vault.cancelTwoFactor(); code = "" }, modifier = Modifier.fillMaxWidth()) { Text("Abbrechen") }
        }
    }
}

// MARK: Registration

/** New account on this server, right in the app; keys are made on the device by the Bitwarden SDK. */
@Composable
private fun RegisterScreen(vault: AndroidVault, onBack: () -> Unit) {
    var email by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var repeated by remember { mutableStateOf("") }
    var hint by rememberSaveable { mutableStateOf("") }
    var link by remember { mutableStateOf("") }
    var local by remember { mutableStateOf<String?>(null) }
    val waiting = vault.registrationToken == ""

    SetupPage(
        Symbols.ShieldLockFill,
        if (waiting) "E-Mail bestätigen" else "Konto erstellen",
        if (waiting) "Wir haben dir einen Link an $email geschickt. Kopiere ihn aus der E-Mail und füge ihn hier ein." else "Auf ${M3BConfig.serverHost}",
    ) {
        if (waiting) {
            OutlinedTextField(link, { link = it }, label = { Text("Link aus der E-Mail") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            ErrorText(vault.error)
            BusyButton("Konto erstellen", vault.busy, link.isNotBlank()) { vault.finishRegistration(email, name, password, hint, link) }
        } else {
            OutlinedTextField(
                email, { email = it }, label = { Text("E-Mail") }, singleLine = true, leadingIcon = { Icon(Symbols.Mail, null) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(name, { name = it }, label = { Text("Name (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            PasswordField("Master-Passwort (mind. 12 Zeichen)", password, { password = it }) {}
            PasswordField("Master-Passwort wiederholen", repeated, { repeated = it }) {}
            OutlinedTextField(hint, { hint = it }, label = { Text("Hinweis (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text(
                "Das Master-Passwort kann niemand zurücksetzen, auch nicht der Server. Vergisst du es, ist der Tresor verloren.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ErrorText(local ?: vault.error)
            BusyButton("Konto erstellen", vault.busy, email.isNotBlank() && password.isNotEmpty() && repeated.isNotEmpty()) {
                local = when {
                    "@" !in email -> "Bitte gib eine gültige E-Mail-Adresse ein."
                    password.length < 12 -> "Das Master-Passwort braucht mindestens 12 Zeichen."
                    password != repeated -> "Die beiden Passwörter stimmen nicht überein."
                    hint.isNotBlank() && password.lowercase().contains(hint.lowercase()) -> "Der Hinweis darf das Passwort nicht enthalten."
                    else -> null
                }
                if (local == null) vault.startRegistration(email, name, password, hint)
            }
        }
        TextButton(onClick = { vault.cancelRegistration(); onBack() }, modifier = Modifier.fillMaxWidth()) { Text("Zurück zur Anmeldung") }
    }
}

// MARK: Unlock

@Composable
private fun UnlockScreen(vault: AndroidVault) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var asked by rememberSaveable { mutableStateOf(false) }

    fun biometric() = scope.launch { vault.unlockWithBiometrics(context) }
    LaunchedEffect(Unit) {
        if (!asked && vault.canUseBiometrics) {
            asked = true
            vault.unlockWithBiometrics(context)
        }
    }

    SetupPage(Symbols.Lock, if (vault.busy) "Wird entsperrt" else "Tresor gesperrt", "${vault.accountEmail} auf ${M3BConfig.serverHost}", busy = vault.busy) {
        if (vault.canUseBiometrics) {
            FilledTonalButton(onClick = { biometric() }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Icon(Symbols.Fingerprint, null)
                Spacer(Modifier.size(8.dp))
                Text("Mit Biometrie entsperren", style = MaterialTheme.typography.titleMedium)
            }
        }
        PasswordField("Master-Passwort", password, { password = it }) {
            if (password.isNotEmpty()) vault.unlock(password).also { password = "" }
        }
        ErrorText(vault.error)
        BusyButton("Entsperren", vault.busy, password.isNotEmpty()) {
            vault.unlock(password)
            password = ""
        }
        TextButton(onClick = { vault.signOut() }, modifier = Modifier.fillMaxWidth()) {
            Text("Abmelden", color = MaterialTheme.colorScheme.error)
        }
    }
}
