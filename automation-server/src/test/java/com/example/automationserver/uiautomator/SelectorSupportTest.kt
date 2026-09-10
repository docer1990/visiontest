package com.example.automationserver.uiautomator

import org.junit.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SelectorSupportTest {
    @Test
    fun `selector builder requires at least one selector`() {
        assertNull(buildUiSelector())
        assertNotNull(buildUiSelector(resourceId = "menu"))
    }

    @Test
    fun `selector description uses the first configured selector`() {
        val description = describeSelector(text = "Menu", resourceId = "menu")

        kotlin.test.assertEquals("text=Menu, resourceId=menu", description)
    }
}
