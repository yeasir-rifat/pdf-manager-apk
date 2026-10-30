package com.pdfmanager.app.printing

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * Step 10. Helpers around Android's print services.
 * Which printers exist, whether they are online or out of paper, and job cancel/retry are all
 * handled by Android's print screen and notifications. The app cannot read them reliably,
 * so it only sends the user there.
 */
object PrintServices {

    /** Opens Android's Printing settings (print services, Mopria, vendor plugins). False if this device has no such screen. */
    fun openSettings(context: Context): Boolean = try {
        context.startActivity(Intent(Settings.ACTION_PRINT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }
}
