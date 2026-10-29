package com.eratw.port.core.vars

import com.eratw.port.core.vars.VariableCode as VC

/**
 * 系统变量的名字 → code 清单，逐条对照 `VariableData.cs:3014-3187` 的注册顺序。
 *
 * 这是本层唯一的"系统变量总表"，[VariableSizes] 与 [VariableRegistry] 都从这里取，
 * 避免尺寸表与元信息表各写一份。槽位一律由 `code & 0xFFFF` 推导。
 */
object SystemLayout {

    /** 1 维整数数组，按槽位 0..64 排列（52..59 未使用，61/62 是局部变量）。 */
    val int1D: List<Pair<String, Int>> = listOf(
        "DAY" to VC.DAY,
        "MONEY" to VC.MONEY,
        "ITEM" to VC.ITEM,
        "FLAG" to VC.FLAG,
        "TFLAG" to VC.TFLAG,
        "UP" to VC.UP,
        "PALAMLV" to VC.PALAMLV,
        "EXPLV" to VC.EXPLV,
        "EJAC" to VC.EJAC,
        "DOWN" to VC.DOWN,
        "RESULT" to VC.RESULT,
        "COUNT" to VC.COUNT,
        "TARGET" to VC.TARGET,
        "ASSI" to VC.ASSI,
        "MASTER" to VC.MASTER,
        "NOITEM" to VC.NOITEM,
        "LOSEBASE" to VC.LOSEBASE,
        "SELECTCOM" to VC.SELECTCOM,
        "ASSIPLAY" to VC.ASSIPLAY,
        "PREVCOM" to VC.PREVCOM,
        "TIME" to VC.TIME,
        "ITEMSALES" to VC.ITEMSALES,
        "PLAYER" to VC.PLAYER,
        "NEXTCOM" to VC.NEXTCOM,
        "PBAND" to VC.PBAND,
        "BOUGHT" to VC.BOUGHT,
        "A" to VC.A, "B" to VC.B, "C" to VC.C, "D" to VC.D, "E" to VC.E,
        "F" to VC.F, "G" to VC.G, "H" to VC.H, "I" to VC.I, "J" to VC.J,
        "K" to VC.K, "L" to VC.L, "M" to VC.M, "N" to VC.N, "O" to VC.O,
        "P" to VC.P, "Q" to VC.Q, "R" to VC.R, "S" to VC.S, "T" to VC.T,
        "U" to VC.U, "V" to VC.V, "W" to VC.W, "X" to VC.X, "Y" to VC.Y,
        "Z" to VC.Z,
        "GLOBAL" to VC.GLOBAL_VAR,
        "RANDDATA" to VC.RANDDATA,
        "ITEMPRICE" to VC.ITEMPRICE,
    )

    /** 1 维字符串数组，槽位 0..6。 */
    val str1D: List<Pair<String, Int>> = listOf(
        "SAVESTR" to VC.SAVESTR,
        "STR" to VC.STR,
        "RESULTS" to VC.RESULTS,
        "GLOBALS" to VC.GLOBALS_VAR,
        "TSTR" to VC.TSTR,
    )

    /** 0 维字符串（长度 1 的 dataString）。spec: VariableData.cs:3073 */
    val str0D: List<Pair<String, Int>> = listOf(
        "SAVEDATA_TEXT" to VC.SAVEDATA_TEXT,
    )

    val int2D: List<Pair<String, Int>> = listOf(
        "DITEMTYPE" to VC.DITEMTYPE,
        "DA" to VC.DA,
        "DB" to VC.DB,
        "DC" to VC.DC,
        "DD" to VC.DD,
        "DE" to VC.DE,
    )

    val int3D: List<Pair<String, Int>> = listOf(
        "TA" to VC.TA,
        "TB" to VC.TB,
    )

    /** 角色 0 维整数：ISASSI / NO。 */
    val charaInt0D: List<Pair<String, Int>> = listOf(
        "ISASSI" to VC.ISASSI,
        "NO" to VC.NO_VAR,
    )

