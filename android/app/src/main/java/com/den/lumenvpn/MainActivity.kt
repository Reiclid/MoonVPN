package com.den.lumenvpn

import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.wireguard.config.Config

class MainActivity : ComponentActivity() {
    private var configVersion by mutableIntStateOf(0)

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) Vpn.connect(this)
    }
    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val text = runCatching { contentResolver.openInputStream(uri)?.bufferedReader()?.readText() }.getOrNull()
            importConfig(text.orEmpty())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Vpn.init(this)
        val actions = Actions(
            toggle = ::toggle,
            importQr = ::scanQr,
            importFile = { pickFile.launch(arrayOf("*/*")) },
            importClipboard = {
                val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                importConfig(cm.primaryClip?.getItemAt(0)?.text?.toString().orEmpty())
            },
            openVpnSettings = { runCatching { startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) } },
            openPanel = { runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://10.8.1.2:3000"))) } },
            saveEndpoint = { value ->
                if (Store.setEndpoint(this, value)) {
                    if (Vpn.state.value == VpnState.ON) Vpn.disconnect(this)
                    configVersion++
                } else toast("Невірна адреса")
            },
        )
        setContent { LumenApp(actions, configVersion) }
    }

    private fun toggle() {
        when (Vpn.state.value) {
            VpnState.ON, VpnState.CONNECTING -> Vpn.disconnect(this)
            else -> {
                val intent = VpnService.prepare(this)
                if (intent != null) vpnPermission.launch(intent) else Vpn.connect(this)
            }
        }
    }

    private fun scanQr() {
        val opts = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        GmsBarcodeScanning.getClient(this, opts).startScan()
            .addOnSuccessListener { importConfig(it.rawValue.orEmpty()) }
            .addOnFailureListener { /* скасовано користувачем */ }
    }

    private fun importConfig(text: String) {
        val ok = runCatching { Config.parse(text.byteInputStream()) }.map { it.peers.isNotEmpty() }.getOrDefault(false)
        if (!ok) { toast("Це не конфігурація WireGuard"); return }
        if (Vpn.state.value == VpnState.ON) Vpn.disconnect(this)
        Store.setConfig(this, text)
        configVersion++
        toast("Готово")
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
