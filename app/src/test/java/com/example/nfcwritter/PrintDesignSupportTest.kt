package com.example.nfcwritter

import com.example.nfcwritter.data.PrintDesignSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PrintDesignSupportTest {
    @Test
    fun relativeStaffUrl_resolvesAgainstConfiguredServer() {
        assertEquals(
            "https://nextap.pythonanywhere.com/api/seller/print-designs/44/pdf/",
            PrintDesignSupport.resolveSameServerUrl(
                "https://nextap.pythonanywhere.com/",
                "/api/seller/print-designs/44/pdf/",
            ),
        )
    }

    @Test
    fun externalUrl_isRejectedToProtectAuthenticationToken() {
        assertNull(
            PrintDesignSupport.resolveSameServerUrl(
                "https://nextap.pythonanywhere.com/",
                "https://example.com/design.pdf",
            ),
        )
    }

    @Test
    fun filename_isSafeAndAlwaysPdf() {
        assertEquals(
            "Nadia-Card-front-back.pdf",
            PrintDesignSupport.fileName("attachment; filename=\"Nadia Card front back.pdf\"", "NX/0044"),
        )
        assertEquals(
            "NexTap-NX-0044-front-back.pdf",
            PrintDesignSupport.fileName(null, "NX/0044"),
        )
    }
}