    /** 角色 0 维字符串：NAME / CALLNAME / NICKNAME / MASTERNAME。 */
    val charaStr0D: List<Pair<String, Int>> = listOf(
        "NAME" to VC.NAME_VAR,
        "CALLNAME" to VC.CALLNAME,
        "NICKNAME" to VC.NICKNAME,
        "MASTERNAME" to VC.MASTERNAME,
    )

    /** 角色 1 维整数：槽位 0..20（21..83 未注册名字）。 */
    val charaInt1D: List<Pair<String, Int>> = listOf(
        "BASE" to VC.BASE,
        "MAXBASE" to VC.MAXBASE,
        "ABL" to VC.ABL,
        "TALENT" to VC.TALENT,
        "EXP" to VC.EXP,
        "MARK" to VC.MARK,
        "PALAM" to VC.PALAM,
        "SOURCE" to VC.SOURCE,
        "EX" to VC.EX,
        "CFLAG" to VC.CFLAG,
        "JUEL" to VC.JUEL,
        "RELATION" to VC.RELATION,
        "EQUIP" to VC.EQUIP,
        "TEQUIP" to VC.TEQUIP,
        "STAIN" to VC.STAIN,
        "GOTJUEL" to VC.GOTJUEL,
        "NOWEX" to VC.NOWEX,
        "DOWNBASE" to VC.DOWNBASE,
        "CUP" to VC.CUP,
        "CDOWN" to VC.CDOWN,
        "TCVAR" to VC.TCVAR,
    )

    val charaStr1D: List<Pair<String, Int>> = listOf("CSTR" to VC.CSTR)

    val charaInt2D: List<Pair<String, Int>> = listOf("CDFLAG" to VC.CDFLAG)

    /** 函数局部变量（按 subID 实例化）。spec: VariableData.cs:3180-3187 */
    val localInt: List<Pair<String, Int>> = listOf("LOCAL" to VC.LOCAL_VAR, "ARG" to VC.ARG)
    val localStr: List<Pair<String, Int>> = listOf("LOCALS" to VC.LOCALS, "ARGS" to VC.ARGS)

    /** 伪变量/常量注册。spec: VariableData.cs:3153-3179 */
    val pseudo: List<Triple<String, Int, PseudoKind>> = listOf(
        Triple("RAND", VC.RAND, PseudoKind.RAND),
        Triple("CHARANUM", VC.CHARANUM, PseudoKind.CHARANUM),
        Triple("LASTLOAD_TEXT", VC.LASTLOAD_TEXT, PseudoKind.LASTLOAD_TEXT),
        Triple("LASTLOAD_VERSION", VC.LASTLOAD_VERSION, PseudoKind.LASTLOAD_VERSION),
        Triple("LASTLOAD_NO", VC.LASTLOAD_NO, PseudoKind.LASTLOAD_NO),
        Triple("LINECOUNT", VC.LINECOUNT, PseudoKind.LINECOUNT),
        Triple("ISTIMEOUT", VC.ISTIMEOUT, PseudoKind.ISTIMEOUT),
        Triple("__INT_MAX__", VC.INT_MAX_VAR, PseudoKind.INT_MAX),
        Triple("__INT_MIN__", VC.INT_MIN_VAR, PseudoKind.INT_MIN),
        Triple("EMUERA_VERSION", VC.EMUERA_VERSION, PseudoKind.EMUERA_VERSION),
        Triple("WINDOW_TITLE", VC.WINDOW_TITLE, PseudoKind.WINDOW_TITLE),
        Triple("MONEYLABEL", VC.MONEYLABEL, PseudoKind.MONEYLABEL),
        Triple("DRAWLINESTR", VC.DRAWLINESTR, PseudoKind.DRAWLINESTR),
        // 非 DebugMode 下这三个返回空串/0；DebugMode 时由宿主注入位置。
        Triple("__FILE__", VC.FILE_VAR, PseudoKind.DEBUG_FILE),
        Triple("__FUNCTION__", VC.FUNCTION_VAR, PseudoKind.DEBUG_FUNCTION),
        Triple("__LINE__", VC.LINE_VAR, PseudoKind.DEBUG_LINE),
    )

