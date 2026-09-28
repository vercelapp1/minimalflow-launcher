package com.minimalflow.launcher.core.privacy

import com.minimalflow.launcher.core.data.UsageDao
import com.minimalflow.launcher.core.data.UsageEventEntity
import com.minimalflow.launcher.core.model.AppKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Launch statistics for one app. */
data class UsageRecord(
    val key: AppKey,
    val launchCount: Int,
    val lastLaunchAt: Long,
)

/**
 * Local launch counters that make "Recently used" and "Most used" sorting work.
 *
 * Nothing is aggregated, sampled or transmitted: the table is a single row per
 * app and lives in the app's own database. When the user turns usage tracking
 * off in privacy settings, [recordLaunch] stops writing and the screen hides
 * both sorting modes that depend on it.
 */
@Singleton
class UsageTrackingRepository @Inject constructor(
    private val usageDao: UsageDao,
) {

    val records: Flow<Map<String, UsageRecord>> = usageDao.observeAll().map { entities ->
        entities.associate { entity ->
            val key = AppKey(entity.packageName, entity.componentName, entity.userSerial)
            key.storageKey() to UsageRecord(
                key = key,
                launchCount = entity.launchCount,
                lastLaunchAt = entity.lastLaunchAt,
            )
        }
    }

    /**
     * Increments the counter for [key]. [now] is injectable so the behaviour is
     * unit testable without waiting for wall-clock time.
     */
    suspend fun recordLaunch(key: AppKey, now: Long = System.currentTimeMillis()) {
        val existing = usageDao.getAll().firstOrNull {
            it.packageName == key.packageName && it.userSerial == key.userSerial
        }
        usageDao.upsert(
            UsageEventEntity(
                packageName = key.packageName,
                componentName = key.activityClassName,
                userSerial = key.userSerial,
                launchCount = (existing?.launchCount ?: 0) + 1,
                lastLaunchAt = now,
            ),
        )
    }

    suspend fun clear() = usageDao.clear()
}
