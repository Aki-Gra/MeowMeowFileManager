package com.meowmeow.filemanager

import android.app.Application
import com.meowmeow.filemanager.diagnostics.ErrorArea
import com.meowmeow.filemanager.diagnostics.ErrorOperation
import com.meowmeow.filemanager.diagnostics.ErrorLogStore

class MeowMeowApp : Application() {
    lateinit var errorLog: ErrorLogStore
        private set

    override fun onCreate() {
        super.onCreate()
        errorLog = ErrorLogStore(this)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, failure ->
            try {
                errorLog.record(ErrorArea.APP_CRASH, failure, fatal = true, operation = ErrorOperation.APP_CRASH)
            } catch (_: Exception) {
                // Never replace the original crash.
            } finally {
                if (previous != null) previous.uncaughtException(thread, failure)
                else kotlin.system.exitProcess(10)
            }
        }
    }
}
