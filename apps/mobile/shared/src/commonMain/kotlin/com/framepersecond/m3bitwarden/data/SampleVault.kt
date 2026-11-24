package com.framepersecond.m3bitwarden.data

/**
 * Demo content so the UI can be explored without a server. Every value here is made up.
 * Replace with the real vault source once the UI is wired to the Bitwarden SDK.
 */
object SampleVault {
    val folders = listOf("Arbeit", "Finanzen", "Privat", "Server")

    val items: List<VaultItem> = listOf(
        VaultItem(
            id = "1",
            name = "GitHub",
            type = ItemType.Login,
            folder = "Arbeit",
            favorite = true,
            login = LoginData(
                username = "du@example.com",
                password = "q7#Vt9!pLm2@xR4s",
                uri = "https://github.com",
                totpSecret = "JBSWY3DPEHPK3PXP",
            ),
            fields = listOf(
                CustomField("Recovery-E-Mail", "backup@example.com"),
                CustomField("PIN", "4711", CustomFieldType.Hidden),
            ),
            passwordHistory = listOf(PasswordHistoryEntry("altesPasswort123", "Vor 3 Monaten")),
            notes = "Recovery-Codes liegen in der Notiz „Wiederherstellung“.",
            updatedLabel = "Heute geändert",
        ),
        VaultItem(
            id = "2",
            name = "Google",
            type = ItemType.Login,
            folder = "Privat",
            favorite = true,
            login = LoginData(
                username = "du@gmail.example",
                password = "Kieselstein-Morgen-Fenster-42",
                uri = "https://accounts.google.com",
                totpSecret = "KRSXG5CTMVRXEZLU",
            ),
            updatedLabel = "Vor 3 Tagen geändert",
        ),
        VaultItem(
            id = "3",
            name = "Visa Kreditkarte",
            type = ItemType.Card,
            folder = "Finanzen",
            favorite = true,
            card = CardData(
                cardholder = "Alex Beispiel",
                brand = "Visa",
                number = "4111111111114242",
                expMonth = "08",
                expYear = "2029",
                code = "317",
            ),
            updatedLabel = "Vor 2 Wochen geändert",
        ),
        VaultItem(
            id = "4",
            name = "NAS Verwaltung",
            type = ItemType.Login,
            folder = "Server",
            login = LoginData(
                username = "admin",
                password = "tH3-m0unta1n!s-c4lling",
                uri = "https://nas.home.example",
            ),
            updatedLabel = "Vor 1 Monat geändert",
        ),
        VaultItem(
            id = "5",
            name = "Homelab Deploy-Key",
            type = ItemType.SshKey,
            folder = "Server",
            sshKey = SshKeyData(
                publicKey = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIGx8w2Zr4kQe9pJ7vT0sYbN3mR5cFhL2uWqA1dE6oKjP deploy@homelab",
                fingerprint = "SHA256:3f9Kp2LmQx7Rt4Vb8Nc1Yh6Ws0Za5Ue2Jd9Gi4Ho7Fk",
                privateKey = "-----BEGIN OPENSSH PRIVATE KEY-----\nb3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAAAMwAAAAtzc2gtZW\n-----END OPENSSH PRIVATE KEY-----",
            ),
            updatedLabel = "Vor 2 Monaten geändert",
        ),
        VaultItem(
            id = "6",
            name = "Persönliche Daten",
            type = ItemType.Identity,
            folder = "Privat",
            identity = IdentityData(
                firstName = "Alex",
                lastName = "Beispiel",
                email = "du@example.com",
                phone = "+49 151 23456789",
                address1 = "Musterstraße 12",
                postalCode = "80331",
                city = "München",
                country = "Deutschland",
                company = "Beispiel GmbH",
            ),
            updatedLabel = "Vor 5 Monaten geändert",
        ),
        VaultItem(
            id = "7",
            name = "WLAN Zuhause",
            type = ItemType.Note,
            folder = "Privat",
            notes = "SSID: Beispielnetz\nPasswort: Sonnenblume-Treppe-Kompass\nGast-WLAN: Beispielgast",
            updatedLabel = "Vor 6 Monaten geändert",
        ),
        VaultItem(
            id = "8",
            name = "Netflix",
            type = ItemType.Login,
            folder = "Privat",
            login = LoginData(
                username = "du@example.com",
                password = "bR8%nQ2&wE5^",
                uri = "https://www.netflix.com",
            ),
            updatedLabel = "Vor 1 Jahr geändert",
        ),
        VaultItem(
            id = "9",
            name = "Deutsche Bahn",
            type = ItemType.Login,
            folder = "Privat",
            login = LoginData(
                username = "alex.beispiel",
                password = "Zugfahrt2019",
                uri = "https://www.bahn.de",
            ),
            notes = "BahnCard 25, gültig bis März.",
            updatedLabel = "Vor 2 Jahren geändert",
        ),
        VaultItem(
            id = "10",
            name = "Sparkasse Online-Banking",
            type = ItemType.Login,
            folder = "Finanzen",
            favorite = true,
            login = LoginData(
                username = "12345678",
                password = "vN4!kT8#zR1@mW6\$",
                uri = "https://www.sparkasse.de",
            ),
            updatedLabel = "Vor 4 Monaten geändert",
        ),
        VaultItem(
            id = "11",
            name = "Wiederherstellung",
            type = ItemType.Note,
            folder = "Arbeit",
            notes = "GitHub Recovery-Codes:\n4f2a-9c1e\n7b3d-2e8f\na1c9-5d4b",
            updatedLabel = "Vor 3 Wochen geändert",
        ),
        VaultItem(
            id = "12",
            name = "Steam",
            type = ItemType.Login,
            login = LoginData(
                username = "alex_spielt",
                password = "Wolke-Laterne-Bergpfad-7",
                uri = "https://store.steampowered.com",
                totpSecret = "MFRGGZDFMZTWQ2LK",
            ),
            updatedLabel = "Vor 8 Monaten geändert",
        ),
        VaultItem(
            id = "13",
            name = "Mastercard Debit",
            type = ItemType.Card,
            folder = "Finanzen",
            card = CardData(
                cardholder = "Alex Beispiel",
                brand = "Mastercard",
                number = "5555555555551881",
                expMonth = "11",
                expYear = "2027",
                code = "842",
            ),
            updatedLabel = "Vor 1 Jahr geändert",
        ),
    )
}
