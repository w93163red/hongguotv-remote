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
    when (app.screen) {
        Screen.Player -> PlayerScreen(app)
        else -> PlayerScreen(app)
    }
}
