package io.github.hoonex.esp32car.ui.screens

import android.app.Activity
import io.github.hoonex.esp32car.update.AppUpdater

/**
 * Temporary source-compatibility shim for the unused ReliableCockpitScreen.
 *
 * Older cockpit code still passes the former installWhenReady named argument. The new updater
 * deliberately ignores that request and performs metadata lookup only, so no legacy screen can
 * accidentally restore automatic APK download or installation behavior.
 */
@Suppress("UNUSED_PARAMETER")
suspend fun AppUpdater.checkForUpdate(activity: Activity, installWhenReady: Boolean) {
    checkForUpdate(activity)
}
