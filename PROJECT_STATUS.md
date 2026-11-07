# KazeFactor 项目交接备忘录

> 最后更新：2026-11-07
> 当前阶段：配方管理主应用与核心自动刷新全链路（架构方案第 9 节第 1~6 步）已全部实现并通过构建与单元测试（`core-domain` 85 个、`core-engine` 73 个、`app` 6 个单元测试全部通过）。第 5 步无障碍截图与手势点击已通过真机 vivo V2312A 验收 ①②③（提交 `f2105ad`）；第 6 步悬浮窗、位置引导与权限引导页已完成开发与构建验证，待代码提交与真机端到端验收。
> 最新代码提交：`f2105ad`（第 5 步，状态文档提交 `4a1435d`）。**第 6 步代码待提交**

**新话题接手请先读：** 本文第 8 节（下一步）→ `docs/ARCHITECTURE_CORE_FEATURE.md`（实现以它为准）→ 需要时查 `docs/ARCHITECTURE_RESEARCH_REPORT.md`（结论的依据）。`docs/HANDOFF_CORE_FEATURE.md` 是交给架构方的需求原稿，已被架构方案回应，只作历史参考。

本文件面向接手开发的任何人员。下文中「维护者」指项目所有者，「本文」所述状态均已对照仓库实际代码核实；未经本轮运行验证的结论会单独注明。

---

## 1. 项目概述

KazeFactor 是《元气骑士》联机模式「挑战因子」的配方管理与自动刷新工具（Android）。

完整使用流程（最终目标）：玩家手动进入游戏内因子配置界面 → 在悬浮窗中选择配方 → 点击开始 → 无障碍服务截图并识别 4 个槽位 → 未命中则点击刷新，直到命中。

- 开源协议：代码为 GPL-3.0，计划发布到 GitHub 与 B 站；不做防检测，不做自动更新。
- 因子图标已获 wiki 工作组与凉屋游戏官方客服授权放入仓库，但不适用开源协议，版权归凉屋游戏所有，详见 `ICON_LICENSE.txt`。
- 维护者为全栈开发者，不熟悉 Android；代码主要由 AI 助手编写，维护者负责构建、真机测试与提交。

---

## 2. 已确认的需求与决策

以下决策以本文为准，若与旧文档或 README 冲突，按本文执行。

- 必须由玩家手动进入因子界面后才能启动识别。
- **配方语义已简化为三态**（2026-10 决策）：
  - 想要（WANTED）：必须全部出现。
  - 不想要（UNWANTED）：一个都不能出现。
  - 默认（NONE）：不作限制。
  - 原「可选（至少出现 m 个）」语义已从 UI 与交互中移除，原因是它与「默认」在用户理解上存在歧义。底层模型字段仍保留，见第 7.3 节。
- 配方支持保存、编辑、删除、口令码分享与导入；配方名由用户自行命名，不写入口令码。
- 自动纠正覆盖：超过 4 个、想要与不想要重叠、想要之间冲突、排除过多、不可能组合。
- R6 低命中率仅作提示，不阻塞保存与开始。UI 中以命中概率横幅内的小标签呈现。
- 刷新频率上限 1~10 次/秒，由用户在悬浮窗面板设置（已实现，默认 3 次/秒）。
- 实机特征：刷新没有动画，按钮没有形变，点击后可直接截图识别。
- 测试机分辨率 2388×1080，目前仅有这一台。
- 应用桌面图标与开屏页不得使用游戏因子图标。当前的简易图标与系统默认开屏即为最终方案（见第 7.4 节）。
- 原定「开屏页底部注明因子图标版权归凉屋游戏所有」：系统默认开屏无法显示文字，这条要求需要维护者确认改放在哪里（例如应用内「关于」信息或 README），见第 9 节。

---

## 3. 工程环境（已验证可构建）

- 路径：`D:\AndroidStudioProjects\KazeFactor`；包名：`io.github.futurelesswindchan.kazefactor`
- 模块：
  - `core-domain`：纯 Kotlin JVM 模块（java-library + kotlin.jvm + kotlin.serialization），无 Android 依赖。
  - `core-engine`：纯 Kotlin JVM 模块，放识别算法与刷新状态机（架构方案第 2.1 节）。`api(project(":core-domain"))`，依赖 kotlinx-coroutines 1.9.0（与 app 传递依赖解析版本一致）。识别层与刷新状态机源码均已完成（第 7.5 节）。
  - `app`：Android 应用模块，依赖 `core-domain` 与 `core-engine`。
