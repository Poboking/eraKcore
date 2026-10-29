// 本文件 0..197 由 specs/isa.yaml 生成，请勿手改。生成规则：内部指令在前（0..28），其后按
// flow -> display -> variable -> call -> input -> io -> data -> debug -> other 分段，段内按语句名升序连续编号。
// 校验：OpcodeTableTest 直接解析 specs/isa.yaml，断言名字/数量/别名一一对应。
//
// 198..243 是**手维护扩展区**（isa.yaml 不覆盖，OpcodeTableTest 逐条锁死名字与顺序）：
// 198..201 编译器扩展（CALLFORM/GOTOFORM/#FUNCTIONS 等运行期名字解析），
// 202..243 栈码组（T2-21：表达式降级的落地目标）。两者都进 NAMES/BY_NAME，
// 保证反汇编可读、文本汇编可写回（asm(disasm(p)) 往返）。
package com.eratw.port.core.code

/**
 * 操作码常量表。
 *
 * ## 编号规则（ADR-004：定长 4x u32 指令，dispatch 用 `when` 密集跳转表）
 *
 * 编号**全局连续、无空洞**——这是 `when (op)` 编成 `tableswitch` 的前提；有空洞会退化成
 * `lookupswitch`（二分比较），分支越多越慢。
 *
 * | 区段 | 范围 | 数量 |
 * |------|------|------|
 * | VM 内部指令 | 0..28 | 29 |
 * | kind=flow | 29..52 | 24 |
 * | kind=display | 53..100 | 48 |
 * | kind=variable | 101..133 | 33 |
 * | kind=call | 134..137 | 4 |
 * | kind=input | 138..152 | 15 |
 * | kind=io | 153..165 | 13 |
 * | kind=data | 166..169 | 4 |
 * | kind=debug | 170..171 | 2 |
 * | kind=other | 172..197 | 26 |
 * | 编译器扩展 | 198..201 | 4 |
 * | 栈码组 | 202..243 | 42 |
 * | **合计** | 0..243 | 244 |
 *
 * 下划线开头的名字是 VM 内部指令（0..28）、编译器扩展指令（198..201）或栈码（202..243），
 * 不对应任何 ERB 语句（isa.yaml 中 JUMP/CALL 等也是语句别名，直接复用会撞名）；29..197 的
 * 名字与 isa.yaml 的 `name` 字段逐字相同（169 条）。ERB 书写用的是 `aliases`，映射见
 * [StatementAliases]。
 *
 * 198..201 是 `core.compile` 追加的**编译器扩展指令**，202..243 是**栈码组**（T2-21），
 * 两者合起来是 [EXTENSION_OPS]，都不是 isa.yaml 语句：它们有独立的操作数语义与 VM 分支，
 * 但同样进 [NAMES]/[BY_NAME] 以保证反汇编可读、文本汇编可写回（`asm(disasm(p))` 往返）。
 */
object Opcode {

    // ------------------------------------------------------------------
    // VM 内部指令（M2 最小可执行集）
    // ------------------------------------------------------------------

    /** 空操作（对齐/占位） */
    const val _NOP = 0

    /** 停止 VM 并正常返回 */
    const val _HALT = 1

    /** a=目标槽, b=i64 常量池下标 */
    const val _SET_INT = 2

    /** a=目标槽, b=字符串池下标 */
    const val _SET_STR = 3

    /** a=目标槽, b=源槽 */
    const val _MOV_INT = 4

    /** a=目标槽, b=源槽 */
    const val _MOV_STR = 5

    /** a=目标, b=源1, c=源2（i64 回绕） */
    const val _ADD = 6

    /** a=目标, b=源1, c=源2 */
    const val _SUB = 7

    /** a=目标, b=源1, c=源2 */
    const val _MUL = 8

    /** a=目标, b=源1, c=源2（除 0 → CodeEE） */
    const val _DIV = 9

    /** a=目标, b=源1, c=源2（除 0 → CodeEE） */
    const val _MOD = 10

    /** a=目标, b=源（Long.MIN_VALUE 回绕） */
    const val _NEG = 11

    /** a=目标, b=源1, c=源2 → 0/1 */
    const val _EQ = 12

    /** a=目标, b=源1, c=源2 → 0/1 */
    const val _NE = 13

    /** a=目标, b=源1, c=源2 → 0/1 */
    const val _LT = 14

    /** a=目标, b=源1, c=源2 → 0/1 */
    const val _LE = 15

    /** a=目标, b=源1, c=源2 → 0/1 */
    const val _GT = 16

    /** a=目标, b=源1, c=源2 → 0/1 */
    const val _GE = 17

    /** a=目标, b=源（0→1，非0→0） */
    const val _NOT = 18

    /** a=有符号指令偏移（相对下一条指令） */
    const val _JUMP = 19

    /** a=条件槽, b=偏移 */
    const val _JUMP_IF_FALSE = 20

    /** a=条件槽, b=偏移 */
    const val _JUMP_IF_TRUE = 21

    /** a=函数下标, b=实参数, c=返回目标槽（NONE=丢弃） */
    const val _CALL = 22

    /** a=返回槽（NONE=返回 0） */
    const val _RET = 23

    /** a=槽, b=标志位（bit0=末尾换行） */
    const val _PRINT_INT = 24

    /** a=槽, b=标志位 */
    const val _PRINT_STR = 25

    /** a=字符串池下标, b=标志位 */
    const val _PRINT_LITERAL = 26

    /** a=表达式池下标, b=目标槽（走 core.expr 求值） */
    const val _EVAL_INT = 27

    /** a=表达式池下标, b=目标槽（走 core.expr 求值） */
    const val _EVAL_STR = 28

    /** 内部指令条数（= 第一条 ERB 语句的编号）。 */
    const val INTERNAL_COUNT = 29

    // ------------------------------------------------------------------
    // kind=flow（24 条，编号 29..52）
    // ------------------------------------------------------------------

    /** 结束当前函数,把执行状态切换为指定的 BEGIN 入口(如 TITLE/SHOW_STATUS)并重置显示样式;FORCE_BEGIN 为强制变体(当前实现行为一致)。 */
    const val BEGIN = 29
    /** 跳出当前循环:对 REPEAT/FOR 先把计数器按步长补进一次再退出,WHILE/DO 直接退出。 */
    const val BREAK = 30
    /** 调用用户定义函数并把实参绑定到其 ARG 变量;JUMP 变体不保留返回点(单向跳转);FORM 变体函数名为表达式;TRY 变体在函数不存在时跳过或进入 CATCH;TRYC 变体在函数体出错时进入 CATCH。 */
    const val CALL = 31
    /** 调用指定名称的事件函数组(依次执行全部匹配的事件函数)。 */
    const val CALLEVENT = 32
    /** TRYC 系列失败后的捕获入口:执行到达即跳到对应 ENDCATCH 之后。 */
    const val CATCH = 33
    /** 进入当前循环的下一次迭代:REPEAT/FOR 先步进计数并判断是否继续;WHILE 重新判断条件;DO 重新判断 LOOP 条件。 */
    const val CONTINUE = 34
    /** 执行到达本行即无条件跳转到匹配的分支体结尾之后(条件求值由 IF/SELECTCASE 完成);注册名为 ELSE、ELSEIF、CASE、CASEELSE。 */
    const val ELSEIF = 35
    /** 结构块结束标记,运行时无行为;注册名为 ENDIF、ENDSELECT、DO、ENDCATCH、ENDFUNC。 */
    const val ENDIF = 36
    /** 结束当前函数,把执行状态切换为指定的 BEGIN 入口并重置显示样式;与 BEGIN 的区别在于解析期跳过部分状态检查(当前实现两者行为一致)。 */
    const val FORCE_BEGIN = 37
    /** 跳转到同一函数内的 $ 标签;GOTOFORM 变体标签名为表达式;TRYGOTO 在标签不存在时静默跳过;TRYCGOTO 在跳转目标行出错时进入 CATCH。 */
    const val GOTO = 38
    /** 依序求值本行及关联 ELSEIF 行的条件,跳转到第一个为真的分支体;全为假且有 ELSE 则进 ELSE,否则跳到 ENDIF 之后。 */
    const val IF = 39
    /** DO 循环尾:条件为真则回到 DO 继续循环。 */
    const val LOOP = 40
    /** REPEAT/FOR 循环尾:计数器按步长递增,未达终值则回到循环体,否则继续;NEXT 为 FOR 的注册名。 */
    const val REND = 41
    /** REPEAT 以参数为循环次数开始计数循环;FOR 变体参数为 (起始值, 结束值, 步长) 并把计数变量绑定为循环变量;初始条件不满足时直接跳到循环尾。 */
    const val REPEAT = 42
    /** 跳回当前函数开头重新执行。 */
    const val RESTART = 43
    /** 从当前函数返回调用者;参数依次写入 RESULT: 0/1/2…,无参数时 RESULT=0。 */
    const val RETURN = 44
    /** 从 #FUNCTION 函数返回值;无参数返回 0/空串。 */
    const val RETURNF = 45
    /** 把格式化字符串展开后按逗号解析为整数列表,写入 RESULT: 系列并返回调用者(空列表按 0 处理)。 */
    const val RETURNFORM = 46
    /** 对选择值依次匹配各 CASE 行(数值或字符串),跳到第一个匹配分支;无匹配时走 CASEELSE,否则跳到 ENDSELECT 之后。 */
    const val SELECTCASE = 47
    /** 条件为真时执行紧随其后的单条语句,否则跳过它。 */
    const val SIF = 48
    /** TRYCALLLIST/TRYJUMPLIST:依序尝试调用块内 FUNC 列出的函数,第一个存在的函数被执行并返回;全部不存在时跳到 ENDFUNC 之后。TRYJUMPLIST 变体为不保留返回点的跳转。 */
    const val TRYCALLLIST = 49
    /** 依序尝试跳转到块内列出的标签,第一个存在的标签生效;全部不存在时继续执行 ENDFUNC 之后的行。 */
    const val TRYGOTOLIST = 50
    /** WHILE 循环尾:重新求值 WHILE 条件,为真则回到循环体。 */
    const val WEND = 51
    /** 条件为真进入循环体,为假跳到 WEND 之后。 */
    const val WHILE = 52

    // ------------------------------------------------------------------
    // kind=display（48 条，编号 53..100）
    // ------------------------------------------------------------------

