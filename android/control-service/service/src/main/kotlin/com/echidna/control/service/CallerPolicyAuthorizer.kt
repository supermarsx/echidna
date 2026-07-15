package com.echidna.control.service

import java.nio.charset.StandardCharsets

private val CALLER_PACKAGE_PATTERN = Regex("[A-Za-z0-9._]+")
private val CALLER_PROCESS_PATTERN = Regex("[A-Za-z0-9._:]+")

internal object CallerPolicyAuthorizer {
    /** Returns the authenticated base package, or null for a mismatched/malformed caller claim. */
    fun authorize(packagesForUid: Collection<String>, processName: String?): String? {
        val process = processName ?: return null
        if (
            process.isEmpty() ||
            process.toByteArray(StandardCharsets.UTF_8).size > 255 ||
            !CALLER_PROCESS_PATTERN.matches(process)
        ) {
            return null
        }
        val packageName = process.substringBefore(':')
        if (!CALLER_PACKAGE_PATTERN.matches(packageName)) return null
        return packageName.takeIf { it in packagesForUid }
    }
}