- 工具链：Gradle 9.8.2、AGP 9.5.0（**内置 Kotlin 支持，`app` 不要再添加 kotlin-android 插件**）、Kotlin 2.2.10、Compose BOM 2026.02.01、compileSdk / targetSdk 37、minSdk 26、Java 11 字节码。
- 主要依赖（版本见 `gradle/libs.versions.toml`）：navigation-compose 2.10.2、lifecycle-viewmodel-compose 2.11.0、datastore-preferences 1.2.0、kotlinx-serialization-json 1.8.0、material-icons-extended。
- 已删除 `gradle/gradle-daemon-jvm.properties`（它要求下载 JDK 25，网络失败），并在 `gradle.properties` 中设置 `org.gradle.java.installations.auto-download=false`。`.gitignore` 也已忽略该文件。
- 常用命令（PowerShell 5.1，不支持 `&&`，请用 `;`）：
  - 领域层单元测试：`.\gradlew.bat :core-domain:test --console=plain`，结果见 `core-domain\build\test-results\test\*.xml`
  - 识别层单元测试：`.\gradlew.bat :core-engine:test --console=plain`，结果见 `core-engine\build\test-results\test\*.xml`；阈值报告、模板可分性报告、锚点测量报告、校准报告（`calibration-report.txt`）和开关判断报告（`toggle-report.txt`）写入 `core-engine\build\reports\recognition\`（不提交）。快速核验报告（`verify-report.txt`）和吸附报告（`snap-report.txt`）也在同一目录。`dd5041d` 时全量运行约 1 分 12 秒，其中校准合成测试约 26 秒、吸附与档案测试约 25 秒
  - 重新裁取识别素材（平时自动跳过）：`.\gradlew.bat :core-engine:test --tests "*RecognitionAssetGenerator*" '-Pkaze.generateRecognitionAssets' --console=plain`。**`-P` 参数必须加单引号**，否则 PowerShell 会在点号处把它拆开，Gradle 报「Task '.generateRecognitionAssets' not found」（2026-11-02 实测）
- Gradle Wrapper 已于 `7e193d5` 用 9.8.2 重新生成，恢复了分发包 SHA-256 校验（`distributionSha256Sum`）。升级 Gradle 时请用 `gradlew wrapper --gradle-distribution-sha256-sum` 一并更新校验和。
- 构建会提示「Deprecated Gradle features ... incompatible with Gradle 10」，目前不影响使用，升级 Gradle 10 前用 `--warning-mode all` 排查。
  - 构建：`.\gradlew assembleDebug`；安装到设备：`.\gradlew installDebug`

---

## 4. Git 状态与提交约定

- 最新代码提交为 `f2105ad`（第 5 步无障碍截图与手势点击）；本文件每次更新单独以 `docs(status)` 提交。
- 提交约定（维护者要求）：
  - 每完成一个最小可提交的进度就提交一次。
  - 提交信息格式为「英文类型前缀 + 中文主体」，例如 `feat(ui): 新增因子选择池紧凑图标网格模式`。
  - 构建、安装等有副作用的命令由维护者亲自执行。
  - **代码提交**：`git add/commit` 由维护者执行；维护者明确授权某次提交时，AI 助手可代为执行，授权只对当次有效。
  - **只含文档改动的提交**（本文件、`docs/` 下的文档等 `.md` 文件）：由 AI 助手直接提交，不需要每次授权（维护者 2026-11-04 确认）。提交前先 `git status` 确认没有混入代码改动，只 `git add` 具体的文档文件；提交后用 `git log` 核对。提交信息含中文，用 `git commit -F <UTF-8 文本文件>` 传入，避免 PowerShell 转码。

近期提交（新 → 旧）：

| 提交           | 内容                                                                                                                                                       |
| -------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 4a1435d        | 同步第 5 步真机实测结果与架构设计拍板结论（本文件）                                                                                                        |
| f2105ad        | 接入无障碍截图与手势点击（`A11yScreenCapturer`、`GestureTapper`、`KazeAccessibilityService`、Manifest 配置）。第 5 步完成                                   |
| 22d40ab        | 同步第 4 步完成情况与吸附 ±20% 复测结果（本文件）                                                                                                          |
| 6498756        | 实现刷新状态机与决策逻辑（`FrameAnalyzer`、`Decision`、`RefreshEngine`、`EngineState` 与 `loop/` 接口）。第 4 步完成                                        |
| 01fa895        | 同步军师第 3 步拍板结果与吸附范围放宽（本文件）                                                                                                            |
| cdc6d38        | `Snapper` 竖边搜索范围 ±12% → ±20%（只改常量和注释，未重跑测试）                                                                                           |
| 24eb5b7        | 架构军师回写第 3 步实测值、吸附搜索范围放宽至 ±20%、补登 `CalibrationProfileCodec` 和 `FrameAnalyzer`                                                      |
| dd5041d        | 模板支持横纵分开缩放；新增 `Snapper`、`CalibrationProfile`、`CalibrationProfileCodec`、`ProfileVerifier`；`SnapperProfileTest`。第 3c 步完成               |
| a3e4898        | 同步第 3b 步完成情况（本文件）                                                                                                                             |
| 1c55b1f        | 新增开关判断 `ToggleDetector`、`ScreenState` 与 `ToggleState`、开关采样区常量；`ToggleDetectorTest`。第 3b 步完成                                          |
| c4da372        | 同步第 3a 步完成情况与文档提交约定（本文件）                                                                                                               |
| 78a31ab        | 识别器接入校准（`FactorRecognizer` 接收 `Calibration`，新增 `RecognizerFactory`）；校准新增槽位验证；问号模板改从 `assets/recognition/` 读取。第 3a 步完成 |
| d73b2ed        | 新增面板边框精修 `PanelEdgeRefiner` 与校准合成测试                                                                                                         |
| f787450        | 帮助图标粗定位改为保留 5 个候选；「骰子」更名为帮助图标（`help.png`、`HELP_ICON_RECT`、`HelpIconEstimate`）                                                |
| eb0fa6e        | 架构修订：骰子更名为帮助图标，粗定位改为多候选加分档精度（架构军师）；附主力开发的粗定位实测报告 `docs/REPORT_DICE_LOCATE.md`                              |
| d8f1e45        | 新增 `Calibration` 坐标换算与面板边框参考参数                                                                                                              |
| 4cd8511        | 同步第 3a 步进度、识别素材与架构修订摘要（本文件）                                                                                                         |
| 6514fe6        | 新增识别素材生成工具，裁出 `dice.png` 与 4 个问号模板；刷新点击位置改为 (1377, 433)，新增 `DICE_RECT`                                                      |
| 7a707e5        | 架构修订：校准改为骰子粗定位 + 面板边框精修，新增手动吸附与位置档案（架构军师）                                                                            |
| 4259e92        | 新增锚点测量报告，按问号与已刷新截图分组统计                                                                                                               |
| 0cc2cf1        | 更新 AI 助手可查看图片的协作说明（本文件）                                                                                                                 |
| 353949a        | 同步第 2 步完成状态与第 3 步注意事项（本文件）                                                                                                             |
| 78dce74        | 实现槽位模板匹配与截图答案测试（架构方案第 9 节第 2 步）；架构方案第 3.2 节回写实测阈值                                                                    |
| 30a1450        | 同步第 1 步完成状态与工具链升级（本文件）                                                                                                                  |
| 7e193d5        | 以 9.8.2 重新生成 Gradle Wrapper，恢复分发包校验和                                                                                                         |
| 1fc315e        | IDE 配置登记 core-engine 模块，新增项目拼写词典                                                                                                            |
| 0138436        | 升级 Gradle 9.8.2 与 AGP 9.5.0                                                                                                                             |
| 09cd2cf        | Manifest 移除未使用的 tools 命名空间与重复的 Activity label                                                                                                |
| e287a7e        | 新建 core-engine 模块并迁移实机截图；新增答案 `labels.csv`；ICON_LICENSE 扩展适用范围                                                                      |
| eb4f44f        | 新增核心功能需求交接、架构方案与调研报告                                                                                                                   |
| c9de958        | 同步测试结果、清理与白屏修复进度（本文件）                                                                                                                 |
| a1e1a6e        | 修复输入框焦点无法清除、保存后重复返回导致白屏                                                                                                             |
| c051f36        | 剪贴板迁移至 `LocalClipboard`；删除无引用的模板图标资源与空类                                                                                              |
| 4ccf01a        | 对照仓库现状重写本备忘录                                                                                                                                   |
| 91b3630        | 忽略 Android Studio 设备管理器界面配置                                                                                                                     |
| eddd1e1        | 天蓝底表情包自适应图标；README 引入艺术字 Logo；清理根目录冗余素材                                                                                         |
| 24f2360        | 已选与排除因子置顶预览槽、列表卡片图标展示、分类名汉化补全                                                                                                 |
| 24d2c6e        | 因子选择池紧凑图标网格模式与视图切换                                                                                                                       |
| dc3f5e4        | 接入真实因子像素图标与选中状态角标                                                                                                                         |
| 4ec3a99        | 移除可选因子状态与数量滑块，简化为三态点选                                                                                                                 |
| 90a34ae        | 修复编辑页布局跳动，整合概率横幅，修正主题配色                                                                                                             |
| 8dcc172        | 集成 Navigation Compose 与 ViewModelFactory                                                                                                                |
| 090b2f9 及更早 | 数据层、三大界面、主题、口令码、规则引擎、领域模型                                                                                                         |

---

## 5. 资源位置

| 资源                 | 位置                                                                                                                                   | 说明                                                                                                                                                                  |
| -------------------- | -------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 因子数据（运行时）   | `app/src/main/assets/factors.json`                                                                                                     | 由 `AssetFactorLoader` 加载                                                                                                                                           |
| 因子数据（测试用）   | `core-domain/src/test/resources/factors.json`                                                                                          | 领域层测试读取；与上一份为两份独立副本，修改时须同步                                                                                                                  |
| 因子图标（运行时）   | `app/src/main/res/drawable/*.png`（59 个）                                                                                             | 由 `FactorIconMapper` 按 `key` 动态查找                                                                                                                               |
| 因子图标（识别模板） | `app/src/main/assets/factors/icons/*.png`（59 个）                                                                                     | 与 `res/drawable` 内容相同（MD5 一致）。按架构方案保留，作为识别模板的唯一来源；`res/drawable` 解码时会按屏幕密度缩放，不能用作模板                                   |
| 识别素材             | `app/src/main/assets/recognition/`：`help.png`（33×38，帮助图标，原名 `dice.png`，`f787450` 改名）、`question_slot1~4.png`（各 90×90） | 由 `RecognitionAssetGenerator` 从 `disable01.jpg` 裁出（`6514fe6`），已看图核对。版权同因子图标，`ICON_LICENSE.txt` 已覆盖。测试经系统属性 `kaze.recognitionDir` 读取 |
| 实机截图             | `core-engine/src/test/resources/screenshots/`（47 张，从 `app/src/androidTest/assets/` 迁入）                                          | `disable01~07` 为问号状态，`enable01~40` 为已刷新状态。答案文件 `labels.csv`（值为因子 key，问号写 `?`）已由维护者核对，全部正确（2026-10-31）                        |
| README Logo          | `assets/logo/`（`KAZE FACTOR.svg`、`K.svg`、`Pinia.png/.webp`）                                                                        | 仅供 README 使用，不打包进 APK                                                                                                                                        |
| 应用图标             | `res/mipmap-anydpi/ic_launcher*.xml` + `res/drawable/ic_launcher_foreground_emoji.xml` + `res/mipmap-*/ic_launcher_emoji.png`          | 见第 7.4 节                                                                                                                                                           |

根目录原有的 `screenshot/` 与 `texture2DEng/` 已于 `eddd1e1` 删除，其内容在上表中的位置保留副本。

`factors.json` 字段：`id`（1~59，发布后永不改变）、`key`、`name`、`nameEn`、`description`、`descriptionEn`、`category`、`iconFileName`；另含 11 组冲突对 `conflicts`，`exclusiveGroups` 为空。命名标准以维护者提供的 CSV 为准。

---

## 6. 游戏规则（来自灰机 wiki，已编码）

- 59 个普通因子：大恶 6（id 1~6）、中恶 14（7~20）、小恶 8（21~28）、中性 15（29~43）、小良 7（44~50）、大良 9（51~59）。已于 2026-10-30 对照 `factors.json` 复核。
- 类别枚举：`MAJOR_BAD` / `MODERATE_BAD` / `MINOR_BAD` / `NEUTRAL` / `MINOR_GOOD` / `MAJOR_GOOD`。
- 槽位：第 1 位为大恶/中恶/小恶；第 2 位为中恶/小恶/中性；第 3 位为小恶/中性/小良/大良；第 4 位为小良/大良。
- 17 种合法类别序列定义在 `SlotConstraint` 枚举（`PATTERN_1~17`，按槽位 1→4）。

---

## 7. 代码现状

### 7.1 core-domain（`core-domain/src/main/kotlin/.../domain/`）

```
model/
  Factor.kt          Factor + FactorCategory，canAppearInSlot()
  FactorDatabase.kt  查询、areConflicting、getAllFactorIds
  Recipe.kt          wanted / unwanted / optional 三个 Set<Int> 与 minOptional；matches(4 个 id)
                     init 只拦截负数，业务错误交给 RuleEngine，以便 UI 展示
  SlotConstraint.kt  17 种组合 + toCategories() + findPattern()
