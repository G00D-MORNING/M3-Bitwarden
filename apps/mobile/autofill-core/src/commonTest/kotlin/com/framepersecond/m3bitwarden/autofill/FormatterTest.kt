package com.framepersecond.m3bitwarden.autofill

import com.framepersecond.m3bitwarden.autofill.format.AddressSplitter
import com.framepersecond.m3bitwarden.autofill.format.Countries
import com.framepersecond.m3bitwarden.autofill.format.FillValue
import com.framepersecond.m3bitwarden.autofill.format.ValueFormatter
import com.framepersecond.m3bitwarden.autofill.model.FieldFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FormatterTest {
    private fun text(kind: ValueKind, raw: String, placeholder: String? = null, maxLength: Int = -1) =
        (ValueFormatter.format(kind, raw, FieldFormat(placeholder = placeholder, maxLength = maxLength)) as? FillValue.Text)?.text

    private fun pick(kind: ValueKind, raw: String, vararg options: String) =
        (ValueFormatter.format(kind, raw, FieldFormat(options = options.toList(), isSelect = true)) as? FillValue.ListIndex)?.index

    @Test
    fun streetAndHouseNumber() {
        assertEquals(AddressSplitter.Parts("Musterstraße", "12a"), AddressSplitter.split("Musterstraße 12a"))
        assertEquals(AddressSplitter.Parts("Hauptstr.", "5-7"), AddressSplitter.split("Hauptstr. 5-7"))
        assertEquals(AddressSplitter.Parts("Am Ring", "3b"), AddressSplitter.split("Am Ring 3 b"))
        assertEquals(AddressSplitter.Parts("Leopoldstraße", "12/3"), AddressSplitter.split("Leopoldstraße 12/3"))
        assertEquals(AddressSplitter.Parts("Main St", "123"), AddressSplitter.split("123 Main St"))
        assertEquals(AddressSplitter.Parts("rue de la Paix", "12"), AddressSplitter.split("12 rue de la Paix"))
        assertEquals(AddressSplitter.Parts("Straße des 17. Juni", "135"), AddressSplitter.split("Straße des 17. Juni 135"))
        assertEquals(AddressSplitter.Parts("Postfach", null), AddressSplitter.split("Postfach"))
        assertEquals("Musterstraße", text(ValueKind.Street, "Musterstraße 12a"))
        assertEquals("12a", text(ValueKind.HouseNumber, "Musterstraße 12a"))
        assertNull(text(ValueKind.HouseNumber, "Postfach"))
    }

    @Test
    fun cardExpiryFollowsThePlaceholder() {
        assertEquals("08/29", text(ValueKind.CardExpiry, "08/2029", "MM/JJ"))
        assertEquals("08 / 29", text(ValueKind.CardExpiry, "08/2029", "MM / YY"))
        assertEquals("08/2029", text(ValueKind.CardExpiry, "08/2029", "MM/JJJJ"))
        assertEquals("0829", text(ValueKind.CardExpiry, "08/2029", "MMJJ"))
        assertEquals("0829", text(ValueKind.CardExpiry, "8/29", maxLength = 4))
        assertEquals("08/29", text(ValueKind.CardExpiry, "08/2029"))
        assertEquals("08", text(ValueKind.CardExpiryMonth, "8", "MM", 2))
        assertEquals("29", text(ValueKind.CardExpiryYear, "2029", "JJ", 2))
        assertEquals("2029", text(ValueKind.CardExpiryYear, "2029", "JJJJ", 4))
    }

    @Test
    fun selectsPickTheRightOption() {
        assertEquals(8, pick(ValueKind.CardExpiryMonth, "8", "Monat", "01", "02", "03", "04", "05", "06", "07", "08", "09", "10", "11", "12"))
        assertEquals(7, pick(ValueKind.CardExpiryMonth, "8", "1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12"))
        assertEquals(3, pick(ValueKind.CardExpiryMonth, "3", "Monat", "Januar", "Februar", "März", "April"))
        assertEquals(4, pick(ValueKind.CardExpiryYear, "2029", "Jahr", "2026", "2027", "2028", "2029"))
        assertEquals(2, pick(ValueKind.CardExpiryYear, "2029", "YY", "28", "29"))
        assertEquals(1, pick(ValueKind.Country, "Deutschland", "Austria", "Germany", "Switzerland"))
        assertEquals(2, pick(ValueKind.Country, "Germany", "AT", "CH", "DE"))
        assertEquals(0, pick(ValueKind.Country, "DE", "Deutschland", "Österreich"))
        assertEquals(2, pick(ValueKind.Title, "Mrs", "Bitte wählen", "Herr", "Frau", "Divers"))
        assertEquals(1, pick(ValueKind.Title, "Herr", "Keine Angabe", "Mr", "Mrs"))
        assertEquals(1, pick(ValueKind.Title, "Mme", "M.", "Mme"))
        assertEquals(2, pick(ValueKind.State, "Bayern", "Bitte wählen", "Baden-Württemberg", "Bayern", "Berlin"))
        assertNull(pick(ValueKind.Country, "Atlantis", "Deutschland", "Österreich"))
    }

    @Test
    fun lengthLimitsAndCodes() {
        assertEquals("4111111111111111", text(ValueKind.CardNumber, "4111 1111 1111 1111"))
        assertEquals("DE", text(ValueKind.Country, "Deutschland", maxLength = 2))
        assertEquals("+491512345678", text(ValueKind.Phone, "+49 151 2345678", maxLength = 15))
        assertEquals("DE", Countries.code("Bundesrepublik Deutschland"))
        assertEquals("CZ", Countries.code("Česko"))
    }

    @Test
    fun phonePartsAndCardBrand() {
        assertEquals(com.framepersecond.m3bitwarden.autofill.format.PhoneSplitter.Parts("49", "089", "1234567"), com.framepersecond.m3bitwarden.autofill.format.PhoneSplitter.split("+49 89 1234567"))
        assertEquals(com.framepersecond.m3bitwarden.autofill.format.PhoneSplitter.Parts(null, "0151", "23456789"), com.framepersecond.m3bitwarden.autofill.format.PhoneSplitter.split("015123456789"))
        assertEquals(com.framepersecond.m3bitwarden.autofill.format.PhoneSplitter.Parts("1", "415", "5550123"), com.framepersecond.m3bitwarden.autofill.format.PhoneSplitter.split("+1 415 555 0123"))
        assertEquals("089", text(ValueKind.PhoneAreaCode, "+49 89 1234567"))
        assertEquals("1234567", text(ValueKind.PhoneLocal, "+49 89 1234567"))
        assertEquals("555", text(ValueKind.PhoneLocal, "+1 415 555 0123", maxLength = 3))
        assertEquals("0123", text(ValueKind.PhoneLocal, "+1 415 555 0123", maxLength = 4))
        assertEquals("+49", text(ValueKind.PhoneCountryCode, "+49 89 1234567"))
        assertEquals(2, pick(ValueKind.CardBrand, "Mastercard", "Bitte wählen", "Visa", "MasterCard", "American Express"))
        assertEquals(3, pick(ValueKind.CardBrand, "Amex", "Bitte wählen", "Visa", "MasterCard", "American Express"))
    }
}
