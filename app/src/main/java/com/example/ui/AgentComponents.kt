package com.example.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Launch
import androidx.compose.material.icons.rounded.AccessibilityNew
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.agent.AgentSettings
import com.example.agent.AgentStepLog
import com.example.agent.AgentUiState
import com.example.agent.StepStatus
import com.example.ui.theme.AmberContainer
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CrimsonContainer
import com.example.ui.theme.CrimsonStop
import com.example.ui.theme.CyberTeal
import com.example.ui.theme.CyberTealDark
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.SlateBorder
import com.example.ui.theme.SlateSurface
import com.example.ui.theme.SlateSurfaceElevated
import com.example.ui.theme.TextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun TelemetryHeader(
    isServiceActive: Boolean,
    isRunning: Boolean,
    onOpenSettings: () -> Unit
) {
    val pulseTransition = rememberInfiniteTransition(label = "status_pulse")
    val pulseAlpha by pulseTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isRunning) 0.3f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )

    val statusColor by animateColorAsState(
        targetValue = if (isServiceActive) CyberTeal else AmberWarning,
        label = "status_color"
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .alpha(pulseAlpha)
                        .clip(CircleShape)
                        .background(statusColor)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isServiceActive) {
                        stringResource(R.string.status_service_active)
                    } else {
                        stringResource(R.string.status_service_inactive)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = stringResource(R.string.hero_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
        }

        IconButton(
            onClick = onOpenSettings,
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .testTag("settings_button")
                .clip(RoundedCornerShape(14.dp))
                .background(SlateSurfaceElevated)
                .border(1.dp, SlateBorder, RoundedCornerShape(14.dp))
        ) {
            Icon(
                imageVector = Icons.Rounded.Settings,
                contentDescription = stringResource(R.string.cd_settings),
                tint = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
fun AccessibilityOnboardingCard(
    onOpenAccessibilitySettings: () -> Unit,
    onRefreshStatus: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = AmberContainer.copy(alpha = 0.45f)),
        border = BorderStroke(1.dp, AmberWarning.copy(alpha = 0.6f)),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.AccessibilityNew,
                    contentDescription = stringResource(R.string.cd_status_indicator),
                    tint = AmberWarning,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.onboarding_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = AmberWarning
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.onboarding_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onOpenAccessibilitySettings,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AmberWarning,
                        contentColor = MaterialTheme.colorScheme.onTertiary
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .minimumInteractiveComponentSize()
                        .testTag("enable_accessibility_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.Launch,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.action_enable_accessibility),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                OutlinedButton(
                    onClick = onRefreshStatus,
                    border = BorderStroke(1.dp, AmberWarning.copy(alpha = 0.7f)),
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("refresh_status_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = stringResource(R.string.action_refresh_status),
                        tint = AmberWarning
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CommandDeckCard(
    goalText: String,
    presets: List<GoalPreset>,
    agentState: AgentUiState,
    isServiceActive: Boolean,
    onGoalChanged: (String) -> Unit,
    onSelectPreset: (GoalPreset) -> Unit,
    onLaunch: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit
) {
    val isRunning = agentState is AgentUiState.Running
    val isPaused = agentState is AgentUiState.Paused
    val isConfirming = agentState is AgentUiState.AwaitingConfirmation
    val isBusy = isRunning || isPaused || isConfirming

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SlateSurface),
        border = BorderStroke(1.dp, SlateBorder),
        shape = MaterialTheme.shapes.large
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.Bolt,
                        contentDescription = null,
                        tint = CyberTeal,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.command_section_title),
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                if (isRunning) {
                    Text(
                        text = "STEP ${agentState.currentStep}/${agentState.maxSteps}",
                        style = MaterialTheme.typography.labelSmall,
                        color = CyberTeal
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = goalText,
                onValueChange = onGoalChanged,
                enabled = !isBusy,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("goal_input_field"),
                label = { Text(stringResource(R.string.command_input_label)) },
                placeholder = { Text(stringResource(R.string.command_input_placeholder)) },
                minLines = 2,
                maxLines = 4,
                trailingIcon = {
                    if (goalText.isNotEmpty() && !isBusy) {
                        IconButton(
                            onClick = { onGoalChanged("") },
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = stringResource(R.string.cd_clear_input)
                            )
                        }
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = CyberTeal,
                    unfocusedBorderColor = SlateBorder,
                    focusedLabelColor = CyberTeal
                )
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = stringResource(R.string.presets_label),
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
            Spacer(modifier = Modifier.height(6.dp))

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                presets.forEach { preset ->
                    AssistChip(
                        onClick = { if (!isBusy) onSelectPreset(preset) },
                        enabled = !isBusy,
                        label = {
                            Text(
                                text = preset.title,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = SlateSurfaceElevated,
                            labelColor = MaterialTheme.colorScheme.onSurface
                        ),
                        border = BorderStroke(1.dp, SlateBorder)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (!isBusy) {
                Button(
                    onClick = onLaunch,
                    enabled = isServiceActive && goalText.isNotBlank(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("launch_agent_button"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CyberTeal,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        disabledContainerColor = SlateSurfaceElevated,
                        disabledContentColor = TextSecondary
                    )
                ) {
                    Icon(
                        imageVector = Icons.Rounded.TouchApp,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.action_launch_agent),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    FilledTonalButton(
                        onClick = { if (isPaused) onResume() else onPause() },
                        enabled = !isConfirming,
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp)
                            .testTag("pause_resume_button"),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = SlateSurfaceElevated,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        )
                    ) {
                        Icon(
                            imageVector = if (isPaused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isPaused) {
                                stringResource(R.string.action_resume_agent)
                            } else {
                                stringResource(R.string.action_pause_agent)
                            }
                        )
                    }

                    Button(
                        onClick = onStop,
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp)
                            .testTag("stop_agent_button"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CrimsonContainer,
                            contentColor = CrimsonStop
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Stop,
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = stringResource(R.string.action_stop_agent))
                    }
                }
            }
        }
    }
}

@Composable
fun SensitiveConfirmCard(
    state: AgentUiState.AwaitingConfirmation,
    onApprove: () -> Unit,
    onCancel: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = AmberContainer.copy(alpha = 0.55f)),
        border = BorderStroke(1.dp, AmberWarning),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.Security,
                    contentDescription = null,
                    tint = AmberWarning
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.confirm_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = AmberWarning
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = state.targetDescription,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = state.safetyReason,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
            Spacer(modifier = Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onCancel,
                    border = BorderStroke(1.dp, CrimsonStop),
                    modifier = Modifier
                        .weight(1f)
                        .minimumInteractiveComponentSize()
                        .testTag("cancel_sensitive_button")
                ) {
                    Text(
                        text = stringResource(R.string.confirm_cancel),
                        color = CrimsonStop
                    )
                }
                Button(
                    onClick = onApprove,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CyberTeal,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .minimumInteractiveComponentSize()
                        .testTag("approve_sensitive_button")
                ) {
                    Text(text = stringResource(R.string.confirm_approve))
                }
            }
        }
    }
}