rule/
  ProbabilityCalculator.kt  穷举全部合法结果（17 组合 × 去重 × 冲突剪枝），
                            假设等概率；unwanted 不缩小抽取池；总数有缓存
  Violation.kt       sealed class，带 severity(ERROR/WARNING)、isBlocking、suggestion；
                     LowProbability 默认阈值 0.001（0.1%）
  RuleEngine.kt      validate / canStart（只看 ERROR）/ isValid / autoFix
codec/
  RecipeCodec.kt     口令码编解码，格式 SKF1-<Base32(payload)>-<CRC8>
  Base32Encoder.kt   去除易混字符的 Base32（手写，无外部依赖）
  CRC8Calculator.kt  CRC8 校验（手写）
  VarintCodec.kt     变长整数编码
```

规则：R1 超过 4 个、R2 想要/不想要重叠、R3 想要之间冲突（前三条命中即直接返回）；R4 仅看不想要时已凑不出结果；R5 仅看想要时不可能；R6 低于阈值为 WARNING。R4/R5 依靠「概率为 0」判断，规则逻辑只在概率计算器中实现一次。

口令码 payload：dataVersion(varint) | flags | wanted ids | unwanted ids | optional ids + minOptional。遇到不认识的 id 提示「请更新 App」，CRC 错误提示「粘贴有误」，均不崩溃。

测试（`core-domain/src/test/kotlin/`）：FactorDatabaseTest(11)、SlotConstraintTest(4)、ProbabilityCalculatorTest(9)、RuleEngineTest(13)、RecipeCodecTest(14)、VarintCodecTest(14)、Base32EncoderTest(10)、CRC8CalculatorTest(10)，共 85 个 `@Test` 方法。2026-10-31 已运行，85 个全部通过（0 failures / 0 errors）。概率参考值：只要「无限能量」为 8.74%（约 11.4 次），「无限能量 + 特卖日」为 0.171%（约 584 次）。

### 7.2 app（`app/src/main/kotlin/.../`，`MainActivity.kt` 位于 `app/src/main/java/.../`）

```
KazeFactorApplication.kt     全局手动依赖注入：factorDatabase、recipeRepository、engineSettingsDataStore、
                             calibrationProfileDataStore、recognizerFactory（lazy 单例）
OverlayController.kt         进程内单例：连接主应用开关与无障碍服务悬浮窗
MainActivity.kt              Surface + NavHost，串联四个界面；从 Application 取依赖；
                             popBackStackFrom(entry) 仅在页面处于 RESUMED 时返回，防止重复返回弹空返回栈（白屏）
util/
  AssetFactorLoader.kt       从 assets/factors.json 加载 FactorDatabase
  AssetRecognitionLoader.kt  从 assets/factors/icons/ 与 assets/recognition/ 零密度缩放加载 59 个图标原图、
                             help.png（33×38）与 4 个问号模板，构建 RecognizerFactory
data/
  datastore/
    RecipeDataStore.kt             DataStore Preferences（文件名 recipe_storage，键 recipes_json），
                                   存配方 JSON 列表；含未知因子 id 的配方在读取时被过滤
    EngineSettingsDataStore.kt     DataStore Preferences（文件名 engine_settings），存刷新频率（1~10）、
                                   次数上限开关与自定义上限、命中震动开关、上次选中的配方 id
    CalibrationProfileDataStore.kt DataStore Preferences（文件名 calibration_profile），按截图尺寸存取位置档案文本，
                                   使用 Bitmap PNG 编解码接入 CalibrationProfileCodec
  repository/RecipeRepository.kt   单例；封装 DataStore、因子查询、RuleEngine、ProbabilityCalculator
domain/viewmodel/
  ViewModelFactory.kt          手动工厂，参数为 repository、database、可选 recipeId
  RecipeListViewModel.kt       配方列表、删除、摘要、概率、按 id 取因子
  RecipeEditorViewModel.kt     三态选择、实时校验与概率、autoFix、保存
  RecipeCodeViewModel.kt       口令码生成与导入
