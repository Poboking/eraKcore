package com.eratw.port.core.patch

import com.eratw.port.core.entity.ComponentFieldSpec
import com.eratw.port.core.entity.ComponentSchema
import com.eratw.port.core.entity.EntityArchetype
import com.eratw.port.core.entity.RelationshipSchema
import com.eratw.port.core.vars.DimSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * M1-1 声明事务钉（era-arch-v61u.md §5）：五域双边冲突、CSV 关键字两阶段动态 ID、
 * 同格值补丁链接序叠加、冻结后不可变。
 *
 * 关键不变量（不是「能跑」而是「两个 Mod 的组合可判定」）：
 * - 两 Mod 抢同一显式下标 ⇒ 整包拒绝，报告带双边 Package；
 * - 各用 `-1` ⇒ 永不撞号，且终表只由链接序决定（与遍历序/哈希序无关）；
 * - 自动分配绝不覆盖 base 表已有命名位，也不占别名指向的槽；
 * - 值补丁同格按链接序末位生效，全部写者留档。
 */
class DeclarationRegistryTest {

    private fun dim(name: String, size: Int = 10): DimSpec =
        DimSpec(name = name, isStr = false, dims = intArrayOf(size), isGlobal = true)

    private fun registry(
        base: Map<String, Map<String, Int>> = emptyMap(),
        capacities: Map<String, Int> = mapOf("CFLAG" to 100, "TALENT" to 50),
        builtins: Set<String> = emptySet(),
        hook: (DeclarationSnapshot) -> Unit = {},
    ): DeclarationRegistry = DeclarationRegistry(
        builtinVariableNames = builtins,
        csvTableCapacities = capacities,
        baseCsvKeywords = base,
        onCommit = hook,
    )

    @Test
    fun explicitReservationsBeatAutoAndAutoSkipsOccupied() {
        // base 的 CFLAG 0/1 已有名字；B 显式要 2；A 的 -1 必须跳过 0/1/2 ⇒ 3。
        val snap = registry(base = mapOf("CFLAG" to mapOf("NAME" to 0, "CALLNAME" to 1)))
            .addPackage(
                ModuleDescriptor(
                    packageId = "mod.a",
                    csvPatches = listOf(CsvKeywordPatch("CFLAG", -1, "A_FLAG")),
                ),
            )
            .addPackage(
                ModuleDescriptor(
                    packageId = "mod.b",
                    csvPatches = listOf(CsvKeywordPatch("CFLAG", 2, "B_FLAG")),
                ),
            )
            .freeze()

        assertEquals(2, snap.csvKeywordIndex("CFLAG", "B_FLAG"))
        assertEquals(3, snap.csvKeywordIndex("cflag", "A_FLAG"), "自动分配必须跳过显式预留位")
        // base 关键字照旧在有效表里（消费侧拿到的是 base ∪ 声明）。
        assertEquals(0, snap.csvKeywordIndex("CFLAG", "NAME"))
        assertEquals(1, snap.csvKeywordIndex("CFLAG", "CALLNAME"))
        // 声明侧只记本事务写入的两条。
        assertEquals(setOf("A_FLAG", "B_FLAG"), snap.declaredCsvKeywords.getValue("CFLAG").keys)
    }

    @Test
    fun autoAllocationDependsOnlyOnLinkOrder() {
        val auto = ModuleDescriptor(
            packageId = "mod.a",
            csvPatches = listOf(CsvKeywordPatch("CFLAG", -1, "A_FLAG")),
        )
        val explicit = ModuleDescriptor(
            packageId = "mod.b",
            csvPatches = listOf(CsvKeywordPatch("CFLAG", 0, "B_FLAG")),
        )
        // 链接序 A→B：B 的显式 0 先被预留，A 拿 1。
        val ab = registry().addPackage(auto).addPackage(explicit).freeze()
        assertEquals(0, ab.csvKeywordIndex("CFLAG", "B_FLAG"))
        assertEquals(1, ab.csvKeywordIndex("CFLAG", "A_FLAG"))
        // 链接序 B→A：显式仍然优先（两阶段），A 还是 1 —— 终表与顺序无关。
        val ba = registry().addPackage(explicit).addPackage(auto).freeze()
        assertEquals(0, ba.csvKeywordIndex("CFLAG", "B_FLAG"))
        assertEquals(1, ba.csvKeywordIndex("CFLAG", "A_FLAG"))
        // 两条自动请求按链接序各取最小空闲位。
        val twoAutos = registry()
            .addPackage(
                ModuleDescriptor(
                    packageId = "mod.x",
                    csvPatches = listOf(CsvKeywordPatch("CFLAG", -1, "X_FLAG")),
                ),
            )
            .addPackage(
                ModuleDescriptor(
                    packageId = "mod.y",
                    csvPatches = listOf(CsvKeywordPatch("CFLAG", -1, "Y_FLAG")),
                ),
            )
            .freeze()
        assertEquals(0, twoAutos.csvKeywordIndex("CFLAG", "X_FLAG"))
        assertEquals(1, twoAutos.csvKeywordIndex("CFLAG", "Y_FLAG"))
    }

