package com.cj.tapblok

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.cj.tapblok.database.AppDatabase
import com.cj.tapblok.database.AppGroup
import com.cj.tapblok.database.BlockedApp
import com.cj.tapblok.database.TagUnlockMode
import com.cj.tapblok.ui.theme.TapBlokTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Create or edit one shared-budget group: its name, the rules every member shares, and which
 * apps belong to it.
 *
 * A group is the only place limits are shared. Every member draws from one session budget,
 * one daily cap, one cooldown — which is what closes substitution (lock YouTube, and TikTok
 * is already spent too). Because of that, a member has no rules of its own: the group's win
 * entirely, so this screen owns the numbers and the per-app override screen steps aside for
 * grouped apps.
 *
 * Rule fields inherit the global default when left unset, exactly like the per-app template —
 * a group is a shared override of the same defaults, not a separate world.
 */
class AppGroupEditActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_GROUP_ID = "com.tj.tapblok.extra.GROUP_ID"

        fun intent(context: Context, groupId: String) =
            Intent(context, AppGroupEditActivity::class.java).putExtra(EXTRA_GROUP_ID, groupId)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val groupId = intent.getStringExtra(EXTRA_GROUP_ID)
        if (groupId == null) {
            finish()
            return
        }

        setContent {
            TapBlokTheme {
                var title by remember { mutableStateOf("Group") }
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text(title) },
                            navigationIcon = {
                                IconButton(onClick = { finish() }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                                }
                            }
                        )
                    }
                ) { padding ->
                    AppGroupEditScreen(
                        groupId = groupId,
                        onNameResolved = { title = it },
                        onDeleted = { finish() },
                        modifier = Modifier.padding(padding)
                    )
                }
            }
        }
    }
}

private data class MemberRow(
    val packageName: String,
    val label: String,
    val inThisGroup: Boolean,
    val otherGroupName: String?
)

