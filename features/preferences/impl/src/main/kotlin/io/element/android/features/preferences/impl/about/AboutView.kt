/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.preferences.impl.about

import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewParameter
import io.element.android.libraries.designsystem.components.preferences.PreferencePage
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.theme.components.ListItem
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.ui.strings.CommonStrings

@Composable
fun AboutView(
    state: AboutState,
    onElementLegalClick: (ElementLegal) -> Unit,
    onOpenSourceLicensesClick: () -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    fun abrir(url: String) {
        try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { /* sin navegador */ }
    }
    PreferencePage(
        modifier = modifier,
        onBackClick = onBackClick,
        title = stringResource(id = CommonStrings.common_about)
    ) {
        // Chamos: aviso de licencia (AGPL-3.0) con enlace al código fuente
        ListItem(
            content = { Text("Código fuente de Chamos") },
            supportingContent = { Text("Chamos es software libre bajo licencia AGPL-3.0. Toca para ver el código.") },
            onClick = { abrir("https://github.com/siervobenshalyahu-ship-it/Chamos/tree/base-propia") }
        )
        ListItem(
            content = { Text("Basado en Element X Android") },
            supportingContent = { Text("© Element Creations Ltd. y colaboradores, licencia AGPL-3.0. Toca para ver el proyecto original.") },
            onClick = { abrir("https://github.com/element-hq/element-x-android") }
        )
        state.elementLegals.forEach { elementLegal ->
            ListItem(
                content = {
                    Text(stringResource(id = elementLegal.titleRes))
                },
                onClick = { onElementLegalClick(elementLegal) }
            )
        }
        ListItem(
            content = {
                Text(stringResource(id = CommonStrings.common_open_source_licenses))
            },
            onClick = onOpenSourceLicensesClick,
        )
    }
}

@PreviewsDayNight
@Composable
internal fun AboutViewPreview(@PreviewParameter(AboutStatePreviewParam::class) state: AboutState) = ElementPreview {
    AboutView(
        state = state,
        onElementLegalClick = {},
        onOpenSourceLicensesClick = {},
        onBackClick = {},
    )
}