ui/
  navigation/Navigation.kt     字符串路由：recipe_list、recipe_editor?recipeId=、recipe_code?recipeId=、permission_guide
  screen/RecipeListScreen.kt   配方列表；卡片展示想要/排除因子图标；顶栏「自动刷新」与「导入口令」入口
  screen/RecipeEditorScreen.kt 配方编辑；固定高度的概率横幅（含低概率标签与已选/排除置顶槽）；
                               图标网格 / 列表视图切换（默认网格）；键盘收起、点击空白或因子、
                               按完成键、保存时清除输入框焦点；保存成功以 Toast 提示并立即返回，
                               保存中禁用保存按钮
  screen/RecipeCodeScreen.kt   recipeId 非空为导出模式，为空为导入模式；复制使用 LocalClipboard
  screen/PermissionGuideScreen.kt 权限引导与悬浮窗开关：无障碍设置跳转、Android 13+「受限设置」解除引导
                                  （直达应用信息页）、国产系统后台保活说明、Android 8~10 兼容提示
  component/FactorSelector.kt  FactorSelectionState（NONE/WANTED/UNWANTED）、FactorDisplayMode、
                               列表项、网格项、CompactFactorChip、状态角标、分类中文名
  component/RuleViolationCard.kt  阻塞性错误卡片与一键修复
  util/FactorIconMapper.kt     按 key 查找 drawable，以 FilterQuality.None 渲染以保持像素清晰
  theme/                       天蓝主色 #38BDF8；浅灰 / 墨蓝双主题；已覆盖 surfaceContainer* 以消除 M3 默认紫色
overlay/
  OverlayState.kt              OverlayMode（BUBBLE / PANEL / RUNNING / HIT / MANUAL_SETUP / CONFIRM_PREVIEW）、
                               ConfirmPreviewData、ManualSetupData、OverlayUiState、禁区计算与 PauseReason 文案映射
  OverlayViewModel.kt          串联 RefreshEngine、快速核验/自动校准 → 确认预览 → 手动拖框 + Snapper 吸附 →
                               保存位置档案；统计最近 10 次刷新的实际速度、命中短震动、「保存当前画面」导出 PNG
  OverlayContent.kt            小球 / 面板 / 运行中胶囊 / 命中展开 四种形态 Compose 视图
  PositionSetupContent.kt      全屏手动位置引导（冻结截图 + 4 格轮廓拖动/两端缩放 + 刷新按钮准星 + 自动吸附）
                               与全屏确认预览（4 槽绿框 + 识别结果核对 + 对/不对门控）
  OverlayWindow.kt             TYPE_ACCESSIBILITY_OVERLAY 窗口管理、拖动、全屏切换与运行中禁区自动避让
service/
  KazeAccessibilityService.kt  无障碍服务：持有 A11yScreenCapturer、GestureTapper，组装 RefreshEngine 与 OverlayWindow；
                               监听前台包名切换（切出游戏立即暂停引擎）
  ServiceLifecycleOwner.kt     为服务中的 ComposeView 提供 Lifecycle、ViewModelStore 与 SavedStateRegistry
  A11yScreenCapturer.kt        takeScreenshot（API 30+）→ HardwareBuffer → ARGB_8888 BitmapFrame（minIntervalMs = 350 ms）
  GestureTapper.kt             dispatchGesture 短按点击（默认 40 ms），挂起等待完成回调
```

测试（`app/src/test/java/`）：`OverlayStateAndPrefTest`(5) + `ExampleUnitTest`(1)，共 6 个 `@Test` 方法，2026-11-07 全部通过。

### 7.3 「可选因子」遗留字段

UI 与 ViewModel 已不再产生可选因子，但以下位置仍保留相关字段，以保持口令码协议与已存数据的兼容：

- `Recipe.optionalFactorIds` / `Recipe.minOptional`
- `RecipeDataStore.PersistedRecipe` 中的同名字段
- `RecipeCodec` payload 中的 optional 段
- `RuleEngine` 与 `ProbabilityCalculator` 仍支持 optional 语义

编辑器新建或保存的配方中，这两个字段恒为空集与 0。

### 7.4 应用图标与开屏

- 图标为自适应图标：背景为纯色 `@color/ic_launcher_background`（`#FF38BDF8`），前景为 `drawable/ic_launcher_foreground_emoji.xml`，它把 `mipmap-*/ic_launcher_emoji.png`（表情包，临时图标）四边各内缩 24dp。
- PNG 改名为 `ic_launcher_emoji` 是为了避免与 `mipmap-anydpi/ic_launcher.xml` 同名而形成循环引用。内缩使用 dp 而非百分比，因为 InsetDrawable 的百分比写法需要 API 28，而 minSdk 为 26。
- 模板自带的 `drawable/ic_launcher_foreground.xml`、`drawable/ic_launcher_background.xml` 与各密度 `ic_launcher_round.webp` 已于 `c051f36` 删除。Manifest 的 `roundIcon` 仍指向 `@mipmap/ic_launcher_round`，由 `mipmap-anydpi/ic_launcher_round.xml` 提供（minSdk 26 总是优先使用 anydpi）。
- 当前没有 `<monochrome>` 层，Android 13+ 主题图标不可用。
- 开屏页尚未定制：Android 12+ 使用系统默认开屏，显示的是上述自适应图标前景。`themes.xml` 的父主题为 `android:Theme.Material.Light.NoActionBar`，未引入 `core-splashscreen`。
- 维护者确认（2026-10-31）：本工具以实用为主，当前的简易图标与系统默认开屏即为最终方案，不再另做原创矢量图标与自定义 Compose 开屏页。

### 7.5 core-engine（`core-engine/src/main/kotlin/.../engine/`）

```
image/
  ArgbImage.kt          width、height、pixels（0xAARRGGBB，行优先）；通道读取与像素差工具函数
  ImageOps.kt           crop、面积平均缩放（RGB 按 alpha 加权）、居中贴图、alpha 合成
vision/
  RecognitionModels.kt  ScreenState（FACTOR_SCREEN_ON / FACTOR_SCREEN_OFF / NOT_FACTOR_SCREEN，classify 判定表）、
                        ToggleState（ON / OFF / UNCERTAIN）、SlotResult（Question / Factor / Ambiguous / Unknown）、FrameResult、PixelRect
  ReferenceLayout.kt    EdgePolarity、ReferenceEdge；2388×1080 参考坐标：槽位、点击刷新位置 (1377, 433)、帮助图标 HELP_ICON_RECT、
                        面板竖边 966/1006/1747/1789、横边 236/350/690、剖面取样行列范围、搜索边距 4 px、
                        开关采样区 TOGGLE_SAMPLE_RECT（x 1320..1355、y 600..620）
  ToggleDetector.kt     开关判断：采样区内「G 比 R、B 都大 40 以上」的像素占比，> 50% 开、< 10% 关，其余或采样区出画面为 UNCERTAIN
  Calibration.kt        scaleX、scaleY、originX、originY；参考坐标 ↔ 屏幕坐标换算（像素边界）
  Calibrator.kt         自动校准入口：帮助图标粗定位（多候选）→ PanelEdgeRefiner 面板边框精修 → 残差检查 → 槽位验证
  PanelEdgeRefiner.kt   面板边框精修：横向亮度剖面 → 梯度 + 抛物线插值找边 → 最小二乘拟合 scale 和 originX → 纵向找边 → originY
  Snapper.kt            手动吸附：用户粗框 → 竖边搜索 → 横边搜索 → 联合精修 → 逐边检查，得到横纵可以不等的 Calibration
  CalibrationProfile.kt 位置档案：截图尺寸 + Calibration + 点击位置 + 开关参照块 + 来源与时间戳，纯数据
  CalibrationProfileCodec.kt 档案与文本互转，PNG 编解码由调用方传入（手机用 Bitmap，测试用 ImageIO）
  ProfileVerifier.kt    快速核验：开关参照块距离 ≤ 15 通过；不通过时检查是否全问号（开关关 / 核验失败）
  FrameAnalyzer.kt      每帧分析入口：开关检测 + 槽位识别 → FrameAnalysis(screenState, slotResults)；
                        无档案时用 ToggleDetector（绿色像素占比），有档案时与档案的开关参照块比较（距离 ≤ 15 为开）
  RecognizerFactory.kt  按 Calibration 的横纵缩放生成 TemplateBank + 问号模板 + SlotMatcher + FactorRecognizer，模板按缩放缓存
  TemplateBank.kt       图标 → 槽位大小的掩膜模板（alpha ≥ 200），横纵缩放可以不同；尺寸系数覆盖表；按槽位剪枝候选
  SlotMatcher.kt        MatchThresholds；问号比较 → 粗搜 ±2 → 前 3 名精修 ±3 → 差距不足时二次比较
  FactorRecognizer.kt   一帧 → FrameResult；通过 Calibration 换算搜索区域，不再限制画面尺寸
loop/
  Frame.kt              interface：width、height、crop(x, y, w, h): ArgbImage、close()
  ScreenCapturer.kt     interface：suspend fun capture(): Frame?；val minIntervalMs: Long
  Tapper.kt             interface：suspend fun tap(x: Float, y: Float): Boolean
  RefreshSettings.kt    频率 1~10（默认 3）、连续失败上限 N（默认 3）、总次数上限（默认 = 期望 × 3 且 ≥ 100）、点击后相同结果上限（默认 3）
  EngineState.kt        Idle / Calibrating / Running(refreshCount, failureCount) / Hit(factorIds, refreshCount) /
                        Paused(reason, refreshCount) / Stopped；PauseReason（8 种）
  Decision.kt           4 个 SlotResult + Recipe → Hit / Miss / Uncertain(reason)；Ambiguous 展开笛卡尔积后按 17 种组合与冲突过滤
  RefreshEngine.kt      自动刷新状态机，对外暴露 StateFlow<EngineState>；支持 start / resume / pause / stop
```

