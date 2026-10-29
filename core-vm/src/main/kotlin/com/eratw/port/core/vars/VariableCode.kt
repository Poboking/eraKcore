package com.eratw.port.core.vars

/**
 * 变量 code 与标志位。
 *
 * 逐值照抄 `VariableCode.cs:3-228`（Emuera 1.824.0.0）。低 16 位是"存储槽下标"
 * （`VariableToken.VarCodeInt = Code & __LOWERCASE__`，spec: VariableToken.cs:68），
 * 高位是类型/维度/归属标志。
 *
 * 这些常量在本层是**唯一真源**：`VariableRegistry` 的系统变量表直接由 code 推导
 * 槽位与类型，避免 registry 与 code 表各写一份而漂移。
 */
object VariableCode {
    // ---- 标志位 (VariableCode.cs:5-23) ----
    const val NULL = 0
    const val CAN_FORBID = 65536               // 0x0001_0000
    const val INTEGER = 131072                 // 0x0002_0000
    const val STRING = 262144                  // 0x0004_0000
    const val ARRAY_1D = 524288                // 0x0008_0000
    const val CHARACTER_DATA = 1048576         // 0x0010_0000
    const val UNCHANGEABLE = 4194304           // 0x0040_0000
    const val CALC = 8388608                   // 0x0080_0000
    const val EXTENDED = 16777216              // 0x0100_0000
    const val LOCAL_FLAG = 33554432            // 0x0200_0000
    const val GLOBAL = 67108864                // 0x0400_0000
    const val ARRAY_2D = 134217728             // 0x0800_0000
    const val SAVE_EXTENDED = 268435456        // 0x1000_0000
    const val ARRAY_3D = 536870912             // 0x2000_0000
    const val CONSTANT = 1073741824            // 0x4000_0000
    const val UPPERCASE = 2147418112           // 0x7FFF_0000
    const val LOWERCASE = 65535                // 0x0000_FFFF

    // ---- 1 维整数数组：下标 0..64 (VariableCode.cs:24-90) ----
    const val DAY = 720896
    const val MONEY = 720897
    const val ITEM = 720898
    const val FLAG = 720899
    const val TFLAG = 720900
    const val UP = 720901
    const val PALAMLV = 655366
    const val EXPLV = 655367
    const val EJAC = 720904
    const val DOWN = 720905
    const val RESULT = 655370
    const val COUNT = 720907
    const val TARGET = 655372
    const val ASSI = 720909
    const val MASTER = 720910
    const val NOITEM = 720911
    const val LOSEBASE = 720912
    const val SELECTCOM = 655377
    const val ASSIPLAY = 720914
    const val PREVCOM = 720915
    const val TIME = 720918
    const val ITEMSALES = 720919
    const val PLAYER = 720920
    const val NEXTCOM = 720921
    const val PBAND = 720922
    const val BOUGHT = 720923
    const val A = 720926
    const val B = 720927
    const val C = 720928
    const val D = 720929
    const val E = 720930
    const val F = 720931
    const val G = 720932
    const val H = 720933
    const val I = 720934
    const val J = 720935
    const val K = 720936
    const val L = 720937
    const val M = 720938
    const val N = 720939
    const val O = 720940
    const val P = 720941
    const val Q = 720942
    const val R = 720943
    const val S = 720944
    const val T = 720945
    const val U = 720946
    const val V = 720947
    const val W = 720948
    const val X = 720949
    const val Y = 720950
    const val Z = 720951
    const val ITEMPRICE = 21692476
    const val LOCAL_VAR = 51052605
    const val ARG = 51052606
    const val GLOBAL_VAR = 84607039
    const val RANDDATA = 285868096

    // ---- 1 维字符串数组：下标 0..6 (VariableCode.cs:91-100) ----
    const val SAVESTR = 851968
    const val STR = 851969
    const val RESULTS = 786434
    const val LOCALS = 51183619
    const val ARGS = 51183620
    const val GLOBALS_VAR = 84738053
    const val TSTR = 286064646
    const val SAVEDATA_TEXT = 17039360