    /** CSV 常量名字表：name → code；低 16 位即 CSV 槽位。spec: VariableData.cs:3112-3139 */
    val csvConsts: List<Pair<String, Int>> = listOf(
        "ABLNAME" to VC.ABLNAME,
        "TALENTNAME" to VC.TALENTNAME,
        "EXPNAME" to VC.EXPNAME,
        "MARKNAME" to VC.MARKNAME,
        "PALAMNAME" to VC.PALAMNAME,
        "ITEMNAME" to VC.ITEMNAME,
        "TRAINNAME" to VC.TRAINNAME,
        "BASENAME" to VC.BASENAME,
        "SOURCENAME" to VC.SOURCENAME,
        "EXNAME" to VC.EXNAME,
        "EQUIPNAME" to VC.EQUIPNAME,
        "TEQUIPNAME" to VC.TEQUIPNAME,
        "FLAGNAME" to VC.FLAGNAME,
        "TFLAGNAME" to VC.TFLAGNAME,
        "CFLAGNAME" to VC.CFLAGNAME,
        "TCVARNAME" to VC.TCVARNAME,
        "CSTRNAME" to VC.CSTRNAME,
        "STAINNAME" to VC.STAINNAME,
        "CDFLAGNAME1" to VC.CDFLAGNAME1,
        "CDFLAGNAME2" to VC.CDFLAGNAME2,
        "STRNAME" to VC.STRNAME,
        "TSTRNAME" to VC.TSTRNAME,
        "SAVESTRNAME" to VC.SAVESTRNAME,
        "GLOBALNAME" to VC.GLOBALNAME,
        "GLOBALSNAME" to VC.GLOBALSNAME,
        "DAYNAME" to VC.DAYNAME,
        "TIMENAME" to VC.TIMENAME,
        "MONEYNAME" to VC.MONEYNAME,
    )

    /** GAMEBASE 常量。 */
    val gameBaseStr: List<Pair<String, Int>> = listOf(
        "GAMEBASE_AUTHOR" to VC.GAMEBASE_AUTHOR,
        "GAMEBASE_AUTHER" to VC.GAMEBASE_AUTHER, // 历史拼写，注册同一 token
        "GAMEBASE_INFO" to VC.GAMEBASE_INFO,
        "GAMEBASE_YEAR" to VC.GAMEBASE_YEAR,
        "GAMEBASE_TITLE" to VC.GAMEBASE_TITLE,
        "GAMEBASE_URL" to VC.GAMEBASE_URL,
        "GAMEBASE_VERSIONNAME" to VC.GAMEBASE_VERSIONNAME,
    )

    val gameBaseInt: List<Pair<String, Int>> = listOf(
        "GAMEBASE_GAMECODE" to VC.GAMEBASE_GAMECODE,
        "GAMEBASE_VERSION" to VC.GAMEBASE_VERSION,
        "GAMEBASE_ALLOWVERSION" to VC.GAMEBASE_ALLOWVERSION,
        "GAMEBASE_DEFAULTCHARA" to VC.GAMEBASE_DEFAULTCHARA,
        "GAMEBASE_NOITEM" to VC.GAMEBASE_NOITEM,
    )

    /** CSV 槽位 → 文件名（29 项；槽位 10 = STR.CSV，21 = STRNAME.CSV）。spec: ConstantData.cs:678-706 */
    val csvFiles: Array<String> = arrayOf(
        "ABL.CSV", "EXP.CSV", "TALENT.CSV", "PALAM.CSV", "TRAIN.CSV", "MARK.CSV",
        "ITEM.CSV", "BASE.CSV", "SOURCE.CSV", "EX.CSV", "STR.CSV", "EQUIP.CSV",
        "TEQUIP.CSV", "FLAG.CSV", "TFLAG.CSV", "CFLAG.CSV", "TCVAR.CSV", "CSTR.CSV",
        "STAIN.CSV", "CDFLAG1.CSV", "CDFLAG2.CSV", "STRNAME.CSV", "TSTR.CSV",
        "SAVESTR.CSV", "GLOBAL.CSV", "GLOBALS.CSV", "DAY.CSV", "TIME.CSV", "MONEY.CSV",
    )