    @Test
    fun sameExplicitSlotAcrossPackagesIsRejectedWithBothSides() {
        val e = assertFailsWith<DeclarationConflictException> {
            registry()
                .addPackage(
                    ModuleDescriptor(
                        packageId = "mod.a",
                        csvPatches = listOf(CsvKeywordPatch("CFLAG", 5, "A_FLAG")),
                    ),
                    source = "A.erbc",
                )
                .addPackage(
                    ModuleDescriptor(
                        packageId = "mod.b",
                        csvPatches = listOf(CsvKeywordPatch("CFLAG", 5, "B_FLAG")),
                    ),
                    source = "B.erbc",
                )
                .freeze()
        }
        val c = e.conflicts.single { it.domain == DeclarationDomain.CSV_PATCH && it.key == "CFLAG:B_FLAG" }
        assertEquals("mod.a", c.first.packageId)
        assertEquals("A.erbc", c.first.source)
        assertEquals("mod.b", c.second.packageId)
        assertTrue(c.reason.contains("同表同一下标"), c.reason)
    }

    @Test
    fun keywordConflictsAndUnknownTablesAreFailClosed() {
        // 同表同关键字不同下标 ⇒ 冲突（关键字唯一）。
        val dupKeyword = assertFailsWith<DeclarationConflictException> {
            registry(base = mapOf("CFLAG" to mapOf("NAME" to 0)))
                .addPackage(
                    ModuleDescriptor(
                        packageId = "mod.a",
                        csvPatches = listOf(CsvKeywordPatch("CFLAG", 5, "X_FLAG")),
                    ),
                )
                .addPackage(
                    ModuleDescriptor(
                        packageId = "mod.b",
                        csvPatches = listOf(CsvKeywordPatch("CFLAG", 6, "X_FLAG")),
                    ),
                )
                .freeze()
        }
        assertEquals(1, dupKeyword.conflicts.count { it.key == "CFLAG:X_FLAG" })

        // 大小写不同的关键字是**两个**关键字（原版关键字字典区分大小写）。
        val caseSensitive = registry()
            .addPackage(
                ModuleDescriptor(
                    packageId = "mod.a",
                    csvPatches = listOf(CsvKeywordPatch("CFLAG", 5, "X_FLAG")),
                ),
            )
            .addPackage(
                ModuleDescriptor(
                    packageId = "mod.b",
                    csvPatches = listOf(CsvKeywordPatch("CFLAG", 6, "x_flag")),
                ),
            )
            .freeze()
        assertEquals(5, caseSensitive.csvKeywordIndex("CFLAG", "X_FLAG"))
        assertEquals(6, caseSensitive.csvKeywordIndex("CFLAG", "x_flag"))

        // 未知表 / 下标越容量 / 覆盖 base 命名位 ⇒ 全部拒绝。
        val bad = assertFailsWith<DeclarationConflictException> {
            registry(capacities = mapOf("CFLAG" to 3), base = mapOf("CFLAG" to mapOf("NAME" to 0)))
                .addPackage(
                    ModuleDescriptor(
                        packageId = "mod.a",
                        csvPatches = listOf(
                            CsvKeywordPatch("CFLAG", 3, "TOO_FAR"),
                            CsvKeywordPatch("CFLAG", 0, "OVERWRITE_NAME"),
                            CsvKeywordPatch("NOPE", -1, "UNKNOWN_TABLE"),
                        ),
                    ),
                )
                .freeze()
        }
        assertEquals(3, bad.conflicts.size, bad.conflicts.joinToString { it.key })
    }

