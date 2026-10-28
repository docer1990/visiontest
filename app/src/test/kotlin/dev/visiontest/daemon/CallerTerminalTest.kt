package dev.visiontest.daemon

import dev.visiontest.cli.CliTerminalDimensions
import kotlin.test.Test
import kotlin.test.assertEquals

class CallerTerminalTest {
    @Test fun `output interaction is independent from redirected input`() {
        assertEquals(true, callerOutputInteractive(consoleInteractive = false) { true })
        assertEquals(false, callerOutputInteractive(consoleInteractive = false) { false })
        assertEquals(true, callerOutputInteractive(consoleInteractive = true) { error("unexpected probe") })
        assertEquals(CliTerminalDimensions(40, 24, true),
            callerTerminalDimensions(mapOf("COLUMNS" to "40"), interactive = false, outputInteractive = true) {
                error("unexpected size probe")
            })
    }

    @Test fun `redirected caller never probes a terminal`() {
        assertEquals(CliTerminalDimensions(79, 24), callerTerminalDimensions(emptyMap(), interactive = false) {
            error("noninteractive request spawned a probe")
        })
    }

    @Test fun `interactive caller captures fresh dimensions`() {
        assertEquals(CliTerminalDimensions(40, 30), callerTerminalDimensions(emptyMap(), true) { "30 40\n" })
        assertEquals(CliTerminalDimensions(100, 50), callerTerminalDimensions(emptyMap(), true) { "50 100\n" })
    }

    @Test fun `caller environment supplies dimensions when terminal size is unavailable`() {
        assertEquals(CliTerminalDimensions(60, 20),
            callerTerminalDimensions(mapOf("COLUMNS" to "60", "LINES" to "20"), false) { error("unexpected probe") })
    }

    @Test fun `invalid dimensions and unavailable probe use bounded defaults`() {
        for (result in listOf(null, "garbage", "0 -1", "9999999999999999 10001")) {
            assertEquals(CliTerminalDimensions(79, 24), callerTerminalDimensions(emptyMap(), true) { result })
        }
    }
}
