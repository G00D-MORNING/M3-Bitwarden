package com.framepersecond.m3bitwarden.autofill.classify

import com.framepersecond.m3bitwarden.autofill.model.FieldType

/**
 * Multilingual keywords per field type. All entries are already in [com.framepersecond.m3bitwarden.autofill.text.TextNorm.squash]
 * form (lower case, umlauts transliterated, no separators).
 *
 * Proton Pass ships English, Spanish and French only and recognises a password field
 * mainly by the word "password". This list covers the languages most of our users meet
 * (DE, EN, FR, ES, IT, NL, PT, PL, TR, the Nordics, CZ, RU/UK, JA, ZH, KO).
 */
internal object Lexicon {

    data class Entry(val type: FieldType, val keywords: Set<String>, val denied: Set<String> = emptySet())

    /** Matching any of these in a field's own metadata means "not a credential field". */
    val globalDenied: Set<String> = setOf(
        "search", "suche", "suchen", "recherche", "buscar", "busqueda", "cerca", "zoeken", "pesquisa", "szukaj", "ara",
        "query", "filter", "coupon", "gutschein", "voucher", "promo", "rabatt", "discount", "giftcard",
        "captcha", "newsletter", "comment", "kommentar", "message", "nachricht", "chat", "reply", "antwort",
        "subject", "betreff", "composer", "tweet", "toot", "mozac", "quantity", "menge", "anzahl",
    )

    val password = setOf(
        "password", "passwort", "kennwort", "passwd", "pwd", "passcode", "pass", "pin", "geheimzahl",
        "motdepasse", "mdp", "contrasena", "clave", "senha", "wachtwoord", "haslo", "parola", "sifre",
        "losenord", "adgangskode", "passord", "salasana", "heslo", "jelszo", "parool",
        "пароль", "パスワード", "密码", "密碼", "비밀번호", "kata sandi", "katasandi",
    )

    val newPasswordMarkers = setOf(
        "new", "neu", "neues", "neuen", "nouveau", "nouvelle", "nuevo", "nueva", "nuovo", "nuova", "novo", "nova", "nieuw",
        "create", "erstellen", "festlegen", "choose", "waehlen", "set", "setup", "register", "signup", "registrieren",
    )
    val confirmPasswordMarkers = setOf(
        "confirm", "confirmation", "bestaetig", "bestaetigen", "bestaetigung", "repeat", "wiederhol", "wiederholen",
        "retype", "reenter", "again", "erneut", "nochmal", "verify", "verification", "confirmar", "confermare",
        "confirmez", "herhaal", "bevestig", "powtorz", "tekrar", "повтор",
    )
    val currentPasswordMarkers = setOf("current", "old", "alt", "altes", "aktuell", "aktuelles", "bisherig", "actuel", "actual", "attuale", "huidig")

