package com.framepersecond.m3bitwarden.autofill.corpus

import com.framepersecond.m3bitwarden.autofill.classify.FormIntent
import com.framepersecond.m3bitwarden.autofill.classify.InputTypeBits
import com.framepersecond.m3bitwarden.autofill.model.FieldType.Address
import com.framepersecond.m3bitwarden.autofill.model.FieldType.AddressLine2
import com.framepersecond.m3bitwarden.autofill.model.FieldType.CardCvv
import com.framepersecond.m3bitwarden.autofill.model.FieldType.CardExpiry
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

/**
 * Third set, written after rules and vocabulary were final. Measured once on 2026-11-24 before
 * any change: 71/76 fields (93.4 %), 18/20 intents (90 %) — the honest estimate for unseen pages.
 * The five errors were general weaknesses (OpenAM "IDToken" ids, example e-mails in placeholders,
 * "Name" next to "Vorname", "Name, Vorname", Portuguese "Apelido") and were fixed afterwards, so
 * from then on this set guards against regressions like the others.
 */
internal object ValidationCorpus {
    private val forms = ArrayList<CorpusForm>()

    private fun form(source: String, domain: String?, intent: FormIntent? = null, block: FormBuilder.() -> Unit) {
        val b = FormBuilder(domain).apply(block)
        forms += CorpusForm(source, b.build(), b.expected.toMap(), intent)
    }

    private const val PW = InputTypeBits.CLASS_TEXT or InputTypeBits.TEXT_VARIATION_PASSWORD
    private const val NUM = InputTypeBits.CLASS_NUMBER

