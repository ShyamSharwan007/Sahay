package com.sahay.comms.sos

import org.junit.Assert.assertEquals
import org.junit.Test

class SmsLengthTest {

    @Test fun `empty text has no parts`() = assertEquals(0, SmsLength.parts(""))

    @Test fun `GSM text up to 160 septets is one part`() {
        assertEquals(1, SmsLength.parts("a".repeat(160)))
        assertEquals(2, SmsLength.parts("a".repeat(161)))
        assertEquals(2, SmsLength.parts("a".repeat(306)))
        assertEquals(3, SmsLength.parts("a".repeat(307)))
    }

    @Test fun `extension characters cost two septets`() {
        assertEquals(1, SmsLength.parts("{".repeat(80)))     // 160 septets
        assertEquals(2, SmsLength.parts("{".repeat(81)))
    }

    @Test fun `one character outside GSM switches the whole text to UCS-2`() {
        assertEquals(1, SmsLength.parts("±" + "a".repeat(69)))        // 70 units
        assertEquals(2, SmsLength.parts("±" + "a".repeat(70)))
        assertEquals(3, SmsLength.parts("±" + "a".repeat(134)))       // 135 units > 2 x 67
        assertEquals(3, SmsLength.parts("±" + "a".repeat(200)))       // 201 units
        assertEquals(4, SmsLength.parts("±" + "a".repeat(201)))
    }

    @Test fun `an emoji is two UCS-2 units`() = assertEquals(2, SmsLength.parts("😀".repeat(36)))   // 72 units
}
