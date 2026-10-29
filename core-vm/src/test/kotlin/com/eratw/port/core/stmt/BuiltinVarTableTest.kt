package com.eratw.port.core.stmt

import com.eratw.port.core.expr.OperandType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **内建变量表与 C# `varTokenDic` 的全集对齐（deep-stmt-sym 路）**
 *
 * 锁三件事：
 * 1. [BuiltinSymbols.variables] 的键集 == 原版 `varTokenDic` 键集 ∪ 函数局部变量四名
 *    （`varTokenDic.Add`，spec: VariableData.cs:2962-3200；`localvarTokenDic.Add`，
 *    spec: VariableData.cs:3190-3199）。曾经的「按需裁剪」漏掉 DITEMTYPE/DA..DE/TA/TB
 *    与 `__FILE__`/`__FUNCTION__`/`__LINE__`，并误登记原版不存在的 FILE/FUNCTION/LINE。
 * 2. 维度（`VariableToken.Dimension`，spec: VariableToken.cs:76-89）：
 *    2D 系统数组 = 2、3D = 3、角色标量（NAME/NO/ISASSI 等）= 0 且 `isCharacterData`。
 * 3. 只读位（`VariableToken.IsConst`，spec: VariableToken.cs:44 =
 *    `(Code & __UNCHANGEABLE__) == __UNCHANGEABLE__`，常量表见 VariableCode.cs:11）：
 *    PseudoVariableToken / ConstantToken 一族为真；WINDOW_TITLE（可写）为假。
 */
class BuiltinVarTableTest {

    /** 原版 `varTokenDic` 全键 + LOCAL/ARG/LOCALS/ARGS（共 158），逐名前照抄。 */
    private val csharpVarNames: Set<String> = setOf(
        "DAY", "MONEY", "ITEM", "FLAG", "TFLAG", "UP",
        "PALAMLV", "EXPLV", "EJAC", "DOWN", "RESULT", "COUNT",
        "TARGET", "ASSI", "MASTER", "NOITEM", "LOSEBASE", "SELECTCOM",
        "ASSIPLAY", "PREVCOM", "TIME", "ITEMSALES", "PLAYER", "NEXTCOM",
        "PBAND", "BOUGHT", "A", "B", "C", "D",
        "E", "F", "G", "H", "I", "J",
        "K", "L", "M", "N", "O", "P",
        "Q", "R", "S", "T", "U", "V",
        "W", "X", "Y", "Z", "GLOBAL", "RANDDATA",
        "SAVESTR", "TSTR", "STR", "RESULTS", "GLOBALS", "SAVEDATA_TEXT",
        "ISASSI", "NO", "BASE", "MAXBASE", "ABL", "TALENT",
        "EXP", "MARK", "PALAM", "SOURCE", "EX", "CFLAG",
        "JUEL", "RELATION", "EQUIP", "TEQUIP", "STAIN", "GOTJUEL",
        "NOWEX", "DOWNBASE", "CUP", "CDOWN", "TCVAR", "NAME",
        "CALLNAME", "NICKNAME", "MASTERNAME", "CSTR", "CDFLAG", "DITEMTYPE",
        "DA", "DB", "DC", "DD", "DE", "TA",
        "TB", "ITEMPRICE", "ABLNAME", "TALENTNAME", "EXPNAME", "MARKNAME",
        "PALAMNAME", "ITEMNAME", "TRAINNAME", "BASENAME", "SOURCENAME", "EXNAME",
        "EQUIPNAME", "TEQUIPNAME", "FLAGNAME", "TFLAGNAME", "CFLAGNAME", "TCVARNAME",
        "CSTRNAME", "STAINNAME", "CDFLAGNAME1", "CDFLAGNAME2", "STRNAME", "TSTRNAME",
        "SAVESTRNAME", "GLOBALNAME", "GLOBALSNAME", "DAYNAME", "TIMENAME", "MONEYNAME",
        "GAMEBASE_AUTHER", "GAMEBASE_AUTHOR", "GAMEBASE_INFO", "GAMEBASE_YEAR",
        "GAMEBASE_TITLE", "GAMEBASE_URL", "GAMEBASE_VERSIONNAME", "GAMEBASE_GAMECODE",
        "GAMEBASE_VERSION", "GAMEBASE_ALLOWVERSION", "GAMEBASE_DEFAULTCHARA", "GAMEBASE_NOITEM",
        "RAND", "CHARANUM", "LASTLOAD_TEXT", "LASTLOAD_VERSION", "LASTLOAD_NO", "LINECOUNT",
        "ISTIMEOUT", "__INT_MAX__", "__INT_MIN__", "EMUERA_VERSION", "WINDOW_TITLE", "MONEYLABEL",
        "DRAWLINESTR", "__FILE__", "__FUNCTION__", "__LINE__", "LOCAL", "ARG",
        "LOCALS", "ARGS",
    )