    /** 设置当前行对齐方式,参数为 LEFT/CENTER/RIGHT。 */
    const val ALIGNMENT = 53
    /** 暂停指定毫秒数后继续;无参数时无限等待。 */
    const val AWAIT = 54
    /** 按 (当前值, 最大值, 长度) 生成进度条字符串输出;BARL 变体输出后换行。 */
    const val BAR = 55
    /** 清除全部背景图。 */
    const val CLEARBGIMAGE = 56
    /** 从当前行向上删除指定数量的显示行并刷新。 */
    const val CLEARLINE = 57
    /** 清空输入框/当前未提交文本。 */
    const val CLEARTEXTBOX = 58
    /** 用参数字符串重复填充一行作为分隔线输出,随后换行。 */
    const val CUSTOMDRAWLINE = 59
    /** 输出一行默认样式分隔线并换行。 */
    const val DRAWLINE = 60
    /** 用参数字符串(可含表达式)重复填充一行作为分隔线输出并换行。 */
    const val DRAWLINEFORM = 61
    /** 把文字样式追加为粗体。 */
    const val FONTBOLD = 62
    /** 把文字样式追加为斜体。 */
    const val FONTITALIC = 63
    /** 把文字样式重置为常规。 */
    const val FONTREGULAR = 64
    /** 按位掩码设置字体样式:1=粗体,2=斜体,4=删除线,8=下划线。 */
    const val FONTSTYLE = 65
    /** 把参数字符串作为 HTML 片段渲染到控制台;第二个可选整型参数非 0 时启用附加渲染选项。 */
    const val HTML_PRINT = 66
    /** 把 HTML 片段作为可独立刷新的浮岛输出到控制台。 */
    const val HTML_PRINT_ISLAND = 67
    /** 清除控制台上已存在的 HTML 浮岛。 */
    const val HTML_PRINT_ISLAND_CLEAR = 68
    /** 把 HTML 字符串按标签拆分,标签数写入整型变量、结果写入字符串数组变量;无法拆分时变量写 -1。 */
    const val HTML_TAGSPLIT = 69
    /** PRINT 家族统一实现:按语句名后缀决定行为——V=逐项输出表达式,S=输出字符串表达式,FORM=展开文本中 {表达式},K=字符串类型转换,D=使用调试配色,SINGLE=单行独立显示,C/LC=右/左对... */
    const val PRINT = 70
    /** 输出一个可点击按钮:显示文本与按钮值(整型或字符串);文本中的换行被移除。 */
    const val PRINTBUTTON = 71
    /** 以定宽对齐方式输出按钮;PRINTBUTTONC 右对齐,PRINTBUTTONLC 左对齐。 */
    const val PRINTBUTTONC = 72
    /** 以无样式纯文本形式输出字符串(不套用用户配色);PRINTPLAINFORM 变体展开表达式。 */
    const val PRINTPLAIN = 73
    /** 打印指定角色的能力(ABL)数据汇总串并换行。PRINT_TALENT/PRINT_MARK/PRINT_EXP 分别对应素质/标记/经验,共用同一实现分支。 */
    const val PRINT_ABL = 74
    /** 从配对的 DATA/DATALIST 数据节中随机选取一组,逐行打印其字符串(行间换行);可先把随机索引写入指定整型变量;按语句名后缀 L/W 决定末尾换行或换行加等待按键。 */
    const val PRINT_DATA = 75
    /** 在控制台打印图片,可指定普通/遮罩文件名及宽、高、附加参数(px 或百分比)。 */
    const val PRINT_IMG = 76
    /** 打印当前持有物品清单并换行。 */
    const val PRINT_ITEM = 77
    /** 按 PrintCPerLine 配置分列打印指定角色的全部参数(PALAM)。 */
    const val PRINT_PALAM = 78
    /** 在控制台打印矩形色块,参数为尺寸/位置序列(px 或百分比)。 */
    const val PRINT_RECT = 79
    /** 打印商店中所有在售商品(名称、编号、价格),按 PrintCPerLine 分列。 */
    const val PRINT_SHOPITEM = 80
    /** 在控制台插入一段空白,参数为宽度(px 或百分比)。 */
    const val PRINT_SPACE = 81
    /** 设置控制台重绘模式(0=禁用,1=命令后,2=每行)。 */
    const val REDRAW = 82
    /** 按文件名移除指定背景图。 */
    const val REMOVEBGIMAGE = 83
    /** 把背景色恢复为配置默认色。 */
    const val RESETBGCOLOR = 84
    /** 把前景色恢复为配置默认色。 */
    const val RESETCOLOR = 85
    /** 把最后一行显示内容替换为参数字符串(临时行)。 */
    const val REUSELASTLINE = 86
    /** 设置背景色,参数为 0xRRGGBB 整数或 R,G,B 三分量。 */
    const val SETBGCOLOR = 87
    /** 按颜色名称设置背景色。 */
    const val SETBGCOLORBYNAME = 88
    /** 添加背景图片:文件名、可选深度值与可选不透明度(0-255)。 */
    const val SETBGIMAGE = 89
    /** 设置前景色,参数为 0xRRGGBB 整数或 R,G,B 三分量。 */
    const val SETCOLOR = 90
    /** 按颜色名称(如 RED)设置前景色。 */
    const val SETCOLORBYNAME = 91
    /** 按名称设置后续输出使用的字体;空参数恢复默认字体。 */
    const val SETFONT = 92
    /** 启用(参数非 0)或禁用(参数为 0)自定义工具提示。 */
    const val TOOLTIP_CUSTOM = 93
    /** 设置工具提示的格式标志(格式样式整数值)。 */
    const val TOOLTIP_FORMAT = 94
    /** 启用(参数非 0)或禁用(参数为 0)工具提示中的图片显示。 */
    const val TOOLTIP_IMG = 95
    /** 设置工具提示的前景色与背景色(0xRRGGBB)。 */
    const val TOOLTIP_SETCOLOR = 96
    /** 设置工具提示弹出延迟(毫秒)。 */
    const val TOOLTIP_SETDELAY = 97
    /** 设置工具提示显示时长(毫秒),超过 32767 时截断为 32767。 */
    const val TOOLTIP_SETDURATION = 98
    /** 按名称设置工具提示字体。 */
    const val TOOLTIP_SETFONT = 99
    /** 设置工具提示字号。 */
    const val TOOLTIP_SETFONTSIZE = 100

    // ------------------------------------------------------------------
    // kind=variable（33 条，编号 101..133）
    // ------------------------------------------------------------------

    /** 按 CSV 角色号把角色加入角色列表;ADDSPCHARA 加入 SP 角色(SP 未启用时抛错);DELCHARA 删除参数指定的角色。 */
    const val ADDCHARA = 101
    /** 把指定角色依次复制为新的角色追加到列表尾。 */
    const val ADDCOPYCHARA = 102
    /** 添加 CSV 0 号角色及 GameBase 指定的默认角色。 */
    const val ADDDEFCHARA = 103
    /** 添加一个不带 CSV 数据的空白角色并返回其角色号。 */
    const val ADDVOIDCHARA = 104
    /** 把第二个数组的内容复制到第一个数组(数组以变量名指定)。 */
    const val ARRAYCOPY = 105
    /** 从一维数组的指定下标开始删除指定个数的元素,其余元素前移补齐。 */
    const val ARRAYREMOVE = 106
    /** 把一维数组从指定下标起整体移位腾出空间,用指定值填充(可限制参与填充的区域范围)。第二参数为移位量,第三参数为起始下标,第四、五参数为填充范围。 */
    const val ARRAYSHIFT = 107
    /** 对一维数组的指定范围按升/降序排序;第二参数为排序方向,第三、四参数为范围。 */
    const val ARRAYSORT = 108
    /** 把一个角色的全部数据复制到目标位置。 */
    const val COPYCHARA = 109
    /** 对指定角色执行 UP/PALAM 系统的参数更新结算,可能伴随打印输出。 */
    const val CUPCHECK = 110
    /** 对全部角色(或指定角色号范围)的角色变量统一赋值;字符串键必须已在常量表中定义。 */
    const val CVARSET = 111
    /** 删除全部角色。 */
    const val DELALLCHARA = 112
    /** 把字符串逐字符转换为 Unicode 码位,写入 RESULT_ARRAY。 */
    const val ENCODETOUNI = 113
    /** 以语句形式执行方法调用并丢弃结果:整型结果写入 RESULT,字符串结果写入 RESULTS。注册表把所有函数方法名(如 ABS、MAX)映射到此实现。 */
    const val METHOD = 114
    /** 重排角色列表,只保留参数指定的角色(顺序按参数)。 */
    const val PICKUPCHARA = 115
    /** 计算 x 的 y 次幂并写入目标变量。 */
    const val POWER = 116
    /** 把配置项 PrintCPerLine(每行 PrintC 列数)写入指定变量。 */
    const val PRINTCPERLINE = 117
    /** 占位语句:运行时直接抛出未实现异常。 */
    const val REF = 118
    /** 把指定角色的污渍(stain)数据重置为默认值。 */
    const val RESET_STAIN = 119
    /** 把配置项 SaveDataNos(存档槽数)写入指定变量。 */
    const val SAVENOS = 120
    /** 向变量赋值:标量直接赋(整型/字符串),或把逗号分隔的多个值批量赋给数组;+= 形式(AddConst)对整型为累加。 */
    const val SET = 121
    /** 对目标整型变量的指定位做位运算:SETBIT 置 1、CLEARBIT 清 0、INVERTBIT 取反;可一次指定多个位。 */
    const val SETBIT = 122
    /** 以指定角色变量为键对角色列表排序,可指定升/降序;排序保持 MASTER 等角色相对关系(fixMaster)。 */
    const val SORTCHARA = 123
    /** 用分隔符把字符串拆分为子串:段数写入计数变量,结果写入字符串数组变量(超出数组容量时截断)。 */
    const val SPLIT = 124
    /** 求字符串长度写入 RESULT;U 变体按 Unicode 字符数,否则按显示宽度计;FORM 变体先展开表达式。 */
    const val STRLEN = 125
    /** 交换两个变量的值(整型或字符串)。 */
    const val SWAP = 126
    /** 交换角色列表中两个角色的位置。 */
    const val SWAPCHARA = 127
    /** 把目标整型变量乘以语句中书写的小数系数后写回(截断取整);配置允许时用非严格浮点算法。 */
    const val TIMES = 128
    /** 对所有角色执行 UP/PALAM 系统的参数更新结算,可能伴随打印输出。 */
    const val UPCHECK = 129
    /** 作用域私有整型变量进入作用域;若该变量为标量(长度为1)则同时以参数表达式赋初值。 */
    const val VARI = 130
    /** 作用域私有字符串变量进入作用域;若该变量为标量则同时以参数值赋初值。 */
    const val VARS = 131
    /** 把数组变量的指定下标范围(缺省为全数组)内所有元素统一赋为给定值;START/END 给反时自动交换。 */
    const val VARSET = 132
    /** 查询数组变量的维度大小,结果经 VarSize 返回。 */
    const val VARSIZE = 133

