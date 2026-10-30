package com.den.lumenvpn

import android.content.Context
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

enum class VpnState { OFF, CONNECTING, ON, ERROR }

object Vpn {
    private val _state = MutableStateFlow(VpnState.OFF)
    val state: StateFlow<VpnState> = _state

    private var backend: GoBackend? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val tunnel = object : Tunnel {
        override fun getName() = "lumen"
        override fun onStateChange(newState: Tunnel.State) {
            if (newState == Tunnel.State.UP) _state.value = VpnState.ON
            else if (_state.value != VpnState.CONNECTING && _state.value != VpnState.ERROR) _state.value = VpnState.OFF
        }
    }

    @Synchronized
    private fun backend(c: Context): GoBackend =
        backend ?: GoBackend(c.applicationContext).also { backend = it }

    fun init(c: Context) {
        val b = backend(c)
        if (runCatching { b.getState(tunnel) }.getOrNull() == Tunnel.State.UP) _state.value = VpnState.ON
    }

    /** Потребує вже виданого дозволу VpnService.prepare(). */
    fun connect(c: Context) {
        if (_state.value == VpnState.CONNECTING || _state.value == VpnState.ON) return
        val b = backend(c)
        _state.value = VpnState.CONNECTING
        scope.launch {
            try {
                val cfg = Config.parse(Split.apply(c, Store.config(c)).byteInputStream())
                b.setState(tunnel, Tunnel.State.UP, cfg)
                _state.value = VpnState.ON
            } catch (e: Exception) {
                _state.value = VpnState.ERROR
                delay(2200)
                if (_state.value == VpnState.ERROR) _state.value = VpnState.OFF
            }
        }
    }

    /** Перепідключення (після зміни фільтрів). */
    fun reconnect(c: Context) {
        val b = backend(c)
        scope.launch {
            runCatching { b.setState(tunnel, Tunnel.State.DOWN, null) }
            _state.value = VpnState.OFF
            connect(c)
        }
    }

    fun disconnect(c: Context) {
        val b = backend(c)
        scope.launch {
            runCatching { b.setState(tunnel, Tunnel.State.DOWN, null) }
            _state.value = VpnState.OFF
        }
    }
}
