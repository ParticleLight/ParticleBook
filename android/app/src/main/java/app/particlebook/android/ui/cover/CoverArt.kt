package app.particlebook.android.ui.cover

import android.graphics.Bitmap
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.particlebook.android.ui.Motion
import app.particlebook.android.ui.Spacing
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
    shape: Shape = RoundedCornerShape(Spacing.coverCornerLarge),
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

/** §17.1.1 的 6 组程序化封面渐变（暖沙 / 雾蓝 / 藕紫 / 苔绿 / 陶粉 / 石灰）。 */
private val CoverPalettes: List<Pair<Color, Color>> = listOf(
    Color(0xFFF6D6B8) to Color(0xFFE9A36B),
    Color(0xFFD9E4F5) to Color(0xFF8FA9D0),
    Color(0xFFE8D7E9) to Color(0xFFB98FC0),
    Color(0xFFDDE9D5) to Color(0xFF8FB183),
    Color(0xFFF3DADB) to Color(0xFFCC9296),
    Color(0xFFE7E1D6) to Color(0xFFA79C8B),
)

/** 书名 hash 稳定取一组渐变（同书每次同色）。 */
fun coverPalette(book: Book): Pair<Color, Color> {
    val seed = (book.title + "|" + (book.author ?: "")).hashCode()
    val index = abs(seed) % CoverPalettes.size
    return CoverPalettes[index]
}

/**
 * 程序化渐变封面：135° 双色渐变 + 12% 黑遮罩 + 衬线首字（96sp、白 22%、右下裁切）。
 * 禁止使用通用书本图标（§17.1.1 不变量）。
 */
@Composable
fun GradientCover(
    book: Book,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val palette = remember(book.id, book.title, book.author) { coverPalette(book) }
    Box(modifier = modifier.background(Brush.linearGradient(listOf(palette.first, palette.second)))) {
        // 12% 黑遮罩：左下压深、右上透气，制造体积。
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.12f),
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.12f),
                        ),
                    ),
                ),
        )
        // 首字：白 22%，右下裁切（compact 缩略图按比例缩）。
        Text(
            text = book.title.take(1),
            color = Color.White.copy(alpha = 0.22f),
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

/** 空书架装饰：三本叠放的渐变封面（不用通用书本图标）。 */
@Composable
fun GeneratedCoverStack(modifier: Modifier = Modifier) {
    Box(modifier.size(width = 148.dp, height = 108.dp)) {
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .size(width = 66.dp, height = 88.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Brush.linearGradient(listOf(CoverPalettes[1].first, CoverPalettes[1].second))),
        )
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .size(width = 66.dp, height = 96.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Brush.linearGradient(listOf(CoverPalettes[0].first, CoverPalettes[0].second))),
        )
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .size(width = 66.dp, height = 82.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Brush.linearGradient(listOf(CoverPalettes[3].first, CoverPalettes[3].second))),
        )
    }
}