@Composable
fun AgentOutcomeBanner(state: AgentUiState) {
    when (state) {
        is AgentUiState.Running -> {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = CyberTealDark.copy(alpha = 0.55f)),
                border = BorderStroke(1.dp, CyberTeal.copy(alpha = 0.6f))
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = state.phase.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = CyberTeal
                    )
                    if (state.lastThought.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = state.lastThought,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
        is AgentUiState.Completed -> {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = CyberTealDark.copy(alpha = 0.45f)),
                border = BorderStroke(1.dp, CyberTeal)
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        tint = CyberTeal
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Completed in ${state.totalSteps} step(s)",
                            style = MaterialTheme.typography.titleMedium,
                            color = CyberTeal
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = state.summary,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
        is AgentUiState.Error -> {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = CrimsonContainer.copy(alpha = 0.65f)),
                border = BorderStroke(1.dp, CrimsonStop)
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ErrorOutline,
                        contentDescription = null,
                        tint = CrimsonStop
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Execution Stopped",
                            style = MaterialTheme.typography.titleMedium,
                            color = CrimsonStop
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
        else -> Unit
    }
}

@Composable
fun StepLogCard(log: AgentStepLog) {
    val badgeColor = when (log.status) {
        StepStatus.EXECUTED, StepStatus.APPROVED, StepStatus.COMPLETED -> CyberTeal
        StepStatus.AWAITING_APPROVAL -> AmberWarning
        StepStatus.REJECTED -> AmberWarning
        StepStatus.FAILED -> CrimsonStop
    }
    val formatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    val timeLabel = formatter.format(Date(log.timestampMs))

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SlateSurfaceElevated),
        border = BorderStroke(1.dp, SlateBorder),
        shape = MaterialTheme.shapes.small
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "#${log.stepNumber} ${log.actionType.name}",
                        style = MaterialTheme.typography.labelSmall,
                        color = badgeColor
                    )
                    if (log.requiresConfirmation) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Rounded.WarningAmber,
                            contentDescription = null,
                            tint = AmberWarning,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
                Text(
                    text = "$timeLabel • ${log.status.name}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = log.targetSummary,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = log.reason,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
        }
    }
}

