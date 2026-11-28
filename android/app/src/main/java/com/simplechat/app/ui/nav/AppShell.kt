package com.simplechat.app.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simplechat.app.App
import com.simplechat.app.ui.RootScreen
import com.simplechat.app.ui.nav.IconVariant

/**
 * 三个 tab 的外壳。聊天页仍是原来那一整套（不动），
 * 记忆与设置先给骨架，等有内容再往里填。
 *
 * 为什么不用 Navigation 组件：只有三个平级页面，没有深链、没有回退栈语义，
 * 一个 `rememberSaveable` 的枚举就够了。
 */
enum class HomeTab { CHAT, MEMORY, SETTINGS }

/**
 * 聊天页不显示底栏：输入框、发送按钮都在底部，再加一条会抢空间。
 * 抽成函数是为了能直接断言（Compose 层在 JVM 里跑不起来）。
 */
fun showBottomBar(tab: HomeTab): Boolean = tab != HomeTab.CHAT

@Composable
fun AppShell() {
    val context = LocalContext.current
    val container = (context.applicationContext as App).container
    val settings by container.settingsStore.flow.collectAsStateWithLifecycle(initialValue = null)
    var tab by rememberSaveable { mutableStateOf(HomeTab.CHAT) }

    Column(Modifier.fillMaxSize().background(NavPalette.background())) {
        Box(Modifier.weight(1f)) {
            when (tab) {
                HomeTab.CHAT -> Box(Modifier.fillMaxSize()) {
                    RootScreen()
                    // 聊天页不占底栏：输入框本来就在底部，再多一条会和它抢空间
                    ChatTabHandle(onOpen = { tab = it })
                }
                HomeTab.MEMORY -> MemoryHomeScreen(IconVariant.of(settings?.iconVariant))
                HomeTab.SETTINGS -> SettingsHomeScreen()
            }
        }
        if (showBottomBar(tab)) {
            BottomBar(
                current = tab,
                variant = IconVariant.of(settings?.iconVariant),
                onSelect = { tab = it },
            )
        }
    }
}

@Composable
private fun BottomBar(
    current: HomeTab,
    variant: IconVariant,
    onSelect: (HomeTab) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(NavPalette.secondary())
            .navigationBarsPadding()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NavItem("对话", current == HomeTab.CHAT, {
            Icon(
                imageVector = Icons.Outlined.ChatBubbleOutline,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = NavPalette.tint(current == HomeTab.CHAT),
            )
        }, onClick = { onSelect(HomeTab.CHAT) })

        NavItem("记忆", current == HomeTab.MEMORY, {
            VariantIcon(
                variant = variant,
                modifier = Modifier.size(24.dp),
                tint = NavPalette.tint(current == HomeTab.MEMORY),
            )
        }, onClick = { onSelect(HomeTab.MEMORY) })

        NavItem("设置", current == HomeTab.SETTINGS, {
            Icon(
                imageVector = Icons.Outlined.Settings,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = NavPalette.tint(current == HomeTab.SETTINGS),
            )
        }, onClick = { onSelect(HomeTab.SETTINGS) })
    }
}

@Composable
private fun NavItem(
    label: String,
    selected: Boolean,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        icon()
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            fontSize = NavType.caption,
            color = NavPalette.tint(selected),
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        )
    }
}

/**
 * 聊天页里的入口：左下角一个小胶囊，点开选记忆 / 设置。
 *
 * 放左下不放右上：右上角是聊天页自己的菜单区，压上去会挡到重新生成。
 */
@Composable
private fun ChatTabHandle(onOpen: (HomeTab) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().padding(start = 12.dp, bottom = 96.dp), Alignment.BottomStart) {
        Text(
            text = "≡",
            fontSize = 18.sp,
            color = NavPalette.secondaryText(),
            modifier = Modifier
                .background(NavPalette.secondary(), RoundedCornerShape(12.dp))
                .clickable { expanded = true }
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("记忆", fontSize = NavType.detail) },
                onClick = { expanded = false; onOpen(HomeTab.MEMORY) },
            )
            DropdownMenuItem(
                text = { Text("设置", fontSize = NavType.detail) },
                onClick = { expanded = false; onOpen(HomeTab.SETTINGS) },
            )
        }
    }
}

