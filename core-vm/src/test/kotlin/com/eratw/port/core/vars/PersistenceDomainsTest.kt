package com.eratw.port.core.vars

import com.eratw.port.core.entity.EntityIdCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [PersistenceDomains] / [CharIdentity] / [ModCsvValueKey] 的判据（M4-1，蓝图 §8 域矩阵）。
 *
 * 断言面：
 * - 域派生优先级复刻 SaveCapture.kt:117-118（!isSavedata 不入域、CHARACTER 先于 GLOBAL）；
 * - Base/Mod 载体分流（.sav 纯净纪律）与写读触发配对（SAVEGAME↔LOADGAME / SAVEGLOBAL↔LOADGLOBAL）；
 * - ENTITY 域**不可由声明到达**（域由声明种类派生，实体走内核，不是 #DIM 的第四种写法）；
 * - 稳定角色身份只有 CSV NO 与 EntityId 两形态（注册下标在结构上不可表达），
 *   NO=0（ADDVOIDCHARA 匿名）与 NULL/非良构 EntityId 构造期拒绝；
 * - CSV 关键字大小写原样保留（ConstantData.cs:118/:120 精确匹配），表名/包名按标识符归一。
 */
class PersistenceDomainsTest {

    private val charaStorages = setOf(
        Storage.C_INT0, Storage.C_STR0, Storage.C_INT1, Storage.C_STR1, Storage.C_INT2,
    )

    private fun def(
        storage: Storage,
        savedata: Boolean,
        global: Boolean,
        isCharacterData: Boolean = storage in charaStorages,
    ): VarDef = VarDef(
        name = "PROBE",
        code = 0,
        type = VarType.INT,
        storage = storage,
        index = 0,
        dims = intArrayOf(4),
        isConst = false,
        isSavedata = savedata,
        isGlobal = global,
        isCharacterData = isCharacterData,
    )

    // ------------------------------------------------------------------
    // 域派生
    // ------------------------------------------------------------------

    @Test
    fun `不可存档的声明不入任何域`() {
        assertNull(PersistenceDomains.domainOf(def(Storage.G_INT1, savedata = false, global = false)))
        assertNull(PersistenceDomains.domainOf(def(Storage.G_INT1, savedata = false, global = true)))
        assertNull(PersistenceDomains.routeOf(def(Storage.G_INT1, savedata = false, global = false), isMod = true))
        assertNull(PersistenceDomains.routeOf(def(Storage.G_INT1, savedata = false, global = false), isMod = false))
        // 角色存储 + 不可存档：仍不入域（!isSavedata 是第一道闸）。
        assertNull(PersistenceDomains.domainOf(def(Storage.C_INT1, savedata = false, global = false)))
    }

    @Test
    fun `角色数据优先于GLOBAL判定`() {
        // SaveCapture.kt:117-118：isCharacterData 先跳过，再看 isGlobal != global。
        // 角色变量即使带 GLOBAL 旗标也只进角色块，绝不进普通档/全局档。
        for (storage in charaStorages) {
            assertEquals(
                PersistenceDomain.CHARACTER,
                PersistenceDomains.domainOf(def(storage, savedata = true, global = false)),
                "$storage",
            )
            assertEquals(
                PersistenceDomain.CHARACTER,
                PersistenceDomains.domainOf(def(storage, savedata = true, global = true)),
                "$storage + GLOBAL 旗标",
            )
        }
    }

    @Test
    fun `GLOBAL声明只被全局档抓取`() {
        assertEquals(
            PersistenceDomain.GLOBAL,
            PersistenceDomains.domainOf(def(Storage.G_INT1, savedata = true, global = true)),
        )
        assertEquals(
            PersistenceDomain.SLOT,
            PersistenceDomains.domainOf(def(Storage.G_INT1, savedata = true, global = false)),
        )
        // GLOBALS（字符串全局）同口径：域由旗标派生，不看类型。
        assertEquals(
            PersistenceDomain.GLOBAL,
            PersistenceDomains.domainOf(def(Storage.G_STR1, savedata = true, global = true)),
        )
    }

