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
    data class Failure(val reason: String) : NfcWriteResult
}

enum class NfcOperationStage { INSPECTING, WRITING, VERIFYING }

object NfcWriter {
    private const val TAG = "NexTapNfcWriter"

    /**
     * Inspects, writes and verifies through one Ndef connection. If the tag already
     * contains the expected URI, it succeeds without performing another write.
     */
    fun writeAndVerify(
        tag: Tag,
        expectedUrl: String,
        onStage: (NfcOperationStage) -> Unit = {},
    ): NfcWriteResult {
        val message = NdefMessage(arrayOf(NdefRecord.createUri(expectedUrl)))
        val ndef = Ndef.get(tag)
        if (ndef == null) {
            val formatable = NdefFormatable.get(tag)
            return if (formatable != null) formatTag(formatable, message, onStage)
            else unsupported(tag, "resolveTechnology")
        }

        var operation = "connect"
        var writeAttempted = false
        return try {
            ndef.connect()

            operation = "readCurrentNdef"
            onStage(NfcOperationStage.INSPECTING)
            val currentUri = uriFrom(ndef.ndefMessage)
            val messageSize = message.toByteArray().size
            operation = "inspectProperties"
            val writable = ndef.isWritable
            val maxSize = ndef.maxSize
            Log.i(
                TAG,
                "operation=inspectNdef connected=${ndef.isConnected} writable=$writable " +
                    "maxSize=$maxSize messageSize=$messageSize currentUriPresent=${currentUri != null}",
            )
            if (currentUri == expectedUrl) {
                onStage(NfcOperationStage.VERIFYING)
                Log.i(TAG, "operation=verifyExisting result=exactMatch")
                return NfcWriteResult.Success
            }

            operation = "checkWritable"
            if (!writable) return NfcWriteResult.Failure("This NFC tag is read-only.")
            if (messageSize > maxSize) {
                return NfcWriteResult.Failure("This NFC tag is too small for the card URL.")
            }

            operation = "writeNdefMessage"
            onStage(NfcOperationStage.WRITING)
            writeAttempted = true
            ndef.writeNdefMessage(message)

            operation = "readBackNdef"
            onStage(NfcOperationStage.VERIFYING)
            val verifiedUri = uriFrom(ndef.ndefMessage)
            if (verifiedUri == expectedUrl) {
                Log.i(TAG, "operation=readBackNdef result=exactMatch")
                NfcWriteResult.Success
            } else {
                Log.e(TAG, "operation=readBackNdef result=mismatch uriPresent=${verifiedUri != null}")
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
        }
    }

    /** Read-only exact verification used after an uncertain write or format operation. */
    fun verify(tag: Tag, expectedUrl: String): NfcWriteResult {
        val ndef = Ndef.get(tag) ?: return unsupported(tag, "verifyResolveNdef")
        var operation = "verifyConnect"
        return try {
            ndef.connect()
            operation = "verifyReadNdef"
            val actual = uriFrom(ndef.ndefMessage)
            if (actual == expectedUrl) {
                Log.i(TAG, "operation=verifyReadNdef result=exactMatch")
                NfcWriteResult.Success
            } else {
                Log.e(TAG, "operation=verifyReadNdef result=mismatch uriPresent=${actual != null}")
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

    private fun uriFrom(message: NdefMessage?): String? = message?.records?.firstNotNullOfOrNull { record ->
        record.toUri()?.toString()?.takeIf { it.isNotBlank() }
    }

    private fun closeNdef(ndef: Ndef, operation: String) {
        runCatching { ndef.close() }.onFailure {
            Log.w(TAG, "operation=closeNdef after=$operation exception=${it.javaClass.name}", it)
        }
    }
}
