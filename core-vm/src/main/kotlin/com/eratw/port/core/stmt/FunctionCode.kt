package com.eratw.port.core.stmt

/**
 * 语句语义分类 —— 用于全量验证的按类统计。取值来自 specs/isa.yaml 的 kind 字段
 * （169 条实现类各自的 kind，按别名回填到 302 个语句名）。
 */
enum class StmtKind { VARIABLE, DISPLAY, FLOW, INPUT, IO, CALL, DATA, DEBUG, OTHER }

/**
 * 参数构造器种类 —— 逐条对应 ArgumentParser 的 FunctionArgType（spec: FunctionArgType.cs:5-72）。
 *
 * 与 isa.yaml 的 arg_form 字段一一对应；额外三项为语句层内部使用的伪类型：
 * - [INT_ASIGN] / [STR_ASIGN]：VARI / VARS（原版 ArgBuilder 为 null，参数在 LogicalLineParser.cs:412-511 手工解析）
 * - [PRINT]：PRINT 家族（原版按语句名后缀在 PRINT_Instruction 构造期内决定真实 argType，spec: FunctionIdentifier.cs:85-171）
 * - [EXPRESSIONS]：GetNormalArgumentBuilder("I", 0) —— 类型数组 [long]，minArg 0（INPUTMOUSEKEY）
 * - [METHOD]：用户函数方法调用（methodInstruction，spec: FunctionIdentifier.cs:3826）
 */
enum class ArgType {
    __NULL__,
    METHOD,
    VOID,
    INT_EXPRESSION,
    INT_EXPRESSION_NULLABLE,
    STR_EXPRESSION,
    STR_EXPRESSION_NULLABLE,
    STR,
    KEYWORD,
    STR_NULLABLE,
    FORM_STR,
    FORM_STR_NULLABLE,
    SP_PRINTV,
    SP_TIMES,
    SP_BAR,
    SP_SET,
    SP_SETS,
    SP_SWAP,
    SP_VAR,
    SP_SAVEDATA,
    SP_INPUT,
    SP_INPUTS,
    SP_ONEINPUT,
    SP_ONEINPUTS,
    SP_TINPUT,
    SP_TINPUTS,
    SP_SORTCHARA,
    SP_CALL,
    SP_CALLF,
    SP_CALLFORM,
    SP_CALLFORMF,
    SP_CALLCSHARP,
    SP_FOR_NEXT,
    SP_POWER,
    SP_SWAPVAR,
    EXPRESSION,
    EXPRESSION_NULLABLE,
    CASE,
    VAR_INT,
    SP_GETINT,
    VAR_STR,
    BIT_ARG,
    SP_VAR_SET,
    SP_BUTTON,
    SP_SET_ARRAY,
    SP_SETS_ARRAY,
    SP_COLOR,
    SP_SPLIT,
    SP_CVAR_SET,
    SP_CONTROL_ARRAY,
    SP_SHIFT_ARRAY,
    SP_SORTARRAY,
    INT_ANY,
    FORM_STR_ANY,
    SP_COPYCHARA,
    SP_COPY_ARRAY,
    SP_SAVEVAR,
    SP_SAVECHARA,
    SP_REF,
    SP_REFBYNAME,
    SP_HTMLSPLIT,
    STR_DOUBLE,
    SP_PRINT_IMG,
    SP_PRINT_RECT,
    SP_PRINT_SPACE,
    SP_DT_COLUMN_OPTIONS,
    SP_HTML_PRINT,
    /** 语句层伪类型：VARI / VARS（手写参数） */
    INT_ASIGN,
    STR_ASIGN,
    /** 语句层伪类型：PRINT 家族（后缀决定真实参数形态） */
    PRINT,
    /** 语句层伪类型：GetNormalArgumentBuilder("I", 0) */
    EXPRESSIONS,
}

