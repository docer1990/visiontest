package com.example.visiontest

import kotlin.test.Test
import kotlin.test.assertEquals

class MainDispatchTest {

    @Test
    fun `empty args route to the MCP server`() {
        assertEquals(Route.McpServer, route(emptyArray()))
    }

    @Test
    fun `explicit serve subcommand routes to the MCP server`() {
        assertEquals(Route.McpServer, route(arrayOf("serve")))
    }

    @Test
    fun `serve with additional args still routes to the MCP server`() {
        assertEquals(Route.McpServer, route(arrayOf("serve", "--ignored")))
    }

    @Test
    fun `unknown first arg routes to the CLI`() {
        assertEquals(Route.Cli, route(arrayOf("tap_by_coordinates", "--platform", "android", "100", "200")))
    }

    @Test
    fun `help flag routes to the CLI`() {
        assertEquals(Route.Cli, route(arrayOf("--help")))
    }

    @Test
    fun `any non-serve token routes to the CLI`() {
        assertEquals(Route.Cli, route(arrayOf("screenshot")))
    }
}
