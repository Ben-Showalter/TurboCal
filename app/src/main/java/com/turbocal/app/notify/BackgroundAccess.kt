package com.turbocal.app.notify

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import com.turbocal.app.R

/**
 * "Run in background" (owner, round 8): asks Android not to battery-optimize
 * TurboCal, so a battery saver doesn't delay reminders or sync. Alarms already
 * fire with the app closed; this only removes the delays.
 *
 * Guide rules: some carrier builds refuse the exemption, and per-app deep links
 * are unreliable on OEM ROMs, so fall back to the general list with a toast
 * giving the D-pad steps.
 */
object BackgroundAccess {

    fun isExempt(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    @android.annotation.SuppressLint("BatteryLife") // a calendar's reminders are the exemption's intended use
    fun request(activity: Activity) {
        if (isExempt(activity)) return
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${activity.packageName}"))
        try {
            activity.startActivity(direct)
            return
        } catch (_: ActivityNotFoundException) {
        } catch (_: SecurityException) {
        }
        Toast.makeText(activity, R.string.background_steps, Toast.LENGTH_LONG).show()
        try {
            activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (_: ActivityNotFoundException) {
        } catch (_: SecurityException) {
        }
    }
}
