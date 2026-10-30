package com.den.lumenvpn

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

private enum class Screen { Main, Settings, Sites, Apps, Filters }

/** Зв'язок екранів з Activity (дозволи, пікери, системні налаштування). */
class Actions(
    val toggle: () -> Unit,
    val importQr: () -> Unit,
    val importFile: () -> Unit,
    val importClipboard: () -> Unit,
    val openVpnSettings: () -> Unit,
    val openPanel: () -> Unit,
    val saveEndpoint: (String) -> Unit,
)

@Composable
fun LumenApp(actions: Actions, configVersion: Int) {
    val ctx = LocalContext.current
    val state by Vpn.state.collectAsState()
    var screen by remember { mutableStateOf(Screen.Main) }
    var dirty by remember { mutableStateOf(false) }
    fun goMain() {
        screen = Screen.Main
        if (dirty) { dirty = false; if (state == VpnState.ON) Vpn.reconnect(ctx) }
    }
    BackHandler(screen != Screen.Main) {
        if (screen == Screen.Sites || screen == Screen.Apps || screen == Screen.Filters) screen = Screen.Settings else goMain()
    }

    val presence by animateFloatAsState(if (state == VpnState.ON) 1f else 0f, tween(1400), label = "presence")
    SilkCanvas(presence) {
        when (screen) {
            Screen.Main -> {
                androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val d = minOf(maxWidth * 1.0f, maxHeight * 0.66f)
                    Orb(state, diameter = d, onClick = {
                        if (Store.hasConfig(ctx)) actions.toggle() else screen = Screen.Settings
                    })
                }
                SettingsDot(
                    onClick = { screen = Screen.Settings },
                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 20.dp),
                )
            }
            Screen.Settings -> SettingsScreen(actions, configVersion, { screen = Screen.Sites }, { screen = Screen.Apps }, { screen = Screen.Filters })
            Screen.Sites -> SitesScreen { dirty = true }
            Screen.Apps -> AppsScreen { dirty = true }
            Screen.Filters -> FiltersScreen()
        }
    }
}

internal val body = TextStyle(
    color = Lumen.textPrimary,
    fontSize = 18.sp,
    fontWeight = FontWeight.Light,
    letterSpacing = (-0.011).em,
    lineHeight = 1.45.em,
    shadow = Shadow(oklch(0.95f, 0.02f, 250f, 0.12f), Offset.Zero, 18f),
)
internal val small = body.copy(color = Lumen.textDim, fontSize = 13.sp, shadow = null)

@Composable
internal fun Item(label: String, hint: String? = null, onClick: () -> Unit, trailing: @Composable (() -> Unit)? = null) {
    val src = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(src, null, onClick = onClick)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            androidx.compose.foundation.text.BasicText(label, style = body)
            if (hint != null) androidx.compose.foundation.text.BasicText(hint, style = small)
        }
        trailing?.invoke()
    }
}

/** Перемикач-«лінза»: пігулка зі світловою точкою, без обводки. */
@Composable
internal fun Toggle(on: Boolean) {
    val k by animateFloatAsState(if (on) 1f else 0f, tween(320), label = "tg")
    Canvas(Modifier.size(width = 46.dp, height = 26.dp)) {
        val h = size.height
        drawRoundRect(
            Brush.linearGradient(oklabStops(
                Color4(0.20f + 0.18f * k, 0.03f + 0.07f * k, 265f - 40f * k),
                Color4(0.16f + 0.08f * k, 0.02f + 0.05f * k, 285f - 5f * k),
            )),
            size = Size(size.width, h), cornerRadius = CornerRadius(h / 2),
        )
        val cx = h / 2 + (size.width - h) * k
        drawCircle(oklch(0.55f + 0.38f * k, 0.02f + 0.05f * k, 240f), radius = h * 0.30f, center = Offset(cx, h / 2))
    }
}

@Composable
private fun SettingsScreen(a: Actions, configVersion: Int, openSites: () -> Unit, openApps: () -> Unit, openFilters: () -> Unit) {
    val ctx = LocalContext.current
    var auto by remember { mutableStateOf(Store.autoStart(ctx)) }
    var endpoint by remember(configVersion) { mutableStateOf(Store.endpoint(ctx)) }
    val loaded = remember(configVersion) { Store.hasConfig(ctx) }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 30.dp, vertical = 36.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // адреса сервера: редагується на місці, зберігається по Enter
        Column(Modifier.padding(vertical = 14.dp).alpha(if (loaded) 1f else 0.4f)) {
            BasicTextField(
                value = endpoint,
                onValueChange = { endpoint = it },
                enabled = loaded,
                singleLine = true,
                textStyle = body,
                cursorBrush = SolidColor(Lumen.textPrimary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { a.saveEndpoint(endpoint) }),
                modifier = Modifier.fillMaxWidth(),
            )
            androidx.compose.foundation.text.BasicText("Сервер (IP або домен : порт)", style = small)
        }
        Item("Сканувати QR", onClick = a.importQr)
        Item("Імпорт з файлу", onClick = a.importFile)
        Item("Вставити з буфера", onClick = a.importClipboard)
        Spacer(Modifier.height(18.dp))
        Item("Списки блокування", "HaGeZi, OISD та інші: вмикай і вимикай", onClick = openFilters)
        Item("Сайти", "Що йде повз VPN або лише через нього", onClick = openSites)
        Item("Застосунки", "Те саме для встановлених застосунків", onClick = openApps)
        Spacer(Modifier.height(18.dp))
        Item("Запуск після перезавантаження", onClick = { auto = !auto; Store.setAutoStart(ctx, auto) }) { Toggle(auto) }
        Item("Блокувати все без VPN", "Системні налаштування: «Постійний VPN»", onClick = a.openVpnSettings)
        Item("Панель блокування реклами", "Працює, коли VPN увімкнено", onClick = a.openPanel)
    }
}
