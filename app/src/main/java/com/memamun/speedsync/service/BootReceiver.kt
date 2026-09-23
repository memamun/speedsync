package com.memamun.speedsync.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.memamun.speedsync.data.DataUsageRepository

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == "com.htc.intent.action.QUICKBOOT_POWERON"
        ) {
            val repo = DataUsageRepository.getInstance(context)
            if (repo.isServiceEnabled() && repo.isStartOnBoot()) {
                SpeedMeterService.start(context)
            }
        }
    }
}
