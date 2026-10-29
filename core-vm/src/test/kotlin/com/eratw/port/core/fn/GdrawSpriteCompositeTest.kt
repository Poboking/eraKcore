package com.eratw.port.core.fn

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.Expr
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.StrLiteral
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VarRef
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.host.GraphicsBuffer
import com.eratw.port.core.host.GraphicsPool
import com.eratw.port.core.host.SpritePool
import com.eratw.port.core.host.unsignedArgb
import com.eratw.port.core.vars.DimSpec
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 总账 GFX-01：`GDRAWSPRITE` 必须真的把 sprite 的像素合成进目标缓冲。
 *
 * 判据是**合成后 [GraphicsBuffer.pixelAt] 的字面值**，不是返回值。期望值三类出处：
 * 1. 原版换算式（`reference/emuera-cs/MinorShift.Emuera.UI.Game.Image/`）：
 *    缺省矩形与四种形态 = `FunctionMethodCreator.cs:7405-7445`；静态 sprite 的偏移与宽高重算 =
 *    `ASpriteSingle.cs:77-99`（`Point.IsEmpty` 守卫在 :79，四个整除在 :81-84）；
 *    动画 sprite 的换算 = `SpriteAnime.cs:148-172`，取帧与空帧守卫 = `:90-119` / `:92-95`；
 *    源外采样 = 透明（GDI+ `DrawImage` 的越界口径，dotnet 对拍读数见 `GraphicsMethodsTest`
 *    的「GDRAWG 源矩形越界」用例）。
 * 2. 颜色矩阵：脚本侧元素 `/ 256f`（`FunctionMethodCreator.cs:9432` / `:9459`）；
 *    通道换位、偏移行、夹取与舍入 = **2026-10-23 本机 System.Drawing（GDI+）实测**，逐条读数
 *    登记在 [GraphicsBuffer.applyColorMatrix] 的 KDoc。本文件的 `0xFF462327`（= 255,70,35,39）、
 *    `0xFFA46464`（= 255,164,100,100）、`0xFF00FF32`（= 255,0,255,50）、保持 0、保持
 *    `0xFF0A141E` 都是那批实测值，**不是**拿移植侧实现算出来的数当期望。
 * 3. alpha 合成 = GDI+ 默认 `CompositingMode.SourceOver`（全仓无赋值点 ⇒ 用默认值）：
 *    实测 源(A128,R200,G100,B50) 叠 目标(A255,R10,G20,B30) ⇒ (A255,R105,G60,B40)。
 *
 * 装置与 `GraphicsMethodsTest` 同形（纯 JVM、真内建注册表、不吃语料环境变量）。
 * 第 7 位是数组引用 ⇒ 表达式层入口 [EvalContext.callFunctionRef] 是真实脚本的唯一通道，
 * 2/4/6 参另外再走一遍值入口 [EvalContext.callFunction] 钉两条路径同结果。
 */
private class GdcBaseCtx : EvalContext {
    override lateinit var variables: VariableReader

    override fun callFunction(name: String, args: List<Value?>): Value =
        throw CodeEE("GdcBaseCtx 不该被直接调用：$name")
}

/** 一台独立的 VM 夹具（图形池 / Sprite 池挂 `store.aux`，用例之间不串味）。 */
private class GdcHarness {
    val store = VariableStore()
    private val base = GdcBaseCtx()
    val ctx: BuiltinEvalContext

    init {
        base.variables = store
        ctx = BuiltinEvalContext(base, store, BuiltinHost.DEFAULT)
    }

    /** 值路径调用（返回码）。 */
    fun call(name: String, vararg args: Any): Long =
        (ctx.callFunction(
            name,
            args.map { if (it is String) Value.StrV(it) else Value.IntV((it as Number).toLong()) },
        ) as Value.IntV).v

    /**
     * 引用路径调用（真实脚本的形态）：数字/字符串折成字面量，[Expr] 原样传入
     * （颜色矩阵那一位必须是 [VarRef]）。
     */
    fun callRef(name: String, vararg args: Any?): Long =
        (ctx.callFunctionRef(
            name,
            args.map {
                when (it) {
                    null -> null
                    is Expr -> it
                    is String -> StrLiteral(it)
                    is Number -> IntLiteral(it.toLong())
                    else -> throw IllegalArgumentException("用例实参类型不支持：$it")
                }
            },
        ) as Value.IntV).v

    fun pool(): GraphicsPool = store.aux.slot("gfx") { GraphicsPool() }
    fun sprites(): SpritePool = store.aux.slot("sprite") { SpritePool() }

    /** 缓冲 [id] 第 (x,y) 格的 ARGB。 */
    fun pixel(id: Int, x: Int, y: Int): Int = pool().get(id).pixelAt(x, y)

    /**
     * 建一张 w×h 缓冲并把像素**原样**铺成 [colors]（行主序；只给一个颜色就铺满）。
     *
     * 走 `GCLEAR` + `GSETCOLOR` 而不是直接写数组：`GSETCOLOR` 是
     * `Bitmap.SetPixel` 的直写通道（spec: FunctionMethodCreator.cs:6274-6293），
     * alpha=0 / alpha=128 这类夹具值能按字面落进缓冲（`GCLEAR` 会把 alpha=0 的
     * 颜色归零，见 [GraphicsBuffer.clear]），夹具本身就不依赖被测代码。
     */
    fun fill(id: Int, w: Int, h: Int, vararg colors: Int) {
        assertEquals(1L, call("GCREATE", id, w, h))
        assertEquals(1L, call("GCLEAR", id, 0))
        for (y in 0 until h) {
            for (x in 0 until w) {
                val v = if (colors.size == 1) colors[0] else colors[y * w + x]
                assertEquals(1L, call("GSETCOLOR", id, unsignedArgb(v), x, y))
            }
        }
    }

    /** 声明一个 5x5 的用户整数数组（颜色矩阵槽）并按行主序写入 25 个值。 */
    fun declareMatrix(name: String, values: LongArray) {
        store.declareUserVar(DimSpec(name, false, intArrayOf(5, 5)))
        for (i in 0..4) {
            for (j in 0..4) {
                store.writeInt(name, longArrayOf(i.toLong(), j.toLong()), values[i * 5 + j])
            }
        }
    }

