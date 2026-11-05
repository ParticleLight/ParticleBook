package app.particlebook.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 阅读器统一 chrome：所有格式（EPUB / PDF / 后续 TXT）共用同一套顶栏与底部胶囊，
 * 避免各格式各写一份颜色/高度/圆角/描边导致观感漂移。
 *
 * 顶栏：88dp 高、surface@88% 半透明、AppType.topBarTitle、返回固定左侧。
 * 底栏：64dp 高、surfaceContainerHigh@92%、CircleShape、1dp 白 12% 描边、floatingShadow。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderTopBar(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(88.dp),
            contentAlignment = Alignment.Center,
        ) {
            TopAppBar(
                title = {
                    Text(
                        text = title,
                        style = AppType.topBarTitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回书架",
                        )
                    }
                },
                actions = actions,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        }
    }
}

@Composable
internal fun ReaderBottomPill(content: @Composable RowScope.() -> Unit) {
    val shape = CircleShape
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.xl)
            .navigationBarsPadding()
            .padding(bottom = Spacing.md)
            .height(64.dp)
            .floatingShadow(shape),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}