测试（`core-engine/src/test/kotlin/.../engine/`）：

- `testutil/TestFixtures.kt`：经系统属性读 app 的 `factors.json`、图标与识别素材（`kaze.recognitionDir`），经 classpath 读截图与 `labels.csv`。问号模板已改为从 `assets/recognition/question_slot1~4.png` 读取（`78a31ab`）。新增 `recognizerFactory`，可按任意缩放生成识别器。
- `testutil/SyntheticFrames.kt`：合成测试工具，画面缩放 + 黑边 + JPEG 重压缩，同时给出真实 `Calibration`。
- `vision/ScreenshotAnswerTest`(2)：47 张截图逐槽核对答案。
- `vision/ThresholdReportTest`(1)：只出报告不断言。
- `vision/TemplateSeparabilityTest`(2)：59 个模板合成到黑色、中灰背景上识别为自己；各槽候选数 28 / 37 / 39 / 16。
- `vision/AnchorSurveyTest`(1)：锚点测量，只出报告。
- `vision/CalibrationTest`(10)：`Calibration` 换算规则（scale = 1 / 0.5 / 1.25 / 1.33 / 横纵不等 / 反算 / 非法参数 / 面板边缘 / 帮助图标改名）。
- `vision/CalibrationSyntheticTest`(1)：47 张原始截图 + 4 组合成图（0.5 / 0.75 / 1.25 / 1.33 倍），验收 ①②③④（详见下文第 8 节）。
- `vision/SnapperProfileTest`(11)：吸附（验收 ①②）、快速核验（③）、档案序列化（④）、横纵缩放模板一致性、横向拉伸截图识别等。报告 `snap-report.txt`、`verify-report.txt`。
- `vision/ToggleDetectorTest`(8)：47 张截图开关判断与画面状态；4 种缩放的合成图抽查；采样区出画面、纯色画面、阈值边界、绿色像素判断、非法阈值、`ScreenState.classify` 判定表。报告 `toggle-report.txt`。
- `vision/RecognitionAssetGenerator`(1)：素材生成工具，平时跳过，用法见第 3 节。
- `loop/DecisionTest`(12)：`Unknown`/`Question` 槽位、命中/未命中、排除因子、`Ambiguous` 展开（全命中/全未命中/结论不一致）、17 种组合与重复/冲突检查。
- `loop/RefreshEngineTest`(25)：假截图器 + 假点击器 + 虚拟时间，覆盖命中停止、未命中点击、点击偏移 ±10 px、`UNCERTAIN` 不点击、连续 N 次暂停、失败计数被 `MISS` 清零、截图失败计数、手势点击被系统拒绝计数、点击无效暂停、达到次数上限暂停、最后一次刷新结果仍被识别、开关关闭暂停、开关开但有问号按 `UNCERTAIN`、不在因子界面暂停、`resume` 先识别再点击且不重新校准、快速核验通过后第一帧 `Unknown` 判核验失败、核验开关关闭/失败、`Frame.close()` 调用、节拍间隔（虚拟时间）、运行中 `stop`、用户暂停后恢复、`RefreshSettings` 计算。

2026-11-07 最后一次运行：`:core-engine:test` 共 73 个通过，1 个跳过（素材生成器），0 failures / 0 errors。

---

## 8. 下一步

已完成（2026-10-31）：

- 领域层测试 85 个全部通过；小型清理（`c051f36`）；编辑页焦点与重复返回白屏修复（`a1e1a6e`），均已真机验证。
- 架构方案第 9 节第 1 步（`e287a7e`）：`core-engine` 模块建好，`:core-engine:test` 与 `assembleDebug` 均构建通过；截图与 `labels.csv` 已迁入 `core-engine/src/test/resources/screenshots/`。

已完成（2026-11-01）：

- 架构方案第 9 节第 2 步（`78dce74`）：识别层与前 3 类测试，`:core-engine:test` 5 个测试全部通过，详见第 7.5 节。本步只改了 `core-engine`，没有动 app，未运行 `assembleDebug`。

已完成（2026-11-02）：

- 锚点测量（`4259e92`）→ 架构军师据此修订校准方案（`7a707e5`）→ 裁出识别素材（`6514fe6`）。`6514fe6` 往 `app/src/main/assets/` 加了 5 个 PNG，没有改 app 的代码，未运行 `assembleDebug`。

已完成（2026-11-02 ~ 11-04）：架构方案第 9 节第 3a 步（自动校准），共 4 次代码提交 + 1 次架构修订：

- `d8f1e45`：`Calibration`（`scaleX`、`scaleY` 两个字段，按像素边界换算）与 `ReferenceLayout` 的面板边框参数。
- 首次实测帮助图标粗定位失败（`docs/REPORT_DICE_LOCATE.md`）：同屏有 3 个外观相同的「?」帮助图标（「挑战因子」标题旁、「周挑战」标题旁、下方手枪图标旁，维护者 2026-11-03 看图确认），只保留 1 个粗搜位置时 5 组里有 3 组选错；缩放 0.5 时 `s0` 误差 −5%。
- `eb0fa6e`（架构军师）：「骰子」更名为「帮助图标」；粗搜保留 K = 5 个候选交给第 2 段筛选；`s0` 精度按图标宽度分档（≥ 25 px 时 ≤ 3%，否则 ≤ 6%）；第 2 段竖边窗口系数 0.04 → 0.06；画面状态判断改以面板边框为主。
- `f787450`：按修订改名并改为多候选粗定位。
- `d73b2ed`：`PanelEdgeRefiner` 面板边框精修。
- `78a31ab`：`RecognizerFactory`；`FactorRecognizer` 接入 `Calibration`；`Calibrator` 新增槽位验证；问号模板改从 `assets/recognition/` 读取。

第 3a 步验收结果（`CalibrationSyntheticTest`，47 张原始截图 + 4 种缩放各 4 张合成图，共 63 帧，报告 `core-engine/build/reports/recognition/calibration-report.txt`）：