    /** `MAT:0:0` 这种引用实参形态（spec: FunctionMethodCreator.cs:9404 的 VariableTerm）。 */
    fun matrixRef(name: String, row: Long = 0, col: Long = 0): Expr =
        VarRef(name, listOf(IntLiteral(row), IntLiteral(col)), operandType = OperandType.INT)

    companion object {
        /** 单位阵（脚本侧的对角 256，尺度出处 spec: FunctionMethodCreator.cs:9432 的 `/ 256f`）。 */
        val IDENTITY = longArrayOf(
            256, 0, 0, 0, 0,
            0, 256, 0, 0, 0,
            0, 0, 256, 0, 0,
            0, 0, 0, 256, 0,
            0, 0, 0, 0, 256,
        )

        /**
         * 语料预设「夜」= 对角 90/90/200/256/256
         * （`eratw-画蛇添足版eratw4.980/ERB/ステータス表示関連/グラフィック特殊効果.ERB:288-295`）。
         */
        val NIGHT = longArrayOf(
            90, 0, 0, 0, 0,
            0, 90, 0, 0, 0,
            0, 0, 200, 0, 0,
            0, 0, 0, 256, 0,
            0, 0, 0, 0, 256,
        )

        /** 把行主序的 `IDENTITY` 第 [index] 个元素改成 [value] 后返回新表（不改动常量）。 */
        fun matrixWith(index: Int, value: Long): LongArray = IDENTITY.copyOf().also { it[index] = value }
    }
}

class GdrawSpriteCompositeTest {

    // ==================================================================
    // 一、静态 sprite 的四种参数形态（缺省矩形 / 点位 / 目标矩形）
    // ==================================================================

    /**
     * 4 参形态：点位来自实参，宽高**仍是** `DestBaseSize`
     * （spec: FunctionMethodCreator.cs:7421-7433 + ASpriteSingle.cs:79 的 IsEmpty 守卫
     * —— pos 为 0 时矩形完全不重算）。
     */
    @Test
    fun `4参形态把sprite原样贴到指定点`() {
        val h = GdcHarness()
        h.fill(10, 2, 2, 0xFF112233.toInt(), 0xFF445566.toInt(), 0xFF778899.toInt(), 0xFFAABBCC.toInt())
        assertEquals(1L, h.call("SPRITECREATE", "SPR1", 10))
        h.fill(11, 4, 4, 0)

        assertEquals(1L, h.call("GDRAWSPRITE", 11, "SPR1", 1, 1))
        assertEquals(0xFF112233.toInt(), h.pixel(11, 1, 1))
        assertEquals(0xFF445566.toInt(), h.pixel(11, 2, 1))
        assertEquals(0xFF778899.toInt(), h.pixel(11, 1, 2))
        assertEquals(0xFFAABBCC.toInt(), h.pixel(11, 2, 2))
        // 落点之外一个像素都没动
        assertEquals(0, h.pixel(11, 0, 0))
        assertEquals(0, h.pixel(11, 3, 3))
        assertEquals(0, h.pixel(11, 0, 3))

        // 引用路径（真实脚本走的这条）同结果
        h.fill(12, 4, 4, 0)
        assertEquals(1L, h.callRef("GDRAWSPRITE", 12, "SPR1", 1, 1))
        assertEquals(0xFF112233.toInt(), h.pixel(12, 1, 1))
        assertEquals(0xFFAABBCC.toInt(), h.pixel(12, 2, 2))
    }

    /**
     * 2 参形态：矩形 = `(0,0,DestBaseSize)`（spec: FunctionMethodCreator.cs:7421-7425），
     * 点位由 `DestBasePosition` 在 `ASpriteSingle.cs:81-82` 加上。此时
     * destRect.Width == DestBaseSize.Width ⇒ `posX * w / DestBaseSize.W` 约掉，
     * 偏移就是 posX/posY 原值，宽高在 :83-84 也保持原样。
     */
    @Test
    fun `2参形态的点位来自posX-posY而不是实参`() {
        val h = GdcHarness()
        h.fill(10, 2, 2, 0xFF112233.toInt(), 0xFF445566.toInt(), 0xFF778899.toInt(), 0xFFAABBCC.toInt())
        assertEquals(1L, h.call("SPRITECREATE", "SPR1", 10))
        h.fill(11, 4, 4, 0)
        // pos 为 0 ⇒ 贴左上角
        assertEquals(1L, h.callRef("GDRAWSPRITE", 11, "SPR1"))
        assertEquals(0xFF112233.toInt(), h.pixel(11, 0, 0))
        assertEquals(0xFFAABBCC.toInt(), h.pixel(11, 1, 1))
        assertEquals(0, h.pixel(11, 2, 0))

        // SPRITESETPOS(1,0) ⇒ 整体右移 1（DestBasePosition 就是 SPRITEPOSX/Y 读的那个，
        // spec: FunctionMethodCreator.cs:6720-6740 + ASprite.cs:37-40 的 Move）
        assertEquals(1L, h.call("SPRITESETPOS", "SPR1", 1, 0))
        h.fill(12, 4, 4, 0)
        assertEquals(1L, h.callRef("GDRAWSPRITE", 12, "SPR1"))
        assertEquals(0xFF112233.toInt(), h.pixel(12, 1, 0))
        assertEquals(0xFF445566.toInt(), h.pixel(12, 2, 0))
        assertEquals(0xFF778899.toInt(), h.pixel(12, 1, 1))
        assertEquals(0xFFAABBCC.toInt(), h.pixel(12, 2, 1))
        assertEquals(0, h.pixel(12, 0, 0))
        assertEquals(0, h.pixel(12, 3, 3))
    }

