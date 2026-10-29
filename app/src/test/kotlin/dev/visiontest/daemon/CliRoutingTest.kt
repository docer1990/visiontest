package dev.visiontest.daemon

import kotlin.test.Test
import kotlin.test.assertEquals

class CliRoutingTest {
    @Test fun `entry sentinels stay local`() {
        assertEquals(CliRoute.MCP, classifyCliArguments(emptyArray()).route)
        assertEquals(CliRoute.MCP, classifyCliArguments(arrayOf("serve")).route)
        assertEquals(CliRoute.HOST, classifyCliArguments(arrayOf("--internal-daemon-host")).route)
        listOf("--help", "-h", "--version", "init").forEach {
            assertEquals(CliRoute.LOCAL, classifyCliArguments(arrayOf(it)).route)
        }
    }
    @Test fun `root switches do not reinterpret typed values`() {
        val args = arrayOf("--standalone", "input_text", "-p", "ios", "--", "--standalone")
        val result = classifyCliArguments(args)
        assertEquals(CliRoute.STANDALONE, result.route)
        assertEquals(args.drop(1), result.arguments)
        assertEquals(CliRoute.DAEMON, classifyCliArguments(args.drop(1).toTypedArray()).route)
        assertEquals(CliRoute.DAEMON, classifyCliArguments(arrayOf("--trace-performance", "--standalone", "tap")).route)
        assertEquals(CliRoute.DAEMON, classifyCliArguments(arrayOf("--", "--standalone")).route)
    }
    @Test fun `lifecycle and unknown commands preserve arguments`() {
        assertEquals(CliRoute.LIFECYCLE, classifyCliArguments(arrayOf("daemon", "status")).route)
        assertEquals(CliRoute.DAEMON, classifyCliArguments(arrayOf("unknown", "--help")).route)
    }
    @Test fun `root argument files require local authoritative parsing`() {
        listOf(
            arrayOf("@root.args"),
            arrayOf("--trace-performance", "trace.jsonl", "@root.args"),
            arrayOf("@root.args", "--standalone"),
            arrayOf("--standalone", "@root.args"),
        ).forEach { args ->
            val result = classifyCliArguments(args)
            assertEquals(CliRoute.LOCAL, result.route)
            assertEquals(args.toList(), result.arguments)
        }
    }
    @Test fun `argument file detection respects command values terminator and escaping`() {
        listOf(
            arrayOf("input_text", "@root.args"),
            arrayOf("--", "@root.args"),
            arrayOf("@@root.args"),
            arrayOf("--trace-performance", "@trace.jsonl", "tap"),
        ).forEach { args ->
            val result = classifyCliArguments(args)
            assertEquals(CliRoute.DAEMON, result.route)
            assertEquals(args.toList(), result.arguments)
        }
    }
}
