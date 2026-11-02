package com.example.nfcwritter.nfc

import android.nfc.FormatException
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.Tag
import android.nfc.TagLostException
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.util.Log
import java.io.IOException

sealed interface NfcWriteResult {
    data object Success : NfcWriteResult
    data class NeedsRetapForVerification(val reason: String) : NfcWriteResult
    data class RequiresOverwriteConfirmation(val fingerprint: String) : NfcWriteResult
    data object DifferentNexTapCardProtected : NfcWriteResult
    data class Failure(val reason: String) : NfcWriteResult
}

enum class NfcOperationStage { INSPECTING, WRITING, VERIFYING }

object NfcWriter {
    private const val TAG = "NexTapNfcWriter"

    /**
     * Inspects, writes and verifies through Ndef connections. If the tag already
     * contains the expected URI, it succeeds without another write unless rewrite
     * mode explicitly requests a fresh write.
     */
    fun writeAndVerify(
        tag: Tag,
        expectedUrl: String,
        backendBaseUrl: String,
        forceRewrite: Boolean = false,
        overwriteConfirmationFingerprint: String? = null,
        onStage: (NfcOperationStage) -> Unit = {},
    ): NfcWriteResult {
        if (!NfcRewritePolicy.isCanonicalCardUrl(expectedUrl, backendBaseUrl)) {
            return NfcWriteResult.Failure("The server did not provide a valid NexTap card URL for this card.")
        }
        val message = NdefMessage(arrayOf(NdefRecord.createUri(expectedUrl)))
        val ndef = Ndef.get(tag)
        if (ndef == null) {
            val formatable = NdefFormatable.get(tag)
            return if (formatable != null) formatTag(formatable, message, onStage)
            else unsupported(tag, "resolveTechnology")
        }

        var operation = "connect"
        var writeAttempted = false
        var verificationNdef: Ndef? = null
        return try {
            ndef.connect()

            operation = "readCurrentNdef"
            onStage(NfcOperationStage.INSPECTING)
            val currentMessage = ndef.ndefMessage
            val currentUris = urisFrom(currentMessage)
            if (NfcRewritePolicy.containsDifferentCardUrl(expectedUrl, currentUris, backendBaseUrl)) {
                Log.w(TAG, "operation=protectOtherCard result=blocked uriCount=${currentUris.size}")
                return NfcWriteResult.DifferentNexTapCardProtected
            }
            val exactCurrentMessage = NfcRewritePolicy.exactReadBackMatches(
                expectedUrl,
                currentUris,
                currentMessage?.records?.size ?: 0,
            )
            val currentUri = if (exactCurrentMessage) expectedUrl else currentUris.firstOrNull()
            val hasExistingData = currentMessage?.records?.isNotEmpty() == true
            val overwriteConfirmed = overwriteConfirmationFingerprint != null &&
                overwriteConfirmationFingerprint == NfcRewritePolicy.overwriteConfirmationFingerprint(
                    tag.id,
                    currentMessage?.toByteArray() ?: ByteArray(0),
                )
            val messageSize = message.toByteArray().size
            operation = "inspectProperties"
            val writable = ndef.isWritable
            val maxSize = ndef.maxSize
            Log.i(
                TAG,
                "operation=inspectNdef connected=${ndef.isConnected} writable=$writable " +
                "maxSize=$maxSize messageSize=$messageSize currentUriPresent=${currentUri != null} " +
                    "hasExistingData=$hasExistingData rewriteMode=$forceRewrite",
            )
            when (NfcRewritePolicy.assess(
                expectedUrl = expectedUrl,
                currentUri = if (currentUri == expectedUrl && !exactCurrentMessage) null else currentUri,
                hasExistingData = hasExistingData,
                writable = writable,
                maxSize = maxSize,
                messageSize = messageSize,
                backendBaseUrl = backendBaseUrl,
                forceRewrite = forceRewrite,
                overwriteConfirmed = overwriteConfirmed,
            )) {
                NfcWriteDecision.INVALID_BACKEND_URL -> return NfcWriteResult.Failure(
                    "The server did not provide a valid NexTap card URL for this card.",
                )
                NfcWriteDecision.ALREADY_MATCHES -> {
                    onStage(NfcOperationStage.VERIFYING)
                    Log.i(TAG, "operation=verifyExisting result=exactMatch")
                    return NfcWriteResult.Success
                }
                NfcWriteDecision.READ_ONLY -> return NfcWriteResult.Failure(
                    "This NFC tag is read-only and cannot be rewritten.",
                )
                NfcWriteDecision.INSUFFICIENT_CAPACITY -> return NfcWriteResult.Failure(
                    "This NFC tag does not have enough capacity for the card URL.",
                )
                NfcWriteDecision.DIFFERENT_NEXTAP_CARD -> {
                    Log.w(TAG, "operation=protectOtherCard result=blocked")
                    return NfcWriteResult.DifferentNexTapCardProtected
                }
                NfcWriteDecision.CONFIRM_OVERWRITE -> {
                    Log.i(TAG, "operation=protectExistingNdef result=confirmationRequired")
                    return NfcWriteResult.RequiresOverwriteConfirmation(
                        NfcRewritePolicy.overwriteConfirmationFingerprint(
                            tag.id,
                            currentMessage?.toByteArray() ?: ByteArray(0),
                        ),
                    )
                }
                NfcWriteDecision.WRITE -> Unit
            }

            operation = "writeNdefMessage"
            onStage(NfcOperationStage.WRITING)
            writeAttempted = true
            ndef.writeNdefMessage(message)

            operation = "closeAfterWrite"
            ndef.close()
            operation = "freshVerificationConnect"
            verificationNdef = Ndef.get(tag)
                ?: return NfcWriteResult.Failure("Ndef became unavailable after writing; the result could not be verified.")
            verificationNdef.connect()
            operation = "readBackNdef"
            onStage(NfcOperationStage.VERIFYING)
            val verificationMessage = verificationNdef.ndefMessage
            val verifiedUris = urisFrom(verificationMessage)
            if (NfcRewritePolicy.exactReadBackMatches(
                    expectedUrl,
                    verifiedUris,
                    verificationMessage?.records?.size ?: 0,
                )
            ) {
                Log.i(TAG, "operation=readBackNdef result=exactMatch")
                NfcWriteResult.Success
            } else {
                Log.e(TAG, "operation=readBackNdef result=mismatch uriCount=${verifiedUris.size}")
                NfcWriteResult.Failure("Verification failed: the tag URL did not exactly match the server URL.")
            }
        } catch (e: TagLostException) {
            loggedConnectionFailure(operation, e, writeAttempted)
        } catch (e: IOException) {
            loggedConnectionFailure(operation, e, writeAttempted)
        } catch (e: FormatException) {
            Log.e(TAG, "operation=$operation exception=${e.javaClass.name}", e)
            NfcWriteResult.Failure("The card returned invalid NDEF data during $operation.")
        } catch (e: SecurityException) {
            Log.e(TAG, "operation=$operation exception=${e.javaClass.name}", e)
            NfcWriteResult.Failure("NFC access was denied during $operation.")
        } catch (e: Exception) {
            Log.e(TAG, "operation=$operation exception=${e.javaClass.name} writeAttempted=$writeAttempted", e)
            if (writeAttempted) NfcWriteResult.NeedsRetapForVerification(
                "The write result is uncertain after an unexpected NFC error. Remove the card and re-tap it for verification.",
            ) else NfcWriteResult.Failure("An unexpected NFC error occurred during $operation. Tap Retry.")
        } finally {
            closeNdef(ndef, operation)
            verificationNdef?.let { closeNdef(it, "$operation verification") }
        }
    }

