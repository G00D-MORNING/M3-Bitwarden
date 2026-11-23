package com.framepersecond.m3bitwarden.android.autofill

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.provider.Settings
import android.view.autofill.AutofillManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.framepersecond.m3bitwarden.autofill.AutofillVault
import com.framepersecond.m3bitwarden.autofill.match.LearnedLink
import com.framepersecond.m3bitwarden.autofill.suggest.InstalledApp
import com.framepersecond.m3bitwarden.autofill.suggest.LinkSuggestion
import com.framepersecond.m3bitwarden.autofill.suggest.Suggester
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * In-app part of autofill: a setup card while the service is off, and the periodic
 * "Verknüpfungsvorschläge" review for installed apps without a linked login.
 */
@Composable
fun AutofillHome() {
    val context = LocalContext.current
    val vault = remember { AutofillVault(PrefsAutofillStore(context)) }
    var enabled by remember { mutableStateOf(isEnabled(context)) }
    var suggestions by remember { mutableStateOf<List<LinkSuggestion>>(emptyList()) }

    LaunchedEffect(Unit) {
        suggestions = withContext(Dispatchers.Default) { review(context, vault) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 8.dp)) {
        if (!enabled) {
            Card(shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Autofill einschalten", style = MaterialTheme.typography.titleMediumEmphasized)
                    Text("Wähle M3 Bitwarden als Autofill-Dienst. In Chrome zusätzlich: Einstellungen → Autofill-Dienste → „Autofill mit anderem Dienst“.")
                    Button(onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE).setData(Uri.parse("package:${context.packageName}")),
                        )
                        enabled = isEnabled(context)
                    }) { Text("Einrichten") }
                }
            }
        }
        if (suggestions.isNotEmpty()) {
            Text("Verknüpfungsvorschläge", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            suggestions.take(5).forEach { s ->
                Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("„${s.credential.name}“ für die App ${s.appLabel ?: s.appId} verwenden?", fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                vault.dismiss(s.key)
                                suggestions = suggestions - s
                            }) { Text("Nicht vorschlagen") }
                            Button(onClick = {
                                vault.addUri(s.credential.id, s.uriToAdd)
                                val cert = s.appId?.let { AppIdentity.certSha256(context.packageManager, it) }
                                vault.addLearnedLink(LearnedLink(s.credential.id, s.appId, cert, s.domain))
                                suggestions = suggestions - s
                            }) { Text("Verknüpfen") }
                        }
                    }
                }
            }
        }
    }
}

private fun isEnabled(context: Context): Boolean =
    context.getSystemService(AutofillManager::class.java)?.hasEnabledAutofillServices() == true

private fun review(context: Context, vault: AutofillVault): List<LinkSuggestion> {
    if (!vault.isUnlocked) return emptyList()
    val pm = context.packageManager
    val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val apps = pm.queryIntentActivities(launcher, 0)
        .map { it.activityInfo.applicationInfo }
        .filter { it.packageName != context.packageName && (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
        .distinctBy { it.packageName }
        .map { InstalledApp(it.packageName, pm.getApplicationLabel(it).toString()) }
    return Suggester(vault.dismissed()).reviewVault(vault.credentials(), apps)
}
