package com.simplechat.app.ui.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.PsychologyAlt
import androidx.compose.material.icons.outlined.ScatterPlot
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import com.simplechat.app.R

/**
 * 「记忆」入口图标的候选。
 *
 * 为什么做成可切换：这个图标是产品人格的一部分，靠嘴讨论没意义，
 * 装上机挨个看一遍才知道哪个对。选择存本地。
 */
enum class IconVariant(val id: String, val label: String) {
    CONSTELLATION("constellation", "星座（自绘）"),
    HUB("hub", "枢纽"),
    SCATTER("scatter", "散点"),
    PSYCHOLOGY("psychology", "心理学"),
    SPARKLES("sparkles", "星芒"),
    NEUROLOGY("neurology", "神经学"),
    ;

    companion object {
        val DEFAULT = CONSTELLATION

        fun of(raw: String?): IconVariant = entries.firstOrNull { it.id == raw } ?: DEFAULT

        fun summary(): String = entries.joinToString(" / ") { it.id }
    }
}

/** Material 那五个；自绘的走 drawable。 */
private val materialIcons: Map<IconVariant, ImageVector> = mapOf(
    IconVariant.HUB to Icons.Outlined.Hub,
    IconVariant.SCATTER to Icons.Outlined.ScatterPlot,
    IconVariant.PSYCHOLOGY to Icons.Outlined.Psychology,
    IconVariant.SPARKLES to Icons.Outlined.AutoAwesome,
    IconVariant.NEUROLOGY to Icons.Outlined.PsychologyAlt,
)

/** 一个 variant 出一个图标；尺寸、颜色由调用方给。 */
@Composable
fun VariantIcon(
    variant: IconVariant,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
) {
    val material = materialIcons[variant]
    if (material != null) {
        Icon(imageVector = material, contentDescription = null, modifier = modifier, tint = tint)
    } else {
        Icon(
            painter = painterResource(R.drawable.ic_constellation),
            contentDescription = null,
            modifier = modifier,
            tint = tint,
        )
    }
}
