package ais.tee.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ais.tee.ui.viewmodel.ChatGptPlanViewModel

/**
 * Per-chat ChatGPT funding route: API key or one signed-in account's plan. Lists only accounts
 * with plan usage granted, plus the chat's current binding so a signed-out one can be switched off.
 */
@Composable
internal fun ChatGptPlanRoutePicker(
    accounts: List<ChatGptPlanViewModel.AccountRow>,
    boundClientId: String?,
    onRefresh: () -> Unit,
    onSelect: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { onRefresh() }
    val bound = boundClientId?.let { id -> accounts.firstOrNull { it.clientId == id } }
    val usable = accounts.filter { it.connected && it.planUsageGranted }
    val label = when {
        boundClientId == null -> "API key"
        bound == null -> "ChatGPT plan (account removed)"
        bound.connected && bound.planUsageGranted -> "ChatGPT plan · ${bound.label}"
        else -> "ChatGPT plan · ${bound.label} (unavailable)"
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .clickable { expanded = true }
            .padding(horizontal = 4.dp, vertical = 2.dp)
            .testTag("chatgpt_route_menu"),
    ) {
        Text(
            text = "ChatGPT via: $label",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
        )
        Icon(
            imageVector = Icons.Default.ArrowDropDown,
            contentDescription = "Change ChatGPT route",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            RouteItem("API key", "Metered OpenAI API billing", boundClientId == null, "chatgpt_route_api_key") {
                onSelect(null)
                expanded = false
            }
            usable.forEach { account ->
                RouteItem(
                    title = account.label,
                    detail = "ChatGPT plan usage",
                    selected = account.clientId == boundClientId,
                    tag = "chatgpt_route_plan",
                ) {
                    onSelect(account.clientId)
                    expanded = false
                }
            }
            if (usable.isEmpty()) {
                DropdownMenuItem(
                    text = {
                        Text(
                            "Sign in under API keys → ChatGPT plan to use a plan.",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                    onClick = { expanded = false },
                    enabled = false,
                )
            }
        }
    }
}

@Composable
private fun RouteItem(title: String, detail: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Column {
                Text(title, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                Text(
                    detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        onClick = onClick,
        leadingIcon = {
            if (selected) Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
        },
        modifier = Modifier.testTag(tag),
    )
}
