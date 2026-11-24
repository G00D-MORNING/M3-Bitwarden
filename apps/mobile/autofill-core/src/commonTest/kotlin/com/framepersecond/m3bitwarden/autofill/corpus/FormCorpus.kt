package com.framepersecond.m3bitwarden.autofill.corpus

import com.framepersecond.m3bitwarden.autofill.classify.FormIntent
import com.framepersecond.m3bitwarden.autofill.classify.InputTypeBits
import com.framepersecond.m3bitwarden.autofill.model.AutofillNode
import com.framepersecond.m3bitwarden.autofill.model.FieldType
import com.framepersecond.m3bitwarden.autofill.model.FieldType.Address
import com.framepersecond.m3bitwarden.autofill.model.FieldType.AddressLine2
import com.framepersecond.m3bitwarden.autofill.model.FieldType.CardCvv
import com.framepersecond.m3bitwarden.autofill.model.FieldType.CardExpiry
import com.framepersecond.m3bitwarden.autofill.model.FieldType.CardExpiryMonth
import com.framepersecond.m3bitwarden.autofill.model.FieldType.CardExpiryYear
import com.framepersecond.m3bitwarden.autofill.model.FieldType.CardHolder
import com.framepersecond.m3bitwarden.autofill.model.FieldType.CardNumber
import com.framepersecond.m3bitwarden.autofill.model.FieldType.City
import com.framepersecond.m3bitwarden.autofill.model.FieldType.ConfirmPassword
import com.framepersecond.m3bitwarden.autofill.model.FieldType.Country
import com.framepersecond.m3bitwarden.autofill.model.FieldType.Email
import com.framepersecond.m3bitwarden.autofill.model.FieldType.FirstName
import com.framepersecond.m3bitwarden.autofill.model.FieldType.FullName
import com.framepersecond.m3bitwarden.autofill.model.FieldType.HouseNumber
import com.framepersecond.m3bitwarden.autofill.model.FieldType.LastName
import com.framepersecond.m3bitwarden.autofill.model.FieldType.NewPassword
import com.framepersecond.m3bitwarden.autofill.model.FieldType.Organization
import com.framepersecond.m3bitwarden.autofill.model.FieldType.Otp
import com.framepersecond.m3bitwarden.autofill.model.FieldType.OtpDigit
import com.framepersecond.m3bitwarden.autofill.model.FieldType.Password
import com.framepersecond.m3bitwarden.autofill.model.FieldType.Phone
import com.framepersecond.m3bitwarden.autofill.model.FieldType.PostalCode
import com.framepersecond.m3bitwarden.autofill.model.FieldType.State
import com.framepersecond.m3bitwarden.autofill.model.FieldType.Street
import com.framepersecond.m3bitwarden.autofill.model.FieldType.Title
import com.framepersecond.m3bitwarden.autofill.model.FieldType.Unknown
import com.framepersecond.m3bitwarden.autofill.model.FieldType.Username
import com.framepersecond.m3bitwarden.autofill.model.NodeKind

/**
 * Labelled forms for measuring and training field detection.
 *
 * Every form mirrors a real page or app screen: the attribute names follow the public markup of
 * the shop systems, payment providers and login pages named in [CorpusForm.source] (Shopify,
 * WooCommerce, Magento, Shopware, Stripe, Adyen, Braintree, Keycloak, …), and fields are laid
 * out the way Chrome and Firefox report them to an Android autofill service (attributes
 * `name`, `id`, `type`, `autocomplete`, `placeholder`, Chrome's computed `label`).
 * [Unknown] marks fields that must stay empty (search, coupons, captchas, …).
 */
internal data class CorpusForm(
    val source: String,
    val root: AutofillNode,
    val expected: Map<String, FieldType>,
    val intent: FormIntent?,
)

internal class FormBuilder(private val domain: String?) {
    private val nodes = ArrayList<AutofillNode>()
    val expected = LinkedHashMap<String, FieldType>()
    private var n = 0

    /** A web input as browsers report it. [label] is the computed label (Chrome's `label` attribute). */
    fun web(
        expect: FieldType,
        type: String = "text",
        name: String? = null,
        id: String? = null,
        placeholder: String? = null,
        label: String? = null,
        autocomplete: String? = null,
        aria: String? = null,
        maxLength: Int = -1,
        inputmode: String? = null,
        labelNode: String? = null,
        extra: Map<String, String> = emptyMap(),
    ) {
        val attrs = buildMap {
            put("type", type)
            name?.let { put("name", it.lowercase()) }
            id?.let { put("id", it.lowercase()) }
            placeholder?.let { put("placeholder", it.lowercase()) }
            label?.let { put("label", it.lowercase()) }
            autocomplete?.let { put("autocomplete", it.lowercase()) }
            aria?.let { put("aria-label", it.lowercase()) }
            inputmode?.let { put("inputmode", it) }
            if (maxLength > 0) put("maxlength", maxLength.toString())
            putAll(extra)
        }
        labelNode?.let { nodes += AutofillNode(id = null, kind = NodeKind.Label, text = it) }
        val key = "f${++n}"
        nodes += AutofillNode(
            id = key, kind = NodeKind.TextInput, htmlTag = "input", htmlAttributes = attrs,
            hint = placeholder, maxLength = maxLength, inputType = InputTypeBits.CLASS_TEXT,
        )
        expected[key] = expect
    }

