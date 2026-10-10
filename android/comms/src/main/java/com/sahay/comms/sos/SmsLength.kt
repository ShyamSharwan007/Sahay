package com.sahay.comms.sos

/**
 * Counts SMS parts the way the network does, so a message can be trimmed before sending.
 * GSM-7 (160 septets, 153 per part when split) when every character is in the GSM alphabet, otherwise UCS-2
 * (70 units, 67 per part when split). The platform makes the final split; this only needs to agree with it.
 */
internal object SmsLength {
    private const val GSM7_SINGLE = 160
    private const val GSM7_MULTI = 153
    private const val UCS2_SINGLE = 70
    private const val UCS2_MULTI = 67

    // GSM 03.38 basic table (one septet each) and extension table (two septets: escape + code).
    private val GSM7_BASIC: Set<Char> =
        ("@£\$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?¡" +
            "ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà").toSet()
    private val GSM7_EXTENDED: Set<Char> = "\u000C^{}\\[~]|€".toSet()

    fun parts(text: String): Int {
        if (text.isEmpty()) return 0
        val septets = gsm7Length(text)
        return if (septets != null) split(septets, GSM7_SINGLE, GSM7_MULTI) else split(text.length, UCS2_SINGLE, UCS2_MULTI)
    }

    /** Number of septets, or null if the text needs UCS-2. */
    private fun gsm7Length(text: String): Int? {
        var septets = 0
        for (c in text) {
            septets += when (c) {
                in GSM7_BASIC -> 1
                in GSM7_EXTENDED -> 2
                else -> return null
            }
        }
        return septets
    }

    private fun split(units: Int, single: Int, multi: Int) =
        if (units <= single) 1 else (units + multi - 1) / multi
}
