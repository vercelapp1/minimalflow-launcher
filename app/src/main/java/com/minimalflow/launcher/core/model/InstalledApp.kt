package com.minimalflow.launcher.core.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/**
 * A stable, serialisation-friendly reference to a launchable activity.
 *
 * The framework [android.content.ComponentName] is deliberately avoided so that
 * every model class stays a pure Kotlin type and can be unit tested on the JVM
 * without Robolectric.
 *
 * @param activityClassName `null` means "any launcher activity of this package",
 *   which is what favourites, hidden apps and aliases store. It keeps a
 *   favourite working when an app ships a new default activity.
 * @param userSerial serial number of the [android.os.UserHandle] the app lives
 *   in. `0` is the primary user; work profiles get their own serial.
 */
@Immutable
@Serializable
data class AppKey(
    val packageName: String,
    val activityClassName: String? = null,
    val userSerial: Long = PRIMARY_USER,
) {
    /** True when this key refers to a whole package rather than one activity. */
    val isPackageWide: Boolean get() = activityClassName == null

    /** True when two keys can refer to the same app for user-facing purposes. */
    fun matches(other: AppKey): Boolean {
        if (packageName != other.packageName) return false
        if (userSerial != other.userSerial) return false
        if (activityClassName == null || other.activityClassName == null) return true
        return activityClassName == other.activityClassName
    }

    /** Compact `package/class` form used for map keys, logs and storage. */
    fun storageKey(): String = buildString {
        append(packageName)
        activityClassName?.let { append('/').append(it) }
        if (userSerial != PRIMARY_USER) append('#').append(userSerial)
    }

    override fun toString(): String = storageKey()

    companion object {
        const val PRIMARY_USER = 0L

        fun of(packageName: String, activityClassName: String? = null, userSerial: Long = PRIMARY_USER) =
            AppKey(packageName, activityClassName, userSerial)

        /** Parses the output of [storageKey]. Returns `null` for malformed input. */
        fun parse(value: String): AppKey? {
            if (value.isBlank()) return null
            val serialPart = value.substringAfterLast('#', "")
            val userSerial = serialPart.toLongOrNull() ?: PRIMARY_USER
            val withoutSerial = if (serialPart.isEmpty()) value else value.substringBeforeLast('#')
            val slash = withoutSerial.indexOf('/')
            return if (slash <= 0) {
                AppKey(withoutSerial, null, userSerial)
            } else {
                AppKey(
                    packageName = withoutSerial.substring(0, slash),
                    activityClassName = withoutSerial.substring(slash + 1).ifEmpty { null },
                    userSerial = userSerial,
                )
            }
        }
    }
}

/** Where the icon for an app comes from. */
@Serializable
sealed interface IconSource {
    /** Fall back to the real system icon, optionally through an icon pack. */
    data class FromPack(val packPackage: String, val packActivityClass: String?) : IconSource

    /** A user chosen image, persisted as a persisted content URI. */
    data class FromImage(val uri: String) : IconSource
}

/** Extra state an app row can show. */
enum class AppBadge {
    NONE,
    FAVORITE,
    HIDDEN,
    WORK_PROFILE,
    CUSTOM_ICON,
    CUSTOM_LABEL,
}

/**
 * One launchable application as reported by
 * [android.content.pm.LauncherApps.getActivityList].
 */
@Immutable
@Serializable
data class InstalledApp(
    val key: AppKey,
    val label: String,
    val isSystemApp: Boolean = false,
    val isUpdatedSystemApp: Boolean = false,
    val isWorkProfile: Boolean = false,
    val isComponentEnabled: Boolean = true,
    val firstInstallTime: Long = 0L,
    val lastUpdateTime: Long = 0L,
    val badged: Boolean = false,
) {
    /** Lower-cased label used for locale-aware searching and sorting. */
    val searchLabel: String get() = label.lowercase()

    /**
     * Launcher entry point. A null [AppKey.activityClassName] would be a bug
     * here because discovery always knows the concrete activity, so this falls
     * back to the package name and lets the framework resolve the default.
     */
    val launchComponentName: String
        get() = key.activityClassName ?: key.packageName
}

/**
 * A launcher entry plus everything the local database knows about it. This is
 * the shape the user interface consumes.
 */
@Immutable
data class AppListEntry(
    val app: InstalledApp,
    val customLabel: String? = null,
    val searchKeywords: List<String> = emptyList(),
    val iconSource: IconSource? = null,
    val isFavorite: Boolean = false,
    val isHidden: Boolean = false,
    val badge: AppBadge = AppBadge.NONE,
    val launchCount: Int = 0,
    val lastLaunchTime: Long = 0L,
) {
    val key: AppKey get() = app.key
    val displayLabel: String get() = customLabel ?: app.label
    val displayLabelLower: String get() = displayLabel.lowercase()
    val isCustomised: Boolean get() = customLabel != null || iconSource != null
}

/** A static or dynamic shortcut published by an application. */
@Immutable
data class AppShortcut(
    val shortcutId: String,
    val appKey: AppKey,
    val shortLabel: String,
    val longLabel: String,
    val isEnabled: Boolean = true,
    val isDynamic: Boolean = false,
    val rank: Int = 0,
)
