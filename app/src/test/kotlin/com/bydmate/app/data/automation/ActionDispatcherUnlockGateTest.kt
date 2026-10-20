package com.bydmate.app.data.automation

import com.bydmate.app.data.automation.ActionDispatcher.BlockReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Door-unlock speed gate: unlocking ("车门解锁") is forbidden above 30 km/h and
 * when speed is unknown (fail-closed, same policy as the frunk gate). Locking
 * ("车门上锁") is never gated.
 */
class ActionDispatcherUnlockGateTest {

    private fun gate(cmd: String, speed: Int?) = ActionDispatcher.unlockGateBlockReason(cmd, speed, maxKmh = 30)

    @Test fun `unlock predicate matches canonical command`() {
        assertTrue(ActionDispatcher.isDoorUnlockCommand("车门解锁"))
    }

    @Test fun `lock command is not an unlock`() {
        assertFalse(ActionDispatcher.isDoorUnlockCommand("车门上锁"))
    }

    @Test fun `unlock allowed when parked`() {
        assertNull(gate("车门解锁", 0))
    }

    @Test fun `unlock allowed at 30 kmh boundary`() {
        assertNull(gate("车门解锁", 30))
    }

    @Test fun `unlock blocked above 30 kmh`() {
        assertEquals(BlockReason.UnlockSpeed(31, 30), gate("车门解锁", 31))
        assertEquals(BlockReason.UnlockSpeed(90, 30), gate("车门解锁", 90))
    }

    @Test fun `unlock limit follows the chosen setting`() {
        assertNull(ActionDispatcher.unlockGateBlockReason("车门解锁", 15, maxKmh = 15))
        assertEquals(BlockReason.UnlockSpeed(16, 15), ActionDispatcher.unlockGateBlockReason("车门解锁", 16, maxKmh = 15))
        assertEquals(BlockReason.UnlockSpeed(11, 10), ActionDispatcher.unlockGateBlockReason("车门解锁", 11, maxKmh = 10))
    }

    @Test fun `unlock blocked when speed unknown`() {
        assertEquals(BlockReason.UnlockSpeedUnknown, gate("车门解锁", null))
    }

    @Test fun `lock is never gated even at speed or unknown speed`() {
        assertNull(gate("车门上锁", 120))
        assertNull(gate("车门上锁", null))
    }

    @Test fun `unrelated commands pass through`() {
        assertNull(gate("车窗全开", 200))
    }
}
