package com.axiel7.anihyou.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.CircularProgressIndicator
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.itemsIndexed
import androidx.glance.appwidget.provideContent
import androidx.glance.color.DynamicThemeColorProviders
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.axiel7.anihyou.core.base.DataResult
import com.axiel7.anihyou.core.base.PagedResult
import com.axiel7.anihyou.core.common.utils.NumberUtils.format
import com.axiel7.anihyou.core.domain.repository.DefaultPreferencesRepository
import com.axiel7.anihyou.core.domain.repository.MediaListRepository
import com.axiel7.anihyou.core.model.DeepLink
import com.axiel7.anihyou.core.model.media.duration
import com.axiel7.anihyou.core.model.media.exampleCommonMediaListEntry
import com.axiel7.anihyou.core.model.media.episodesBehind
import com.axiel7.anihyou.core.model.media.isBehind
import com.axiel7.anihyou.core.model.media.progressOrVolumes
import com.axiel7.anihyou.core.network.NetworkVariables
import com.axiel7.anihyou.core.network.fragment.CommonMediaListEntry
import com.axiel7.anihyou.core.network.type.MediaListSort
import com.axiel7.anihyou.core.network.type.MediaListStatus
import com.axiel7.anihyou.core.network.type.MediaStatus
import com.axiel7.anihyou.core.network.type.MediaType
import com.axiel7.anihyou.core.network.type.ScoreFormat
import com.axiel7.anihyou.core.resources.ColorUtils.colorFromHex
import com.axiel7.anihyou.core.resources.R
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

private val exampleBehindWidgetEntries = List(4) { index ->
    val example = exampleCommonMediaListEntry
    example.copy(
        mediaId = index + 1,
        basicMediaListEntry = example.basicMediaListEntry.copy(
            mediaId = index + 1,
            progress = 3,
        ),
        media = example.media?.let { media ->
            media.copy(
                nextAiringEpisode = media.nextAiringEpisode?.copy(episode = 9),
                basicMediaDetails = media.basicMediaDetails.copy(episodes = 12),
            )
        },
    )
}

class AnimeBehindWidget : GlanceAppWidget(), KoinComponent {

    private val networkVariables: NetworkVariables by inject()
    private val defaultPreferencesRepository: DefaultPreferencesRepository by inject()
    private val mediaListRepository: MediaListRepository by inject()

    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val result = loadResult(context)

        provideContent {
            val scope = rememberCoroutineScope()
            val prefs = currentState<Preferences>()
            val isColored = prefs[IS_COLORED_KEY] ?: true

            GlanceTheme(colors = DynamicThemeColorProviders) {
                Content(
                    result = result,
                    isColored = isColored,
                    onRefresh = { scope.launch { update(context, id) } },
                )
            }
        }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        val result = loadResult(context).takeIf { it is DataResult.Success }
            ?: DataResult.Success(exampleBehindWidgetEntries)