    /** Read-only exact verification used after an uncertain write or format operation. */
    fun verify(tag: Tag, expectedUrl: String): NfcWriteResult {
        val ndef = Ndef.get(tag) ?: return unsupported(tag, "verifyResolveNdef")
        var operation = "verifyConnect"
        return try {
            ndef.connect()
            operation = "verifyReadNdef"
            val actualMessage = ndef.ndefMessage
            val actualUris = urisFrom(actualMessage)
            if (NfcRewritePolicy.exactReadBackMatches(
                    expectedUrl,
                    actualUris,
                    actualMessage?.records?.size ?: 0,
                )
            ) {
                Log.i(TAG, "operation=verifyReadNdef result=exactMatch")
                NfcWriteResult.Success
            } else {
                Log.e(TAG, "operation=verifyReadNdef result=mismatch uriCount=${actualUris.size}")
                NfcWriteResult.Failure("Verification found a different URL. Tap Retry to inspect and write the card again.")
            }
        } catch (e: TagLostException) {
            Log.e(TAG, "operation=$operation exception=${e.javaClass.name}", e)
            NfcWriteResult.NeedsRetapForVerification(
                "The card moved out of range while verifying. Remove it and re-tap to verify again.",
            )
        } catch (e: IOException) {
            Log.e(TAG, "operation=$operation exception=${e.javaClass.name}", e)
            NfcWriteResult.NeedsRetapForVerification(
                "NFC communication was interrupted while verifying. Remove the card and re-tap it.",
            )
        } catch (e: FormatException) {
            Log.e(TAG, "operation=$operation exception=${e.javaClass.name}", e)
            NfcWriteResult.Failure("The card returned invalid NDEF data during verification.")
        } catch (e: SecurityException) {
            Log.e(TAG, "operation=$operation exception=${e.javaClass.name}", e)
            NfcWriteResult.Failure("NFC access was denied during verification.")
        } catch (e: Exception) {
            Log.e(TAG, "operation=$operation exception=${e.javaClass.name}", e)
            NfcWriteResult.NeedsRetapForVerification(
                "Verification was interrupted by an unexpected NFC error. Remove the card and re-tap it.",
            )
        } finally {
            closeNdef(ndef, operation)
        }
    }

