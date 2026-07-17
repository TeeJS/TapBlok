package com.cj.tapblok

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.cj.tapblok.database.AppDatabase
import com.cj.tapblok.database.AppGroup
import com.cj.tapblok.database.Defaults
import com.cj.tapblok.ui.theme.TapBlokTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Lists shared-budget groups and creates new ones. Editing a group, including choosing its
 * members, happens in [AppGroupEditActivity].
 */
class AppGroupsActivity : ComponentActivity() {

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TapBlokTheme {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("App Groups") },
                            navigationIcon = {
                                IconButton(onClick = { finish() }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                                }
                            }
                        )
                    }
                ) { padding ->
                    AppGroupsScreen(Modifier.padding(padding))
                }
            }
        }
    }
}

private data class GroupRow(val group: AppGroup, val memberCount: Int)

@Composable
private fun AppGroupsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.getDatabase(context) }
    val defaults = remember { AppSettings.defaults(context) }

    val editable = !isServiceRunning(context, AppMonitoringService::class.java)

    // Membership lives in blocked_apps, not app_groups — deriving counts only when the
    // groups table changed left them stale after the edit screen added a member (the list
    // said "0 apps" with a member in the group). Observing both tables makes the row
    // reactive to either kind of change.
    val groups by db.appGroupDao().observeAll().collectAsState(initial = emptyList())
    val blockedApps by db.blockedAppDao().getAllBlockedApps().collectAsState(initial = emptyList())
    var creating by remember { mutableStateOf(false) }

    val rows = remember(groups, blockedApps) {
        val counts = blockedApps.groupingBy { it.groupId }.eachCount()
        groups.map { g -> GroupRow(g, counts[g.groupId] ?: 0) }
            .sortedBy { it.group.name.lowercase() }
    }

    fun createGroup(name: String) {
        val id = UUID.randomUUID().toString()
        scope.launch(Dispatchers.IO) {
            // All rule fields null → the group inherits the global defaults until the user
            // sets its own. Same template semantics as apps.
            db.appGroupDao().upsert(AppGroup(groupId = id, name = name))
        }
        context.startActivity(AppGroupEditActivity.intent(context, id))
    }

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(12.dp))
        Text(
            text = "A group shares one budget across several apps — a “Short video” group over " +
                "TikTok, Instagram and YouTube shares a single daily cap, so you can't dodge it " +
                "by switching apps.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))

        Button(
            onClick = { creating = true },
            enabled = editable,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("New group")
        }

        if (!editable) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Groups are locked while a session is active.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        Spacer(Modifier.height(16.dp))

        if (rows.isEmpty()) {
            Text(
                text = "No groups yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            LazyColumn {
                items(rows, key = { it.group.groupId }) { row ->
                    GroupListItem(
                        row = row,
                        defaults = defaults,
                        onClick = {
                            context.startActivity(AppGroupEditActivity.intent(context, row.group.groupId))
                        }
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    if (creating) {
        TextFieldDialog(
            title = "New group",
            initial = "",
            onDismiss = { creating = false },
            onConfirm = { creating = false; createGroup(it) }
        )
    }
}

@Composable
private fun GroupListItem(row: GroupRow, defaults: Defaults, onClick: () -> Unit) {
    val g = row.group
    val session = g.sessionMinutes ?: defaults.sessionMinutes
    val daily = g.dailyMinutes ?: defaults.dailyMinutes
    val reset = g.resetMinutes ?: defaults.resetMinutes
    val dailyText = if (daily > 0) "${AppSettings.formatMinutes(daily)} daily" else "no daily cap"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 12.dp)
    ) {
        Text(g.name, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = "${row.memberCount} app${if (row.memberCount == 1) "" else "s"} · " +
                "${AppSettings.formatMinutes(session)} · " +
                "${AppSettings.formatMinutes(reset)} reset · $dailyText",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