    /**
     * 6 参形态：`ReadRectangle` 整体替换矩形（spec: FunctionMethodCreator.cs:7435-7440）。
     * 目标 4x4 装 2x2 源 ⇒ `srcX(i) = i*2/4` ⇒ 每格放大成 2x2（最近邻，见
     * [GraphicsBuffer.blitFrom] 的取样口径）。
     */
    @Test
    fun `6参形态的目标矩形整体替换并按声明尺寸缩放`() {
        val h = GdcHarness()
        h.fill(10, 2, 2, 0xFF112233.toInt(), 0xFF445566.toInt(), 0xFF778899.toInt(), 0xFFAABBCC.toInt())
        assertEquals(1L, h.call("SPRITECREATE", "SPR1", 10))
        h.fill(11, 4, 4, 0)

        assertEquals(1L, h.callRef("GDRAWSPRITE", 11, "SPR1", 0, 0, 4, 4))
        assertEquals(0xFF112233.toInt(), h.pixel(11, 0, 0))
        assertEquals(0xFF112233.toInt(), h.pixel(11, 1, 0))
        assertEquals(0xFF445566.toInt(), h.pixel(11, 2, 0))
        assertEquals(0xFF445566.toInt(), h.pixel(11, 3, 0))
        assertEquals(0xFF778899.toInt(), h.pixel(11, 0, 2))
        assertEquals(0xFF778899.toInt(), h.pixel(11, 1, 3))
        assertEquals(0xFFAABBCC.toInt(), h.pixel(11, 2, 2))
        assertEquals(0xFFAABBCC.toInt(), h.pixel(11, 3, 3))
    }

    /**
     * pos 非 0 的 6 参形态：`ASpriteSingle.cs:81-84` **同时**加偏移并重算宽高
     * （`w = w * SrcRectangle.Width / DestBaseSize.Width`）。
     * 夹具：基图 4x4，源矩形只取左上 2x2，pos=(1,0)，DestBaseSize=4x4，目标矩形 4x4
     * ⇒ nx = 0 + 1*4/4 = 1、nw = 4*2/4 = 2 ⇒ 只画 (1,0) 起那块 2x2。
     * 这条同时否掉两种「按常识」的写法：不重算宽高（会画满 4x4）、不加偏移（会画在 (0,0)）。
     */
    @Test
    fun `pos非0时目标宽高按源与DestBaseSize的比例重算`() {
        val h = GdcHarness()
        h.fill(50, 4, 4, 0xFF000000.toInt())
        assertEquals(1L, h.call("GSETCOLOR", 50, 0xFF112233, 0, 0))
        assertEquals(1L, h.call("GSETCOLOR", 50, 0xFF445566, 1, 0))
        assertEquals(1L, h.call("GSETCOLOR", 50, 0xFF778899, 0, 1))
        assertEquals(1L, h.call("GSETCOLOR", 50, 0xFFAABBCC, 1, 1))
        // SPRITECREATE 10 参：name, gid, x,y,w,h, px,py, destW,destH（spec: :7016-7071）
        assertEquals(1L, h.call("SPRITECREATE", "SPRP", 50, 0, 0, 2, 2, 1, 0, 4, 4))
        h.fill(51, 4, 4, 0xFF000000.toInt())

        assertEquals(1L, h.callRef("GDRAWSPRITE", 51, "SPRP", 0, 0, 4, 4))
        assertEquals(0xFF112233.toInt(), h.pixel(51, 1, 0))
        assertEquals(0xFF445566.toInt(), h.pixel(51, 2, 0))
        assertEquals(0xFF778899.toInt(), h.pixel(51, 1, 1))
        assertEquals(0xFFAABBCC.toInt(), h.pixel(51, 2, 1))
        // 偏移之外、重算宽度之外 ⇒ 保持底色
        assertEquals(0xFF000000.toInt(), h.pixel(51, 0, 0))
        assertEquals(0xFF000000.toInt(), h.pixel(51, 3, 0))
        assertEquals(0xFF000000.toInt(), h.pixel(51, 3, 3))
        assertEquals(0xFF000000.toInt(), h.pixel(51, 0, 3))
    }

    // ==================================================================
    // 二、alpha 合成与颜色矩阵（7 参）
    // ==================================================================

    /**
     * alpha 合成是**叠加**（GDI+ 默认 SourceOver，全仓无 `CompositingMode` 赋值点）。
     * 期望值 = 2026-10-23 本机 System.Drawing 实测：源 (A128,R200,G100,B50) 叠
     * 目标 (A255,R10,G20,B30) ⇒ (A255,R105,G60,B40) = `0xFF693C28`。
     * 换算后 alpha=0 的源像素一个不改目标（同批实测：目标保持 (255,10,20,30)）。
     */
    @Test
    fun `半透明像素按source-over叠加而不是覆盖`() {
        val h = GdcHarness()
        h.fill(20, 2, 2, 0x80C86432.toInt())
        assertEquals(1L, h.call("SPRITECREATE", "SPRA", 20))
        h.fill(21, 2, 2, 0xFF0A141E.toInt())

        assertEquals(1L, h.callRef("GDRAWSPRITE", 21, "SPRA", 0, 0))
        val expect = 0xFF693C28.toInt()
        assertEquals(expect, h.pixel(21, 0, 0))
        assertEquals(expect, h.pixel(21, 1, 0))
        assertEquals(expect, h.pixel(21, 0, 1))
        assertEquals(expect, h.pixel(21, 1, 1))

        // 完全透明但 RGB 非 0 的源像素：目标原样保持（不是被 0x00RRGGBB 覆盖）
        h.fill(22, 2, 2, 0x00C86432)
        assertEquals(1L, h.call("SPRITECREATE", "SPRT", 22))
        h.fill(23, 2, 2, 0xFF0A141E.toInt())
        assertEquals(1L, h.callRef("GDRAWSPRITE", 23, "SPRT", 0, 0))
        assertEquals(0xFF0A141E.toInt(), h.pixel(23, 0, 0))
        assertEquals(0xFF0A141E.toInt(), h.pixel(23, 1, 1))
    }

