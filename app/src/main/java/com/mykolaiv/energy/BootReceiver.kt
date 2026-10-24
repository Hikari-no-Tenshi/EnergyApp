package com.mykolaiv.energy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

private const val TAG = "BootReceiver"

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "BOOT COMPLETED!")
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            setupBackgroundWork(context.applicationContext)
        }
    }
}