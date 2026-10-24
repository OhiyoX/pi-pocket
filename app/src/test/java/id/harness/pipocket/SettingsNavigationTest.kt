package id.harness.pipocket

import org.junit.Assert.*
import org.junit.Test

class SettingsNavigationTest {
    @Test fun profileFromSettingsOpensImmediatelyAndBackReturnsToSettings() {
        val root = SettingsNavigation(SettingsPage.Home)
        val child = root.open(SettingsPage.AppProfile)
        assertEquals(SettingsPage.AppProfile, child.page)
        assertEquals(SettingsPage.Home, child.back().page)
        assertNull(child.back().back().page)
    }
    @Test fun directChatEntryReturnsToChatInsteadOfInventingSettingsParent() {
        val child = SettingsNavigation().open(SettingsPage.AppProfile, fromSettings = false)
        assertNull(child.parent)
        assertNull(child.back().page)
    }
    @Test fun switchingSettingsDestinationNeverStacksSiblingSheets() {
        val route = SettingsNavigation(SettingsPage.Home).open(SettingsPage.Providers).open(SettingsPage.Misc)
        assertEquals(SettingsPage.Misc, route.page)
        assertEquals(SettingsPage.Home, route.back().page)
        assertNull(route.back().back().page)
    }
    @Test fun everyChildHasAConsistentParentAndRootDoesNotLoop() {
        for (page in SettingsPage.entries) {
            val route = SettingsNavigation().open(page)
            assertEquals(if (page == SettingsPage.Home) null else SettingsPage.Home, route.parent)
        }
    }
}
