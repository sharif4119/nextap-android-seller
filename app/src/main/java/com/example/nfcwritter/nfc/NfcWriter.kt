package com.example.nfcwritter.nfc

import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import java.io.IOException

sealed interface NfcWriteResult {
    data object Success : NfcWriteResult
    data object NeedsRetapForVerification : NfcWriteResult
    data class Failure(val reason: String) : NfcWriteResult
}

object NfcWriter {
    fun writeAndVerify(tag: Tag, expectedUrl: String): NfcWriteResult {
        val message = NdefMessage(arrayOf(NdefRecord.createUri(expectedUrl)))
        return try {
            val ndef = Ndef.get(tag)
            when {
                ndef != null -> writeNdef(ndef, message).let { result ->
                    if (result is NfcWriteResult.Failure) result else verify(tag, expectedUrl)
                }
                NdefFormatable.get(tag) != null -> formatTag(tag, message)
                else -> NfcWriteResult.Failure("Unsupported tag: this tag is not NDEF compatible.")
            }
        } catch (e: SecurityException) {
            NfcWriteResult.Failure("NFC access was denied.")
        } catch (e: Exception) {
            NfcWriteResult.Failure(e.message ?: "NFC write failed.")
        }
    }

    private fun writeNdef(ndef: Ndef, message: NdefMessage): NfcWriteResult = useConnected(ndef) {
        if (!ndef.isWritable) return@useConnected NfcWriteResult.Failure("This NFC tag is read-only.")
        if (message.toByteArray().size > ndef.maxSize) {
            return@useConnected NfcWriteResult.Failure("This NFC tag is too small for the card URL.")
        }
        ndef.writeNdefMessage(message)
        NfcWriteResult.Success
    }

    private fun formatTag(tag: Tag, message: NdefMessage): NfcWriteResult {
        val formatable = NdefFormatable.get(tag)
            ?: return NfcWriteResult.Failure("Unsupported tag: it cannot be NDEF formatted.")
        return try {
            formatable.connect()
            formatable.format(message)
            NfcWriteResult.NeedsRetapForVerification
        } catch (e: IOException) {
            NfcWriteResult.Failure("Could not format and write this NFC tag.")
        } finally {
            runCatching { formatable.close() }
        }
    }

    fun verify(tag: Tag, expectedUrl: String): NfcWriteResult {
        val ndef = Ndef.get(tag)
            ?: return NfcWriteResult.Failure("Verification failed: the written tag is not readable as NDEF.")
        return useConnected(ndef) {
            val actual = ndef.ndefMessage?.records?.firstOrNull()?.toUri()?.toString()
            if (actual == expectedUrl) NfcWriteResult.Success
            else NfcWriteResult.Failure("Verification failed: the tag URL did not exactly match the server URL.")
        }
    }

    private inline fun useConnected(ndef: Ndef, block: () -> NfcWriteResult): NfcWriteResult = try {
        ndef.connect()
        block()
    } catch (e: IOException) {
        NfcWriteResult.Failure("NFC communication failed. Keep the tag against the phone and try again.")
    } catch (e: android.nfc.FormatException) {
        NfcWriteResult.Failure("The tag returned invalid NDEF data.")
    } finally {
        runCatching { ndef.close() }
    }
}
