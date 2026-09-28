package com.minimalflow.launcher.core.backup

/**
 * How a backup file is presented to the system file picker.
 *
 * `application/json` is what the file actually is, and offering a specific type
 * lets the picker pre-filter to files this app can read instead of showing
 * everything. The import picker also offers `text/plain` as a fallback, because
 * some file providers report a plain JSON document as text.
 */
const val BACKUP_MIME_TYPE: String = "application/json"

/** Types offered when importing: our own type, plus the common mislabelling. */
val BACKUP_IMPORT_MIME_TYPES: Array<String> =
    arrayOf(BACKUP_MIME_TYPE, "text/plain", "application/octet-stream")

/** Default file name offered when exporting. */
const val BACKUP_FILE_NAME: String = "minimalflow-backup.json"
