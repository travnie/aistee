package ais.tee.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.AppWidgetId
import androidx.lifecycle.lifecycleScope
import ais.tee.R
import ais.tee.data.preferences.AisteeWidgetPreferencesStore
import ais.tee.ui.theme.AisteeTheme
import kotlinx.coroutines.launch

class AisteeWidgetConfigurationActivity : ComponentActivity() {
    private var appWidgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(Activity.RESULT_CANCELED)

        appWidgetId = intent?.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        val preferences = AisteeWidgetPreferencesStore(this)
        val initialShowTitles = preferences.showConversationTitles(appWidgetId)
        setContent {
            AisteeTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    WidgetPrivacyConfiguration(
                        initialShowTitles = initialShowTitles,
                        onCancel = ::finish,
                        onSave = { showConversationTitles ->
                            saveAndFinish(preferences, showConversationTitles)
                        },
                    )
                }
            }
        }
    }

    private fun saveAndFinish(
        preferences: AisteeWidgetPreferencesStore,
        showConversationTitles: Boolean,
    ) {
        preferences.saveShowConversationTitles(appWidgetId, showConversationTitles)
        lifecycleScope.launch {
            runCatching {
                AisteeWidget().update(this@AisteeWidgetConfigurationActivity, AppWidgetId(appWidgetId))
            }
            setResult(
                Activity.RESULT_OK,
                Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId),
            )
            finish()
        }
    }
}

@Composable
private fun WidgetPrivacyConfiguration(
    initialShowTitles: Boolean,
    onCancel: () -> Unit,
    onSave: (Boolean) -> Unit,
) {
    var showTitles by rememberSaveable { mutableStateOf(initialShowTitles) }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(
            text = "Aistee widget",
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = "Conversation titles are hidden by default. You can expose them on the launcher for this widget only.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Show conversation titles",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "When off, recent chats appear as Recent chat 1, 2, and 3.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(
                checked = showTitles,
                onCheckedChange = { showTitles = it },
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onCancel) {
                Text("Cancel")
            }
            Button(
                onClick = { onSave(showTitles) },
                modifier = Modifier.padding(start = 8.dp),
            ) {
                Text("Save")
            }
        }
    }
}
