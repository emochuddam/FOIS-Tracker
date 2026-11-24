package dev.foistracker

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import androidx.test.rule.GrantPermissionRule
import org.junit.Rule
import org.junit.Test

class TrackerDeviceTest {
    @get:Rule(order = 0)
    val notificationPermission: org.junit.rules.TestRule =
        if (android.os.Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)
        else org.junit.rules.TestRule { statement, _ -> statement }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test fun validatesFromHomeAndSearch() {
        compose.onNodeWithText("FNR or station pair").performTextInput("123")
        compose.onNodeWithText("Search freight").performClick()
        compose.onNodeWithText("Enter an 11-digit FNR or two different station codes, such as RMN CFS.").assertIsDisplayed()
        compose.onNodeWithText("Search").performClick()
        compose.onNodeWithText("Search freight").performClick()
        compose.onNodeWithText("Enter an 11-digit FNR or two different station codes, such as RMN CFS.").assertIsDisplayed()
    }

    @Test fun displaysFreightNumberAndOpensDetailsByDefault() {
        val fnr = "01234567890"
        compose.runOnIdle {
            compose.activity.page = ResultPage(Query(fnr),
                listOf(Freight(mapOf("FNR" to fnr, "Remaining distance" to "415", "Origin" to "RMN", "Destination" to "CFS"))), true)
        }
        compose.onNodeWithText("FNR · $fnr").assertIsDisplayed()
        compose.onAllNodesWithText("415 km")[0].assertIsDisplayed()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("RMN → CFS"))
        compose.onNodeWithText("RMN → CFS").assertIsDisplayed()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Collapse"))
        compose.onNodeWithText("Collapse").assertIsDisplayed()
    }

    @Test fun unavailableAutomaticRefreshStaysInline() {
        val query = Query("01234567890")
        compose.runOnIdle {
            val app = compose.activity
            app.page = ResultPage(query, listOf(Freight(mapOf("FNR" to query.key))), true)
            app.refresh(query, automatic = true)
            assertNull(app.challenge)
            assertNull(app.message)
            assertTrue(app.refreshIssue?.second.orEmpty().startsWith("Live refresh paused"))
        }
        compose.onNodeWithText("Live refresh paused", substring = true).assertIsDisplayed()
    }

    @Test fun addSavedFreightPrefillsTheEnteredNumber() {
        val fnr = "01234567890"
        compose.onNodeWithText("Tracking").performClick()
        compose.onNodeWithText("Find saved freight").performTextInput(fnr)
        compose.onNodeWithText("Add saved freight").performClick()
        compose.onNodeWithText("FNR or station pair").assertTextContains(fnr)
        compose.onNodeWithText("Save").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
    }

    @Test fun persistsSavedAndCachedFreightAndDeduplicatesAlerts() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val key = "01234567891"
        Store(context).use { first ->
            first.save(Saved(key, "Fixture freight", true, true))
            first.record(key, listOf(Freight(mapOf("FNR" to key, "Expected arrival" to "24-11-2026 12:00", "Origin" to "RMN"))))
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
            assertEquals("RMN", second.cached(key).single().get("Origin"))
            second.delete(key)
            second.clearAlert(key, "TEST")
        }
    }

    @Test fun backgroundLiveActionIsVisibleOnGuestResults() {
        val fnr = "01234567890"
        compose.runOnIdle {
            compose.activity.page = ResultPage(Query(fnr), listOf(Freight(mapOf("FNR" to fnr))), true)
        }
        compose.onNodeWithText("Keep live in background").assertIsDisplayed()
    }
}
