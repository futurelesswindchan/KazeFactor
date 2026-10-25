# KazeFactor 项目交接备忘录

> 最后更新：2026-10-25｜当前阶段：选项 A（core-domain 纯逻辑），差口令码模块

## 1. 项目是什么

《元气骑士》联机模式「挑战因子」自动刷新工具（Android）。玩家手动进入因子配置界面 → 悬浮窗选配方 → 点开始 → 无障碍截图识别 4 个槽位 → 未命中就点刷新，直到命中。

- 开源：代码 GPL-3.0，发布到 GitHub + B站；不做防检测、不做自动更新
- 因子图标已获 wiki 工作组与凉屋官方客服授权放进仓库，但必须注明：不适用开源协议，版权归凉屋游戏（见 ICON_LICENSE.txt）
- 主人是 vibecoding 全栈，不熟 Android；风风酱主力写代码

## 2. 主人已确认的需求与决策

- 必须由玩家手动进入因子界面才能启动识别
- 配方：想要 / 不想要 / 可选（至少 m 个）三种语义；配方保存 + 口令码分享；用户自行命名
- 自动纠正：超过 4 个、想要与不想要冲突、想要之间冲突、排除过多、不可能组合
- **R6 低命中率 = 仅小悬浮字提示，不阻塞**（选择权交给用户）
- 刷新频率上限 1~10 次/秒，用户自己设置
- factors.json 每个因子带效果描述（以后悬浮窗展示用）
- 实机特征：**刷新没有动画**，按钮也没有形变，点完可以直接截图识别
- 测试机分辨率 2388×1080（目前只有这一台）

## 3. 工程环境（已验证可构建）

- 路径 `D:\AndroidStudioProjects\KazeFactor`，包名 `io.github.futurelesswindchan.kazefactor`
- 模块：`app`（Compose 模板，还没改过）、`core-domain`（纯 Kotlin JVM：java-library + kotlin.jvm + kotlin.serialization）
- Gradle 9.6.0、AGP 9.4.1（**内置 Kotlin，app 不要加 kotlin-android 插件**）、Kotlin 2.2.10、Compose BOM 2026.02.01、compile/targetSdk 37、minSdk 26
- 新增依赖：kotlinx-serialization-json 1.8.0、kotlin-test（都在 libs.versions.toml）
- 已删除 `gradle/gradle-daemon-jvm.properties`（它要求下载 JDK 25，网络失败）；gradle.properties 加了 `org.gradle.java.installations.auto-download=false`
- 终端是 PowerShell 5.1：不支持 `&&`，用 `;`。readFile 读不了 `.kts`，要用 `Get-Content`
- 测试命令：`.\gradlew.bat :core-domain:test --console=plain`；结果看 `core-domain\build\test-results\test\*.xml`

## 4. Git 状态

- 已提交：899a5cf（模板）→ 06037db（阶段1 基础设施）→ 535d8fd（图标、截图、数据模型更新）
- **未提交**：rule/ 下的规则引擎、概率计算和测试，Recipe/SlotConstraint/FactorDatabase 的修改，以及本文件

## 5. 资源位置

- 因子图标原图：`texture2DEng/`（59 个 PNG，snake_case 命名），已复制到 `app/src/main/assets/factors/icons/`
- 实机截图：`screenshot/`（disable01~07 问号状态，enable01~40 已刷新状态，背景有模式/难度干扰），已复制到 `app/src/androidTest/assets/screenshots/`
- 因子数据：`core-domain/src/test/resources/factors.json`（**目前只在 test 资源里**，以后要挪到 main 或 app assets）
  - 字段：id(1~59，发布后永不改)、key、name、nameEn、description、descriptionEn、category、iconFileName
  - 11 组冲突对写在 `conflicts`；`exclusiveGroups` 为空
- 命名标准以主人给的 CSV 为准（中英文名、文件名、中英文描述），factors.json 已按它生成

## 6. 游戏规则（来自灰机 wiki，已编码）

