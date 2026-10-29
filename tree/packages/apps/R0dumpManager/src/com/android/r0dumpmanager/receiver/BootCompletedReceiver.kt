package com.android.r0dumpmanager.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.android.r0dumpmanager.data.property.FusionDeviceSnapshot
import com.android.r0dumpmanager.data.property.FusionPropertyRepository
import com.android.r0dumpmanager.notification.ActiveSessionNotifier
import com.android.r0dumpmanager.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED) return
        val pending = goAsync()
        val deviceContext = context.createDeviceProtectedStorageContext()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val notifier = ActiveSessionNotifier(deviceContext)
                when (val snapshot = FusionPropertyRepository().read()) {
                    is FusionDeviceSnapshot.Enabled -> notifier.show(snapshot.draft.targetPackage, snapshot.draft.runId)
                    is FusionDeviceSnapshot.Interrupted -> notifier.showRecovery(
                        snapshot.generation,
                        deviceContext.getString(R.string.recovery_interrupted_detail),
                    )
                    is FusionDeviceSnapshot.Invalid -> notifier.showRecovery(
                        -1L,
                        deviceContext.getString(R.string.recovery_invalid_detail, snapshot.reason),
                    )
                    is FusionDeviceSnapshot.ConcurrentChange -> notifier.showRecovery(
                        -1L,
                        deviceContext.getString(R.string.recovery_concurrent_detail),
                    )
                    else -> notifier.cancel()
                }
            } finally { pending.finish() }
        }
    }
}
