package dev.davidx3d.wisp.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.davidx3d.wisp.tools.PermissionChecker
import dev.davidx3d.wisp.tools.Tool
import dev.davidx3d.wisp.tools.ToolRegistry
import dev.davidx3d.wisp.tools.impl.*
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/** Every tool the assistant knows. Add new ones here. */
fun allTools(http: OkHttpClient): List<Tool> = listOf(
    SetTimerTool(), CancelTimerTool(), SetAlarmTool(), NextAlarmTool(), DismissAlarmTool(),
    CalendarEventsTool(), CreateEventTool(), CreateReminderTool(),
    FindContactTool(), CallTool(), SendSmsTool(), ReadSmsTool(), ReadNotificationsTool(), ReplyToMessageTool(),
    FlashlightTool(), VolumeTool(), DndTool(), BrightnessTool(), RotationTool(), DeviceStatusTool(),
    ClipboardTool(), OpenAppTool(), NomiFoodTool(),
    MediaControlTool(), NowPlayingTool(),
    LocationTool(), WeatherTool(http),
    ReadScreenTool(),
    ToggleConnectivityTool(), KillAppTool(), RebootTool(),
)

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /** No logging interceptor on purpose: requests carry the API key in a header. */
    @Provides
    @Singleton
    fun okHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    fun toolRegistry(http: OkHttpClient, checker: PermissionChecker): ToolRegistry =
        ToolRegistry(allTools(http), access = checker::missing)
}
