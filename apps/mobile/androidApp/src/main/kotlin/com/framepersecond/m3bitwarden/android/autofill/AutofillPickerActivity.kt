package com.framepersecond.m3bitwarden.android.autofill

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.view.autofill.AutofillManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.framepersecond.m3bitwarden.ThemeMode
import com.framepersecond.m3bitwarden.android.R
import com.framepersecond.m3bitwarden.autofill.AutofillVault
import com.framepersecond.m3bitwarden.autofill.FillPlan
import com.framepersecond.m3bitwarden.autofill.ValueKind
import com.framepersecond.m3bitwarden.autofill.match.Credential
import com.framepersecond.m3bitwarden.autofill.match.CredentialKind
import com.framepersecond.m3bitwarden.autofill.match.LearnedLink
import com.framepersecond.m3bitwarden.autofill.match.MatchEngine
import com.framepersecond.m3bitwarden.autofill.match.MatchResult
import com.framepersecond.m3bitwarden.autofill.suggest.LinkSuggestion
import com.framepersecond.m3bitwarden.autofill.suggest.Suggester
import com.framepersecond.m3bitwarden.data.ItemType
import com.framepersecond.m3bitwarden.ui.components.ItemAvatar
import com.framepersecond.m3bitwarden.ui.icons.Symbols
import com.framepersecond.m3bitwarden.ui.theme.M3BitwardenTheme

/**
 * Opened from an autofill entry: unlocks the vault, lets the user pick or confirm a login,
 * offers to remember the link, and returns the filled dataset.
 */
class AutofillPickerActivity : ComponentActivity() {

    enum class Mode { Search, Confirm }

    private lateinit var vault: AutofillVault
    private var session: AutofillSessions.Fill? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        vault = AutofillVault(PrefsAutofillStore(this))
        session = AutofillSessions.fill(intent.getStringExtra(EXTRA_TOKEN))
        if (session == null) return cancel()
        val mode = intent.getStringExtra(EXTRA_MODE)?.let(Mode::valueOf) ?: Mode.Search
        val preselected = intent.getStringExtra(EXTRA_ITEM)

