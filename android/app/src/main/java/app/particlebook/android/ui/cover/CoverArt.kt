package app.particlebook.android.ui.cover

import android.graphics.Bitmap
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.particlebook.android.ui.Motion
import app.particlebook.android.ui.data.Book
import app.particlebook.android.ui.data.BookCoverLoader
import kotlin.math.abs

/**
 * 封面系统（§17.1.6 / §17.3 #3-#4）：
 * 真实封面（PDF 首页 / EPUB cover-image）优先，失败回落 [GradientCover]。
 * 首次先画渐变兜底，真实位图到达后 Crossfade(180ms) 淡入。
 */
@Composable
fun BookCover(
    book: Book,
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.medium,
    compact: Boolean = false,
) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(
        initialValue = BookCoverLoader.fromMemory(book.id),
        book.id,
        book.filePath,
    ) {
        value = BookCoverLoader.fromMemory(book.id)
        value = BookCoverLoader.load(context, book)
    }
    Crossfade(
        targetState = bitmap,
        animationSpec = Motion.coverFade<Float>(),
        label = "coverCrossfade",
        modifier = modifier.clip(shape),
    ) { image ->
        if (image != null) {
            // 装饰性图片：书名由调用方的卡片/行语义节点朗读，这里再报一次会重复。
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(shape),
            )
        } else {
            GradientCover(
                book = book,
                compact = compact,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(shape),
            )
        }
    }
}

/**
 * 一组封面配色：容器 role + 与它**配对**的内容 role。
 * 渐变第二档由两者混合得出，所以文字/水印只许取 [onContainer]——不会出现
 * 「primaryContainer 底 + onSurfaceVariant 字」这种跨配对组合。
 */
data class CoverTint(val container: Color, val onContainer: Color) {
    /** 同族深一档（向配对内容色混合 38%），用作渐变终点；色相不跑偏，明度不塌。 */
    val deeper: Color get() = lerp(container, onContainer, 0.38f)
}

/**
 * 六组配色 = 当前主题的六个语义容器家族：雾蓝 / 暖沙 / 陶粉 / 石灰 / 墨色 / 中褐。
 *
 * 这里不再写死 hex：浅色与深色主题各取各的容器角色，
 * 「按书封生成柔和底色」的意图保留，配色随主题走；调色板增删只改这一个列表。
 */
internal fun coverTintFamilies(scheme: ColorScheme): List<CoverTint> = listOf(
    CoverTint(scheme.primaryContainer, scheme.onPrimaryContainer),
    CoverTint(scheme.secondaryContainer, scheme.onSecondaryContainer),
    CoverTint(scheme.tertiaryContainer, scheme.onTertiaryContainer),
    CoverTint(scheme.surfaceVariant, scheme.onSurfaceVariant),
    CoverTint(scheme.inverseSurface, scheme.inverseOnSurface),
    CoverTint(scheme.secondary, scheme.onSecondary),
)

/** 书名 hash 稳定取一组配色（同书每次同色）。 */
fun coverTint(book: Book, scheme: ColorScheme): CoverTint {
    val families = coverTintFamilies(scheme)
    val seed = (book.title + "|" + (book.author ?: "")).hashCode()
    return families[abs(seed) % families.size]
}

/** 组合期取配色：跟随主题（切换深浅色即刻重算），同书同色。 */
@Composable
fun rememberCoverTint(book: Book): CoverTint {
    val scheme = MaterialTheme.colorScheme
    return remember(book.id, book.title, book.author, scheme) { coverTint(book, scheme) }
}

/**
 * 程序化渐变封面：双色渐变 + 12% scrim 遮罩 + 衬线首字（96sp、18%、右下裁切）。
 * 禁止使用通用书本图标（§17.1.1 不变量）。
 */
@Composable
fun GradientCover(
    book: Book,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val tint = rememberCoverTint(book)
    val scrim = MaterialTheme.colorScheme.scrim
    Box(
        modifier = modifier.background(
            Brush.linearGradient(listOf(tint.container, tint.deeper)),
        ),
    ) {
        // 12% scrim：左下压深、右上透气，制造体积。scrim 是 M3 的「压暗」语义角色。
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            scrim.copy(alpha = 0.12f),
                            Color.Transparent,
                            scrim.copy(alpha = 0.12f),
                        ),
                    ),
                ),
        )
        // 首字水印：取配对内容色 18%，右下裁切（compact 缩略图按比例缩）。
        Text(
            text = book.title.take(1),
            color = tint.onContainer.copy(alpha = 0.18f),
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.Bold,
            fontSize = if (compact) 44.sp else 96.sp,
            lineHeight = if (compact) 44.sp else 96.sp,
            maxLines = 1,
            overflow = TextOverflow.Clip,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset(x = if (compact) 6.dp else 18.dp, y = if (compact) 8.dp else 22.dp),
        )
    }
}

/** 空书架装饰：三本叠放的渐变封面（不用通用书本图标；纯装饰，无 contentDescription）。 */
@Composable
fun GeneratedCoverStack(modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val families = coverTintFamilies(scheme)
    val corner = MaterialTheme.shapes.medium
    Box(modifier = modifier.size(width = 148.dp, height = 108.dp)) {
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .size(width = 66.dp, height = 88.dp)
                .clip(corner)
                .background(Brush.linearGradient(listOf(families[1].container, families[1].deeper))),
        )
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .size(width = 66.dp, height = 96.dp)
                .clip(corner)
                .background(Brush.linearGradient(listOf(families[0].container, families[0].deeper))),
        )
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .size(width = 66.dp, height = 82.dp)
                .clip(corner)
                .background(Brush.linearGradient(listOf(families[3].container, families[3].deeper))),
        )
    }
}