    /** 关键字下标使用的 CSV 表。spec: ConstantData.cs:914-1204 */
    data class KeywordSpec(val table: String, val dim: Int, val byDim: Boolean = false)

    val keywords: Map<String, KeywordSpec> = mapOf(
        "ABL" to KeywordSpec("ABL", 1),
        "EXP" to KeywordSpec("EXP", 1),
        "TALENT" to KeywordSpec("TALENT", 1),
        "UP" to KeywordSpec("PALAM", 0),
        "DOWN" to KeywordSpec("PALAM", 0),
        "PALAM" to KeywordSpec("PALAM", 1),
        "JUEL" to KeywordSpec("PALAM", 1),
        "GOTJUEL" to KeywordSpec("PALAM", 1),
        "CUP" to KeywordSpec("PALAM", 1),
        "CDOWN" to KeywordSpec("PALAM", 1),
        "TRAINNAME" to KeywordSpec("TRAIN", 0),
        "MARK" to KeywordSpec("MARK", 1),
        "ITEM" to KeywordSpec("ITEM", 0),
        "ITEMSALES" to KeywordSpec("ITEM", 0),
        "ITEMPRICE" to KeywordSpec("ITEM", 0),
        "LOSEBASE" to KeywordSpec("BASE", 0),
        "BASE" to KeywordSpec("BASE", 1),
        "MAXBASE" to KeywordSpec("BASE", 1),
        "DOWNBASE" to KeywordSpec("BASE", 1),
        "SOURCE" to KeywordSpec("SOURCE", 1),
        "EX" to KeywordSpec("EX", 1),
        "NOWEX" to KeywordSpec("EX", 1),
        "EQUIP" to KeywordSpec("EQUIP", 1),
        "TEQUIP" to KeywordSpec("TEQUIP", 1),
        "FLAG" to KeywordSpec("FLAG", 0),
        "TFLAG" to KeywordSpec("TFLAG", 0),
        "CFLAG" to KeywordSpec("CFLAG", 1),
        "TCVAR" to KeywordSpec("TCVAR", 1),
        "CSTR" to KeywordSpec("CSTR", 1),
        "STAIN" to KeywordSpec("STAIN", 1),
        "CDFLAGNAME1" to KeywordSpec("CDFLAG1", 0),
        "CDFLAGNAME2" to KeywordSpec("CDFLAG2", 0),
        "CDFLAG" to KeywordSpec("CDFLAG1", 1, byDim = true),
        "STR" to KeywordSpec("STRNAME", 0),
        "TSTR" to KeywordSpec("TSTR", 0),
        "SAVESTR" to KeywordSpec("SAVESTR", 0),
        "GLOBAL" to KeywordSpec("GLOBAL", 0),
        "GLOBALS" to KeywordSpec("GLOBALS", 0),
        "RELATION" to KeywordSpec("RELATION", 1),
        "DAY" to KeywordSpec("DAY", 0),
        "TIME" to KeywordSpec("TIME", 0),
        "MONEY" to KeywordSpec("MONEY", 0),
    )

    /** 角色名字关键字表：按 NO / 名字 / 呼称 反查角色位置。spec: ConstantData.cs:914-1204 (NAME 分支) */
    const val RELATION_TABLE = "RELATION"

    fun int1DIndex(name: String): Int =
        int1D.firstOrNull { it.first == name }?.second?.let { VC.lowIndex(it) }
            ?: throw CodeEE("未知的 1 维整数变量 $name")

    fun str1DIndex(name: String): Int =
        str1D.firstOrNull { it.first == name }?.second?.let { VC.lowIndex(it) }
            ?: throw CodeEE("未知的 1 维字符串变量 $name")
}