| 验收条目                          | 结果                                                                          |
| --------------------------------- | ----------------------------------------------------------------------------- |
| ① 槽位误差 ≤ 2 px，答案测试仍通过 | 最大 0.22 px（缩放 1.33）；63 帧 4 槽全部识别正确                             |
| ② `s0` 误差分档                   | 最大 −5.00%（缩放 0.5，图标宽 16.5 px，上限 6%）                              |
| ③ `s0` 人为偏 ±8%                 | 要么拒收，要么槽位误差 ≤ 0.20 px，没有给出错误坐标                            |
| ④ 同屏 3 个帮助图标时选中真锚点   | 全部选对；缩放 0.5 时排第 1 的是手枪旁的假图标，被精修拒掉，最终用第 2 个候选 |
| ⑤ 开关判断                        | 第 3b 步完成，见下文                                                          |

已完成（2026-11-04）：架构方案第 9 节第 3b 步（`1c55b1f`），开关判断与画面状态：

- `ToggleDetector` 按架构方案第 3.3 节的规则和阈值初值实现，阈值没有改。
- `ScreenState.classify(toggle, frame)`：开关开 → `FACTOR_SCREEN_ON`；开关关且 4 槽都是问号 → `FACTOR_SCREEN_OFF`；开关关但不全是问号、或开关不确定 → `NOT_FACTOR_SCREEN`。校准失败时调用方直接取 `NOT_FACTOR_SCREEN`。
- 开关开但有槽位是问号时仍返回 `FACTOR_SCREEN_ON`，留给第 4 步的 `Decision` 按 UNCERTAIN 处理（架构方案第 3.3 节）。
- 还没有把「校准 + 开关 + 槽位识别」串起来的总入口，架构方案没有规定这个类，打算在第 4 步状态机里组合。

验收 ⑤（`ToggleDetectorTest`，报告 `core-engine/build/reports/recognition/toggle-report.txt`，用 `Calibration.IDENTITY`，不跑完整校准）：

| 分组                                                                                | 绿色像素占比              | 结果                               |
| ----------------------------------------------------------------------------------- | ------------------------- | ---------------------------------- |
| 开关关 7 张（`disable*`）                                                           | 全部 0.0%（阈值 < 10%）   | 开关 OFF，画面 `FACTOR_SCREEN_OFF` |
| 开关开 40 张（`enable*`）                                                           | 全部 100.0%（阈值 > 50%） | 开关 ON，画面 `FACTOR_SCREEN_ON`   |
| `disable01`、`enable01` × 缩放 0.5 / 0.75 / 1.25 / 1.33（加黑边，3 组 JPEG 重压缩） | —                         | 判断不变                           |

架构方案第 3.3 节写着「两个阈值是初值，第 3 步在 Kotlin 测试中按 47 张截图实测确认」，实测结果（0% / 100%）**尚未回写架构方案**，第 3.3 节归架构军师维护。

已完成（2026-11-04）：架构方案第 9 节第 3c 步（`dd5041d`），手动吸附、位置档案与快速核验：

- `TemplateBank`、`SlotMatcher`、`RecognizerFactory` 支持横纵分开缩放，原来的 TODO 已去掉。只给一个缩放时两者相等；`ScreenshotAnswerTest`、`CalibrationSyntheticTest` 照旧通过。
- `Snapper` 按架构方案第 3.4.4 节的 4 步实现。搜索参数用「轮廓左沿、上沿的屏幕位置」代替 `origin`，换缩放时轮廓不会整体漂走。第 1~3 步每隔 1 个参考像素采样一次（架构方案没有规定采样密度，这是主力开发自定的），第 4 步逐边检查逐像素采样。
- `ProfileVerifier` 返回 `Pass` / `ToggleOff` / `Fail`。截图尺寸与档案不符时直接 `Fail`。架构方案第 3.4.5 节第 2 条「通过后第一帧 4 个槽位都不能是 `Unknown`」**没有放在这里**，打算由第 4 步状态机在拿到第一帧时检查，避免同一帧识别两次。
- `CalibrationProfileCodec` **不在架构方案的类清单里**，是主力开发为了在电脑上测验收 ④ 加的：文本格式带版本号，参照块按 PNG + Base64 保存（第 3.4.1 节），PNG 编解码由调用方传入。文本损坏时返回 null，按「没有档案」处理。是否该挪到 app 待架构军师判断。

第 3c 步验收（`SnapperProfileTest`，11 个测试全部通过）：

| 验收         | 条件                                                                                                                                                    | 结果                                                                                                                                                |
| ------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------- |
| ① 吸附       | 4 张截图 × 7 种合成条件（原始、k = 1 JPEG 85、k = 0.75 JPEG 85、k = 1.25 黑边、kx = 1.1 / 0.9 / 1.2 且 ky = 1）× 用户框误差 0 / ±15 / ±30 px，共 140 次 | ±12% 时通过 136 次、拒收 4 次；**±20% 后通过 140 次、拒收 0 次**（2026-11-07 全量重跑确认），槽位误差最大 1.27 px，**没有「通过却给错坐标」的情况** |
| ② 偏一个槽位 | 4 张截图，初值左右各偏 163 px                                                                                                                           | 8 次全部被逐边检查拦下                                                                                                                              |
| ③ 快速核验   | 以 enable01 的开关为参照块，47 张截图                                                                                                                   | 开关开 40 张距离 0.00，全部 `Pass`；开关关 7 张距离 40.89，全部 `ToggleOff`                                                                         |
| ④ 序列化     | 横纵不等、带小数的换算 + 3×2 参照块；真实截图的参照块                                                                                                   | 读回后逐字段、逐像素相同，换算结果不变；8 种损坏文本都读回为 null                                                                                   |
| 补充         | 横向拉伸 0.9 / 1.1 / 1.2 倍后按吸附结果识别 4 张截图                                                                                                    | 全部与 `labels.csv` 一致                                                                                                                            |

拒收的 4 次（「k = 0.75、JPEG 85、e = +30」）在 ±20% 后全部转为通过。原因是放宽搜索范围后 scaleX 初值偏 −13% 也能搜到正确位置。2026-11-07 全量重跑 `:core-engine:test` 确认。

已完成（2026-11-07，`6498756`）：架构方案第 9 节第 4 步，`FrameAnalyzer`、`Decision`、刷新状态机与 `loop/` 下的接口：

- `FrameAnalyzer`（`vision/`）：`analyze(frame, calibration)` 无档案时用 `ToggleDetector`（绿色像素占比）；`analyzeWithProfile(frame, profile)` 有档案时与档案的开关参照块比较（距离 ≤ 15 为 ON，否则为 OFF，再交给 `ScreenState.classify`）。
- `Decision`：把 `Ambiguous` 槽位展开笛卡尔积，按 17 种类别组合、互不重复、冲突对过滤；合法组合全命中 → `Hit`、全未命中 → `Miss`、结论不一致或无合法组合 → `Uncertain`。
- `RefreshEngine`：`start` / `resume` / `pause` / `stop`。次数上限放在「识别完、点击前」检查，避免漏掉最后一次刷出的命中；`resume` 沿用已有校准不重跑，先截图识别再决定点不点。
- `Calibrator`、`ProfileVerifier`、`FrameAnalyzer` 标为 `open`，供 `RefreshEngineTest` 用假实现替换。
- 测试：`DecisionTest`(12)、`RefreshEngineTest`(25)，全部通过，覆盖架构方案第 9 节第 4 行全部验收条目。
- **架构军师拍板结论（2026-11-07 已结案）**：
  1. **快速核验失败时的处理**：确认采用当前做法（引擎直接暂停为 `PauseReason.VERIFY_FAILED`，由 app 统筹后续自动校准与确认预览）。
  2. **校准与每帧分析仍按 `Frame.crop(0, 0, width, height)` 取整帧**：确认保持现状，先看第 5 步真机测速数据。
  3. **`Tapper.tap()` 返回 `false`（系统拒绝手势）**：确认归入 `consecutiveFailures`（已在 `RefreshEngine` 补上并新增单测）。
  4. **统计字段**：确认速度统计与期望次数由第 6 步的 `OverlayViewModel` 计算，`EngineState.Running` 保持精简。

