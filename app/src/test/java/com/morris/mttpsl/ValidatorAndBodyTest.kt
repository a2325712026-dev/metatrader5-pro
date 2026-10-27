package com.morris.mttpsl

import com.morris.mttpsl.api.PendingType
import com.morris.mttpsl.api.Side
import com.morris.mttpsl.api.SymbolSpec
import com.morris.mttpsl.api.orderBody
import com.morris.mttpsl.domain.TpSlValidator
import org.junit.Assert.*
import org.junit.Test

class ValidatorAndBodyTest {
    private val spec = SymbolSpec("EURUSD", 5, 0.00001, 0)

    @Test fun orderBodyCarriesTpSl() {
        val b = orderBody("EURUSD", Side.BUY, 0.01, 1.09850, 1.10200, "x")
        assertEquals(1.10200, b.getDouble("tp"), 1e-9); assertEquals(1.09850, b.getDouble("sl"), 1e-9)
        assertEquals("BUY", b.getString("side"))
        val none = orderBody("EURUSD", Side.SELL, 0.01, null, null)
        assertEquals(0.0, none.getDouble("tp"), 0.0); assertEquals(0.0, none.getDouble("sl"), 0.0)
    }

    @Test fun marketTpSlSides() {
        assertNull(TpSlValidator.validate(Side.BUY, 1.1, 1.10008, 1.102, 1.098, spec))
        assertNotNull(TpSlValidator.validate(Side.BUY, 1.1, 1.10008, 1.098, null, spec))
        assertNull(TpSlValidator.validate(Side.SELL, 1.1, 1.10008, 1.098, 1.102, spec))
        assertNotNull(TpSlValidator.validate(Side.SELL, 1.1, 1.10008, null, 1.0999, spec))
    }

    @Test fun pendingRules() {
        val bid = 1.10000; val ask = 1.10008
        assertNull(TpSlValidator.validatePending(PendingType.BUY_LIMIT, bid, ask, 1.098, null, 1.1, 1.097, spec))
        assertNotNull(TpSlValidator.validatePending(PendingType.BUY_LIMIT, bid, ask, 1.101, null, null, null, spec))
        assertNull(TpSlValidator.validatePending(PendingType.SELL_LIMIT, bid, ask, 1.102, null, null, null, spec))
        assertNotNull(TpSlValidator.validatePending(PendingType.SELL_LIMIT, bid, ask, 1.099, null, null, null, spec))
        assertNull(TpSlValidator.validatePending(PendingType.BUY_STOP, bid, ask, 1.102, null, null, null, spec))
        assertNotNull(TpSlValidator.validatePending(PendingType.SELL_STOP, bid, ask, 1.101, null, null, null, spec))
        assertNull(TpSlValidator.validatePending(PendingType.BUY_STOP_LIMIT, bid, ask, 1.102, 1.101, null, null, spec))
        assertNotNull(TpSlValidator.validatePending(PendingType.BUY_STOP_LIMIT, bid, ask, 1.102, 1.103, null, null, spec))
        assertNotNull(TpSlValidator.validatePending(PendingType.SELL_STOP_LIMIT, bid, ask, 1.098, null, null, null, spec))
    }
}