    val entries: List<Entry> = listOf(
        Entry(
            FieldType.Email,
            setOf(
                "email", "emailaddress", "emailadresse", "mail", "courriel", "correo", "correoelectronico",
                "eposta", "epost", "sahkoposti", "почта", "メール", "邮箱", "电子邮件", "이메일",
            ),
        ),
        Entry(
            FieldType.Username,
            setOf(
                "username", "user", "userid", "login", "loginname", "loginid", "account", "accountname", "identifier",
                "benutzer", "benutzername", "benutzerkennung", "nutzername", "anmeldename", "kennung", "zugangsname",
                "kundennummer", "mitgliedsnummer", "vertragsnummer", "anmeldung",
                "identifiant", "utilisateur", "nomdutilisateur", "usuario", "nombredeusuario", "utente", "nomeutente",
                "gebruikersnaam", "uzytkownik", "nazwauzytkownika", "kullaniciadi", "anvandarnamn", "brugernavn",
                "brukernavn", "kayttajatunnus", "uzivatel", "логин", "ユーザー名", "用户名", "아이디", "사용자",
            ),
            denied = setOf("firstname", "lastname", "vorname", "nachname", "connectionsroletag"),
        ),
        Entry(
            FieldType.Otp,
            setOf(
                "otp", "totp", "mfa", "2fa", "tfa", "onetimecode", "onetimepassword", "verificationcode", "authcode",
                "authenticationcode", "securitycodesms", "smscode", "code", "token", "tan", "mtan", "pushtan",
                "bestaetigungscode", "sicherheitscode2fa", "verifizierungscode", "einmalcode", "einmalpasswort",
                "codedeverification", "codigo", "codice", "verificatiecode", "kod", "dogrulama", "код", "認証コード", "验证码", "인증번호",
            ),
            denied = setOf(
                "postal", "zip", "plz", "postleitzahl", "promo", "coupon", "gutschein", "voucher", "country", "iban",
                "bic", "swift", "sortcode", "area", "discount", "rabatt", "referral", "invite", "einladung", "qr",
                "cvc", "cvv", "securitycode", "barcode", "colorcode", "language",
            ),
        ),
        Entry(
            FieldType.CardNumber,
            setOf(
                "cardnumber", "cardnum", "ccnumber", "ccnum", "creditcard", "creditcardnumber", "kartennummer",
                "kreditkartennummer", "kreditkarte", "numerocarte", "numerodetarjeta", "numerocarta", "kaartnummer",
                "numerkarty", "kartnumarasi", "номеркарты",
            ),
        ),
        Entry(
            FieldType.CardCvv,
            setOf("cvc", "cvv", "cvn", "cvv2", "cvc2", "csc", "securitycode", "kartenpruefnummer", "pruefnummer", "sicherheitscode", "cryptogramme", "codigodeseguridad"),
        ),
        Entry(
            FieldType.CardHolder,
            setOf("cardholder", "cardname", "nameoncard", "holdername", "ccname", "karteninhaber", "inhaber", "titulaire", "titular", "titolare", "kaarthouder"),
        ),
        Entry(FieldType.CardExpiry, setOf("expiry", "expiration", "expirationdate", "expdate", "mmyy", "mmjj", "mmaa", "ablaufdatum", "gueltigbis", "validthru", "validuntil", "vervaldatum")),
        Entry(FieldType.CardExpiryMonth, setOf("expmonth", "expirymonth", "expirationmonth", "ablaufmonat", "cardmonth", "moisexpiration", "mesexpiracion")),
        Entry(FieldType.CardExpiryYear, setOf("expyear", "expiryyear", "expirationyear", "ablaufjahr", "cardyear", "anneeexpiration", "anoexpiracion")),
        Entry(FieldType.FirstName, setOf("firstname", "givenname", "forename", "vorname", "prenom", "nombre", "nome", "voornaam", "imie")),
        Entry(FieldType.LastName, setOf("lastname", "surname", "familyname", "nachname", "familienname", "nomdefamille", "apellido", "cognome", "achternaam", "nazwisko", "soyad")),
        Entry(FieldType.FullName, setOf("fullname", "yourname", "name", "vollstaendigername", "nomcomplet", "nombrecompleto", "nomecompleto")),
        Entry(FieldType.Phone, setOf("phone", "telephone", "tel", "mobile", "mobil", "handy", "handynummer", "telefon", "telefonnummer", "rufnummer", "cellphone", "telefono", "telefone", "telefoon")),
        Entry(FieldType.PostalCode, setOf("postalcode", "postcode", "zip", "zipcode", "plz", "postleitzahl", "codepostal", "codigopostal", "cap", "postcodenl")),
        Entry(FieldType.City, setOf("city", "town", "ort", "stadt", "wohnort", "ville", "ciudad", "citta", "plaats", "miasto"), denied = setOf("street", "strasse")),
        Entry(FieldType.Country, setOf("country", "land", "staat", "pays", "pais", "paese", "kraj")),
        Entry(FieldType.Address, setOf("address", "street", "strasse", "anschrift", "adresse", "hausnummer", "direccion", "calle", "indirizzo", "via", "straat"), denied = setOf("email", "mail", "ipaddress", "line2", "zeile2")),
        Entry(FieldType.Organization, setOf("organization", "organisation", "company", "firma", "unternehmen", "business", "entreprise", "empresa", "azienda", "bedrijf")),
    )

    /** Texts on buttons and headings that reveal what a form is for. */
    val signupWords = setOf(
        "signup", "register", "registrieren", "registrierung", "createaccount", "kontoerstellen", "kontoanlegen",
        "join", "jetztregistrieren", "inscription", "sinscrire", "registrarse", "crearcuenta", "registrati",
        "registreren", "zarejestruj", "kayitol",
    )
    val loginWords = setOf(
        "login", "signin", "logon", "anmelden", "einloggen", "anmeldung", "connexion", "seconnecter", "iniciarsesion",
        "entrar", "accedi", "inloggen", "zaloguj", "girisyap", "logga", "войти",
    )
    val nextWords = setOf("next", "weiter", "continue", "fortfahren", "suivant", "siguiente", "avanti", "volgende", "dalej", "devam", "далее")
    val changePasswordWords = setOf("changepassword", "passwortaendern", "kennwortaendern", "updatepassword", "resetpassword", "passwortzuruecksetzen")
}
