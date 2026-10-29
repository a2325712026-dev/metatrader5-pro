package com.morris.mttpsl

import com.morris.mttpsl.ui.SupportInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class V16Test {
    @Test fun donationAddressExact() {
        assertEquals("0xB5f7e22fa69b5D88eeCc238A767b582d18CFF2bF", SupportInfo.DONATION_ADDRESS)
        assertEquals(42, SupportInfo.DONATION_ADDRESS.length)
    }
    @Test fun telegramIsHttps() {
        assertEquals("https://t.me/metatrader5pro_chat_group", SupportInfo.TELEGRAM_URL)
        assertTrue(SupportInfo.TELEGRAM_URL.startsWith("https://"))
    }
}