    // ------------------------------------------------------------------
    // kind=call（4 条，编号 134..137）
    // ------------------------------------------------------------------

    /** 调用用户 #FUNCTION 方法并对结果求值,但不跳转、结果不写入 RESULT(仅供副作用/校验);CALLFORMF 变体函数名为表达式。 */
    const val CALLF = 134
    /** 调用插件注册的本地 C# 方法,参数传入;参数若为变量则把本地方法修改后的值回写。 */
    const val CALLSHARP = 135
    /** #FUNCTION 函数定义头:声明带 ARG 参数的函数方法;运行时无行为,仅解析期使用。 */
    const val FUNC = 136
    /** 同 CALLF,但函数不存在时静默跳过,不报错。 */
    const val TRYCALLF = 137

    // ------------------------------------------------------------------
    // kind=input（15 条，编号 138..152）
    // ------------------------------------------------------------------

    /** 等待用户点击画面上已有的整数值按钮,结果存入 RESULT;若无可点击按钮则直接采用默认值;无按钮且无默认值时抛错。执行前先换行并强制刷新显示。 */
    const val BINPUT = 138
    /** 等待用户点击字符串值按钮,结果存入 RESULTS;行为同 BINPUT。 */
    const val BINPUTS = 139
    /** 请求用户输入整数存入 RESULT;可带默认值与是否接受鼠标输入标志;消息跳过激活时直接采用默认值。 */
    const val INPUT = 140
    /** 等待任意类型输入(整数/字符串/按键均可),结果写入 RESULT 与 RESULTS。 */
    const val INPUTANY = 141
    /** 请求原始鼠标/键盘输入(写入 RESULT: 系列),参数 1 为可选限时毫秒数。 */
    const val INPUTMOUSEKEY = 142
    /** 请求用户输入字符串存入 RESULTS;可带默认值与鼠标输入标志;消息跳过激活时直接采用默认值。 */
    const val INPUTS = 143
    /** 单次按钮整数输入:一次点击即完成,行为同 BINPUT。 */
    const val ONEBINPUT = 144
    /** 单次按钮字符串输入:一次点击即完成,行为同 BINPUTS。 */
    const val ONEBINPUTS = 145
    /** 请求单键整数输入(一个按键即完成);可带默认值;消息跳过激活时直接采用默认值。 */
    const val ONEINPUT = 146
    /** 请求单键字符串输入;可带默认值;消息跳过激活时直接采用默认值。 */
    const val ONEINPUTS = 147
    /** 限时整数输入:参数为时限毫秒、默认值、是否接受鼠标、是否显示剩余时间、超时提示语(缺省用配置);超时取默认值;TONEINPUT 为单键变体。 */
    const val TINPUT = 148
    /** 限时字符串输入,行为同 TINPUT;TONEINPUTS 为单键变体。 */
    const val TINPUTS = 149
    /** 先执行一次普通按键等待,再进行限时输入:参数 1 为时限毫秒数,参数 2 非 0 时为定时等待(无需回车)。 */
    const val TWAIT = 150
    /** 等待用户按键;FORCEWAIT 变体禁止用消息跳过来中断等待。 */
    const val WAIT = 151
    /** 等待任意按键(不限回车)。 */
    const val WAITANYKEY = 152

    // ------------------------------------------------------------------
    // kind=io（13 条，编号 153..165）
    // ------------------------------------------------------------------

    /** 删除指定编号存档槽的存档文件。 */
    const val DELDATA = 153
    /** 从指定名称的角色存档文件加载角色并加入角色列表。 */
    const val LOADCHARA = 154
    /** 从指定槽位读取存档并切换到读档后状态;读档成功后清空调用栈。 */
    const val LOADDATA = 155
    /** 从全局存档文件读取全局变量;RESULT 记录 1=成功,0=失败。 */
    const val LOADGLOBAL = 156
    /** 占位语句:运行时抛出未实现异常。 */
    const val LOADVAR = 157
    /** 把字符串追加到存档描述文本 SAVEDATA_TEXT(供 SAVEDATA 使用)。 */
    const val PUTFORM = 158
    /** 重置全部变量数据为初始值并重置显示样式。 */
    const val RESETDATA = 159
    /** 清空全部全局变量数据。 */
    const val RESETGLOBAL = 160
    /** 把指定角色号的角色数据保存到 (文件名, 描述) 指定的角色存档文件。 */
    const val SAVECHARA = 161
    /** 把游戏数据保存到指定槽位,第二个参数为存档描述文本。 */
    const val SAVEDATA = 162
    /** 把全局变量写入全局存档文件。 */
    const val SAVEGLOBAL = 163
    /** SAVEGAME/LOADGAME:进入存档/读档界面流程。 */
    const val SAVELOADGAME = 164
    /** 占位语句:运行时抛出未实现异常。 */
    const val SAVEVAR = 165

    // ------------------------------------------------------------------
    // kind=data（4 条，编号 166..169）
    // ------------------------------------------------------------------

    /** DATA/DATAFORM 数据行:声明数据内容供 PRINT_DATA 等语句随机抽取;运行时本身无独立行为,由消费方读取。 */
    const val DATA = 166
    /** 开始一个数据组(DATALIST..ENDLIST 之间各行作为一组被整组抽取)。 */
    const val DATALIST = 167
    /** 运行时无任何行为;注册名为 ENDDATA,作为 DATA 块结束标记(跳转目标)。 */
    const val DO_NOTHING = 168
    /** 从 STRDATA..ENDDATA 数据节随机抽取一组,把组内各行以换行连接写入指定字符串变量,然后跳到 ENDDATA 之后。 */
    const val STRDATA = 169

    // ------------------------------------------------------------------
    // kind=debug（2 条，编号 170..171）
    // ------------------------------------------------------------------

    /** 清空调试窗口内容。 */
    const val DEBUGCLEAR = 170
    /** 把字符串输出到调试窗口(不进入主文字流);L 变体输出后换行,FORM 变体展开表达式。 */
    const val DEBUGPRINT = 171

    // ------------------------------------------------------------------
    // kind=other（26 条，编号 172..197）
    // ------------------------------------------------------------------

    /** 断言语句:参数为 0 时报错中断执行。 */
    const val ASSERT = 172
    /** 进入自动连续执行模式:按指定命令序列自动依次调用训练命令。 */
    const val CALLTRAIN = 173
    /** 切换到指定命令编号的训练执行状态(DO TRAIN 流程)。 */
    const val DOTRAIN = 174
    /** 为数据表列设置选项(当前实现仅支持 Default 默认值)。 */
    const val DT_COLUMN_OPTIONS = 175
    /** 保存当前随机数状态以便之后恢复;UseNewRandom 配置下禁止使用并警告。 */
    const val DUMPRAND = 176
    /** 结束 NOSKIP 区间,恢复此前的跳过设置。 */
    const val ENDNOSKIP = 177
    /** 设置假名显示转换模式(整型模式值)。 */
    const val FORCEKANA = 178
    /** 强制退出程序。 */
    const val FORCE_QUIT = 179
    /** 设置重启标志后强制退出程序。 */
    const val FORCE_QUIT_AND_RESTART = 180
    /** 把当前时间写入 RESULT(YYYYMMDDHHMMSSmmm 数字形式)与 RESULTS(yyyy/MM/dd HH:mm:ss 字符串)。 */
    const val GETTIME = 181
    /** 重置随机数初始状态;UseNewRandom 配置下禁止使用并警告。 */
    const val INITRAND = 182
    /** NOSKIP..ENDNOSKIP 区间内暂时关闭消息跳过(区间结束后恢复)。 */
    const val NOSKIP = 183
    /** 循环播放 sound 目录下指定文件作为 BGM;文件不存在时静默跳过。 */
    const val PLAYBGM = 184
    /** 播放 sound 目录下的音频文件,第二参数为重复次数(最小 1);文件不存在时静默跳过。 */
    const val PLAYSOUND = 185
    /** 正常退出程序。 */
    const val QUIT = 186
    /** 设置重启标志后退出程序(下次启动即重启)。 */
    const val QUIT_AND_RESTART = 187
    /** 以参数为种子重置随机序列;启用 UseNewRandom 配置时仅发出警告并忽略。 */
    const val RANDOMIZE = 188
    /** 设置 BGM 音量。 */
    const val SETBGMVOLUME = 189
    /** 设置全部音效通道的音量。 */
    const val SETSOUNDVOLUME = 190
    /** 设置显示跳过标志(参数非 0 开启);RESULT 记录设置后的状态;跳过期间所有 PRINT 类输出被抑制。 */
    const val SKIPDISP = 191
    /** 直接设置消息跳过(MesSkip)标志,参数非 0 开启。 */
    const val SKIPLOG = 192
    /** 停止 BGM 播放。 */
    const val STOPBGM = 193
    /** 停止自动连续执行模式并关闭显示跳过。 */
    const val STOPCALLTRAIN = 194
    /** 停止全部正在播放的音效。 */
    const val STOPSOUND = 195
    /** 以参数字符串为消息抛出脚本异常,可被 TRYC 结构捕获。 */
    const val THROW = 196
    /** 访问 GameBase 指定的更新检查 URL 比较版本;有新版本时弹窗询问并可选打开下载链接;结果写入 RESULT(0=最新,1=用户拒绝,2=用户接受,3=网络/解析失败,4=被配置禁止,5=无网络)。 */
    const val UPDATECHECK = 197

    // ------------------------------------------------------------------
    // 编译器扩展指令（198..，由 core.compile 追加；不是 isa.yaml 语句，但进
    // NAMES/BY_NAME 以保证反汇编可读、文本汇编可写回。OpcodeTableTest 锁死
    // "29..197 恰为 isa.yaml 的 169 条语句、198..201 恰为下列四条扩展"。
    // VM 分派实现属 M2 执行层。
    // ------------------------------------------------------------------

    /** a=名字槽(SlotAddr), b=实参数, c=返回目标槽 —— CALLFORM/CALLFORMF/CALLEVENT(FORM) 展开为运行期函数表查找。 */
    const val _CALL_BY_NAME = 198

    /** a=名字槽(SlotAddr) —— GOTOFORM/TRYGOTOFORM 展开为运行期函数内 $标签查找（TRY 变体查不到则跳过）。 */
    const val _JUMP_BY_NAME = 199

    /**
     * a=函数内标签下标 —— 按**当前函数**标签表（[FunctionInfo.labelPcs]）换算 pc 后绝对跳转。
     * 标签名→下标的解析归调用方（编译期静态 / 运行期经 ProcessRuntime.resolveLabel 的
     * LabelTable），VM 不接触标签名字。team-lead 裁决 2026-10-18（GOTOFORM 控制转移缺口）。
     */
    const val _JUMP_DYN = 200

