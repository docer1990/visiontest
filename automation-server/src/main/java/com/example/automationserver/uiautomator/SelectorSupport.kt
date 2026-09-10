package com.example.automationserver.uiautomator

import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector

internal fun buildUiSelector(
    text: String? = null,
    textContains: String? = null,
    resourceId: String? = null,
    className: String? = null,
    contentDescription: String? = null,
): BySelector? {
    var selector: BySelector? = null
    text?.let { selector = selector?.text(it) ?: By.text(it) }
    textContains?.let { selector = selector?.textContains(it) ?: By.textContains(it) }
    resourceId?.let { selector = selector?.res(it) ?: By.res(it) }
    className?.let { selector = selector?.clazz(it) ?: By.clazz(it) }
    contentDescription?.let { selector = selector?.desc(it) ?: By.desc(it) }
    return selector
}

internal fun describeSelector(
    text: String? = null,
    textContains: String? = null,
    resourceId: String? = null,
    className: String? = null,
    contentDescription: String? = null,
): String = listOfNotNull(
    text?.let { "text=$it" },
    textContains?.let { "textContains=$it" },
    resourceId?.let { "resourceId=$it" },
    className?.let { "className=$it" },
    contentDescription?.let { "contentDescription=$it" },
).joinToString(", ")

internal fun buildUiSelector(selectors: TapOnElementSelectors): BySelector? = buildUiSelector(
    selectors.text,
    selectors.textContains,
    selectors.resourceId,
    selectors.className,
    selectors.contentDescription,
)

internal fun describeSelector(selectors: TapOnElementSelectors): String = describeSelector(
    selectors.text,
    selectors.textContains,
    selectors.resourceId,
    selectors.className,
    selectors.contentDescription,
)
