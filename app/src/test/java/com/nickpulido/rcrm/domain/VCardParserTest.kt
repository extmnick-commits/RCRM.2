package com.nickpulido.rcrm.domain

import org.junit.Assert.*
import org.junit.Test

class VCardParserTest {
    @Test fun parsesMultiPhoneVCardWithLabelsAndMetadata() {
        val vcf = """
            BEGIN:VCARD
            VERSION:3.0
            FN:Alex Rivera
            ORG:Acme Financial;Sales
            TITLE:Senior Advisor
            TEL;TYPE=CELL:+1 (209) 555-0199
            TEL;TYPE=WORK:209-555-0144
            EMAIL;TYPE=INTERNET:alex@acme.example
            ADR;TYPE=WORK:;;100 Main St;Modesto;CA;95354;USA
            END:VCARD
        """.trimIndent()

        val contacts = VCardParser.parse(vcf)
        assertEquals(1, contacts.size)
        val contact = contacts.single()
        assertEquals("Alex Rivera", contact.name)
        assertEquals("Acme Financial", contact.company)
        assertEquals("Senior Advisor", contact.jobTitle)
        assertEquals("alex@acme.example", contact.email)
        assertEquals("100 Main St, Modesto, CA, 95354, USA", contact.address)
        assertEquals(
            listOf(
                LabeledPhone("Mobile", "+1 (209) 555-0199"),
                LabeledPhone("Work", "209-555-0144")
            ),
            contact.phones
        )
    }

    @Test fun deduplicatesMatchingPhoneNumbersAndFallsBackToStructuredName() {
        val vcf = """
            BEGIN:VCARD
            VERSION:2.1
            N:Doe;Jane;;;
            TEL;HOME:(555) 123-4567
            TEL;CELL:5551234567
            END:VCARD
        """.trimIndent()

        val contacts = VCardParser.parse(vcf)
        assertEquals(1, contacts.size)
        assertEquals("Jane Doe", contacts.single().name)
        assertEquals(1, contacts.single().phones.size)
        assertEquals("Home", contacts.single().phones.single().label)
    }

    @Test fun unfoldsWrappedLines() {
        val vcf = "BEGIN:VCARD\r\nFN:Maria Elena\r\n  Gonzalez\r\nTEL;TYPE=CELL:5550001111\r\nEND:VCARD"
        val contacts = VCardParser.parse(vcf)
        assertEquals(1, contacts.size)
        assertEquals("Maria Elena Gonzalez", contacts.single().name)
    }
}
