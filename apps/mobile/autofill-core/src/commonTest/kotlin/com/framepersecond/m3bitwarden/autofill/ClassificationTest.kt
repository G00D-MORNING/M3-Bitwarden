package com.framepersecond.m3bitwarden.autofill

import com.framepersecond.m3bitwarden.autofill.Nodes.EMAIL
import com.framepersecond.m3bitwarden.autofill.Nodes.NUMBER
import com.framepersecond.m3bitwarden.autofill.Nodes.NUMBER_PASSWORD
import com.framepersecond.m3bitwarden.autofill.Nodes.PASSWORD
import com.framepersecond.m3bitwarden.autofill.Nodes.TEXT
import com.framepersecond.m3bitwarden.autofill.Nodes.button
import com.framepersecond.m3bitwarden.autofill.Nodes.form
import com.framepersecond.m3bitwarden.autofill.Nodes.input
import com.framepersecond.m3bitwarden.autofill.Nodes.label
import com.framepersecond.m3bitwarden.autofill.Nodes.screen
import com.framepersecond.m3bitwarden.autofill.classify.FieldClassifier
import com.framepersecond.m3bitwarden.autofill.classify.FormAnalyzer
import com.framepersecond.m3bitwarden.autofill.classify.FormIntent
import com.framepersecond.m3bitwarden.autofill.model.FieldType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClassificationTest {
    private val classifier = FieldClassifier()
    private val analyzer = FormAnalyzer()

    @Test
    fun germanFieldsWithoutHintsAreRecognised() {
        // Proton only knows "password": "Kennwort" in a plain text field would be missed.
        assertEquals(FieldType.Password, classifier.classify(input("a", TEXT, idEntry = "kennwort_feld")).type)
        assertEquals(FieldType.Username, classifier.classify(input("b", TEXT, hint = "Benutzername")).type)
        assertEquals(FieldType.Email, classifier.classify(input("c", TEXT, hint = "E-Mail-Adresse")).type)
    }

    @Test
    fun shortKeywordsDoNotFireInsideLongerWords() {
        // "ort" (city) is inside "passwort"; it must stay a password.
        assertEquals(FieldType.Password, classifier.classify(input("a", TEXT, idEntry = "passwort")).type)
        // "tel" inside "hotel" is not a phone field.
        assertEquals(FieldType.Unknown, classifier.classify(input("b", TEXT, idEntry = "hotelname_suche")).type)
    }

    @Test
    fun autocompleteAttributeWins() {
        val newPw = input("a", PASSWORD, html = mapOf("type" to "password", "autocomplete" to "new-password", "name" to "password"))
        assertEquals(FieldType.NewPassword, classifier.classify(newPw).type)
        val otp = input("b", TEXT, html = mapOf("type" to "text", "autocomplete" to "one-time-code"))
        assertEquals(FieldType.Otp, classifier.classify(otp).type)
    }

    @Test
    fun searchAndCouponFieldsAreRejected() {
        assertEquals(FieldType.Unknown, classifier.classify(input("a", TEXT, html = mapOf("type" to "search", "name" to "q"))).type)
        assertEquals(FieldType.Unknown, classifier.classify(input("b", TEXT, idEntry = "coupon_code")).type)
    }

    @Test
    fun postalCodeIsNotAOneTimeCode() {
        val zip = classifier.classify(input("a", NUMBER, idEntry = "zip_code", maxLength = 5))
        assertEquals(FieldType.PostalCode, zip.type)
    }

    @Test
    fun bankingPinIsAPassword() {
        assertEquals(FieldType.Password, classifier.classify(input("a", NUMBER_PASSWORD, hint = "Online-Banking-PIN")).type)
    }

    @Test
    fun simpleLoginForm() {
        val a = analyzer.analyze(screen(form(input("u", EMAIL, idEntry = "email"), input("p", PASSWORD), button("Anmelden"))))
        assertEquals(FormIntent.Login, a.intent)
        assertEquals("u", a.firstOf(FieldType.Email)?.id)
        assertEquals("p", a.firstOf(FieldType.Password)?.id)
    }

    @Test
    fun unlabelledFieldBeforePasswordIsTheUsername() {
        val a = analyzer.analyze(screen(input("x", TEXT), input("p", PASSWORD)))
        assertEquals(FormIntent.Login, a.intent)
        assertEquals("x", a.firstOf(FieldType.Username)?.id)
    }

    @Test
    fun searchBoxIsNeverPickedAsUsername() {
        val a = analyzer.analyze(screen(input("s", TEXT, html = mapOf("type" to "search")), input("p", PASSWORD)))
        assertNull(a.firstOf(FieldType.Username))
        assertEquals(FormIntent.PasswordStep, a.intent)
    }

    @Test
    fun labelNextToTheInputIsUsed() {
        val a = analyzer.analyze(screen(label("Kundennummer"), input("k", TEXT), label("Passwort"), input("p", TEXT)))
        assertEquals("k", a.firstOf(FieldType.Username)?.id)
        assertEquals("p", a.firstOf(FieldType.Password)?.id)
    }

    @Test
    fun twoPasswordsMeanSignup() {
        val a = analyzer.analyze(screen(form(input("e", EMAIL), input("p1", PASSWORD), input("p2", PASSWORD), button("Konto erstellen"))))
        assertEquals(FormIntent.Signup, a.intent)
        assertEquals("p1", a.firstOf(FieldType.NewPassword)?.id)
        assertEquals("p2", a.firstOf(FieldType.ConfirmPassword)?.id)
    }

    @Test
    fun changePasswordForm() {
        val a = analyzer.analyze(
            screen(
                input("old", PASSWORD, html = mapOf("type" to "password", "autocomplete" to "current-password")),
                input("n1", PASSWORD, html = mapOf("type" to "password", "autocomplete" to "new-password")),
                input("n2", PASSWORD, html = mapOf("type" to "password", "autocomplete" to "new-password")),
            ),
        )
        assertEquals(FormIntent.ChangePassword, a.intent)
        assertEquals("old", a.firstOf(FieldType.Password)?.id)
        assertEquals("n1", a.firstOf(FieldType.NewPassword)?.id)
        assertEquals("n2", a.firstOf(FieldType.ConfirmPassword)?.id)
    }

    @Test
    fun firstStepOfMultiStepLogin() {
        val a = analyzer.analyze(screen(input("e", EMAIL, hint = "E-Mail"), button("Weiter")))
        assertEquals(FormIntent.UsernameStep, a.intent)
    }

    @Test
    fun splitOneTimeCodeBoxes() {
        val boxes = (1..6).map { input("d$it", NUMBER, maxLength = 1) }.toTypedArray()
        val a = analyzer.analyze(screen(label("Bestätigungscode"), *boxes))
        assertEquals(FormIntent.OneTimeCode, a.intent)
        assertEquals(6, a.allOf(FieldType.OtpDigit).size)
    }

    @Test
    fun expiryFieldsNeedACardNumber() {
        val alone = analyzer.analyze(screen(input("m", TEXT, idEntry = "exp_month")))
        assertTrue(alone.fields.none { it.type == FieldType.CardExpiryMonth })
        val card = analyzer.analyze(screen(input("n", TEXT, idEntry = "cardnumber"), input("m", TEXT, idEntry = "exp_month"), input("c", NUMBER, idEntry = "cvc")))
        assertEquals(FormIntent.Card, card.intent)
        assertEquals("m", card.firstOf(FieldType.CardExpiryMonth)?.id)
        assertEquals("c", card.firstOf(FieldType.CardCvv)?.id)
    }

    @Test
    fun androidHintsAreTrusted() {
        val a = analyzer.analyze(screen(input("u", TEXT, hints = listOf("username")), input("p", TEXT, hints = listOf("password"))))
        assertEquals(FormIntent.Login, a.intent)
        assertEquals("u", a.firstOf(FieldType.Username)?.id)
    }
}