    /**
     * 7 参形态：颜色矩阵（`ReadColormatrix`，spec: FunctionMethodCreator.cs:9402-9464，
     * 元素尺度 `/ 256f` 在 :9432）。
     * 期望值出自 2026-10-23 本机 GDI+ 实测：源 (255,200,100,50) 过「夜」预设
     * （对角 90/90/200/256）⇒ (255,70,35,39) = `round(200*90/256)` / `round(100*90/256)` /
     * `round(50*200/256)`，alpha 保持 255。
     */
    @Test
    fun `7参颜色矩阵逐通道换算与语料夜预设`() {
        val h = GdcHarness()
        h.fill(30, 1, 1, 0xFFC86432.toInt())
        assertEquals(1L, h.call("SPRITECREATE", "SPRN", 30))
        h.declareMatrix("CM", GdcHarness.NIGHT)

        h.fill(31, 1, 1, 0)
        assertEquals(1L, h.callRef("GDRAWSPRITE", 31, "SPRN", 0, 0, 1, 1, h.matrixRef("CM")))
        assertEquals(0xFF462327.toInt(), h.pixel(31, 0, 0))

        // 单位阵（对角 256 = 1.0）逐通道原样透传
        h.declareMatrix("ID", GdcHarness.IDENTITY)
        h.fill(32, 1, 1, 0)
        assertEquals(1L, h.callRef("GDRAWSPRITE", 32, "SPRN", 0, 0, 1, 1, h.matrixRef("ID")))
        assertEquals(0xFFC86432.toInt(), h.pixel(32, 0, 0))

        // 5x5 全 0 ⇒ 换算后 alpha=0 ⇒ 什么都不画（同批实测：目标保持原值）。
        // 语料 `COLOR_MATRIX_PRESET` 的 CASEELSE 就是 `VARSET ARG_CM:0:0 , 0`
        // （ERB/ステータス表示関連/グラフィック特殊効果.ERB:298），
        // 该处注释声称「全黑」，原版实测是**什么都不画** —— 按实测钉，不跟着注释走。
        h.fill(33, 1, 1, 0xFF0A141E.toInt())
        h.declareMatrix("CMZ", LongArray(25))
        assertEquals(1L, h.callRef("GDRAWSPRITE", 33, "SPRN", 0, 0, 1, 1, h.matrixRef("CMZ")))
        assertEquals(0xFF0A141E.toInt(), h.pixel(33, 0, 0))
    }

    /**
     * 颜色矩阵的**偏移行**（第 4 行）：`out[dst] = Σ 源通道 × m[源][dst] + m[4][dst]`。
     * 同批实测：`m[4][0] = 64/256` 对源 R=100 ⇒ `round((100/255 + 0.25) * 255)` = 164，
     * 其余通道原样；把同一个 0.25 写在 `m[0][4]` 不产生任何偏移 ⇒ 偏移住在第 4 行。
     */
    @Test
    fun `颜色矩阵的偏移住在第四行`() {
        val h = GdcHarness()
        h.fill(30, 1, 1, 0xFF646464.toInt())
        assertEquals(1L, h.call("SPRITECREATE", "SPRO", 30))
        h.declareMatrix("CM", GdcHarness.matrixWith(4 * 5 + 0, 64L))
        h.fill(31, 1, 1, 0)

        assertEquals(1L, h.callRef("GDRAWSPRITE", 31, "SPRO", 0, 0, 1, 1, h.matrixRef("CM")))
        assertEquals(0xFFA46464.toInt(), h.pixel(31, 0, 0))
    }

    /**
     * 矩阵基址越出数组：`num + 5 > GetLength(0)` 那一条（spec: FunctionMethodCreator.cs:9423-9426），
     * 文案取 zhs 语言表（`lang.emuera-zhs.xml:936`：`{0}函数: ColorMatrix的要素({1}, {2})不恰当或者大小不满足5x5`）。
     */
    @Test
    fun `矩阵基址越出数组时报原版文案且不画任何像素`() {
        val h = GdcHarness()
        h.fill(30, 1, 1, 0xFFC86432.toInt())
        assertEquals(1L, h.call("SPRITECREATE", "SPRN", 30))
        h.declareMatrix("CM", GdcHarness.IDENTITY)
        h.fill(31, 1, 1, 0xFF0A141E.toInt())

        val err = assertFailsWith<CodeEE> {
            h.callRef("GDRAWSPRITE", 31, "SPRN", 0, 0, 1, 1, h.matrixRef("CM", 1, 1))
        }
        assertTrue(
            err.message!!.contains("ColorMatrix的要素(1, 1)不恰当或者大小不满足5x5"),
            "实际文案：${err.message}",
        )
        assertEquals(0xFF0A141E.toInt(), h.pixel(31, 0, 0))
    }

    /** 第 7 位必须是数组引用（装载期 ArgType = RefInt2D / RefInt3D 加 AllowConstRef，spec: FunctionMethod.cs:27,39,43）。 */
    @Test
    fun `颜色矩阵位不是数组引用时报错`() {
        val h = GdcHarness()
        h.fill(30, 1, 1, 0xFFC86432.toInt())
        assertEquals(1L, h.call("SPRITECREATE", "SPRN", 30))
        h.declareMatrix("CM", GdcHarness.IDENTITY)
        h.fill(31, 1, 1, 0)
        // 字面量当矩阵：报错，且不写像素
        val err = assertFailsWith<CodeEE> {
            h.callRef("GDRAWSPRITE", 31, "SPRN", 0, 0, 1, 1, 256)
        }
        assertTrue(err.message!!.contains("二维或三维整数数组变量的引用"), "实际文案：${err.message}")
        assertEquals(0, h.pixel(31, 0, 0))
        // 值路径拿不到数组身份 ⇒ 同一条报错（表达式层恒走引用路径，这条只兜宿主直调）
        assertFailsWith<CodeEE> { h.call("GDRAWSPRITE", 31, "SPRN", 0, 0, 1, 1, 256) }
    }

    // ==================================================================
    // 三、越界三态
    // ==================================================================