/**
 * 函数码 —— 与原版 FunctionIdentifier 注册表 1:1 对应（302 条，
 * spec: FunctionIdentifier.cs:3822-4140 static ctor；SET/METHOD 两条伪码见下）。
 *
 * [argType] 为注册时绑定的参数构造器（spec: FunctionArgType.cs）；
 * [kind] 取自 isa.yaml，仅用于统计。
 *
 * [flag] 是原版 `AInstruction.flag` 的**合成终值**，口径 =
 * `FunctionIdentifier.cs:4200-4204`（`flag = instruction.Flag | additionalFlag`；
 * 只给 ArgumentBuilder 的重载 `:4210-4218` 直接用注册行的实参 flag，:3801-3805）。
 * 位定义见 `FunctionIdentifier.cs:3721-3753`，读取接口见 `:4230-4308`：
 *
 * | 位 | 常量（行号） | 读取接口 |
 * |---|---|---|
 * | 1 | FLOW_CONTROL :3721 | IsFlowContorol :4230-4233 |
 * | 2 | EXTENDED :3723 | IsExtended :4235-4238 |
 * | 4 | METHOD_SAFE :3725 | IsMethodSafe :4270-4273 |
 * | 8 | DEBUG_FUNC :3727 | IsDebug :4295-4298 |
 * | 16 | PARTIAL :3729 | IsPartial :4265-4268 |
 * | 32 | FORCE_SETARG :3731 | IsForceSetArg :4290-4293 |
 * | 64 | IS_JUMP :3733 | IsJump :4305-4308 |
 * | 128 | IS_TRY :3735 | IsTry :4300-4303 |
 * | 256 | PRINT_NEWLINE :3739 | IsNewLine :4250-4253 |
 * | 512 | PRINT_WAITINPUT :3741 | IsWaitInput :4255-4258 |
 * | 1024 | PRINT_SINGLE :3743 | IsPrintSingle :4260-4263 |
 * | 2048 | ISPRINTDFUNC :3745 | IsPrintDFunction :4240-4243 |
 * | 4096 | ISPRINTKFUNC :3747 | IsPrintKFunction :4245-4248 |
 * | 8192 | IS_PRINT :3749 | IsPrint :4275-4278 |
 * | 16384 | IS_INPUT :3751 | IsInput :4280-4283 |
 * | 32768 | IS_TRYC :3737 | **无读取接口**（声明了常量但没有对应方法，见下条） |
 * | 65536 | IS_PRINTDATA :3753 | IsPrintData :4285-4288 |
 *
 * 两条**必须照抄的原版事实**（ADR-005）：
 * - `IS_TRYC`（32768）由 `CALL_Instruction`/`GOTO_Instruction` 的 `isTryCatch` 分支置上
 *   （`FunctionIdentifier.cs:3514-3516` / `:3649-3651`，值 32784 = IS_TRYC|PARTIAL），
 *   但 :3721-3753 那批常量里**没有**它的读取接口，:4230-4308 的 16 个 `Is*` 方法也查不到该位
 *   —— 即 TRYC 的「函数体运行期出错转 CATCH」在 1.824 只是装载期语义
 *   （`ErbLoader.cs:1339-1349` 的 CATCH 配对 + `:1347` / `:1357` 写 `JumpToEndCatch`），
 *   flag 位本身无人读。移植侧保留位值，不实现「运行错误自动进 CATCH」。
 * - PRINT 家族与 PRINTDATA 家族的 flag 是**按语句名后缀**在构造器里单遍解析出来的
 *   （`FunctionIdentifier.cs:85-171` / `:233-268`），所以本表 77 + 9 个别名的 flag 互不相同；
 *   PRINT 那 77 名的移植侧真值是 [PrintSpec.flag]（由 [PrintSpec.parse] 复现同一遍解析），
 *   本表对它们只是登记，运行期不许直接读 `FunctionCode.flag`
 *   （对拍口径与逐名依据行见 specs/isa.yaml 的 flag_by_alias / flag_source_by_alias 两块，
 *   由 tools/spec_crosscheck.py 从 C# 反推生成）。
 *
 * 本列的取值口径：specs/isa.yaml 的 `flag` / `flag_by_alias` 字段（唯一权威），
 * 逐名对拍 `FunctionIdentifier.cs` 的注册表（:3830-4134 + :4135-4142 的方法名合并）。
 * **flag 为 0 的只有 6 名**：QUIT / QUIT_AND_RESTART / FORCE_QUIT / FORCE_QUIT_AND_RESTART
 * （走 `addFunction(FunctionCode, ArgumentBuilder)` 重载，flag 实参缺省 0，:3796-3799 + :4108-4110）
 * 与 VARI / VARS（参数在 `LogicalLineParser.cs:412-511` 手工解析，注册用指令实现类
 * 但类体内没有任何 flag 赋值，故保持 `AInstruction` 默认 0，:33-71 + AInstruction.cs:7 + :4125-4126）。
 *
 * SET 为赋值伪语句（setFunc，spec: FunctionIdentifier.cs:3829 + LogicalLineParser.cs:548-556）；
 * METHOD 为「行首为用户定义函数方法名」的语句（methodInstruction，spec: FunctionIdentifier.cs:3826, 4140-4148）。
 * 两条伪码不在 302 条注册表内：SET 的 flag 取 `SET_Instruction` 的 4（METHOD_SAFE，
 * `FunctionIdentifier.cs:610-611` + `:3829` 的 `new FunctionIdentifier(name, code, inst)`
 * 重载 additionalFlag 缺省 0，:4195-4198）；METHOD 的 flag 取 `METHOD_Instruction` 的 6
 * （METHOD_SAFE|EXTENDED，`FunctionIdentifier.cs:588-589`），原版方法名进表时用的正是
 * `new FunctionIdentifier(key, method.Value, methodInstruction)`（:4140，ctor :4220-4228
 * `flag = instruction.Flag`，不叠加）。__NULL__ 是移植侧「非指令行」占位，原版无对应位。
 */