    @Test
    fun aliasDoesNotOccupyKeywordButReservesAllocationSlot() {
        val snap = registry()
            .addPackage(
                ModuleDescriptor(
                    packageId = "mod.a",
                    csvPatches = listOf(
                        // 别名指向 7（无 base 命名位），随后 -1 不得落到 7。
                        CsvKeywordPatch("CFLAG", 7, "ALIAS_FLAG", isAlias = true),
                        CsvKeywordPatch("CFLAG", -1, "REAL_FLAG"),
                    ),
                ),
            )
            .freeze()
        assertEquals(7, snap.csvKeywordIndex("CFLAG", "ALIAS_FLAG"))
        assertEquals(0, snap.csvKeywordIndex("CFLAG", "REAL_FLAG"), "别名目标位算占用，自动分配跳过")
        assertTrue(snap.declaredCsvKeywords.getValue("CFLAG").getValue("ALIAS_FLAG").alias)
    }

    @Test
    fun globalDimNamespaceRejectsCrossPackageAndBuiltinShadowing() {
        val e = assertFailsWith<DeclarationConflictException> {
            registry(builtins = setOf("CFLAG"))
                .addPackage(ModuleDescriptor(packageId = "mod.a", globalDims = listOf(dim("MOD_STATE"))))
                .addPackage(ModuleDescriptor(packageId = "mod.b", globalDims = listOf(dim("mod_state"))))
                .addPackage(ModuleDescriptor(packageId = "mod.c", globalDims = listOf(dim("cflag"))))
                .freeze()
        }
        assertEquals(2, e.conflicts.size, e.conflicts.joinToString { it.key })
        assertTrue(e.conflicts.any { it.key == "MOD_STATE" && it.reason.contains("Global×Global") })
        assertTrue(e.conflicts.any { it.key == "CFLAG" && it.reason.contains("Built-in") })

        // 私有 #DIM 是包私有作用域：跨包同名合法，同包同函数内重名拒绝。
        val ok = registry()
            .addPackage(
                ModuleDescriptor(
                    packageId = "mod.a",
                    privateDims = mapOf("FUNC" to listOf(dim("TMP"))),
                ),
            )
            .addPackage(
                ModuleDescriptor(
                    packageId = "mod.b",
                    privateDims = mapOf("FUNC" to listOf(dim("TMP"))),
                ),
            )
            .freeze()
        assertEquals(1, ok.privateDimsOf("mod.a", "func").size)
        assertEquals(1, ok.privateDimsOf("mod.b", "FUNC").size)

        assertFailsWith<DeclarationConflictException> {
            registry()
                .addPackage(
                    ModuleDescriptor(
                        packageId = "mod.a",
                        privateDims = mapOf("FUNC" to listOf(dim("TMP"), dim("tmp"))),
                    ),
                )
                .freeze()
        }
    }

    @Test
    fun componentSchemaAndFieldNamesAreGlobal() {
        fun schema(pkg: String, name: String, vararg fields: String): ComponentSchema =
            ComponentSchema(
                packageId = pkg,
                name = name,
                fields = fields.map { ComponentFieldSpec(it) },
            )

        val e = assertFailsWith<DeclarationConflictException> {
            registry()
                .addPackage(
                    ModuleDescriptor(
                        packageId = "mod.a",
                        componentSchemas = listOf(schema("mod.a", "Education", "mentorId", "note")),
                    ),
                )
                .addPackage(
                    ModuleDescriptor(
                        packageId = "mod.b",
                        componentSchemas = listOf(schema("mod.b", "Health", "MENTORID")),
                    ),
                )
                .addPackage(
                    ModuleDescriptor(
                        packageId = "mod.c",
                        componentSchemas = listOf(schema("mod.c", "education")),
                    ),
                )
                .freeze()
        }
        assertTrue(e.conflicts.any { it.key == "MENTORID" && it.reason.contains("字段名") })
        assertTrue(e.conflicts.any { it.key == "EDUCATION" && it.reason.contains("schema") })

        // schema.packageId 与声明包不符 ⇒ 拒绝（Manifest 自相矛盾）。
        assertFailsWith<DeclarationConflictException> {
            registry()
                .addPackage(
                    ModuleDescriptor(
                        packageId = "mod.a",
                        componentSchemas = listOf(schema("mod.other", "Education")),
                    ),
                )
                .freeze()
        }
    }