    /** A web `<select>`. */
    fun select(expect: FieldType, name: String? = null, id: String? = null, label: String? = null, autocomplete: String? = null, options: List<String>, labelNode: String? = null) {
        val attrs = buildMap {
            name?.let { put("name", it.lowercase()) }
            id?.let { put("id", it.lowercase()) }
            label?.let { put("label", it.lowercase()) }
            autocomplete?.let { put("autocomplete", it.lowercase()) }
        }
        labelNode?.let { nodes += AutofillNode(id = null, kind = NodeKind.Label, text = it) }
        val key = "f${++n}"
        nodes += AutofillNode(id = key, kind = NodeKind.Select, htmlTag = "select", htmlAttributes = attrs, options = options)
        expected[key] = expect
    }

    /** A native Android view. [label] becomes a TextView right before it. */
    fun app(
        expect: FieldType,
        idEntry: String? = null,
        hint: String? = null,
        inputType: Int = InputTypeBits.CLASS_TEXT,
        hints: List<String> = emptyList(),
        label: String? = null,
        maxLength: Int = -1,
        description: String? = null,
    ) {
        label?.let { nodes += AutofillNode(id = null, kind = NodeKind.Label, text = it) }
        val key = "f${++n}"
        nodes += AutofillNode(
            id = key, kind = NodeKind.TextInput, inputType = inputType, idEntry = idEntry, hint = hint,
            autofillHints = hints, maxLength = maxLength, contentDescription = description,
        )
        expected[key] = expect
    }

    fun button(text: String) {
        nodes += AutofillNode(id = "b${++n}", kind = NodeKind.Button, text = text)
    }

    fun text(text: String) {
        nodes += AutofillNode(id = null, kind = NodeKind.Label, text = text)
    }

    fun build(): AutofillNode {
        val form = AutofillNode(id = null, kind = NodeKind.Container, htmlTag = if (domain != null) "form" else null, children = nodes.toList())
        return AutofillNode(id = "root", kind = NodeKind.Container, children = listOf(form), webDomain = domain, webScheme = domain?.let { "https" })
    }
}

internal object FormCorpus {
    private val forms = ArrayList<CorpusForm>()

    private fun form(source: String, domain: String?, intent: FormIntent? = null, block: FormBuilder.() -> Unit) {
        val b = FormBuilder(domain).apply(block)
        forms += CorpusForm(source, b.build(), b.expected.toMap(), intent)
    }

    private const val PW = InputTypeBits.CLASS_TEXT or InputTypeBits.TEXT_VARIATION_PASSWORD
    private const val MAIL = InputTypeBits.CLASS_TEXT or InputTypeBits.TEXT_VARIATION_EMAIL_ADDRESS
    private const val NUM_PW = InputTypeBits.CLASS_NUMBER or InputTypeBits.NUMBER_VARIATION_PASSWORD
    private const val NUM = InputTypeBits.CLASS_NUMBER
    private const val PHONE = InputTypeBits.CLASS_PHONE
    private const val PERSON = InputTypeBits.CLASS_TEXT or InputTypeBits.TEXT_VARIATION_PERSON_NAME
    private val MONTHS = listOf("Monat") + (1..12).map { it.toString().padStart(2, '0') }
    private val YEARS = listOf("Jahr") + (2026..2036).map { it.toString() }
    private val COUNTRIES = listOf("Deutschland", "Österreich", "Schweiz", "Niederlande", "Frankreich")
    private val SALUTATIONS = listOf("Bitte wählen", "Herr", "Frau", "Divers")
    private val STATES = listOf("Bitte wählen", "Baden-Württemberg", "Bayern", "Berlin", "Nordrhein-Westfalen")

