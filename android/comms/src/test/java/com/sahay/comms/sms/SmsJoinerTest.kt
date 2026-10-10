package com.sahay.comms.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsJoinerTest {

    @Test fun `parts of one long SMS are joined in order`() {
        val joined = SmsJoiner.join(
            listOf(
                IncomingSms("VM-NDMAEW", "Cyclone warning for "),
                IncomingSms("VM-NDMAEW", "the coast. "),
                IncomingSms("VM-NDMAEW", "Stay indoors."),
            ),
        )
        assertEquals(listOf(IncomingSms("VM-NDMAEW", "Cyclone warning for the coast. Stay indoors.")), joined)
    }

    @Test fun `different senders stay separate and keep their order`() {
        val joined = SmsJoiner.join(
            listOf(IncomingSms("B", "b1"), IncomingSms("A", "a1"), IncomingSms("B", "b2"), IncomingSms(null, "n1")),
        )
        assertEquals(listOf("B" to "b1b2", "A" to "a1", null to "n1"), joined.map { it.sender to it.body })
    }

    @Test fun `empty input and blank bodies give nothing`() {
        assertTrue(SmsJoiner.join(emptyList()).isEmpty())
        assertTrue(SmsJoiner.join(listOf(IncomingSms("A", ""), IncomingSms("B", "  "))).isEmpty())
    }
}
