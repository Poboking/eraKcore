package com.eratw.port.render.displaylist

import com.eratw.port.render.Grid
import com.eratw.port.render.RunKind
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 类型形态规约：把 ADR-001 / ADR-002 / ADR-003 的三条硬约束变成可执行断言，
 * 避免以后有人往绘制命令里塞一个 `measure()`、或往 `DisplayList` 里塞一把锁。
 *
 * 用 java 反射而非 kotlin-reflect —— `:render` 的测试依赖里没有 kotlin-reflect
 * （见 `render/build.gradle.kts`），字段类型集合这类判据用 JVM 反射足够且零新依赖。
 */
class DrawCmdShapeTest {

    private val primitiveInt = Int::class.javaPrimitiveType

    @Test
    fun `DrawCmd 的字段类型只允许整数与字形引用列表`() {
        val fields = DrawCmd::class.java.declaredFields
        assertTrue(fields.isNotEmpty())
        // 白名单逐字登记，不做「只查名字」的放宽：M10-GFX 给 [DrawCmd] 加的 `graphic` 字段
        // 类型是 [InlineGraphic]（sealed，三个分支只带尺寸与**资源名**，不带像素、不带文本，
        // 见其类文档「为什么不带像素」）。它是 ADR-001「绘制阶段不再测量」允许的唯一
        // 非整数、非列表负载：原版 `ConsoleImagePart` 也是显示行上的一个节点，
        // 绘制时只读自己在构造期算好的 destRect（spec: `ConsoleImagePart.cs:104-115`、
        // `ConsoleDisplayLine.cs:118-125` 逐节点画）。
        // 除它以外任何新类型都要先改 ADR 再来动这张表。
        val allowed = setOf<Class<*>>(
            primitiveInt!!,
            RunKind::class.java,
            List::class.java,
            InlineGraphic::class.java,
        )
        val offenders = fields.filter { it.type !in allowed }.map { "${it.name}:${it.type.name}" }
        assertTrue(offenders.isEmpty(), "DrawCmd 出现越界字段类型：$offenders")
    }

    @Test
    fun `DrawCmd 不携带任何文本载体所以无法再测量`() {
        val textCarriers = setOf<Class<*>>(
            String::class.java,
            CharSequence::class.java,
            Char::class.javaObjectType,
            CharArray::class.java,
            com.eratw.port.render.TextRun::class.java,
            com.eratw.port.render.DisplayRow::class.java,
        )
        val offenders = DrawCmd::class.java.declaredFields
            .filter { it.type in textCarriers || it.type.isArray }
            .map { it.name }
        assertTrue(offenders.isEmpty(), "绘制命令里不该有文本（原版绘制阶段只遍历既有节点，spec: ConsoleDisplayLine.cs:118-125）：$offenders")
    }

    @Test
    fun `DrawCmd 除数据类生成成员外没有其它方法`() {
        // 过滤掉编译器合成物，只判「源码里写得出来的」成员。
        val declared = DrawCmd::class.java.declaredMethods.filterNot { it.isSynthetic }
        val generated = setOf("equals", "hashCode", "toString", "copy")
        val extras = declared
            .map { it.name }
            .filterNot { it in generated || it.startsWith("get") || it.startsWith("component") || it.contains("\$default") }
        assertTrue(extras.isEmpty(), "DrawCmd 上不允许挂行为（尤其不得有测量入口）：$extras")
        // 名字层面再兜一次：任何 measure/calc/fold/layout/width 语义的方法都算违规。
        val bannedVerb = listOf("measure", "calc", "fold", "layout", "width")
        declared.forEach {
            val lower = it.name.lowercase()
            assertTrue(
                bannedVerb.none { verb -> lower.contains(verb) },
                "DrawCmd 不得含再测量入口：${it.name}"
            )
        }
    }

    @Test
    fun `DisplayList 不加锁不起线程也没有静态可变状态`() {
        val banned = sequenceOf(
            java.util.concurrent.locks.Lock::class.java,
            Thread::class.java,
            java.util.concurrent.Executor::class.java,
            java.util.concurrent.ExecutorService::class.java,
            java.util.concurrent.atomic.AtomicInteger::class.java,
        )
        DisplayList::class.java.declaredFields.forEach { f ->
            assertTrue(
                banned.none { it.isAssignableFrom(f.type) },
                "ADR-003：本波禁止在显示列表里加锁或起线程，发现 ${f.name}:${f.type.name}"
            )
            assertTrue(
                !Modifier.isStatic(f.modifiers) || Modifier.isFinal(f.modifiers),
                "禁静态可变全局（AGENTS.md 铁律 4），发现 ${f.name}"
            )
        }
    }

    @Test
    fun `DisplayList 的公开入口签名符合波次契约`() {
        val submit = DisplayList::class.java.methods.single { it.name == "submit" }
        assertEquals(List::class.java, submit.returnType)
        assertEquals(1, submit.parameterCount)
        val render = DisplayList::class.java.methods.single { it.name == "render" && it.parameterCount == 0 }
        assertEquals(List::class.java, render.returnType)
        // 契约形态「只给 clientWidth 也能构造」必须由源码层调用证明：
        // Kotlin 的默认参数不会为每种缺省组合生成一个 JVM 重载。
        assertEquals(Grid.MAX_LOG_DEFAULT, DisplayList(1800).maxRows)
    }
}