    @Test
    fun variablesKeySetEqualsCsharpVarTokenDic() {
        // 断言的是 VariableData.cs:2962-3200 逐条 varTokenDic.Add（+ :3190-3199 局部变量四名）。
        assertEquals(csharpVarNames, BuiltinSymbols.variables.keys)
        // 反例：原版 varTokenDic 无 FILE/FUNCTION/LINE（对应物是 __FILE__ 系列，:3168-3179）。
        for (n in listOf("FILE", "FUNCTION", "LINE")) {
            assertNull(BuiltinSymbols.variables[n], "变量表不得再登记原版不存在的 $n")
            assertFalse(BuiltinSymbols.systemVariableNames.contains(n), "$n 不是系统变量名")
        }
    }

    @Test
    fun multiDimArrayDimensions() {
        // VariableData.cs:3103-3110：DITEMTYPE/DA-DE 为 Int2DVariableToken，TA/TB 为 Int3DVariableToken。
        for (n in listOf("DITEMTYPE", "DA", "DB", "DC", "DD", "DE")) {
            assertEquals(2, BuiltinSymbols.variables.getValue(n).dimension, "$n 必须是 2 维")
        }
        for (n in listOf("TA", "TB")) {
            assertEquals(3, BuiltinSymbols.variables.getValue(n).dimension, "$n 必须是 3 维")
        }
        assertEquals(2, BuiltinSymbols.variables.getValue("CDFLAG").dimension)
    }

    @Test
    fun characterScalarVsArray() {
        // CharaIntVariableToken / CharaStrVariableToken 是 0 维标量（VariableData.cs:3074-3100）。
        for (n in listOf("ISASSI", "NO", "NAME", "CALLNAME", "NICKNAME", "MASTERNAME")) {
            val v = BuiltinSymbols.variables.getValue(n)
            assertEquals(0, v.dimension, "$n 是角色标量")
            assertTrue(v.isCharacterData, "$n 带角色数据位")
        }
        // CharaInt1DVariableToken / CharaStr1DVariableToken 是 1 维（VariableData.cs:3076-3101）。
        for (n in listOf("BASE", "MAXBASE", "ABL", "TALENT", "CFLAG", "TCVAR", "CSTR")) {
            val v = BuiltinSymbols.variables.getValue(n)
            assertEquals(1, v.dimension, "$n 是角色 1 维数组")
            assertTrue(v.isCharacterData, "$n 带角色数据位")
        }
    }

    @Test
    fun readonlyFlagFollowsUnchangeableBit() {
        // __UNCHANGEABLE__ 位（VariableCode.cs:11）为真的只读变量：PseudoVariableToken
        // （SetValue 抛 AssignToPseudoVar）与 ConstantToken（:602-634 赋值抛错）一族。
        val readonly = listOf(
            "ITEMPRICE", "ABLNAME", "GAMEBASE_AUTHOR", "GAMEBASE_AUTHER",
            "RAND", "CHARANUM", "LASTLOAD_TEXT", "LASTLOAD_VERSION", "LASTLOAD_NO",
            "LINECOUNT", "ISTIMEOUT", "__INT_MAX__", "__INT_MIN__", "EMUERA_VERSION",
            "MONEYLABEL", "DRAWLINESTR", "__FILE__", "__FUNCTION__", "__LINE__",
        )
        for (n in readonly) {
            assertTrue(BuiltinSymbols.variables.getValue(n).isConst, "$n 必须只读")
        }
        // WINDOW_TITLE 覆盖 SetValue（VariableData.cs:956-959）⇒ 可写 ⇒ 无 __UNCHANGEABLE__ 位。
        assertFalse(BuiltinSymbols.variables.getValue("WINDOW_TITLE").isConst)
        // 普通可写数组/字符串。
        for (n in listOf("DAY", "FLAG", "STR", "GLOBAL", "TARGET")) {
            assertFalse(BuiltinSymbols.variables.getValue(n).isConst, "$n 可写")
        }
    }

    @Test
    fun functionAndMethodTablesAreTheSame271Set() {
        // FunctionMethodCreator.cs:9040-9316（271 条）、FunctionIdentifier.cs:4140-4148 双注册。
        assertEquals(271, BuiltinSymbols.functions.size)
        assertEquals(271, BuiltinSymbols.methods.size)
        assertEquals(BuiltinSymbols.functions.keys, BuiltinSymbols.methods)
        // 同时是语句名的 6 个方法名（FunctionIdentifier.cs:4142 的 `!funcDic.ContainsKey`）。
        assertEquals(
            setOf("ENCODETOUNI", "GETTIME", "POWER", "PRINTCPERLINE", "SAVENOS", "VARSIZE"),
            BuiltinSymbols.statementShadowedMethods,
        )
    }

    @Test
    fun symbolLookupIsCaseInsensitiveByDefault() {
        // 原版 Config.StrComper 默认 OrdinalIgnoreCase（Config.cs:33 / ConfigData.cs:45 t:true）。
        assertEquals(OperandType.INT, BuiltinSymbols.function("rand"))
        assertEquals(3, BuiltinSymbols.variable("ta")!!.dimension)
        assertTrue(BuiltinSymbols.isBuiltinMethod("getnum"))
    }
}