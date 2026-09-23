package com.example.nfcwritter

import com.example.nfcwritter.nfc.NfcCaptureAction
import com.example.nfcwritter.nfc.NfcCaptureController
import com.example.nfcwritter.nfc.NfcCaptureState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NfcCaptureControllerTest {
    @Test
    fun duplicateCallbacksAreRejectedWhileProcessing() {
        val controller = NfcCaptureController()

        assertEquals(NfcCaptureAction.WRITE_OR_VERIFY, controller.tryAcceptTag())
        assertNull(controller.tryAcceptTag())
        assertEquals(NfcCaptureState.PROCESSING, controller.state)
    }

    @Test
    fun uncertainWriteRequiresVerificationBeforeAnotherWrite() {
        val controller = NfcCaptureController()

        controller.tryAcceptTag()
        controller.needsVerificationRetap()

        assertEquals(NfcCaptureAction.VERIFY_ONLY, controller.tryAcceptTag())
        assertNull(controller.tryAcceptTag())
    }

    @Test
    fun failedAttemptRequiresExplicitRetry() {
        val controller = NfcCaptureController()

        controller.tryAcceptTag()
        controller.fail()

        assertNull(controller.tryAcceptTag())
        assertTrue(controller.retry())
        assertFalse(controller.retry())
        assertEquals(NfcCaptureAction.WRITE_OR_VERIFY, controller.tryAcceptTag())
    }

    @Test
    fun lifecycleCancellationDuringIoResumesInVerificationOnlyMode() {
        val controller = NfcCaptureController()

        controller.tryAcceptTag()
        controller.cancelForLifecycle()

        assertEquals(NfcCaptureAction.VERIFY_ONLY, controller.tryAcceptTag())
    }
}
