package dev.tomato.hongguo.tv

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

sealed interface Screen {
    data object Player : Screen
    data object Theater : Screen
    data object Search : Screen
    data class Mine(val tab: Int) : Screen
    data object Login : Screen
    data object Settings : Screen
    data object Setup : Screen
}

class AppState(private val activity: ComponentActivity, val scope: CoroutineScope) {
    private val prefs = activity.getSharedPreferences("tv", Context.MODE_PRIVATE)
    val store = Store(prefs)
    val api = Api(prefs).also { it.base = store.serverUrl }
    val player = PlayerState(activity, api, store, scope).also { p -> p.loggedIn = { user != null } }

    var screen: Screen by mutableStateOf(Screen.Player)
        private set
    var user: AuthUser? by mutableStateOf(null)
    var navSel by mutableStateOf(Nav.Feed)
    var setupError: String? by mutableStateOf(null)

    // Theater and search keep their place while the player is in front.
    var theaterTab by mutableStateOf(0)
    var genre by mutableStateOf("short_play")
    var theme: String? by mutableStateOf(null)
    var query by mutableStateOf("")

    /** Loads the 推荐 feed, or falls back to the first-run screen when there is no reachable server. */
    fun boot(show: Boolean = true) {
        if (store.serverUrl.isBlank()) { screen = Screen.Setup; return }
        api.base = store.serverUrl
        player.loading = true
        scope.launch {
            val cards = runCatching { api.rank("recommend", 30) }
            cards.onSuccess {
                setupError = null
                player.startFeed(it)
                if (show) { navSel = Nav.Feed; go(Screen.Player) }
            }.onFailure {
                player.loading = false
                setupError = "无法连接 ${store.serverUrl}（${it.message}）"
                screen = Screen.Setup
            }
        }
        refreshUser()
    }

    fun refreshUser() {
        scope.launch {
            user = runCatching { api.me() }.getOrNull()?.takeIf { it.loggedIn }?.let { it.user ?: AuthUser() }
        }
    }

    fun go(s: Screen) {
        if (s != Screen.Player) player.pause()
        player.overlay = Overlay.None
        screen = s
    }

    fun backToNav() {
        screen = Screen.Player
        player.overlay = Overlay.SideNav
    }

    fun open(seriesId: String, card: SeriesCard? = null) {
        player.openSeries(seriesId, card = card)
        go(Screen.Player)
    }

    fun nav(n: Nav) {
        navSel = n
        when (n) {
            Nav.Feed -> if (player.resumeFeed()) go(Screen.Player) else boot()
            Nav.Theater -> go(Screen.Theater)
            Nav.Search -> go(Screen.Search)
            Nav.Follow -> go(Screen.Mine(0))
            Nav.History -> go(Screen.Mine(1))
        }
    }

    fun exit() = activity.finish()
}

class MainActivity : ComponentActivity() {
    private val scope = MainScope()
    private lateinit var app: AppState

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = AppState(this, scope)
        intent.getStringExtra("server")?.let { app.store.setServer(it) }
        app.boot()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) { App(app) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra("server")?.let { app.store.setServer(it); app.boot() }
    }

    override fun onStart() {
        super.onStart()
        if (app.screen == Screen.Player && app.player.exo.currentMediaItem != null) app.player.exo.play()
    }

    override fun onStop() {
        app.player.pause()
        super.onStop()
    }

    override fun onDestroy() {
        app.player.exo.release()
        scope.cancel()
        super.onDestroy()
    }
}

@Composable
fun App(app: AppState) {
    LaunchedEffect(app.screen) {
        if (app.screen == Screen.Player && app.player.exo.currentMediaItem != null) app.player.exo.play()
    }
    when (val s = app.screen) {
        Screen.Player -> PlayerScreen(app)
        Screen.Theater -> TheaterScreen(app)
        Screen.Search -> SearchScreen(app)
        is Screen.Mine -> MineScreen(app, s.tab)
        Screen.Login -> LoginScreen(app)
        Screen.Settings -> SettingsScreen(app, firstRun = false)
        Screen.Setup -> SettingsScreen(app, firstRun = true)
    }
}

