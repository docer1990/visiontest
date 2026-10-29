package dev.visiontest

import dev.visiontest.daemon.CliRoute
import dev.visiontest.daemon.classifyCliArguments
import kotlin.test.Test
import kotlin.test.assertEquals

class MainDispatchTest {

    @Test
    fun `empty args route to the MCP server`() {
        assertEquals(CliRoute.MCP, classifyCliArguments(emptyArray()).route)
    }

    @Test
    fun `explicit serve subcommand routes to the MCP server`() {
        assertEquals(CliRoute.MCP, classifyCliArguments(arrayOf("serve")).route)
    }

    @Test
    fun `serve with additional args still routes to the MCP server`() {
        assertEquals(CliRoute.MCP, classifyCliArguments(arrayOf("serve", "--ignored")).route)
    }

    @Test
    fun `unknown first arg routes to the CLI`() {
        assertEquals(CliRoute.DAEMON, classifyCliArguments(
            arrayOf("tap_by_coordinates", "--platform", "android", "100", "200")).route)
    }

    @Test
    fun `help flag routes to the CLI`() {
        assertEquals(CliRoute.LOCAL, classifyCliArguments(arrayOf("--help")).route)
    }

    @Test
    fun `any non-serve token routes to the CLI`() {
        assertEquals(CliRoute.DAEMON, classifyCliArguments(arrayOf("screenshot")).route)
    }
}