已完成（2026-11-07，`f2105ad`）：架构方案第 9 节第 5 步，无障碍截图与手势点击（已在真机 `vivo V2312A` Android 13 / SDK 33 验证通过）：

- `AndroidManifest.xml`、`res/xml/accessibility_service_config.xml`、`res/values/strings.xml` 已按第 7 节配置。
- `A11yScreenCapturer`：`takeScreenshot`（API 30+）→ `HardwareBuffer` → `Bitmap.wrapHardwareBuffer` + `copy(ARGB_8888)`，封装为 `BitmapFrame`（`crop` 用 `Bitmap.getPixels` 按矩形读取），`DEFAULT_MIN_INTERVAL_MS` 按真机实测设为 `350L`。
- `GestureTapper`：`dispatchGesture` 注入 40 ms 短按手势，挂起等待完成回调。
- `KazeAccessibilityService` + `A11yDebugDialog`（主界面顶栏「调试」按钮）：提供 `TYPE_ACCESSIBILITY_OVERLAY` 左上角迷你调试条与三项真机验证动作（第 6 步做正式悬浮窗时已替换删除）。
- **第 5 步真机（vivo V2312A，2388×1080，Android 13 / SDK 33）验收结果**：
  1. **验收 ①（截图与悬浮窗）**：保存的截图尺寸为 `2388×1080`，与手机画面一致；**截图里包含左上角的 `TYPE_ACCESSIBILITY_OVERLAY` 悬浮条以及系统无障碍悬浮按钮**。因此第 6 步正式悬浮窗的小球与运行中状态条默认放在左上角（避开右侧 `x 966..1789` 的挑战因子面板），不会遮挡识别区域。
  2. **验收 ②（点击刷新）**：在开启挑战因子的情况下，点击 (1377, 433) 游戏正常刷新。
  3. **验收 ③（连续截图 20 次实测最小间隔与失败码）**：
     - **0 ms 紧贴调用 5 次**：第 1 次成功（91 ms），第 2~5 次全部立即返回 `ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT(3)`（耗时 1~3 ms）。
     - **25 ms 步进连续截满 20 张成功帧**（总尝试 198 次，其中 `INTERVAL_TIME_SHORT(3)` 178 次）：相邻两次成功发起间隔**最小 = 332 ms，平均 = 348.5 ms，最大 = 362 ms**（与 AOSP 333 ms 常量完全吻合）；单次 `takeScreenshot + 拷像素` 耗时**最小 = 54 ms，平均 = 68.4 ms，最大 = 110 ms**。
     - `A11yScreenCapturer.DEFAULT_MIN_INTERVAL_MS` 已定为 **350 ms**。

已完成（2026-11-07，待提交）：架构方案第 9 节第 6 步，悬浮窗、位置引导与权限接入：

- `EngineSettingsDataStore` 与 `CalibrationProfileDataStore`：分别保存自动刷新设置（频率、次数上限、震动、上次选的配方）与位置档案（按截图尺寸存取，使用 `Bitmap` PNG 编解码接入 `CalibrationProfileCodec`）。
- `AssetRecognitionLoader`：从 `assets/factors/icons/` 与 `assets/recognition/` 零密度缩放加载 59 个图标原图、`help.png` 与 4 个问号模板，在 `KazeFactorApplication` 中提供全局共享的 `recognizerFactory`。
- `ServiceLifecycleOwner` 与 `OverlayController`：为服务中的 `ComposeView` 提供生命周期宿主，并通过进程内单例连接主应用与无障碍服务。
- `OverlayState`、`OverlayViewModel`、`OverlayContent`、`PositionSetupContent`、`OverlayWindow`：完整实现小球、面板、运行中/暂停胶囊、命中展开 4 种形态，以及全屏手动引导（4 格轮廓拖动/两端缩放 + 刷新按钮准星 + `Snapper` 吸附）和全屏确认预览（4 槽绿框 + 识别结果核对 + 对/不对门控）；运行中若悬浮窗与禁区重叠则自动避让；面板提供「保存当前画面」导出按钮。
- `KazeAccessibilityService`：正式组装 `RefreshEngine` 与 `OverlayWindow`，监听前台包名切换（切出游戏立即暂停引擎）；删除第 5 步的临时 `A11yDebugDialog`。
- `PermissionGuideScreen`：在主界面顶栏新增「自动刷新」入口，包含无障碍服务跳转、悬浮窗开关、Android 13+「受限设置」解除指引（直达应用信息页）、国产系统后台保活说明与 Android 8~10 兼容提示。
- 新增 `OverlayStateAndPrefTest`（5 个测试），`:app:testDebugUnitTest`（6 个）、`:core-engine:test`（73 个）与 `assembleDebug` 全部通过。

合成分辨率下的识别阈值余量（架构方案第 2 步遗留的复测项，已由架构军师在 `24eb5b7` 回写第 3.2 节）：

| 指标                     | 阈值 | scale = 1 最差 | 合成图最差        |
| ------------------------ | ---- | -------------- | ----------------- |
| 问号距离                 | < 10 | 0.00           | 4.66（缩放 0.5）  |
| 正确因子距离             | ≤ 30 | 21.19          | 22.03（缩放 0.5） |
| 最小差距（不经二次比较） | ≥ 8  | 12.24          | 17.64（缩放 0.5） |

建议按以下顺序推进，每一步完成后单独提交：

