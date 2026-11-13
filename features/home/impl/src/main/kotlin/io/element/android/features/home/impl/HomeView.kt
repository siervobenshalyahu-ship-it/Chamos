/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.home.impl

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.plus
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.FabPosition
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.features.home.impl.components.HomeTopBar
import io.element.android.features.home.impl.components.MultiAccountAnnouncementBottomSheet
import io.element.android.features.home.impl.components.RoomListContentView
import io.element.android.features.home.impl.components.RoomListMenuAction
import io.element.android.features.home.impl.model.RoomListRoomSummary
import io.element.android.features.home.impl.roomlist.RoomListContextMenu
import io.element.android.features.home.impl.roomlist.RoomListDeclineInviteMenu
import io.element.android.features.home.impl.roomlist.RoomListEvent
import io.element.android.features.home.impl.roomlist.RoomListState
import io.element.android.features.home.impl.search.GlobalSearchEvent
import io.element.android.features.home.impl.search.GlobalSearchView
import io.element.android.features.home.impl.search.RoomListSearchView
import io.element.android.features.home.impl.spacefilters.SpaceFiltersEvent
import io.element.android.features.home.impl.spacefilters.SpaceFiltersState
import io.element.android.features.home.impl.spacefilters.SpaceFiltersView
import io.element.android.features.home.impl.spaces.HomeSpacesView
import io.element.android.libraries.androidutils.throttler.FirstThrottler
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.theme.components.FloatingActionButton
import io.element.android.libraries.designsystem.theme.components.HorizontalFloatingToolbar
import io.element.android.libraries.designsystem.theme.components.HorizontalFloatingToolbarItem
import io.element.android.libraries.designsystem.theme.components.HorizontalFloatingToolbarSeparator
import io.element.android.libraries.designsystem.theme.components.Icon
import io.element.android.libraries.designsystem.theme.components.Scaffold
import io.element.android.libraries.designsystem.utils.lazyColumnContentPadding
import io.element.android.libraries.designsystem.utils.scaffoldScrollableContentInsets
import io.element.android.libraries.designsystem.utils.snackbar.SnackbarHost
import io.element.android.libraries.designsystem.utils.snackbar.rememberSnackbarHostState
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.ui.strings.CommonStrings
import kotlinx.coroutines.launch

