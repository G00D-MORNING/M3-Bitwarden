package com.framepersecond.m3bitwarden.autofill.corpus

import com.framepersecond.m3bitwarden.autofill.classify.FormIntent
import com.framepersecond.m3bitwarden.autofill.classify.InputTypeBits
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
import com.framepersecond.m3bitwarden.autofill.model.FieldType.Password
import com.framepersecond.m3bitwarden.autofill.model.FieldType.Phone
import com.framepersecond.m3bitwarden.autofill.model.FieldType.PostalCode
import com.framepersecond.m3bitwarden.autofill.model.FieldType.State
import com.framepersecond.m3bitwarden.autofill.model.FieldType.Street
import com.framepersecond.m3bitwarden.autofill.model.FieldType.Title
import com.framepersecond.m3bitwarden.autofill.model.FieldType.Unknown
import com.framepersecond.m3bitwarden.autofill.model.FieldType.Username

/**
 * Second set, never used to train the model. First measurement with rules only: 99/105 fields
 * (94.2 %). Its errors led to a broader multilingual vocabulary (address parts in NL/PL/CZ/Nordic/
 * PT/TR, banking ids), so it is no longer independent; [ValidationCorpus] was written afterwards.
 */
internal object HoldoutCorpus {
    private val forms = ArrayList<CorpusForm>()

    private fun form(source: String, domain: String?, intent: FormIntent? = null, block: FormBuilder.() -> Unit) {
        val b = FormBuilder(domain).apply(block)
        forms += CorpusForm(source, b.build(), b.expected.toMap(), intent)
    }

    private const val PW = InputTypeBits.CLASS_TEXT or InputTypeBits.TEXT_VARIATION_PASSWORD
    private const val NUM = InputTypeBits.CLASS_NUMBER
    private val COUNTRIES = listOf("Bitte auswählen", "Deutschland", "Österreich", "Schweiz", "Italien")

