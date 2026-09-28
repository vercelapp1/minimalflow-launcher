package com.minimalflow.launcher.core.themes

import com.minimalflow.launcher.core.data.CustomThemeDao
import com.minimalflow.launcher.core.data.EntityMappers.toEntity
import com.minimalflow.launcher.core.data.EntityMappers.toModel
import com.minimalflow.launcher.core.model.ThemeConfig
import com.minimalflow.launcher.core.model.ThemeIds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Built-in themes plus the user's own.
 *
 * The active theme is whatever `themeId` points at: a built-in id, or a
 * `custom:` id for a row in the database. Deleting the active custom theme falls
 * back to Obsidian rather than leaving the launcher theme-less.
 */
@Singleton
class ThemeRepository @Inject constructor(
    private val customThemeDao: CustomThemeDao,
) {

    val customThemes: Flow<List<ThemeConfig>> = customThemeDao.observeAll()
        .map { rows -> rows.map { it.toModel() } }

    suspend fun all(): List<ThemeConfig> = BuiltInThemes.all + customThemeDao.getAll().map { it.toModel() }

    suspend fun byId(id: String): ThemeConfig? =
        BuiltInThemes.byId(id) ?: customThemeDao.find(id)?.toModel()

    /** Never returns null: unknown ids fall back to the default theme. */
    suspend fun resolveOrDefault(id: String): ThemeConfig =
        byId(id) ?: BuiltInThemes.default

    /**
     * Saves a custom theme. Built-in themes are never written to the database:
     * [ThemeConfig.isBuiltIn] is forced to `false` and the id is rewritten to a
     * custom one if the caller passed a built-in id.
     */
    suspend fun save(theme: ThemeConfig, createdAt: Long = System.currentTimeMillis()): String {
        val id = if (theme.isBuiltIn || BuiltInThemes.isBuiltIn(theme.id)) newCustomId(theme.name) else theme.id
        val stored = theme.copy(id = id, isBuiltIn = false)
        val existing = customThemeDao.find(id)
        val entity = stored.toEntity(createdAt = existing?.createdAt ?: createdAt)
        customThemeDao.upsert(entity)
        return id
    }

    /** Copies a theme - built-in or custom - into a new editable custom theme. */
    suspend fun duplicate(source: ThemeConfig, name: String? = null): String {
        val baseName = (name ?: source.name).take(ThemeValidator.MAX_NAME_LENGTH)
        val copy = source.copy(
            id = newCustomId(baseName),
            name = baseName,
            isBuiltIn = false,
        )
        return save(copy)
    }

    /** Renames a custom theme. Built-ins keep their names. */
    suspend fun rename(id: String, name: String): Boolean {
        if (BuiltInThemes.isBuiltIn(id)) return false
        val existing = customThemeDao.find(id) ?: return false
        customThemeDao.update(
            existing.copy(name = name.trim().take(ThemeValidator.MAX_NAME_LENGTH)),
        )
        return true
    }

    suspend fun delete(id: String): Boolean {
        if (BuiltInThemes.isBuiltIn(id)) return false
        val existing = customThemeDao.find(id) ?: return false
        customThemeDao.delete(id)
        return true
    }

    /** Restores a custom theme to the built-in it was created from. */
    suspend fun reset(id: String, baseId: String): String? {
        val existing = customThemeDao.find(id) ?: return null
        val base = BuiltInThemes.byId(baseId) ?: BuiltInThemes.default
        customThemeDao.upsert(
            base.copy(id = id, name = existing.name, isBuiltIn = false)
                .toEntity(createdAt = existing.createdAt),
        )
        return id
    }

    suspend fun clearCustom() = customThemeDao.clear()

    private fun newCustomId(name: String): String {
        val slug = name.trim().lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(24)
            .ifEmpty { "theme" }
        return "${ThemeIds.CUSTOM_PREFIX}$slug-${System.currentTimeMillis()}"
    }
}