    val all: List<CorpusForm> by lazy {
        form("Miles & More", "www.miles-and-more.com", FormIntent.Login) {
            web(Username, name = "loginId", id = "loginId", label = "Servicekartennummer oder Benutzername")
            web(Password, type = "password", name = "pin", id = "pin", label = "PIN / Passwort")
            button("Login")
        }
        form("Telekom Login", "accounts.login.idm.telekom.com", FormIntent.UsernameStep) {
            web(Username, name = "username", id = "username", autocomplete = "username", label = "E-Mail-Adresse oder Benutzername")
            button("Weiter")
        }
        form("o2 Mein o2", "login.o2online.de", FormIntent.Login) {
            web(Username, name = "IDToken1", id = "IDToken1", label = "Rufnummer, E-Mail oder Benutzername")
            web(Password, type = "password", name = "IDToken2", id = "IDToken2", label = "Kennwort")
            button("Einloggen")
        }
        form("Angular-App (nur Platzhalter)", "portal.example.de", FormIntent.Login) {
            web(Email, type = "email", id = "mat-input-0", placeholder = "E-Mail")
            web(Password, type = "password", id = "mat-input-1", placeholder = "Passwort")
            button("Anmelden")
        }
        form("React-App mit data-testid", "app.example.io", FormIntent.Login) {
            web(Email, extra = mapOf("data-testid" to "login-email-input"), placeholder = "you@company.com")
            web(Password, type = "password", extra = mapOf("data-testid" to "login-password-input"))
            button("Continue")
        }
        form("Jellyfin", "media.jochenderrochen.de", FormIntent.Login) {
            web(Username, id = "txtManualName", label = "Benutzer")
            web(Password, type = "password", id = "txtManualPassword", label = "Passwort")
            button("Anmelden")
        }
        form("Paperless-ngx", "docs.jochenderrochen.de", FormIntent.Login) {
            web(Username, name = "login", id = "inputUsername", autocomplete = "username", placeholder = "Benutzername")
            web(Password, type = "password", name = "password", id = "inputPassword", autocomplete = "current-password", placeholder = "Passwort")
            button("Anmelden")
        }
        form("Authentik 2FA", "auth.jochenderrochen.de", FormIntent.OneTimeCode) {
            web(Otp, name = "code", autocomplete = "one-time-code", inputmode = "numeric", placeholder = "Bitte Code eingeben")
            button("Weiter")
        }
        form("Code in acht Kästchen (Microsoft)", "login.microsoftonline.com", FormIntent.OneTimeCode) {
            repeat(8) { i -> web(OtpDigit, inputmode = "numeric", maxLength = 1, aria = "Codeziffer ${i + 1}") }
        }
        form("Etsy Registrierung", "www.etsy.com", FormIntent.Signup) {
            web(Email, type = "email", name = "email", id = "join_neu_email_field", label = "E-Mail-Adresse")
            web(FirstName, name = "first_name", id = "join_neu_first_name_field", label = "Vorname")
            web(NewPassword, type = "password", name = "password", id = "join_neu_password_field", label = "Passwort")
            button("Registrieren")
        }
        form("Vereinsportal Registrierung", "verein.example.de", FormIntent.Signup) {
            web(Username, name = "benutzername", label = "Gewünschter Benutzername")
            web(Email, type = "email", name = "mail", label = "E-Mail")
            web(NewPassword, type = "password", name = "pw1", label = "Passwort")
            web(ConfirmPassword, type = "password", name = "pw2", label = "Passwort (Wiederholung)")
            button("Konto anlegen")
        }
        form("Schweizer Post Adresse", "service.post.ch", FormIntent.Identity) {
            select(Title, name = "anrede", label = "Anrede", options = listOf("Herr", "Frau"))
            web(FirstName, name = "vorname", label = "Vorname")
            web(LastName, name = "name", label = "Name")
            web(Street, name = "strasse", label = "Strasse")
            web(HouseNumber, name = "hausnummer", label = "Hausnummer")
            web(PostalCode, name = "plz", label = "PLZ", maxLength = 4)
            web(City, name = "ort", label = "Ort")
        }
        form("Österreichischer Shop", "shop.example.at", FormIntent.Identity) {
            web(FirstName, name = "customer[firstname]", label = "Vorname")
            web(LastName, name = "customer[lastname]", label = "Nachname")
            web(Address, name = "customer[street]", label = "Straße / Hausnummer / Stiege / Tür")
            web(PostalCode, name = "customer[zip]", label = "PLZ")
            web(City, name = "customer[city]", label = "Ort")
            select(State, name = "customer[state]", label = "Bundesland", options = listOf("Wien", "Tirol", "Steiermark", "Kärnten"))
            web(Phone, type = "tel", name = "customer[phone]", label = "Telefon")
        }
        form("Schwedischer Shop", "butik.example.se", FormIntent.Identity) {
            web(FirstName, name = "fornamn", label = "Förnamn")
            web(LastName, name = "efternamn", label = "Efternamn")
            web(Address, name = "gatuadress", label = "Gatuadress")
            web(PostalCode, name = "postnummer", label = "Postnummer")
            web(City, name = "ort", label = "Ort")
            web(Phone, type = "tel", name = "mobil", label = "Mobilnummer")
        }
        form("Portugiesischer Shop", "loja.example.pt", FormIntent.Identity) {
            web(FirstName, name = "nome", label = "Nome")
            web(LastName, name = "apelido", label = "Apelido")
            web(Address, name = "morada", label = "Morada")
            web(PostalCode, name = "codigo_postal", label = "Código postal")
            web(City, name = "localidade", label = "Localidade")
        }
        form("Uber Eats Lieferadresse (US)", "www.ubereats.com", FormIntent.Identity) {
            web(Address, name = "streetAddress", placeholder = "Street address")
            web(AddressLine2, name = "aptOrSuite", placeholder = "Apt or suite number")
            web(Unknown, name = "dropoffNote", placeholder = "Add delivery instructions")
        }
        form("Ticketshop Rechnungsadresse", "tickets.example.de", FormIntent.Identity) {
            web(FullName, name = "rechnung_name", label = "Name, Vorname")
            web(Address, name = "rechnung_anschrift", label = "Anschrift")
            web(PostalCode, name = "rechnung_plz", label = "PLZ")
            web(City, name = "rechnung_ort", label = "Ort")
            select(Country, name = "rechnung_land", label = "Land", options = listOf("Deutschland", "Österreich", "Schweiz"))
            web(Email, type = "email", name = "rechnung_email", label = "E-Mail für Tickets")
        }
        form("Unzer Karte", "payment.unzer.com", FormIntent.Card) {
            web(CardNumber, name = "card-number", id = "card-element-id-number", aria = "Kartennummer", inputmode = "numeric")
            web(CardExpiry, name = "card-expiry-date", id = "card-element-id-expiry", aria = "Ablaufdatum", placeholder = "MM / JJ")
            web(CardCvv, name = "card-ccv", id = "card-element-id-cvc", aria = "CVC")
            web(CardHolder, name = "card-holder", id = "card-element-id-holder", aria = "Karteninhaber")
        }
        form("Karten-App ohne Hints", null, FormIntent.Card) {
            app(CardNumber, idEntry = "editCardNumber", hint = "Kartennummer", inputType = NUM)
            app(CardExpiry, idEntry = "editExpiry", hint = "MM/JJ", inputType = NUM)
            app(CardCvv, idEntry = "editCvv", hint = "CVV", inputType = NUM, maxLength = 3)
        }
        form("App Profil bearbeiten", null, FormIntent.Identity) {
            app(FullName, idEntry = "display_name", hint = "Anzeigename")
            app(Phone, idEntry = "phone_number", hint = "Telefonnummer")
            app(Email, idEntry = "contact_email", hint = "Kontakt-E-Mail")
        }
        form("Forum Suche und Antwort", "forum.example.de", null) {
            web(Unknown, name = "keywords", placeholder = "Forum durchsuchen")
            web(Unknown, name = "reply_text", placeholder = "Antwort schreiben")
        }
        form("Gewinnspiel-Code", "aktion.example.de", null) {
            web(Unknown, name = "aktionscode", label = "Aktionscode von der Verpackung")
        }
        forms.toList()
    }
}