    // ---- 角色 0 维 (VariableCode.cs:103-106) ----
    const val ISASSI = 1179648
    const val NO_VAR = 1179649

    // ---- 角色 1 维整数 (VariableCode.cs:107-129) ----
    const val BASE = 1769472
    const val MAXBASE = 1769473
    const val ABL = 1769474
    const val TALENT = 1769475
    const val EXP = 1769476
    const val MARK = 1769477
    const val PALAM = 1769478
    const val SOURCE = 1769479
    const val EX = 1769480
    const val CFLAG = 1769481
    const val JUEL = 1769482
    const val RELATION = 1769483
    const val EQUIP = 1769484
    const val TEQUIP = 1769485
    const val STAIN = 1703950
    const val GOTJUEL = 1769487
    const val NOWEX = 1769488
    const val DOWNBASE = 286982161
    const val CUP = 286982162
    const val CDOWN = 286982163
    const val TCVAR = 286982164

    // ---- 角色 0 维字符串 (VariableCode.cs:130-135) ----
    const val NAME_VAR = 1310720
    const val CALLNAME = 1310721
    const val NICKNAME = 286523394
    const val MASTERNAME = 286523395

    // ---- 角色 1 维字符串 / 2 维整数 (VariableCode.cs:136-141) ----
    const val CSTR = 287113216
    const val CDFLAG = 420675584

    // ---- 2 维 / 3 维整数数组 (VariableCode.cs:142-153) ----
    const val DITEMTYPE = 419627008
    const val DA = 419627009
    const val DB = 419627010
    const val DC = 419627011
    const val DD = 419627012
    const val DE = 419627013
    const val TA = 822280192
    const val TB = 822280193

    // ---- 伪变量 / 常量 (VariableCode.cs:154-211) ----
    const val RAND = 13238272
    const val CHARANUM = 12713985
    const val LASTLOAD_TEXT = 29622277
    const val LASTLOAD_VERSION = 29491205
    const val LASTLOAD_NO = 29491206
    const val LINECOUNT = 29491208
    const val ISTIMEOUT = 29491211
    const val INT_MAX_VAR = 29491209
    const val INT_MIN_VAR = 29491210
    const val EMUERA_VERSION = 29622282
    const val WINDOW_TITLE = 25427973
    const val MONEYLABEL = 29622280
    const val DRAWLINESTR = 29622281
    const val FILE_VAR = 29622278
    const val FUNCTION_VAR = 29622279
    const val LINE_VAR = 29491207

    // ---- CSV 常量名字表 (VariableCode.cs:156-185)，低 16 位 = CSV 槽位 ----
    const val ABLNAME = 1078788096
    const val EXPNAME = 1078788097
    const val TALENTNAME = 1078788098
    const val PALAMNAME = 1078788099
    const val TRAINNAME = 1095565316
    const val MARKNAME = 1078788101
    const val ITEMNAME = 1078788102
    const val BASENAME = 1095565319
    const val SOURCENAME = 1095565320
    const val EXNAME = 1095565321
    const val EQUIPNAME = 1095565323
    const val TEQUIPNAME = 1095565324
    const val FLAGNAME = 1095565325
    const val TFLAGNAME = 1095565326
    const val CFLAGNAME = 1095565327
    const val TCVARNAME = 1095565328
    const val CSTRNAME = 1095565329
    const val STAINNAME = 1095565330
    const val CDFLAGNAME1 = 1095565331
    const val CDFLAGNAME2 = 1095565332
    const val STRNAME = 1095565333
    const val TSTRNAME = 1095565334
    const val SAVESTRNAME = 1095565335
    const val GLOBALNAME = 1095565336
    const val GLOBALSNAME = 1095565337
    const val DAYNAME = 1095565338
    const val TIMENAME = 1095565339
    const val MONEYNAME = 1095565340

