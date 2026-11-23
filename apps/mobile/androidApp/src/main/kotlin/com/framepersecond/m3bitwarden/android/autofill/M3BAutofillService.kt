package com.framepersecond.m3bitwarden.android.autofill

import android.content.Intent
import android.os.Build
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.SaveCallback
import android.service.autofill.SaveInfo
import android.service.autofill.SaveRequest
import android.view.autofill.AutofillId
import com.framepersecond.m3bitwarden.android.R
import com.framepersecond.m3bitwarden.autofill.AutofillEngine
import com.framepersecond.m3bitwarden.autofill.AutofillVault
import com.framepersecond.m3bitwarden.autofill.FieldAssignment
import com.framepersecond.m3bitwarden.autofill.FillPlan
import com.framepersecond.m3bitwarden.autofill.ValueKind
import com.framepersecond.m3bitwarden.autofill.classify.FormAnalyzer
import com.framepersecond.m3bitwarden.autofill.classify.FormIntent
import com.framepersecond.m3bitwarden.autofill.match.FillTarget
import com.framepersecond.m3bitwarden.autofill.match.MatchEngine
import com.framepersecond.m3bitwarden.autofill.match.MatchReason
import com.framepersecond.m3bitwarden.autofill.model.FieldType
import com.framepersecond.m3bitwarden.autofill.suggest.Suggester
import com.framepersecond.m3bitwarden.autofill.url.UrlInfo
import com.framepersecond.m3bitwarden.data.VaultSession
import com.framepersecond.m3bitwarden.generator.PasswordGenerator
import com.framepersecond.m3bitwarden.generator.PasswordOptions
import java.util.concurrent.Executors

/**
 * Android autofill entry point.
 *
 * Every request ends with at least one entry ("Tresor durchsuchen"), so the vault is always
 * one tap away even when nothing matched: that is the "it always works" guarantee.
 */
class M3BAutofillService : AutofillService() {

    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var vault: AutofillVault
    private lateinit var verifier: AssetLinksVerifier
    private lateinit var datasets: DatasetFactory
    private val engine = AutofillEngine()

    override fun onCreate() {
        super.onCreate()
        vault = AutofillVault(PrefsAutofillStore(this))
        verifier = AssetLinksVerifier(this)
        datasets = DatasetFactory(this)
        VaultSession.onLock { AutofillSessions.clear() }
    }

    override fun onFillRequest(request: FillRequest, cancellationSignal: CancellationSignal, callback: FillCallback) {
        // Asset-link checks may touch the network: never on the main thread.
        val future = worker.submit {
            val response = runCatching { buildResponse(request) }.getOrNull()
            if (!cancellationSignal.isCanceled) runCatching { callback.onSuccess(response) }
        }
        cancellationSignal.setOnCancelListener { future.cancel(true) }
    }

