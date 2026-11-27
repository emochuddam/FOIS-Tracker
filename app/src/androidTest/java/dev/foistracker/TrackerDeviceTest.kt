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
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class TrackerDeviceTest {
    @get:Rule(order = 0)
    val notificationPermission: org.junit.rules.TestRule =
        if (android.os.Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)
        else org.junit.rules.TestRule { statement, _ -> statement }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Before
    fun chooseInitialTheme() {
        if (compose.onAllNodesWithText("Choose your theme").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithText("System").performClick()
        }
    }

    @Test fun savesThemeChoiceAcrossActivityRecreation() {
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Appearance and Motion").performClick()
        compose.onNodeWithText("Dark").performClick()
        compose.runOnIdle { assertEquals(ThemeMode.DARK, compose.activity.themeMode) }
        compose.activityRule.scenario.recreate()
        compose.runOnIdle {
            assertEquals(ThemeMode.DARK, compose.activity.themeMode)
            compose.activity.chooseTheme(ThemeMode.SYSTEM)
        }
    }

    @Test fun validatesFromHomeAndSearch() {
        compose.onNodeWithText("Search freight").performClick()
        compose.onNodeWithText("FNR enquiry").performClick()
        compose.onNodeWithText("11-digit FNR").performTextInput("123")
        compose.onNodeWithText("Search freight").performClick()
        compose.onNodeWithText("Enter exactly 11 digits for the FNR.").assertIsDisplayed()
    }

    @Test fun displaysFreightNumberAndOpensDetailsByDefault() {
        val fnr = "01234567890"
        compose.runOnIdle {
            compose.activity.page = ResultPage(Query(fnr),
                listOf(Freight(mapOf("FNR" to fnr, "Remaining distance" to "415", "Origin" to "RMN", "Destination" to "CFS",
                    "Last reported station" to "KOHL"))), true)
        }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(fnr))
        compose.onNodeWithText(fnr).assertExists()
        compose.onNodeWithText(fnr).performClick()
        compose.onNodeWithText("Movement").assertIsDisplayed()
        compose.onNodeWithText("415 km").assertIsDisplayed()
        compose.onNodeWithText("RMN → CFS").assertIsDisplayed()
        compose.onNodeWithText("Last reported station · KOHL (KOHLI)").assertIsDisplayed()
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
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Live refresh paused", substring = true))
        compose.onNodeWithText("Live refresh paused", substring = true).assertExists()
    }

    @Test fun addSavedFreightPrefillsTheEnteredNumber() {
        val fnr = "01234567890"
        compose.onNodeWithText("Settings").performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Storage"))
        compose.onNodeWithText("Storage").performClick()
        compose.onNodeWithText("Find saved freight").performTextInput(fnr)
        compose.onNodeWithText("Add saved freight").performClick()
        compose.onNodeWithText("FNR or station pair").assertTextContains(fnr)
        compose.onNodeWithText("Save").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
    }

    @Test fun removesSavedFreightFromTrackingScreen() {
        val item = Saved("01234567897", "Removal fixture", false, false)
        val store = compose.activity.services.store
        store.save(item)
        try {
            compose.onNodeWithText("Settings").performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Storage"))
        compose.onNodeWithText("Storage").performClick()
            compose.onNodeWithText("Find saved freight").performTextInput(item.key)
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Remove"))
            compose.onNodeWithText("Remove").performClick()
            compose.onNodeWithText("Remove freight").performClick()
            compose.waitUntil(5_000) { store.saved().none { it.key == item.key } }
        } finally { store.delete(item.key) }
    }

    @Test fun persistsSavedAndCachedFreightAndDeduplicatesAlerts() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val key = "01234567891"
        Store(context).use { first ->
            first.save(Saved(key, "Fixture freight", true, true))
            first.record(key, listOf(Freight(mapOf("FNR" to key, "Expected arrival" to "24-11-2026 12:00", "Origin" to "RMN"))))
            first.record(key, listOf(Freight(mapOf("FNR" to key, "Expected arrival" to "24-11-2026 14:00"))))
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

    @Test fun secureWindowAndTrackingToolbarAreEnabled() {
        val fnr = "01234567890"
        compose.runOnIdle {
            val activity = compose.activity
            assertTrue(activity.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE != 0)
            activity.page = ResultPage(Query(fnr), listOf(Freight(mapOf("FNR" to fnr))), true)
        }
        compose.onNodeWithText("BG OFF").assertIsDisplayed()
        compose.onNodeWithText("1 sec").performClick()
        compose.onNodeWithText("15 min").performClick()
        compose.onNodeWithText("Apply").performClick()
        compose.runOnIdle {
            assertEquals(900L, compose.activity.services.intervalSeconds.value)
            compose.activity.setSyncSeconds(1L)
        }
    }
}
