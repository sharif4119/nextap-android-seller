package com.example.nfcwritter.nfc

import java.util.concurrent.atomic.AtomicReference

enum class NfcCaptureState {
    WAITING,
    PROCESSING,
    WAITING_FOR_VERIFICATION,
    ERROR,
    COMPLETE,
    CANCELLED,
}

enum class NfcCaptureAction { WRITE_OR_VERIFY, VERIFY_ONLY }

/** Thread-safe gate for Reader Mode callbacks and explicit retry transitions. */
class NfcCaptureController {
    private val current = AtomicReference(NfcCaptureState.WAITING)

    val state: NfcCaptureState get() = current.get()

    fun tryAcceptTag(): NfcCaptureAction? {
        while (true) {
            val before = current.get()
            val action = when (before) {
                NfcCaptureState.WAITING -> NfcCaptureAction.WRITE_OR_VERIFY
                NfcCaptureState.WAITING_FOR_VERIFICATION -> NfcCaptureAction.VERIFY_ONLY
                else -> return null
            }
            if (current.compareAndSet(before, NfcCaptureState.PROCESSING)) return action
        }
    }

    fun needsVerificationRetap() {
        current.compareAndSet(NfcCaptureState.PROCESSING, NfcCaptureState.WAITING_FOR_VERIFICATION)
    }

    fun fail() {
        current.set(NfcCaptureState.ERROR)
    }

    fun retry(): Boolean = current.compareAndSet(NfcCaptureState.ERROR, NfcCaptureState.WAITING)

    fun complete() {
        current.set(NfcCaptureState.COMPLETE)
    }

    fun restart() {
        current.set(NfcCaptureState.WAITING)
    }

    fun cancelForLifecycle() {
        current.updateAndGet { state ->
            if (state == NfcCaptureState.PROCESSING) NfcCaptureState.WAITING_FOR_VERIFICATION
            else NfcCaptureState.CANCELLED
        }
    }

    fun resume() {
        current.compareAndSet(NfcCaptureState.CANCELLED, NfcCaptureState.WAITING)
    }
}