/** A real text field: OK opens the system IME, UP/DOWN leave it even when keys arrive from a keyboard source. */
@Composable
fun TvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    width: Dp = 310.dp,
    size: TextUnit = 22.sp,
    uri: Boolean = false,
    onSubmit: () -> Unit = {},
) {
    val kb = LocalSoftwareKeyboardController.current
    val fm = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    BasicTextField(
        value, onValueChange,
        modifier.width(width).onFocusChanged { focused = it.isFocused }.onPreviewKeyEvent { ev ->
            if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (ev.key) {
                Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { kb?.show(); true }
                Key.DirectionDown -> { kb?.hide(); fm.moveFocus(FocusDirection.Down) }
                Key.DirectionUp -> { kb?.hide(); fm.moveFocus(FocusDirection.Up) }
                else -> false
            }
        },
        textStyle = TextStyle(color = Fg, fontSize = size),
        singleLine = true,
        cursorBrush = SolidColor(Accent),
        keyboardOptions = KeyboardOptions(
            keyboardType = if (uri) KeyboardType.Uri else KeyboardType.Text,
            imeAction = if (uri) ImeAction.Done else ImeAction.Search,
        ),
        keyboardActions = KeyboardActions(onAny = { kb?.hide(); onSubmit() }),
        decorationBox = { inner ->
            Column {
                Box(Modifier.height(size.value.dp * 2.2f), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) T(placeholder, size, FgDim)
                    inner()
                }
                Box(Modifier.width(width).height(if (focused) 3.dp else 1.5.dp).background(if (focused) Accent else Fg))
            }
        },
    )
}

@Composable
private fun SettingsScreen(app: AppState, firstRun: Boolean) {
    if (!firstRun) BackHandler { app.backToNav() }
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(app.store.serverUrl.ifEmpty { "http://" }) }
    var status by remember { mutableStateOf(app.setupError ?: "") }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    fun normalized() = url.trim().trimEnd('/').let { if (it.startsWith("http")) it else "http://$it" }

    Column(
        Modifier.fillMaxSize().background(PageBg).padding(start = 64.dp, top = 40.dp, end = 64.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        T(if (firstRun) "连接 hongguo-web 服务器" else "设置", 30.sp, weight = FontWeight.Bold)
        T("服务器地址", 16.sp, FgDim)
        TvTextField(url, { url = it }, "http://192.168.1.2:8000", Modifier.focusRequester(first), width = 420.dp, size = 20.sp, uri = true)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Pill("测试连接", {
                status = "正在连接…"
                scope.launch {
                    status = runCatching { app.api.ping(normalized()) }.fold({ "连接成功" }, { "连接失败：${it.message}" })
                }
            })
            Pill("保存", {
                app.store.setServer(normalized())
                url = normalized()
                status = "已保存，正在加载…"
                app.boot(show = firstRun)
            })
        }
        if (status.isNotEmpty()) T(status, 13.sp, if (status.contains("失败") || status.contains("无法")) Accent else FgDim, maxLines = 2)
        if (!firstRun) {
            Spacer(Modifier.height(6.dp))
            T("视频两侧", 16.sp, FgDim)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Pill("模糊填满", { app.store.sides = Sides.Blur }, selected = app.store.sides == Sides.Blur)
                Pill("纯黑", { app.store.sides = Sides.Black }, selected = app.store.sides == Sides.Black)
            }
            Spacer(Modifier.height(6.dp))
            T("观看记录 · ${app.store.watched.size} 部", 16.sp, FgDim)
            Pill("清除观看记录", { app.store.clearWatched(); status = "观看记录已清除" })
            app.user?.let { u ->
                Spacer(Modifier.height(6.dp))
                T("账号 · ${u.name.ifEmpty { "已登录" }}", 16.sp, FgDim)
                Pill("退出登录", {
                    scope.launch { runCatching { app.api.logout() }; app.user = null; status = "已退出登录" }
                })
            }
        }
    }
}
