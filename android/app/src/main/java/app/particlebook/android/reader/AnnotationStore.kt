package app.particlebook.android.reader

/**
 * P1 标注持久化契约（Lead 独占，2026-11-15 冻结）。
 *
 * 设计约束：
 * - 纯 JVM 接口，不依赖 Android/Compose；UI 与阅读器只依赖它。
 * - 所有方法同步执行；调用方负责放到 IO dispatcher，主线程零 IO。
 * - 返回的 [ReaderAnnotation] 必须带持久化 id 与 createdAt/updatedAt；
 *   [add] 收到 id=0 的模型时由实现分配 id。
 * - [remove] 幂等：不存在（或已删）返回 false，不抛异常。
 * - 列表按 createdAt 升序，只返回未删除记录。
 */
interface AnnotationStore {
    fun list(bookId: Long): List<ReaderAnnotation>

    fun add(annotation: ReaderAnnotation): ReaderAnnotation

    /** 按 id 更新 note/color/quote 等可编辑字段；不存在返回 null。 */
    fun update(annotation: ReaderAnnotation): ReaderAnnotation?

    fun remove(annotationId: Long): Boolean
}