    /**
     * 目标矩形越出缓冲：只画落在缓冲内的部分（`ASpriteSingle.cs:86` 交给 GDI+ 的
     * `DrawImage`，裁剪口径与 [GraphicsBuffer.blitFrom] 同源）。
     */
    @Test
    fun `目标矩形越界只画落在缓冲内的部分`() {
        val h = GdcHarness()
        h.fill(10, 2, 2, 0xFF112233.toInt(), 0xFF445566.toInt(), 0xFF778899.toInt(), 0xFFAABBCC.toInt())
        assertEquals(1L, h.call("SPRITECREATE", "SPR1", 10))
        h.fill(11, 4, 4, 0xFF000000.toInt())

        // (-1,-1) 起贴 2x2 ⇒ 只有源 (1,1) 落进 (0,0)
        assertEquals(1L, h.callRef("GDRAWSPRITE", 11, "SPR1", -1, -1, 2, 2))
        assertEquals(0xFFAABBCC.toInt(), h.pixel(11, 0, 0))
        assertEquals(0xFF000000.toInt(), h.pixel(11, 1, 1))
        assertEquals(0xFF000000.toInt(), h.pixel(11, 0, 1))

        // 完全在面外：返回码仍是 1（原版不判可见性，spec: :7437-7444），像素全不动
        assertEquals(1L, h.callRef("GDRAWSPRITE", 11, "SPR1", 100, 100, 2, 2))
        assertEquals(0xFFAABBCC.toInt(), h.pixel(11, 0, 0))
        assertEquals(0xFF000000.toInt(), h.pixel(11, 1, 0))

        // 负宽高：ReadRectangle 只拒 0（spec: FunctionMethodCreator.cs:9390-9398），
        // 负矩形 GDI+ 什么都不画（同批实测，既有断言见 GraphicsMethodsTest 的「GDRAWG 源矩形越界」）
        assertEquals(1L, h.callRef("GDRAWSPRITE", 11, "SPR1", 0, 0, -2, -2))
        assertEquals(0xFFAABBCC.toInt(), h.pixel(11, 0, 0))
        assertEquals(0xFF000000.toInt(), h.pixel(11, 1, 1))
    }

    /**
     * 源矩形越出 sprite 基图：`SPRITECREATE` 只要求源矩形与父图**相交**
     * （`Rectangle.IntersectsWith`，spec: FunctionMethodCreator.cs:7044），
     * 越出部分照贴 ⇒ GDI+ 对基图外的采样给透明 ⇒ 一个像素都不写
     * （dotnet 实测口径与「GDRAWG 源矩形越界」用例同源）。
     */
    @Test
    fun `源矩形越出基图的部分不写像素`() {
        val h = GdcHarness()
        h.fill(10, 2, 2, 0xFF112233.toInt(), 0xFF445566.toInt(), 0xFF778899.toInt(), 0xFFAABBCC.toInt())
        // 源矩形 (1,1,4,4) 只与 2x2 基图相交一格 ⇒ sprite 仍按声明的 4x4 尺寸贴
        assertEquals(1L, h.call("SPRITECREATE", "SPRO", 10, 1, 1, 4, 4))
        h.fill(11, 4, 4, 0)

        assertEquals(1L, h.callRef("GDRAWSPRITE", 11, "SPRO", 0, 0, 4, 4))
        assertEquals(0xFFAABBCC.toInt(), h.pixel(11, 0, 0))
        assertEquals(0, h.pixel(11, 1, 1))
        assertEquals(0, h.pixel(11, 2, 2))
        assertEquals(0, h.pixel(11, 3, 3))
    }

    // ==================================================================
    // 一之附、负尺寸取绝对值（ASprite.cs:16-23）
    // ==================================================================

    /**
     * 6 参形态的负宽高源矩形：`IntersectsWith` 是 `rx<W && 0<rx+rw && ry<H && 0<ry+rh`
     * 四项严格比较（spec: FunctionMethodCreator.cs:7044），负宽高时 `Right = rx+rw < rx`
     * 仍按归一化区间判交 ⇒ `(3,1,-2,2)` 与 4x4 父图**放行**（3<4 && 0<1 && 1<4 && 0<3）。
     * 函数层不取绝对值（destSize = rect.Size，:7043），兜底在 `ASprite` 构造器
     * （ASprite.cs:16-23 负 Width/Height 取反）⇒ `DestBaseSize` = (2,2)，
     * `SPRITEWIDTH`/`SPRITEHEIGHT` 读回的必须是绝对值。
     * 绘制面不钉：负 `SrcRectangle` 贴出来是什么连 PC 都量不到
     * （render/.../ImageFrameSource.kt:53 登记的卡点），本用例只钉 VM 可观测面。
     */
    @Test
    fun `6参负宽高源矩形取绝对值后登记`() {
        val h = GdcHarness()
        h.fill(60, 4, 4, 0xFF112233.toInt())
        assertEquals(1L, h.call("SPRITECREATE", "SPRN6", 60, 3, 1, -2, 2))
        assertEquals(2L, h.call("SPRITEWIDTH", "SPRN6"))
        assertEquals(2L, h.call("SPRITEHEIGHT", "SPRN6"))
    }

    /**
     * 10 参形态的负目标尺寸：函数层就地取绝对值（spec: FunctionMethodCreator.cs:7059-7066），
     * 随后 `ASprite` 构造器（:16-23）再兜底一次（幂等）。源矩形保持 (0,0,2,2) 正值。
     */
    @Test
    fun `10参负目标尺寸取绝对值`() {
        val h = GdcHarness()
        h.fill(62, 2, 2, 0xFF112233.toInt())
        assertEquals(1L, h.call("SPRITECREATE", "SPRN10", 62, 0, 0, 2, 2, 0, 0, -4, -6))
        assertEquals(4L, h.call("SPRITEWIDTH", "SPRN10"))
        assertEquals(6L, h.call("SPRITEHEIGHT", "SPRN10"))
    }

    /**
     * 对照组：真正与父图不相交的源矩形仍抛 `CodeEE`（`ImgRefOutOfRange`，:7044-7047）——
     * 证明负尺寸放行来自 `IntersectsWith` 的归一化比较，不是把越界检查关掉了。
     * `(5,0,2,2)` 与 4x4 父图：5<4 不成立 ⇒ 不相交。
     */
    @Test
    fun `真正越界的源矩形仍然抛出`() {
        val h = GdcHarness()
        h.fill(63, 4, 4, 0xFF112233.toInt())
        assertFailsWith<CodeEE> { h.call("SPRITECREATE", "SPROUT", 63, 5, 0, 2, 2) }
        // 负向整体飞出：(-4,0,-2,2) ⇒ 0 < -4+(-2) 不成立
        assertFailsWith<CodeEE> { h.call("SPRITECREATE", "SPROUT2", 63, -4, 0, -2, 2) }
    }

