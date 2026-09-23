package com.example.nfcwritter.data

import okhttp3.HttpUrl.Companion.toHttpUrl

object PrintDesignSupport {
    fun resolveSameServerUrl(baseUrl: String, candidate: String): String? {
        val base = runCatching { baseUrl.toHttpUrl() }.getOrNull() ?: return null
        val resolved = base.resolve(candidate.trim()) ?: return null
        return resolved.toString().takeIf {
            resolved.scheme == base.scheme && resolved.host == base.host && resolved.port == base.port
        }
    }

    fun fileName(contentDisposition: String?, fallbackUid: String): String {
        val headerName = contentDisposition
            ?.substringAfter("filename=", missingDelimiterValue = "")
            ?.trim()
            ?.trim('"')
            ?.substringBefore(';')
            ?.takeIf { it.isNotBlank() }
        val fallback = "NexTap-${fallbackUid.ifBlank { "card" }}-front-back.pdf"
        val cleaned = (headerName ?: fallback)
            .replace(Regex("[^A-Za-z0-9._-]+"), "-")
            .trim('-', '.')
            .take(100)
            .ifBlank { "NexTap-card-front-back.pdf" }
        return if (cleaned.endsWith(".pdf", ignoreCase = true)) cleaned else "$cleaned.pdf"
    }
}