@Composable
fun HomeView(
    homeState: HomeState,
    onRoomClick: (RoomId, EventId?) -> Unit,
    onSettingsClick: () -> Unit,
    onAddAccountClick: () -> Unit,
    onSwitchToAccount: (SessionId) -> Unit,
    onSetUpRecoveryClick: () -> Unit,
    onConfirmRecoveryKeyClick: () -> Unit,
    onStartChatClick: () -> Unit,
    onCreateSpaceClick: () -> Unit,
    onRoomSettingsClick: (roomId: RoomId) -> Unit,
    onMenuActionClick: (RoomListMenuAction) -> Unit,
    onReportRoomClick: (roomId: RoomId) -> Unit,
    onDeclineInviteAndBlockUser: (roomSummary: RoomListRoomSummary) -> Unit,
    acceptDeclineInviteView: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    leaveRoomView: @Composable () -> Unit,
) {
    val state: RoomListState = homeState.roomListState
    val coroutineScope = rememberCoroutineScope()
    val firstThrottler = remember { FirstThrottler(300, coroutineScope) }
    val chamosReelsAbierto = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val chamosMuroInicial = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    Box(modifier) {
        if (state.contextMenu is RoomListState.ContextMenu.Shown) {
            RoomListContextMenu(
                contextMenu = state.contextMenu,
                canReportRoom = state.canReportRoom,
                eventSink = state.eventSink,
                onRoomSettingsClick = onRoomSettingsClick,
                onReportRoomClick = onReportRoomClick,
            )
        }
        if (state.declineInviteMenu is RoomListState.DeclineInviteMenu.Shown) {
            RoomListDeclineInviteMenu(
                menu = state.declineInviteMenu,
                canReportRoom = state.canReportRoom,
                eventSink = state.eventSink,
                onDeclineAndBlockClick = onDeclineInviteAndBlockUser,
            )
        }

        if (homeState.showMultiAccountAnnouncement) {
            MultiAccountAnnouncementBottomSheet(
                onDismiss = { homeState.eventSink(HomeEvent.DismissMultiAccountAnnouncement) },
                onAddAccountClick = { if (firstThrottler.canHandle()) onAddAccountClick() },
            )
        }

        leaveRoomView()

        if (chamosReelsAbierto.value) {
            val chamosReelsEstado = homeState.chamosReels
            if (chamosReelsEstado != null) ChamosReelsPantalla(chamosReelsEstado, chamosMuroInicial.value) { chamosReelsAbierto.value = false }
        }

        HomeScaffold(
            state = homeState,
            onSetUpRecoveryClick = onSetUpRecoveryClick,
            onConfirmRecoveryKeyClick = onConfirmRecoveryKeyClick,
            onRoomClick = { roomId -> if (firstThrottler.canHandle()) onRoomClick(roomId, null) },
            onOpenSettings = { if (firstThrottler.canHandle()) onSettingsClick() },
            onOpenReels = { chamosMuroInicial.value = false; chamosReelsAbierto.value = true },
            onOpenMuro = { chamosMuroInicial.value = true; chamosReelsAbierto.value = true },
            onStartChatClick = { if (firstThrottler.canHandle()) onStartChatClick() },
            onCreateSpaceClick = { if (firstThrottler.canHandle()) onCreateSpaceClick() },
            onMenuActionClick = onMenuActionClick,
            onSwitchToAccount = onSwitchToAccount,
        )

        if (state.globalSearchState.isEnabled) {
            GlobalSearchView(
                state = state.globalSearchState,
                onSelectSearchResult = { roomId, eventId -> if (firstThrottler.canHandle()) onRoomClick(roomId, eventId) },
                modifier = Modifier
                    .fillMaxSize()
                    .background(ElementTheme.colors.bgCanvasDefault),
            )
        } else {
            // This overlaid view will only be visible when state.displaySearchResults is true
            RoomListSearchView(
                state = state.searchState,
                eventSink = state.eventSink,
                hideInvitesAvatars = state.hideInvitesAvatars,
                onRoomClick = { roomId -> if (firstThrottler.canHandle()) onRoomClick(roomId, null) },
                modifier = Modifier
                    .fillMaxSize()
                    .background(ElementTheme.colors.bgCanvasDefault)
            )
        }

        acceptDeclineInviteView()
    }
}

