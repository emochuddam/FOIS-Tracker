package dev.foistracker

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream

class TrackerDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun validatesInvalidFNRAndProvidesThemeSettings() {
        compose.onNodeWithText("FNR or station pair").performTextInput("123")
        compose.onNodeWithText("Search freight").performClick()
        compose.onNodeWithText("Enter an 11-digit FNR or two different station codes, such as RMN CFS.").assertIsDisplayed()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("AMOLED").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("AMOLED", compose.activity.theme) }
        compose.onNodeWithText("System").performClick()
    }

    @Test fun persistsSavedAndCachedFreightAndDeduplicatesAlerts() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val key = "01234567891"
        Store(context).use { first ->
            first.save(Saved(key, "Fixture freight", true, true))
            first.record(key, listOf(Freight(mapOf("FNR" to key, "Expected arrival" to "24-11-2026 12:00"))))
            first.record(key, listOf(Freight(mapOf("FNR" to key, "Expected arrival" to "24-11-2026 14:00"))))
            assertTrue(first.registerAlert(key, "TEST", "fixture"))
            assertFalse(first.registerAlert(key, "TEST", "fixture"))
            first.pause(key)
        }
        Store(context).use { second ->
            val saved = second.saved().first { it.key == key }
            assertTrue(saved.favorite)
            assertFalse(saved.tracking)
            assertEquals("24-11-2026 12:00", second.cached(key).single().previousEta)
            second.delete(key)
            second.clearAlert(key, "TEST")
        }
    }

    @Test fun buildsValidPdfHeader() {
        val bytes = ByteArrayOutputStream()
        Reports.write(ReportFormat.PDF, Freight(mapOf("FNR" to "01234567890")), bytes)
        assertTrue(bytes.toByteArray().take(5).toByteArray().decodeToString().startsWith("%PDF"))
        assertTrue(bytes.size() > 1000)
    }
}