    /**
     * a=被调帧内**字符串**槽（NONE = 无返回值）—— `#FUNCTIONS`（str 方法）的返回通道
     * （合同 M3-A §3 A7；[_RET] 仍是 long 通道）。调用位由 `_CALL`/`_CALL_BY_NAME` 的
     * b bit28（STR_RET_FLAG）声明"我要字符串"：置位时 leaveFrame 走 `setStr` 写回。
     * 语义依据（reference/emuera-cs）：`#FUNCTIONS` 的返回值走
     * `ProcessState.MethodReturnValue`（ProcessState.cs:30/463-473 的 `ReturnF`），
     * 消费侧 `SuperUserDefinedMethodTerm.GetStrValue`（SuperUserDefinedMethodTerm.cs:33-38）
     * 对非 `SingleStrTerm` 统一得 `""` —— VM 侧同口径容错（见 Vm.kt `_RETS` 分支注释）。
     */
    const val _RETS = 201

    // ------------------------------------------------------------------
    // 栈码组（202..243，T2-21）。表达式降级的落地目标：把「表达式池下标 → 递归虚分派」
    // 换成直线指令序列。
    //
    // 设计口径（改动前先读 CONTRACT-T2 §2.3 与 §4）：
    // * 两个**类型化**栈（long 栈 / String 栈），各带独立栈指针；每条指令只碰自己那一侧，
    //   运行期没有类型标签、没有装箱。
    // * KDoc 首行写栈效应：`栈：a b → c` = 弹出 b、a（b 在栈顶）再压 c；`∅` 表示不压栈。
    // * 下标与操作数一律**弹出**（保持栈平衡）。需要「下标只求值一次」的读改写走
    //   [_STK_PLUS_VAR_I]：下标个数是编译期常量，DUP 表达不了变长序列。
    // * 跳转的 a 与 [_JUMP] 同口径：相对**下一条**指令的有符号偏移，单位 = 指令条数。
    // * 运行期错误一律经 `Vm.errorAt` 抛 CodeEE，文案与 [com.eratw.port.core.expr.Expr]
    //   对应分支逐字一致（ADR-005：不许顺手修）。
    // ------------------------------------------------------------------

    /** 栈：∅ → const(a)；a = i64 常量池下标。 */
    const val _STK_PUSH_IMM = 202

    /** 栈：∅ → str(a)；a = 字符串池下标。 */
    const val _STK_PUSH_STR = 203

    /** int 栈：v → v v（复制栈顶）。 */
    const val _STK_DUP_I = 204

    /** int 栈：v → ∅。 */
    const val _STK_DROP = 205

    /** String 栈：s → ∅（[205] 的类型化补充：丢弃字符串值必须有对应指令，否则只能借道槽）。 */
    const val _STK_DROP_S = 206

    /** 栈：idx0..idx(b-1) → value；a = 变量名字符串池下标，b = 维数（弹出 b 个下标后读整型）。 */
    const val _STK_PUSH_VAR_I = 207

    /** 栈：idx0..idx(b-1) → value；a = 变量名字符串池下标，b = 维数。 */
    const val _STK_PUSH_VAR_S = 208

    /**
     * 栈：idx0..idx(b-1) → newValue；a = 变量名字符串池下标，b = 维数，c = 增量（±1）。
     *
     * 读改写一体（= `Variables.plusInt`，_EVAL 路径的 `Unary(++/--)` 同源）：下标只求值
     * 一次，与 CONTRACT-T2 §4 红线 6 同口径。[_STK_PUSH_VAR_I] + [_STK_ADD] +
     * [_STK_STORE_VAR_I] 的组合**做不到**这一点（下标在第一次读时就被弹出）。
     */
    const val _STK_PLUS_VAR_I = 209

    /** 栈：idx0..idx(b-1) value → ∅；a = 变量名字符串池下标，b = 维数。 */
    const val _STK_STORE_VAR_I = 210

    /** 栈：idx0..idx(b-1) value → ∅；a = 变量名字符串池下标，b = 维数。 */
    const val _STK_STORE_VAR_S = 211

    /** int 栈：l r → l+r（i64 回绕）。 */
    const val _STK_ADD = 212

    /** int 栈：l r → l-r。 */
    const val _STK_SUB = 213

    /** int 栈：l r → l*r。 */
    const val _STK_MUL = 214

    /** int 栈：l r → l/r；r=0 → CodeEE「除数为 0」，MIN_VALUE/-1 → CodeEE「除算溢出：…」。 */
    const val _STK_DIV = 215

    /** int 栈：l r → l%r；r=0 → CodeEE「除数为 0」（MIN_VALUE%-1 不抛，同 `_MOD`）。 */
    const val _STK_MOD = 216

    /** int 栈：v → -v。 */
    const val _STK_NEG = 217

    /** int 栈：l r → l and r。 */
    const val _STK_BITAND = 218

    /** int 栈：l r → l or r。 */
    const val _STK_BITOR = 219

    /** int 栈：l r → l xor r。 */
    const val _STK_BITXOR = 220

    /** int 栈：l r → l shl r（计数取低 6 位，与 JVM/C# 一致）。 */
    const val _STK_SHL = 221

    /** int 栈：l r → l shr r（算术右移，与 JVM/C# 一致）。 */
    const val _STK_SHR = 222

    /** int 栈：v → v.inv()。 */
    const val _STK_BITNOT = 223

    /** int 栈：l r → (l == r)。 */
    const val _STK_EQ = 224

    /** int 栈：l r → (l != r)。 */
    const val _STK_NE = 225

    /** int 栈：l r → (l < r)。 */
    const val _STK_LT = 226

    /** int 栈：l r → (l <= r)。 */
    const val _STK_LE = 227

    /** int 栈：l r → (l > r)。 */
    const val _STK_GT = 228

    /** int 栈：l r → (l >= r)。 */
    const val _STK_GE = 229

    /** String 栈：l r → (l == r)（Ordinal 比较）。 */
    const val _STK_SEQ = 230

    /** String 栈：l r → (l != r)。 */
    const val _STK_SNE = 231

    /** String 栈：l r → (compare(l,r) < 0)。 */
    const val _STK_SLT = 232

    /** String 栈：l r → (compare(l,r) < 0)；**官方 bug 原样复刻**（`<=` 应判 `c <= 0`）。 */
    const val _STK_SLE = 233

    /** String 栈：l r → (compare(l,r) > 0)。 */
    const val _STK_SGT = 234

    /** String 栈：l r → (compare(l,r) < 0)；**官方 bug 原样复刻**（`>=` 应判 `c >= 0`，方向整个反了）。 */
    const val _STK_SGE = 235

    /** String 栈：l r → l+r。 */
    const val _STK_CONCAT = 236

    /**
     * 栈：s n → s.repeat(n)；n<0 → CodeEE「负数(N)不能与字符串相乘」，
     * n>=10000 → CodeEE「重复次数(N)不能大于等于 10000」，空串或 n==0 → ""。
     * 两种书写 `s * n` 与 `n * s` 降级到同一条指令（求值序由发码方按静态类型对齐）。
     */
    const val _STK_SREPEAT = 237

    /** int 栈 → String 栈：v → v.toString()（口径 = 原版 `ToString()` / `_PRINT_INT`）。 */
    const val _STK_I2S = 238

    /** int 栈：c → ∅；c==0 时压 0 并跳转（`&&` 右位短路），a = 相对偏移。 */
    const val _STK_JZ_POP = 239

    /** int 栈：c → ∅；c!=0 时压 1 并跳转（`||` 右位短路），a = 相对偏移。 */
    const val _STK_JNZ_POP = 240

    /** int 栈：c → ∅；c==0 跳转、否则顺序执行（三目），a = 相对偏移。 */
    const val _STK_JZ_KEEP = 241

    /** int 栈：v → ∅；v 写入帧槽 a（`SlotAddr` 编码，R<n>/G<n>）。 */
    const val _STK_POP_SLOT_I = 242

    /** String 栈：s → ∅；s 写入帧槽 a（`SlotAddr` 编码）。 */
    const val _STK_POP_SLOT_S = 243

    /** 最后一条已定义的操作码（= 栈码组末条）。 */
    const val MAX_OPCODE = _STK_POP_SLOT_S

    /** 最后一条 ERB 语句的操作码（isa.yaml 的 169 条语句占 29..197）。 */
    const val MAX_STATEMENT_OPCODE = 197

    /** 编译器扩展指令的编号区间（198..201，见 [_CALL_BY_NAME]…[_RETS]）。 */
    val COMPILER_EXTENSION_OPS: IntRange = _CALL_BY_NAME.._RETS

    /** 栈码组编号区间（202..243，T2-21）。 */
    val STACK_OPS: IntRange = _STK_PUSH_IMM.._STK_POP_SLOT_S

    /**
     * 全部**非 isa.yaml 语句**的操作码区间（198..243 = 编译器扩展 + 栈码组）。
     *
     * 语义与改造前一致：`isExtension` 回答「这不是 169 条 ERB 语句之一，操作数语义由
     * [Opcode] 各常量的 KDoc 单独定义」。需要区分两类时用 [isStackOp]。
     */
    val EXTENSION_OPS: IntRange = _CALL_BY_NAME.._STK_POP_SLOT_S

    // ------------------------------------------------------------------
    // 调用指令 b 操作数的标志位（CMP-57：单一真源）
    //
    // 这些位是 `_CALL` / `_CALL_BY_NAME` 的 b 操作数契约，被 VM 分派
    // （`Vm.kt`）、编译器发射（`ErbProgramCompiler.kt` 的 TRY_BIT 等别名）与
    // 汇编/测试共用。此前三份字面量各自维护，改动时只改一处会让 VM 与编译器
    // 对同一条指令产生两种解释 —— 统一收在这里。
    // ------------------------------------------------------------------

    /** [_CALL_BY_NAME]/[_JUMP_BY_NAME] 的 b 操作数 bit31 = try 标志（契约 C5/R3）。 */
    const val TRY_FLAG = Int.MIN_VALUE

    /** [_CALL]/[_CALL_BY_NAME] 的 b 操作数 bit30 = JUMP 尾跳标志（契约 R10 API 3）。 */
    const val JUMP_FLAG = 0x40000000

    /** [_CALL_BY_NAME] 的 b 操作数 bit29 = CALLF 家族（允许用户方法）标志（契约 R11/API 5）。 */
    const val CALLF_FLAG = 0x20000000

