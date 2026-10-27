package dev.visiontest.cli

import dev.visiontest.android.Android
import dev.visiontest.ios.IOSManager
import dev.visiontest.tools.IOSAutomationToolRegistrar
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test

class ComponentHolderLifecycleTest {
    @Test fun `graph disposal destroys retained startup process`() = kotlinx.coroutines.test.runTest {
        val process = mockk<Process>(relaxed = true)
        io.mockk.every { process.isAlive } returns true
        val device = mockk<IOSManager>()
        io.mockk.coEvery { device.getFirstAvailableDevice() } returns dev.visiontest.common.MobileDevice(
            "booted", "iPhone", dev.visiontest.common.DeviceType.IOS_SIMULATOR, "Booted")
        val client = mockk<dev.visiontest.ios.IOSAutomationClient>()
        io.mockk.coEvery { client.isServerRunning() } returnsMany listOf(false, true)
        val discovery = mockk<dev.visiontest.discovery.ToolDiscovery>()
        io.mockk.every { discovery.findXctestrun() } returns "/tmp/test.xctestrun"
        io.mockk.every { discovery.findXcodeProject() } returns null
        val registrar = IOSAutomationToolRegistrar(device, client, discovery,
            org.slf4j.LoggerFactory.getLogger(javaClass), dev.visiontest.performance.TraceRecorder.Disabled,
            launchProcess = { process })
        registrar.startAutomationServer()
        registrar.close()
        registrar.close()
        verify(exactly = 1) { process.destroyForcibly() }
        kotlin.test.assertContains(registrar.stopAutomationServer(), "not running")
    }

    @Test fun `explicit close releases owned resources exactly once`() {
        val android = mockk<Android>(relaxed = true)
        val ios = mockk<IOSManager>(relaxed = true)
        val registrar = mockk<IOSAutomationToolRegistrar>(relaxed = true)
        val holder = ComponentHolder(android, ios, mockk(), mockk(), mockk(), mockk(), mockk(), registrar)
        holder.close()
        holder.close()
        verify(exactly = 1) { android.close() }
        verify(exactly = 1) { ios.close() }
        verify(exactly = 1) { registrar.close() }
    }
}
