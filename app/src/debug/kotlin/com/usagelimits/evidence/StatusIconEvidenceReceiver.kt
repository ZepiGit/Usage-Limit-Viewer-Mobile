package com.usagelimits.evidence

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.usagelimits.UsageLimitsApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Explicit debug broadcasts only; completion is the tokenized file, not broadcast delivery. */
class StatusIconEvidenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext as UsageLimitsApp
        val commands = synchronized(scope) {
            driver ?: StatusIconEvidenceDriver(app).also { driver = it }
        }
        scope.launch {
            try {
                commands.execute(intent.getStringExtra("command"), intent.getStringExtra("token"), intent.getStringExtra("account"))
            } catch (error: Exception) {
                Log.e("StatusIconEvidence", "Could not write command completion", error)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var driver: StatusIconEvidenceDriver? = null
    }
}