    /**
     * [_CALL]/[_CALL_BY_NAME] 的 b 操作数 bit28 = 字符串返回标志（合同 M3-A §3 A7）：
     * 置位 = 调用位要**字符串**返回值，被调帧经 [_RETS] 返回（leaveFrame 走 `setStr`）；
     * 不置位 = long 通道（[_RET]）。整型位宽内 b 仍可容纳 2^28 实参，无冲突。
     */
    const val STR_RET_FLAG = 0x10000000

    /** [_CALL]/[_CALL_BY_NAME] 的 b 去掉 try/尾跳/CALLF/str-ret 位后的实参数。 */
    const val ARG_COUNT_MASK = (TRY_FLAG or JUMP_FLAG or CALLF_FLAG or STR_RET_FLAG).inv()

    /** 操作码 -> 名字（反汇编与错误信息用；下标即操作码，0..243 连续无空洞）。 */
    val NAMES: Array<String> = arrayOf(
        "_NOP", "_HALT", "_SET_INT", "_SET_STR", "_MOV_INT", "_MOV_STR",
        "_ADD", "_SUB", "_MUL", "_DIV", "_MOD", "_NEG",
        "_EQ", "_NE", "_LT", "_LE", "_GT", "_GE",
        "_NOT", "_JUMP", "_JUMP_IF_FALSE", "_JUMP_IF_TRUE", "_CALL", "_RET",
        "_PRINT_INT", "_PRINT_STR", "_PRINT_LITERAL", "_EVAL_INT", "_EVAL_STR", "BEGIN",
        "BREAK", "CALL", "CALLEVENT", "CATCH", "CONTINUE", "ELSEIF",
        "ENDIF", "FORCE_BEGIN", "GOTO", "IF", "LOOP", "REND",
        "REPEAT", "RESTART", "RETURN", "RETURNF", "RETURNFORM", "SELECTCASE",
        "SIF", "TRYCALLLIST", "TRYGOTOLIST", "WEND", "WHILE", "ALIGNMENT",
        "AWAIT", "BAR", "CLEARBGIMAGE", "CLEARLINE", "CLEARTEXTBOX", "CUSTOMDRAWLINE",
        "DRAWLINE", "DRAWLINEFORM", "FONTBOLD", "FONTITALIC", "FONTREGULAR", "FONTSTYLE",
        "HTML_PRINT", "HTML_PRINT_ISLAND", "HTML_PRINT_ISLAND_CLEAR", "HTML_TAGSPLIT", "PRINT", "PRINTBUTTON",
        "PRINTBUTTONC", "PRINTPLAIN", "PRINT_ABL", "PRINT_DATA", "PRINT_IMG", "PRINT_ITEM",
        "PRINT_PALAM", "PRINT_RECT", "PRINT_SHOPITEM", "PRINT_SPACE", "REDRAW", "REMOVEBGIMAGE",
        "RESETBGCOLOR", "RESETCOLOR", "REUSELASTLINE", "SETBGCOLOR", "SETBGCOLORBYNAME", "SETBGIMAGE",
        "SETCOLOR", "SETCOLORBYNAME", "SETFONT", "TOOLTIP_CUSTOM", "TOOLTIP_FORMAT", "TOOLTIP_IMG",
        "TOOLTIP_SETCOLOR", "TOOLTIP_SETDELAY", "TOOLTIP_SETDURATION", "TOOLTIP_SETFONT", "TOOLTIP_SETFONTSIZE", "ADDCHARA",
        "ADDCOPYCHARA", "ADDDEFCHARA", "ADDVOIDCHARA", "ARRAYCOPY", "ARRAYREMOVE", "ARRAYSHIFT",
        "ARRAYSORT", "COPYCHARA", "CUPCHECK", "CVARSET", "DELALLCHARA", "ENCODETOUNI",
        "METHOD", "PICKUPCHARA", "POWER", "PRINTCPERLINE", "REF", "RESET_STAIN",
        "SAVENOS", "SET", "SETBIT", "SORTCHARA", "SPLIT", "STRLEN",
        "SWAP", "SWAPCHARA", "TIMES", "UPCHECK", "VARI", "VARS",
        "VARSET", "VARSIZE", "CALLF", "CALLSHARP", "FUNC", "TRYCALLF",
        "BINPUT", "BINPUTS", "INPUT", "INPUTANY", "INPUTMOUSEKEY", "INPUTS",
        "ONEBINPUT", "ONEBINPUTS", "ONEINPUT", "ONEINPUTS", "TINPUT", "TINPUTS",
        "TWAIT", "WAIT", "WAITANYKEY", "DELDATA", "LOADCHARA", "LOADDATA",
        "LOADGLOBAL", "LOADVAR", "PUTFORM", "RESETDATA", "RESETGLOBAL", "SAVECHARA",
        "SAVEDATA", "SAVEGLOBAL", "SAVELOADGAME", "SAVEVAR", "DATA", "DATALIST",
        "DO_NOTHING", "STRDATA", "DEBUGCLEAR", "DEBUGPRINT", "ASSERT", "CALLTRAIN",
        "DOTRAIN", "DT_COLUMN_OPTIONS", "DUMPRAND", "ENDNOSKIP", "FORCEKANA", "FORCE_QUIT",
        "FORCE_QUIT_AND_RESTART", "GETTIME", "INITRAND", "NOSKIP", "PLAYBGM", "PLAYSOUND",
        "QUIT", "QUIT_AND_RESTART", "RANDOMIZE", "SETBGMVOLUME", "SETSOUNDVOLUME", "SKIPDISP",
        "SKIPLOG", "STOPBGM", "STOPCALLTRAIN", "STOPSOUND", "THROW", "UPDATECHECK",
        "_CALL_BY_NAME", "_JUMP_BY_NAME", "_JUMP_DYN", "_RETS",
        // 栈码组（202..243）：顺序必须与常量声明逐条一致，OpcodeTableTest 锁死。
        "_STK_PUSH_IMM", "_STK_PUSH_STR", "_STK_DUP_I", "_STK_DROP", "_STK_DROP_S",
        "_STK_PUSH_VAR_I", "_STK_PUSH_VAR_S", "_STK_PLUS_VAR_I", "_STK_STORE_VAR_I", "_STK_STORE_VAR_S",
        "_STK_ADD", "_STK_SUB", "_STK_MUL", "_STK_DIV", "_STK_MOD", "_STK_NEG",
        "_STK_BITAND", "_STK_BITOR", "_STK_BITXOR", "_STK_SHL", "_STK_SHR", "_STK_BITNOT",
        "_STK_EQ", "_STK_NE", "_STK_LT", "_STK_LE", "_STK_GT", "_STK_GE",
        "_STK_SEQ", "_STK_SNE", "_STK_SLT", "_STK_SLE", "_STK_SGT", "_STK_SGE",
        "_STK_CONCAT", "_STK_SREPEAT", "_STK_I2S",
        "_STK_JZ_POP", "_STK_JNZ_POP", "_STK_JZ_KEEP",
        "_STK_POP_SLOT_I", "_STK_POP_SLOT_S",
    )

    /** 名字 -> 操作码（内部指令 + 169 条语句的规范名 + 4 条编译器扩展 + 42 条栈码）。 */
    val BY_NAME: Map<String, Int> = buildMap {
        for ((i, n) in NAMES.withIndex()) put(n, i)
    }

    /** 每个 kind 段的编号范围（含端点）。 */
    val KIND_RANGES: Map<StatementKind, IntRange> = mapOf(
        StatementKind.FLOW to 29..52,
        StatementKind.DISPLAY to 53..100,
        StatementKind.VARIABLE to 101..133,
        StatementKind.CALL to 134..137,
        StatementKind.INPUT to 138..152,
        StatementKind.IO to 153..165,
        StatementKind.DATA to 166..169,
        StatementKind.DEBUG to 170..171,
        StatementKind.OTHER to 172..197,
    )

    /** 操作码 -> kind；内部指令返回 null。 */
    fun kindOf(op: Int): StatementKind? {
        for ((k, r) in KIND_RANGES) if (op in r) return k
        return null
    }

    /** 是否 VM 内部指令。 */
    fun isInternal(op: Int): Boolean = op in 0 until INTERNAL_COUNT

    /** 是否扩展指令（198..243：编译器扩展 + 栈码组，均不是 ERB 语句）。 */
    fun isExtension(op: Int): Boolean = op in EXTENSION_OPS

    /** 是否栈码指令（202..243，T2-21 的表达式降级目标）。 */
    fun isStackOp(op: Int): Boolean = op in STACK_OPS

    /** 操作码名字；越界返回 `<op:N>`。 */
    fun nameOf(op: Int): String = NAMES.getOrElse(op) { "<op:$op>" }

    /** 语句规范名 -> 操作码；不是语句名（内部指令 / 编译器扩展指令）返回 null。 */
    fun statementOf(name: String): Int? =
        BY_NAME[name]?.takeIf { it in INTERNAL_COUNT..MAX_STATEMENT_OPCODE }
}

enum class StatementKind { FLOW, DISPLAY, VARIABLE, CALL, INPUT, IO, DATA, DEBUG, OTHER }

/**
 * ERB 语句别名（isa.yaml 各条目的 `aliases` 字段中形如标识符的项，共 303 个）。
 *
 * [variant] 是该别名在所属条目 aliases 列表中的序号（0 起，0 通常是规范名）。
 * **它不落盘、也不参与运行期分派**（M4-I i-erbcround 实测：本文件 `resolve()` 只回操作码、
 * 语句池 marker 的 `b` 恒 0，`ErbcWriter` 落的是 `s.code.name`）——
 * 语句身份的唯一载体是语句池实例的 code 名（`Vm.kt:609-619` 取 `program.statement(a)` 交给 sem，
 * `StatementExecutor.kt:560` 读的必是 `ADDSPCHARA`）。PRINT 家族的后缀语义因此由编译期
 * 建成不同的 `FunctionCode`，不是靠本序号在指令流里区分；`ADDCHARA`/`ADDSPCHARA`/`DELCHARA`
 * 三族在指令流层面完全同码 101。
 * TODO(verify): 后缀 -> 行为位 的映射表属语句层（M2 第二批），本表只保证别名可达。
 *
 * 歧义处理：`FORCE_BEGIN` 既是 BEGIN 的别名又是独立语句名，按**规范名优先**归给
 * [Opcode.FORCE_BEGIN]（原版注册表里 FORCE_BEGIN 单独注册过，见 isa.yaml register 行）。
 */
data class StatementAlias(
    val name: String,
    val opcode: Int,
    val variant: Int,
    val canonical: String,
)

object StatementAliases {

