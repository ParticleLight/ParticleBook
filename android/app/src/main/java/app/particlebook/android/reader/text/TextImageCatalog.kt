package app.particlebook.android.reader.text

/**
 * 正文插图的目录索引：把段落流里的插图段抽成一条线性的图片列表。
 *
 * 渲染层两处要用同一套编号，所以只留这一个口径：
 * - 全屏查看器的「第 n / 共 m 张」按**出现次数**算，同一张内嵌图在正文里出现两次就是两张；
 * - 点段落里的插图要反查它在列表里的位置（上一张 / 下一张翻页）。
 *
 * 因此这里**不去重**：key 相同的图片各自占一个位置。去重会让第 2 次出现的图点开时落到第 1 次
 * 的位置，翻页也会凭空少一张。[TextImage.key] 只是渲染层的缓存键（避免 LazyColumn 重组时重复
 * 解码），不承担「同一资源只算一张」的语义。
 *
 * 纯函数、不依赖 Android：入参是已解析好的 [TextDocument]；查表一次线性扫描即可，
 * 插图段的数量级远小于段落数，建映射表换不来收益，反而多一份要跟段落列表同步的状态。
 */
internal object TextImageCatalog {

    /** 按段落顺序收集所有插图段，跳过纯文字段；**不去重**。 */
    fun images(document: TextDocument): List<TextImage> {
        val paragraphs = document.paragraphs
        // 不按段落数预分配：一本几十万段的书通常只有几十张图，按段落数开数组等于白占几 MB。
        val images = ArrayList<TextImage>()
        for (paragraph in paragraphs) {
            val image = paragraph.image ?: continue
            images.add(image)
        }
        return images
    }

    /**
     * 该段在 [images] 里的下标（= 它前面有多少个插图段）；段落越界或该段不是插图段时返回 null。
     *
     * 越界返回 null 而不是夹到合法范围：调用方拿这个下标去初始化查看器 Pager，夹边会让
     * 「越界 / 无图」静默变成「打开第一张」或「打开最后一张」，比直接不打开更难查。
     * 空文档没有任何段落，走的是同一条越界分支。
     */
    fun indexOfParagraph(document: TextDocument, paragraphIndex: Int): Int? {
        val paragraphs = document.paragraphs
        if (paragraphIndex < 0 || paragraphIndex >= paragraphs.size) return null
        if (paragraphs[paragraphIndex].image == null) return null
        var imageIndex = 0
        for (index in 0 until paragraphIndex) {
            if (paragraphs[index].image != null) imageIndex++
        }
        return imageIndex
    }

    /**
     * [images] 中第一个 key 相等者的下标；没有这张图返回 null（空文档同样返回 null）。
     *
     * 「第一个」而不是最后一个：按 key 查只可能给一个答案，取最早出现的那次符合阅读顺序；
     * 要落到某一段自己那张图（同一资源在正文里出现多次），调用方该用 [indexOfParagraph]。
     */
    fun indexOfKey(document: TextDocument, key: String): Int? {
        var imageIndex = 0
        for (paragraph in document.paragraphs) {
            val image = paragraph.image ?: continue
            if (image.key == key) return imageIndex
            imageIndex++
        }
        return null
    }
}
