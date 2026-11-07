package dev.davidx3d.wisp.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dagger.hilt.android.AndroidEntryPoint
import dev.davidx3d.wisp.session.VoiceSessionManager
import dev.davidx3d.wisp.ui.history.HistoryDetailScreen
import dev.davidx3d.wisp.ui.history.HistoryScreen
import dev.davidx3d.wisp.ui.home.HomeScreen
import dev.davidx3d.wisp.ui.onboarding.OnboardingScreen
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import dev.davidx3d.wisp.ui.overlay.AssistantOverlay
import dev.davidx3d.wisp.ui.settings.AssistantScreen
import dev.davidx3d.wisp.ui.settings.ConversationScreen
import dev.davidx3d.wisp.ui.settings.PersonaScreen
import dev.davidx3d.wisp.ui.settings.ProviderScreen
import dev.davidx3d.wisp.ui.settings.Routes
import dev.davidx3d.wisp.ui.settings.SettingsHome
import dev.davidx3d.wisp.ui.settings.ToolsScreen
import dev.davidx3d.wisp.ui.settings.UsageScreen
import dev.davidx3d.wisp.ui.settings.VoiceScreen
import dev.davidx3d.wisp.ui.theme.WispTheme
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            WispTheme {
                val start: StartViewModel = hiltViewModel()
                val initial by start.settings.collectAsState()
                // Wait for DataStore so returning users never see the onboarding flash by.
                val ready = initial ?: run {
                    androidx.compose.foundation.layout.Box(
                        androidx.compose.ui.Modifier.fillMaxSize()
                            .background(androidx.compose.material3.MaterialTheme.colorScheme.surface),
                    )
                    return@WispTheme
                }
                val startRoute = remember { if (ready.onboardingDone) Routes.HOME else "onboarding" }
                val nav = rememberNavController()
                val enter = slideInHorizontally { it / 4 } + fadeIn()
                val exit = slideOutHorizontally { -it / 6 } + fadeOut()
                val popEnter = slideInHorizontally { -it / 6 } + fadeIn()
                val popExit = slideOutHorizontally { it / 4 } + fadeOut()
                NavHost(
                    nav, startRoute,
                    enterTransition = { enter }, exitTransition = { exit },
                    popEnterTransition = { popEnter }, popExitTransition = { popExit },
                ) {
                    composable(Routes.HOME, enterTransition = { fadeIn() + scaleIn(initialScale = 0.97f) }, exitTransition = { fadeOut() + scaleOut(targetScale = 0.97f) }) {
                        val vm: HomeViewModel = hiltViewModel()
                        val settings by vm.settings.collectAsState()
                        HomeScreen(
                            manager = vm.manager, settings = settings,
                            onSettings = { nav.navigate(Routes.SETTINGS) },
                            onHistory = { nav.navigate(Routes.HISTORY) },
                            onNeedsKey = { nav.navigate(Routes.PROVIDER) },
                        )
                    }
                    composable("onboarding") {
                        OnboardingScreen(hiltViewModel()) {
                            nav.navigate(Routes.HOME) { popUpTo("onboarding") { inclusive = true } }
                        }
                    }
                    val back: () -> Unit = { nav.popBackStack() }
                    composable(Routes.SETTINGS) { SettingsHome(hiltViewModel(), back) { nav.navigate(it) } }
                    composable(Routes.PROVIDER) { ProviderScreen(hiltViewModel(), back) }
                    composable(Routes.VOICE) { VoiceScreen(hiltViewModel(), back) }
                    composable(Routes.PERSONA) { PersonaScreen(hiltViewModel(), back) }
                    composable(Routes.CONVERSATION) { ConversationScreen(hiltViewModel(), back) }
                    composable(Routes.TOOLS) { ToolsScreen(hiltViewModel(), back) }
                    composable(Routes.ASSISTANT) { AssistantScreen(hiltViewModel(), back) }
                    composable(Routes.USAGE) { UsageScreen(hiltViewModel(), back) }
                    composable(Routes.HISTORY) { HistoryScreen(hiltViewModel(), back) { nav.navigate("history/$it") } }
                    composable("history/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { e ->
                        HistoryDetailScreen(hiltViewModel(), e.arguments?.getLong("id") ?: 0, back)
                    }
                }
            }
        }
    }
}

/**
 * Translucent overlay used by the Quick Settings tile, the widget and ACTION_ASSIST.
 * (When Wisp is the default assistant, the system uses WispSession instead.)
 */
@AndroidEntryPoint
class AssistantActivity : ComponentActivity() {
    @Inject lateinit var manager: VoiceSessionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        val micOk = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!micOk) {
            // Permissions need a full activity; send the user to the app once.
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }
        if (savedInstanceState == null && !manager.state.value.active) manager.start()
        setContent {
            WispTheme {
                AssistantOverlay(
                    manager = manager,
                    onDismiss = {
                        if (manager.state.value.active) manager.stop()
                        finish()
                    },
                    onOpenApp = {
                        startActivity(Intent(this, MainActivity::class.java))
                        finish()
                    },
                )
            }
        }
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, android.R.anim.fade_out)
    }
}