    val all: List<CorpusForm> by lazy {
        // ---------------------------------------------------------------- logins (web)
        form("Amazon Anmeldung Schritt 1", "www.amazon.de", FormIntent.UsernameStep) {
            text("Anmelden")
            web(Email, type = "email", name = "email", id = "ap_email", autocomplete = "username", label = "E-Mail-Adresse oder Mobiltelefonnummer", maxLength = 128)
            button("Weiter")
        }
        form("Amazon Anmeldung Schritt 2", "www.amazon.de", FormIntent.PasswordStep) {
            web(Password, type = "password", name = "password", id = "ap_password", autocomplete = "current-password", label = "Passwort")
            button("Anmelden")
        }
        form("Google Kontoauswahl", "accounts.google.com", FormIntent.UsernameStep) {
            web(Email, type = "email", name = "identifier", id = "identifierId", autocomplete = "username webauthn", aria = "E-Mail oder Telefonnummer")
            button("Weiter")
        }
        form("Google Passwort", "accounts.google.com", FormIntent.PasswordStep) {
            web(Password, type = "password", name = "Passwd", autocomplete = "current-password", aria = "Passwort eingeben")
            button("Weiter")
        }
        form("eBay", "signin.ebay.de", FormIntent.UsernameStep) {
            web(Username, name = "userid", id = "userid", autocomplete = "username", placeholder = "E-Mail oder Nutzername")
            button("Weiter")
        }
        form("PayPal", "www.paypal.com", FormIntent.Login) {
            web(Email, type = "email", name = "login_email", id = "email", autocomplete = "username", placeholder = "E-Mail-Adresse oder Handynummer")
            web(Password, type = "password", name = "login_password", id = "password", autocomplete = "current-password", placeholder = "Passwort")
            button("Einloggen")
        }
        form("GitHub", "github.com", FormIntent.Login) {
            web(Username, name = "login", id = "login_field", autocomplete = "username", label = "Username or email address")
            web(Password, type = "password", name = "password", id = "password", autocomplete = "current-password", label = "Password")
            button("Sign in")
        }
        form("GitHub 2FA", "github.com", FormIntent.OneTimeCode) {
            web(Otp, name = "app_otp", id = "app_totp", autocomplete = "one-time-code", inputmode = "numeric", maxLength = 6, label = "Authentication code")
            button("Verify")
        }
        form("Keycloak (DB, Behörden)", "accounts.bahn.de", FormIntent.Login) {
            web(Username, name = "username", id = "username", autocomplete = "username", label = "Benutzername oder E-Mail")
            web(Password, type = "password", name = "password", id = "password", autocomplete = "current-password", label = "Passwort")
            button("Anmelden")
        }
        form("Sparkasse Online-Banking (verschleierte Namen)", "www.sparkasse-koelnbonn.de", FormIntent.Login) {
            web(Username, name = "Xa1b2c3d4e5", id = "Xa1b2c3d4e5", label = "Anmeldename", labelNode = "Anmeldename")
            web(Password, type = "password", name = "Yf9e8d7c6b5", id = "Yf9e8d7c6b5", label = "PIN", labelNode = "PIN")
            button("Anmelden")
        }
        form("Netflix", "www.netflix.com", FormIntent.Login) {
            web(Email, name = "userLoginId", id = "id_userLoginId", autocomplete = "email", label = "E-Mail-Adresse oder Telefonnummer")
            web(Password, type = "password", name = "password", id = "id_password", autocomplete = "password", label = "Passwort")
            button("Anmelden")
        }
        form("Microsoft", "login.live.com", FormIntent.UsernameStep) {
            web(Email, type = "email", name = "loginfmt", id = "i0116", autocomplete = "username", aria = "Geben Sie Ihre E-Mail-Adresse, Telefonnummer oder Ihren Skype-Namen ein.", placeholder = "E-Mail, Telefon oder Skype")
            button("Weiter")
        }
        form("Microsoft Passwort", "login.live.com", FormIntent.PasswordStep) {
            web(Password, type = "password", name = "passwd", id = "i0118", autocomplete = "current-password", placeholder = "Kennwort")
            button("Anmelden")
        }
        form("Apple-Account", "idmsa.apple.com", FormIntent.Login) {
            web(Username, id = "account_name_text_field", autocomplete = "off", placeholder = "E-Mail oder Telefonnummer", label = "E-Mail oder Telefonnummer")
            web(Password, type = "password", id = "password_text_field", autocomplete = "off", placeholder = "Passwort")
            button("Weiter")
        }
        form("Nextcloud", "cloud.jochenderrochen.de", FormIntent.Login) {
            web(Username, name = "user", id = "user", autocomplete = "username", placeholder = "Kontoname oder E-Mail")
            web(Password, type = "password", name = "password", id = "password", autocomplete = "current-password", placeholder = "Passwort")
            button("Anmelden")
        }
        form("Home Assistant", "ha.jochenderrochen.de", FormIntent.Login) {
            web(Username, name = "username", autocomplete = "username", label = "Benutzername")
            web(Password, type = "password", name = "password", autocomplete = "current-password", label = "Passwort")
            button("Anmelden")
        }
        form("Home Assistant MFA", "ha.jochenderrochen.de", FormIntent.OneTimeCode) {
            web(Otp, name = "code", autocomplete = "one-time-code", label = "Code")
            button("Weiter")
        }
        form("Proxmox VE", "pve.jochenderrochen.de", FormIntent.Login) {
            web(Username, name = "username", id = "textfield-1018-inputEl", label = "Benutzername")
            web(Password, type = "password", name = "password", id = "textfield-1019-inputEl", label = "Kennwort")
            web(Unknown, name = "realm", id = "combobox-1020-inputEl", label = "Realm", extra = mapOf("readonly" to "readonly"))
            web(Unknown, name = "lang", id = "combobox-1021-inputEl", label = "Sprache", extra = mapOf("readonly" to "readonly"))
            button("Login")
        }
        form("Synology DSM", "nas.jochenderrochen.de", FormIntent.Login) {
            web(Username, name = "username", id = "login_username", placeholder = "Benutzername")
            web(Password, type = "password", name = "passwd", id = "login_passwd", placeholder = "Passwort")
            button("Anmelden")
        }
        form("Synology 2FA", "nas.jochenderrochen.de", FormIntent.OneTimeCode) {
            web(Otp, name = "otp_code", id = "login_otp", placeholder = "Code eingeben", maxLength = 6)
            button("Anmelden")
        }
        form("WordPress", "blog.example.de", FormIntent.Login) {
            web(Username, name = "log", id = "user_login", autocomplete = "username", label = "Benutzername oder E-Mail-Adresse")
            web(Password, type = "password", name = "pwd", id = "user_pass", autocomplete = "current-password", label = "Passwort")
            button("Anmelden")
        }
        form("Discord", "discord.com", FormIntent.Login) {
            web(Username, name = "email", aria = "E-Mail oder Telefonnummer", autocomplete = "off")
            web(Password, type = "password", name = "password", aria = "Passwort", autocomplete = "off")
            button("Anmelden")
        }
        form("Steam (React, ohne Namen)", "store.steampowered.com", FormIntent.Login) {
            web(Username, labelNode = "Mit Kontonamen anmelden")
            web(Password, type = "password", labelNode = "Passwort")
            button("Anmelden")
        }
        form("Instagram", "www.instagram.com", FormIntent.Login) {
            web(Username, name = "username", aria = "Telefonnummer, Benutzername oder E-Mail-Adresse", maxLength = 75)
            web(Password, type = "password", name = "password", aria = "Passwort")
            button("Anmelden")
        }
        form("Zalando", "accounts.zalando.com", FormIntent.Login) {
            web(Email, type = "email", name = "login.email", id = "login.email", autocomplete = "username", label = "E-Mail-Adresse")
            web(Password, type = "password", name = "login.secret", id = "login.secret", autocomplete = "current-password", label = "Passwort")
            button("Anmelden")
        }
        form("Reddit", "www.reddit.com", FormIntent.Login) {
            web(Username, name = "username", id = "login-username", label = "Email or username")
            web(Password, type = "password", name = "password", id = "login-password", label = "Password")
            button("Log In")
        }
        form("Vodafone MeinVodafone", "www.vodafone.de", FormIntent.Login) {
            web(Username, name = "userName", id = "txtUsername", label = "Benutzername / E-Mail")
            web(Password, type = "password", name = "password", id = "txtPassword", label = "Kennwort")
            button("Login")
        }
        form("Router FRITZ!Box (Benutzer fest vorgegeben)", "fritz.box", FormIntent.PasswordStep) {
            web(Unknown, name = "uiViewUser", id = "uiViewUser", label = "Benutzer", extra = mapOf("readonly" to "readonly"))
            web(Password, type = "password", name = "uiPass", id = "uiPass", label = "Kennwort")
            button("Anmelden")
        }
        form("Banking TAN", "banking.dkb.de", FormIntent.OneTimeCode) {
            text("Bitte bestätige den Auftrag")
            web(Otp, name = "tan", id = "tanInput", inputmode = "numeric", maxLength = 6, label = "TAN")
            button("Freigeben")
        }
        form("Geteilter 6-stelliger Code", "auth.example.com", FormIntent.OneTimeCode) {
            text("Bestätigungscode eingeben")
            repeat(6) { i -> web(OtpDigit, inputmode = "numeric", maxLength = 1, aria = "Ziffer ${i + 1}") }
            button("Bestätigen")
        }
        form("E-Mail-Code", "id.example.de", FormIntent.OneTimeCode) {
            web(Otp, name = "verification_code", placeholder = "6-stelliger Code", inputmode = "numeric")
            button("Bestätigen")
        }

        // ---------------------------------------------------------------- native apps
        form("App mit Ressourcen-IDs", null, FormIntent.Login) {
            app(Email, idEntry = "et_email", hint = "E-Mail", inputType = MAIL)
            app(Password, idEntry = "et_password", hint = "Passwort", inputType = PW)
            button("Anmelden")
        }
        form("Compose-App ohne IDs", null, FormIntent.Login) {
            app(Email, label = "E-Mail-Adresse")
            app(Password, inputType = PW, label = "Passwort")
            button("Einloggen")
        }
        form("Banking-App", null, FormIntent.Login) {
            app(Username, idEntry = "edit_login_name", hint = "Anmeldename")
            app(Password, idEntry = "edit_pin", hint = "PIN", inputType = NUM_PW)
            button("Login")
        }
        form("App mit Autofill-Hints", null, FormIntent.Login) {
            app(Email, idEntry = "input1", hints = listOf("emailAddress"))
            app(Password, idEntry = "input2", hints = listOf("password"), inputType = PW)
        }
        form("App-OTP", null, FormIntent.OneTimeCode) {
            app(Otp, idEntry = "otp_input", hint = "Bestätigungscode", inputType = NUM, maxLength = 6)
        }
        form("App Kundennummer", null, FormIntent.Login) {
            app(Username, idEntry = "customer_number", hint = "Kundennummer", inputType = NUM)
            app(Password, idEntry = "password", hint = "Passwort", inputType = PW)
        }
        form("App mit Content-Description", null, FormIntent.Login) {
            app(Username, description = "Benutzername")
            app(Password, description = "Kennwort", inputType = PW)
        }
        form("App Karte", null, FormIntent.Card) {
            app(CardNumber, idEntry = "card_number", hints = listOf("creditCardNumber"), inputType = NUM)
            app(CardExpiry, idEntry = "card_expiry", hints = listOf("creditCardExpirationDate"))
            app(CardCvv, idEntry = "card_cvc", hints = listOf("creditCardSecurityCode"), inputType = NUM)
            app(CardHolder, idEntry = "card_holder", hint = "Karteninhaber", inputType = PERSON)
        }
        form("App Profil", null, FormIntent.Identity) {
            app(FirstName, idEntry = "first_name", hint = "Vorname", inputType = PERSON)
            app(LastName, idEntry = "last_name", hint = "Nachname", inputType = PERSON)
            app(Phone, idEntry = "phone", hint = "Mobilnummer", inputType = PHONE)
            app(Email, idEntry = "email", hint = "E-Mail", inputType = MAIL)
        }

        // ---------------------------------------------------------------- sign-up and password change
        form("GitHub Registrierung", "github.com", FormIntent.Signup) {
            web(Email, type = "email", name = "user[email]", id = "email", autocomplete = "email", label = "Email")
            web(NewPassword, type = "password", name = "user[password]", id = "password", autocomplete = "new-password", label = "Password")
            web(Username, name = "user[login]", id = "login", autocomplete = "off", label = "Username")
            button("Create account")
        }
        form("Deutsche Registrierung", "www.beispielshop.de", FormIntent.Signup) {
            select(Title, name = "anrede", id = "anrede", label = "Anrede", options = SALUTATIONS)
            web(FirstName, name = "vorname", id = "vorname", label = "Vorname")
            web(LastName, name = "nachname", id = "nachname", label = "Nachname")
            web(Email, type = "email", name = "email", id = "email", label = "E-Mail-Adresse")
            web(Email, type = "email", name = "email_confirm", id = "email_confirm", label = "E-Mail-Adresse wiederholen")
            web(NewPassword, type = "password", name = "passwort", id = "passwort", label = "Passwort")
            web(ConfirmPassword, type = "password", name = "passwort_wdh", id = "passwort_wdh", label = "Passwort wiederholen")
            button("Jetzt registrieren")
        }
        form("Shopware Registrierung", "shop.example.de", FormIntent.Signup) {
            select(Title, name = "salutationId", id = "personalSalutation", label = "Anrede*", options = listOf("Keine Angabe", "Herr", "Frau"))
            web(FirstName, name = "firstName", id = "personalFirstName", autocomplete = "section-personal given-name", label = "Vorname*")
            web(LastName, name = "lastName", id = "personalLastName", autocomplete = "section-personal family-name", label = "Nachname*")
            web(Email, type = "email", name = "email", id = "personalMail", autocomplete = "section-personal email", label = "E-Mail-Adresse*")
            web(NewPassword, type = "password", name = "password", id = "personalPassword", autocomplete = "new-password", label = "Passwort*")
            web(Address, name = "billingAddress[street]", id = "billingAddressAddressStreet", autocomplete = "section-billing address-line1", label = "Straße und Hausnummer*")
            web(PostalCode, name = "billingAddress[zipcode]", id = "billingAddressAddressZipcode", autocomplete = "section-billing postal-code", label = "PLZ*")
            web(City, name = "billingAddress[city]", id = "billingAddressAddressCity", autocomplete = "section-billing address-level2", label = "Ort*")
            select(Country, name = "billingAddress[countryId]", id = "billingAddressAddressCountry", autocomplete = "section-billing country", label = "Land*", options = COUNTRIES)
            button("Weiter")
        }
        form("Passwort ändern", "konto.example.de", FormIntent.ChangePassword) {
            text("Passwort ändern")
            web(Password, type = "password", name = "current_password", label = "Aktuelles Passwort")
            web(NewPassword, type = "password", name = "new_password", label = "Neues Passwort")
            web(ConfirmPassword, type = "password", name = "new_password_confirmation", label = "Neues Passwort bestätigen")
            button("Speichern")
        }
        form("Passwort zurücksetzen (nur neu)", "konto.example.de", FormIntent.ChangePassword) {
            text("Passwort zurücksetzen")
            web(NewPassword, type = "password", name = "password", autocomplete = "new-password", label = "Neues Passwort")
            web(ConfirmPassword, type = "password", name = "password_confirmation", autocomplete = "new-password", label = "Passwort bestätigen")
            button("Passwort ändern")
        }

        // ---------------------------------------------------------------- checkout addresses
        form("Shopify Checkout (alt)", "shop.example.com", FormIntent.Identity) {
            web(Email, type = "email", name = "checkout[email]", id = "checkout_email", autocomplete = "shipping email", placeholder = "E-Mail")
            web(FirstName, name = "checkout[shipping_address][first_name]", id = "checkout_shipping_address_first_name", autocomplete = "shipping given-name", placeholder = "Vorname")
            web(LastName, name = "checkout[shipping_address][last_name]", id = "checkout_shipping_address_last_name", autocomplete = "shipping family-name", placeholder = "Nachname")
            web(Organization, name = "checkout[shipping_address][company]", id = "checkout_shipping_address_company", autocomplete = "shipping organization", placeholder = "Firma (optional)")
            web(Address, name = "checkout[shipping_address][address1]", id = "checkout_shipping_address_address1", autocomplete = "shipping address-line1", placeholder = "Straße und Hausnummer")
            web(AddressLine2, name = "checkout[shipping_address][address2]", id = "checkout_shipping_address_address2", autocomplete = "shipping address-line2", placeholder = "Wohnung, Zimmer usw. (optional)")
            web(PostalCode, name = "checkout[shipping_address][zip]", id = "checkout_shipping_address_zip", autocomplete = "shipping postal-code", placeholder = "Postleitzahl")
            web(City, name = "checkout[shipping_address][city]", id = "checkout_shipping_address_city", autocomplete = "shipping address-level2", placeholder = "Stadt")
            select(Country, name = "checkout[shipping_address][country]", id = "checkout_shipping_address_country", autocomplete = "shipping country", options = COUNTRIES)
            web(Phone, type = "tel", name = "checkout[shipping_address][phone]", id = "checkout_shipping_address_phone", autocomplete = "shipping tel", placeholder = "Telefon")
            web(Unknown, name = "checkout[reduction_code]", id = "checkout_reduction_code", placeholder = "Rabattcode oder Geschenkgutschein")
        }
        form("Shopify Checkout (neu)", "shop.example.com", FormIntent.Identity) {
            web(Email, type = "email", name = "email", id = "email", autocomplete = "shipping email", label = "E-Mail")
            select(Country, name = "countryCode", id = "Select0", autocomplete = "shipping country", label = "Land/Region", options = COUNTRIES)
            web(FirstName, name = "firstName", id = "TextField0", autocomplete = "shipping given-name", label = "Vorname (optional)")
            web(LastName, name = "lastName", id = "TextField1", autocomplete = "shipping family-name", label = "Nachname")
            web(Address, name = "address1", id = "shipping-address1", autocomplete = "shipping address-line1", label = "Adresse")
            web(AddressLine2, name = "address2", id = "TextField2", autocomplete = "shipping address-line2", label = "Wohnung, Zimmer usw. (optional)")
            web(PostalCode, name = "postalCode", id = "TextField3", autocomplete = "shipping postal-code", label = "Postleitzahl")
            web(City, name = "city", id = "TextField4", autocomplete = "shipping address-level2", label = "Stadt")
            web(Phone, type = "tel", name = "phone", id = "TextField5", autocomplete = "shipping tel", label = "Telefon (optional)")
        }
        form("WooCommerce Kasse", "laden.example.de", FormIntent.Identity) {
            select(Title, name = "billing_title", id = "billing_title", label = "Anrede", options = SALUTATIONS)
            web(FirstName, name = "billing_first_name", id = "billing_first_name", autocomplete = "given-name", label = "Vorname")
            web(LastName, name = "billing_last_name", id = "billing_last_name", autocomplete = "family-name", label = "Nachname")
            web(Organization, name = "billing_company", id = "billing_company", autocomplete = "organization", label = "Firmenname (optional)")
            select(Country, name = "billing_country", id = "billing_country", autocomplete = "country", label = "Land / Region", options = COUNTRIES)
            web(Address, name = "billing_address_1", id = "billing_address_1", autocomplete = "address-line1", placeholder = "Straßenname und Hausnummer", label = "Straße")
            web(AddressLine2, name = "billing_address_2", id = "billing_address_2", autocomplete = "address-line2", placeholder = "Wohnung, Suite, Zimmer usw. (optional)")
            web(PostalCode, name = "billing_postcode", id = "billing_postcode", autocomplete = "postal-code", label = "Postleitzahl")
            web(City, name = "billing_city", id = "billing_city", autocomplete = "address-level2", label = "Ort / Stadt")
            select(State, name = "billing_state", id = "billing_state", autocomplete = "address-level1", label = "Bundesland / Landkreis (optional)", options = STATES)
            web(Phone, type = "tel", name = "billing_phone", id = "billing_phone", autocomplete = "tel", label = "Telefon")
            web(Email, type = "email", name = "billing_email", id = "billing_email", autocomplete = "email username", label = "E-Mail-Adresse")
            web(Unknown, name = "coupon_code", id = "coupon_code", placeholder = "Gutscheincode")
        }
        form("WooCommerce alt (ohne autocomplete)", "laden.example.de", FormIntent.Identity) {
            web(FirstName, name = "billing_first_name", id = "billing_first_name", label = "Vorname")
            web(LastName, name = "billing_last_name", id = "billing_last_name", label = "Nachname")
            web(Address, name = "billing_address_1", id = "billing_address_1", placeholder = "Straße und Hausnummer", label = "Straße")
            web(AddressLine2, name = "billing_address_2", id = "billing_address_2", placeholder = "Appartement, Suite, Einheit usw. (optional)")
            web(PostalCode, name = "billing_postcode", id = "billing_postcode", label = "PLZ")
            web(City, name = "billing_city", id = "billing_city", label = "Ort / Stadt")
            web(State, name = "billing_state", id = "billing_state", label = "Bundesland")
            web(Phone, type = "tel", name = "billing_phone", id = "billing_phone", label = "Telefon")
            web(Email, type = "email", name = "billing_email", id = "billing_email", label = "E-Mail-Adresse")
        }
        form("Magento 2 Lieferadresse", "store.example.de", FormIntent.Identity) {
            web(Email, type = "email", name = "username", id = "customer-email", autocomplete = "email", label = "E-Mail-Adresse")
            web(FirstName, name = "firstname", label = "Vorname")
            web(LastName, name = "lastname", label = "Nachname")
            web(Organization, name = "company", label = "Firma")
            web(Street, name = "street[0]", label = "Straße")
            web(HouseNumber, name = "street[1]", label = "Hausnummer")
            select(Country, name = "country_id", label = "Land", options = COUNTRIES)
            select(State, name = "region_id", label = "Bundesland", options = STATES)
            web(City, name = "city", label = "Stadt")
            web(PostalCode, name = "postcode", label = "PLZ")
            web(Phone, type = "tel", name = "telephone", label = "Telefonnummer")
        }
        form("Shopware 6 Adresse", "shop.example.de", FormIntent.Identity) {
            select(Title, name = "address[salutationId]", id = "addresspersonalSalutation", label = "Anrede", options = SALUTATIONS)
            web(FirstName, name = "address[firstName]", id = "addresspersonalFirstName", autocomplete = "section-personal given-name", label = "Vorname")
            web(LastName, name = "address[lastName]", id = "addresspersonalLastName", autocomplete = "section-personal family-name", label = "Nachname")
            web(Address, name = "address[street]", id = "addressAddressStreet", autocomplete = "address-line1", label = "Straße und Hausnummer")
            web(PostalCode, name = "address[zipcode]", id = "addressAddressZipcode", autocomplete = "postal-code", label = "PLZ")
            web(City, name = "address[city]", id = "addressAddressCity", autocomplete = "address-level2", label = "Ort")
            select(Country, name = "address[countryId]", id = "addressAddressCountry", autocomplete = "country", label = "Land", options = COUNTRIES)
            select(State, name = "address[countryStateId]", id = "addressAddressCountryState", autocomplete = "address-level1", label = "Bundesland", options = STATES)
            web(Phone, type = "tel", name = "address[phoneNumber]", id = "addressAddressPhoneNumber", autocomplete = "section-personal tel", label = "Telefonnummer")
        }
        form("Amazon Adresse", "www.amazon.de", FormIntent.Identity) {
            select(Country, name = "address-ui-widgets-countryCode", id = "address-ui-widgets-countryCode-dropdown-nativeId", label = "Land/Region", options = COUNTRIES)
            web(FullName, name = "address-ui-widgets-enterAddressFullName", id = "address-ui-widgets-enterAddressFullName", label = "Vollständiger Name (Vor- und Nachname)")
            web(Phone, type = "tel", name = "address-ui-widgets-enterAddressPhoneNumber", id = "address-ui-widgets-enterAddressPhoneNumber", label = "Telefonnummer")
            web(Address, name = "address-ui-widgets-enterAddressLine1", id = "address-ui-widgets-enterAddressLine1", label = "Straße und Hausnummer", autocomplete = "off")
            web(AddressLine2, name = "address-ui-widgets-enterAddressLine2", id = "address-ui-widgets-enterAddressLine2", label = "Adresszusatz (optional)", autocomplete = "off")
            web(PostalCode, name = "address-ui-widgets-enterAddressPostalCode", id = "address-ui-widgets-enterAddressPostalCode", label = "Postleitzahl")
            web(City, name = "address-ui-widgets-enterAddressCity", id = "address-ui-widgets-enterAddressCity", label = "Stadt")
        }
        form("Otto (Straße und Hausnummer getrennt)", "www.otto.de", FormIntent.Identity) {
            select(Title, name = "salutation", label = "Anrede", options = SALUTATIONS)
            web(FirstName, name = "firstName", label = "Vorname")
            web(LastName, name = "lastName", label = "Nachname")
            web(Street, name = "street", label = "Straße")
            web(HouseNumber, name = "houseNumber", label = "Hausnummer", maxLength = 10)
            web(AddressLine2, name = "addition", label = "Adresszusatz")
            web(PostalCode, name = "zipCode", label = "Postleitzahl", maxLength = 5)
            web(City, name = "city", label = "Ort")
        }
        form("Deutsche Bahn Reisende", "www.bahn.de", FormIntent.Identity) {
            select(Title, name = "anrede", label = "Anrede", options = SALUTATIONS)
            web(Unknown, name = "titel", label = "Titel (optional)")
            web(FirstName, name = "vorname", autocomplete = "given-name", label = "Vorname")
            web(LastName, name = "nachname", autocomplete = "family-name", label = "Nachname")
            web(Unknown, name = "geburtsdatum", label = "Geburtsdatum", placeholder = "TT.MM.JJJJ")
            web(Email, type = "email", name = "email", autocomplete = "email", label = "E-Mail")
            web(Phone, type = "tel", name = "telefon", autocomplete = "tel", label = "Mobilnummer (optional)")
        }
        form("US-Adresse", "store.example.com", FormIntent.Identity) {
            web(FullName, name = "fullName", autocomplete = "name", label = "Full name")
            web(Address, name = "addressLine1", autocomplete = "address-line1", label = "Street address")
            web(AddressLine2, name = "addressLine2", autocomplete = "address-line2", label = "Apt, suite, etc. (optional)")
            web(City, name = "city", autocomplete = "address-level2", label = "City")
            select(State, name = "state", autocomplete = "address-level1", label = "State", options = listOf("State", "California", "New York", "Texas"))
            web(PostalCode, name = "zip", autocomplete = "postal-code", label = "ZIP code")
            web(Phone, type = "tel", name = "phone", autocomplete = "tel", label = "Phone number")
        }
        form("Kontaktformular", "www.handwerker.example.de", FormIntent.Identity) {
            web(FullName, name = "your-name", label = "Ihr Name")
            web(Email, type = "email", name = "your-email", label = "Ihre E-Mail-Adresse")
            web(Phone, type = "tel", name = "your-phone", label = "Telefon")
            web(Unknown, name = "your-subject", label = "Betreff")
        }
        form("Blog-Kommentar", "blog.example.de", null) {
            web(FullName, name = "author", id = "author", autocomplete = "name", label = "Name")
            web(Email, type = "email", name = "email", id = "email", autocomplete = "email", label = "E-Mail")
            web(Unknown, type = "url", name = "url", id = "url", autocomplete = "url", label = "Website")
        }

        // ---------------------------------------------------------------- payment cards
        form("Stripe Elements", "checkout.stripe.com", FormIntent.Card) {
            web(CardNumber, name = "cardnumber", autocomplete = "cc-number", inputmode = "numeric", placeholder = "1234 1234 1234 1234", aria = "Kreditkartennummer")
            web(CardExpiry, name = "exp-date", autocomplete = "cc-exp", inputmode = "numeric", placeholder = "MM / JJ", aria = "Gültig bis")
            web(CardCvv, name = "cvc", autocomplete = "cc-csc", inputmode = "numeric", placeholder = "CVC", aria = "Prüfnummer")
            web(CardHolder, name = "billingName", id = "billingName", autocomplete = "ccname", placeholder = "Vollständiger Name", label = "Name des Karteninhabers")
            web(PostalCode, name = "postal", autocomplete = "postal-code", placeholder = "PLZ")
        }
        form("Adyen Drop-in", "pay.example.com", FormIntent.Card) {
            web(CardNumber, id = "adyen-checkout-encryptedCardNumber-1700000000001", aria = "Kartennummer", inputmode = "numeric", extra = mapOf("data-fieldtype" to "encryptedcardnumber"))
            web(CardExpiry, id = "adyen-checkout-encryptedExpiryDate-1700000000002", aria = "Ablaufdatum", placeholder = "MM/JJ", inputmode = "numeric", extra = mapOf("data-fieldtype" to "encryptedexpirydate"))
            web(CardCvv, id = "adyen-checkout-encryptedSecurityCode-1700000000003", aria = "Sicherheitscode", placeholder = "3 Ziffern", inputmode = "numeric", extra = mapOf("data-fieldtype" to "encryptedsecuritycode"))
            web(CardHolder, name = "holderName", id = "adyen-checkout-holderName-1700000000004", placeholder = "J. Smith", label = "Name auf der Karte")
        }
        form("Braintree Hosted Fields", "pay.example.com", FormIntent.Card) {
            web(CardNumber, name = "credit-card-number", id = "credit-card-number", autocomplete = "cc-number", placeholder = "4111 1111 1111 1111")
            web(CardExpiry, name = "expiration", id = "expiration", autocomplete = "cc-exp", placeholder = "MM / YY")
            web(CardCvv, name = "cvv", id = "cvv", autocomplete = "cc-csc", placeholder = "•••")
        }
        form("Klassischer Shop mit Auswahllisten", "www.beispielshop.de", FormIntent.Card) {
            web(CardHolder, name = "payment[cc_owner]", id = "cc_owner", label = "Karteninhaber")
            web(CardNumber, name = "payment[cc_number]", id = "cc_number", label = "Kreditkartennummer", maxLength = 19)
            select(CardExpiryMonth, name = "payment[cc_exp_month]", id = "cc_exp_month", label = "Gültig bis", options = MONTHS)
            select(CardExpiryYear, name = "payment[cc_exp_year]", id = "cc_exp_year", options = YEARS)
            web(CardCvv, type = "password", name = "payment[cc_cid]", id = "cc_cid", label = "Kartenprüfnummer", maxLength = 4)
        }
        form("PayPal Gast-Kartenzahlung", "www.paypal.com", FormIntent.Card) {
            web(Email, type = "email", name = "email", id = "email", autocomplete = "email", label = "E-Mail-Adresse")
            web(CardNumber, name = "cardNumber", id = "cardNumber", autocomplete = "cc-number", label = "Kartennummer")
            web(CardExpiry, name = "cardExpiry", id = "cardExpiry", autocomplete = "cc-exp", placeholder = "MM/JJ", label = "Ablaufdatum")
            web(CardCvv, name = "cardCvv", id = "cardCvv", autocomplete = "cc-csc", label = "Prüfnummer")
            web(FirstName, name = "firstName", id = "firstName", autocomplete = "given-name", label = "Vorname")
            web(LastName, name = "lastName", id = "lastName", autocomplete = "family-name", label = "Nachname")
            web(Address, name = "billingLine1", id = "billingLine1", autocomplete = "address-line1", label = "Straße und Hausnummer")
            web(PostalCode, name = "billingPostalCode", id = "billingPostalCode", autocomplete = "postal-code", label = "PLZ")
            web(City, name = "billingCity", id = "billingCity", autocomplete = "address-level2", label = "Ort")
            web(Phone, type = "tel", name = "phone", id = "phone", autocomplete = "tel-national", label = "Mobilnummer")
        }
        form("Ablaufdatum in zwei Textfeldern", "shop.example.at", FormIntent.Card) {
            web(CardNumber, name = "ccnum", label = "Kartennummer")
            web(CardExpiryMonth, name = "ccmonth", placeholder = "MM", maxLength = 2, label = "Monat")
            web(CardExpiryYear, name = "ccyear", placeholder = "JJ", maxLength = 2, label = "Jahr")
            web(CardCvv, name = "cccvc", placeholder = "CVC", maxLength = 4)
        }
        form("SEPA-Lastschrift", "shop.example.de", FormIntent.Identity) {
            web(FullName, name = "account_holder", label = "Kontoinhaber")
            web(Unknown, name = "iban", label = "IBAN", placeholder = "DE00 0000 0000 0000 0000 00")
            web(Unknown, name = "bic", label = "BIC (optional)")
        }

        // ---------------------------------------------------------------- traps: must stay empty
        form("Suche", "www.example.de", null) {
            web(Unknown, type = "search", name = "q", placeholder = "Suchen …")
        }
        form("Website-Suche als Textfeld", "www.example.de", null) {
            web(Unknown, name = "s", id = "s", placeholder = "Suchbegriff eingeben", aria = "Suche")
        }
        form("Newsletter", "www.example.de", null) {
            web(Unknown, type = "email", name = "newsletter_email", placeholder = "Deine E-Mail-Adresse", label = "Newsletter abonnieren")
            button("Abonnieren")
        }
        form("Warenkorb Menge und Gutschein", "shop.example.de", null) {
            web(Unknown, type = "number", name = "quantity", label = "Menge")
            web(Unknown, name = "voucher", label = "Gutscheincode einlösen")
        }
        form("Captcha", "www.example.de", null) {
            web(Unknown, name = "captcha_code", label = "Bitte geben Sie die Zeichen ein")
        }
        form("Chat", "support.example.de", null) {
            web(Unknown, name = "message", placeholder = "Nachricht schreiben …")
        }
        form("Sendungsverfolgung", "www.dhl.de", null) {
            web(Unknown, name = "piececode", label = "Sendungsnummer")
        }
        forms.toList()
    }
}
