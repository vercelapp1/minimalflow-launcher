package com.minimalflow.launcher.core.apps

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import com.minimalflow.launcher.core.model.AppKey
import com.minimalflow.launcher.core.model.IconSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Icon loading and caching.
 *
 * Icons are rasterised to an [ImageBitmap] at the size the list actually asks
 * for and kept in a size-aware [LruCache]. Two consequences matter for a
 * launcher: scrolling never allocates bitmaps (they are already cached), and the
 * cache is bounded by a fraction of the heap instead of growing with the number
 * of installed apps.
 */
@Singleton
class AppIconRepository @Inject constructor(
    @ApplicationContext
    private val context: Context,
) {

    private val packContextCache = ConcurrentHashMap<String, Context>()

    private val cache: LruCache<String, ImageBitmap> = object : LruCache<String, ImageBitmap>(
        maxCacheSizeBytes(),
    ) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
    }

    private val density = context.resources.displayMetrics.density

    fun dpToPx(dp: Int): Int = (dp * density).toInt().coerceIn(24, 512)

    /** Returns an already-cached icon without touching the disk or the package manager. */
    fun cached(storageKey: String, sizePx: Int): ImageBitmap? = cache.get(cacheKey(storageKey, sizePx))

    /**
     * Resolves the icon for [key], honouring an icon pack or a user-chosen image
     * when one is configured. Returns `null` only when the app has no icon at
     * all, which in practice does not happen.
     */
    suspend fun loadIcon(
        key: AppKey,
        iconSource: IconSource?,
        sizePx: Int,
        packPackage: String? = null,
    ): ImageBitmap? = withContext(Dispatchers.IO) {
        val resolvedKey = cacheKey(key.storageKey(), sizePx) + "|" + (iconSource?.cacheToken() ?: packPackage ?: "")
        cache.get(resolvedKey)?.let { return@withContext it }

        val drawable = when (iconSource) {
            is IconSource.FromImage -> loadFromImage(iconSource.uri)
            is IconSource.FromPack -> loadFromPack(key, iconSource.packPackage, iconSource.packActivityClass)
            null -> if (packPackage != null) loadFromPack(key, packPackage, null) else null
        } ?: return@withContext null

        val bitmap = drawable.rasterise(sizePx)
        val image = bitmap.asImageBitmap()
        cache.put(resolvedKey, image)
        image
    }

    /** Drops everything, e.g. after a theme change altered the icon size. */
    fun clear() {
        cache.evictAll()
        packContextCache.clear()
    }

    fun trimMemory() = cache.evictAll()

    // --------------------------------------------------------------- internals

    private fun cacheKey(storageKey: String, sizePx: Int): String = "$storageKey@$sizePx"

    private fun IconSource.cacheToken(): String = when (this) {
        is IconSource.FromImage -> "img:$uri"
        is IconSource.FromPack -> "pack:$packPackage/${packActivityClass.orEmpty()}"
    }

    private fun loadFromImage(uri: String): Drawable? = runCatching {
        val parsed = Uri.parse(uri)
        if (parsed.scheme == "android.resource") {
            val resourceId = parsed.lastPathSegment?.toIntOrNull() ?: return@runCatching null
            ContextCompat.getDrawable(context, resourceId)
        } else {
            context.contentResolver.openInputStream(parsed)?.use { stream ->
                BitmapFactory.decodeStream(stream)
            }?.let { BitmapDrawable(context.resources, it) }
        }
    }.getOrNull()

    /**
     * Resolves an icon out of an installed icon pack.
     *
     * Two protocols are handled because both are still shipped in the wild:
     *
     *  - **Adaptive packs** expose an activity that answers
     *    `org.LauncherTools.IconPack` and publishes its layer drawables as
     *    `com.anddoes.launcher.THEMES` metadata, the same way the system icon
     *    picker consumes them. The layers are stacked back, mask, overlay.
     *  - **Legacy packs** simply ship a drawable named after the target package.
     *
     * When neither yields anything the real icon is used, because a missing
     * override must never look like a broken app.
     */
    private fun loadFromPack(
        target: AppKey,
        packPackage: String,
        providerClass: String?,
    ): Drawable? = runCatching {
        val packContext = packContextFor(packPackage)

        adaptivePackLayers(packContext, packPackage, providerClass, target.packageName)
            ?.let { LayerDrawable(it.toTypedArray()) }
            ?: legacyPackDrawable(packContext, packPackage, target.packageName)
    }.getOrNull()

    private fun packContextFor(packPackage: String): Context =
        packContextCache.getOrPut(packPackage) {
            context.createPackageContext(packPackage, Context.CONTEXT_IGNORE_SECURITY)
        }

    /**
     * Reads the themed layer drawables out of an icon pack's picker activity.
     *
     * Returns `null` when the package is not an icon pack at all, which is the
     * common case and must not be treated as an error.
     */
    private fun adaptivePackLayers(
        packContext: Context,
        packPackage: String,
        providerClass: String?,
        targetPackage: String,
    ): List<Drawable>? = runCatching {
        val packManager = packContext.packageManager
        val resolvedProvider = providerClass ?: packManager.iconPackProviderClass() ?: return null
        val info = packManager.getActivityInfo(
            ComponentName(packPackage, resolvedProvider),
            PackageManager.GET_META_DATA,
        )
        val metaData = info.metaData ?: return null

        val themeName = metaData.getString(META_PACK_NAME)
            ?: return null
        if (themeName != packPackage && !resolvedProvider.startsWith(packPackage)) return null

        val resources = packManager.getResourcesForApplication(packPackage)
        val byId = { id: Int -> id.takeIf { it != 0 }?.let { ContextCompat.getDrawable(packContext, it) } }
        val layers = listOfNotNull(
            byId(metaData.getInt(META_ICON_BACK, 0)),
            byId(metaData.getInt(META_ICON_MASK, 0)),
            byId(metaData.getInt(META_ICON_UPON, 0)),
        )
        layers.ifEmpty { null }
    }.getOrNull()

    /** The old convention: a drawable whose name is the target package. */
    private fun legacyPackDrawable(
        packContext: Context,
        packPackage: String,
        targetPackage: String,
    ): Drawable? = runCatching {
        val packManager = packContext.packageManager
        val candidates = listOf(
            targetPackage,
            targetPackage.replace('.', '_'),
            "icon_$targetPackage",
        )
        for (candidate in candidates) {
            val id = packContext.resources.getIdentifier(candidate, "drawable", packPackage)
            if (id != 0) return@runCatching ContextCompat.getDrawable(packContext, id)
        }
        null
    }.getOrNull()

    private fun Drawable.rasterise(targetSizePx: Int): Bitmap {
        if (this is BitmapDrawable && bitmap != null) {
            return Bitmap.createScaledBitmap(bitmap, targetSizePx, targetSizePx, true)
        }
        val bitmap = Bitmap.createBitmap(targetSizePx, targetSizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        setBounds(0, 0, targetSizePx, targetSizePx)
        draw(canvas)
        return bitmap
    }

    private companion object {
        const val META_PACK_NAME = "packname"
        const val META_ICON_BACK = "iconback"
        const val META_ICON_MASK = "iconmask"
        const val META_ICON_UPON = "iconupon"

        fun maxCacheSizeBytes(): Int {
            val maxMemory = (Runtime.getRuntime().maxMemory() / 1024L).toInt()
            val eighth = maxMemory / 8
            return eighth.coerceIn(4 * 1024, 48 * 1024) * 1024
        }
    }
}

/**
 * The picker activity of an installed icon pack, if it has one.
 *
 * Icon pack activities are declared for the `org.LauncherTools.IconPack` action
 * with no category, so the query must not add one: adding `CATEGORY_LAUNCHER`
 * would hide every real pack and return an empty list on purpose-built devices.
 */
internal fun PackageManager.iconPackActivities(): List<ResolveInfo> = runCatching {
    queryIntentActivities(Intent(ACTION_ICON_PACK), 0)
}.getOrDefault(emptyList())

internal fun PackageManager.iconPackProviderClass(): String? =
    iconPackActivities().firstOrNull()?.activityInfo?.name

internal const val ACTION_ICON_PACK = "org.LauncherTools.IconPack"
