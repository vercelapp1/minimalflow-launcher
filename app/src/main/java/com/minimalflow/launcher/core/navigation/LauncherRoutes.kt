package com.minimalflow.launcher.core.navigation

/**
 * The contract between "something asked for a destination" and "something shows
 * it".
 *
 * A home screen gesture, a widget tap, a shortcut and a notification action can
 * all arrive from outside the navigation graph, so they cannot be expressed as a
 * typed route. They travel as an intent extra instead, using these constants.
 *
 * Both sides must agree, which is why the strings live here rather than in either
 * the dispatcher or the navigation graph.
 */
object LauncherRoutes {

    /** Extra carried by the settings activity's intent. */
    const val EXTRA_DESTINATION = "com.minimalflow.launcher.extra.DESTINATION"

    const val DESTINATION_HOME = "home"
    const val DESTINATION_APPS = "apps"
    const val DESTINATION_APPEARANCE = "appearance"
    const val DESTINATION_ICONS = "icons"
    const val DESTINATION_GESTURES = "gestures"
    const val DESTINATION_WIDGETS = "widgets"
    const val DESTINATION_BACKUP = "backup"
    const val DESTINATION_HIDDEN = "hidden-apps"
    const val DESTINATION_PRIVACY = "privacy"
    const val DESTINATION_ABOUT = "about"

    /** Extra carrying an initial search query, if the caller has one. */
    const val EXTRA_SEARCH_QUERY = "com.minimalflow.launcher.extra.SEARCH_QUERY"

    /** Every route the activity accepts, used to reject unknown extras. */
    val all: Set<String> = setOf(
        DESTINATION_HOME,
        DESTINATION_APPS,
        DESTINATION_APPEARANCE,
        DESTINATION_ICONS,
        DESTINATION_GESTURES,
        DESTINATION_WIDGETS,
        DESTINATION_BACKUP,
        DESTINATION_HIDDEN,
        DESTINATION_PRIVACY,
        DESTINATION_ABOUT,
    )

    /** Returns the extra's value when it is a real route, otherwise null. */
    fun normalise(value: String?): String? = value?.takeIf { it in all }
}