1. 核心功能阶段：按 `docs/ARCHITECTURE_CORE_FEATURE.md` 第 9 节实现。**第 3 步已按架构修订拆成 3a / 3b / 3c**，以架构方案第 3.1、3.3、3.4 节和第 9 节的表为准。
   - [x] 第 3a 步：自动校准（见上文）。
   - [x] 第 3b 步：开关判断与 `ScreenState`（见上文）。
   - [x] 第 3c 步：手动吸附、位置档案与快速核验（见上文）。
   - [x] 第 4 步，`Decision`、`RefreshEngine`、`EngineState`、`FrameAnalyzer` 和 `loop/` 下的接口，用假截图器和假点击器测试（`6498756`，军师 4 项拍板已结案）。
   - [x] 第 5 步，`KazeAccessibilityService`、`A11yScreenCapturer`、`GestureTapper`、Manifest 和无障碍配置，已在真机 `vivo V2312A` 完成 ①②③ 验收（`f2105ad`）。
   - [x] 第 6 步，悬浮窗（`OverlayWindow`、`OverlayState`、`OverlayViewModel`、`OverlayContent`、`PositionSetupContent`）、`EngineSettingsDataStore`、`CalibrationProfileDataStore`、`KazeAccessibilityService` 组装 `RefreshEngine`、主应用 `PermissionGuideScreen` 与「保存当前画面」导出按钮（代码已完成并通过构建与单测，待提交 `feat(overlay): 新增悬浮窗与权限引导，打通自动刷新流程` 并进行真机端到端验收）。
   - [ ] **下一项**：真机端到端验收第 6 步（选配方 → 开始 → 自动校准确认预览 → 自动刷到命中并震动；切到桌面立即暂停；拉下通知栏暂停；用「只要无限能量」配方测 20 次记录平均刷新次数），随后按体验决定是否做第 7 步（可选 MediaProjection 高速模式）或直接进入第 8 步文档收尾。

   动手前注意：
   - **槽位验证还没有被测试真正触发过**。合成测试里所有假帮助图标都在「预测边落在画面外」或「残差超标」这一步就被拒了，`Calibrator.verifySlots` 这道防线写好了但没拦下过任何东西。要验证它，需要构造「假锚点的预测边都在画面内」的合成图（例如上下加很宽的黑边），或等补拍非因子界面截图。可交给测试质检补。
   - 帮助图标粗定位的距离分布：3 个真帮助图标始终排前 3，距离 ≤ 15.25；其他位置 ≥ 47。架构方案第 3.3 节「帮助图标距离明显过大时提前放弃」的阈值可以放在 20~40 之间，具体值等非因子界面截图补拍后再定。
   - 第 3.1 节第 2 段纵向剖面的取样列 x 1083..1086 等，架构方案明确要求**按原样实现，不要改**（已按原样实现）。
   - 第 3.2 节的识别阈值只在等比缩放下复测过。横向拉伸时只验证了 4 张截图能正确识别，没有出阈值报告。
     吸附搜索范围 `Snapper.SCALE_SEARCH_RANGE` 已按军师拍板从 ±12% 改为 ±20%（架构方案 `24eb5b7`，代码 `cdc6d38`）。**2026-11-07 已重跑 `:core-engine:test`**，原先的 4 次拒收全部转为通过，`snap-report.txt` 汇总行为「通过 140 / 140，拒收 0」。
   - `Snapper` 在 PC 上每次约 0.1 秒（140 次吸附共约 15 秒，含合成图生成），手机上的速度没测。
   - `Calibrator` 在 PC 上每帧约 0.3 秒（整屏粗定位占大头），手机上的速度还没测。校准只在开始时做一次。
   - T_anchor 和开关判断的「介于两者之间」区间（10%~50%，判为 `NOT_FACTOR_SCREEN`），需要非因子界面截图才能验证，这批截图还没补拍（架构方案第 10 节第 2、7 条）。现有 47 张只有 0% 和 100% 两种占比。
   - `CalibrationSyntheticTest` 在 `1c55b1f` 那次运行耗时 42.7 秒，`dd5041d` 这次回到 26.3 秒（`78a31ab` 时 22.7 秒），之前的变慢应是机器负载造成的，不再跟进。
   - 架构方案第 1.2 节与第 3.2 节的两处旧说法已由架构军师在 `eb0fa6e` 修正。

   架构方案第 10 节事项已确认（2026-10-31）：
   - 第 1.5 节的默认值全部采用（N = 3、次数上限 = 期望 × 3 且至少 100、默认 3 次/秒、命中只震动）。
   - Android 8~10 设备暂不支持自动刷新，发布时在说明中注明。
   - 截图与识别素材同因子图标一样获准使用，不适用 GPL-3.0，已在 `ICON_LICENSE.txt` 注明。
   - 尚缺：非因子界面截图 5~10 张（开关判断中间区间、T_anchor 与槽位验证用），以及英文界面截图（如游戏支持切换语言）。

2. 小型 UI 优化：主页面顶栏标题「KazeFactor - 配方管理」改用专有 Logo 资产。`assets/logo/` 下为 SVG，需转换为 VectorDrawable 放入 `res/drawable/` 后使用。
3. 开源发布前收尾：更新 README（项目状态、功能完成情况；第 27 行工具链版本仍为 Gradle 9.6.0 + AGP 9.4.1，需改为 9.8.2 + 9.5.0；删除与「刷新没有动画」矛盾的「帧差检测」和「OpenCV 模板匹配」两条，改为「纯 Kotlin 模板匹配」；注明 Android 8~10 不支持自动刷新；注明图标与截图不适用 GPL-3.0），然后推送到 GitHub。

---

## 9. 已知问题与待决事项

- **导入含可选因子的旧口令码**：`RecipeCodeViewModel.importCode` 会把 `optionalFactorIds` 与 `minOptional` 原样存入；而 `RecipeEditorViewModel.loadRecipe` 只读取想要与不想要，用户再次保存时可选部分会被静默丢弃。由于 UI 已不再生成可选因子，目前只影响旧口令码。处理方式待维护者决定：导入时直接丢弃，或保留现状。
- **版权声明的展示位置**：原定写在开屏页底部，但现在用的是系统默认开屏，无法显示文字。需要维护者决定改放在哪里。
- **新建配方不能直接分享**：新建配方保存前没有 id，编辑页的分享按钮为禁用状态，需要保存后从列表进入分享。
- **源码目录不统一**：`MainActivity.kt` 位于 `src/main/java/`，其余源码位于 `src/main/kotlin/`。不影响构建，可择机统一。
- **两份 factors.json**：运行时与 `core-domain` 测试各一份，修改因子数据时必须同步两处。`core-engine` 测试直接读运行时那份，不算第三份。

---

## 10. 核心功能架构

以 `docs/ARCHITECTURE_CORE_FEATURE.md` 为准（2026-10-31 交付，2026-11-01 两次修订、11-02 第三次修订，取代本节旧内容）。主要结论：

- 新建纯 Kotlin JVM 模块 `core-engine`，放识别算法和刷新状态机；依赖方向 app → core-engine → core-domain。
- 识别：纯 Kotlin 掩膜像素差模板匹配，不引入 OpenCV；模板由 `assets/factors/icons/` 原图缩放生成。
- 位置（2026-11-01 修订，11-02 再修订）：自动校准为主（帮助图标多候选粗定位 + 面板边框精修 + 槽位验证，已实现），失败时手动拖框并自动吸附；设置成功后保存位置档案，之后每次开始先做快速核验。首次设置和每次出错重设都要用户确认预览。
- 截图：先做无障碍截图（`takeScreenshot`，API 30+）；录屏高速模式等真机数据出来再决定做不做。
- 点击与悬浮窗：均由无障碍服务提供（`dispatchGesture`、`TYPE_ACCESSIBILITY_OVERLAY`），不需要悬浮窗权限。
- 持久化：继续使用 DataStore。依赖按阶段加入，版本须先查证再锁定。

---

## 11. 协作约定

- AI 助手自行完成文件读写；工程初始化、安装依赖、构建、安装、代码的 git 提交等命令整理成步骤交给维护者执行。只含文档改动的提交由 AI 助手直接执行，规则见第 4 节。
- 维护者的 PowerShell 终端输出中文会乱码。测试报告（`core-engine\build\reports\recognition\*.txt`）和测试结果 XML 由 AI 助手用 `readFile` 直接读取，不要请维护者 `type` 后粘贴。
- 环境注意事项：
  - 终端为 PowerShell 5.1。
  - `readFile` 工具无法读取 `.kts` 文件，需改用 `type`。`type` 输出中文注释会乱码，但 `editFile` 可以正常编辑 `.kts`。
  - AI 助手可以通过 `readFile` 直接查看图片（2026-11-02 维护者清理 LobeHub 存储空间后恢复，已用 `disable01.jpg`、`enable01.jpg` 验证）。若再次出现无法读取图片的情况，通常是存储空间已满，请维护者清理后重试。
- 改动代码后必须构建，涉及领域逻辑的改动还须运行测试；未运行过的检查不得表述为「已通过」。
- 采用小步迭代：每次只做一个模块或功能点，完成后由维护者真机验证并提交。
- 维护者纠正过的事实（如 R6 不阻塞、可选语义已移除）以本文为准。
