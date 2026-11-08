package com.eratw.port.core.fn

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.FuncCall
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.StrLiteral
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.host.SPRITE_SLOT
import com.eratw.port.core.host.SpritePool
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `SPRITEANIMEFRAME(name)` —— 4.981 fork 新增命令的端口单测。
 *
 * 权威出处：`画蛇又添足版自改emuera相关说明.txt:236-239` 里那句
 * 「新增命令：SPRITEANIMEFRAME，获取动画精灵的帧数。统一支持 SpriteAnime、SpriteGif、
 * SpriteWebP 三种动画精灵的帧数查询。废弃 GET_GIF_FRAMES、GET_GIF_WIDTH、GET_GIF_HEIGHT、
 * PRINT_GIF_FRAME。见手册」—— fork 无 C# 源，故本文件钉的是**端口口径**（0 分支逐条），
 * 不是 C# 行号对拍。
 *
 * 装置与 `GraphicsMethodsTest` / `GdrawSpriteCompositeTest` 同形：纯 JVM、真内建注册表、
 * 状态挂 `store.aux` 的 `sprite` 槽（用例之间不串味），不吃语料环境变量。
 *
 * 帧来源只用**写侧既有命令**搭：`SPRITEANIMECREATE` 建容器、`SPRITEANIMEADDFRAME` 追加，
 * 读侧若能读回同一个数字，说明两条命令走的是同一张帧表。
 */
private class FrameBaseCtx : EvalContext {
    override lateinit var variables: VariableReader

    override fun callFunction(name: String, args: List<Value?>): Value =
        throw CodeEE("FrameBaseCtx 不该被直接调用：$name")
}

/** 一台独立的 VM 夹具（Sprite 池挂 `store.aux`）。 */
private class FrameHarness {
    val store = VariableStore()
    private val base = FrameBaseCtx()
    val ctx: BuiltinEvalContext

    init {
        base.variables = store
        ctx = BuiltinEvalContext(base, store, BuiltinHost.DEFAULT)
    }

    /** 值路径调用（返回码 / 读数）。 */
    fun call(name: String, vararg args: Any): Long =
        (ctx.callFunction(
            name,
            args.map { if (it is String) Value.StrV(it) else Value.IntV((it as Number).toLong()) },
        ) as Value.IntV).v

    /** 表达式路径调用：真实编译产物里 `{SPRITEANIMEFRAME("X")}` 就是一条 [FuncCall]。 */
    fun callExpr(name: String, arg: String): Long =
        (FuncCall(name, listOf(StrLiteral(arg)), OperandType.INT).toValue(ctx) as Value.IntV).v
}

class SpriteAnimeFrameMethodTest {

    // ==================================================================
    // 1. 正常读数：SPRITEANIMECREATE + SPRITEANIMEADDFRAME 的帧表长度
    // ==================================================================

    @Test
    fun `三帧动画精灵读回 3，零帧容器读回 0`() {
        val h = FrameHarness()
        assertEquals(1L, h.call("GCREATE", 12, 10, 10), "帧源缓冲")
        assertEquals(1L, h.call("SPRITEANIMECREATE", "GANM", 10, 10))
        // 容器刚建、帧表为空 ⇒ 0（不是 1，也不是 -1）
        assertEquals(0L, h.call("SPRITEANIMEFRAME", "GANM"), "零帧容器的帧数")

        assertEquals(1L, h.call("SPRITEANIMEADDFRAME", "GANM", 12, 0, 0, 10, 10, 0, 0, 100))
        assertEquals(1L, h.call("SPRITEANIMEFRAME", "GANM"))
        assertEquals(1L, h.call("SPRITEANIMEADDFRAME", "GANM", 12, 0, 0, 4, 4, 0, 0, 100))
        assertEquals(1L, h.call("SPRITEANIMEADDFRAME", "GANM", 12, 2, 2, 4, 4, 0, 0, 100))
        assertEquals(3L, h.call("SPRITEANIMEFRAME", "GANM"))
    }

