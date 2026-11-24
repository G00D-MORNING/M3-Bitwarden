package com.framepersecond.m3bitwarden.autofill.classify

import com.framepersecond.m3bitwarden.autofill.model.FieldType
import com.framepersecond.m3bitwarden.autofill.text.TextNorm

/**
 * Multilingual keywords per field type. All entries are already in [com.framepersecond.m3bitwarden.autofill.text.TextNorm.squash]
 * form (lower case, umlauts transliterated, no separators).
 *
 * Proton Pass ships English, Spanish and French only and recognises a password field
 * mainly by the word "password". This list covers the languages most of our users meet
 * (DE, EN, FR, ES, IT, NL, PT, PL, TR, the Nordics, CZ, RU/UK, JA, ZH, KO).
 */
internal object Lexicon {

    /**
     * Keywords are normalised with the same [TextNorm.squash] as page text, so scripts with
     * combining marks (Devanagari, Malayalam, Thai) and accented spellings match reliably.
     */
    class Entry(val type: FieldType, keywords: Set<String>, denied: Set<String> = emptySet()) {
        val keywords: Set<String> = keywords.map(TextNorm::squash).filter { it.isNotEmpty() }.toSet()
        val denied: Set<String> = denied.map(TextNorm::squash).filter { it.isNotEmpty() }.toSet()
    }

    /** Matching any of these in a field's own metadata means "not a credential field". */
    val globalDenied: Set<String> = setOf(
        "search", "suche", "suchen", "recherche", "buscar", "busqueda", "cerca", "zoeken", "pesquisa", "szukaj", "ara",
        "query", "filter", "coupon", "gutschein", "voucher", "promo", "rabatt", "discount", "giftcard",
        "captcha", "newsletter", "comment", "kommentar", "message", "nachricht", "chat", "reply", "antwort",
        "subject", "betreff", "composer", "tweet", "toot", "mozac", "quantity", "menge", "anzahl",
        "iban", "bic", "swift", "sendungsnummer", "trackingnumber", "piececode", "tracking", "realm",
        "geburtsdatum", "birthday", "birthdate", "dateofbirth", "dob", "geburtstag",
        // Phone extensions stay empty: we only store whole numbers.
        "ext", "extension", "durchwahl", "nebenstelle", "phoneext", "telext", "poste",
        "savedcard", "savedcreditcard", "gespeichertekarte", "passwordhint", "passworthinweis", "giftfrom", "giftmessage",
        "middlename", "middleinitial", "zweitervorname", "namesuffix", "honorificsuffix", "namenszusatz",
        // Honeypots: fields hidden from people to catch bots. Filling them flags the user as a bot.
        "ignorethisfield", "leavethisfieldblank", "leavethisempty", "leaveblank", "dontfillthis", "donotfill",
        "nichtausfuellen", "feldleerlassen", "honeypot", "bottrap",
    )

