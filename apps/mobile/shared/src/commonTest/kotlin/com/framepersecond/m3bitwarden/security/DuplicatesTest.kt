package com.framepersecond.m3bitwarden.security

import com.framepersecond.m3bitwarden.data.CustomField
import com.framepersecond.m3bitwarden.data.CustomFieldType
import com.framepersecond.m3bitwarden.data.ItemType
import com.framepersecond.m3bitwarden.data.LoginData
import com.framepersecond.m3bitwarden.data.VaultItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DuplicatesTest {
    private fun login(id: String, user: String, password: String, vararg uris: String, notes: String = "", totp: String? = null, passkeys: Int = 0, revision: Long = 0) =
        VaultItem(
            id = id, name = "Item $id", type = ItemType.Login, notes = notes, revisionEpoch = revision,
            login = LoginData(username = user, password = password, totpSecret = totp, passkeys = passkeys).withUris(uris.toList()),
        )

    @Test
    fun sameUserPasswordAndSiteAreDuplicates() {
        val a = login("a", "anna@mail.de", "pw1", "https://www.example.com/login")
        val b = login("b", "Anna@mail.de ", "pw1", "example.com", notes = "note", totp = "JBSWY3DP")
        val groups = SecurityCheck.duplicates(listOf(a, b))
        assertEquals(1, groups.size)
        // The fuller item stays.
        assertEquals("b", groups[0].keeper.id)
        assertTrue(groups[0].mergeable)
    }

    @Test
    fun differentPasswordUserOrSiteAreNotDuplicates() {
        val base = login("a", "anna", "pw1", "https://example.com")
        assertTrue(SecurityCheck.duplicates(listOf(base, login("b", "anna", "pw2", "https://example.com"))).isEmpty())
        assertTrue(SecurityCheck.duplicates(listOf(base, login("c", "bob", "pw1", "https://example.com"))).isEmpty())
        assertTrue(SecurityCheck.duplicates(listOf(base, login("d", "anna", "pw1", "https://other.com"))).isEmpty())
        assertTrue(SecurityCheck.duplicates(listOf(base, login("e", "anna", "pw1", "https://example.com", "androidapp://com.example"))).isEmpty())
    }

    @Test
    fun mergeKeepsEverything() {
        val a = login("a", "anna", "pw1", "https://example.com", notes = "eins", passkeys = 1)
            .copy(fields = listOf(CustomField("PIN", "1234", CustomFieldType.Hidden)))
        val b = login("b", "anna", "pw1", "https://example.com", "https://www.example.com", notes = "zwei", totp = "JBSWY3DP")
            .copy(favorite = true, fields = listOf(CustomField("PIN", "1234", CustomFieldType.Hidden), CustomField("Kunde", "42", CustomFieldType.Text)))
        val group = SecurityCheck.duplicates(listOf(a, b)).single()
        assertEquals("a", group.keeper.id, "the one with the passkey stays")
        val merged = group.merged()
        assertEquals("a", merged.id)
        assertEquals("JBSWY3DP", merged.login?.totpSecret)
        assertEquals("eins\n\nzwei", merged.notes)
        assertEquals(listOf("PIN", "Kunde"), merged.fields.map { it.name })
        assertTrue(merged.favorite)
        assertEquals(2, merged.login?.allUris?.size)
    }

    @Test
    fun twoPasskeysOrSharedItemsAreNotMergedAutomatically() {
        val a = login("a", "anna", "pw1", "https://example.com", passkeys = 1)
        val b = login("b", "anna", "pw1", "https://example.com", passkeys = 1)
        assertFalse(SecurityCheck.duplicates(listOf(a, b)).single().mergeable)
        val shared = login("c", "anna", "pw1", "https://example.com").copy(shared = true)
        assertFalse(SecurityCheck.duplicates(listOf(login("d", "anna", "pw1", "https://example.com"), shared)).single().mergeable)
    }

    @Test
    fun duplicatesAreNotReportedAsReuse() {
        val report = SecurityCheck.run(listOf(login("a", "anna", "Correct-Horse-Battery-9!x", "https://example.com"), login("b", "anna", "Correct-Horse-Battery-9!x", "https://example.com")))
        assertTrue(report.findings.flatMap { it.issues }.none { it.kind == IssueKind.Reused })
        assertTrue(report.findings.flatMap { it.issues }.any { it.kind == IssueKind.Duplicate })
    }
}
