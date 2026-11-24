package com.framepersecond.m3bitwarden.autofill

import com.framepersecond.m3bitwarden.autofill.format.AddressSplitter
import com.framepersecond.m3bitwarden.autofill.format.HouseNumberJoiner
import com.framepersecond.m3bitwarden.autofill.format.HouseNumberJoiner.Joined
import kotlin.test.Test
import kotlin.test.assertEquals

class HouseNumberTest {
    @Test fun together() = assertEquals(Joined("Musterstraße 12a", listOf("Hinterhaus")), HouseNumberJoiner.join("Musterstraße 12a", "Hinterhaus", ""))
    @Test fun numberOnSecondLine() = assertEquals(Joined("Musterstraße 12a", emptyList()), HouseNumberJoiner.join("Musterstraße", "12a", ""))
    @Test fun numberFirst() = assertEquals(Joined("Hauptstr. 5-7", listOf("3. OG")), HouseNumberJoiner.join("5-7", "Hauptstr.", "3. OG"))
    @Test fun nrPrefix() = assertEquals(Joined("Am Ring 3", emptyList()), HouseNumberJoiner.join("Am Ring", "Nr. 3", ""))
    @Test fun additionIsNotANumber() = assertEquals(Joined("Musterstraße 1", listOf("Wohnung 4")), HouseNumberJoiner.join("Musterstraße 1", "Wohnung 4", ""))
    @Test fun splitsAgainForSeparateFields() {
        val line = HouseNumberJoiner.join("Musterstraße", "12a", "").line
        assertEquals(AddressSplitter.Parts("Musterstraße", "12a"), AddressSplitter.split(line))
    }
}
