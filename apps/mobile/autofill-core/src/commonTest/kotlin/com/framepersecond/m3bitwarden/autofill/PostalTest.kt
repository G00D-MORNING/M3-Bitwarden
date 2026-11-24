package com.framepersecond.m3bitwarden.autofill

import com.framepersecond.m3bitwarden.autofill.format.PostalSplitter
import com.framepersecond.m3bitwarden.autofill.format.PostalSplitter.Place
import kotlin.test.Test
import kotlin.test.assertEquals

class PostalTest {
    private fun r(code: String, city: String, vararg lines: String) = PostalSplitter.resolve(code, city, lines.toList())

    @Test fun separateFieldsStay() = assertEquals(Place("10115", "Berlin"), r("10115", "Berlin"))
    @Test fun codeInsideCity() = assertEquals(Place("10115", "Berlin"), r("", "10115 Berlin"))
    @Test fun countryPrefix() = assertEquals(Place("8001", "Zürich"), r("", "CH-8001 Zürich"))
    @Test fun cityFirst() = assertEquals(Place("1010", "Wien"), r("", "Wien 1010"))
    @Test fun dutch() = assertEquals(Place("1012 AB", "Amsterdam"), r("", "1012 AB Amsterdam"))
    @Test fun uk() = assertEquals(Place("SW1A 1AA", "London"), r("", "SW1A 1AA London"))
    @Test fun polish() = assertEquals(Place("00-950", "Warszawa"), r("", "00-950 Warszawa"))
    @Test fun duplicatedCodeRemovedFromCity() = assertEquals(Place("10115", "Berlin"), r("10115", "10115 Berlin"))
    @Test fun codeInAddressLine() = assertEquals(Place("80331", "München"), r("", "", "Marienplatz 1, 80331 München"))
    @Test fun houseNumberIsNoCode() = assertEquals(Place("", ""), r("", "", "Musterstraße 1234"))
    @Test fun nothingFound() = assertEquals(Place("", "Berlin"), r("", "Berlin"))
}
