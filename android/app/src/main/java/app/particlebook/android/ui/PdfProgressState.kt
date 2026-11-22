package app.particlebook.android.ui

/** 初始布局可自动补页；只有明确导航之后的可见页变化才解除失效 locator 保护。 */
internal class PdfProgressState(protectRestore: Boolean) {
    private var saveEnabled = !protectRestore
    private var lastVisiblePage: Int? = null
    private var navigationFrom: Int? = null

    fun navigationStarted(currentPage: Int?) {
        if (!saveEnabled && navigationFrom == null) {
            navigationFrom = currentPage ?: lastVisiblePage
        }
    }

    fun visiblePage(page: Int): Boolean {
        lastVisiblePage = page
        if (!saveEnabled && navigationFrom?.let { it != page } == true) saveEnabled = true
        return saveEnabled
    }
}
