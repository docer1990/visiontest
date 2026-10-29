package dev.visiontest.common

import dev.visiontest.android.AutomationClient
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JsonRpcMutationEvidenceTest {
    @Test
    fun `malformed mutation responses retain uncertain evidence in both transports`() = runBlocking {
        val responses = listOf("not json", "{}", """{"result":{"success":"true"}}""",
            """{"result":{"success":true,"error":{}}}""", """{"error":{"message":"bad"}}""")
        for (budgeted in listOf(false, true)) for (response in responses) {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody(response))
                server.start()
                val evidence = DispatchEvidence()
                val context: CoroutineContext = if (budgeted) evidence + RequestDeadline(5000) else evidence
                val returned = withContext(context) { AutomationClient(server.hostName, server.port).pressHome() }
                assertEquals(response, returned)
                assertTrue(evidence.uncertain, "budgeted=$budgeted response=$response")
                assertTrue(evidence.transportFailed)
            }
        }
    }

    @Test
    fun `validated mutation outcomes remain known in both transports`() = runBlocking {
        val responses = listOf("""{"result":{"success":true}}""",
            """{"result":{"success":false,"error":"blocked"}}""",
            """{"error":{"code":-32000,"message":"blocked"}}""")
        for (budgeted in listOf(false, true)) for (response in responses) {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody(response))
                server.start()
                val evidence = DispatchEvidence()
                val context: CoroutineContext = if (budgeted) evidence + RequestDeadline(5000) else evidence
                withContext(context) { AutomationClient(server.hostName, server.port).pressHome() }
                assertFalse(evidence.pending)
                assertFalse(evidence.uncertain)
                assertFalse(evidence.transportFailed)
            }
        }
    }

    @Test
    fun `lost response never replays a fully received mutation in either transport`() = runBlocking {
        for (budgeted in listOf(false, true)) {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
                server.enqueue(MockResponse().setBody("""{"result":{"success":true}}"""))
                server.start()
                val evidence = DispatchEvidence()
                val context: CoroutineContext = if (budgeted) evidence + RequestDeadline(5000) else evidence
                val outcome = runCatching {
                    withContext(context) { AutomationClient(server.hostName, server.port).pressHome() }
                }
                assertNotNull(server.takeRequest())
                assertTrue(outcome.isFailure, "budgeted=$budgeted")
                assertEquals(1, server.requestCount)
                assertTrue(evidence.uncertain)
            }
        }
    }
}