    /** Option texts that give a select away. */
    val salutationOptions = setOf("herr", "frau", "divers", "mr", "mrs", "ms", "miss", "mx", "monsieur", "madame", "senor", "senora", "keineangabe")
    val countryOptions = setOf(
        "deutschland", "germany", "oesterreich", "austria", "schweiz", "switzerland", "niederlande", "netherlands", "frankreich",
        "france", "italien", "italy", "spanien", "spain", "belgien", "belgium", "unitedstates", "unitedkingdom", "polen", "poland",
    )
    val stateOptions = setOf(
        "bayern", "berlin", "badenwuerttemberg", "nordrheinwestfalen", "hessen", "niedersachsen", "sachsen", "hamburg", "bremen",
        "california", "newyork", "texas", "florida", "wien", "tirol", "zuerich", "bern",
    )
    val monthOptions = setOf("januar", "january", "februar", "february", "maerz", "march", "april", "mai", "may", "juni", "june")

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
                "eposta", "epost", "sahkoposti", "почта", "メール", "邮箱", "电子邮件", "이메일", "メールアドレス", "電郵", "電子郵件", "电子邮箱", "почты", "электроннаяпочта", "электронн", "ईमेल", "ഇമെയില്‍", "이메일주소",
            ),
        ),
        Entry(
            FieldType.Username,
            setOf(
                "username", "user", "userid", "login", "loginname", "loginid", "account", "accountname", "identifier",
                "benutzer", "benutzername", "benutzerkennung", "nutzername", "anmeldename", "kennung", "zugangsname",
                "kundennummer", "mitgliedsnummer", "vertragsnummer", "anmeldung", "zugangsnummer", "zugangskennung", "teilnehmernummer",
                "legitimationsid", "leginummer", "anmeldekennung", "onlinebankingid", "kontoname", "accountid", "loginemail",
                "identifiant", "utilisateur", "nomdutilisateur", "usuario", "nombredeusuario", "utente", "nomeutente",
                "gebruikersnaam", "uzytkownik", "nazwauzytkownika", "kullaniciadi", "anvandarnamn", "brugernavn",
                "brukernavn", "kayttajatunnus", "uzivatel", "логин", "ユーザー名", "用户名", "아이디", "사용자",
            ),
            denied = setOf("firstname", "lastname", "vorname", "nachname", "connectionsroletag", "accountholder", "kontoinhaber", "accountnumber", "kontonummer", "realm"),
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
                "cvc", "cvv", "securitycode", "barcode", "colorcode", "language", "pocztowy", "postale", "postal", "pocztow",
                "idtoken", "csrftoken", "xsrftoken", "authenticitytoken", "requesttoken", "formtoken", "sessiontoken",
            ),
        ),
        Entry(
            FieldType.CardNumber,
            setOf(
                "cardnumber", "cardnum", "ccnumber", "ccnum", "creditcard", "creditcardnumber", "kartennummer",
                "kreditkartennummer", "kreditkarte", "numerocarte", "numerodetarjeta", "numerocarta", "kaartnummer",
                "numerkarty", "kartnumarasi", "номеркарты", "encryptedcardnumber", "kreditkartennr", "creditcardno", "cardno",
                "numerodelatarjeta", "numerodecarte", "numerodicarta", "numerodocartao", "カード番号", "卡号", "卡號", "카드번호", "信用卡号码", "信用卡號碼", "信用卡卡號", "номеркредитнойкарты", "クレジットカード番号",
            ),
            denied = setOf(
                "type", "typ", "brand", "kartentyp", "kartenart", "idcard", "giftcard", "geschenkkarte", "kundenkarte", "loyalty",
                "membership", "mitglied", "payback", "bonuscard", "bahncard", "ausweis", "library", "saved", "gespeichert",
            ),
        ),
        Entry(
            FieldType.CardCvv,
            setOf("cvc", "cvv", "cvn", "cvv2", "cvc2", "csc", "cid", "securitycode", "kartenpruefnummer", "pruefnummer", "sicherheitscode", "cryptogramme", "codigodeseguridad", "cardcode", "cardverification"),
        ),
        Entry(
            FieldType.CardHolder,
            setOf("cardholder", "cardname", "nameoncard", "holdername", "ccname", "karteninhaber", "nameaufderkarte", "billingname", "titulaire", "titular", "titolare", "kaarthouder", "名義", "持卡人", "владелецкарты", "имянакарте"),
        ),
        Entry(
            FieldType.CardExpiry,
            setOf(
                "expiry", "expiration", "expirationdate", "expdate", "mmyy", "mmjj", "mmaa", "ablaufdatum", "gueltigbis", "gueltigkeit",
                "validthru", "validuntil", "vervaldatum", "fechadeexpiracion", "fechadevencimiento", "vencimiento", "caducidad",
                "datedexpiration", "expire", "scadenza", "datadiscadenza", "validade", "datadevalidade", "有効期限", "有效期", "유효기간",
                "만료", "срокдействия", "sonkullanmatarihi",
            ),
        ),
        Entry(FieldType.CardBrand, setOf("cardtype", "cctype", "creditcardtype", "kartentyp", "kartenart", "kreditkartentyp", "cardbrand", "typedecarte", "tipodetarjeta", "paymentcardtype", "cardnetwork")),
        Entry(FieldType.CardExpiryMonth, setOf("expmonth", "expirymonth", "expirationmonth", "ablaufmonat", "cardmonth", "ccmonth", "moisexpiration", "mesexpiracion", "mm")),
        Entry(FieldType.CardExpiryYear, setOf("expyear", "expiryyear", "expirationyear", "ablaufjahr", "cardyear", "ccyear", "anneeexpiration", "anoexpiracion", "yy", "jj", "yyyy", "jjjj")),
        Entry(FieldType.FirstName, setOf("firstname", "givenname", "forename", "vorname", "prenom", "nombre", "nome", "voornaam", "imie", "名", "名字", "имя", "이름", "fornavn", "fornamn", "etunimi", "jmeno", "ad", "keresztnev")),
        Entry(FieldType.LastName, setOf("lastname", "surname", "familyname", "nachname", "familienname", "nomdefamille", "apellido", "apellidos", "apelido", "apelidos", "cognome", "nom", "姓", "姓氏", "фамилия", "उपनाम", "성", "achternaam", "nazwisko", "soyad", "soyadi", "efternavn", "efternamn", "sukunimi", "prijmeni", "vezeteknev", "sobrenome")),
        Entry(
            FieldType.FullName,
            setOf(
                "fullname", "yourname", "name", "vollstaendigername", "nomcomplet", "nombrecompleto", "nomecompleto", "accountholder", "kontoinhaber",
                "ihrname", "author", "ansprechpartner", "contactperson", "kontaktperson", "contactname", "recipient", "empfaenger", "naam", "nazwa",
                "vorundnachname", "namevorname", "nachnamevorname", "vornamename", "vornameundname", "vornameundnachname", "firstandlastname", "displayname", "anzeigename",
            ),
            denied = setOf("username", "benutzername", "nutzername", "kontoname", "accountname", "loginname", "anmeldename", "domainname", "hostname", "filename", "dateiname", "projectname"),
        ),
        Entry(FieldType.Phone, setOf("phone", "telephone", "tel", "mobile", "mobil", "handy", "handynummer", "telefon", "telefonnummer", "rufnummer", "mobilnummer", "cellphone", "telefono", "telefone", "telefoon", "phonenumber", "telefonnummer", "puhelin", "telefonu", "mobilni", "contactnumber", "電話番号", "電話", "电话", "手机", "携帯", "телефон", "мобильный", "मोबाइल", "फोन", "전화번호", "휴대폰")),
        Entry(FieldType.PhoneCountryCode, setOf("laendervorwahl", "landesvorwahl", "phonecountrycode", "dialcode", "callingcode", "countrycallingcode", "telcountry", "phoneprefix", "telefonvorwahlland")),
        Entry(FieldType.PhoneAreaCode, setOf("vorwahl", "ortsvorwahl", "areacode", "citycode", "phonearea", "netzvorwahl", "indicatif", "prefijo"), denied = setOf("laendervorwahl", "landesvorwahl", "country")),
        Entry(FieldType.PostalCode, setOf("postalcode", "postcode", "zip", "zipcode", "plz", "postleitzahl", "codepostal", "codigopostal", "cap", "postcodenl", "zipcity", "kodpocztowy", "postnummer", "postnr", "postinumero", "psc", "postakodu", "codicepostale", "iranyitoszam", "cep", "郵便番号", "邮编", "郵遞區號", "邮政编码", "почтовыйиндекс", "индекс", "우편번호", "pincode", "पिनकोड")),
        Entry(FieldType.City, setOf("city", "town", "ort", "stadt", "wohnort", "ville", "ciudad", "citta", "plaats", "woonplaats", "miasto", "miejscowosc", "locality", "gemeinde", "localidad", "localita", "cidade", "stad", "by", "kaupunki", "mesto", "obec", "sehir", "varos",
                "poblacion", "municipio", "市区町村", "市", "城市", "город", "населенныйпункт", "ilce", "시군구", "도시", "शहर"), denied = setOf("street", "strasse", "geburtsort")),
        Entry(FieldType.State, setOf("state", "bundesland", "region", "province", "provinz", "kanton", "canton", "county", "regionid", "countrystate", "provincia", "landkreis", "wojewodztwo", "provincie", "estado", "il", "lan", "都道府県", "省", "область", "регион", "край", "штат", "시도", "राज्य")),
        Entry(FieldType.Country, setOf("country", "land", "staat", "pays", "pais", "paese", "kraj", "countrycode", "countryid", "ulke", "maa", "orszag", "国", "国家", "страна", "국가", "देश"), denied = setOf("state", "bundesland", "landkreis", "telefon", "phone", "vorwahl", "countrystate")),
        Entry(
            FieldType.AddressLine2,
            setOf(
                "address2", "addressline2", "adresszeile2", "line2", "zeile2", "adresszusatz", "zusatz", "addition", "addresssuffix",
                "apartment", "apt", "suite", "wohnung", "etage", "stockwerk", "complement", "complemento", "appartement", "unit",
                "toevoeging", "huisnummertoevoeging", "piso", "puerta", "interno", "scala", "lokal", "mieszkanie", "dairenumarasi", "lagenhet", "co", "careof", "street2", "street3", "address3", "line3", "addressline3", "indirizzo2", "adresse2", "direccion2", "住所2", "地址2",
            ),
        ),
        Entry(
            FieldType.HouseNumber,
            setOf(
                "hausnummer", "hausnr", "housenumber", "housenr", "housenum", "streetnumber", "buildingnumber", "huisnummer", "huisnr",
                "numerocivico", "numerorue", "nr", "numerodomu", "nrdomu", "cislopopisne", "husnummer", "gatunummer", "binano", "kapino",
            ),
            denied = setOf("strasse", "street", "telefon", "phone", "kunden", "kartennummer", "cardnumber", "steuer", "vat", "ustid", "order", "bestell"),
        ),
        Entry(
            FieldType.Address,
            setOf(
                "address", "addressline1", "address1", "street", "strasse", "streetaddress", "anschrift", "adresse", "direccion", "calle",
                "indirizzo", "via", "straat", "straatnaam", "rue", "ulica", "adres", "endereco", "morada", "rua", "gatuadress", "gade", "gate",
                "katuosoite", "ulice", "sokak", "cadde", "utca", "住所", "番地", "地址", "街道地址", "адрес", "улица", "주소", "पता",
            ),
            denied = setOf("email", "mail", "ipaddress", "line2", "zeile2", "address2", "adresszusatz", "macaddress", "walletaddress", "street2", "street3", "address3", "line3", "indirizzo2"),
        ),
        Entry(FieldType.Organization, setOf("organization", "organisation", "company", "firma", "firmenname", "unternehmen", "business", "entreprise", "empresa", "azienda", "bedrijf", "societe", "ragionesociale", "会社", "会社名", "公司", "компания", "названиекомпании", "организация", "회사")),
        Entry(FieldType.Title, setOf("anrede", "salutation", "honorific", "honorificprefix", "civilite", "tratamiento", "aanhef", "salutationid")),
    )

    /** Texts on buttons and headings that reveal what a form is for. */
    val signupWords = setOf(
        "signup", "register", "registrieren", "registrierung", "createaccount", "kontoerstellen", "kontoanlegen",
        "join", "jetztregistrieren", "inscription", "sinscrire", "registrarse", "crearcuenta", "registrati",
        "registreren", "zarejestruj", "kayitol", "beitreten", "kontoeroeffnen", "createyouraccount", "registerfree",
        "kostenlosregistrieren", "jetztkontoerstellen", "creeruncompte", "crearunacuenta",
    )
    val loginWords = setOf(
        "login", "signin", "logon", "anmelden", "einloggen", "anmeldung", "connexion", "seconnecter", "iniciarsesion",
        "entrar", "accedi", "inloggen", "zaloguj", "girisyap", "logga", "войти",
    )
    val nextWords = setOf("next", "weiter", "continue", "fortfahren", "suivant", "siguiente", "avanti", "volgende", "dalej", "devam", "далее")
    val changePasswordWords = setOf(
        "changepassword", "passwortaendern", "kennwortaendern", "updatepassword", "resetpassword", "passwortzuruecksetzen",
        "kennwortzuruecksetzen", "neuespasswortfestlegen", "setnewpassword", "choosenewpassword", "passwortneusetzen",
    )
}