- 59 个普通因子：大恶 6（id 1~6）、中恶 14（7~20）、小恶 8（21~28）、中性 15（29~43）、小良 7（44~50）、大良 9（51~59）
- 枚举名：MAJOR_BAD / MODERATE_BAD / MINOR_BAD / NEUTRAL / MINOR_GOOD / MAJOR_GOOD
- 槽位：第1位 大恶/中恶/小恶；第2位 中恶/小恶/中性；第3位 小恶/中性/小良/大良；第4位 小良/大良
- 17 种合法类别序列在 `SlotConstraint` 枚举（PATTERN_1~17，按槽位 1→4）

## 7. 代码现状（core-domain）

```
model/
  Factor.kt          Factor + FactorCategory，canAppearInSlot()
  FactorDatabase.kt  查询、areConflicting、getAllFactorIds
  Recipe.kt          wanted/unwanted/optional 都是 Set<Int>，minOptional；matches(4个id)
                     init 只拦负数，业务错误交给 RuleEngine（这样 UI 能展示）
  SlotConstraint.kt  17 种组合 + toCategories() + findPattern()
rule/
  ProbabilityCalculator.kt  穷举全部合法结果（17 组合 × 去重 × 冲突剪枝），
                            假设等概率；unwanted 不缩小抽取池；总数缓存
  Violation.kt       sealed class，带 severity(ERROR/WARNING)、isBlocking、suggestion
  RuleEngine.kt      validate / canStart(只看 ERROR) / isValid / autoFix
test/
  testutil/TestFactorDatabase.kt  loadFactorDatabase() 读真实 factors.json
  FactorDatabaseTest(11) SlotConstraintTest(4) rule/ProbabilityCalculatorTest(9) rule/RuleEngineTest(13)
```

规则：R1 超过4个、R2 想要/不想要重叠、R3 想要之间冲突（前三条有就直接返回）；R4 只看不想要已凑不出结果；R5 只看想要就不可能；R6 低于阈值（默认 0.1%）为 WARNING。R4/R5 都靠"概率为 0"判断，规则只在概率计算器里实现一次。

上次测试结果：37 个测试，0 失败。概率参考：只要无限能量 8.74%（约 11.4 次），无限能量+特卖日 0.171%（约 584 次）。

## 8. 下一步

1. 提交当前未提交的改动
2. 写 `codec/RecipeCodec.kt` + 测试。已定方案：
   - 格式 `SKF1-<Base32(payload)>-<CRC8>`，Base32 去掉易混字符，适合 B站评论复制
   - payload：dataVersion(varint) | flags | wanted ids | unwanted ids | optional ids + minOptional
   - 配方名不进口令码，导入时用户自己命名
   - 不认识的 id → 提示"请更新 App"，不能崩溃；CRC 错误 → 提示粘贴有误
   - 不引入新依赖，Base32 和 CRC8 手写
3. 选项 A 收尾后进入：识别方案细化（分析截图定位槽位/刷新按钮）或阶段②（悬浮窗 + DataStore）

## 9. 已定的架构（后续阶段用）

- 纯原生 Kotlin + Jetpack Compose；悬浮窗用 Compose 挂到 WindowManager
- 截图：默认 `AccessibilityService.takeScreenshot()`（API 30+，约每秒 3 次上限，需真机验证）；更高频率走 MediaProjection
- 识别：OpenCV 模板匹配（`org.opencv:opencv`，接入前确认 Maven 最新版本），校准用刷新按钮/骰子算缩放比例，不靠问号；低于阈值判 UNKNOWN
- 点击：`dispatchGesture`；运行时悬浮窗缩小并避开刷新按钮和槽位
- 状态机：IDLE → CALIBRATING → CAPTURE → RECOGNIZE → MATCH → TAP_REFRESH → COOLDOWN（无动画，所以不需要等画面稳定）；切出游戏或连续识别失败就暂停
- 持久化：先用 DataStore 存配方 JSON 列表，暂不用 Room
- 依赖分阶段加入，版本要先查证再锁定

## 10. 协作约定

- 文件读写自己做；工程初始化、装依赖这类命令原则上整理给主人执行
- 改完代码必须编译并跑测试；没跑过就不能说通过
- 主人纠正的事实（如 R6 不阻塞）以本文件为准
