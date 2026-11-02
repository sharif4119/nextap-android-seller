package com.example.nfcwritter

import com.example.nfcwritter.nfc.NfcRewritePolicy
import com.example.nfcwritter.nfc.NfcWriteDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NfcRewritePolicyTest {
    private val backend = "https://nextap.pythonanywhere.com/"
    private val cardOne = "https://nextap.pythonanywhere.com/c/card-one/"
    private val cardTwo = "https://nextap.pythonanywhere.com/c/card-two/"

    @Test
    fun rewriteIsAvailableForPreparedAndActiveCardsOnly() {
        assertTrue(NfcRewritePolicy.isRewriteAvailable("PROGRAMMED"))
        assertTrue(NfcRewritePolicy.isRewriteAvailable("ACTIVE"))
        assertFalse(NfcRewritePolicy.isRewriteAvailable("ASSIGNED"))
        assertFalse(NfcRewritePolicy.isRewriteAvailable("UNASSIGNED"))
    }

    @Test
    fun rewriteNeverRequestsBackendLifecycleMutation() {
        assertFalse(NfcRewritePolicy.shouldUpdateBackendAfterWrite(rewriteMode = true))
        assertTrue(NfcRewritePolicy.shouldUpdateBackendAfterWrite(rewriteMode = false))
    }

    @Test
    fun exactExistingBackendUrlIsVerifiedWithoutAWriteByDefault() {
        assertEquals(
            NfcWriteDecision.ALREADY_MATCHES,
            assess(expected = cardOne, current = cardOne),
        )
    }

    @Test
    fun explicitRewriteWritesTheSameCanonicalBackendUrl() {
        assertEquals(
            NfcWriteDecision.WRITE,
            assess(expected = cardOne, current = cardOne, forceRewrite = true),
        )
        assertTrue(NfcRewritePolicy.isCanonicalCardUrl(cardOne, backend))
    }

    @Test
    fun anotherCardsNexTapUrlIsBlockedInsteadOfOverwritten() {
        assertEquals(
            NfcWriteDecision.DIFFERENT_NEXTAP_CARD,
            assess(expected = cardOne, current = cardTwo, forceRewrite = true),
        )
        assertTrue(NfcRewritePolicy.containsDifferentCardUrl(cardOne, listOf(cardOne, cardTwo), backend))
    }

    @Test
    fun unrelatedExistingDataRequiresExplicitConfirmation() {
        val unrelated = "https://example.org/some-page"
        assertEquals(
            NfcWriteDecision.CONFIRM_OVERWRITE,
            assess(expected = cardOne, current = unrelated, hasData = true),
        )
        assertEquals(
            NfcWriteDecision.WRITE,
            assess(expected = cardOne, current = unrelated, hasData = true, overwriteConfirmed = true),
        )
    }

    @Test
    fun readOnlyTagIsRejectedEvenWhenRewriteUrlAlreadyMatches() {
        assertEquals(
            NfcWriteDecision.READ_ONLY,
            assess(expected = cardOne, current = cardOne, writable = false, forceRewrite = true),
        )
    }

    @Test
    fun insufficientTagCapacityIsRejected() {
        assertEquals(
            NfcWriteDecision.INSUFFICIENT_CAPACITY,
            assess(expected = cardOne, maxSize = 8),
        )
    }

    @Test
    fun missingOrForeignBackendUrlIsRejected() {
        assertFalse(NfcRewritePolicy.isCanonicalCardUrl("", backend))
        assertFalse(NfcRewritePolicy.isCanonicalCardUrl("https://evil.example/c/token/", backend))
        assertEquals(
            NfcWriteDecision.INVALID_BACKEND_URL,
            assess(expected = "https://evil.example/c/token/"),
        )
    }

    @Test
    fun failedOrAmbiguousReadBackNeverCountsAsExactVerification() {
        assertTrue(NfcRewritePolicy.exactReadBackMatches(cardOne, listOf(cardOne), 1))
        assertFalse(NfcRewritePolicy.exactReadBackMatches(cardOne, listOf(cardTwo), 1))
        assertFalse(NfcRewritePolicy.exactReadBackMatches(cardOne, listOf(cardOne, cardTwo), 2))
        assertFalse(NfcRewritePolicy.exactReadBackMatches(cardOne, emptyList(), 0))
    }

    @Test
    fun overwriteConfirmationIsBoundToTheScannedTagAndItsCurrentContent() {
        val first = NfcRewritePolicy.overwriteConfirmationFingerprint(byteArrayOf(1, 2), byteArrayOf(3, 4))
        val otherTag = NfcRewritePolicy.overwriteConfirmationFingerprint(byteArrayOf(1, 9), byteArrayOf(3, 4))
        val changedContent = NfcRewritePolicy.overwriteConfirmationFingerprint(byteArrayOf(1, 2), byteArrayOf(3, 5))
        assertEquals(64, first.length)
        assertNotEquals(first, otherTag)
        assertNotEquals(first, changedContent)
    }

    private fun assess(
        expected: String,
        current: String? = null,
        hasData: Boolean = current != null,
        writable: Boolean = true,
        maxSize: Int = 512,
        forceRewrite: Boolean = false,
        overwriteConfirmed: Boolean = false,
    ) = NfcRewritePolicy.assess(
        expectedUrl = expected,
        currentUri = current,
        hasExistingData = hasData,
        writable = writable,
        maxSize = maxSize,
        messageSize = 64,
        backendBaseUrl = backend,
        forceRewrite = forceRewrite,
        overwriteConfirmed = overwriteConfirmed,
    )
}
