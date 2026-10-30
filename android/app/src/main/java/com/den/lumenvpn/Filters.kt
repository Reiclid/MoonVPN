package com.den.lumenvpn

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class FilterItem(
    val name: String, val desc: String, val url: String,
    val enabled: Boolean, val rules: Int, val busy: Boolean, val error: String,
)

/** API списків блокування на сервері (filterctl). Доступний лише зсередини VPN. */
object FilterApi {
    private const val BASE = "http://10.8.1.4:8080/api/filters"

    private fun call(method: String, body: String? = null): String {
        val conn = URL(BASE).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 4000
            conn.readTimeout = 15000
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val stream = if (conn.responseCode < 400) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.readText().orEmpty()
            if (conn.responseCode >= 400) error(text)
            return text
        } finally {
            conn.disconnect()
        }
    }

    fun list(): List<FilterItem> {
        val arr = JSONArray(call("GET"))
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            FilterItem(o.getString("name"), o.optString("desc"), o.getString("url"),
                o.getBoolean("enabled"), o.optInt("rules"), o.optBoolean("busy"), o.optString("error"))
        }
    }

    fun set(url: String, enabled: Boolean) {
        call("POST", JSONObject().put("url", url).put("enabled", enabled).toString())
    }
}

@Composable
fun FiltersScreen() {
    val ctx = LocalContext.current
    val vpn by Vpn.state.collectAsState()
    var items by remember { mutableStateOf<List<FilterItem>>(emptyList()) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // оновлюємо стан, поки екран відкрито (зміна списку на сервері триває десятки секунд)
    LaunchedEffect(vpn) {
        while (true) {
            val r = withContext(Dispatchers.IO) { runCatching { FilterApi.list() } }
            r.onSuccess { items = it; failed = false }.onFailure { failed = true }
            delay(if (items.any { it.busy }) 2000 else 5000)
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 30.dp, vertical = 36.dp)) {
        if (failed && items.isEmpty()) {
            BasicText(
                if (vpn == VpnState.ON) "Сервер не відповідає. Онови сервер до останньої версії (install.sh)."
                else "Підключи VPN, щоб змінювати списки блокування.",
                style = small,
            )
        }
        LazyColumn {
            items(items, key = { it.url }) { f ->
                val hint = buildString {
                    append(f.desc)
                    if (f.rules > 0) append(" · ${"%,d".format(f.rules).replace(',', ' ')} правил")
                    if (f.busy) append(" · застосовується…")
                    if (f.error.isNotEmpty() && !f.busy) append(" · помилка")
                }
                Item(f.name, hint, onClick = {
                    if (f.busy) return@Item
                    val want = !f.enabled
                    items = items.map { if (it.url == f.url) it.copy(enabled = want, busy = true, error = "") else it }
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { runCatching { FilterApi.set(f.url, want) } }
                        r.onFailure {
                            items = items.map { x -> if (x.url == f.url) x.copy(enabled = !want, busy = false) else x }
                            Toast.makeText(ctx, "Не вдалося змінити список", Toast.LENGTH_SHORT).show()
                        }
                    }
                }) { Toggle(f.enabled) }
            }
        }
    }
}