    @Test
    fun `名字大小写不敏感，表达式路径与值路径同读数`() {
        val h = FrameHarness()
        assertEquals(1L, h.call("GCREATE", 12, 10, 10))
        assertEquals(1L, h.call("SPRITEANIMECREATE", "GANM", 10, 10))
        assertEquals(1L, h.call("SPRITEANIMEADDFRAME", "GANM", 12, 0, 0, 4, 4, 0, 0, 100))
        assertEquals(1L, h.call("SPRITEANIMEADDFRAME", "GANM", 12, 2, 2, 4, 4, 0, 0, 100))
        // 精灵池按大写归一（SpritePool.find，同原版 AppContents 的 imageDictionary）
        assertEquals(2L, h.call("SPRITEANIMEFRAME", "GANM"))
        assertEquals(2L, h.call("SPRITEANIMEFRAME", "ganm"))
        assertEquals(2L, h.call("SPRITEANIMEFRAME", "GAnM"))
        // 表达式路径（`{SPRITEANIMEFRAME("GANM")}` 的实际形态）
        assertEquals(2L, h.callExpr("SPRITEANIMEFRAME", "GANM"))
        assertEquals(2L, h.callExpr("spriteanimeframe", "ganm"))
    }

    /**
     * 「交集为空」的帧仍在帧表里，因此计入帧数。
     *
     * 出处：`SpriteAnime.cs:24-36` 的 `AnimeFrame.Normalize` 只把矩形裁空，
     * 帧对象照旧留在 `frames` 里、`totaltime` 仍加它的 delay（`:72-78`）；移植侧
     * `SPRITEANIMEADDFRAME` 用 `srcW/srcH = 0` 表达「这帧不画」，同样入表。
     */
    @Test
    fun `裁空的帧仍算一帧`() {
        val h = FrameHarness()
        assertEquals(1L, h.call("GCREATE", 12, 10, 10))
        assertEquals(1L, h.call("SPRITEANIMECREATE", "GANM", 10, 10))
        // 源矩形本身在父图内（4×4 落在 10×10 里），但 posX=10 ⇒ 与 DestBaseSize 交集为空
        assertEquals(1L, h.call("SPRITEANIMEADDFRAME", "GANM", 12, 0, 0, 4, 4, 10, 0, 100))
        assertEquals(1L, h.call("SPRITEANIMEFRAME", "GANM"), "空交集帧不画但仍在帧表里")
    }

    // ==================================================================
    // 2. 返回 0 的四类输入（不抛错）
    // ==================================================================

    @Test
    fun `未创建的名字与空名读回 0`() {
        val h = FrameHarness()
        assertEquals(0L, h.call("SPRITEANIMEFRAME", "NOSUCH"))
        assertEquals(0L, h.call("SPRITEANIMEFRAME", ""))
        assertEquals(0L, h.callExpr("SPRITEANIMEFRAME", ""))
    }

    @Test
    fun `静态 sprite 读回 0`() {
        val h = FrameHarness()
        assertEquals(1L, h.call("GCREATE", 11, 4, 4))
        assertEquals(1L, h.call("SPRITECREATE", "STATIC", 11))
        // 静态精灵的 frames 是 null（SpritePool.Sprite 的形态判据），没有帧数概念
        assertEquals(0L, h.call("SPRITEANIMEFRAME", "STATIC"))
        // 同一张表里动画与静态并存：各读各的
        assertEquals(1L, h.call("SPRITEANIMECREATE", "ANM", 4, 4))
        assertEquals(1L, h.call("SPRITEANIMEADDFRAME", "ANM", 11, 0, 0, 4, 4, 0, 0, 100))
        assertEquals(1L, h.call("SPRITEANIMEFRAME", "ANM"))
        assertEquals(0L, h.call("SPRITEANIMEFRAME", "STATIC"))
    }

