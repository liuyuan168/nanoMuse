package io.github.nanomuse.assistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.SupportAgent
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.openminis.app.R
import com.openminis.app.i18n.uppercaseForDisplay
import io.github.nanomuse.wakeword.WakeWordService
import io.github.nanomuse.wakeword.WakeWordSettings
import kotlinx.coroutines.launch

/**
 * nanoMuse: the "Wake word and assistant" section inside Settings ›
 * Background (inserted from BackgroundSettingsScreen). Two rows:
 *
 *  1. Voice wake word — the toggle for [WakeWordService]. Turning it on
 *     without the RECORD_AUDIO grant sends the permission dialog first; the
 *     row re-probes on ON_RESUME so coming back from the system dialog
 *     updates the footer immediately.
 *  2. Digital assistant app — deep-links to the OS "digital assistant app"
 *     picker so nanoMuse can be chosen for the power-key/assistant gesture.
 *
 * The Bg* row helpers in BackgroundSettingsScreen are file-private by
 * design, so the rows here are local and visually matched to them.
 */
@Composable
fun AssistantSettingsSection() {
    val context = LocalContext.current
    val settings = remember { WakeWordSettings(context) }
    val enabled by settings.enabled.collectAsState(initial = false)
    val scope = rememberCoroutineScope()

    var micGranted by remember {
        mutableStateOf(hasMicPermission(context))
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    micGranted = hasMicPermission(context)
                }
            },
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        micGranted = granted
        if (granted) {
            scope.launch {
                settings.setEnabled(true)
                WakeWordService.start(context)
            }
        }
    }

    Spacer(Modifier.size(8.dp))
    SectionTitle(stringResource(R.string.nm_wake_section))
    SettingsRow(
        icon = Icons.Outlined.Mic,
        iconColor = Color(0xFFFF375F),
        title = stringResource(R.string.nm_wake_word_row_title),
        content = {
            Switch(
                checked = enabled,
                onCheckedChange = { want ->
                    when {
                        want && !micGranted ->
                            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        want -> scope.launch {
                            settings.setEnabled(true)
                            WakeWordService.start(context)
                        }
                        else -> WakeWordService.stop(context)
                    }
                },
            )
        },
    )
    RowFooter(
        if (!micGranted) {
            stringResource(R.string.nm_wake_word_needs_mic)
        } else {
            stringResource(R.string.nm_wake_word_hint, WakeWordSettings.DEFAULT_PHRASE)
        },
    )

    Spacer(Modifier.size(8.dp))
    SettingsRow(
        icon = Icons.Outlined.SupportAgent,
        iconColor = Color(0xFF0A84FF),
        title = stringResource(R.string.nm_assist_row_title),
        content = {},
        onClick = {
            val intent = Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
            } catch (_: Throwable) {
                // Very old or heavily skinned ROM without the page; the app
                // details screen is the next best route in.
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.parse("package:" + context.packageName))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        },
    )
    RowFooter(stringResource(R.string.nm_assist_footer))
}

private fun hasMicPermission(context: android.content.Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text.uppercaseForDisplay(),
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 6.dp),
    )
}

@Composable
private fun RowFooter(text: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 6.dp),
    )
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    iconColor: Color,
    title: String,
    content: @Composable () -> Unit,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(12.dp))
            .then(
                if (onClick != null) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = iconColor,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = title,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        content()
    }
}