    @Test
    fun archetypesMustResolveDeclaredSchemas() {
        val schema = ComponentSchema(packageId = "mod.a", name = "Education")
        val snap = registry()
            .addPackage(
                ModuleDescriptor(
                    packageId = "mod.a",
                    componentSchemas = listOf(schema),
                    entityArchetypes = listOf(EntityArchetype("Student", listOf("education"))),
                ),
            )
            .freeze()
        assertEquals(listOf("education"), snap.archetype("STUDENT")?.components)

        val e = assertFailsWith<DeclarationConflictException> {
            registry()
                .addPackage(
                    ModuleDescriptor(
                        packageId = "mod.a",
                        entityArchetypes = listOf(EntityArchetype("Student", listOf("Missing"))),
                    ),
                )
                .freeze()
        }
        assertTrue(e.conflicts.single().reason.contains("未声明的组件 schema"))

        // 原型名是全局命名空间：跨包同名拒绝。
        assertFailsWith<DeclarationConflictException> {
            registry()
                .addPackage(
                    ModuleDescriptor(
                        packageId = "mod.a",
                        componentSchemas = listOf(schema),
                        entityArchetypes = listOf(EntityArchetype("Student", listOf("Education"))),
                    ),
                )
                .addPackage(
                    ModuleDescriptor(
                        packageId = "mod.b",
                        componentSchemas = listOf(ComponentSchema(packageId = "mod.b", name = "B")),
                        entityArchetypes = listOf(EntityArchetype("student", listOf("B"))),
                    ),
                )
                .freeze()
        }
    }

    @Test
    fun relationshipAutoInverseRequiresDeclaredInverseType() {
        val snap = registry()
            .addPackage(
                ModuleDescriptor(
                    packageId = "mod.a",
                    relationshipSchemas = listOf(
                        RelationshipSchema("PARENT", autoInverse = true, inverseName = "CHILD"),
                        RelationshipSchema("CHILD"),
                    ),
                ),
            )
            .freeze()
        assertTrue(snap.relationship("parent")!!.autoInverse)

        val e = assertFailsWith<DeclarationConflictException> {
            registry()
                .addPackage(
                    ModuleDescriptor(
                        packageId = "mod.a",
                        relationshipSchemas = listOf(
                            RelationshipSchema("PARENT", autoInverse = true, inverseName = "CHILD"),
                        ),
                    ),
                )
                .freeze()
        }
        assertTrue(e.conflicts.single().reason.contains("未声明的关系类型"))

        // 数据类自身的形状闸：无向关系不得要反向边；未启用时不得带 inverseName。
        assertFailsWith<IllegalArgumentException> {
            RelationshipSchema("PAIR", directed = false, autoInverse = true, inverseName = "PAIR_REV")
        }
        assertFailsWith<IllegalArgumentException> {
            RelationshipSchema("PARENT", inverseName = "CHILD")
        }
    }