    /** 纯查询不得在 `store.aux` 里凭空造池：池不在 = 一个精灵都没有 = 0。 */
    @Test
    fun `池不存在时不建槽且读回 0`() {
        val h = FrameHarness()
        assertNull(h.store.aux.peek<SpritePool>(SPRITE_SLOT), "开局不该有 sprite 槽")
        assertEquals(0L, h.call("SPRITEANIMEFRAME", "NOSUCH"))
        assertNull(
            h.store.aux.peek<SpritePool>(SPRITE_SLOT),
            "SPRITEANIMEFRAME 是只读查询，不得创建 sprite 槽",
        )
    }

    // ==================================================================
    // 3. 入表失败的帧不计数（读数是「成功入表的帧数」）
    // ==================================================================

    @Test
    fun `入表失败的帧不计数`() {
        val h = FrameHarness()
        assertEquals(1L, h.call("GCREATE", 12, 10, 10))
        assertEquals(1L, h.call("SPRITEANIMECREATE", "GANM", 10, 10))

        // delay <= 0：SPRITEANIMEADDFRAME 返回 0、不入表
        assertEquals(0L, h.call("SPRITEANIMEADDFRAME", "GANM", 12, 0, 0, 4, 4, 0, 0, 0))
        // 源矩形越出父图：返回 0、不入表
        assertEquals(0L, h.call("SPRITEANIMEADDFRAME", "GANM", 12, 0, 0, 40, 40, 0, 0, 100))
        // gid 未创建：返回 0、不入表
        assertEquals(0L, h.call("SPRITEANIMEADDFRAME", "GANM", 99, 0, 0, 4, 4, 0, 0, 100))
        assertEquals(0L, h.call("SPRITEANIMEFRAME", "GANM"), "三条失败调用都不该进帧表")

        // 对照：一条合法调用后读数才变 1
        assertEquals(1L, h.call("SPRITEANIMEADDFRAME", "GANM", 12, 0, 0, 4, 4, 0, 0, 100))
        assertEquals(1L, h.call("SPRITEANIMEFRAME", "GANM"))
    }

    // ==================================================================
    // 4. 参数校验走既有 CodeEE 惯例
    // ==================================================================

    @Test
    fun `个数与类型错走 CodeEE`() {
        val h = FrameHarness()
        val tooFew = assertFailsWith<CodeEE> { h.call("SPRITEANIMEFRAME") }
        assertTrue(
            tooFew.message!!.contains("参数个数不匹配"),
            "既有文案（BuiltinMethod.checkArity）：${tooFew.message}",
        )
        val tooMany = assertFailsWith<CodeEE> { h.call("SPRITEANIMEFRAME", "A", "B") }
        assertTrue(tooMany.message!!.contains("参数个数不匹配"), "实际：${tooMany.message}")
        // 类型错：偏函数家族的口径（fn/HtmlMethods.kt 同句式），不做隐式转串
        val wrongType = assertFailsWith<CodeEE> { h.call("SPRITEANIMEFRAME", 3) }
        assertTrue(
            wrongType.message!!.contains("必须是字符串"),
            "实际：${wrongType.message}",
        )
    }

    // ==================================================================
    // 5. 注册面：名字/返回类型/元数/大小写，且不是占位
    // ==================================================================

    @Test
    fun `注册表登记为 INT 一元函数`() {
        val m = assertNotNull(Builtins.registry["SPRITEANIMEFRAME"], "内建表必须有这条")
        assertEquals(OperandType.INT, m.retType)
        assertEquals(1, m.minArgs)
        assertEquals(1, m.maxArgs)
        assertEquals(m, Builtins.registry["spriteanimeframe"], "名字大小写不敏感（同 Config.StrComper）")
        assertTrue(
            !Builtins.registry.isPlaceholder("SPRITEANIMEFRAME"),
            "真实现不该留在占位名单里",
        )
        // 已废弃的四条 GIF 命令在本端口从未登记（没有并行实现要兼容）
        for (gone in listOf("GET_GIF_FRAMES", "GET_GIF_WIDTH", "GET_GIF_HEIGHT", "PRINT_GIF_FRAME")) {
            assertNull(Builtins.registry[gone], "$gone 应保持未登记")
        }
    }
}