        provideContent {
            GlanceTheme(colors = DynamicThemeColorProviders) {
                Content(
                    result = result,
                    isColored = true,
                    onRefresh = {},
                )
            }
        }
    }

    private suspend fun loadResult(context: Context): DataResult<List<CommonMediaListEntry>> {
        val token = defaultPreferencesRepository.accessToken.first()
        val userId = defaultPreferencesRepository.userId.first()
        networkVariables.accessToken = token

        return if (token == null || userId == null) {
            DataResult.Error(context.getString(R.string.not_logged_text))
        } else {
            withTimeoutOrNull(WIDGET_LOAD_TIMEOUT_MS) { fetchBehindList(userId) }
                ?: DataResult.Error(context.getString(R.string.widget_load_timeout))
        }
    }

    // Mirrors the current-watching query on the home screen (CurrentViewModel) on purpose: same
    // status filter, same UPDATED_TIME_DESC sort, same null page/perPage. Null means the paging
    // arguments are left out of the request entirely, so the server answers with its default
    // first page. The widget shows exactly what that page contains instead of paging through
    // every behind entry; the filtering and sorting below match the home screen too, so both
    // surfaces always agree on which entries are shown and in what order.
    private suspend fun fetchBehindList(userId: Int): DataResult<List<CommonMediaListEntry>> {
        val result = mediaListRepository.getUserMediaList(
            userId = userId,
            mediaType = MediaType.ANIME,
            statusIn = listOf(MediaListStatus.CURRENT, MediaListStatus.REPEATING),
            sort = listOf(MediaListSort.UPDATED_TIME_DESC),
            scoreFormat = defaultPreferencesRepository.scoreFormat.first()
                ?: ScoreFormat.POINT_10_DECIMAL,
            fetchFromNetwork = true,
            page = null,
            perPage = null,
        ).first { it !is PagedResult.Loading }

        return when (result) {
            is PagedResult.Success -> DataResult.Success(
                result.list
                    .filter { it.media?.status == MediaStatus.RELEASING && it.isBehind() }
                    .sortedWith(
                        compareByDescending<CommonMediaListEntry> { it.basicMediaListEntry.priority }
                            .thenByDescending { it.episodesBehind() }
                    )
            )

            is PagedResult.Error -> DataResult.Error(result.message)
            is PagedResult.Loading -> DataResult.Loading
        }
    }

    @Composable
    private fun Content(
        result: DataResult<List<CommonMediaListEntry>>,
        isColored: Boolean,
        onRefresh: () -> Unit,
    ) {
        val context = LocalContext.current
        val openListAction = remember(context) { context.openAnimeList() }

        RoundedDrawableBox(
            shapeRes = R.drawable.widget_background_24,
            color = GlanceTheme.colors.widgetBackground,
            modifier = GlanceModifier
                .fillMaxSize()
                .appWidgetBackground()
                .widgetCornerRadius()
        ) {
            LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
                item(itemId = 0) {
                    Header(onRefresh = onRefresh, onOpenList = openListAction)
                }
                if (result is DataResult.Success) {
                    if (result.data.isEmpty()) {
                        item {
                            Column(
                                modifier = GlanceModifier
                                    .fillMaxSize()
                                    .clickableIfAvailable(openListAction),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = glanceStringResource(R.string.no_information),
                                    modifier = GlanceModifier.padding(24.dp),
                                    style = TextStyle(color = GlanceTheme.colors.onSurface)
                                )
                            }
                        }
                    } else {
                        itemsIndexed(
                            items = result.data,
                            itemId = { _, item -> item.mediaId.toLong() }
                        ) { _, item ->
                            ItemView(item = item, isColored = isColored)
                        }
                    }
                } else {
                    item {
                        Column(
                            modifier = GlanceModifier.fillMaxSize(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            if (result is DataResult.Loading) {
                                CircularProgressIndicator(color = GlanceTheme.colors.primary)
                            } else if (result is DataResult.Error) {
                                Text(
                                    text = result.message,
                                    modifier = GlanceModifier.padding(24.dp),
                                    style = TextStyle(color = GlanceTheme.colors.onSurface)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun Header(onRefresh: () -> Unit, onOpenList: Action?) {
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 8dp card margin + 8dp card padding, so the header shares the left edge of item titles
            Spacer(modifier = GlanceModifier.width(16.dp))
            Text(
                text = glanceStringResource(R.string.anime_behind),
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                ),
                maxLines = 1,
                modifier = GlanceModifier
                    .defaultWeight()
                    .padding(vertical = 10.dp)
                    .clickableIfAvailable(onOpenList)
            )

            // The outer box exists only as a touch target, padded to the 48dp minimum;
            // the pill itself stays 54x32dp.
            Box(
                modifier = GlanceModifier
                    .width(54.dp)
                    .height(48.dp)
                    .clickable(onRefresh),
                contentAlignment = Alignment.Center
            ) {
                RoundedDrawableBox(
                    shapeRes = R.drawable.widget_refresh_bg_20,
                    color = GlanceTheme.colors.primary,
                    modifier = GlanceModifier
                        .width(54.dp)
                        .height(32.dp)
                        .cornerRadius(20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        provider = ImageProvider(R.drawable.replay_20),
                        contentDescription = glanceStringResource(R.string.refresh),
                        modifier = GlanceModifier.size(20.dp),
                        colorFilter = ColorFilter.tint(GlanceTheme.colors.onPrimary)
                    )
                }
            }
            Spacer(modifier = GlanceModifier.width(16.dp))
        }
    }

    @Composable
    private fun ItemView(item: CommonMediaListEntry, isColored: Boolean) {
        val context = LocalContext.current
        val openDetailsAction = remember(context, item.mediaId) {
            context.openAnimeDetails(item.mediaId)
        }

        val baseMediaColor = remember(item.media?.coverImage?.color, isColored) {
            if (isColored) {
                item.media?.coverImage?.color?.takeIf { it.isNotBlank() }?.let { hex ->
                    runCatching { colorFromHex(hex) }.getOrNull()
                }
            } else null
        }

        val cardColors = rememberWidgetCardColors(baseMediaColor)

        val containerColor = cardColors?.container ?: GlanceTheme.colors.secondaryContainer
        val titleColor = cardColors?.title ?: GlanceTheme.colors.onSecondaryContainer
        val textColor = cardColors?.text ?: GlanceTheme.colors.onSecondaryContainer

        val episodesBehind = item.episodesBehind()
        val progress = item.basicMediaListEntry.progressOrVolumes()?.format() ?: "0"
        val total = item.duration()?.format()

        // Spacing has to go on the outer container: Glance background() fills the whole view
        // bounds, so padding on the card only insets its content while the color block still
        // touches the edges and merges with the next card.
        Box(
            modifier = GlanceModifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            RoundedDrawableBox(
                shapeRes = R.drawable.widget_behind_bg_16,
                color = containerColor,
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .cornerRadius(16.dp)
                    .clickableIfAvailable(openDetailsAction)
            ) {
                Column(
                    modifier = GlanceModifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = item.media?.basicMediaDetails?.title?.userPreferred.orEmpty(),
                        style = TextStyle(
                            color = titleColor,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        maxLines = 1
                    )
                    Row(
                        modifier = GlanceModifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = glancePluralResource(
                                id = R.plurals.num_episodes_behind,
                                count = episodesBehind,
                                episodesBehind
                            ),
                            style = TextStyle(
                                // Not the theme primary: it is dark purple on a light theme and
                                // light purple on a dark one, so its luminance inverts while the
                                // card background does not.
                                color = textColor,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            ),
                            maxLines = 1,
                            modifier = GlanceModifier.defaultWeight()
                        )
                        Image(
                            provider = ImageProvider(R.drawable.play_arrow_20),
                            contentDescription = null,
                            modifier = GlanceModifier.size(16.dp),
                            colorFilter = ColorFilter.tint(textColor)
                        )
                        Text(
                            text = if (total != null) "$progress/$total" else "$progress",
                            style = TextStyle(
                                color = textColor,
                                fontSize = 12.sp
                            ),
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }

    // No deep link action here: the launcher intent keeps MAIN/LAUNCHER, so the app opens on its
    // default start tab — the currently-watching list these entries come from. Returns null when
    // there is no launch intent, so the caller can skip the click rather than start an empty
    // intent, which would throw ActivityNotFoundException.
    private fun Context.openAnimeList(): Action? =
        packageManager.getLaunchIntentForPackage(packageName)?.let { intent ->
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            actionStartActivity(intent)
        }

    private fun Context.openAnimeDetails(mediaId: Int): Action? =
        packageManager.getLaunchIntentForPackage(packageName)?.let { intent ->
            intent.action = DeepLink.Type.ANIME.intentAction
            intent.putExtra("content_id", mediaId.toString())
            intent.putExtra("widget", true)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            actionStartActivity(intent)
        }

    companion object {
        val IS_COLORED_KEY = booleanPreferencesKey("is_colored")
    }
}

class AnimeBehindWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = AnimeBehindWidget()
}

@Composable
private fun glancePluralResource(id: Int, count: Int, vararg args: Any): String =
    LocalContext.current.resources.getQuantityString(id, count, *args)