@Composable
private fun AppGroupEditScreen(
    groupId: String,
    onNameResolved: (String) -> Unit,
    onDeleted: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.getDatabase(context) }
    val defaults = remember { AppSettings.defaults(context) }

    var group by remember { mutableStateOf<AppGroup?>(null) }
    var members by remember { mutableStateOf<List<MemberRow>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    // Same rule as everywhere else: membership and limits can't be changed mid-session, or a
    // group could be loosened while it's actively enforcing.
    val editable = !isServiceRunning(context, AppMonitoringService::class.java)

    suspend fun reloadMembers(currentGroup: AppGroup) {
        val pm = context.packageManager
        val allGroups = db.appGroupDao().getAll().associateBy { it.groupId }
        members = db.blockedAppDao().getAllBlockedAppsList()
            .map { app ->
                MemberRow(
                    packageName = app.packageName,
                    label = runCatching {
                        pm.getApplicationLabel(pm.getApplicationInfo(app.packageName, 0)).toString()
                    }.getOrDefault(app.packageName),
                    inThisGroup = app.groupId == currentGroup.groupId,
                    otherGroupName = app.groupId
                        ?.takeIf { it != currentGroup.groupId }
                        ?.let { allGroups[it]?.name }
                )
            }
            .sortedBy { it.label.lowercase() }
    }

    LaunchedEffect(groupId) {
        val g = withContext(Dispatchers.IO) { db.appGroupDao().get(groupId) }
        if (g != null) {
            group = g
            onNameResolved(g.name)
            withContext(Dispatchers.IO) { reloadMembers(g) }
        }
        loading = false
    }

    fun saveGroup(updated: AppGroup) {
        group = updated
        onNameResolved(updated.name)
        scope.launch(Dispatchers.IO) { db.appGroupDao().update(updated) }
    }

    fun toggleMember(row: MemberRow, include: Boolean) {
        scope.launch(Dispatchers.IO) {
            db.blockedAppDao().setGroup(row.packageName, if (include) groupId else null)
            group?.let { reloadMembers(it) }
        }
    }

    fun deleteGroup() {
        scope.launch(Dispatchers.IO) {
            // Detach members first so no blocked_apps row is left pointing at a dead scope.
            db.blockedAppDao().clearGroup(groupId)
            group?.let { db.appGroupDao().delete(it) }
            withContext(Dispatchers.Main) { onDeleted() }
        }
    }

    val current = group
    if (loading || current == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (loading) CircularProgressIndicator() else Text("This group no longer exists.")
        }
        return
    }

    var renaming by remember { mutableStateOf(false) }
    val memberCount = members.count { it.inThisGroup }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (!editable) {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Groups are locked while a session is active.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }

        SettingsSection(title = "Name") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = editable) { renaming = true }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(current.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text(
                    "Rename",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (editable) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                )
            }
        }

        SettingsSection(title = "Shared limits") {
            Text(
                text = "One budget for the whole group. Everyone in it draws from the same " +
                    "session, daily cap and reset — that's what stops hopping between apps to " +
                    "dodge a limit.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            MinutesRow(
                label = "Session limit",
                caption = "Combined continuous use before the group locks",
                minutes = current.sessionMinutes ?: defaults.sessionMinutes,
                enabled = editable,
                isDefault = current.sessionMinutes == null,
                onPicked = { saveGroup(current.copy(sessionMinutes = it)) }
            )
            MinutesRow(
                label = "Daily cap",
                caption = "Combined per day. Only the daily reset clears it — not the tag.",
                minutes = current.dailyMinutes ?: defaults.dailyMinutes,
                enabled = editable,
                zeroLabel = "No cap",
                isDefault = current.dailyMinutes == null,
                onPicked = { saveGroup(current.copy(dailyMinutes = it)) }
            )
            MinutesRow(
                label = "Reset time",
                caption = "Frees a locked group, and clears a part-used session",
                minutes = current.resetMinutes ?: defaults.resetMinutes,
                enabled = editable,
                isDefault = current.resetMinutes == null,
                onPicked = { saveGroup(current.copy(resetMinutes = it)) }
            )
        }

        SettingsSection(title = "Tag behaviour") {
            val effectiveMode = current.tagUnlockMode ?: defaults.tagUnlockMode
            Text(
                text = "A tag tap on any member unlocks the whole group.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = effectiveMode == TagUnlockMode.SKIP_THE_WAIT,
                    enabled = editable,
                    onClick = { saveGroup(current.copy(tagUnlockMode = TagUnlockMode.SKIP_THE_WAIT)) },
                    label = { Text("Fresh session") }
                )
                FilterChip(
                    selected = effectiveMode == TagUnlockMode.GRACE_WINDOW,
                    enabled = editable,
                    onClick = { saveGroup(current.copy(tagUnlockMode = TagUnlockMode.GRACE_WINDOW)) },
                    label = { Text("Timed unlock") }
                )
            }
            Text(
                text = if (current.tagUnlockMode == null) "Following the default" else "Custom",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (effectiveMode == TagUnlockMode.GRACE_WINDOW) {
                MinutesRow(
                    label = "Unlock window",
                    minutes = current.graceMinutes ?: defaults.graceMinutes,
                    enabled = editable,
                    isDefault = current.graceMinutes == null,
                    onPicked = { saveGroup(current.copy(graceMinutes = it)) }
                )
            }
        }

        SettingsSection(title = "Apps in this group ($memberCount)") {
            if (members.isEmpty()) {
                Text(
                    text = "No blocked apps yet. Add apps from “Manage Blocked Apps” first, " +
                        "then include them here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            members.forEach { row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = editable) { toggleMember(row, !row.inThisGroup) }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(row.label, style = MaterialTheme.typography.bodyLarge)
                        // Checking an app already in another group moves it here — say so, so
                        // that isn't a silent surprise.
                        row.otherGroupName?.takeIf { !row.inThisGroup }?.let {
                            Text(
                                "Currently in “$it” — adding moves it here",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Checkbox(
                        checked = row.inThisGroup,
                        onCheckedChange = { toggleMember(row, it) },
                        enabled = editable
                    )
                }
            }
        }

        OutlinedButton(
            onClick = { deleteGroup() },
            enabled = editable,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
        ) {
            Text("Delete group")
        }
        Text(
            text = "Deleting a group returns its apps to their own individual limits.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    if (renaming) {
        TextFieldDialog(
            title = "Rename group",
            initial = current.name,
            onDismiss = { renaming = false },
            onConfirm = { renaming = false; saveGroup(current.copy(name = it)) }
        )
    }
}
