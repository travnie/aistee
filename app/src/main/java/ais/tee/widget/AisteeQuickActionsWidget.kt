package ais.tee.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.Button
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Alignment
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import ais.tee.navigation.AisteeQuickActionNavigation

internal data class QuickActionsWidgetButton(val label: String, val destination: String)

/** The toolbar widget shows no conversation data, so it needs no privacy settings. */
internal val QUICK_ACTIONS_WIDGET_BUTTONS = listOf(
    QuickActionsWidgetButton("New chat", AisteeQuickActionNavigation.DESTINATION_NEW_NATIVE_CHAT),
    QuickActionsWidgetButton("Web AI", AisteeQuickActionNavigation.DESTINATION_WEB_AI),
    QuickActionsWidgetButton("Compare", AisteeQuickActionNavigation.DESTINATION_COMPARE),
    QuickActionsWidgetButton("Library", AisteeQuickActionNavigation.DESTINATION_LIBRARY),
)

class AisteeQuickActionsWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { QuickActionsRow(context) }
    }

    @Composable
    private fun QuickActionsRow(context: Context) {
        Row(
            modifier = GlanceModifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            QUICK_ACTIONS_WIDGET_BUTTONS.forEach { button ->
                Button(
                    text = button.label,
                    onClick = actionStartActivity(
                        AisteeQuickActionNavigation.launchIntent(context, button.destination)
                    ),
                    modifier = GlanceModifier.padding(horizontal = 2.dp),
                )
            }
        }
    }
}

class AisteeQuickActionsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = AisteeQuickActionsWidget()
}
