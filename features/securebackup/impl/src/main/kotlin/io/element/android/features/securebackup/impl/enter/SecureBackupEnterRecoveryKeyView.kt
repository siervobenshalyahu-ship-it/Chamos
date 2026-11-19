/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.securebackup.impl.enter

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalUriHandler
import io.element.android.libraries.designsystem.components.dialogs.ConfirmationDialog
import io.element.android.libraries.designsystem.theme.components.TextButton
import androidx.compose.foundation.layout.Column
import io.element.android.libraries.designsystem.theme.components.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.features.securebackup.impl.R
import io.element.android.features.securebackup.impl.setup.views.RecoveryKeyView
import io.element.android.libraries.designsystem.atomic.pages.FlowStepPage
import io.element.android.libraries.designsystem.components.BigIcon
import io.element.android.libraries.designsystem.components.async.AsyncActionView
import io.element.android.libraries.designsystem.modifiers.bringIntoViewOnImeVisible
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.theme.components.Button
import io.element.android.libraries.ui.strings.CommonStrings

@Composable
fun SecureBackupEnterRecoveryKeyView(
    state: SecureBackupEnterRecoveryKeyState,
    onSuccess: () -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AsyncActionView(
        async = state.submitAction,
        onSuccess = { onSuccess() },
        progressDialog = { },
        errorTitle = { stringResource(id = R.string.screen_recovery_key_confirm_error_title) },
        errorMessage = { stringResource(id = R.string.screen_recovery_key_confirm_error_content) },
        onErrorDismiss = { state.eventSink(SecureBackupEnterRecoveryKeyEvent.ClearDialog) },
    )

    FlowStepPage(
        modifier = modifier,
        isScrollable = true,
        onBackClick = onBackClick,
        iconStyle = BigIcon.Style.Default(CompoundIcons.KeySolid()),
        title = stringResource(id = R.string.screen_recovery_key_confirm_title),
        subTitle = stringResource(id = R.string.screen_recovery_key_confirm_description),
        buttons = { Buttons(state = state) }
    ) {
        Content(state = state)
    }
}

@Composable
private fun Content(
    state: SecureBackupEnterRecoveryKeyState,
) {
    var olvide by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    if (olvide) {
        ConfirmationDialog(
            title = "Olvidé mi contraseña o PIN",
            content = "La contraseña o PIN de recuperación no se puede enviar por correo: nadie la guarda, ni siquiera Chamos, y eso es lo que mantiene tus chats privados.\n\n" +
                "Qué puedes hacer:\n" +
                "• Si tienes Chamos abierto en otro teléfono, verifica desde allí con \"Verificar con otro dispositivo\" y luego crea una nueva en Ajustes → Respaldo de chats → Cambiar contraseña o PIN.\n" +
                "• Si lo que olvidaste es la contraseña de tu CUENTA, puedes recuperarla con un código enviado a tu correo desde la página de la cuenta (toca \"Recuperar mi cuenta\" y elige \"¿Olvidaste tu contraseña?\").",
            submitText = "Recuperar mi cuenta",
            cancelText = "Cerrar",
            onSubmitClick = { olvide = false; uriHandler.openUri("https://account.matrix.org/") },
            onDismiss = { olvide = false },
        )
    }
    Column {
    TextButton(text = "Olvidé mi contraseña o PIN", onClick = { olvide = true })
    RecoveryKeyView(
        modifier = Modifier
            .bringIntoViewOnImeVisible()
            .padding(top = 52.dp, bottom = 32.dp),
        state = state.recoveryKeyViewState,
        onClick = null,
        onChange = {
            state.eventSink.invoke(SecureBackupEnterRecoveryKeyEvent.OnRecoveryKeyChange(it))
        },
        onSubmit = {
            state.eventSink.invoke(SecureBackupEnterRecoveryKeyEvent.Submit)
        },
        toggleRecoveryKeyVisibility = {
            state.eventSink(SecureBackupEnterRecoveryKeyEvent.ChangeRecoveryKeyFieldContentsVisibility(it))
        }
    )
    }
}

@Composable
private fun ColumnScope.Buttons(
    state: SecureBackupEnterRecoveryKeyState,
) {
    Button(
        text = stringResource(id = CommonStrings.action_continue),
        enabled = state.isSubmitEnabled,
        showProgress = state.submitAction.isLoading(),
        modifier = Modifier.fillMaxWidth(),
        onClick = {
            state.eventSink.invoke(SecureBackupEnterRecoveryKeyEvent.Submit)
        }
    )
}

@PreviewsDayNight
@Composable
internal fun SecureBackupEnterRecoveryKeyViewPreview(
    @PreviewParameter(SecureBackupEnterRecoveryKeyStatePreviewParam::class) state: SecureBackupEnterRecoveryKeyState
) = ElementPreview {
    SecureBackupEnterRecoveryKeyView(
        state = state,
        onSuccess = {},
        onBackClick = {},
    )
}