@Composable
private fun HomeScaffold(
    state: HomeState,
    onSetUpRecoveryClick: () -> Unit,
    onConfirmRecoveryKeyClick: () -> Unit,
    onRoomClick: (RoomId) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenReels: () -> Unit,
    onOpenMuro: () -> Unit,
    onStartChatClick: () -> Unit,
    onCreateSpaceClick: () -> Unit,
    onMenuActionClick: (RoomListMenuAction) -> Unit,
    onSwitchToAccount: (SessionId) -> Unit,
    modifier: Modifier = Modifier,
) {
    fun onRoomClick(room: RoomListRoomSummary) {
        onRoomClick(room.roomId)
    }

    val appBarState = rememberTopAppBarState()
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(appBarState)
    val snackbarHostState = rememberSnackbarHostState(snackbarMessage = state.snackbarMessage)
    val roomListState: RoomListState = state.roomListState

    BackHandler(enabled = state.isBackHandlerEnabled) {
        if (state.currentHomeNavigationBarItem != HomeNavigationBarItem.Chats) {
            state.eventSink(HomeEvent.SelectHomeNavigationBarItem(HomeNavigationBarItem.Chats))
        } else {
            val spaceFiltersState = state.roomListState.spaceFiltersState
            if (spaceFiltersState is SpaceFiltersState.Selected) {
                spaceFiltersState.eventSink(SpaceFiltersEvent.Selected.ClearSelection)
            }
        }
    }

    var mostrarEstilos by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    if (mostrarEstilos) ChamosEstilosDialog(onDismiss = { mostrarEstilos = false })
    var mostrarNumero by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    if (mostrarNumero) ChamosNumeroDialog(onDismiss = { mostrarNumero = false })
    val roomsLazyListState = rememberLazyListState()
    val spacesLazyListState = rememberLazyListState()

    Scaffold(
        modifier = modifier,
        floatingActionButton = {
            val coroutineScope = rememberCoroutineScope()
            HomeBottomBar(
                // The Scaffold uses top-only insets so the scrollable content can go edge-to-edge behind the
                // navigation bar, so the floating toolbar has to apply the bottom inset itself to avoid overlapping it.
                modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars),
                currentHomeNavigationBarItem = state.currentHomeNavigationBarItem,
                onSettingsClick = onOpenSettings,
                onStylesClick = { mostrarEstilos = true },
                onNumberClick = { mostrarNumero = true },
                onStartChatClick = onStartChatClick,
                onReelsClick = onOpenReels,
                onMuroClick = onOpenMuro,
                onItemClick = { item ->
                    // scroll to top if selecting the same item
                    if (item == state.currentHomeNavigationBarItem) {
                        val lazyListStateTarget = when (item) {
                            HomeNavigationBarItem.Chats -> roomsLazyListState
                            HomeNavigationBarItem.Spaces -> spacesLazyListState
                        }
                        coroutineScope.launch {
                            if (lazyListStateTarget.firstVisibleItemIndex > 10) {
                                lazyListStateTarget.scrollToItem(10)
                            }
                            // Also reset the scrollBehavior height offset as it's not triggered by programmatic scrolls
                            scrollBehavior.state.heightOffset = 0f
                            lazyListStateTarget.animateScrollToItem(0)
                        }
                    } else {
                        state.eventSink(HomeEvent.SelectHomeNavigationBarItem(item))
                    }
                },
                floatingActionButton = null,
                totalUnreadCount = state.totalUnreadCount,
            )
        },
        floatingActionButtonPosition = FabPosition.Center,
        contentWindowInsets = scaffoldScrollableContentInsets,
        content = { padding ->
            val outerPadding = PaddingValues(
                start = padding.calculateStartPadding(LocalLayoutDirection.current),
                end = padding.calculateEndPadding(LocalLayoutDirection.current),
                // Remove these two lines once https://issuetracker.google.com/issues/436432313 has been fixed
                bottom = padding.calculateBottomPadding(),
                top = padding.calculateTopPadding()
            )
            val contentPadding = PaddingValues(
                bottom = 96.dp,
            )
            // The nested scroll connection is set on the content and not on the Scaffold, else the top bar would
            // consume the scroll events of its own content.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
            ) {
                when (state.currentHomeNavigationBarItem) {
                    HomeNavigationBarItem.Chats -> {
                      Column(
                          modifier = Modifier
                              .padding(outerPadding)
                              .consumeWindowInsets(outerPadding)
                      ) {
                        androidx.compose.foundation.layout.Row(
                            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 4.dp),
                            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {
                            ChamosTituloNeon()
                            ChamosBarraOvalada(fijo = {
                                io.element.android.libraries.designsystem.theme.components.Text(
                                    text = "⚙️",
                                    style = androidx.compose.ui.text.TextStyle(fontSize = 20.sp),
                                    modifier = Modifier.clickable { onOpenSettings() }.padding(horizontal = 12.dp, vertical = 8.dp),
                                )
                            }) {
                                io.element.android.libraries.designsystem.theme.components.Text(
                                    text = "👥",
                                    style = androidx.compose.ui.text.TextStyle(fontSize = 20.sp),
                                    modifier = Modifier.clickable {
                                        io.element.android.libraries.matrix.api.ChamosGrupo.directo = true
                                        onStartChatClick()
                                    }.padding(horizontal = 12.dp, vertical = 8.dp),
                                )
                                io.element.android.libraries.designsystem.theme.components.Text(
                                    text = "🔍",
                                    style = androidx.compose.ui.text.TextStyle(fontSize = 20.sp),
                                    modifier = Modifier.clickable { onStartChatClick() }.padding(horizontal = 12.dp, vertical = 8.dp),
                                )
                                val chamosCtx = androidx.compose.ui.platform.LocalContext.current
                                io.element.android.libraries.designsystem.theme.components.Text(
                                    text = "📨",
                                    style = androidx.compose.ui.text.TextStyle(fontSize = 20.sp),
                                    modifier = Modifier.clickable { chamosInvitar(chamosCtx, "otro") }.padding(horizontal = 12.dp, vertical = 8.dp),
                                )
                                io.element.android.libraries.designsystem.theme.components.Text(
                                    text = "🔄",
                                    style = androidx.compose.ui.text.TextStyle(fontSize = 20.sp),
                                    modifier = Modifier.clickable {
                                        ChamosEstados.recargar()
                                        io.element.android.libraries.matrix.api.ChamosReinicio.ejecutar()
                                        android.widget.Toast.makeText(chamosCtx, "Actualizando estados, barra VC y mensajes…", android.widget.Toast.LENGTH_SHORT).show()
                                    }.padding(horizontal = 12.dp, vertical = 8.dp),
                                )
                            }
                        }
                        ChamosEstadosRow(state = state.chamosEstados)
                        RoomListContentView(
                            contentState = roomListState.contentState,
                            filtersState = roomListState.filtersState,
                            spaceFiltersState = roomListState.spaceFiltersState,
                            lazyListState = roomsLazyListState,
                            hideInvitesAvatars = roomListState.hideInvitesAvatars,
                            eventSink = roomListState.eventSink,
                            onSetUpRecoveryClick = onSetUpRecoveryClick,
                            onConfirmRecoveryKeyClick = onConfirmRecoveryKeyClick,
                            onRoomClick = ::onRoomClick,
                            onCreateRoomClick = onStartChatClick,
                            contentPadding = lazyColumnContentPadding + contentPadding,
                            modifier = Modifier.weight(1f)
                        )
                      }
                        SpaceFiltersView(roomListState.spaceFiltersState)
                    }
                    HomeNavigationBarItem.Spaces -> {
                        HomeSpacesView(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(outerPadding)
                                .consumeWindowInsets(outerPadding),
                            contentPadding = lazyColumnContentPadding + contentPadding,
                            state = state.homeSpacesState,
                            lazyListState = spacesLazyListState,
                            onSpaceClick = { spaceId ->
                                onRoomClick(spaceId)
                            },
                            onCreateSpaceClick = onCreateSpaceClick,
                            // TODO use actual callbacks for this
                            onExploreClick = {},
                        )
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    )
}

@Composable
private fun HomeFloatingActionButton(
    onClick: () -> Unit,
    contentDescription: Int,
    modifier: Modifier = Modifier,
) {
    FloatingActionButton(onClick = onClick, modifier = modifier) {
        Icon(
            imageVector = CompoundIcons.Plus(),
            contentDescription = stringResource(id = contentDescription),
        )
    }
}

@Composable
private fun HomeBottomBarOriginal(
    totalUnreadCount: Int,
    currentHomeNavigationBarItem: HomeNavigationBarItem,
    onItemClick: (HomeNavigationBarItem) -> Unit,
    onSettingsClick: () -> Unit,
    onReelsClick: () -> Unit,
    onMuroClick: () -> Unit,
    onStylesClick: () -> Unit,
    onNumberClick: () -> Unit,
    onStartChatClick: () -> Unit,
    modifier: Modifier = Modifier,
    floatingActionButton: (@Composable () -> Unit)?,
) {
    HorizontalFloatingToolbar(
        floatingActionButton = floatingActionButton,
        modifier = modifier
            .zIndex(1f),
    ) {
        HorizontalFloatingToolbarItem(
            icon = CompoundIcons.Image(),
            tooltipLabel = "Estilos",
            isSelected = false,
            onClick = onStylesClick,
        )
        HorizontalFloatingToolbarItem(
            icon = CompoundIcons.UserProfile(),
            tooltipLabel = "Número",
            isSelected = false,
            onClick = onNumberClick,
        )
        HorizontalFloatingToolbarSeparator()
        HorizontalFloatingToolbarItem(
            icon = CompoundIcons.PlaySolid(),
            tooltipLabel = "Videos",
            isSelected = false,
            onClick = onReelsClick,
        )
        HorizontalFloatingToolbarSeparator()
        HorizontalFloatingToolbarItem(
            icon = ChamosIconoNinos,
            tooltipLabel = "Chamo Muro",
            isSelected = false,
            onClick = onMuroClick,
        )
        HorizontalFloatingToolbarSeparator()
        val chamosNavCtx = androidx.compose.ui.platform.LocalContext.current
        HorizontalFloatingToolbarItem(
            icon = ChamosIconoMundoGorra,
            tooltipLabel = "Navegador",
            isSelected = false,
            onClick = {
                chamosNavCtx.startActivity(
                    android.content.Intent().setClassName(chamosNavCtx.packageName, "io.element.android.x.ChamosNavegadorActivity"),
                )
            },
        )
    }
}

internal fun RoomListRoomSummary.contentType() = displayType.ordinal

@PreviewsDayNight
@Composable
internal fun HomeViewPreview(@PreviewParameter(HomeStatePreviewParam::class) state: HomeState) = ElementPreview {
    HomeView(
        homeState = state,
        onRoomClick = { _, _ -> },
        onSettingsClick = {},
        onAddAccountClick = {},
        onSwitchToAccount = {},
        onSetUpRecoveryClick = {},
        onConfirmRecoveryKeyClick = {},
        onStartChatClick = {},
        onCreateSpaceClick = {},
        onRoomSettingsClick = {},
        onReportRoomClick = {},
        onMenuActionClick = {},
        onDeclineInviteAndBlockUser = {},
        acceptDeclineInviteView = {},
        leaveRoomView = {}
    )
}

@Preview
@Composable
internal fun HomeViewA11yPreview() = ElementPreview {
    HomeView(
        homeState = aHomeState(),
        onRoomClick = { _, _ -> },
        onSettingsClick = {},
        onAddAccountClick = {},
        onSwitchToAccount = {},
        onSetUpRecoveryClick = {},
        onConfirmRecoveryKeyClick = {},
        onStartChatClick = {},
        onCreateSpaceClick = {},
        onRoomSettingsClick = {},
        onReportRoomClick = {},
        onMenuActionClick = {},
        onDeclineInviteAndBlockUser = {},
        acceptDeclineInviteView = {},
        leaveRoomView = {}
    )
}

@Composable
private fun HomeBottomBar(
    totalUnreadCount: Int,
    currentHomeNavigationBarItem: HomeNavigationBarItem,
    onItemClick: (HomeNavigationBarItem) -> Unit,
    onSettingsClick: () -> Unit,
    onReelsClick: () -> Unit,
    onMuroClick: () -> Unit,
    onStylesClick: () -> Unit,
    onNumberClick: () -> Unit,
    onStartChatClick: () -> Unit,
    modifier: Modifier = Modifier,
    floatingActionButton: (@Composable () -> Unit)?,
) {
    ChamosBarraAbajo(modifier = modifier) {
        HomeBottomBarOriginal(
            totalUnreadCount = totalUnreadCount,
            currentHomeNavigationBarItem = currentHomeNavigationBarItem,
            onItemClick = onItemClick,
            onSettingsClick = onSettingsClick,
            onReelsClick = onReelsClick,
            onMuroClick = onMuroClick,
            onStylesClick = onStylesClick,
            onNumberClick = onNumberClick,
            onStartChatClick = onStartChatClick,
            floatingActionButton = floatingActionButton,
        )
    }
}