enum class FunctionCode(val argType: ArgType, val flag: Int, val kind: StmtKind) {
    /** 非指令行（$标签 / @函数标签 / #预处理行） */
    __NULL__(ArgType.__NULL__, 0, StmtKind.OTHER),
    SET(ArgType.SP_SET, 4, StmtKind.VARIABLE),
    METHOD(ArgType.METHOD, 6, StmtKind.CALL),
    ADDCHARA(ArgType.INT_ANY, 4, StmtKind.VARIABLE),
    ADDCOPYCHARA(ArgType.INT_ANY, 4, StmtKind.VARIABLE),
    ADDDEFCHARA(ArgType.VOID, 6, StmtKind.VARIABLE),
    ADDSPCHARA(ArgType.INT_ANY, 4, StmtKind.VARIABLE),
    ADDVOIDCHARA(ArgType.VOID, 6, StmtKind.VARIABLE),
    ALIGNMENT(ArgType.STR, 6, StmtKind.DISPLAY),
    ARRAYCOPY(ArgType.SP_COPY_ARRAY, 6, StmtKind.VARIABLE),
    ARRAYREMOVE(ArgType.SP_CONTROL_ARRAY, 6, StmtKind.VARIABLE),
    ARRAYSHIFT(ArgType.SP_SHIFT_ARRAY, 6, StmtKind.VARIABLE),
    ARRAYSORT(ArgType.SP_SORTARRAY, 6, StmtKind.VARIABLE),
    ASSERT(ArgType.INT_EXPRESSION, 14, StmtKind.OTHER),
    AWAIT(ArgType.EXPRESSION_NULLABLE, 2, StmtKind.DISPLAY),
    BAR(ArgType.SP_BAR, 8198, StmtKind.DISPLAY),
    BARL(ArgType.SP_BAR, 8198, StmtKind.DISPLAY),
    BEGIN(ArgType.STR, 1, StmtKind.FLOW),
    BINPUT(ArgType.SP_INPUT, 24576, StmtKind.INPUT),
    BINPUTS(ArgType.SP_INPUTS, 24576, StmtKind.INPUT),
    BREAK(ArgType.VOID, 5, StmtKind.FLOW),
    CALL(ArgType.SP_CALL, 33, StmtKind.FLOW),
    CALLEVENT(ArgType.STR, 3, StmtKind.FLOW),
    CALLF(ArgType.SP_CALLF, 38, StmtKind.CALL),
    CALLFORM(ArgType.SP_CALLFORM, 35, StmtKind.FLOW),
    CALLFORMF(ArgType.SP_CALLFORMF, 38, StmtKind.CALL),
    CALLSHARP(ArgType.SP_CALLCSHARP, 38, StmtKind.CALL),
    CALLTRAIN(ArgType.INT_EXPRESSION, 3, StmtKind.OTHER),
    CASE(ArgType.CASE, 55, StmtKind.FLOW),
    CASEELSE(ArgType.VOID, 55, StmtKind.FLOW),
    CATCH(ArgType.VOID, 23, StmtKind.FLOW),
    CLEARBGIMAGE(ArgType.VOID, 6, StmtKind.DISPLAY),
    CLEARBIT(ArgType.BIT_ARG, 6, StmtKind.VARIABLE),
    CLEARLINE(ArgType.INT_EXPRESSION, 8198, StmtKind.DISPLAY),
    CLEARTEXTBOX(ArgType.VOID, 6, StmtKind.DISPLAY),
    CONTINUE(ArgType.VOID, 5, StmtKind.FLOW),
    COPYCHARA(ArgType.SP_SWAP, 6, StmtKind.VARIABLE),
    CUPCHECK(ArgType.INT_EXPRESSION, 6, StmtKind.VARIABLE),
    CUSTOMDRAWLINE(ArgType.STR, 6, StmtKind.DISPLAY),
    CVARSET(ArgType.SP_CVAR_SET, 6, StmtKind.VARIABLE),
    DATA(ArgType.STR_NULLABLE, 22, StmtKind.DATA),
    DATAFORM(ArgType.FORM_STR_NULLABLE, 22, StmtKind.DATA),
    DATALIST(ArgType.VOID, 22, StmtKind.DATA),
    DEBUGCLEAR(ArgType.VOID, 14, StmtKind.DEBUG),
    DEBUGPRINT(ArgType.STR_NULLABLE, 14, StmtKind.DEBUG),
    DEBUGPRINTFORM(ArgType.FORM_STR_NULLABLE, 14, StmtKind.DEBUG),
    DEBUGPRINTFORML(ArgType.FORM_STR_NULLABLE, 270, StmtKind.DEBUG),
    DEBUGPRINTL(ArgType.STR_NULLABLE, 270, StmtKind.DEBUG),
    DELALLCHARA(ArgType.VOID, 6, StmtKind.VARIABLE),
    DELCHARA(ArgType.INT_ANY, 4, StmtKind.VARIABLE),
    DELDATA(ArgType.INT_EXPRESSION, 6, StmtKind.IO),
    DO(ArgType.VOID, 55, StmtKind.FLOW),
    DOTRAIN(ArgType.INT_EXPRESSION, 3, StmtKind.OTHER),
    DRAWLINE(ArgType.VOID, 4, StmtKind.DISPLAY),
    DRAWLINEFORM(ArgType.FORM_STR, 6, StmtKind.DISPLAY),
    DT_COLUMN_OPTIONS(ArgType.SP_DT_COLUMN_OPTIONS, 6, StmtKind.OTHER),
    DUMPRAND(ArgType.VOID, 6, StmtKind.OTHER),
    ELSE(ArgType.VOID, 53, StmtKind.FLOW),
    ELSEIF(ArgType.INT_EXPRESSION, 53, StmtKind.FLOW),
    ENCODETOUNI(ArgType.FORM_STR_NULLABLE, 6, StmtKind.VARIABLE),
    ENDCATCH(ArgType.VOID, 55, StmtKind.FLOW),
    ENDDATA(ArgType.VOID, 22, StmtKind.DATA),
    ENDFUNC(ArgType.VOID, 51, StmtKind.FLOW),
    ENDIF(ArgType.VOID, 53, StmtKind.FLOW),
    ENDLIST(ArgType.VOID, 22, StmtKind.DATA),
    ENDNOSKIP(ArgType.VOID, 22, StmtKind.OTHER),
    ENDSELECT(ArgType.VOID, 55, StmtKind.FLOW),
    FONTBOLD(ArgType.VOID, 6, StmtKind.DISPLAY),
    FONTITALIC(ArgType.VOID, 6, StmtKind.DISPLAY),
    FONTREGULAR(ArgType.VOID, 6, StmtKind.DISPLAY),
    FONTSTYLE(ArgType.INT_EXPRESSION_NULLABLE, 6, StmtKind.DISPLAY),
    FOR(ArgType.SP_FOR_NEXT, 23, StmtKind.FLOW),
    FORCEKANA(ArgType.INT_EXPRESSION, 6, StmtKind.OTHER),
    FORCEWAIT(ArgType.VOID, 8192, StmtKind.INPUT),
    FORCE_BEGIN(ArgType.STR, 1, StmtKind.FLOW),
    FORCE_QUIT(ArgType.VOID, 0, StmtKind.OTHER),
    FORCE_QUIT_AND_RESTART(ArgType.VOID, 0, StmtKind.OTHER),
    FUNC(ArgType.SP_CALLFORM, 51, StmtKind.CALL),
    GETTIME(ArgType.VOID, 6, StmtKind.OTHER),
    GOTO(ArgType.SP_CALL, 37, StmtKind.FLOW),
    GOTOFORM(ArgType.SP_CALLFORM, 39, StmtKind.FLOW),
    HTML_PRINT(ArgType.SP_HTML_PRINT, 6, StmtKind.DISPLAY),
    HTML_PRINT_ISLAND(ArgType.SP_HTML_PRINT, 6, StmtKind.DISPLAY),
    HTML_PRINT_ISLAND_CLEAR(ArgType.VOID, 6, StmtKind.DISPLAY),
    HTML_TAGSPLIT(ArgType.SP_HTMLSPLIT, 6, StmtKind.DISPLAY),
    IF(ArgType.INT_EXPRESSION, 53, StmtKind.FLOW),
    INITRAND(ArgType.VOID, 6, StmtKind.OTHER),
    INPUT(ArgType.SP_INPUT, 24576, StmtKind.INPUT),
    INPUTANY(ArgType.VOID, 2, StmtKind.INPUT),
    INPUTMOUSEKEY(ArgType.EXPRESSIONS, 2, StmtKind.INPUT),
    INPUTS(ArgType.SP_INPUTS, 24576, StmtKind.INPUT),
    INVERTBIT(ArgType.BIT_ARG, 6, StmtKind.VARIABLE),
    JUMP(ArgType.SP_CALL, 97, StmtKind.FLOW),
    JUMPFORM(ArgType.SP_CALLFORM, 99, StmtKind.FLOW),
    LOADCHARA(ArgType.STR_EXPRESSION, 6, StmtKind.IO),
    LOADDATA(ArgType.INT_EXPRESSION, 3, StmtKind.IO),
    LOADGAME(ArgType.VOID, 1, StmtKind.IO),
    LOADGLOBAL(ArgType.VOID, 6, StmtKind.IO),
    LOADVAR(ArgType.STR_EXPRESSION, 6, StmtKind.IO),
    LOOP(ArgType.INT_EXPRESSION, 55, StmtKind.FLOW),
    NEXT(ArgType.VOID, 23, StmtKind.FLOW),
    NOSKIP(ArgType.VOID, 22, StmtKind.OTHER),
    ONEBINPUT(ArgType.SP_INPUT, 24576, StmtKind.INPUT),
    ONEBINPUTS(ArgType.SP_INPUTS, 24576, StmtKind.INPUT),
    ONEINPUT(ArgType.SP_INPUT, 24578, StmtKind.INPUT),
    ONEINPUTS(ArgType.SP_INPUTS, 24578, StmtKind.INPUT),
    PICKUPCHARA(ArgType.INT_ANY, 6, StmtKind.VARIABLE),
    PLAYBGM(ArgType.STR_EXPRESSION, 6, StmtKind.OTHER),
    PLAYSOUND(ArgType.SP_HTML_PRINT, 6, StmtKind.OTHER),
    POWER(ArgType.SP_POWER, 6, StmtKind.VARIABLE),
    PRINT(ArgType.PRINT, 8196, StmtKind.DISPLAY),
    PRINTBUTTON(ArgType.SP_BUTTON, 6, StmtKind.DISPLAY),
    PRINTBUTTONC(ArgType.SP_BUTTON, 6, StmtKind.DISPLAY),
    PRINTBUTTONLC(ArgType.SP_BUTTON, 6, StmtKind.DISPLAY),
    PRINTC(ArgType.PRINT, 8196, StmtKind.DISPLAY),
    PRINTCD(ArgType.PRINT, 10246, StmtKind.DISPLAY),
    PRINTCK(ArgType.PRINT, 12294, StmtKind.DISPLAY),
    PRINTCPERLINE(ArgType.SP_GETINT, 6, StmtKind.VARIABLE),
    PRINTD(ArgType.PRINT, 10246, StmtKind.DISPLAY),
    PRINTDATA(ArgType.VAR_INT, 73750, StmtKind.DISPLAY),
    PRINTDATAD(ArgType.VAR_INT, 75798, StmtKind.DISPLAY),
    PRINTDATADL(ArgType.VAR_INT, 76054, StmtKind.DISPLAY),
    PRINTDATADW(ArgType.VAR_INT, 76562, StmtKind.DISPLAY),
    PRINTDATAK(ArgType.VAR_INT, 77846, StmtKind.DISPLAY),
    PRINTDATAKL(ArgType.VAR_INT, 78102, StmtKind.DISPLAY),
    PRINTDATAKW(ArgType.VAR_INT, 78610, StmtKind.DISPLAY),
    PRINTDATAL(ArgType.VAR_INT, 74006, StmtKind.DISPLAY),
    PRINTDATAW(ArgType.VAR_INT, 74514, StmtKind.DISPLAY),
    PRINTDL(ArgType.PRINT, 10502, StmtKind.DISPLAY),
    PRINTDW(ArgType.PRINT, 11010, StmtKind.DISPLAY),
    PRINTFORM(ArgType.PRINT, 8196, StmtKind.DISPLAY),
    PRINTFORMC(ArgType.PRINT, 8198, StmtKind.DISPLAY),
    PRINTFORMCD(ArgType.PRINT, 10246, StmtKind.DISPLAY),
    PRINTFORMCK(ArgType.PRINT, 12294, StmtKind.DISPLAY),
    PRINTFORMD(ArgType.PRINT, 10246, StmtKind.DISPLAY),
    PRINTFORMDL(ArgType.PRINT, 10502, StmtKind.DISPLAY),
    PRINTFORMDW(ArgType.PRINT, 11010, StmtKind.DISPLAY),
    PRINTFORMK(ArgType.PRINT, 12294, StmtKind.DISPLAY),
    PRINTFORMKL(ArgType.PRINT, 12550, StmtKind.DISPLAY),
    PRINTFORMKW(ArgType.PRINT, 13058, StmtKind.DISPLAY),
    PRINTFORML(ArgType.PRINT, 8452, StmtKind.DISPLAY),
    PRINTFORMLC(ArgType.PRINT, 8198, StmtKind.DISPLAY),
    PRINTFORMLCD(ArgType.PRINT, 10246, StmtKind.DISPLAY),
    PRINTFORMLCK(ArgType.PRINT, 12294, StmtKind.DISPLAY),
    PRINTFORMN(ArgType.PRINT, 8708, StmtKind.DISPLAY),
    PRINTFORMS(ArgType.PRINT, 8196, StmtKind.DISPLAY),
    PRINTFORMSD(ArgType.PRINT, 10246, StmtKind.DISPLAY),
    PRINTFORMSDL(ArgType.PRINT, 10502, StmtKind.DISPLAY),
    PRINTFORMSDW(ArgType.PRINT, 11010, StmtKind.DISPLAY),
    PRINTFORMSK(ArgType.PRINT, 12294, StmtKind.DISPLAY),
    PRINTFORMSKL(ArgType.PRINT, 12550, StmtKind.DISPLAY),
    PRINTFORMSKW(ArgType.PRINT, 13058, StmtKind.DISPLAY),
    PRINTFORMSL(ArgType.PRINT, 8452, StmtKind.DISPLAY),
    PRINTFORMSN(ArgType.PRINT, 8708, StmtKind.DISPLAY),
    PRINTFORMSW(ArgType.PRINT, 8960, StmtKind.DISPLAY),
    PRINTFORMW(ArgType.PRINT, 8960, StmtKind.DISPLAY),
    PRINTK(ArgType.PRINT, 12294, StmtKind.DISPLAY),
    PRINTKL(ArgType.PRINT, 12550, StmtKind.DISPLAY),
    PRINTKW(ArgType.PRINT, 13058, StmtKind.DISPLAY),
    PRINTL(ArgType.PRINT, 8452, StmtKind.DISPLAY),
    PRINTLC(ArgType.PRINT, 8198, StmtKind.DISPLAY),
    PRINTLCD(ArgType.PRINT, 10246, StmtKind.DISPLAY),
    PRINTLCK(ArgType.PRINT, 12294, StmtKind.DISPLAY),
    PRINTN(ArgType.PRINT, 8708, StmtKind.DISPLAY),
    PRINTPLAIN(ArgType.STR_NULLABLE, 6, StmtKind.DISPLAY),
    PRINTPLAINFORM(ArgType.FORM_STR_NULLABLE, 6, StmtKind.DISPLAY),
    PRINTS(ArgType.PRINT, 8196, StmtKind.DISPLAY),
    PRINTSD(ArgType.PRINT, 10246, StmtKind.DISPLAY),
    PRINTSDL(ArgType.PRINT, 10502, StmtKind.DISPLAY),
    PRINTSDW(ArgType.PRINT, 11010, StmtKind.DISPLAY),
    PRINTSINGLE(ArgType.PRINT, 9222, StmtKind.DISPLAY),
    PRINTSINGLED(ArgType.PRINT, 11270, StmtKind.DISPLAY),
    PRINTSINGLEFORM(ArgType.PRINT, 9222, StmtKind.DISPLAY),
    PRINTSINGLEFORMD(ArgType.PRINT, 11270, StmtKind.DISPLAY),
    PRINTSINGLEFORMK(ArgType.PRINT, 13318, StmtKind.DISPLAY),
    PRINTSINGLEFORMS(ArgType.PRINT, 9222, StmtKind.DISPLAY),
    PRINTSINGLEFORMSD(ArgType.PRINT, 11270, StmtKind.DISPLAY),
    PRINTSINGLEFORMSK(ArgType.PRINT, 13318, StmtKind.DISPLAY),
    PRINTSINGLEK(ArgType.PRINT, 13318, StmtKind.DISPLAY),
    PRINTSINGLES(ArgType.PRINT, 9222, StmtKind.DISPLAY),
    PRINTSINGLESD(ArgType.PRINT, 11270, StmtKind.DISPLAY),
    PRINTSINGLESK(ArgType.PRINT, 13318, StmtKind.DISPLAY),
    PRINTSINGLEV(ArgType.PRINT, 9222, StmtKind.DISPLAY),
    PRINTSINGLEVD(ArgType.PRINT, 11270, StmtKind.DISPLAY),
    PRINTSINGLEVK(ArgType.PRINT, 13318, StmtKind.DISPLAY),
    PRINTSK(ArgType.PRINT, 12294, StmtKind.DISPLAY),
    PRINTSKL(ArgType.PRINT, 12550, StmtKind.DISPLAY),
    PRINTSKW(ArgType.PRINT, 13058, StmtKind.DISPLAY),
    PRINTSL(ArgType.PRINT, 8452, StmtKind.DISPLAY),
    PRINTSN(ArgType.PRINT, 8708, StmtKind.DISPLAY),
    PRINTSW(ArgType.PRINT, 8960, StmtKind.DISPLAY),
    PRINTV(ArgType.PRINT, 8196, StmtKind.DISPLAY),
    PRINTVD(ArgType.PRINT, 10246, StmtKind.DISPLAY),
    PRINTVDL(ArgType.PRINT, 10502, StmtKind.DISPLAY),
    PRINTVDW(ArgType.PRINT, 11010, StmtKind.DISPLAY),
    PRINTVK(ArgType.PRINT, 12294, StmtKind.DISPLAY),
    PRINTVKL(ArgType.PRINT, 12550, StmtKind.DISPLAY),
    PRINTVKW(ArgType.PRINT, 13058, StmtKind.DISPLAY),
    PRINTVL(ArgType.PRINT, 8452, StmtKind.DISPLAY),
    PRINTVN(ArgType.PRINT, 8708, StmtKind.DISPLAY),
    PRINTVW(ArgType.PRINT, 8960, StmtKind.DISPLAY),
    PRINTW(ArgType.PRINT, 8960, StmtKind.DISPLAY),
    PRINT_ABL(ArgType.INT_EXPRESSION, 4, StmtKind.DISPLAY),
    PRINT_EXP(ArgType.INT_EXPRESSION, 4, StmtKind.DISPLAY),
    PRINT_IMG(ArgType.SP_PRINT_IMG, 6, StmtKind.DISPLAY),
    PRINT_ITEM(ArgType.VOID, 4, StmtKind.DISPLAY),
    PRINT_MARK(ArgType.INT_EXPRESSION, 4, StmtKind.DISPLAY),
    PRINT_PALAM(ArgType.INT_EXPRESSION, 4, StmtKind.DISPLAY),
    PRINT_RECT(ArgType.SP_PRINT_RECT, 6, StmtKind.DISPLAY),
    PRINT_SHOPITEM(ArgType.VOID, 4, StmtKind.DISPLAY),
    PRINT_SPACE(ArgType.SP_PRINT_SPACE, 6, StmtKind.DISPLAY),
    PRINT_TALENT(ArgType.INT_EXPRESSION, 4, StmtKind.DISPLAY),
    PUTFORM(ArgType.FORM_STR_NULLABLE, 4, StmtKind.IO),
    QUIT(ArgType.VOID, 0, StmtKind.OTHER),
    QUIT_AND_RESTART(ArgType.VOID, 0, StmtKind.OTHER),
    RANDOMIZE(ArgType.INT_EXPRESSION_NULLABLE, 6, StmtKind.OTHER),
    REDRAW(ArgType.INT_EXPRESSION, 6, StmtKind.DISPLAY),
    REF(ArgType.SP_REF, 6, StmtKind.VARIABLE),
    REFBYNAME(ArgType.SP_REFBYNAME, 6, StmtKind.VARIABLE),
    REMOVEBGIMAGE(ArgType.FORM_STR_ANY, 6, StmtKind.DISPLAY),
    REND(ArgType.VOID, 21, StmtKind.FLOW),
    REPEAT(ArgType.INT_EXPRESSION, 21, StmtKind.FLOW),
    RESETBGCOLOR(ArgType.VOID, 6, StmtKind.DISPLAY),
    RESETCOLOR(ArgType.VOID, 6, StmtKind.DISPLAY),
    RESETDATA(ArgType.VOID, 6, StmtKind.IO),
    RESETGLOBAL(ArgType.VOID, 6, StmtKind.IO),
    RESET_STAIN(ArgType.INT_EXPRESSION, 6, StmtKind.VARIABLE),
    RESTART(ArgType.VOID, 7, StmtKind.FLOW),
    RETURN(ArgType.INT_ANY, 1, StmtKind.FLOW),
    RETURNF(ArgType.EXPRESSION_NULLABLE, 7, StmtKind.FLOW),
    RETURNFORM(ArgType.FORM_STR, 3, StmtKind.FLOW),
    REUSELASTLINE(ArgType.FORM_STR_NULLABLE, 8198, StmtKind.DISPLAY),
    SAVECHARA(ArgType.SP_SAVECHARA, 6, StmtKind.IO),
    SAVEDATA(ArgType.SP_SAVEDATA, 6, StmtKind.IO),
    SAVEGAME(ArgType.VOID, 1, StmtKind.IO),
    SAVEGLOBAL(ArgType.VOID, 6, StmtKind.IO),
    SAVENOS(ArgType.SP_GETINT, 6, StmtKind.VARIABLE),
    SAVEVAR(ArgType.SP_SAVEVAR, 6, StmtKind.IO),
    SELECTCASE(ArgType.EXPRESSION, 55, StmtKind.FLOW),
    SETBGCOLOR(ArgType.SP_COLOR, 6, StmtKind.DISPLAY),
    SETBGCOLORBYNAME(ArgType.STR, 6, StmtKind.DISPLAY),
    SETBGIMAGE(ArgType.FORM_STR_ANY, 6, StmtKind.DISPLAY),
    SETBGMVOLUME(ArgType.INT_EXPRESSION, 6, StmtKind.OTHER),
    SETBIT(ArgType.BIT_ARG, 6, StmtKind.VARIABLE),
    SETCOLOR(ArgType.SP_COLOR, 6, StmtKind.DISPLAY),
    SETCOLORBYNAME(ArgType.STR, 6, StmtKind.DISPLAY),
    SETFONT(ArgType.STR_EXPRESSION_NULLABLE, 6, StmtKind.DISPLAY),
    SETSOUNDVOLUME(ArgType.INT_EXPRESSION, 6, StmtKind.OTHER),
    SIF(ArgType.INT_EXPRESSION, 53, StmtKind.FLOW),
    SKIPDISP(ArgType.INT_EXPRESSION, 6, StmtKind.OTHER),
    SKIPLOG(ArgType.INT_EXPRESSION, 6, StmtKind.OTHER),
    SORTCHARA(ArgType.SP_SORTCHARA, 6, StmtKind.VARIABLE),
    SPLIT(ArgType.SP_SPLIT, 6, StmtKind.VARIABLE),
    STOPBGM(ArgType.VOID, 6, StmtKind.OTHER),
    STOPCALLTRAIN(ArgType.VOID, 3, StmtKind.OTHER),
    STOPSOUND(ArgType.VOID, 6, StmtKind.OTHER),
    STRDATA(ArgType.VAR_STR, 22, StmtKind.DATA),
    STRLEN(ArgType.STR_NULLABLE, 6, StmtKind.VARIABLE),
    STRLENFORM(ArgType.FORM_STR_NULLABLE, 6, StmtKind.VARIABLE),
    STRLENFORMU(ArgType.FORM_STR_NULLABLE, 6, StmtKind.VARIABLE),
    STRLENU(ArgType.STR_NULLABLE, 6, StmtKind.VARIABLE),
    SWAP(ArgType.SP_SWAPVAR, 6, StmtKind.VARIABLE),
    SWAPCHARA(ArgType.SP_SWAP, 6, StmtKind.VARIABLE),
    THROW(ArgType.FORM_STR_NULLABLE, 6, StmtKind.OTHER),
    TIMES(ArgType.SP_TIMES, 4, StmtKind.VARIABLE),
    TINPUT(ArgType.SP_TINPUT, 24578, StmtKind.INPUT),
    TINPUTS(ArgType.SP_TINPUTS, 24578, StmtKind.INPUT),
    TONEINPUT(ArgType.SP_TINPUT, 24578, StmtKind.INPUT),
    TONEINPUTS(ArgType.SP_TINPUTS, 24578, StmtKind.INPUT),
    TOOLTIP_CUSTOM(ArgType.INT_EXPRESSION, 2, StmtKind.DISPLAY),
    TOOLTIP_FORMAT(ArgType.INT_EXPRESSION, 2, StmtKind.DISPLAY),
    TOOLTIP_IMG(ArgType.INT_EXPRESSION, 2, StmtKind.DISPLAY),
    TOOLTIP_SETCOLOR(ArgType.SP_SWAP, 6, StmtKind.DISPLAY),
    TOOLTIP_SETDELAY(ArgType.INT_EXPRESSION, 6, StmtKind.DISPLAY),
    TOOLTIP_SETDURATION(ArgType.INT_EXPRESSION, 6, StmtKind.DISPLAY),
    TOOLTIP_SETFONT(ArgType.STR_EXPRESSION, 2, StmtKind.DISPLAY),
    TOOLTIP_SETFONTSIZE(ArgType.INT_EXPRESSION, 2, StmtKind.DISPLAY),
    TRYCALL(ArgType.SP_CALL, 163, StmtKind.FLOW),
    TRYCALLF(ArgType.SP_CALLF, 38, StmtKind.CALL),
    TRYCALLFORM(ArgType.SP_CALLFORM, 163, StmtKind.FLOW),
    TRYCALLFORMF(ArgType.SP_CALLFORMF, 38, StmtKind.CALL),
    TRYCALLLIST(ArgType.VOID, 147, StmtKind.FLOW),
    TRYCCALL(ArgType.SP_CALL, 32947, StmtKind.FLOW),
    TRYCCALLFORM(ArgType.SP_CALLFORM, 32947, StmtKind.FLOW),
    TRYCGOTO(ArgType.SP_CALL, 32951, StmtKind.FLOW),
    TRYCGOTOFORM(ArgType.SP_CALLFORM, 32951, StmtKind.FLOW),
    TRYCJUMP(ArgType.SP_CALL, 33011, StmtKind.FLOW),
    TRYCJUMPFORM(ArgType.SP_CALLFORM, 33011, StmtKind.FLOW),
    TRYGOTO(ArgType.SP_CALL, 167, StmtKind.FLOW),
    TRYGOTOFORM(ArgType.SP_CALLFORM, 167, StmtKind.FLOW),
    TRYGOTOLIST(ArgType.VOID, 147, StmtKind.FLOW),
    TRYJUMP(ArgType.SP_CALL, 227, StmtKind.FLOW),
    TRYJUMPFORM(ArgType.SP_CALLFORM, 227, StmtKind.FLOW),
    TRYJUMPLIST(ArgType.VOID, 211, StmtKind.FLOW),
    TWAIT(ArgType.SP_SWAP, 8194, StmtKind.INPUT),
    UPCHECK(ArgType.VOID, 4, StmtKind.VARIABLE),
    UPDATECHECK(ArgType.VOID, 6, StmtKind.OTHER),
    VARI(ArgType.INT_ASIGN, 0, StmtKind.VARIABLE),
    VARS(ArgType.STR_ASIGN, 0, StmtKind.VARIABLE),
    VARSET(ArgType.SP_VAR_SET, 6, StmtKind.VARIABLE),
    VARSIZE(ArgType.SP_VAR, 6, StmtKind.VARIABLE),
    WAIT(ArgType.VOID, 8192, StmtKind.INPUT),
    WAITANYKEY(ArgType.VOID, 8192, StmtKind.INPUT),
    WEND(ArgType.VOID, 23, StmtKind.FLOW),
    WHILE(ArgType.INT_EXPRESSION, 23, StmtKind.FLOW),
    ;