    @Test
    fun `ENTITY域不可由声明到达`() {
        // 四组旗标 × Base/Mod 全枚举：没有任何 #DIM 写法能派生出 ENTITY。
        // 实体组件/关系走内核载荷（SaveService.captureEntityPayload），不是声明的第四种域选择。
        for (savedata in listOf(true, false)) {
            for (global in listOf(true, false)) {
                for (storage in listOf(Storage.G_INT1, Storage.C_INT1)) {
                    val domain = PersistenceDomains.domainOf(def(storage, savedata, global))
                    assertTrue(domain == null || domain != PersistenceDomain.ENTITY, "$storage/$savedata/$global")
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 载体路由
    // ------------------------------------------------------------------

    @Test
    fun `Base与Mod载体分流且写读触发配对`() {
        val expected = mapOf(
            PersistenceDomain.SLOT to (SaveCarrier.SAV_SLOT to SaveCarrier.RT2_CHUNK),
            PersistenceDomain.GLOBAL to (SaveCarrier.GLOBAL_SAV to SaveCarrier.GLOBAL_RT2),
            PersistenceDomain.CHARACTER to (SaveCarrier.SAV_CHARA_BLOCK to SaveCarrier.RT2_CHARA),
            PersistenceDomain.ENTITY to (SaveCarrier.RT2_ENTITY to SaveCarrier.RT2_ENTITY),
        )
        for (domain in PersistenceDomain.entries) {
            val (baseCarrier, modCarrier) = expected.getValue(domain)
            val base = PersistenceDomains.routeOf(domain, isMod = false)
            val mod = PersistenceDomains.routeOf(domain, isMod = true)
            assertEquals(baseCarrier, base.carrier, "$domain Base")
            assertEquals(modCarrier, mod.carrier, "$domain Mod")
            assertEquals(domain, base.domain)
            assertEquals(domain, mod.domain)
            // GLOBAL 域只由 SAVEGLOBAL 落盘；其余按槽走 SAVEGAME。读触发一一配对。
            val write = if (domain == PersistenceDomain.GLOBAL) SaveOperation.SAVEGLOBAL else SaveOperation.SAVEGAME
            val read = if (domain == PersistenceDomain.GLOBAL) SaveOperation.LOADGLOBAL else SaveOperation.LOADGAME
            assertEquals(write, base.writeTrigger, "$domain Base 写触发")
            assertEquals(read, base.readTrigger, "$domain Base 读触发")
            assertEquals(write, mod.writeTrigger, "$domain Mod 写触发")
            assertEquals(read, mod.readTrigger, "$domain Mod 读触发")
        }
    }

    @Test
    fun `Mod载体恒rt2家族Base载体恒原版`() {
        val rt2Family = setOf(SaveCarrier.RT2_CHUNK, SaveCarrier.RT2_CHARA, SaveCarrier.RT2_ENTITY, SaveCarrier.GLOBAL_RT2)
        val vanilla = setOf(SaveCarrier.SAV_SLOT, SaveCarrier.SAV_CHARA_BLOCK, SaveCarrier.GLOBAL_SAV)
        for (domain in PersistenceDomain.entries) {
            assertTrue(PersistenceDomains.routeOf(domain, isMod = true).carrier in rt2Family, "$domain Mod")
            if (domain == PersistenceDomain.ENTITY) {
                // 实体是 RT2 独有概念，原版没有对应载体 ⇒ Base/Mod 两侧同落 .rt2（§8：域与 isMod 无关）。
                assertEquals(SaveCarrier.RT2_ENTITY, PersistenceDomains.routeOf(domain, isMod = false).carrier)
            } else {
                assertTrue(PersistenceDomains.routeOf(domain, isMod = false).carrier in vanilla, "$domain Base")
            }
        }
    }

    @Test
    fun `便捷入口与两步派生同值`() {
        val cases = listOf(
            def(Storage.G_INT1, savedata = true, global = false) to PersistenceDomain.SLOT,
            def(Storage.G_INT1, savedata = true, global = true) to PersistenceDomain.GLOBAL,
            def(Storage.C_INT1, savedata = true, global = false) to PersistenceDomain.CHARACTER,
        )
        for ((d, domain) in cases) {
            for (isMod in listOf(true, false)) {
                assertEquals(PersistenceDomains.routeOf(domain, isMod), PersistenceDomains.routeOf(d, isMod))
            }
        }
    }

    // ------------------------------------------------------------------
    // 稳定角色身份
    // ------------------------------------------------------------------

    @Test
    fun `CsvNo拒绝匿名角色与负数`() {
        val ex = assertFailsWith<IllegalArgumentException> { CharIdentity.CsvNo(0) }
        assertTrue("ADDVOIDCHARA" in ex.message!!, ex.message)
        assertFailsWith<IllegalArgumentException> { CharIdentity.CsvNo(-1) }
        assertEquals(7, CharIdentity.CsvNo(7).no)
    }

    @Test
    fun `Entity身份拒绝NULL与非良构`() {
        assertFailsWith<IllegalArgumentException> { CharIdentity.Entity(EntityIdCodec.NULL) }
        // 符号位为 1（bit63 占用）= 非良构。
        assertFailsWith<IllegalArgumentException> { CharIdentity.Entity(Long.MIN_VALUE or 5L) }
        // pkg 段为 0 = 非良构（0 是 NULL 保留）。
        assertFailsWith<IllegalArgumentException> { CharIdentity.Entity(5L) }
        val good = EntityIdCodec.encode(2, 3, 7)
        assertEquals(good, CharIdentity.Entity(good).entityId)
    }

    @Test
    fun `身份只有番号与EntityId两形态`() {
        // 穷尽 when **不写 else**：将来若有人加「注册下标」第三形态，本用例编译不过。
        // 这是 §8 P0-4 的结构闸——DELCHARA 平移注册下标而非 NO（VariableStore.kt:2010、
        // CharacterTable.kt:282-286），下标做持久键会在删人后串号。
        fun shape(identity: CharIdentity): String = when (identity) {
            is CharIdentity.CsvNo -> "NO"
            is CharIdentity.Entity -> "EID"
        }
        assertEquals("NO", shape(CharIdentity.CsvNo(1)))
        assertEquals("EID", shape(CharIdentity.Entity(EntityIdCodec.encode(1, 0, 0))))
    }

    // ------------------------------------------------------------------
    // 持久化键
    // ------------------------------------------------------------------

    @Test
    fun `表名包名归一而CSV关键字原样保留`() {
        val key = CsvIdentityKeys.keyOf("  school.mod ", " cflag ", "Love", CharIdentity.CsvNo(7))
        assertEquals("SCHOOL.MOD", key.packageId)
        assertEquals("CFLAG", key.tableId)
        assertEquals("Love", key.keyword, "CSV 关键字是精确匹配（ConstantData.cs:118/:120），不许 uppercase")
        // 三种大小写是三个不同的格子，不是同一个键。
        val lower = CsvIdentityKeys.keyOf("school.mod", "CFLAG", "love", CharIdentity.CsvNo(7))
        val upper = CsvIdentityKeys.keyOf("school.mod", "CFLAG", "LOVE", CharIdentity.CsvNo(7))
        assertNotEquals(key.toString(), lower.toString())
        assertNotEquals(key.toString(), upper.toString())
        assertNotEquals(lower.toString(), upper.toString())
        // 标识符归一走 Locale.ROOT，不受宿主默认 Locale 影响。
        assertEquals("TALENT", CsvIdentityKeys.normalizeIdentifier(" talent "))
    }

    @Test
    fun `键的空字段拒绝`() {
        assertFailsWith<IllegalArgumentException> { ModCsvValueKey("", "CFLAG", "Love", CharIdentity.CsvNo(1)) }
        assertFailsWith<IllegalArgumentException> { ModCsvValueKey("p", " ", "Love", CharIdentity.CsvNo(1)) }
        assertFailsWith<IllegalArgumentException> { ModCsvValueKey("p", "CFLAG", "", CharIdentity.CsvNo(1)) }
    }

    @Test
    fun `键文本形按身份形态渲染`() {
        val no = ModCsvValueKey("P", "CFLAG", "Love", CharIdentity.CsvNo(7))
        assertEquals("P/CFLAG[Love]@NO=7", no.toString())
        val id = EntityIdCodec.encode(2, 0, 7)
        val eid = ModCsvValueKey("P", "CFLAG", "Love", CharIdentity.Entity(id))
        assertEquals("P/CFLAG[Love]@EID=${id.toString(16)}", eid.toString())
        assertNotEquals(no.toString(), eid.toString())
    }
}