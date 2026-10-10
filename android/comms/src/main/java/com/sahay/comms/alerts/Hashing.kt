package com.sahay.comms.alerts

import java.security.MessageDigest

/** Lower-case hex SHA-256 of the UTF-8 bytes of [text]. */
internal fun sha256Hex(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
