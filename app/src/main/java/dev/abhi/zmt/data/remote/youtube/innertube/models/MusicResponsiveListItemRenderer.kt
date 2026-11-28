package dev.abhi.zmt.data.remote.youtube.innertube.models

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class MusicResponsiveListItemRenderer(
    val fixedColumns: List<FlexColumn>?,
    val flexColumns: List<FlexColumn>,
    val thumbnail: ThumbnailRenderer?,
    val navigationEndpoint: NavigationEndpoint?,
    val badges: List<Badge>?,
    val playlistItemData: PlaylistItemData? = null,
    val overlay: Overlay? = null,
) {
    @Serializable
    data class FlexColumn(
        @JsonNames("musicResponsiveListItemFixedColumnRenderer")
        val musicResponsiveListItemFlexColumnRenderer: MusicResponsiveListItemFlexColumnRenderer?
    ) {
        @Serializable
        data class MusicResponsiveListItemFlexColumnRenderer(
            val text: Runs?
        )
    }

    @Serializable data class PlaylistItemData(val videoId: String? = null)
    @Serializable data class Overlay(val musicItemThumbnailOverlayRenderer: MusicItemThumbnailOverlayRenderer? = null)
    @Serializable data class MusicItemThumbnailOverlayRenderer(val content: Content? = null)
    @Serializable data class Content(val musicPlayButtonRenderer: MusicPlayButtonRenderer? = null)
    @Serializable data class MusicPlayButtonRenderer(val playNavigationEndpoint: NavigationEndpoint? = null)
}