    /** 流程控制语句（flag & 1 = FLOW_CONTROL，spec: FunctionIdentifier.cs:3721 常量、:4230-4233 IsFlowContorol） */
    val isFlowControl: Boolean get() = (flag and 1) == 1

    /** 扩展语句（flag & 2 = EXTENDED，spec: FunctionIdentifier.cs:3723 常量、:4235-4238 IsExtended） */
    val isExtended: Boolean get() = (flag and 2) == 2

    companion object {
        /**
         * 名称（大写）→ 函数码。原版 Config.IgnoreCase 默认 true，spec: FunctionIdentifier.cs:3823。
         *
         * **SET / METHOD 两条伪码不在表内**（CMP-48）：原版 `funcDic` 只由
         * `addFunction` / `addPrintFunction` 填充（spec: FunctionIdentifier.cs:3791-3805、:4135-4141），
         * 而 `setFunc = new FunctionIdentifier("SET", …)` 只是构造出来供赋值行复用、**从未入表**
         * （:3829；`GetInstructionNameDic()` 直接返回 funcDic，:3807-3810）；
         * `methodInstruction` 同理 —— 入表键是**方法名**（`method.Key`，:4138-4141），不是字面量
         * `"METHOD"`。所以原版行首 `SET = 5` 走赋值两段式（LogicalLineParser.cs:536-556），
         * 用户 `#DIM SET` 也合法（IdentifierDictionary.cs:258-297 只拒 Reserved/SystemVariable/
         * SystemMethod/SystemInstrument，SET 不属任何一类）。
         *
         * 若把伪码留在表里，`LineParser.parseCodeLine` 的语句名优先查找就把它当指令：
         * `SET` 进 SP_SET 无左值 → 抛错 → **整个 @函数降成空体桩**（缺陷台账 CMP-48）。
         */
        val BY_NAME: Map<String, FunctionCode> = entries
            .filter { it != SET && it != METHOD }
            .associateBy { it.name }
    }
}

