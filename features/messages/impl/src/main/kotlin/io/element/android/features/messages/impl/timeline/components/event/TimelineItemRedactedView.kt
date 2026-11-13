/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.timeline.components.event

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import io.element.android.compound.theme.ElementTheme
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.matrix.api.core.EventId
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemRedactedContent
import io.element.android.libraries.designsystem.icons.CompoundDrawables
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.ui.common.layout.ContentAvoidingLayoutData
import io.element.android.libraries.ui.strings.CommonStrings

@Composable
fun TimelineItemRedactedView(
    @Suppress("UNUSED_PARAMETER") content: TimelineItemRedactedContent,
    onContentLayoutChange: (ContentAvoidingLayoutData) -> Unit,
    modifier: Modifier = Modifier,
    eventId: EventId? = null,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        TimelineItemInformativeView(
            text = stringResource(id = CommonStrings.common_message_removed),
            iconDescription = stringResource(id = CommonStrings.common_message_removed),
            iconResourceId = CompoundDrawables.ic_compound_delete,
            onContentLayoutChange = onContentLayoutChange,
        )
        if (eventId != null) {
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = "✕ Quitar",
                color = ElementTheme.colors.textActionAccent,
                style = ElementTheme.typography.fontBodyMdRegular,
                modifier = Modifier
                    .clickable { ChamosAvisosChat.ocultar(eventId.value) }
                    .padding(vertical = 4.dp),
            )
        }
    }
}

@PreviewsDayNight
@Composable
internal fun TimelineItemRedactedViewPreview() = ElementPreview {
    TimelineItemRedactedView(
        TimelineItemRedactedContent,
        onContentLayoutChange = {},
    )
}
