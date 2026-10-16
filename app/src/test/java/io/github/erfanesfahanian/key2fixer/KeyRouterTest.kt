package io.github.erfanesfahanian.key2fixer

import io.github.erfanesfahanian.key2fixer.keys.BuiltinAction
import io.github.erfanesfahanian.key2fixer.keys.KeyAction
import io.github.erfanesfahanian.key2fixer.keys.KeyCodes
import io.github.erfanesfahanian.key2fixer.keys.KeyConfig
import io.github.erfanesfahanian.key2fixer.keys.KeyInput
import io.github.erfanesfahanian.key2fixer.keys.KeyRouter
import io.github.erfanesfahanian.key2fixer.keys.Scheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyRouterTest {
    /** Manually advanced clock for delayed tasks. */
    private class FakeScheduler : Scheduler {
        var now = 0L
        private val tasks = mutableListOf<Triple<Long, Any, () -> Unit>>()
        override fun postDelayed(delayMillis: Long, task: () -> Unit): Any {
            val token = Any()
            tasks.add(Triple(now + delayMillis, token, task))
            return token
        }
        override fun cancel(token: Any) {
            tasks.removeAll { it.second === token }
        }
        fun advance(ms: Long) {
            now += ms
            val due = tasks.filter { it.first <= now }
            tasks.removeAll(due)
            due.forEach { it.third() }
        }
    }

    private val sched = FakeScheduler()
    private val performed = mutableListOf<KeyAction>()
    private val unassigned = mutableListOf<Char>()
    private val router = KeyRouter(sched, { performed.add(it) }, { unassigned.add(it) })

    private val camera = KeyAction.Launch("org.lineageos.aperture")
    private val torch = KeyAction.Builtin(BuiltinAction.TORCH)
    private val home = KeyAction.Builtin(BuiltinAction.HOME)

    private fun down(code: Int, repeat: Int = 0) = KeyInput(true, code, repeat)
    private fun up(code: Int) = KeyInput(false, code, 0)
    private val keyC = KeyCodes.A + 2
    private val keyT = KeyCodes.A + 19

    @Test
    fun speedKeyPlusLetterLaunchesShortcutAndSwallowsKeys() {
        val cfg = KeyConfig(letterTap = mapOf('C' to camera))
        assertTrue(router.onKey(down(KeyCodes.SHIFT_RIGHT), cfg, false))
        assertTrue(router.onKey(down(keyC), cfg, false))
        assertEquals(listOf<KeyAction>(camera), performed)
        assertTrue(router.onKey(up(KeyCodes.SHIFT_RIGHT), cfg, false))
        assertTrue("letter release after Speed Key release is still swallowed", router.onKey(up(keyC), cfg, false))
        assertFalse("plain letters type normally", router.onKey(down(keyC), cfg, false))
    }

    @Test
    fun holdingTheLetterRunsTheHoldShortcut() {
        val cfg = KeyConfig(letterTap = mapOf('T' to camera), letterHold = mapOf('T' to torch))
        router.onKey(down(KeyCodes.FUNCTION), cfg, false)
        router.onKey(down(keyT), cfg, false)
        sched.advance(500)
        router.onKey(down(keyT, repeat = 1), cfg, false)
        router.onKey(up(keyT), cfg, false)
        assertEquals(listOf<KeyAction>(torch), performed)
    }

    @Test
    fun tappingTheLetterWithAHoldActionRunsTapOnRelease() {
        val cfg = KeyConfig(letterTap = mapOf('T' to camera), letterHold = mapOf('T' to torch))
        router.onKey(down(KeyCodes.SHIFT_RIGHT), cfg, false)
        router.onKey(down(keyT), cfg, false)
        sched.advance(100)
        assertTrue(performed.isEmpty())
        router.onKey(up(keyT), cfg, false)
        sched.advance(1000)
        assertEquals(listOf<KeyAction>(camera), performed)
    }

    @Test
    fun holdOnlyLetterIgnoresShortTapAndRunsOnHold() {
        val copy = KeyAction.Builtin(BuiltinAction.COPY)
        val cfg = KeyConfig(letterHold = mapOf('C' to copy))
        router.onKey(down(KeyCodes.SHIFT_RIGHT), cfg, false)
        assertTrue(router.onKey(down(keyC), cfg, false))
        assertTrue(router.onKey(up(keyC), cfg, false))
        sched.advance(1000)
        assertTrue("a short tap runs nothing and is not 'unassigned'", performed.isEmpty() && unassigned.isEmpty())
        router.onKey(down(keyC), cfg, false)
        sched.advance(500)
        router.onKey(up(keyC), cfg, false)
        assertEquals(listOf<KeyAction>(copy), performed)
    }

    @Test
    fun unassignedLetterIsReported() {
        router.onKey(down(KeyCodes.SHIFT_RIGHT), KeyConfig(), false)
        assertTrue(router.onKey(down(keyC), KeyConfig(), false))
        assertEquals(listOf('C'), unassigned)
        assertTrue(performed.isEmpty())
    }

    @Test
    fun speedKeyTapAloneRunsItsAction() {
        val cfg = KeyConfig(speedTapAction = home)
        router.onKey(down(KeyCodes.SHIFT_RIGHT), cfg, false)
        router.onKey(up(KeyCodes.SHIFT_RIGHT), cfg, false)
        assertEquals(listOf<KeyAction>(home), performed)
        performed.clear()
        router.onKey(down(KeyCodes.SHIFT_RIGHT), cfg.copy(letterTap = mapOf('C' to camera)), false)
        router.onKey(down(keyC), cfg.copy(letterTap = mapOf('C' to camera)), false)
        router.onKey(up(keyC), cfg, false)
        router.onKey(up(KeyCodes.SHIFT_RIGHT), cfg, false)
        assertEquals("used as a modifier, so no tap action", listOf<KeyAction>(camera), performed)
    }

    @Test
    fun passThroughWhileTypingLeavesKeysAlone() {
        val cfg = KeyConfig(letterTap = mapOf('C' to camera), speedPassThroughWhileTyping = true)
        assertFalse(router.onKey(down(KeyCodes.SHIFT_RIGHT), cfg, true))
        assertFalse(router.onKey(down(keyC), cfg, true))
        assertFalse(router.onKey(up(keyC), cfg, true))
        assertFalse(router.onKey(up(KeyCodes.SHIFT_RIGHT), cfg, true))
        assertTrue(performed.isEmpty())
    }

    @Test
    fun externalKeyboardsAreIgnored() {
        assertFalse(router.onKey(KeyInput(true, KeyCodes.SHIFT_RIGHT, 0, isExternal = true), KeyConfig(), false))
    }

    @Test
    fun convenienceKeyTapActsOnPressWhenNothingElseIsAssigned() {
        val cfg = KeyConfig(convenienceTap = torch)
        assertTrue(router.onKey(down(KeyCodes.ASSIST), cfg, false))
        assertEquals(listOf<KeyAction>(torch), performed)
        assertTrue(router.onKey(up(KeyCodes.ASSIST), cfg, false))
        assertEquals(1, performed.size)
    }

    @Test
    fun convenienceKeyHoldAndDoublePress() {
        val cfg = KeyConfig(convenienceTap = torch, convenienceHold = camera, convenienceDouble = home)
        // hold
        router.onKey(down(KeyCodes.ASSIST), cfg, false)
        sched.advance(600)
        router.onKey(up(KeyCodes.ASSIST), cfg, false)
        assertEquals(listOf<KeyAction>(camera), performed)
        performed.clear()
        // double press
        router.onKey(down(KeyCodes.ASSIST), cfg, false)
        router.onKey(up(KeyCodes.ASSIST), cfg, false)
        sched.advance(100)
        router.onKey(down(KeyCodes.ASSIST), cfg, false)
        router.onKey(up(KeyCodes.ASSIST), cfg, false)
        sched.advance(1000)
        assertEquals(listOf<KeyAction>(home), performed)
        performed.clear()
        // single press waits for the double-press window
        router.onKey(down(KeyCodes.ASSIST), cfg, false)
        router.onKey(up(KeyCodes.ASSIST), cfg, false)
        assertTrue(performed.isEmpty())
        sched.advance(400)
        assertEquals(listOf<KeyAction>(torch), performed)
    }

    @Test
    fun disabledFeaturesPassEverythingThrough() {
        val cfg = KeyConfig(speedEnabled = false, convenienceEnabled = false)
        assertFalse(router.onKey(down(KeyCodes.SHIFT_RIGHT), cfg, false))
        assertFalse(router.onKey(down(keyC), cfg, false))
        assertFalse(router.onKey(down(KeyCodes.ASSIST), cfg, false))
    }

    @Test
    fun actionCodecRoundTrips() {
        val all = listOf(KeyAction.None, camera) + BuiltinAction.entries.map { KeyAction.Builtin(it) }
        for (a in all) assertEquals(a, KeyAction.decode(a.encode()))
        assertEquals(KeyAction.None, KeyAction.decode("garbage"))
        assertEquals(KeyAction.None, KeyAction.decode(null))
        assertEquals(KeyAction.None, KeyAction.decode("app:"))
    }
}
