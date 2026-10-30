package com.den.lumenvpn

import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun SitesScreen(onChanged: () -> Unit) {
    val ctx = LocalContext.current
    var sites by remember { mutableStateOf(SplitStore.sites(ctx).sorted()) }
    var mode by remember { mutableStateOf(SplitStore.siteMode(ctx)) }
    var input by remember { mutableStateOf("") }

    fun save(list: List<String>) { sites = list.sorted(); SplitStore.setSites(ctx, sites.toSet()); onChanged() }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 30.dp, vertical = 36.dp)) {
        Item(
            if (mode == "bypass") "Усі сайти через VPN, крім списку" else "Через VPN лише сайти зі списку",
            "Торкнись, щоб змінити режим",
            onClick = { mode = if (mode == "bypass") "only" else "bypass"; SplitStore.setSiteMode(ctx, mode); onChanged() },
        )
        Column(Modifier.padding(vertical = 14.dp)) {
            BasicTextField(
                value = input, onValueChange = { input = it }, singleLine = true, textStyle = body,
                cursorBrush = SolidColor(Lumen.textPrimary),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    val h = SplitStore.normalizeHost(input)
                    if (h.isNotEmpty() && "." in h) save(sites + h)
                    input = ""
                }),
                modifier = Modifier.fillMaxWidth(),
            )
            BasicText("Додати сайт: example.com", style = small)
        }
        LazyColumn {
            items(sites, key = { it }) { s -> Item(s, "Торкнись, щоб прибрати", onClick = { save(sites - s) }) }
        }
        BasicText(
            "IP-адреси сайтів визначаються при підключенні. Сайти з CDN можуть змінювати адреси.",
            style = small, modifier = Modifier.padding(top = 20.dp),
        )
    }
}

private data class AppEntry(val label: String, val pkg: String)

@Composable
fun AppsScreen(onChanged: () -> Unit) {
    val ctx = LocalContext.current
    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var chosen by remember { mutableStateOf(SplitStore.apps(ctx)) }
    var mode by remember { mutableStateOf(SplitStore.appMode(ctx)) }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            val pm = ctx.packageManager
            val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(q, PackageManager.MATCH_ALL)
                .map { AppEntry(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
                .filter { it.pkg != ctx.packageName }
                .distinctBy { it.pkg }
                .sortedBy { it.label.lowercase() }
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 30.dp, vertical = 36.dp)) {
        Item(
            if (mode == "exclude") "Усі застосунки через VPN, крім обраних" else "Через VPN лише обрані застосунки",
            "Торкнись, щоб змінити режим",
            onClick = { mode = if (mode == "exclude") "include" else "exclude"; SplitStore.setAppMode(ctx, mode); onChanged() },
        )
        LazyColumn {
            items(apps, key = { it.pkg }) { a ->
                Item(a.label, onClick = {
                    chosen = if (a.pkg in chosen) chosen - a.pkg else chosen + a.pkg
                    SplitStore.setApps(ctx, chosen); onChanged()
                }) { Toggle(a.pkg in chosen) }
            }
        }
    }
}
