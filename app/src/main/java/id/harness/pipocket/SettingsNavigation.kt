package id.harness.pipocket

enum class SettingsPage { Home, Connection, AppProfile, Providers, Appearance, Misc, Updates }

/** Exactly one settings surface, with an explicit return destination. */
data class SettingsNavigation(val page: SettingsPage? = null, val parent: SettingsPage? = null) {
    fun open(page: SettingsPage, fromSettings: Boolean = true) = SettingsNavigation(
        page, if (page != SettingsPage.Home && fromSettings) SettingsPage.Home else null,
    )
    fun back() = SettingsNavigation(parent)
}
