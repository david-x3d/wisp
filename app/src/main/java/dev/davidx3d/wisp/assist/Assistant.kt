package dev.davidx3d.wisp.assist

import android.app.PendingIntent
import android.app.assist.AssistContent
import android.app.assist.AssistStructure
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.view.View
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import android.widget.RemoteViews
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.davidx3d.wisp.R
import dev.davidx3d.wisp.data.settings.SettingsRepository
import dev.davidx3d.wisp.services.ScreenContext
import dev.davidx3d.wisp.session.VoiceSessionManager
import dev.davidx3d.wisp.ui.AssistantActivity
import dev.davidx3d.wisp.ui.overlay.AssistantOverlay
import dev.davidx3d.wisp.ui.theme.WispTheme

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WispEntryPoint {
    fun manager(): VoiceSessionManager
    fun settings(): SettingsRepository
}

fun Context.entryPoint(): WispEntryPoint = EntryPointAccessors.fromApplication(applicationContext, WispEntryPoint::class.java)

/** Makes Wisp selectable as the default digital assistant. */
class WispVoiceInteractionService : VoiceInteractionService()

class WispSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = WispSession(this)
}

/**
 * The system requires a recognition service next to a VoiceInteractionService. Wisp does
 * its own speech handling, so this one politely declines.
 */
class WispRecognitionService : RecognitionService() {
    override fun onStartListening(intent: Intent?, callback: Callback?) {
        callback?.error(android.speech.SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
    }

    override fun onCancel(callback: Callback?) = Unit
    override fun onStopListening(callback: Callback?) = Unit
}

/**
 * The Gemini-style overlay: a bottom sheet drawn in the assistant session window above
 * whatever app is open. Starts a conversation right away.
 */
class WispSession(context: Context) : VoiceInteractionSession(context), LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()
    private val manager = context.entryPoint().manager()

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = store

    override fun onCreate() {
        super.onCreate()
        savedState.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        window?.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            setDecorFitsSystemWindows(false)
            navigationBarColor = Color.TRANSPARENT
            statusBarColor = Color.TRANSPARENT
        }
    }

    override fun onCreateContentView(): View = ComposeView(context).apply {
        setViewTreeLifecycleOwner(this@WispSession)
        setViewTreeSavedStateRegistryOwner(this@WispSession)
        setViewTreeViewModelStoreOwner(this@WispSession)
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        setContent {
            WispTheme {
                AssistantOverlay(
                    manager = manager,
                    onDismiss = { hide() },
                    onOpenApp = {
                        startAssistantActivity(Intent(context, dev.davidx3d.wisp.ui.MainActivity::class.java))
                        hide()
                    },
                )
            }
        }
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        if (!manager.state.value.active) manager.start()
    }

    override fun onHandleAssist(state: AssistState) {
        ScreenContext.set(state.assistStructure)
    }

    @Deprecated("Deprecated in Java")
    override fun onHandleAssist(data: Bundle?, structure: AssistStructure?, content: AssistContent?) {
        ScreenContext.set(structure)
    }

    override fun onHide() {
        super.onHide()
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        // Closing the overlay ends the conversation, like Gemini does.
        if (manager.state.value.active) manager.stop()
    }

    override fun onDestroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        store.clear()
        super.onDestroy()
    }
}

/** Quick Settings tile: tap to talk, tap again to stop. */
class WispTileService : TileService() {
    override fun onStartListening() {
        val active = applicationContext.entryPoint().manager().state.value.active
        qsTile?.apply {
            state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            subtitle = if (active) "Hört zu" else "Tippen zum Sprechen"
            updateTile()
        }
    }

    override fun onClick() {
        val manager = applicationContext.entryPoint().manager()
        if (manager.state.value.active) {
            manager.stop()
            onStartListening()
            return
        }
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, AssistantActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        startActivityAndCollapse(pi)
    }
}

/** Home screen widget: one big pill that starts a conversation. */
class WispWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pi = PendingIntent.getActivity(
            context, 0,
            Intent(context, AssistantActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        ids.forEach { id ->
            val views = RemoteViews(context.packageName, R.layout.widget_wisp).apply {
                setOnClickPendingIntent(R.id.widget_root, pi)
            }
            manager.updateAppWidget(id, views)
        }
    }
}
