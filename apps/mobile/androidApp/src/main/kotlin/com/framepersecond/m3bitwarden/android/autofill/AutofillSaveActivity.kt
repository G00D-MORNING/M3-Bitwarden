package com.framepersecond.m3bitwarden.android.autofill

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.framepersecond.m3bitwarden.ThemeMode
import com.framepersecond.m3bitwarden.android.R
import com.framepersecond.m3bitwarden.autofill.AutofillVault
import com.framepersecond.m3bitwarden.autofill.match.LearnedLink
import com.framepersecond.m3bitwarden.autofill.match.MatchEngine
import com.framepersecond.m3bitwarden.autofill.suggest.SaveDecision
import com.framepersecond.m3bitwarden.autofill.suggest.Submitted
import com.framepersecond.m3bitwarden.autofill.suggest.Suggester
import com.framepersecond.m3bitwarden.autofill.url.UrlInfo
import com.framepersecond.m3bitwarden.ui.icons.Symbols
import com.framepersecond.m3bitwarden.ui.theme.M3BitwardenTheme

/**
 * Shown after a form was submitted: save a new login, update a changed password, or link an
 * existing login instead of creating a duplicate. Decided only after the vault is unlocked.
 */
class AutofillSaveActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val save = AutofillSessions.takeSave(intent.getStringExtra(EXTRA_TOKEN)) ?: return finish()
        val vault = AutofillVault(PrefsAutofillStore(this))
        val siteKey = "save|" + (save.target.webDomain?.let { UrlInfo.parse("https://$it")?.registrableDomain ?: it } ?: save.target.appId)
        if (siteKey in vault.dismissed()) return finish()

        var decision by mutableStateOf<SaveDecision?>(null)
        fun decide() {
            val creds = vault.credentials()
            val matcher = MatchEngine(EquivalentDomains.defaults, learnedLinks = vault.learnedLinks())
            val result = matcher.match(save.target, creds)
            decision = Suggester(vault.dismissed()).afterSubmit(
                Submitted(save.username, save.password, save.intent), save.target, result, creds, vault::passwordEquals,
            )
            if (decision == SaveDecision.Nothing) finish()
        }

        setContent {
            M3BitwardenTheme(themeMode = ThemeMode.System, dynamicColor = true) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f)) {
                    Box(Modifier.fillMaxSize().navigationBarsPadding(), contentAlignment = Alignment.BottomCenter) {
                        Card(
                            Modifier.fillMaxWidth().padding(12.dp),
                            shape = RoundedCornerShape(28.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                        ) {
                            when (val d = decision) {
                                null -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { LoadingIndicator() }
                                else -> Sheet(d, save, onNever = { vault.dismiss(siteKey); finish() }, onLater = { finish() }) {
                                    apply(d, save, vault)
                                    finish()
                                }
                            }
                        }
                    }
                }
            }
        }
        Unlock.ensure(this) { ok -> if (ok) decide() else finish() }
    }

    private fun apply(d: SaveDecision, save: AutofillSessions.Save, vault: AutofillVault) {
        when (d) {
            is SaveDecision.SaveNew -> {
                val name = save.target.webDomain?.let { UrlInfo.parse("https://$it")?.registrableDomain } ?: save.app.label
                val uri = d.uri ?: save.target.appId?.let { "androidapp://$it" }
                vault.saveNew(name.replaceFirstChar { it.uppercase() }, d.username, save.password.orEmpty(), uri)
            }
            is SaveDecision.UpdatePassword -> vault.updatePassword(d.credential.id, save.password.orEmpty())
            is SaveDecision.LinkExisting -> {
                vault.addUri(d.suggestion.credential.id, d.suggestion.uriToAdd)
                vault.addLearnedLink(LearnedLink(d.suggestion.credential.id, d.suggestion.appId, save.app.certSha256.takeIf { d.suggestion.appId != null }, d.suggestion.domain))
            }
            SaveDecision.Nothing -> Unit
        }
    }

    @Composable
    private fun Sheet(d: SaveDecision, save: AutofillSessions.Save, onNever: () -> Unit, onLater: () -> Unit, onConfirm: () -> Unit) {
        val site = save.target.webDomain ?: save.app.label
        val (title, body, action) = when (d) {
            is SaveDecision.SaveNew -> Triple(
                stringResource(R.string.autofill_save_title, site),
                d.username.ifBlank { stringResource(R.string.autofill_no_username) },
                stringResource(R.string.autofill_save_action),
            )
            is SaveDecision.UpdatePassword -> Triple(
                stringResource(R.string.autofill_update_title, d.credential.name),
                stringResource(R.string.autofill_update_body, d.credential.username),
                stringResource(R.string.autofill_update_action),
            )
            is SaveDecision.LinkExisting -> Triple(
                stringResource(R.string.autofill_reuse_title, d.suggestion.credential.name),
                stringResource(R.string.autofill_reuse_body, site),
                stringResource(R.string.autofill_suggest_link),
            )
            SaveDecision.Nothing -> return
        }
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Symbols.ShieldLockFill, null, tint = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.headlineSmallEmphasized)
            Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onNever) { Text(stringResource(R.string.autofill_never_here)) }
                TextButton(onClick = onLater) { Text(stringResource(R.string.autofill_not_now)) }
                Button(onClick = onConfirm) { Text(action) }
            }
        }
    }

    companion object {
        private const val EXTRA_TOKEN = "token"

        fun intent(context: Context, token: String): Intent =
            Intent(context, AutofillSaveActivity::class.java).putExtra(EXTRA_TOKEN, token)
    }
}
