package com.meowmeow.filemanager.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkFileTest {
    private fun entry(name: String, mime: String? = null, directory: Boolean = false) =
        FileEntry("content://test/file", name, directory, 1L, 0L, mime, false)

    @Test fun detectsExtensionRegardlessOfCaseOrGenericMime() {
        assertTrue(isApkFile(entry("test.apk")))
        assertTrue(isApkFile(entry("TEST.APK", "application/octet-stream")))
    }

    @Test fun detectsExplicitPackageMimeWithoutExtension() {
        assertTrue(isApkFile(entry("download", APK_MIME_TYPE)))
    }

    @Test fun ignoresFoldersAndOtherFiles() {
        assertFalse(isApkFile(entry("folder.apk", APK_MIME_TYPE, true)))
        assertFalse(isApkFile(entry("test.apk.txt", "text/plain")))
        assertFalse(isApkFile(entry("document.pdf", "application/pdf")))
    }
}