@Composable
fun EmptyTraceCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SlateSurface),
        border = BorderStroke(1.dp, SlateBorder),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Rounded.TouchApp,
                contentDescription = null,
                tint = ElectricCyan,
                modifier = Modifier.size(36.dp)
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.trace_empty_title),
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.trace_empty_body),
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentSettingsSheet(
    settings: AgentSettings,
    onUpdate: ((AgentSettings) -> AgentSettings) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = SlateSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = stringResource(R.string.settings_title),
                style = MaterialTheme.typography.headlineSmall
            )
            HorizontalDivider(color = SlateBorder)

            SettingSwitchRow(
                title = stringResource(R.string.settings_vision_label),
                subtitle = stringResource(R.string.settings_vision_desc),
                checked = settings.useVisionScreenshot,
                onCheckedChange = { enabled ->
                    onUpdate { it.copy(useVisionScreenshot = enabled) }
                }
            )

            SettingSwitchRow(
                title = stringResource(R.string.settings_confirm_label),
                subtitle = stringResource(R.string.settings_confirm_desc),
                checked = settings.confirmSensitiveActions,
                onCheckedChange = { enabled ->
                    onUpdate { it.copy(confirmSensitiveActions = enabled) }
                }
            )

            SettingSwitchRow(
                title = stringResource(R.string.settings_home_label),
                subtitle = stringResource(R.string.settings_home_desc),
                checked = settings.goHomeOnLaunch,
                onCheckedChange = { enabled ->
                    onUpdate { it.copy(goHomeOnLaunch = enabled) }
                }
            )

            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.settings_max_steps_label),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = "${settings.maxSteps} steps",
                        style = MaterialTheme.typography.labelSmall,
                        color = CyberTeal
                    )
                }
                Slider(
                    value = settings.maxSteps.toFloat(),
                    onValueChange = { value ->
                        onUpdate { it.copy(maxSteps = value.roundToInt()) }
                    },
                    valueRange = 5f..30f,
                    steps = 4,
                    colors = SliderDefaults.colors(
                        thumbColor = CyberTeal,
                        activeTrackColor = CyberTeal
                    )
                )
            }

            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.settings_delay_label),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = "${settings.stepDelayMs} ms",
                        style = MaterialTheme.typography.labelSmall,
                        color = CyberTeal
                    )
                }
                Slider(
                    value = settings.stepDelayMs.toFloat(),
                    onValueChange = { value ->
                        onUpdate { it.copy(stepDelayMs = (value / 200f).roundToInt() * 200L) }
                    },
                    valueRange = 600f..3000f,
                    steps = 11,
                    colors = SliderDefaults.colors(
                        thumbColor = CyberTeal,
                        activeTrackColor = CyberTeal
                    )
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = CyberTeal
            )
        )
    }
}
