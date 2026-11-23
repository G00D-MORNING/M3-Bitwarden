package com.framepersecond.m3bitwarden.autofill

import com.framepersecond.m3bitwarden.autofill.classify.FormAnalysis
import com.framepersecond.m3bitwarden.autofill.classify.FormAnalyzer
import com.framepersecond.m3bitwarden.autofill.classify.FormIntent
import com.framepersecond.m3bitwarden.autofill.match.Credential
import com.framepersecond.m3bitwarden.autofill.match.CredentialKind
import com.framepersecond.m3bitwarden.autofill.match.FillTarget
import com.framepersecond.m3bitwarden.autofill.match.Match
import com.framepersecond.m3bitwarden.autofill.match.MatchEngine
import com.framepersecond.m3bitwarden.autofill.match.MatchReason
import com.framepersecond.m3bitwarden.autofill.match.MatchResult
import com.framepersecond.m3bitwarden.autofill.model.AutofillNode
import com.framepersecond.m3bitwarden.autofill.model.FieldType
import com.framepersecond.m3bitwarden.autofill.suggest.LinkSuggestion
import com.framepersecond.m3bitwarden.autofill.suggest.Suggester

/** Which secret of an item goes into which field. Resolved to real values by the platform. */
enum class ValueKind { Username, Password, Totp, CardNumber, CardHolder, CardExpiry, CardExpiryMonth, CardExpiryYear, CardCvv, FullName, FirstName, LastName, Email, Phone, Address, PostalCode, City, Country, Organization }

data class FieldAssignment(val fieldId: String, val value: ValueKind, val otpDigitIndex: Int = -1)

data class FillPlan(
    val analysis: FormAnalysis,
    val matches: MatchResult,
    val suggestions: List<LinkSuggestion>,
    /** Which credential kind the screen wants, so the picker can filter. */
    val wantedKind: CredentialKind?,
) {
    /** Field → value instructions for a chosen item of [wantedKind]. */
    fun assignments(): List<FieldAssignment> {
        val out = ArrayList<FieldAssignment>()
        val f = analysis.fields
        var digit = 0
        for (field in f.sortedBy { it.order }) {
            val kind = when (field.type) {
                FieldType.Username -> ValueKind.Username
                // An e-mail field takes the login's username (usually the e-mail) for logins,
                // and the identity's e-mail otherwise.
                FieldType.Email -> if (wantedKind == CredentialKind.Identity) ValueKind.Email else ValueKind.Username
                FieldType.Password -> ValueKind.Password
                // Never put an existing password into a sign-up form; the generator fills those.
                FieldType.NewPassword, FieldType.ConfirmPassword -> null
                FieldType.Otp -> ValueKind.Totp
                FieldType.OtpDigit -> { out += FieldAssignment(field.id, ValueKind.Totp, digit++); null }
                FieldType.CardNumber -> ValueKind.CardNumber
                FieldType.CardHolder -> ValueKind.CardHolder
                FieldType.CardExpiry -> ValueKind.CardExpiry
                FieldType.CardExpiryMonth -> ValueKind.CardExpiryMonth
                FieldType.CardExpiryYear -> ValueKind.CardExpiryYear
                FieldType.CardCvv -> ValueKind.CardCvv
                FieldType.FullName -> ValueKind.FullName
                FieldType.FirstName -> ValueKind.FirstName
                FieldType.LastName -> ValueKind.LastName
                FieldType.Phone -> ValueKind.Phone
                FieldType.Address -> ValueKind.Address
                FieldType.PostalCode -> ValueKind.PostalCode
                FieldType.City -> ValueKind.City
                FieldType.Country -> ValueKind.Country
                FieldType.Organization -> ValueKind.Organization
                FieldType.Unknown -> null
            }
            if (kind != null) out += FieldAssignment(field.id, kind)
        }
        return out
    }
}

/**
 * One call per fill request: analyse the screen, find matching items, collect suggestions.
 * Everything here runs on the device; nothing leaves it.
 */
class AutofillEngine(
    private val analyzer: FormAnalyzer = FormAnalyzer(),
) {
    fun plan(root: AutofillNode, target: FillTarget, credentials: List<Credential>, matcher: MatchEngine, suggester: Suggester): FillPlan? {
        val analysis = analyzer.analyze(root)
        if (!analysis.hasFillableFields) return null
        val wanted = when (analysis.intent) {
            FormIntent.Card -> CredentialKind.Card
            FormIntent.Identity -> CredentialKind.Identity
            FormIntent.Login, FormIntent.UsernameStep, FormIntent.PasswordStep, FormIntent.OneTimeCode,
            FormIntent.ChangePassword, FormIntent.Signup -> CredentialKind.Login
            FormIntent.Unknown -> null
        }
        // The page's own domain wins over what the request reported for the window.
        val effectiveTarget = target.copy(
            webDomain = analysis.webDomain ?: target.webDomain,
            webScheme = analysis.webScheme ?: target.webScheme,
        )
        val pool = credentials.filter { wanted == null || it.kind == wanted }
        val matches = when (wanted) {
            CredentialKind.Card, CredentialKind.Identity -> MatchResult(
                // Cards and identities are not tied to a site: every one is a candidate.
                primary = pool.map { Match(it, 70, MatchReason.KindOnly) },
                possible = emptyList(),
            )
            else -> matcher.match(effectiveTarget, pool)
        }
        return FillPlan(analysis, matches, suggester.forTarget(effectiveTarget, matches), wanted)
    }
}
