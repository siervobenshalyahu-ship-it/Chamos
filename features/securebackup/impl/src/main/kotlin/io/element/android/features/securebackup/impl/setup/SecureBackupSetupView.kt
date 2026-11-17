/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.securebackup.impl.setup

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.designsystem.theme.components.TextField
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.features.securebackup.impl.R
import io.element.android.features.securebackup.impl.setup.views.RecoveryKeyView
import io.element.android.libraries.androidutils.system.startSharePlainTextIntent
import io.element.android.libraries.designsystem.atomic.pages.FlowStepPage
import io.element.android.libraries.designsystem.components.BigIcon
import io.element.android.libraries.designsystem.components.dialogs.ConfirmationDialog
import io.element.android.libraries.designsystem.components.dialogs.ErrorDialog
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.theme.components.Button
import io.element.android.libraries.designsystem.theme.components.IconSource
import io.element.android.libraries.designsystem.theme.components.OutlinedButton
import io.element.android.libraries.ui.strings.CommonStrings

@Composable
fun SecureBackupSetupView(
    state: SecureBackupSetupState,
    onSuccess: () -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowStepPage(
        modifier = modifier,
        onBackClick = onBackClick.takeIf { state.canGoBack() },
        title = title(state),
        subTitle = subtitle(state),
        iconStyle = BigIcon.Style.Default(CompoundIcons.KeySolid()),
        buttons = { Buttons(state, onFinish = onSuccess) },
    ) {
        Content(state = state)
    }

    if (state.setupState is SetupState.Error) {
        ErrorDialog(
            title = stringResource(id = CommonStrings.common_something_went_wrong),
            content = stringResource(id = CommonStrings.common_something_went_wrong_message),
            onSubmit = {
                state.eventSink.invoke(SecureBackupSetupEvent.DismissDialog)
            },
        )
    }

    if (state.showSaveConfirmationDialog) {
        ConfirmationDialog(
            title = stringResource(id = R.string.screen_recovery_key_setup_confirmation_title),
            content = stringResource(id = R.string.screen_recovery_key_setup_confirmation_description),
            submitText = stringResource(id = CommonStrings.action_continue),
            onSubmitClick = onSuccess,
            onDismiss = {
                state.eventSink.invoke(SecureBackupSetupEvent.DismissDialog)
            }
        )
    }
}

private fun SecureBackupSetupState.canGoBack(): Boolean {
    return recoveryKeyViewState.formattedRecoveryKey == null
}

@Composable
private fun title(state: SecureBackupSetupState): String {
    return when (state.setupState) {
        SetupState.Init,
        SetupState.Creating,
        is SetupState.Error -> if (state.isChangeRecoveryKeyUserStory) {
            stringResource(id = R.string.screen_recovery_key_change_title)
        } else {
            stringResource(id = R.string.screen_recovery_key_setup_title)
        }
        is SetupState.Created,
        is SetupState.CreatedAndSaved ->
            stringResource(id = R.string.screen_recovery_key_save_title)
    }
}

@Composable
private fun subtitle(state: SecureBackupSetupState): String {
    return when (state.setupState) {
        SetupState.Init,
        SetupState.Creating,
        is SetupState.Error -> if (state.isChangeRecoveryKeyUserStory) {
            stringResource(id = R.string.screen_recovery_key_change_description)
        } else {
            stringResource(id = R.string.screen_recovery_key_setup_description)
        }
        is SetupState.Created,
        is SetupState.CreatedAndSaved ->
            stringResource(id = R.string.screen_recovery_key_save_description)
    }
}

@Composable
private fun Content(
    state: SecureBackupSetupState,
) {
    val formattedRecoveryKey = state.recoveryKeyViewState.formattedRecoveryKey
    val clickLambda = if (formattedRecoveryKey != null) {
        {
            state.eventSink.invoke(SecureBackupSetupEvent.CopyRecoveryKey)
        }
    } else {
        if (!state.recoveryKeyViewState.inProgress) {
            {
                state.eventSink.invoke(SecureBackupSetupEvent.CreateRecoveryKey)
            }
        } else {
            null
        }
    }
    RecoveryKeyView(
        modifier = Modifier.padding(top = 52.dp),
        state = state.recoveryKeyViewState,
        onClick = clickLambda,
        onChange = null,
        onSubmit = null,
        toggleRecoveryKeyVisibility = {},
    )
    if (formattedRecoveryKey == null && !state.recoveryKeyViewState.inProgress && !state.isChangeRecoveryKeyUserStory) {
        EligeTuPropia(state)
    }
}

/** Chamos: la persona puede elegir su propia contraseña o un PIN de 6 números en vez de la clave generada. */
@Composable
private fun EligeTuPropia(state: SecureBackupSetupState) {
    var clave by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    Column(modifier = Modifier.padding(top = 24.dp)) {
        Text(text = "O crea la tuya: una contraseña (mínimo 8 caracteres) o un PIN de 6 números. Si la olvidas no podrás recuperar tus mensajes.")
        TextField(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            value = clave,
            onValueChange = { clave = it },
            placeholder = "Mi contraseña de recuperación",
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
        )
        Button(
            text = "Usar mi contraseña",
            enabled = clave.length >= 8,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            onClick = { state.eventSink(SecureBackupSetupEvent.CreateWithPassphrase(clave)) },
        )
        Text(modifier = Modifier.padding(top = 20.dp), text = "O un PIN de 6 números (más fácil de recordar, pero menos seguro que una contraseña larga):")
        TextField(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            value = pin,
            onValueChange = { nuevo -> pin = nuevo.filter { it.isDigit() }.take(6) },
            placeholder = "PIN de 6 números",
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        )
        Button(
            text = "Usar mi PIN",
            enabled = pin.length == 6,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            onClick = { state.eventSink(SecureBackupSetupEvent.CreateWithPassphrase(pin)) },
        )
    }
}

@Composable
private fun ColumnScope.Buttons(
    state: SecureBackupSetupState,
    onFinish: () -> Unit,
) {
    val context = LocalContext.current
    val chooserTitle = stringResource(id = R.string.screen_recovery_key_save_action)
    when (state.setupState) {
        SetupState.Init,
        SetupState.Creating,
        is SetupState.Error -> {
            Button(
                text = stringResource(id = CommonStrings.action_done),
                enabled = false,
                modifier = Modifier.fillMaxWidth(),
                onClick = onFinish
            )
        }
        is SetupState.Created,
        is SetupState.CreatedAndSaved -> {
            OutlinedButton(
                text = stringResource(id = R.string.screen_recovery_key_save_action),
                leadingIcon = IconSource.Vector(CompoundIcons.Download()),
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    context.startSharePlainTextIntent(
                        activityResultLauncher = null,
                        chooserTitle = chooserTitle,
                        text = state.setupState.recoveryKey()!!,
                    )
                    state.eventSink.invoke(SecureBackupSetupEvent.RecoveryKeyHasBeenSaved)
                },
            )
            Button(
                text = stringResource(id = CommonStrings.action_done),
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    if (state.setupState is SetupState.CreatedAndSaved) {
                        onFinish()
                    } else {
                        state.eventSink.invoke(SecureBackupSetupEvent.Done)
                    }
                },
            )
        }
    }
}

@PreviewsDayNight
@Composable
internal fun SecureBackupSetupViewPreview(
    @PreviewParameter(SecureBackupSetupStatePreviewParam::class) state: SecureBackupSetupState
) = ElementPreview {
    SecureBackupSetupView(
        state = state,
        onSuccess = {},
        onBackClick = {},
    )
}
