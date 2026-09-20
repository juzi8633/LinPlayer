package xyz.linplayer.app.ui.plugin

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/*
回调实参里的 Map / List 必须**递归**转成 JSON。

☠ 2026-09-20 在 TV 上撞出来的真 bug:jsonOf 少了 Map 分支,`onRange({from,to})`
   落到 toString(),JS 收到字符串 "{from=0, to=24}",`r.from|0` 悄悄算成 0 ——
   虚拟列表窗口变 0..0,一千项全没了,**不报错**。
   判据只能是序列化结果本身:「空白但可滚」和「正常」在截图上差不出来。
*/
class EventArgsJsonTest {
    @Test
    fun `范围对象要转成 JSON 对象而不是字符串`() {
        val v = jsonOf(mapOf("from" to 0, "to" to 24))
        assertTrue("不是 JsonObject,说明落到了 toString():$v", v is JsonObject)
        assertEquals("""{"from":0,"to":24}""", v.toString())
    }

    @Test
    fun `列表要转成 JSON 数组`() {
        val v = jsonOf(listOf(1, "a", true))
        assertTrue("不是 JsonArray:$v", v is JsonArray)
        assertEquals("""[1,"a",true]""", v.toString())
    }
}