    /**
     * sprite 的基图就是目标缓冲（基准 664 行的形态）：读侧必须是**冻结快照**
     * （[GraphicsBuffer.blitFrom] 的快照通道，同 `GDRAWG` 的自贴口径）。
     * 夹具让「读」与「写」错开一格 ⇒ 边读边写会把整行同化成第一个像素。
     */
    @Test
    fun `自贴图按冻结快照而不是边读边写`() {
        val h = GdcHarness()
        h.fill(61, 4, 1, 0xFF111111.toInt(), 0xFF222222.toInt(), 0xFF333333.toInt(), 0xFF444444.toInt())
        assertEquals(1L, h.call("SPRITECREATE", "SPRX", 61))
        // 目标矩形 (1,0,4,1) ⇒ 只有 i=0,1,2 落在面内 ⇒ dest(1)=src(0)、dest(2)=src(1)、dest(3)=src(2)
        assertEquals(1L, h.callRef("GDRAWSPRITE", 61, "SPRX", 1, 0))
        assertEquals(0xFF111111.toInt(), h.pixel(61, 0, 0), "未被写入，保持原值")
        assertEquals(0xFF111111.toInt(), h.pixel(61, 1, 0))
        assertEquals(0xFF222222.toInt(), h.pixel(61, 2, 0), "边读边写会得到 0xFF111111")
        assertEquals(0xFF333333.toInt(), h.pixel(61, 3, 0), "边读边写会得到 0xFF111111")
    }

    // ==================================================================
    // 四、动画 sprite 的取帧口径（定时器归总账 GFX-07）
    // ==================================================================

    /**
     * 动画 sprite 被画哪一帧：原版 `GetCurrentFrame()` 用挂钟（`SpriteAnime.cs:96-117`），
     * `lastFrame == -1` 时返回 `FrameList[0]`；移植侧无挂钟可复现（`animeTimerMs` 无读者 =
     * 总账 GFX-07）⇒ 当前实现**恒取第 0 帧**。
     * 夹具让两帧的颜色与偏移都不同 ⇒ 取了第 1 帧就会画在 (2,0) 且颜色是 0xFF222222。
     * 换算式与静态形态不同：**没有** IsEmpty 守卫、宽高超比用的是**帧的**源尺寸
     * （`SpriteAnime.cs:153-156`）：nx = 0 + (0+0)*4/4 = 0、nw = 2*4/4 = 2。
     */
    @Test
    fun `动画sprite画第0帧并按帧偏移换算`() {
        val h = GdcHarness()
        h.fill(40, 2, 2, 0xFF111111.toInt())
        h.fill(41, 2, 2, 0xFF222222.toInt())
        assertEquals(1L, h.call("SPRITEANIMECREATE", "ANM", 4, 2))
        assertEquals(1L, h.call("SPRITEANIMEADDFRAME", "ANM", 40, 0, 0, 2, 2, 0, 0, 100))
        assertEquals(1L, h.call("SPRITEANIMEADDFRAME", "ANM", 41, 0, 0, 2, 2, 2, 0, 100))
        h.fill(42, 4, 2, 0)

        assertEquals(1L, h.callRef("GDRAWSPRITE", 42, "ANM"))
        assertEquals(0xFF111111.toInt(), h.pixel(42, 0, 0))
        assertEquals(0xFF111111.toInt(), h.pixel(42, 1, 1))
        assertEquals(0, h.pixel(42, 2, 0), "取了第 1 帧才会画在这里")
        assertEquals(0, h.pixel(42, 3, 1))

        // 单帧 + 偏移 (2,0)：帧偏移参与 nx 的换算（SpriteAnime.cs:153）
        assertEquals(1L, h.call("SPRITEANIMECREATE", "ANM2", 4, 2))
        assertEquals(1L, h.call("SPRITEANIMEADDFRAME", "ANM2", 41, 0, 0, 2, 2, 2, 0, 100))
        h.fill(43, 4, 2, 0)
        assertEquals(1L, h.callRef("GDRAWSPRITE", 43, "ANM2"))
        assertEquals(0xFF222222.toInt(), h.pixel(43, 2, 0))
        assertEquals(0xFF222222.toInt(), h.pixel(43, 3, 1))
        assertEquals(0, h.pixel(43, 0, 0))
        assertEquals(0, h.pixel(43, 1, 1))
    }

    /**
     * 没有任何帧的动画 sprite：`IsCreated` 恒 true（`SpriteAnime.cs:54`）⇒ 返回码 1，
     * 但 `totaltime <= 0` 让 `GetCurrentFrame()` 返回 null ⇒ 一个像素都不画（:92-95）。
     * 基准 673 行前置状态里那 35 个 `seedRuntimeSprites` 正是这一类。
     */
    @Test
    fun `空帧列表的动画sprite不画任何像素但返回1`() {
        val h = GdcHarness()
        assertEquals(1L, h.call("SPRITEANIMECREATE", "ANM0", 2, 2))
        assertTrue(h.sprites().find("ANM0")!!.isCreated, "SpriteAnime.IsCreated 恒真（SpriteAnime.cs:54）")
        h.fill(43, 2, 2, 0xFF0A141E.toInt())

        assertEquals(1L, h.callRef("GDRAWSPRITE", 43, "ANM0"))
        assertEquals(0xFF0A141E.toInt(), h.pixel(43, 0, 0))
        assertEquals(0xFF0A141E.toInt(), h.pixel(43, 1, 1))
    }