/**
 * 块结构配对表 —— 逐条移植自 FunctionIdentifier 静态构造末尾的 funcMatch / funcParent
 * （spec: FunctionIdentifier.cs:4143-4170 与 :4171-4174；两张表的**读取者**只有
 * `getMatchFunction` :4177-4184 与 `getParentFunc` :4186-4193，调用点在装载器的
 * `ErbLoader.cs:1251`（CASE 恢复）/ `:1279`（ENDSELECT 恢复）/ `:1329`（REND/NEXT/WEND/LOOP
 * 找母语句）/ `:1565`（函数结束时栈里剩下的开语句报错））。
 */
object BlockTable {
    /** 开始语句 → 期望的结束语句。spec: FunctionIdentifier.cs:4143-4170（28 条，逐条对齐） */
    val openToClose: Map<FunctionCode, FunctionCode> = mapOf(
        FunctionCode.IF to FunctionCode.ENDIF,
        FunctionCode.SELECTCASE to FunctionCode.ENDSELECT,
        FunctionCode.REPEAT to FunctionCode.REND,
        FunctionCode.FOR to FunctionCode.NEXT,
        FunctionCode.WHILE to FunctionCode.WEND,
        FunctionCode.TRYCGOTO to FunctionCode.CATCH,
        FunctionCode.TRYCJUMP to FunctionCode.CATCH,
        FunctionCode.TRYCCALL to FunctionCode.CATCH,
        FunctionCode.TRYCGOTOFORM to FunctionCode.CATCH,
        FunctionCode.TRYCJUMPFORM to FunctionCode.CATCH,
        FunctionCode.TRYCCALLFORM to FunctionCode.CATCH,
        FunctionCode.CATCH to FunctionCode.ENDCATCH,
        FunctionCode.DO to FunctionCode.LOOP,
        FunctionCode.PRINTDATA to FunctionCode.ENDDATA,
        FunctionCode.PRINTDATAL to FunctionCode.ENDDATA,
        FunctionCode.PRINTDATAW to FunctionCode.ENDDATA,
        FunctionCode.PRINTDATAK to FunctionCode.ENDDATA,
        FunctionCode.PRINTDATAKL to FunctionCode.ENDDATA,
        FunctionCode.PRINTDATAKW to FunctionCode.ENDDATA,
        FunctionCode.PRINTDATAD to FunctionCode.ENDDATA,
        FunctionCode.PRINTDATADL to FunctionCode.ENDDATA,
        FunctionCode.PRINTDATADW to FunctionCode.ENDDATA,
        FunctionCode.DATALIST to FunctionCode.ENDLIST,
        FunctionCode.STRDATA to FunctionCode.ENDDATA,
        FunctionCode.NOSKIP to FunctionCode.ENDNOSKIP,
        FunctionCode.TRYCALLLIST to FunctionCode.ENDFUNC,
        FunctionCode.TRYGOTOLIST to FunctionCode.ENDFUNC,
        FunctionCode.TRYJUMPLIST to FunctionCode.ENDFUNC,
    )