    // ---- GAMEBASE 常量 (VariableCode.cs:186-206) ----
    const val GAMEBASE_AUTHER = 29622276
    const val GAMEBASE_AUTHOR = 29622272
    const val GAMEBASE_INFO = 29622273
    const val GAMEBASE_YEAR = 29622274
    const val GAMEBASE_TITLE = 29622275
    const val GAMEBASE_URL = 29622283
    const val GAMEBASE_VERSIONNAME = 29622284
    const val GAMEBASE_GAMECODE = 29491200
    const val GAMEBASE_VERSION = 29491201
    const val GAMEBASE_ALLOWVERSION = 29491202
    const val GAMEBASE_DEFAULTCHARA = 29491203
    const val GAMEBASE_NOITEM = 29491204

    // ---- 用户变量 code 家族 (VariableCode.cs:212-227) ----
    const val CVAR = 18481404
    const val CVARS = 18612476
    const val CVAR2D = 152174844
    const val CVARS2D = 152305916
    const val REF = 17432829
    const val REFS = 17563901
    const val REF2D = 151126269
    const val REFS2D = 151257341
    const val REF3D = 553779453
    const val REFS3D = 553910525
    const val VAR = 17432830
    const val VARS = 17563902
    const val VAR2D = 151126270
    const val VARS2D = 151257342
    const val VAR3D = 553779454
    const val VARS3D = 553910526

    /** spec: VariableToken.cs:68 —— 存储槽下标 = code & 0xFFFF。 */
    fun lowIndex(code: Int): Int = code and LOWERCASE

    fun isInteger(code: Int): Boolean = (code and INTEGER) == INTEGER

    fun isString(code: Int): Boolean = (code and STRING) == STRING

    fun isCharacterData(code: Int): Boolean = (code and CHARACTER_DATA) == CHARACTER_DATA

    fun isConst(code: Int): Boolean = (code and UNCHANGEABLE) == UNCHANGEABLE

    fun isGlobal(code: Int): Boolean = code == GLOBAL_VAR || code == GLOBALS_VAR

    /** 维度由 ARRAY_* 标志决定，spec: VariableToken.cs:74-87。 */
    fun dimension(code: Int): Int = when {
        (code and ARRAY_3D) == ARRAY_3D -> 3
        (code and ARRAY_2D) == ARRAY_2D -> 2
        (code and ARRAY_1D) == ARRAY_1D -> 1
        else -> 0
    }

    /**
     * 存档标记，逐分支复刻 `VariableToken` 构造函数，spec: VariableToken.cs:88-154。
     *
     * 注意：`SAVEDATA` 不是"看起来该存"的直觉判断，而是这串位运算的产物。
     * 例如 STR（低 16 位 = 1）不满足 `VarCodeInt < 1`，因此**不入存档**，
     * 真正入经典存档的是 SAVESTR（低 16 位 = 0）。variables.yaml:66 标 STR saved:true
     * 与源码位规则冲突，以源码为准（见交接报告 TODO(verify)）。
     */
    fun isSavedata(code: Int, name: String): Boolean {
        if (code == GLOBAL_VAR || code == GLOBALS_VAR) return true
        if ((code and SAVE_EXTENDED) == SAVE_EXTENDED) return true
        if ((code and (EXTENDED or CALC or UNCHANGEABLE or LOCAL_FLAG)) != 0) return false
        if (name.startsWith("NOTUSE_")) return false
        val vci = lowIndex(code)
        return when (code and 673054720) { // 0x281E_0000 = 3D|2D|CHARADATA|1D|STRING|INTEGER
            ISASSI, NAME_VAR -> vci < 2
            1703936 -> vci < 17          // 0x1A0000: 角色 1 维整数（CHARACTER_DATA|ARRAY_1D|INTEGER）
            1835008 -> vci < 0           // 0x1C0000: 角色 1 维字符串（CHARACTER_DATA|ARRAY_1D|STRING）
            INTEGER -> vci < 0
            STRING -> vci < 0
            655360 -> vci < 60           // 0x0A0000: 全局 1 维整数
            786432 -> vci < 1            // 0x0C0000: 全局 1 维字符串
            else -> false
        }
    }
}
