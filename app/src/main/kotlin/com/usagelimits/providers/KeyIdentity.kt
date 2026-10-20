package com.usagelimits.providers

import java.security.MessageDigest

/**
 * A stable account id for a provider whose response names nobody: a truncated SHA-256 of the
 * credential, never reversible into it. A rotated key is therefore a new account.
 */
internal fun keyIdentity(key: String): String =
    "key-" + MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).take(8).joinToString("") { "%02x".format(it) }