    /** 结束语句 → 其母语句。spec: FunctionIdentifier.cs:4171-4174（只有 REND/NEXT/WEND/LOOP 四条） */
    val parentOf: Map<FunctionCode, FunctionCode> = mapOf(
        FunctionCode.REND to FunctionCode.REPEAT,
        FunctionCode.NEXT to FunctionCode.FOR,
        FunctionCode.WEND to FunctionCode.WHILE,
        FunctionCode.LOOP to FunctionCode.DO,
    )

    /**
     * 分支语句（不改变块深度，但要求栈顶是对应的母块）。
     * spec: ELSE / ELSEIF 要求栈顶是 `IF`（`ErbLoader.cs:1208-1222`，否则
     * `InvalidElse` level 2 置错）；CASE / CASEELSE 要求栈顶是 `SELECTCASE`
     * （`ErbLoader.cs:1239-1265`，否则 `OutsideSelectcase` level 2）。
     * 原版还有一层 `stack2`（SELECTCASE 的嵌套栈，`:1048` 建、`:1135` 压、`:1284` 弹）：
     * 栈顶不是 SELECTCASE 但 `stack2` 非空时，原版**逐个弹掉中间的开语句**并逐条报
     * `InstructionNotClosed`（`:1247-1256` / `:1275-1287`），而不是只报错不弹 —— 见
     * [BlockChecker] 的差异登记。
     */
    val branchTargets: Set<FunctionCode> = setOf(
        FunctionCode.ELSEIF, FunctionCode.ELSE, FunctionCode.CASE, FunctionCode.CASEELSE,
    )
}