    val all: List<CorpusForm> by lazy {
        form("IONOS Login", "login.ionos.de", FormIntent.UsernameStep) {
            web(Username, name = "identifier", id = "username", autocomplete = "username", label = "E-Mail-Adresse oder Kundennummer")
            button("Weiter")
        }
        form("Hetzner Accounts", "accounts.hetzner.com", FormIntent.Login) {
            web(Username, name = "_username", id = "_username", label = "Benutzername oder E-Mail")
            web(Password, type = "password", name = "_password", id = "_password", label = "Passwort")
            button("Login")
        }
        form("Check24", "kundenbereich.check24.de", FormIntent.Login) {
            web(Email, type = "email", name = "email", id = "cl_login", placeholder = "E-Mail-Adresse")
            web(Password, type = "password", name = "password", id = "cl_pw_login", placeholder = "Passwort")
            button("anmelden")
        }
        form("Kleinanzeigen", "www.kleinanzeigen.de", FormIntent.Login) {
            web(Email, type = "email", name = "loginMail", id = "login-email", label = "E-Mail")
            web(Password, type = "password", name = "password", id = "login-password", label = "Passwort")
            button("Einloggen")
        }
        form("Spotify", "accounts.spotify.com", FormIntent.Login) {
            web(Username, id = "login-username", autocomplete = "username", placeholder = "E-Mail-Adresse oder Benutzername")
            web(Password, type = "password", id = "login-password", autocomplete = "current-password", placeholder = "Passwort")
            button("Anmelden")
        }
        form("Atlassian", "id.atlassian.com", FormIntent.UsernameStep) {
            web(Email, type = "email", name = "username", id = "username", autocomplete = "username", placeholder = "Ihre E-Mail-Adresse eingeben")
            button("Weiter")
        }
        form("GitLab self-hosted", "git.jochenderrochen.de", FormIntent.Login) {
            web(Username, name = "user[login]", id = "user_login", autocomplete = "username", label = "Benutzername oder primäre E-Mail")
            web(Password, type = "password", name = "user[password]", id = "user_password", autocomplete = "current-password", label = "Passwort")
            button("Anmelden")
        }
        form("Grafana", "grafana.jochenderrochen.de", FormIntent.Login) {
            web(Username, name = "user", id = ":r0:", placeholder = "email or username", aria = "Username input field")
            web(Password, type = "password", name = "password", id = "current-password", placeholder = "password", aria = "Password input field")
            button("Log in")
        }
        form("Portainer", "docker.jochenderrochen.de", FormIntent.Login) {
            web(Username, name = "username", id = "username", placeholder = "Enter your username")
            web(Password, type = "password", name = "password", id = "password", placeholder = "Enter your password")
            button("Login")
        }
        form("ING Banking (Zugangsnummer)", "banking.ing.de", FormIntent.Login) {
            web(Username, name = "zugangsnummer", id = "zugangsnummer", label = "Zugangsnummer", inputmode = "numeric", maxLength = 10)
            web(Password, type = "password", name = "pin", id = "pin", label = "Internetbanking PIN")
            button("Anmelden")
        }
        form("Krankenkasse Online-Geschäftsstelle", "meine.tk.de", FormIntent.Login) {
            web(Username, name = "benutzerkennung", label = "Benutzerkennung")
            web(Password, type = "password", name = "kennwort", label = "Kennwort")
            button("Anmelden")
        }
        form("Cloudflare 2FA", "dash.cloudflare.com", FormIntent.OneTimeCode) {
            web(Otp, name = "twofactor_token", id = "twofactor_token", autocomplete = "one-time-code", label = "Authentifizierungscode")
            button("Anmelden")
        }
        form("SMS-Bestätigung", "secure.example.de", FormIntent.OneTimeCode) {
            text("Wir haben Ihnen eine SMS geschickt")
            web(Otp, name = "smsCode", label = "SMS-Code", inputmode = "numeric", maxLength = 6)
            button("Bestätigen")
        }
        form("Booking.com Registrierung", "account.booking.com", FormIntent.Signup) {
            web(NewPassword, type = "password", name = "new_password", id = "new_password", autocomplete = "new-password", label = "Passwort")
            web(ConfirmPassword, type = "password", name = "confirmed_password", id = "confirmed_password", autocomplete = "new-password", label = "Passwort bestätigen")
            button("Konto erstellen")
        }
        form("Lieferando Registrierung", "www.lieferando.de", FormIntent.Signup) {
            web(FullName, name = "name", id = "name", autocomplete = "name", label = "Vor- und Nachname")
            web(Email, type = "email", name = "email", id = "email", autocomplete = "email", label = "E-Mail")
            web(NewPassword, type = "password", name = "password", id = "password", autocomplete = "new-password", label = "Passwort")
            button("Konto erstellen")
        }
        form("Mobile App Registrierung", null, FormIntent.Signup) {
            app(Username, idEntry = "username", hint = "Benutzername")
            app(Email, idEntry = "email", hint = "E-Mail")
            app(NewPassword, idEntry = "password", hint = "Neues Passwort", inputType = PW)
            app(ConfirmPassword, idEntry = "password_repeat", hint = "Passwort wiederholen", inputType = PW)
        }
        form("Rewe Lieferadresse", "shop.rewe.de", FormIntent.Identity) {
            select(Title, name = "salutation", label = "Anrede", options = listOf("Bitte wählen", "Frau", "Herr", "Ohne Anrede"))
            web(FirstName, name = "firstName", label = "Vorname")
            web(LastName, name = "lastName", label = "Nachname")
            web(Street, name = "streetName", label = "Straße")
            web(HouseNumber, name = "houseNumber", label = "Nr.")
            web(PostalCode, name = "zipCode", label = "PLZ", inputmode = "numeric", maxLength = 5)
            web(City, name = "city", label = "Ort")
            web(Phone, type = "tel", name = "phone", label = "Telefonnummer für Rückfragen")
        }
        form("Lidl Kundenkonto Adresse", "www.lidl.de", FormIntent.Identity) {
            web(Street, name = "street", id = "street", label = "Straße", autocomplete = "address-line1")
            web(HouseNumber, name = "streetNumber", id = "streetNumber", label = "Hausnr.")
            web(AddressLine2, name = "addressAddition", id = "addressAddition", label = "Adresszusatz (optional)")
            web(PostalCode, name = "postalCode", id = "postalCode", label = "PLZ", autocomplete = "postal-code")
            web(City, name = "city", id = "city", label = "Ort", autocomplete = "address-level2")
        }
        form("Hotel-Gastformular", "booking.hotel-example.de", FormIntent.Identity) {
            select(Title, name = "guest_salutation", label = "Anrede", options = listOf("Herr", "Frau", "Familie"))
            web(FirstName, name = "guest_firstname", label = "Vorname")
            web(LastName, name = "guest_lastname", label = "Nachname")
            web(Address, name = "guest_street", label = "Straße, Nr.")
            web(PostalCode, name = "guest_zip", label = "PLZ")
            web(City, name = "guest_city", label = "Ort")
            select(Country, name = "guest_country", label = "Land", options = COUNTRIES)
            web(Email, type = "email", name = "guest_email", label = "E-Mail")
            web(Unknown, name = "guest_arrival", label = "Anreise", placeholder = "TT.MM.JJJJ")
            web(Unknown, name = "guest_remarks", label = "Bemerkungen")
        }
        form("Französischer Shop", "boutique.example.fr", FormIntent.Identity) {
            select(Title, name = "id_gender", label = "Civilité", options = listOf("M.", "Mme"))
            web(FirstName, name = "firstname", label = "Prénom")
            web(LastName, name = "lastname", label = "Nom")
            web(Address, name = "address1", label = "Adresse")
            web(AddressLine2, name = "address2", label = "Complément d'adresse")
            web(PostalCode, name = "postcode", label = "Code postal")
            web(City, name = "city", label = "Ville")
            web(Phone, type = "tel", name = "phone", label = "Téléphone")
        }
        form("Spanischer Shop", "tienda.example.es", FormIntent.Identity) {
            web(FirstName, name = "nombre", label = "Nombre")
            web(LastName, name = "apellidos", label = "Apellidos")
            web(Address, name = "direccion", label = "Dirección")
            web(PostalCode, name = "codigo_postal", label = "Código postal")
            web(City, name = "ciudad", label = "Ciudad")
            web(State, name = "provincia", label = "Provincia")
            web(Phone, type = "tel", name = "telefono", label = "Teléfono")
        }
        form("Italienischer Shop", "negozio.example.it", FormIntent.Identity) {
            web(FirstName, name = "nome", label = "Nome")
            web(LastName, name = "cognome", label = "Cognome")
            web(Address, name = "indirizzo", label = "Indirizzo")
            web(PostalCode, name = "cap", label = "CAP")
            web(City, name = "citta", label = "Città")
            web(State, name = "provincia", label = "Provincia")
        }
        form("Niederländischer Shop", "winkel.example.nl", FormIntent.Identity) {
            web(PostalCode, name = "postcode", label = "Postcode")
            web(HouseNumber, name = "huisnummer", label = "Huisnummer")
            web(AddressLine2, name = "toevoeging", label = "Toevoeging")
            web(Street, name = "straat", label = "Straat")
            web(City, name = "plaats", label = "Plaats")
        }
        form("Polnischer Shop", "sklep.example.pl", FormIntent.Identity) {
            web(FirstName, name = "imie", label = "Imię")
            web(LastName, name = "nazwisko", label = "Nazwisko")
            web(Address, name = "ulica", label = "Ulica i numer")
            web(PostalCode, name = "kod_pocztowy", label = "Kod pocztowy")
            web(City, name = "miasto", label = "Miasto")
        }
        form("Firmenkunde", "b2b.example.de", FormIntent.Identity) {
            web(Organization, name = "company_name", label = "Firmenname")
            web(Unknown, name = "vat_id", label = "USt-IdNr.")
            web(FullName, name = "contact_person", label = "Ansprechpartner")
            web(Email, type = "email", name = "contact_email", label = "E-Mail")
        }
        form("Mollie Kreditkarte", "pay.mollie.com", FormIntent.Card) {
            web(CardHolder, name = "cardHolder", id = "cardHolder", autocomplete = "cc-name", label = "Karteninhaber")
            web(CardNumber, name = "cardNumber", id = "cardNumber", autocomplete = "cc-number", label = "Kartennummer")
            web(CardExpiry, name = "expiryDate", id = "expiryDate", autocomplete = "cc-exp", label = "Gültig bis", placeholder = "MM/JJ")
            web(CardCvv, name = "verificationCode", id = "verificationCode", autocomplete = "cc-csc", label = "CVC")
        }
        form("Buchungsstrecke ohne autocomplete", "flug.example.de", FormIntent.Card) {
            web(CardNumber, name = "creditCardNo", label = "Kreditkarten-Nr.")
            select(CardExpiryMonth, name = "validMonth", label = "Gültig bis Monat", options = (1..12).map { it.toString() })
            select(CardExpiryYear, name = "validYear", label = "Gültig bis Jahr", options = (2026..2035).map { it.toString() })
            web(CardCvv, name = "checkDigits", label = "Prüfziffer", maxLength = 4)
            web(CardHolder, name = "cardOwner", label = "Name des Karteninhabers")
        }
        form("Kartenformular in App", null, FormIntent.Card) {
            app(CardNumber, idEntry = "cardNumberInput", hint = "Kartennummer", inputType = NUM)
            app(CardExpiryMonth, idEntry = "expiryMonthInput", hint = "MM", inputType = NUM, maxLength = 2)
            app(CardExpiryYear, idEntry = "expiryYearInput", hint = "JJ", inputType = NUM, maxLength = 2)
            app(CardCvv, idEntry = "cvcInput", hint = "CVC", inputType = NUM, maxLength = 3)
        }
        form("Suche im Shop", "shop.example.de", null) {
            web(Unknown, name = "searchTerm", placeholder = "Wonach suchst du?")
        }
        form("Gutschein im Checkout", "shop.example.de", null) {
            web(Unknown, name = "promoCode", label = "Aktionscode")
        }
        form("Filialfinder", "www.example.de", null) {
            web(Unknown, name = "storeLocatorQuery", placeholder = "PLZ oder Ort für Filialsuche")
        }
        forms.toList()
    }
}
