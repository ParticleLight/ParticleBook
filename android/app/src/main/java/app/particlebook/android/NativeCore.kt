package app.particlebook.android

object NativeCore {
    @Volatile
    var loadMs: Long = -1
        private set

    fun loadOnce() {
        if (loadMs >= 0) return
        val t0 = System.nanoTime()
        System.loadLibrary("pbcore")
        loadMs = (System.nanoTime() - t0) / 1_000_000
    }

    external fun ping(): Long
    external fun version(): String
}
