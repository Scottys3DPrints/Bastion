package com.bastion.app.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.action.actionStartActivity
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.compose.ui.graphics.Color
import com.bastion.app.data.BastionGraph
import com.bastion.app.domain.Rank
import com.bastion.app.feature.panic.PanicActivity
import kotlinx.coroutines.flow.first

/**
 * The Watchtower on the home screen.
 *
 * Two jobs: keep the rank visible where he will see it without opening anything,
 * and put Hold the Line one tap from wherever he is.
 */
class WatchtowerWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val graph = BastionGraph.from(context)
        val state = graph.journey.state.first()
        val faithMode = graph.settings.current().faithMode

        // The same line the home screen is showing, chosen by the same day
        // seed — the widget exists so the words reach him without opening
        // anything, and two different "today's words" would just be noise.
        // Short items only: there is room for one line here, not a paragraph.
        val word = graph.content.motivationForMoment(
            faithMode = faithMode,
            moment = "daily",
            daySeed = java.time.LocalDate.now().toEpochDay(),
            maxLength = "short",
        )

        provideContent {
            GlanceTheme {
                WidgetBody(
                    streak = state.currentStreak,
                    rankName = state.rank.displayName(faithMode),
                    points = state.points,
                    word = word?.text,
                )
            }
        }
    }

    @Composable
    private fun WidgetBody(streak: Int, rankName: String, points: Int, word: String?) {
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(Color(0xFF0E1220))
                .padding(16.dp)
                .clickable(actionStartActivity<PanicActivity>()),
            verticalAlignment = Alignment.Vertical.CenterVertically,
            horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
        ) {
            Text(
                text = rankName.uppercase(),
                style = TextStyle(
                    color = ColorProvider(Color(0xFFC8A24B)),
                    fontSize = androidx.compose.ui.unit.TextUnit(11f, androidx.compose.ui.unit.TextUnitType.Sp),
                    fontWeight = FontWeight.Medium,
                ),
            )
            Spacer(GlanceModifier.height(6.dp))
            Row(verticalAlignment = Alignment.Vertical.Bottom) {
                Text(
                    text = "$streak",
                    style = TextStyle(
                        color = ColorProvider(Color(0xFFEAEEF7)),
                        fontSize = androidx.compose.ui.unit.TextUnit(34f, androidx.compose.ui.unit.TextUnitType.Sp),
                        fontWeight = FontWeight.Bold,
                    ),
                )
                Text(
                    text = if (streak == 1) " day" else " days",
                    style = TextStyle(
                        color = ColorProvider(Color(0xFF96A0BA)),
                        fontSize = androidx.compose.ui.unit.TextUnit(13f, androidx.compose.ui.unit.TextUnitType.Sp),
                    ),
                )
            }
            word?.let {
                Spacer(GlanceModifier.height(8.dp))
                Text(
                    text = it,
                    style = TextStyle(
                        color = ColorProvider(Color(0xFF96A0BA)),
                        fontSize = androidx.compose.ui.unit.TextUnit(12f, androidx.compose.ui.unit.TextUnitType.Sp),
                        textAlign = androidx.glance.text.TextAlign.Center,
                    ),
                    maxLines = 3,
                )
            }
            Spacer(GlanceModifier.height(10.dp))
            Text(
                text = "Hold the Line",
                style = TextStyle(
                    color = ColorProvider(Color(0xFFC8A24B)),
                    fontSize = androidx.compose.ui.unit.TextUnit(13f, androidx.compose.ui.unit.TextUnitType.Sp),
                    fontWeight = FontWeight.Medium,
                ),
            )
            Text(
                text = "$points pts",
                style = TextStyle(
                    color = ColorProvider(Color(0xFF67718C)),
                    fontSize = androidx.compose.ui.unit.TextUnit(11f, androidx.compose.ui.unit.TextUnitType.Sp),
                ),
            )
        }
    }
}

class WatchtowerWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = WatchtowerWidget()
}