/** 记忆 tab 的空状态：图标 + 一句话，没有别的。 */
@Composable
private fun MemoryHomeScreen(variant: IconVariant) {
    Box(
        Modifier.fillMaxSize().background(NavPalette.background()).statusBarsPadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            VariantIcon(
                variant = variant,
                modifier = Modifier.size(48.dp),
                tint = NavPalette.tertiaryText(),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "还没有记忆，聊几句就有",
                fontSize = NavType.body,
                color = NavPalette.secondaryText(),
            )
        }
    }
}

/** 设置 tab 的骨架：四项，点了进空白页。 */
@Composable
private fun SettingsHomeScreen() {
    var opened by rememberSaveable { mutableStateOf<String?>(null) }
    val items = listOf("管家", "模块", "角色卡", "关于")
    Box(Modifier.fillMaxSize().background(NavPalette.background())) {
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(top = 24.dp)) {
            Text(
                text = "设置",
                fontSize = NavType.title,
                fontWeight = FontWeight.SemiBold,
                color = NavPalette.primaryText(),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            items.forEach { item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { opened = item }
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = item,
                        fontSize = NavType.body,
                        color = NavPalette.primaryText(),
                        modifier = Modifier.weight(1f),
                    )
                    Text(text = "›", fontSize = NavType.body, color = NavPalette.tertiaryText())
                }
            }
        }
        opened?.let { title ->
            // 空白页：先占位，内容后面做
            Box(
                Modifier
                    .fillMaxSize()
                    .background(NavPalette.background())
                    .clickable { opened = null },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = title + "（待做）",
                    fontSize = NavType.body,
                    color = NavPalette.secondaryText(),
                )
            }
        }
    }
}

/**
 * 新骨架用的配色：按设计稿的十六进制值。
 *
 * 不复用主题里的 `TextPrimary*`（那是「正文色 × alpha」体系）——
 * 这三屏是全新的、还没接壁纸与主题色，先按绝对值来，等接上主题再统一。
 */
internal object NavPalette {
    private data class NavColors(
        val background: Color,
        val secondary: Color,
        val primaryText: Color,
        val secondaryText: Color,
        val accent: Color,
    )

    private val light = NavColors(
        background = Color(0xFFFFFFFF),
        secondary = Color(0xFFF5F5F7),
        primaryText = Color(0xFF1D1D1F),
        secondaryText = Color(0xFF86868B),
        accent = Color(0xFF0A84FF),
    )
    private val dark = NavColors(
        background = Color(0xFF000000),
        secondary = Color(0xFF1C1C1E),
        primaryText = Color(0xFFF5F5F7),
        secondaryText = Color(0xFF98989D),
        accent = Color(0xFF0A84FF),
    )

    @Composable private fun colors(): NavColors =
        if (MaterialTheme.colorScheme.background.luminance() < 0.5f) dark else light

    @Composable fun background(): Color = colors().background
    @Composable fun secondary(): Color = colors().secondary
    @Composable fun primaryText(): Color = colors().primaryText
    @Composable fun secondaryText(): Color = colors().secondaryText
    @Composable fun tertiaryText(): Color = colors().secondaryText.copy(alpha = 0.6f)
    @Composable fun tint(selected: Boolean): Color =
        colors().let { if (selected) it.accent else it.secondaryText }

    private fun Color.luminance(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue
}

/** 字号：22 / 16 / 14 / 12。 */
internal object NavType {
    val title = 22.sp
    val body = 16.sp
    val caption = 12.sp
    val detail = 14.sp
}