    /** 全部别名 + 规范名，按 (opcode, variant) 升序。 */
    val ALL: List<StatementAlias> = listOf(
        StatementAlias("BEGIN", Opcode.BEGIN, 0, "BEGIN"),
        StatementAlias("BREAK", Opcode.BREAK, 0, "BREAK"),
        StatementAlias("JUMP", Opcode.CALL, 0, "CALL"),
        StatementAlias("CALL", Opcode.CALL, 1, "CALL"),
        StatementAlias("TRYJUMP", Opcode.CALL, 2, "CALL"),
        StatementAlias("TRYCALL", Opcode.CALL, 3, "CALL"),
        StatementAlias("JUMPFORM", Opcode.CALL, 4, "CALL"),
        StatementAlias("CALLFORM", Opcode.CALL, 5, "CALL"),
        StatementAlias("TRYJUMPFORM", Opcode.CALL, 6, "CALL"),
        StatementAlias("TRYCALLFORM", Opcode.CALL, 7, "CALL"),
        StatementAlias("TRYCJUMP", Opcode.CALL, 8, "CALL"),
        StatementAlias("TRYCCALL", Opcode.CALL, 9, "CALL"),
        StatementAlias("TRYCJUMPFORM", Opcode.CALL, 10, "CALL"),
        StatementAlias("TRYCCALLFORM", Opcode.CALL, 11, "CALL"),
        StatementAlias("CALLEVENT", Opcode.CALLEVENT, 0, "CALLEVENT"),
        StatementAlias("CATCH", Opcode.CATCH, 0, "CATCH"),
        StatementAlias("CONTINUE", Opcode.CONTINUE, 0, "CONTINUE"),
        StatementAlias("ELSE", Opcode.ELSEIF, 0, "ELSEIF"),
        StatementAlias("ELSEIF", Opcode.ELSEIF, 1, "ELSEIF"),
        StatementAlias("CASE", Opcode.ELSEIF, 2, "ELSEIF"),
        StatementAlias("CASEELSE", Opcode.ELSEIF, 3, "ELSEIF"),
        StatementAlias("ENDIF", Opcode.ENDIF, 0, "ENDIF"),
        StatementAlias("ENDSELECT", Opcode.ENDIF, 1, "ENDIF"),
        StatementAlias("DO", Opcode.ENDIF, 2, "ENDIF"),
        StatementAlias("ENDCATCH", Opcode.ENDIF, 3, "ENDIF"),
        StatementAlias("ENDFUNC", Opcode.ENDIF, 4, "ENDIF"),
        StatementAlias("FORCE_BEGIN", Opcode.FORCE_BEGIN, 0, "FORCE_BEGIN"),
        StatementAlias("GOTO", Opcode.GOTO, 0, "GOTO"),
        StatementAlias("TRYGOTO", Opcode.GOTO, 1, "GOTO"),
        StatementAlias("GOTOFORM", Opcode.GOTO, 2, "GOTO"),
        StatementAlias("TRYGOTOFORM", Opcode.GOTO, 3, "GOTO"),
        StatementAlias("TRYCGOTO", Opcode.GOTO, 4, "GOTO"),
        StatementAlias("TRYCGOTOFORM", Opcode.GOTO, 5, "GOTO"),
        StatementAlias("IF", Opcode.IF, 0, "IF"),
        StatementAlias("LOOP", Opcode.LOOP, 0, "LOOP"),
        StatementAlias("REND", Opcode.REND, 0, "REND"),
        StatementAlias("NEXT", Opcode.REND, 1, "REND"),
        StatementAlias("REPEAT", Opcode.REPEAT, 0, "REPEAT"),
        StatementAlias("FOR", Opcode.REPEAT, 1, "REPEAT"),
        StatementAlias("RESTART", Opcode.RESTART, 0, "RESTART"),
        StatementAlias("RETURN", Opcode.RETURN, 0, "RETURN"),
        StatementAlias("RETURNF", Opcode.RETURNF, 0, "RETURNF"),
        StatementAlias("RETURNFORM", Opcode.RETURNFORM, 0, "RETURNFORM"),
        StatementAlias("SELECTCASE", Opcode.SELECTCASE, 0, "SELECTCASE"),
        StatementAlias("SIF", Opcode.SIF, 0, "SIF"),
        StatementAlias("TRYCALLLIST", Opcode.TRYCALLLIST, 0, "TRYCALLLIST"),
        StatementAlias("TRYJUMPLIST", Opcode.TRYCALLLIST, 1, "TRYCALLLIST"),
        StatementAlias("TRYGOTOLIST", Opcode.TRYGOTOLIST, 0, "TRYGOTOLIST"),
        StatementAlias("WEND", Opcode.WEND, 0, "WEND"),
        StatementAlias("WHILE", Opcode.WHILE, 0, "WHILE"),
        StatementAlias("ALIGNMENT", Opcode.ALIGNMENT, 0, "ALIGNMENT"),
        StatementAlias("AWAIT", Opcode.AWAIT, 0, "AWAIT"),
        StatementAlias("BAR", Opcode.BAR, 0, "BAR"),
        StatementAlias("BARL", Opcode.BAR, 1, "BAR"),
        StatementAlias("CLEARBGIMAGE", Opcode.CLEARBGIMAGE, 0, "CLEARBGIMAGE"),
        StatementAlias("CLEARLINE", Opcode.CLEARLINE, 0, "CLEARLINE"),
        StatementAlias("CLEARTEXTBOX", Opcode.CLEARTEXTBOX, 0, "CLEARTEXTBOX"),
        StatementAlias("CUSTOMDRAWLINE", Opcode.CUSTOMDRAWLINE, 0, "CUSTOMDRAWLINE"),
        StatementAlias("DRAWLINE", Opcode.DRAWLINE, 0, "DRAWLINE"),
        StatementAlias("DRAWLINEFORM", Opcode.DRAWLINEFORM, 0, "DRAWLINEFORM"),
        StatementAlias("FONTBOLD", Opcode.FONTBOLD, 0, "FONTBOLD"),
        StatementAlias("FONTITALIC", Opcode.FONTITALIC, 0, "FONTITALIC"),
        StatementAlias("FONTREGULAR", Opcode.FONTREGULAR, 0, "FONTREGULAR"),
        StatementAlias("FONTSTYLE", Opcode.FONTSTYLE, 0, "FONTSTYLE"),
        StatementAlias("HTML_PRINT", Opcode.HTML_PRINT, 0, "HTML_PRINT"),
        StatementAlias("HTML_PRINT_ISLAND", Opcode.HTML_PRINT_ISLAND, 0, "HTML_PRINT_ISLAND"),
        StatementAlias("HTML_PRINT_ISLAND_CLEAR", Opcode.HTML_PRINT_ISLAND_CLEAR, 0, "HTML_PRINT_ISLAND_CLEAR"),
        StatementAlias("HTML_TAGSPLIT", Opcode.HTML_TAGSPLIT, 0, "HTML_TAGSPLIT"),
        StatementAlias("PRINT", Opcode.PRINT, 0, "PRINT"),
        StatementAlias("PRINTL", Opcode.PRINT, 1, "PRINT"),
        StatementAlias("PRINTW", Opcode.PRINT, 2, "PRINT"),
        StatementAlias("PRINTV", Opcode.PRINT, 3, "PRINT"),
        StatementAlias("PRINTVL", Opcode.PRINT, 4, "PRINT"),
        StatementAlias("PRINTVW", Opcode.PRINT, 5, "PRINT"),
        StatementAlias("PRINTS", Opcode.PRINT, 6, "PRINT"),
        StatementAlias("PRINTSL", Opcode.PRINT, 7, "PRINT"),
        StatementAlias("PRINTSW", Opcode.PRINT, 8, "PRINT"),
        StatementAlias("PRINTFORM", Opcode.PRINT, 9, "PRINT"),
        StatementAlias("PRINTFORML", Opcode.PRINT, 10, "PRINT"),
        StatementAlias("PRINTFORMW", Opcode.PRINT, 11, "PRINT"),
        StatementAlias("PRINTFORMS", Opcode.PRINT, 12, "PRINT"),
        StatementAlias("PRINTFORMSL", Opcode.PRINT, 13, "PRINT"),
        StatementAlias("PRINTFORMSW", Opcode.PRINT, 14, "PRINT"),
        StatementAlias("PRINTK", Opcode.PRINT, 15, "PRINT"),
        StatementAlias("PRINTKL", Opcode.PRINT, 16, "PRINT"),
        StatementAlias("PRINTKW", Opcode.PRINT, 17, "PRINT"),
        StatementAlias("PRINTVK", Opcode.PRINT, 18, "PRINT"),
        StatementAlias("PRINTVKL", Opcode.PRINT, 19, "PRINT"),
        StatementAlias("PRINTVKW", Opcode.PRINT, 20, "PRINT"),
        StatementAlias("PRINTSK", Opcode.PRINT, 21, "PRINT"),
        StatementAlias("PRINTSKL", Opcode.PRINT, 22, "PRINT"),
        StatementAlias("PRINTSKW", Opcode.PRINT, 23, "PRINT"),
        StatementAlias("PRINTFORMK", Opcode.PRINT, 24, "PRINT"),
        StatementAlias("PRINTFORMKL", Opcode.PRINT, 25, "PRINT"),
        StatementAlias("PRINTFORMKW", Opcode.PRINT, 26, "PRINT"),
        StatementAlias("PRINTFORMSK", Opcode.PRINT, 27, "PRINT"),
        StatementAlias("PRINTFORMSKL", Opcode.PRINT, 28, "PRINT"),
        StatementAlias("PRINTFORMSKW", Opcode.PRINT, 29, "PRINT"),
        StatementAlias("PRINTD", Opcode.PRINT, 30, "PRINT"),
        StatementAlias("PRINTDL", Opcode.PRINT, 31, "PRINT"),
        StatementAlias("PRINTDW", Opcode.PRINT, 32, "PRINT"),
        StatementAlias("PRINTVD", Opcode.PRINT, 33, "PRINT"),
        StatementAlias("PRINTVDL", Opcode.PRINT, 34, "PRINT"),
        StatementAlias("PRINTVDW", Opcode.PRINT, 35, "PRINT"),
        StatementAlias("PRINTSD", Opcode.PRINT, 36, "PRINT"),
        StatementAlias("PRINTSDL", Opcode.PRINT, 37, "PRINT"),
        StatementAlias("PRINTSDW", Opcode.PRINT, 38, "PRINT"),
        StatementAlias("PRINTFORMD", Opcode.PRINT, 39, "PRINT"),
        StatementAlias("PRINTFORMDL", Opcode.PRINT, 40, "PRINT"),
        StatementAlias("PRINTFORMDW", Opcode.PRINT, 41, "PRINT"),
        StatementAlias("PRINTFORMSD", Opcode.PRINT, 42, "PRINT"),
        StatementAlias("PRINTFORMSDL", Opcode.PRINT, 43, "PRINT"),
        StatementAlias("PRINTFORMSDW", Opcode.PRINT, 44, "PRINT"),
        StatementAlias("PRINTSINGLE", Opcode.PRINT, 45, "PRINT"),
        StatementAlias("PRINTSINGLEV", Opcode.PRINT, 46, "PRINT"),
        StatementAlias("PRINTSINGLES", Opcode.PRINT, 47, "PRINT"),
        StatementAlias("PRINTSINGLEFORM", Opcode.PRINT, 48, "PRINT"),
        StatementAlias("PRINTSINGLEFORMS", Opcode.PRINT, 49, "PRINT"),
        StatementAlias("PRINTSINGLEK", Opcode.PRINT, 50, "PRINT"),
        StatementAlias("PRINTSINGLEVK", Opcode.PRINT, 51, "PRINT"),
        StatementAlias("PRINTSINGLESK", Opcode.PRINT, 52, "PRINT"),
        StatementAlias("PRINTSINGLEFORMK", Opcode.PRINT, 53, "PRINT"),
        StatementAlias("PRINTSINGLEFORMSK", Opcode.PRINT, 54, "PRINT"),
        StatementAlias("PRINTSINGLED", Opcode.PRINT, 55, "PRINT"),
        StatementAlias("PRINTSINGLEVD", Opcode.PRINT, 56, "PRINT"),
        StatementAlias("PRINTSINGLESD", Opcode.PRINT, 57, "PRINT"),
        StatementAlias("PRINTSINGLEFORMD", Opcode.PRINT, 58, "PRINT"),
        StatementAlias("PRINTSINGLEFORMSD", Opcode.PRINT, 59, "PRINT"),
        StatementAlias("PRINTC", Opcode.PRINT, 60, "PRINT"),
        StatementAlias("PRINTLC", Opcode.PRINT, 61, "PRINT"),
        StatementAlias("PRINTFORMC", Opcode.PRINT, 62, "PRINT"),
        StatementAlias("PRINTFORMLC", Opcode.PRINT, 63, "PRINT"),
        StatementAlias("PRINTCK", Opcode.PRINT, 64, "PRINT"),
        StatementAlias("PRINTLCK", Opcode.PRINT, 65, "PRINT"),
        StatementAlias("PRINTFORMCK", Opcode.PRINT, 66, "PRINT"),
        StatementAlias("PRINTFORMLCK", Opcode.PRINT, 67, "PRINT"),
        StatementAlias("PRINTCD", Opcode.PRINT, 68, "PRINT"),
        StatementAlias("PRINTLCD", Opcode.PRINT, 69, "PRINT"),
        StatementAlias("PRINTFORMCD", Opcode.PRINT, 70, "PRINT"),
        StatementAlias("PRINTFORMLCD", Opcode.PRINT, 71, "PRINT"),
        StatementAlias("PRINTN", Opcode.PRINT, 72, "PRINT"),
        StatementAlias("PRINTVN", Opcode.PRINT, 73, "PRINT"),
        StatementAlias("PRINTSN", Opcode.PRINT, 74, "PRINT"),
        StatementAlias("PRINTFORMN", Opcode.PRINT, 75, "PRINT"),
        StatementAlias("PRINTFORMSN", Opcode.PRINT, 76, "PRINT"),
        StatementAlias("PRINTBUTTON", Opcode.PRINTBUTTON, 0, "PRINTBUTTON"),
        StatementAlias("PRINTBUTTONC", Opcode.PRINTBUTTONC, 0, "PRINTBUTTONC"),
        StatementAlias("PRINTBUTTONLC", Opcode.PRINTBUTTONC, 1, "PRINTBUTTONC"),
        StatementAlias("PRINTPLAIN", Opcode.PRINTPLAIN, 0, "PRINTPLAIN"),
        StatementAlias("PRINTPLAINFORM", Opcode.PRINTPLAIN, 1, "PRINTPLAIN"),
        StatementAlias("PRINT_ABL", Opcode.PRINT_ABL, 0, "PRINT_ABL"),
        StatementAlias("PRINT_TALENT", Opcode.PRINT_ABL, 1, "PRINT_ABL"),
        StatementAlias("PRINT_MARK", Opcode.PRINT_ABL, 2, "PRINT_ABL"),
        StatementAlias("PRINT_EXP", Opcode.PRINT_ABL, 3, "PRINT_ABL"),
        StatementAlias("PRINTDATA", Opcode.PRINT_DATA, 0, "PRINT_DATA"),
        StatementAlias("PRINTDATAL", Opcode.PRINT_DATA, 1, "PRINT_DATA"),
        StatementAlias("PRINTDATAW", Opcode.PRINT_DATA, 2, "PRINT_DATA"),
        StatementAlias("PRINTDATAK", Opcode.PRINT_DATA, 3, "PRINT_DATA"),
        StatementAlias("PRINTDATAKL", Opcode.PRINT_DATA, 4, "PRINT_DATA"),
        StatementAlias("PRINTDATAKW", Opcode.PRINT_DATA, 5, "PRINT_DATA"),
        StatementAlias("PRINTDATAD", Opcode.PRINT_DATA, 6, "PRINT_DATA"),
        StatementAlias("PRINTDATADL", Opcode.PRINT_DATA, 7, "PRINT_DATA"),
        StatementAlias("PRINTDATADW", Opcode.PRINT_DATA, 8, "PRINT_DATA"),
        StatementAlias("PRINT_IMG", Opcode.PRINT_IMG, 0, "PRINT_IMG"),
        StatementAlias("PRINT_ITEM", Opcode.PRINT_ITEM, 0, "PRINT_ITEM"),
        StatementAlias("PRINT_PALAM", Opcode.PRINT_PALAM, 0, "PRINT_PALAM"),
        StatementAlias("PRINT_RECT", Opcode.PRINT_RECT, 0, "PRINT_RECT"),
        StatementAlias("PRINT_SHOPITEM", Opcode.PRINT_SHOPITEM, 0, "PRINT_SHOPITEM"),
        StatementAlias("PRINT_SPACE", Opcode.PRINT_SPACE, 0, "PRINT_SPACE"),
        StatementAlias("REDRAW", Opcode.REDRAW, 0, "REDRAW"),
        StatementAlias("REMOVEBGIMAGE", Opcode.REMOVEBGIMAGE, 0, "REMOVEBGIMAGE"),
        StatementAlias("RESETBGCOLOR", Opcode.RESETBGCOLOR, 0, "RESETBGCOLOR"),
        StatementAlias("RESETCOLOR", Opcode.RESETCOLOR, 0, "RESETCOLOR"),
        StatementAlias("REUSELASTLINE", Opcode.REUSELASTLINE, 0, "REUSELASTLINE"),
        StatementAlias("SETBGCOLOR", Opcode.SETBGCOLOR, 0, "SETBGCOLOR"),
        StatementAlias("SETBGCOLORBYNAME", Opcode.SETBGCOLORBYNAME, 0, "SETBGCOLORBYNAME"),
        StatementAlias("SETBGIMAGE", Opcode.SETBGIMAGE, 0, "SETBGIMAGE"),
        StatementAlias("SETCOLOR", Opcode.SETCOLOR, 0, "SETCOLOR"),
        StatementAlias("SETCOLORBYNAME", Opcode.SETCOLORBYNAME, 0, "SETCOLORBYNAME"),
        StatementAlias("SETFONT", Opcode.SETFONT, 0, "SETFONT"),
        StatementAlias("TOOLTIP_CUSTOM", Opcode.TOOLTIP_CUSTOM, 0, "TOOLTIP_CUSTOM"),
        StatementAlias("TOOLTIP_FORMAT", Opcode.TOOLTIP_FORMAT, 0, "TOOLTIP_FORMAT"),
        StatementAlias("TOOLTIP_IMG", Opcode.TOOLTIP_IMG, 0, "TOOLTIP_IMG"),
        StatementAlias("TOOLTIP_SETCOLOR", Opcode.TOOLTIP_SETCOLOR, 0, "TOOLTIP_SETCOLOR"),
        StatementAlias("TOOLTIP_SETDELAY", Opcode.TOOLTIP_SETDELAY, 0, "TOOLTIP_SETDELAY"),
        StatementAlias("TOOLTIP_SETDURATION", Opcode.TOOLTIP_SETDURATION, 0, "TOOLTIP_SETDURATION"),
        StatementAlias("TOOLTIP_SETFONT", Opcode.TOOLTIP_SETFONT, 0, "TOOLTIP_SETFONT"),
        StatementAlias("TOOLTIP_SETFONTSIZE", Opcode.TOOLTIP_SETFONTSIZE, 0, "TOOLTIP_SETFONTSIZE"),
        StatementAlias("ADDCHARA", Opcode.ADDCHARA, 0, "ADDCHARA"),
        StatementAlias("ADDSPCHARA", Opcode.ADDCHARA, 1, "ADDCHARA"),
        StatementAlias("DELCHARA", Opcode.ADDCHARA, 2, "ADDCHARA"),
        StatementAlias("ADDCOPYCHARA", Opcode.ADDCOPYCHARA, 0, "ADDCOPYCHARA"),
        StatementAlias("ADDDEFCHARA", Opcode.ADDDEFCHARA, 0, "ADDDEFCHARA"),
        StatementAlias("ADDVOIDCHARA", Opcode.ADDVOIDCHARA, 0, "ADDVOIDCHARA"),
        StatementAlias("ARRAYCOPY", Opcode.ARRAYCOPY, 0, "ARRAYCOPY"),
        StatementAlias("ARRAYREMOVE", Opcode.ARRAYREMOVE, 0, "ARRAYREMOVE"),
        StatementAlias("ARRAYSHIFT", Opcode.ARRAYSHIFT, 0, "ARRAYSHIFT"),
        StatementAlias("ARRAYSORT", Opcode.ARRAYSORT, 0, "ARRAYSORT"),
        StatementAlias("COPYCHARA", Opcode.COPYCHARA, 0, "COPYCHARA"),
        StatementAlias("CUPCHECK", Opcode.CUPCHECK, 0, "CUPCHECK"),
        StatementAlias("CVARSET", Opcode.CVARSET, 0, "CVARSET"),
        StatementAlias("DELALLCHARA", Opcode.DELALLCHARA, 0, "DELALLCHARA"),
        StatementAlias("ENCODETOUNI", Opcode.ENCODETOUNI, 0, "ENCODETOUNI"),
        StatementAlias("PICKUPCHARA", Opcode.PICKUPCHARA, 0, "PICKUPCHARA"),
        StatementAlias("POWER", Opcode.POWER, 0, "POWER"),
        StatementAlias("PRINTCPERLINE", Opcode.PRINTCPERLINE, 0, "PRINTCPERLINE"),
        StatementAlias("REF", Opcode.REF, 0, "REF"),
        StatementAlias("REFBYNAME", Opcode.REF, 1, "REF"),
        StatementAlias("RESET_STAIN", Opcode.RESET_STAIN, 0, "RESET_STAIN"),
        StatementAlias("SAVENOS", Opcode.SAVENOS, 0, "SAVENOS"),
        StatementAlias("SET", Opcode.SET, 0, "SET"),
        StatementAlias("SETBIT", Opcode.SETBIT, 0, "SETBIT"),
        StatementAlias("CLEARBIT", Opcode.SETBIT, 1, "SETBIT"),
        StatementAlias("INVERTBIT", Opcode.SETBIT, 2, "SETBIT"),
        StatementAlias("SORTCHARA", Opcode.SORTCHARA, 0, "SORTCHARA"),
        StatementAlias("SPLIT", Opcode.SPLIT, 0, "SPLIT"),
        StatementAlias("STRLEN", Opcode.STRLEN, 0, "STRLEN"),
        StatementAlias("STRLENFORM", Opcode.STRLEN, 1, "STRLEN"),
        StatementAlias("STRLENU", Opcode.STRLEN, 2, "STRLEN"),
        StatementAlias("STRLENFORMU", Opcode.STRLEN, 3, "STRLEN"),
        StatementAlias("SWAP", Opcode.SWAP, 0, "SWAP"),
        StatementAlias("SWAPCHARA", Opcode.SWAPCHARA, 0, "SWAPCHARA"),
        StatementAlias("TIMES", Opcode.TIMES, 0, "TIMES"),
        StatementAlias("UPCHECK", Opcode.UPCHECK, 0, "UPCHECK"),
        StatementAlias("VARI", Opcode.VARI, 0, "VARI"),
        StatementAlias("VARS", Opcode.VARS, 0, "VARS"),
        StatementAlias("VARSET", Opcode.VARSET, 0, "VARSET"),
        StatementAlias("VARSIZE", Opcode.VARSIZE, 0, "VARSIZE"),
        StatementAlias("CALLF", Opcode.CALLF, 0, "CALLF"),
        StatementAlias("CALLFORMF", Opcode.CALLF, 1, "CALLF"),
        StatementAlias("CALLSHARP", Opcode.CALLSHARP, 0, "CALLSHARP"),
        StatementAlias("FUNC", Opcode.FUNC, 0, "FUNC"),
        StatementAlias("TRYCALLF", Opcode.TRYCALLF, 0, "TRYCALLF"),
        StatementAlias("TRYCALLFORMF", Opcode.TRYCALLF, 1, "TRYCALLF"),
        StatementAlias("BINPUT", Opcode.BINPUT, 0, "BINPUT"),
        StatementAlias("BINPUTS", Opcode.BINPUTS, 0, "BINPUTS"),
        StatementAlias("INPUT", Opcode.INPUT, 0, "INPUT"),
        StatementAlias("INPUTANY", Opcode.INPUTANY, 0, "INPUTANY"),
        StatementAlias("INPUTMOUSEKEY", Opcode.INPUTMOUSEKEY, 0, "INPUTMOUSEKEY"),
        StatementAlias("INPUTS", Opcode.INPUTS, 0, "INPUTS"),
        StatementAlias("ONEBINPUT", Opcode.ONEBINPUT, 0, "ONEBINPUT"),
        StatementAlias("ONEBINPUTS", Opcode.ONEBINPUTS, 0, "ONEBINPUTS"),
        StatementAlias("ONEINPUT", Opcode.ONEINPUT, 0, "ONEINPUT"),
        StatementAlias("ONEINPUTS", Opcode.ONEINPUTS, 0, "ONEINPUTS"),
        StatementAlias("TINPUT", Opcode.TINPUT, 0, "TINPUT"),
        StatementAlias("TONEINPUT", Opcode.TINPUT, 1, "TINPUT"),
        StatementAlias("TINPUTS", Opcode.TINPUTS, 0, "TINPUTS"),
        StatementAlias("TONEINPUTS", Opcode.TINPUTS, 1, "TINPUTS"),
        StatementAlias("TWAIT", Opcode.TWAIT, 0, "TWAIT"),
        StatementAlias("WAIT", Opcode.WAIT, 0, "WAIT"),
        StatementAlias("FORCEWAIT", Opcode.WAIT, 1, "WAIT"),
        StatementAlias("WAITANYKEY", Opcode.WAITANYKEY, 0, "WAITANYKEY"),
        StatementAlias("DELDATA", Opcode.DELDATA, 0, "DELDATA"),
        StatementAlias("LOADCHARA", Opcode.LOADCHARA, 0, "LOADCHARA"),
        StatementAlias("LOADDATA", Opcode.LOADDATA, 0, "LOADDATA"),
        StatementAlias("LOADGLOBAL", Opcode.LOADGLOBAL, 0, "LOADGLOBAL"),
        StatementAlias("LOADVAR", Opcode.LOADVAR, 0, "LOADVAR"),
        StatementAlias("PUTFORM", Opcode.PUTFORM, 0, "PUTFORM"),
        StatementAlias("RESETDATA", Opcode.RESETDATA, 0, "RESETDATA"),
        StatementAlias("RESETGLOBAL", Opcode.RESETGLOBAL, 0, "RESETGLOBAL"),
        StatementAlias("SAVECHARA", Opcode.SAVECHARA, 0, "SAVECHARA"),
        StatementAlias("SAVEDATA", Opcode.SAVEDATA, 0, "SAVEDATA"),
        StatementAlias("SAVEGLOBAL", Opcode.SAVEGLOBAL, 0, "SAVEGLOBAL"),
        StatementAlias("SAVEGAME", Opcode.SAVELOADGAME, 0, "SAVELOADGAME"),
        StatementAlias("LOADGAME", Opcode.SAVELOADGAME, 1, "SAVELOADGAME"),
        StatementAlias("SAVEVAR", Opcode.SAVEVAR, 0, "SAVEVAR"),
        StatementAlias("DATA", Opcode.DATA, 0, "DATA"),
        StatementAlias("DATAFORM", Opcode.DATA, 1, "DATA"),
        StatementAlias("DATALIST", Opcode.DATALIST, 0, "DATALIST"),
        StatementAlias("ENDLIST", Opcode.DATALIST, 1, "DATALIST"),
        StatementAlias("ENDDATA", Opcode.DO_NOTHING, 0, "DO_NOTHING"),
        StatementAlias("STRDATA", Opcode.STRDATA, 0, "STRDATA"),
        StatementAlias("DEBUGCLEAR", Opcode.DEBUGCLEAR, 0, "DEBUGCLEAR"),
        StatementAlias("DEBUGPRINT", Opcode.DEBUGPRINT, 0, "DEBUGPRINT"),
        StatementAlias("DEBUGPRINTL", Opcode.DEBUGPRINT, 1, "DEBUGPRINT"),
        StatementAlias("DEBUGPRINTFORM", Opcode.DEBUGPRINT, 2, "DEBUGPRINT"),
        StatementAlias("DEBUGPRINTFORML", Opcode.DEBUGPRINT, 3, "DEBUGPRINT"),
        StatementAlias("ASSERT", Opcode.ASSERT, 0, "ASSERT"),
        StatementAlias("CALLTRAIN", Opcode.CALLTRAIN, 0, "CALLTRAIN"),
        StatementAlias("DOTRAIN", Opcode.DOTRAIN, 0, "DOTRAIN"),
        StatementAlias("DT_COLUMN_OPTIONS", Opcode.DT_COLUMN_OPTIONS, 0, "DT_COLUMN_OPTIONS"),
        StatementAlias("DUMPRAND", Opcode.DUMPRAND, 0, "DUMPRAND"),
        StatementAlias("ENDNOSKIP", Opcode.ENDNOSKIP, 0, "ENDNOSKIP"),
        StatementAlias("FORCEKANA", Opcode.FORCEKANA, 0, "FORCEKANA"),
        StatementAlias("FORCE_QUIT", Opcode.FORCE_QUIT, 0, "FORCE_QUIT"),
        StatementAlias("FORCE_QUIT_AND_RESTART", Opcode.FORCE_QUIT_AND_RESTART, 0, "FORCE_QUIT_AND_RESTART"),
        StatementAlias("GETTIME", Opcode.GETTIME, 0, "GETTIME"),
        StatementAlias("INITRAND", Opcode.INITRAND, 0, "INITRAND"),
        StatementAlias("NOSKIP", Opcode.NOSKIP, 0, "NOSKIP"),
        StatementAlias("PLAYBGM", Opcode.PLAYBGM, 0, "PLAYBGM"),
        StatementAlias("PLAYSOUND", Opcode.PLAYSOUND, 0, "PLAYSOUND"),
        StatementAlias("QUIT", Opcode.QUIT, 0, "QUIT"),
        StatementAlias("QUIT_AND_RESTART", Opcode.QUIT_AND_RESTART, 0, "QUIT_AND_RESTART"),
        StatementAlias("RANDOMIZE", Opcode.RANDOMIZE, 0, "RANDOMIZE"),
        StatementAlias("SETBGMVOLUME", Opcode.SETBGMVOLUME, 0, "SETBGMVOLUME"),
        StatementAlias("SETSOUNDVOLUME", Opcode.SETSOUNDVOLUME, 0, "SETSOUNDVOLUME"),
        StatementAlias("SKIPDISP", Opcode.SKIPDISP, 0, "SKIPDISP"),
        StatementAlias("SKIPLOG", Opcode.SKIPLOG, 0, "SKIPLOG"),
        StatementAlias("STOPBGM", Opcode.STOPBGM, 0, "STOPBGM"),
        StatementAlias("STOPCALLTRAIN", Opcode.STOPCALLTRAIN, 0, "STOPCALLTRAIN"),
        StatementAlias("STOPSOUND", Opcode.STOPSOUND, 0, "STOPSOUND"),
        StatementAlias("THROW", Opcode.THROW, 0, "THROW"),
        StatementAlias("UPDATECHECK", Opcode.UPDATECHECK, 0, "UPDATECHECK"),
    )

    /** 名字（别名或规范名）-> 语句；名字唯一。 */
    val BY_NAME: Map<String, StatementAlias> = ALL.associateBy { it.name }

    /** 每条语句的全部别名，按 variant 升序。 */
    val BY_OPCODE: Map<Int, List<StatementAlias>> = ALL.groupBy { it.opcode }

    /** ERB 书写的语句名 -> 操作码；未知名返回 null。 */
    fun resolve(mnemonic: String): Int? = BY_NAME[mnemonic]?.opcode
}

