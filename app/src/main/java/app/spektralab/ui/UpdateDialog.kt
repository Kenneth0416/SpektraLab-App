package app.spektralab.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.spektralab.R
import app.spektralab.update.AppRelease

@Composable
fun UpdateDialog(release: AppRelease, onLater: () -> Unit, onOpened: () -> Unit) {
    val context = LocalContext.current
    UpdateDialogContent(release, onLater) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.downloadUrl)))
            onOpened()
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
}

@Composable
internal fun UpdateDialogContent(release: AppRelease, onLater: () -> Unit, onOpen: () -> Boolean) {
    var openFailed by remember(release) { mutableStateOf(false) }
    val locale = LocalConfiguration.current.locales[0]
    AlertDialog(
        onDismissRequest = onLater,
        modifier = Modifier.testTag("update_dialog"),
        containerColor = Pal.paper,
        titleContentColor = Pal.ink,
        textContentColor = Pal.ink,
        title = { Text(stringResource(R.string.update_available, release.versionName)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(release.notesFor(locale))
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.update_install_hint), color = Pal.grey)
                if (openFailed) {
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.update_open_failed), color = Pal.red,
                        modifier = Modifier.testTag("update_open_failed"))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { openFailed = !onOpen() }, modifier = Modifier.testTag("update_open")) {
                Text(stringResource(R.string.update_download), color = Pal.red)
            }
        },
        dismissButton = {
            TextButton(onClick = onLater, modifier = Modifier.testTag("update_later")) {
                Text(stringResource(R.string.update_later), color = Pal.ink)
            }
        },
    )
}
