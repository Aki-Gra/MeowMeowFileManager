package com.meowmeow.filemanager.model

const val APK_MIME_TYPE = "application/vnd.android.package-archive"

/** Use the extension for providers that omit or misreport APK MIME metadata. */
fun isApkFile(entry: FileEntry): Boolean =
    !entry.isDirectory && (entry.name.endsWith(".apk", ignoreCase = true) ||
        entry.mimeType?.equals(APK_MIME_TYPE, ignoreCase = true) == true)
