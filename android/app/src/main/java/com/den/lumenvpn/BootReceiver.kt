package com.den.lumenvpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService

/** Після перезавантаження телефону (і після оновлення додатку) піднімає VPN сам. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!Store.autoStart(context) || !Store.hasConfig(context)) return
        if (VpnService.prepare(context) != null) return // дозвіл ще не видано
        Vpn.init(context)
        Vpn.connect(context)
    }
}
