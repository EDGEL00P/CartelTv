package com.carteltv.tv.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.carteltv.tv.data.network.PrivateDnsController

/** Resumes Private DNS ad blocking after a timed pause expires. */
class DnsResumeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        PrivateDnsController(context).reconcilePause()
    }
}
