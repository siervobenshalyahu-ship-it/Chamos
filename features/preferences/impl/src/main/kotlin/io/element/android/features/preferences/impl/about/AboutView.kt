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
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
        // Chamos: sello del autor
        ListItem(
            content = { Text("Chamos, por BENSHALYAHU") },
            supportingContent = { Text("El hebreo de Venezuela") },
        )
        // Chamos: aviso de licencia (AGPL-3.0) con enlace al código fuente
        ListItem(
            content = { Text("Código fuente de Chamos") },
            supportingContent = { Text("Chamos es software libre bajo licencia AGPL-3.0. Toca para ver el código.") },
            onClick = { abrir("https://github.com/siervobenshalyahu-ship-it/Chamos/tree/base-propia") }
        )
        // Créditos del equipo de Chamos
        ListItem(
            content = { Text("CEO") },
            supportingContent = { Text("Jefferson Castillo") },
        )
        ListItem(
            content = { Text("Desarrollador general del proyecto") },
            supportingContent = { Text("Jefferson Castillo") },
        )
        ListItem(
            content = { Text("Desarrollador de aplicaciones móviles") },
            supportingContent = { Text("Jefferson Castillo") },
        )
        ListItem(
            content = { Text("Desarrollador dedicado a la versión Android") },
            supportingContent = { Text("Jhonfer Castillo") },
        )
        ListItem(
            content = { Text("Desarrolladores de interfaz gráfica") },
            supportingContent = { Text("Yulie O. Castillo\nWilly J. Granados\nJeison D. Castillo") },
        )
        ListItem(
            content = { Text("Ingeniero administrador de capacidades y servidores") },
            supportingContent = { Text("Jeison D. Castillo") },
        )
        ListItem(
            content = { Text("Desarrollo web") },
            supportingContent = { Text("Jefferson Castillo: Programador HTML\nJhonfer Castillo: Programador CS5\nJeison D. Castillo: Administrador de base de datos") },
        )
        ListItem(
            content = { Text("Ingenieros de testeo") },
            supportingContent = { Text("José Gregorio Castillo\nDigna Rosa Mora Mora\nYelly Katherine Castillo\nLeovag Barú Mora\nHaide María Mora\nMaria Herlinda Mora\nYeray Jhiret Martínez\nJairo Javier Mora\nJoel David Castillo\nJohana Custon\nJuaidy Jesiel Mora\nMayerling Carina Granados\nWilly Jesús Granados\nLeoneiner Mora Flores\nEdixon Martínez\nWilliams Sining\nAntonio José Mora\nAlejandro José Mora") },
        )
        // Enlaces legales propios de Chamos (ya no los de element.io)
        ListItem(
            content = { Text("Política de privacidad") },
            onClick = { abrir("https://chamos.pages.dev/privacidad.html") }
        )
        ListItem(
            content = {
                Text(stringResource(id = CommonStrings.common_open_source_licenses))
            },
            onClick = onOpenSourceLicensesClick,
        )
        // Crédito mínimo exigido por la AGPL: una sola línea, discreta y al final
        Text(
            text = "Basada en Element X (AGPL-3.0) y Matrix",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            style = TextStyle(fontSize = 10.sp),
            color = Color.Gray.copy(alpha = 0.6f),
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
