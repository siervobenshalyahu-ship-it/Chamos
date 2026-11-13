/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.usersearch.impl

import dev.zacsweers.metro.ContributesBinding
import io.element.android.libraries.di.SessionScope
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.user.MatrixUser
import io.element.android.libraries.usersearch.api.UserListDataSource

@ContributesBinding(SessionScope::class)
class MatrixUserListDataSource(
    private val client: MatrixClient
) : UserListDataSource {
    override suspend fun search(query: String, count: Long): List<MatrixUser> {
        val res = client.searchUsers(query, count)
        val todos = res.getOrNull()?.results.orEmpty()
        val f = io.element.android.libraries.matrix.api.ChamosFiltroBusqueda.filtro
        if (f == null || query.trim().startsWith("@") || !io.element.android.libraries.matrix.api.ChamosFiltroBusqueda.soloChamos.value) return todos
        val son = f(todos.map { it.userId.value }) ?: return todos
        return todos.filter { it.userId.value in son }
    }

    override suspend fun getProfile(userId: UserId): MatrixUser? {
        return client.getProfile(userId).getOrNull()
    }
}