    private fun buildResponse(request: FillRequest): FillResponse? {
        val structure = request.fillContexts.last().structure
        val pkg = structure.activityComponent.packageName
        if (pkg == packageName) return null

        val mapper = AssistMapper()
        val root = mapper.map(structure)
        val app = AppIdentity.of(this, pkg)
        val ids = HashMap<String, AutofillId>()
        val baseTarget = FillTarget(
            appId = pkg,
            appCertSha256 = app.certSha256,
            appLabel = app.label,
            isTrustedBrowser = app.isTrustedBrowser,
        )
        val inlineSpecs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            request.inlineSuggestionsRequest?.inlinePresentationSpecs.orEmpty()
        } else {
            emptyList()
        }
        val maxInline = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) request.inlineSuggestionsRequest?.maxSuggestionCount ?: 0 else 0
        fun spec(i: Int) = if (i < maxInline) inlineSpecs.getOrNull(i) ?: inlineSpecs.lastOrNull() else null

        val unlocked = vault.isUnlocked
        val credentials = if (unlocked) vault.credentials() else emptyList()
        val suggester = Suggester(dismissed = vault.dismissed())
        var plan = engine.plan(root, baseTarget, credentials, matcher(emptyMap()), suggester) ?: return null
        plan.analysis.fields.forEach { f -> mapper.autofillId(f.id)?.let { ids[f.id] = it } }
        if (ids.isEmpty()) return null

        // Native app without a matching login, or a web view we cannot vouch for:
        // ask the candidate websites whether they officially belong to this app.
        if (unlocked && !app.isTrustedBrowser && plan.matches.primary.isEmpty()) {
            val webDomain = plan.analysis.webDomain
            val candidates = buildList {
                webDomain?.let { add(UrlInfo.parse("https://$it")?.registrableDomain ?: it) }
                plan.matches.possible.flatMap { m -> m.credential.uris.mapNotNull { UrlInfo.parse(it.uri)?.host } }.forEach(::add)
            }
            val verified = verifier.verifiedDomains(app, candidates)
            if (verified.isNotEmpty()) {
                plan = engine.plan(root, baseTarget, credentials, matcher(mapOf(pkg to verified)), suggester) ?: plan
            }
        }

        val target = baseTarget.copy(webDomain = plan.analysis.webDomain, webScheme = plan.analysis.webScheme)
        val token = AutofillSessions.put(AutofillSessions.Fill(plan, target, app, ids, inlineSpecs.lastOrNull()))
        val assignments = plan.assignments()
        val response = FillResponse.Builder()
        var count = 0

        if (!unlocked) {
            datasets.authenticated(
                assignments, ids,
                DatasetFactory.Label(getString(R.string.autofill_unlock_title), getString(R.string.autofill_unlock_subtitle), R.drawable.ic_autofill_lock),
                spec(count),
                AutofillPickerActivity.intent(this, token, AutofillPickerActivity.Mode.Search),
            )?.let { response.addDataset(it); count++ }
        } else {
            val now = System.currentTimeMillis() / 1000
            for (m in plan.matches.primary.take(MAX_DIRECT)) {
                val label = DatasetFactory.Label(m.credential.name, subtitleFor(m.credential.username, m.reason, m.insecureTransport))
                val ds = datasets.direct(assignments, ids, label, spec(count)) { a ->
                    vault.value(m.credential.id, a.value, now, a.otpDigitIndex)
                } ?: continue
                response.addDataset(ds); count++
            }
            for (m in plan.matches.possible.take(MAX_POSSIBLE)) {
                val label = DatasetFactory.Label(m.credential.name, getString(R.string.autofill_confirm_subtitle), R.drawable.ic_autofill_warning)
                datasets.authenticated(
                    assignments, ids, label, spec(count),
                    AutofillPickerActivity.intent(this, token, AutofillPickerActivity.Mode.Confirm, m.credential.id),
                )?.let { response.addDataset(it); count++ }
            }
        }

        // Sign-up and password change: offer a fresh strong password for the new fields.
        if (unlocked && (plan.analysis.intent == FormIntent.Signup || plan.analysis.intent == FormIntent.ChangePassword)) {
            val generated = PasswordGenerator.password(PasswordOptions(length = 20))
            val newFields = plan.analysis.allOf(FieldType.NewPassword, FieldType.ConfirmPassword)
                .map { FieldAssignment(it.id, ValueKind.Password) }
            datasets.direct(
                newFields, ids,
                DatasetFactory.Label(getString(R.string.autofill_generate_title), getString(R.string.autofill_generate_subtitle), R.drawable.ic_autofill_key),
                spec(count),
            ) { generated }?.let { response.addDataset(it); count++ }
        }

        // Always offer the full vault, with a hint when we have a link suggestion.
        val searchSubtitle = plan.suggestions.firstOrNull()?.let { getString(R.string.autofill_search_suggestion, it.credential.name) }
            ?: getString(R.string.autofill_search_subtitle)
        if (unlocked) {
            datasets.authenticated(
                assignments, ids,
                DatasetFactory.Label(getString(R.string.autofill_search_title), searchSubtitle, R.drawable.ic_autofill_search),
                spec(count),
                AutofillPickerActivity.intent(this, token, AutofillPickerActivity.Mode.Search),
            )?.let { response.addDataset(it) }
        }

        saveInfo(plan, ids)?.let { response.setSaveInfo(it) }
        return response.build()
    }

    private fun matcher(appDomains: Map<String, Set<String>>) = MatchEngine(
        equivalentDomains = EquivalentDomains.defaults,
        appDomains = appDomains,
        learnedLinks = vault.learnedLinks(),
        nowEpochMs = { System.currentTimeMillis() },
    )

    private fun subtitleFor(username: String, reason: MatchReason, insecure: Boolean): String {
        val base = username.ifBlank { getString(R.string.autofill_no_username) }
        return when {
            insecure -> getString(R.string.autofill_insecure_subtitle, base)
            reason == MatchReason.EquivalentDomain -> getString(R.string.autofill_equivalent_subtitle, base)
            else -> base
        }
    }

    private fun saveInfo(plan: FillPlan, ids: Map<String, AutofillId>): SaveInfo? {
        val f = plan.analysis.fields
        val passwords = f.filter { it.type == FieldType.Password || it.type == FieldType.NewPassword }.mapNotNull { ids[it.id] }
        val users = f.filter { it.type.isIdentifier }.mapNotNull { ids[it.id] }
        return when {
            passwords.isNotEmpty() -> SaveInfo.Builder(
                SaveInfo.SAVE_DATA_TYPE_PASSWORD or if (users.isNotEmpty()) SaveInfo.SAVE_DATA_TYPE_USERNAME else 0,
                passwords.toTypedArray(),
            ).apply {
                if (users.isNotEmpty()) setOptionalIds(users.toTypedArray())
                setFlags(SaveInfo.FLAG_SAVE_ON_ALL_VIEWS_INVISIBLE)
            }.build()
            // First step of a two-step login: keep the username for the save after step two.
            plan.analysis.intent == FormIntent.UsernameStep && users.isNotEmpty() ->
                SaveInfo.Builder(SaveInfo.SAVE_DATA_TYPE_USERNAME, users.toTypedArray())
                    .setFlags(SaveInfo.FLAG_DELAY_SAVE)
                    .build()
            else -> null
        }
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        val analyzer = FormAnalyzer()
        var username: String? = null
        var password: String? = null
        var intent = FormIntent.Unknown
        var webDomain: String? = null
        var webScheme: String? = null
        var pkg: String? = null
        for (ctx in request.fillContexts) {
            val mapper = AssistMapper()
            val analysis = analyzer.analyze(mapper.map(ctx.structure))
            pkg = ctx.structure.activityComponent.packageName
            webDomain = analysis.webDomain ?: webDomain
            webScheme = analysis.webScheme ?: webScheme
            analysis.firstOf(FieldType.Username, FieldType.Email)?.let { f -> mapper.textValue(f.id)?.takeIf { it.isNotBlank() }?.let { username = it } }
            val pwField = when (analysis.intent) {
                FormIntent.Signup, FormIntent.ChangePassword -> analysis.firstOf(FieldType.NewPassword) ?: analysis.firstOf(FieldType.Password)
                else -> analysis.firstOf(FieldType.Password) ?: analysis.firstOf(FieldType.NewPassword)
            }
            pwField?.let { f -> mapper.textValue(f.id)?.takeIf { it.isNotEmpty() }?.let { password = it } }
            if (analysis.intent != FormIntent.Unknown) intent = analysis.intent
        }
        val appPkg = pkg ?: return callback.onSuccess()
        if (password == null) return callback.onSuccess()
        val app = AppIdentity.of(this, appPkg)
        val target = FillTarget(
            appId = appPkg, appCertSha256 = app.certSha256, appLabel = app.label,
            isTrustedBrowser = app.isTrustedBrowser, webDomain = webDomain, webScheme = webScheme,
        )
        val token = AutofillSessions.put(AutofillSessions.Save(username, password, intent, target, app))
        val sender = datasets.intentSender(AutofillSaveActivity.intent(this, token))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) callback.onSuccess(sender) else callback.onSuccess()
    }

    private companion object {
        const val MAX_DIRECT = 4
        const val MAX_POSSIBLE = 2
    }
}

/**
 * Fallback groups until the server's list (`/api/settings/domains`) is synced. Each group is a
 * set of domains Bitwarden treats as one site.
 */
object EquivalentDomains {
    val defaults: List<Set<String>> = listOf(
        setOf("google.com", "google.de", "youtube.com", "gmail.com"),
        setOf("amazon.com", "amazon.de", "amazon.co.uk", "amazon.fr", "amazon.it", "amazon.es", "amazon.at"),
        setOf("microsoft.com", "live.com", "outlook.com", "hotmail.com", "office.com", "xbox.com", "microsoftonline.com"),
        setOf("apple.com", "icloud.com"),
        setOf("ebay.com", "ebay.de", "ebay.at", "ebay.co.uk"),
        setOf("paypal.com", "paypal.de", "paypal.me"),
        setOf("steampowered.com", "steamcommunity.com"),
    )
}
