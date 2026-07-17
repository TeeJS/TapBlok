package com.cj.tapblok

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.cj.tapblok.database.AppDatabase
import com.cj.tapblok.database.BlockedApp
import com.cj.tapblok.database.TagUnlockMode
import com.cj.tapblok.ui.theme.TapBlokTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Per-app rule overrides.
 *
 * Every field here is optional. Null means "inherit the default", resolved when the rule is
 * evaluated rather than copied in when the app is added — so changing a default in Settings
 * moves every app that hasn't been explicitly pinned here.
 *
 * That is exactly why each row says *default* or *custom*: an inherited 25 and a pinned 25
 * look identical, and without the label the template idea is impossible to reason about.
 */
class AppOverrideActivity : ComponentActivity() {

    companion object {
        const val EXTRA_PACKAGE = "com.tj.tapblok.extra.OVERRIDE_PACKAGE"

        fun intent(context: Context, packageName: String) =
            Intent(context, AppOverrideActivity::class.java).putExtra(EXTRA_PACKAGE, packageName)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val packageName = intent.getStringExtra(EXTRA_PACKAGE)
        if (packageName == null) {
            finish()
            return
        }

        setContent {
            TapBlokTheme {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text(appLabel(packageName)) },
                            navigationIcon = {
                                IconButton(onClick = { finish() }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                                }
                            }
                        )
                    }
                ) { padding ->
                    AppOverrideScreen(packageName, Modifier.padding(padding))
                }
            }
        }
    }

    private fun appLabel(packageName: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        packageName
    }
}

@Composable
private fun AppOverrideScreen(packageName: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = remember { AppDatabase.getDatabase(context).blockedAppDao() }
    val defaults = remember { AppSettings.defaults(context) }

    var app by remember { mutableStateOf<BlockedApp?>(null) }
    var loading by remember { mutableStateOf(true) }

    // Same rule as app selection and the rest of Settings: rules can't be loosened mid-session,
    // or "set YouTube to 999 minutes" walks around the entire feature
    val editable = !isServiceRunning(context, AppMonitoringService::class.java)

    LaunchedEffect(packageName) {
        app = withContext(Dispatchers.IO) { dao.getByPackage(packageName) }
        loading = false
    }

    fun save(updated: BlockedApp) {
        app = updated
        scope.launch(Dispatchers.IO) { dao.update(updated) }
    }

    val current = app
    if (loading || current == null) {
        Box(modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
            if (loading) CircularProgressIndicator() else Text("This app isn't blocked.")
        }
        return
    }

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
                    text = "Limits are locked while a session is active.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }

        if (current.groupId != null) {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "This app is in a group and shares that group's budget, so its own " +
                        "limits don't apply. Remove it from the group to give it separate limits.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp)
                )
            }
            return@Column
        }

        SettingsSection(title = "Limits") {
            Text(
                text = "Leave a value on “default” and it follows the template in Settings — " +
                    "change the default there and this app follows. Set one here and this app " +
                    "stops following.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            MinutesRow(
                label = "Session limit",
                caption = "Continuous use before it locks",
                minutes = current.sessionMinutes ?: defaults.sessionMinutes,
                enabled = editable,
                isDefault = current.sessionMinutes == null,
                onPicked = { save(current.copy(sessionMinutes = it)) }
            )
            MinutesRow(
                label = "Daily cap",
                caption = "Only the daily reset clears this — the tag can't",
                minutes = current.dailyMinutes ?: defaults.dailyMinutes,
                enabled = editable,
                zeroLabel = "No cap",
                isDefault = current.dailyMinutes == null,
                onPicked = { save(current.copy(dailyMinutes = it)) }
            )
            MinutesRow(
                label = "Reset time",
                caption = "Frees a locked app, and clears a part-used session",
                minutes = current.resetMinutes ?: defaults.resetMinutes,
                enabled = editable,
                isDefault = current.resetMinutes == null,
                onPicked = { save(current.copy(resetMinutes = it)) }
            )
        }

        SettingsSection(title = "Tag behaviour") {
            val effectiveMode = current.tagUnlockMode ?: defaults.tagUnlockMode
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = effectiveMode == TagUnlockMode.SKIP_THE_WAIT,
                    enabled = editable,
                    onClick = { save(current.copy(tagUnlockMode = TagUnlockMode.SKIP_THE_WAIT)) },
                    label = { Text("Fresh session") }
                )
                FilterChip(
                    selected = effectiveMode == TagUnlockMode.GRACE_WINDOW,
                    enabled = editable,
                    onClick = { save(current.copy(tagUnlockMode = TagUnlockMode.GRACE_WINDOW)) },
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
                    onPicked = { save(current.copy(graceMinutes = it)) }
                )
            }
        }

        val hasOverrides = with(current) {
            sessionMinutes != null || dailyMinutes != null || resetMinutes != null ||
                tagUnlockMode != null || graceMinutes != null
        }
        if (hasOverrides) {
            OutlinedButton(
                onClick = {
                    save(
                        current.copy(
                            sessionMinutes = null,
                            dailyMinutes = null,
                            resetMinutes = null,
                            tagUnlockMode = null,
                            graceMinutes = null
                        )
                    )
                },
                enabled = editable,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Reset to defaults")
            }
        }
    }
}
