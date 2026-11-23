package app.particlebook.android.ui.about

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.particlebook.android.ui.AppType
import app.particlebook.android.ui.Spacing

/**
 * 「关于」二级页共用的返回行：返回键 + 页面标题。
 *
 * 不用 `TopAppBar`：这两页没有滚动折叠、没有动作菜单，`SearchScreen` 的全屏二级页也是同一套
 * 自绘返回行——顶栏高度、返回键触控目标（[Spacing.minTouchTarget]）与标题字级因此完全一致，
 * 也不需要再处理一次 app bar 的 inset。
 */
@Composable
internal fun AboutTopBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier.size(Spacing.minTouchTarget),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
            )
        }
        Spacer(Modifier.width(Spacing.xs))
        Text(
            text = title,
            style = AppType.sectionTitle,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}