    /**
     * 动画 sprite 的某一帧在 `SPRITEANIMEADDFRAME` 之后被 `GDISPOSE`：
     * 原版绘制时逐帧查 `BaseImage.IsCreated`（`SpriteAnime.cs:151`）⇒ 那一帧什么都不画，
     * 返回码仍是 1（`FunctionMethodCreator.cs:7417` 只在**取 sprite 时**查一次 IsCreated）。
     */
    @Test
    fun `帧的基图被释放后不画但返回1`() {
        val h = GdcHarness()
        h.fill(40, 2, 2, 0xFF111111.toInt())
        h.fill(44, 2, 2, 0xFF555555.toInt())
        assertEquals(1L, h.call("SPRITEANIMECREATE", "ANM3", 2, 2))
        assertEquals(1L, h.call("SPRITEANIMEADDFRAME", "ANM3", 44, 0, 0, 2, 2, 0, 0, 100))
        assertEquals(1L, h.call("GDISPOSE", 44))
        h.fill(43, 2, 2, 0xFF0A141E.toInt())

        assertEquals(1L, h.callRef("GDRAWSPRITE", 43, "ANM3"))
        assertEquals(0xFF0A141E.toInt(), h.pixel(43, 0, 0))
        assertEquals(0xFF0A141E.toInt(), h.pixel(43, 1, 1))
    }

    // ==================================================================
    // 五、失败返回码（改动不许顺手修好别的东西）
    // ==================================================================

    /** 图形未创建 / Sprite 取不到 / 基图已释放 ⇒ 0（spec: FunctionMethodCreator.cs:7412-7420）。 */
    @Test
    fun `未创建与找不到的场合仍然只返回0`() {
        val h = GdcHarness()
        h.fill(10, 2, 2, 0xFF112233.toInt())
        assertEquals(1L, h.call("SPRITECREATE", "SPR1", 10))
        h.fill(11, 4, 4, 0)

        assertEquals(0L, h.callRef("GDRAWSPRITE", 999, "SPR1", 0, 0))
        assertEquals(0L, h.callRef("GDRAWSPRITE", 11, "NO_SUCH_SPR", 0, 0))
        // 基图被 GDISPOSE ⇒ Sprite.isCreated 转假（ASpriteSingle.cs:24-34）
        assertEquals(1L, h.call("GDISPOSE", 10))
        assertEquals(0L, h.callRef("GDRAWSPRITE", 11, "SPR1", 0, 0))
        // 三条失败路径都没留下像素
        assertEquals(0, h.pixel(11, 0, 0))
        assertEquals(0, h.pixel(11, 1, 1))
    }

    /** 参数个数只允许 2/4/6/7（spec: FunctionMethodCreator.cs:7352-7400 的四份 ArgTypeList）。 */
    @Test
    fun `非法参数个数报参数个数不匹配`() {
        val h = GdcHarness()
        h.fill(10, 2, 2, 0xFF112233.toInt())
        assertEquals(1L, h.call("SPRITECREATE", "SPR1", 10))
        val err = assertFailsWith<CodeEE> { h.callRef("GDRAWSPRITE", 10, "SPR1", 0) }
        assertTrue(err.message!!.contains("需要 2/4/6/7 个"), "实际文案：${err.message}")
    }

    /**
     * [GraphicsBuffer.applyColorMatrix] 的直接对拍：通道换位（行 = 源、列 = 目标）与
     * 夹取/舍入档位，全部是 2026-10-23 本机 System.Drawing 的实测读数。
     */
    @Test
    fun `矩阵换算的通道换位与夹取舍入`() {
        // m[0][0] = 0 且 m[0][1] = 1（第 1 行仍是单位行）：实测 (255,200,100,50) ⇒ (255,0,255,50)
        // ⇒ 源 R 送进目标 G（G' = 源R + 源G = 200 + 100 = 300 → 夹到 1.0 → 255）；
        // 若按「行 = 目标通道」写就会得到 R' = 源G = 100（实测否掉了这一种）。
        val swap = GdcHarness.IDENTITY.copyOf()
        swap[0 * 5 + 0] = 0L
        swap[0 * 5 + 1] = 256L
        val m = toMatrix(swap)
        assertEquals(0xFF00FF32.toInt(), GraphicsBuffer.applyColorMatrix(0xFFC86432.toInt(), m))

        // 语料尺度 90/256 对 R=1/2/4/5/7/9/255 ⇒ 0/1/1/2/2/3/90（四舍五入，不是向下取整）
        val hm = toMatrix(GdcHarness.matrixWith(0, 90L))
        for ((input, expect) in listOf(1 to 0, 2 to 1, 4 to 1, 5 to 2, 7 to 2, 9 to 3, 255 to 90)) {
            val src = 0xFF000000.toInt() or (input shl 16)
            assertEquals(
                expect shl 16,
                GraphicsBuffer.applyColorMatrix(src, hm) and 0x00FF0000,
                "R=$input 期望 R=$expect",
            )
        }
        // 负值夹 0（m[0][0] = -256 = -1.0）、超过 1 夹 255（m[0][0] = 512 = 2.0）
        assertEquals(0, GraphicsBuffer.applyColorMatrix(0xFFC86432.toInt(), toMatrix(GdcHarness.matrixWith(0, -256L))) shr 16 and 0xFF)
        assertEquals(0xFF, GraphicsBuffer.applyColorMatrix(0xFFC86432.toInt(), toMatrix(GdcHarness.matrixWith(0, 512L))) shr 16 and 0xFF)
        // 换算作用在直通值上：源 A=0、RGB 非 0，配「A 行偏移 +128/256」⇒ 实测得到
        // 与 (A128,R200,G100,B50) 直接合成完全一致的读数（若矩阵作用在预乘值上 RGB 会是 0）
        val am = toMatrix(GdcHarness.IDENTITY.copyOf().also { it[4 * 5 + 3] = 128L })
        assertEquals(0x80C86432.toInt(), GraphicsBuffer.applyColorMatrix(0x00C86432, am))
    }

    // ==================================================================
    // 四、GDRAWG 的 11 参矩阵形态（spec: FunctionMethodCreator.cs:7248-7274）
    // ==================================================================

