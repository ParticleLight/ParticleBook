package app.particlebook.android.reader.epub.layout

import app.particlebook.android.reader.epub.EpubCharLineMetrics
import org.junit.Assert.assertTrue
import org.junit.Test

class StaticLineMetricsTest {
    @Test
    fun adapterImplementsCharLineMetricsContract() {
        // 不实例化 StaticLayout（纯 JVM 不可用），只验证生产适配器与
        // controller 的字符定位契约绑定；真实 lineStart/lineEnd 由设备断言覆盖。
        assertTrue(EpubCharLineMetrics::class.java.isAssignableFrom(StaticLineMetrics::class.java))
    }
}