        var ready by mutableStateOf(vault.isUnlocked)
        setContent {
            M3BitwardenTheme(themeMode = ThemeMode.System, dynamicColor = true) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    if (!ready) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator() }
                    } else {
                        val plan = remember { currentPlan() }
                        when (mode) {
                            Mode.Confirm -> ConfirmScreen(plan, preselected)
                            Mode.Search -> SearchScreen(plan)
                        }
                    }
                }
            }
        }
        if (!ready) Unlock.ensure(this) { ok -> if (ok) ready = true else cancel() }
    }

    /** Re-runs matching now that the vault is open (the request may have come in locked). */
    private fun currentPlan(): Pair<FillPlan?, MatchResult> {
        val s = session!!
        val creds = vault.credentials()
        val matcher = MatchEngine(EquivalentDomains.defaults, learnedLinks = vault.learnedLinks(), nowEpochMs = { System.currentTimeMillis() })
        val plan = s.plan
        val pool = creds.filter { plan?.wantedKind == null || it.kind == plan.wantedKind }
        val result = if (plan?.wantedKind == CredentialKind.Login || plan?.wantedKind == null) matcher.match(s.target, pool) else MatchResult(emptyList(), emptyList())
        return plan to result
    }

    @Composable
    private fun SearchScreen(planAndMatches: Pair<FillPlan?, MatchResult>) {
        val (plan, matches) = planAndMatches
        val s = session!!
        val suggester = remember { Suggester(vault.dismissed()) }
        var query by remember { mutableStateOf("") }
        var pending by remember { mutableStateOf<Pair<Credential, LinkSuggestion?>?>(null) }
        val all = remember { vault.credentials().filter { plan?.wantedKind == null || it.kind == plan.wantedKind } }
        val proposals = remember { suggester.forTarget(s.target, matches) }

        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().padding(16.dp)) {
            Text(
                stringResource(R.string.autofill_picker_title, s.target.webDomain ?: s.app.label),
                style = MaterialTheme.typography.titleLargeEmphasized,
            )
            Spacer(Modifier.height(12.dp))
            TextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text(stringResource(R.string.autofill_search_hint)) },
                leadingIcon = { Icon(Symbols.Search, null) },
                shape = RoundedCornerShape(28.dp),
                colors = TextFieldDefaults.colors(focusedIndicatorColor = MaterialTheme.colorScheme.surfaceContainerLow, unfocusedIndicatorColor = MaterialTheme.colorScheme.surfaceContainerLow),
            )
            Spacer(Modifier.height(12.dp))
            val pick = pending
            if (pick != null) {
                LinkSheet(pick.first, pick.second) { remember -> finishWith(pick.first, pick.second.takeIf { remember }) }
                return@Column
            }
            val q = query.trim().lowercase()
            val filtered = if (q.isEmpty()) all else all.filter { c ->
                c.name.lowercase().contains(q) || c.username.lowercase().contains(q) || c.uris.any { it.uri.lowercase().contains(q) }
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (q.isEmpty() && proposals.isNotEmpty()) {
                    item { Section(stringResource(R.string.autofill_section_suggestions)) }
                    items(proposals, key = { "s" + it.key }) { p ->
                        SuggestionCard(p, onAccept = { pending = p.credential to p }, onDismiss = { vault.dismiss(p.key) })
                    }
                }
                if (q.isEmpty() && matches.primary.isNotEmpty()) {
                    item { Section(stringResource(R.string.autofill_section_matching)) }
                    items(matches.primary, key = { "m" + it.credential.id }) { m -> CredentialRow(m.credential) { finishWith(m.credential, null) } }
                }
                if (q.isEmpty() && matches.possible.isNotEmpty()) {
                    item { Section(stringResource(R.string.autofill_section_possible)) }
                    items(matches.possible, key = { "p" + it.credential.id }) { m ->
                        CredentialRow(m.credential) { pending = m.credential to suggester.afterFill(m.credential, s.target, matches) }
                    }
                }
                item { Section(stringResource(R.string.autofill_section_all)) }
                items(filtered, key = { "a" + it.id }) { c ->
                    CredentialRow(c) { pending = c to suggester.afterFill(c, s.target, matches) }
                }
            }
        }
    }

    @Composable
    private fun ConfirmScreen(planAndMatches: Pair<FillPlan?, MatchResult>, itemId: String?) {
        val s = session!!
        val cred = vault.credentials().firstOrNull { it.id == itemId } ?: return cancel()
        val suggestion = remember { Suggester(vault.dismissed()).afterFill(cred, s.target, planAndMatches.second) }
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Symbols.ShieldLock, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                    Text(stringResource(R.string.autofill_confirm_heading), style = MaterialTheme.typography.titleMediumEmphasized, color = MaterialTheme.colorScheme.onErrorContainer)
                    val body = if (s.target.webDomain != null && !s.app.isTrustedBrowser) {
                        stringResource(R.string.autofill_confirm_webview, s.app.label, s.target.webDomain!!)
                    } else {
                        stringResource(R.string.autofill_confirm_guess, s.app.label, cred.name)
                    }
                    Text(body, color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
            LinkSheet(cred, suggestion) { remember -> finishWith(cred, suggestion.takeIf { remember }) }
        }
    }

    @Composable
    private fun LinkSheet(cred: Credential, suggestion: LinkSuggestion?, onFill: (remember: Boolean) -> Unit) {
        var remember by remember { mutableStateOf(suggestion != null) }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CredentialRow(cred) {}
            if (suggestion != null) {
                Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.autofill_link_title, suggestion.appLabel ?: suggestion.domain ?: ""), fontWeight = FontWeight.SemiBold)
                            Text(stringResource(R.string.autofill_link_body), style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = remember, onCheckedChange = { remember = it })
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { cancel() }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.autofill_cancel)) }
                Button(onClick = { onFill(remember) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.autofill_fill)) }
            }
        }
    }

    @Composable
    private fun SuggestionCard(p: LinkSuggestion, onAccept: () -> Unit, onDismiss: () -> Unit) {
        var hidden by remember { mutableStateOf(false) }
        if (hidden) return
        Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.autofill_suggest_heading, p.credential.name, p.appLabel ?: p.domain ?: ""), fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onDismiss(); hidden = true }) { Text(stringResource(R.string.autofill_suggest_never)) }
                    Button(onClick = onAccept) { Text(stringResource(R.string.autofill_suggest_link)) }
                }
            }
        }
    }

    @Composable
    private fun Section(title: String) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp, start = 4.dp))
    }

    @Composable
    private fun CredentialRow(c: Credential, onClick: () -> Unit) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ItemAvatar(type = c.kind.toItemType(), name = c.name, size = 40.dp)
            Column(Modifier.weight(1f)) {
                Text(c.name, style = MaterialTheme.typography.bodyLarge)
                if (c.username.isNotBlank()) Text(c.username, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (c.hasTotp) Icon(Symbols.LockClock, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    private fun CredentialKind.toItemType(): ItemType = when (this) {
        CredentialKind.Login -> ItemType.Login
        CredentialKind.Card -> ItemType.Card
        CredentialKind.Identity -> ItemType.Identity
    }

    private fun finishWith(cred: Credential, link: LinkSuggestion?) {
        val s = session ?: return cancel()
        val plan = s.plan ?: return cancel()
        val now = System.currentTimeMillis()
        if (link != null) {
            vault.addUri(cred.id, link.uriToAdd)
            vault.addLearnedLink(
                LearnedLink(cred.id, appId = link.appId, appCertSha256 = s.app.certSha256.takeIf { link.appId != null }, domain = link.domain),
            )
        }
        val dataset = DatasetFactory(this).direct(plan.assignments(), s.ids, DatasetFactory.Label(cred.name, cred.username), null) { a ->
            vault.value(cred.id, a.value, now / 1000, a.otpDigitIndex)
        } ?: return cancel()
        vault.recordUse(cred.id, now)
        // Like Proton Pass: after a password login, the 2FA code is ready in the clipboard.
        if (cred.hasTotp && plan.assignments().none { it.value == ValueKind.Totp }) {
            vault.value(cred.id, ValueKind.Totp, now / 1000)?.let { Unlock.copySensitive(this, "2FA", it) }
        }
        setResult(Activity.RESULT_OK, Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, dataset))
        finish()
    }

    private fun cancel() {
        setResult(Activity.RESULT_CANCELED)
        finish()
    }

    companion object {
        private const val EXTRA_TOKEN = "token"
        private const val EXTRA_MODE = "mode"
        private const val EXTRA_ITEM = "item"

        fun intent(context: Context, token: String, mode: Mode, itemId: String? = null): Intent =
            Intent(context, AutofillPickerActivity::class.java)
                .putExtra(EXTRA_TOKEN, token)
                .putExtra(EXTRA_MODE, mode.name)
                .apply { if (itemId != null) putExtra(EXTRA_ITEM, itemId) }
    }
}
