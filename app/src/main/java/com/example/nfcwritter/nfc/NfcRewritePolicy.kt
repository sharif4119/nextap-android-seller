package com.example.nfcwritter.nfc

import java.net.URI
import java.security.MessageDigest

enum class NfcWriteDecision {
    INVALID_BACKEND_URL,
    ALREADY_MATCHES,
    READ_ONLY,
    INSUFFICIENT_CAPACITY,
    DIFFERENT_NEXTAP_CARD,
    CONFIRM_OVERWRITE,
    WRITE,
}

/** Pure policy for deciding whether a scanned tag may receive this card's canonical URL. */
object NfcRewritePolicy {
    fun isRewriteAvailable(status: String): Boolean =
        status.trim().uppercase() in setOf("PROGRAMMED", "ACTIVE")

    fun shouldUpdateBackendAfterWrite(rewriteMode: Boolean): Boolean = !rewriteMode

    fun overwriteConfirmationFingerprint(tagId: ByteArray, currentNdefMessage: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(tagId.size.toByte())
        digest.update(tagId)
        digest.update(currentNdefMessage)
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    fun containsDifferentCardUrl(expectedUrl: String, currentUrls: List<String>, backendBaseUrl: String): Boolean =
        currentUrls.any { it != expectedUrl && isCanonicalCardUrl(it, backendBaseUrl) }

    fun exactReadBackMatches(expectedUrl: String, actualUrls: List<String>, recordCount: Int): Boolean =
        recordCount == 1 && actualUrls == listOf(expectedUrl)

    fun assess(
        expectedUrl: String,
        currentUri: String?,
        hasExistingData: Boolean,
        writable: Boolean,
        maxSize: Int,
        messageSize: Int,
        backendBaseUrl: String,
        forceRewrite: Boolean = false,
        overwriteConfirmed: Boolean = false,
    ): NfcWriteDecision {
        if (!isCanonicalCardUrl(expectedUrl, backendBaseUrl)) return NfcWriteDecision.INVALID_BACKEND_URL
        if (currentUri == expectedUrl && !forceRewrite) return NfcWriteDecision.ALREADY_MATCHES
        if (!writable) return NfcWriteDecision.READ_ONLY
        if (messageSize > maxSize) return NfcWriteDecision.INSUFFICIENT_CAPACITY

        if (!currentUri.isNullOrBlank() && currentUri != expectedUrl &&
            isCanonicalCardUrl(currentUri, backendBaseUrl)
        ) {
            return NfcWriteDecision.DIFFERENT_NEXTAP_CARD
        }
        if (hasExistingData && currentUri != expectedUrl && !overwriteConfirmed) {
            return NfcWriteDecision.CONFIRM_OVERWRITE
        }
        return NfcWriteDecision.WRITE
    }

    fun isCanonicalCardUrl(candidate: String, backendBaseUrl: String): Boolean {
        val url = runCatching { URI(candidate.trim()) }.getOrNull() ?: return false
        val base = runCatching { URI(backendBaseUrl.trim()) }.getOrNull() ?: return false
        if (url.scheme !in setOf("https", "http") || url.host.isNullOrBlank() ||
            url.rawUserInfo != null || url.rawFragment != null
        ) return false
        if (!sameOrigin(url, base)) return false
        val segments = url.path.split('/').filter(String::isNotBlank)
        return segments.size == 2 && segments[0] == "c" && segments[1].isNotBlank()
    }

    private fun sameOrigin(left: URI, right: URI): Boolean {
        fun effectivePort(uri: URI): Int = when {
            uri.port >= 0 -> uri.port
            uri.scheme.equals("https", ignoreCase = true) -> 443
            uri.scheme.equals("http", ignoreCase = true) -> 80
            else -> -1
        }
        return left.scheme.equals(right.scheme, ignoreCase = true) &&
            left.host.equals(right.host, ignoreCase = true) && effectivePort(left) == effectivePort(right)
    }
}
