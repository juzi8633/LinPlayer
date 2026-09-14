package xyz.linplayer.app.core

import android.view.Surface
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * UI 看得见的核心层。真实现是 [CoreClient];另一个实现只在 JVM 出图 / 焦点测试里
 * (`src/test/.../FakeCore.kt`)—— Robolectric 上没有 liblpcore.so,真客户端连构造都起不来。
 */
interface CorePort {
    val events: SharedFlow<CoreEvent>
    val localBaseUrl: String
    val localToken: String

    suspend fun callJson(
        command: String,
        args: JsonObject? = null,
        onPartial: ((JsonElement) -> Unit)? = null,
    ): JsonElement

    fun setSurface(surface: Surface?, w: Int, h: Int): Int
}