    private fun formatTag(
        formatable: NdefFormatable,
        message: NdefMessage,
        onStage: (NfcOperationStage) -> Unit,
    ): NfcWriteResult {
        var operation = "formatConnect"
        var formatAttempted = false
        return try {
            formatable.connect()
            operation = "formatNdef"
            onStage(NfcOperationStage.WRITING)
            formatAttempted = true
            formatable.format(message)
            NfcWriteResult.NeedsRetapForVerification(
                "The card was formatted and written. Remove it and re-tap it for exact verification.",
            )
        } catch (e: TagLostException) {
            Log.e(TAG, "operation=$operation exception=${e.javaClass.name}", e)
            if (formatAttempted) NfcWriteResult.NeedsRetapForVerification(
                "Connection was lost during formatting. Remove the card and re-tap it so NexTap can verify before writing again.",
            ) else NfcWriteResult.Failure("The card moved out of range before formatting started. Tap Retry.")
        } catch (e: IOException) {
            Log.e(TAG, "operation=$operation exception=${e.javaClass.name}", e)
            if (formatAttempted) NfcWriteResult.NeedsRetapForVerification(
                "Formatting may have completed. Remove the card and re-tap it so NexTap can verify safely.",
            ) else NfcWriteResult.Failure("Could not connect to this NFC card for formatting. Tap Retry.")
        } catch (e: FormatException) {
            Log.e(TAG, "operation=$operation exception=${e.javaClass.name}", e)
            NfcWriteResult.Failure("The card could not be formatted as NDEF.")
        } catch (e: SecurityException) {
            Log.e(TAG, "operation=$operation exception=${e.javaClass.name}", e)
            NfcWriteResult.Failure("NFC access was denied while formatting the card.")
        } catch (e: Exception) {
            Log.e(TAG, "operation=$operation exception=${e.javaClass.name} formatAttempted=$formatAttempted", e)
            if (formatAttempted) NfcWriteResult.NeedsRetapForVerification(
                "The format result is uncertain. Remove the card and re-tap it for verification.",
            ) else NfcWriteResult.Failure("An unexpected NFC error occurred before formatting. Tap Retry.")
        } finally {
            runCatching { formatable.close() }.onFailure {
                Log.w(TAG, "operation=formatClose exception=${it.javaClass.name}", it)
            }
        }
    }

    private fun loggedConnectionFailure(
        operation: String,
        exception: IOException,
        writeAttempted: Boolean,
    ): NfcWriteResult {
        Log.e(TAG, "operation=$operation exception=${exception.javaClass.name} writeAttempted=$writeAttempted", exception)
        val lost = exception is TagLostException
        return if (writeAttempted) {
            NfcWriteResult.NeedsRetapForVerification(
                if (lost) "The card moved out of range during $operation. Remove it and re-tap it; NexTap will verify before any rewrite."
                else "NFC communication was interrupted during $operation. Remove the card and re-tap it; NexTap will verify before any rewrite.",
            )
        } else {
            NfcWriteResult.Failure(
                if (lost) "The card moved out of range during $operation. Keep it steady, then tap Retry."
                else "NFC communication failed during $operation. Keep the card steady, then tap Retry.",
            )
        }
    }

    private fun unsupported(tag: Tag, operation: String): NfcWriteResult {
        val technologies = tag.techList.joinToString()
        Log.e(TAG, "operation=$operation result=ndefUnavailable techList=$technologies")
        return NfcWriteResult.Failure(
            "Ndef is unavailable and this tag cannot be written as an NDEF card. Detected techList: $technologies",
        )
    }

    private fun urisFrom(message: NdefMessage?): List<String> = message?.records?.mapNotNull { record ->
        record.toUri()?.toString()?.takeIf { it.isNotBlank() }
    }.orEmpty()

    private fun closeNdef(ndef: Ndef, operation: String) {
        runCatching { ndef.close() }.onFailure {
            Log.w(TAG, "operation=closeNdef after=$operation exception=${it.javaClass.name}", it)
        }
    }
}