    @Test
    fun sameCellValuePatchesLastWinsAndKeepAllWriters() {
        fun docs(op: CsvPatchOp, value: String?) = ModuleDescriptor(
            packageId = "unused",
            csvValuePatches = listOf(CsvValuePatch("CFLAG", "10", "MY_FLAG", op, value)),
        )
        val snap = registry()
            .addPackage(ModuleDescriptor(packageId = "mod.a", csvValuePatches = docs(CsvPatchOp.ADD, "5").csvValuePatches))
            .addPackage(ModuleDescriptor(packageId = "mod.b", csvValuePatches = docs(CsvPatchOp.REPLACE, "9").csvValuePatches))
            .addPackage(ModuleDescriptor(packageId = "mod.c", csvValuePatches = docs(CsvPatchOp.REMOVE, null).csvValuePatches))
            .freeze()

        val plan = snap.csvCellPlan("CFLAG", "10", "MY_FLAG")!!
        assertEquals(CsvPatchOp.REMOVE, plan.effectiveOp)
        assertNull(plan.effectiveValue)
        assertEquals(listOf("mod.a", "mod.b", "mod.c"), plan.writers.map { it.packageId })
        assertEquals(listOf(CsvPatchOp.ADD, CsvPatchOp.REPLACE, CsvPatchOp.REMOVE), plan.writers.map { it.op })
        assertNull(snap.csvCellPlan("CFLAG", "10", "OTHER_FLAG"))

        // 配对与空字段在构造期判死（不留到消费侧猜）。
        assertFailsWith<IllegalArgumentException> {
            CsvValuePatch("CFLAG", "10", "X", CsvPatchOp.REMOVE, "still-here")
        }
        assertFailsWith<IllegalArgumentException> {
            CsvValuePatch("CFLAG", "10", "X", CsvPatchOp.REPLACE)
        }
        assertFailsWith<IllegalArgumentException> { CsvKeywordPatch("CFLAG", -2, "X") }

        // 未知表的写入同样 fail-closed（容量未声明 ⇒ 无法定界）。
        assertFailsWith<DeclarationConflictException> {
            registry()
                .addPackage(
                    ModuleDescriptor(
                        packageId = "mod.a",
                        csvValuePatches = listOf(CsvValuePatch("NOPE", "1", "X", CsvPatchOp.ADD, "v")),
                    ),
                )
                .freeze()
        }
    }

    @Test
    fun freezeIsIdempotentAndCommitHookFiresOnce() {
        var commits = 0
        val reg = registry(hook = { commits++ })
            .addPackage(ModuleDescriptor(packageId = "mod.a", globalDims = listOf(dim("MOD_STATE"))))
        val first = reg.freeze()
        val second = reg.freeze()
        assertSame(first, second, "冻结后重复 freeze 返回同一快照（不可变）")
        assertEquals(1, commits, "统一清刷钩子只在成功提交时触发一次")
        assertFailsWith<IllegalStateException> {
            reg.addPackage(ModuleDescriptor(packageId = "mod.b"))
        }

        // 冲突路径：整包拒绝，钩子不触发，快照不产出。
        var conflictCommits = 0
        val bad = registry(hook = { conflictCommits++ })
            .addPackage(ModuleDescriptor(packageId = "mod.a", globalDims = listOf(dim("X"))))
            .addPackage(ModuleDescriptor(packageId = "mod.b", globalDims = listOf(dim("x"))))
        assertFailsWith<DeclarationConflictException> { bad.freeze() }
        assertEquals(0, conflictCommits)

        // 同一 packageId 装两次是装配错误，立即拒绝（不进冲突清单）。
        assertFailsWith<IllegalArgumentException> {
            registry().addPackage(ModuleDescriptor(packageId = "mod.a"))
                .addPackage(ModuleDescriptor(packageId = "mod.a"))
        }

        // 构造期容量校验。
        assertFailsWith<IllegalArgumentException> {
            DeclarationRegistry(csvTableCapacities = mapOf("CFLAG" to -1))
        }
    }

    @Test
    fun namePolicyMatchesShadowingChain() {
        assertEquals("MOD_STATE", NamePolicy.canonical("  mod_state "))
        assertTrue(NamePolicy.shadows(DeclarationScope.FUNCTION_LOCAL, DeclarationScope.PACKAGE_GLOBAL))
        assertTrue(NamePolicy.shadows(DeclarationScope.FILE_PRIVATE, DeclarationScope.BUILT_IN))
        assertTrue(!NamePolicy.shadows(DeclarationScope.PACKAGE_GLOBAL, DeclarationScope.FUNCTION_LOCAL))
        assertTrue(NamePolicy.isForbiddenShadow(DeclarationScope.PACKAGE_GLOBAL, DeclarationScope.PACKAGE_GLOBAL))
        assertTrue(NamePolicy.isForbiddenShadow(DeclarationScope.PACKAGE_GLOBAL, DeclarationScope.BUILT_IN))
        assertTrue(!NamePolicy.isForbiddenShadow(DeclarationScope.FILE_PRIVATE, DeclarationScope.PACKAGE_GLOBAL))
        assertTrue(!NamePolicy.isForbiddenShadow(DeclarationScope.FUNCTION_LOCAL, DeclarationScope.BUILT_IN))
    }
}