    /**
     * 11 参形态：第 11 位是颜色矩阵（:7270 的 `ReadColormatrix(..., 10)` ⇒ 报错口径的
     * 「第 11 个参数」），两个矩形在矩阵之前读（:7261-7262）。
     * 期望值与 GDRAWSPRITE 7 参同源：2026-10-23 本机 GDI+ 实测，
     * 源 (255,200,100,50) 过「夜」⇒ (255,70,35,39) = `0xFF462327`。
     */
    @Test
    fun `GDRAWG 11参矩阵按夜预设逐通道换算`() {
        val h = GdcHarness()
        h.fill(40, 1, 1, 0xFFC86432.toInt())
        h.declareMatrix("CM", GdcHarness.NIGHT)

        h.fill(41, 1, 1, 0)
        assertEquals(1L, h.callRef("GDRAWG", 41, 40, 0, 0, 1, 1, 0, 0, 1, 1, h.matrixRef("CM")))
        assertEquals(0xFF462327.toInt(), h.pixel(41, 0, 0))

        // 10 参对照组：第 11 位整个不给 ⇒ 无矩阵原样透传（:7266 第一个析取项）
        h.fill(42, 1, 1, 0)
        assertEquals(1L, h.callRef("GDRAWG", 42, 40, 0, 0, 1, 1, 0, 0, 1, 1))
        assertEquals(0xFFC86432.toInt(), h.pixel(42, 0, 0))
    }

    /**
     * 第 11 位显式省略（:7266 的 `arguments[10] == null`，装载期 `OmitStart = 10`）
     * ⇒ 与 10 参同一条无矩阵通道。两条调用路径的 nil 形态不同（Expr 表 vs 值表），
     * 这里各钉一枪，防 [GdrawGArgs.isOmitted] 回归成只看 Expr。
     */
    @Test
    fun `GDRAWG 第11位省略时按无矩阵贴`() {
        val h = GdcHarness()
        h.fill(40, 1, 1, 0xFFC86432.toInt())
        h.declareMatrix("CM", GdcHarness.NIGHT)

        // 引用路径（真实脚本）：Expr 表第 11 位是 null
        h.fill(41, 1, 1, 0)
        assertEquals(1L, h.callRef("GDRAWG", 41, 40, 0, 0, 1, 1, 0, 0, 1, 1, null))
        assertEquals(0xFFC86432.toInt(), h.pixel(41, 0, 0))

        // 值路径（宿主直调）：值表第 11 位是 null
        h.fill(42, 1, 1, 0)
        assertEquals(
            1L,
            (
                h.ctx.callFunction(
                    "GDRAWG",
                    listOf(
                        Value.IntV(42), Value.IntV(40),
                        Value.IntV(0), Value.IntV(0), Value.IntV(1), Value.IntV(1),
                        Value.IntV(0), Value.IntV(0), Value.IntV(1), Value.IntV(1),
                        null,
                    ),
                ) as Value.IntV
                ).v,
        )
        assertEquals(0xFFC86432.toInt(), h.pixel(42, 0, 0))
    }

    /**
     * 第 11 位给了但不是数组引用 ⇒ 文案说「第 11 个参数」（argNo=11，
     * spec: :7270 的 `ReadColormatrix(base.Name, exm, arguments, 10)`）；
     * 装载期 ArgType 是 RefInt2D/RefInt3D（:7221 / :7239）。
     * 值路径第 11 位有值但数组身份已折丢 ⇒ 同一条报错，与 GDRAWSPRITE 7 参同口径。
     */
    @Test
    fun `GDRAWG 第11位不是数组引用时报错且不画`() {
        val h = GdcHarness()
        h.fill(40, 1, 1, 0xFFC86432.toInt())
        h.fill(41, 1, 1, 0xFF0A141E.toInt())

        val err = assertFailsWith<CodeEE> {
            h.callRef("GDRAWG", 41, 40, 0, 0, 1, 1, 0, 0, 1, 1, 256)
        }
        assertTrue(
            err.message!!.contains("第 11 个参数必须是二维或三维整数数组变量的引用"),
            "实际文案：${err.message}",
        )
        assertEquals(0xFF0A141E.toInt(), h.pixel(41, 0, 0))

        // 值路径：表达式层恒走引用路径，这条只兜宿主/用户函数直调
        assertFailsWith<CodeEE> { h.call("GDRAWG", 41, 40, 0, 0, 1, 1, 0, 0, 1, 1, 256) }
    }

    /**
     * 求值顺序（spec: :7252-7262）：dst/src 任一未创建就 `return 0`，
     * **两个矩形与矩阵位都不求值** —— 宽 0 的矩形在原版要等 `ReadRectangle` 才抛
     * （:9377-9400），图形不存在时根本走不到。这条钉住惰性读取器，
     * 防回归成「整张实参表预求值」的旧形态（那会把返回 0 变成 CodeEE）。
     */
    @Test
    fun `GDRAWG 图形未创建时矩形不求值`() {
        val h = GdcHarness()
        h.fill(40, 1, 1, 0xFFC86432.toInt())

        // dst 不存在：宽 0 的目标矩形连求值都不发生 ⇒ 0 而不是 CodeEE
        assertEquals(0L, h.callRef("GDRAWG", 999, 40, 0, 0, 0, 1, 0, 0, 1, 1))

        h.fill(41, 1, 1, 0)
        // src 不存在：同样不求值
        assertEquals(0L, h.callRef("GDRAWG", 41, 999, 0, 0, 0, 1, 0, 0, 1, 1))

        // 矩阵位同理：dst 不存在时第 11 位连看都不看（给了个非法矩阵也不抛）
        assertEquals(0L, h.callRef("GDRAWG", 999, 40, 0, 0, 1, 1, 0, 0, 1, 1, 256))
    }

    /** 行主序 25 个脚本值（尺度 256 = 1.0）→ [GraphicsBuffer.applyColorMatrix] 的 Float 表。 */
    private fun toMatrix(values: LongArray): FloatArray {
        val m = FloatArray(GraphicsBuffer.COLOR_MATRIX_SIZE)
        for (i in 0 until GraphicsBuffer.COLOR_MATRIX_SIZE) m[i] = values[i] / 256f
        return m
    }
}
