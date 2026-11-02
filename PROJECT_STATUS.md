# KazeFactor 项目交接备忘录

> 最后更新：2026-11-02
> 当前阶段：配方管理主应用（离线部分）已完成并可在真机运行。核心功能（识别与自动刷新）已完成架构方案第 9 节第 1、2 步（`core-engine` 模块、槽位模板匹配与截图答案测试），正在做第 3a 步（自动校准）：识别素材已裁出（`6514fe6`），下一项是 `Calibration`。
> 最新代码提交：`6514fe6`

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
- 刷新频率上限 1~10 次/秒，由用户设置（尚未实现）。
- 实机特征：刷新没有动画，按钮没有形变，点击后可直接截图识别。
- 测试机分辨率 2388×1080，目前仅有这一台。
- 应用桌面图标与开屏页不得使用游戏因子图标。当前的简易图标与系统默认开屏即为最终方案（见第 7.4 节）。
- 原定「开屏页底部注明因子图标版权归凉屋游戏所有」：系统默认开屏无法显示文字，这条要求需要维护者确认改放在哪里（例如应用内「关于」信息或 README），见第 9 节。

---

## 3. 工程环境（已验证可构建）

- 路径：`D:\AndroidStudioProjects\KazeFactor`；包名：`io.github.futurelesswindchan.kazefactor`
- 模块：
  - `core-domain`：纯 Kotlin JVM 模块（java-library + kotlin.jvm + kotlin.serialization），无 Android 依赖。
  - `core-engine`：纯 Kotlin JVM 模块，放识别算法与刷新状态机（架构方案第 2.1 节）。`api(project(":core-domain"))`，依赖 kotlinx-coroutines 1.9.0（与 app 传递依赖解析版本一致）。已有识别层源码（第 7.5 节），刷新状态机尚未实现。
  - `app`：Android 应用模块，依赖 `core-domain` 与 `core-engine`。
- 工具链：Gradle 9.8.2、AGP 9.5.0（**内置 Kotlin 支持，`app` 不要再添加 kotlin-android 插件**）、Kotlin 2.2.10、Compose BOM 2026.02.01、compileSdk / targetSdk 37、minSdk 26、Java 11 字节码。
- 主要依赖（版本见 `gradle/libs.versions.toml`）：navigation-compose 2.10.2、lifecycle-viewmodel-compose 2.11.0、datastore-preferences 1.2.0、kotlinx-serialization-json 1.8.0、material-icons-extended。
- 已删除 `gradle/gradle-daemon-jvm.properties`（它要求下载 JDK 25，网络失败），并在 `gradle.properties` 中设置 `org.gradle.java.installations.auto-download=false`。`.gitignore` 也已忽略该文件。
- 常用命令（PowerShell 5.1，不支持 `&&`，请用 `;`）：
  - 领域层单元测试：`.\gradlew.bat :core-domain:test --console=plain`，结果见 `core-domain\build\test-results\test\*.xml`
  - 识别层单元测试：`.\gradlew.bat :core-engine:test --console=plain`，结果见 `core-engine\build\test-results\test\*.xml`；阈值报告、模板可分性报告和锚点测量报告写入 `core-engine\build\reports\recognition\`（不提交）
  - 重新裁取识别素材（平时自动跳过）：`.\gradlew.bat :core-engine:test --tests "*RecognitionAssetGenerator*" '-Pkaze.generateRecognitionAssets' --console=plain`。**`-P` 参数必须加单引号**，否则 PowerShell 会在点号处把它拆开，Gradle 报「Task '.generateRecognitionAssets' not found」（2026-11-02 实测）
- Gradle Wrapper 已于 `7e193d5` 用 9.8.2 重新生成，恢复了分发包 SHA-256 校验（`distributionSha256Sum`）。升级 Gradle 时请用 `gradlew wrapper --gradle-distribution-sha256-sum` 一并更新校验和。
- 构建会提示「Deprecated Gradle features ... incompatible with Gradle 10」，目前不影响使用，升级 Gradle 10 前用 `--warning-mode all` 排查。
  - 构建：`.\gradlew assembleDebug`；安装到设备：`.\gradlew installDebug`

---

## 4. Git 状态与提交约定

- 最新代码提交为 `6514fe6`；本文件每次更新单独以 `docs(status)` 提交。
- 提交约定（维护者要求）：
  - 每完成一个最小可提交的进度就提交一次。
  - 提交信息格式为「英文类型前缀 + 中文主体」，例如 `feat(ui): 新增因子选择池紧凑图标网格模式`。
  - 构建、安装等有副作用的命令由维护者亲自执行。`git add/commit` 默认也由维护者执行；维护者明确授权某次提交时，AI 助手可代为执行，授权只对当次有效。

近期提交（新 → 旧）：

| 提交 | 内容 |
| --- | --- |
| 6514fe6 | 新增识别素材生成工具，裁出 `dice.png` 与 4 个问号模板；刷新点击位置改为 (1377, 433)，新增 `DICE_RECT` |
| 7a707e5 | 架构修订：校准改为骰子粗定位 + 面板边框精修，新增手动吸附与位置档案（架构军师） |
| 4259e92 | 新增锚点测量报告，按问号与已刷新截图分组统计 |
| 0cc2cf1 | 更新 AI 助手可查看图片的协作说明（本文件） |
| 353949a | 同步第 2 步完成状态与第 3 步注意事项（本文件） |
| 78dce74 | 实现槽位模板匹配与截图答案测试（架构方案第 9 节第 2 步）；架构方案第 3.2 节回写实测阈值 |
| 30a1450 | 同步第 1 步完成状态与工具链升级（本文件） |
| 7e193d5 | 以 9.8.2 重新生成 Gradle Wrapper，恢复分发包校验和 |
| 1fc315e | IDE 配置登记 core-engine 模块，新增项目拼写词典 |
| 0138436 | 升级 Gradle 9.8.2 与 AGP 9.5.0 |
| 09cd2cf | Manifest 移除未使用的 tools 命名空间与重复的 Activity label |
| e287a7e | 新建 core-engine 模块并迁移实机截图；新增答案 `labels.csv`；ICON_LICENSE 扩展适用范围 |
| eb4f44f | 新增核心功能需求交接、架构方案与调研报告 |
| c9de958 | 同步测试结果、清理与白屏修复进度（本文件） |
| a1e1a6e | 修复输入框焦点无法清除、保存后重复返回导致白屏 |
| c051f36 | 剪贴板迁移至 `LocalClipboard`；删除无引用的模板图标资源与空类 |
| 4ccf01a | 对照仓库现状重写本备忘录 |
| 91b3630 | 忽略 Android Studio 设备管理器界面配置 |
| eddd1e1 | 天蓝底表情包自适应图标；README 引入艺术字 Logo；清理根目录冗余素材 |
| 24f2360 | 已选与排除因子置顶预览槽、列表卡片图标展示、分类名汉化补全 |
| 24d2c6e | 因子选择池紧凑图标网格模式与视图切换 |
| dc3f5e4 | 接入真实因子像素图标与选中状态角标 |
| 4ec3a99 | 移除可选因子状态与数量滑块，简化为三态点选 |
| 90a34ae | 修复编辑页布局跳动，整合概率横幅，修正主题配色 |
| 8dcc172 | 集成 Navigation Compose 与 ViewModelFactory |
| 090b2f9 及更早 | 数据层、三大界面、主题、口令码、规则引擎、领域模型 |

---

## 5. 资源位置

| 资源 | 位置 | 说明 |
| --- | --- | --- |
| 因子数据（运行时） | `app/src/main/assets/factors.json` | 由 `AssetFactorLoader` 加载 |
| 因子数据（测试用） | `core-domain/src/test/resources/factors.json` | 领域层测试读取；与上一份为两份独立副本，修改时须同步 |
| 因子图标（运行时） | `app/src/main/res/drawable/*.png`（59 个） | 由 `FactorIconMapper` 按 `key` 动态查找 |
| 因子图标（识别模板） | `app/src/main/assets/factors/icons/*.png`（59 个） | 与 `res/drawable` 内容相同（MD5 一致）。按架构方案保留，作为识别模板的唯一来源；`res/drawable` 解码时会按屏幕密度缩放，不能用作模板 |
| 识别素材 | `app/src/main/assets/recognition/`：`dice.png`（33×38）、`question_slot1~4.png`（各 90×90） | 由 `RecognitionAssetGenerator` 从 `disable01.jpg` 裁出（`6514fe6`），已看图核对。版权同因子图标，`ICON_LICENSE.txt` 已覆盖 |
| 实机截图 | `core-engine/src/test/resources/screenshots/`（47 张，从 `app/src/androidTest/assets/` 迁入） | `disable01~07` 为问号状态，`enable01~40` 为已刷新状态。答案文件 `labels.csv`（值为因子 key，问号写 `?`）已由维护者核对，全部正确（2026-10-31） |
| README Logo | `assets/logo/`（`KAZE FACTOR.svg`、`K.svg`、`Pinia.png/.webp`） | 仅供 README 使用，不打包进 APK |
| 应用图标 | `res/mipmap-anydpi/ic_launcher*.xml` + `res/drawable/ic_launcher_foreground_emoji.xml` + `res/mipmap-*/ic_launcher_emoji.png` | 见第 7.4 节 |

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
KazeFactorApplication.kt     全局手动依赖注入：factorDatabase、recipeRepository（lazy 单例）
MainActivity.kt              Surface + NavHost，串联三个界面；从 Application 取依赖；
                             popBackStackFrom(entry) 仅在页面处于 RESUMED 时返回，防止重复返回弹空返回栈（白屏）
util/AssetFactorLoader.kt    从 assets/factors.json 加载 FactorDatabase
data/
  datastore/RecipeDataStore.kt     DataStore Preferences（文件名 recipe_storage，键 recipes_json），
                                   存配方 JSON 列表；含未知因子 id 的配方在读取时被过滤
  repository/RecipeRepository.kt   单例；封装 DataStore、因子查询、RuleEngine、ProbabilityCalculator
domain/viewmodel/
  ViewModelFactory.kt          手动工厂，参数为 repository、database、可选 recipeId
  RecipeListViewModel.kt       配方列表、删除、摘要、概率、按 id 取因子
  RecipeEditorViewModel.kt     三态选择、实时校验与概率、autoFix、保存
  RecipeCodeViewModel.kt       口令码生成与导入
ui/
  navigation/Navigation.kt     字符串路由：recipe_list、recipe_editor?recipeId=、recipe_code?recipeId=
  screen/RecipeListScreen.kt   配方列表；卡片展示想要/排除因子图标；顶栏「导入口令」入口
  screen/RecipeEditorScreen.kt 配方编辑；固定高度的概率横幅（含低概率标签与已选/排除置顶槽）；
                               图标网格 / 列表视图切换（默认网格）；键盘收起、点击空白或因子、
                               按完成键、保存时清除输入框焦点；保存成功以 Toast 提示并立即返回，
                               保存中禁用保存按钮
  screen/RecipeCodeScreen.kt   recipeId 非空为导出模式，为空为导入模式；复制使用 LocalClipboard
  component/FactorSelector.kt  FactorSelectionState（NONE/WANTED/UNWANTED）、FactorDisplayMode、
                               列表项、网格项、CompactFactorChip、状态角标、分类中文名
  component/RuleViolationCard.kt  阻塞性错误卡片与一键修复
  util/FactorIconMapper.kt     按 key 查找 drawable，以 FilterQuality.None 渲染以保持像素清晰
  theme/                       天蓝主色 #38BDF8；浅灰 / 墨蓝双主题；已覆盖 surfaceContainer* 以消除 M3 默认紫色
```

`app` 模块仅有模板自带的 `ExampleUnitTest` 与 `ExampleInstrumentedTest`，没有业务测试。

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
  RecognitionModels.kt  SlotResult（Question / Factor / Ambiguous / Unknown）、FrameResult、PixelRect
  ReferenceLayout.kt    2388×1080 参考坐标：槽位、点击刷新位置 (1377, 433)、骰子 DICE_RECT、搜索边距 4 px
  TemplateBank.kt       图标 → 90×90 透明画布上的掩膜模板（alpha ≥ 200）；尺寸系数覆盖表；按槽位剪枝候选
  SlotMatcher.kt        MatchThresholds；问号比较 → 粗搜 ±2 → 前 3 名精修 ±3 → 差距不足时二次比较
  FactorRecognizer.kt   一帧 → FrameResult；目前只支持 2388×1080（scale = 1，未校准）
```

尚未实现：`Calibration`、`Calibrator`（第 3a 步）；开关判断与 `ScreenState`（第 3b 步）；`Snapper`、`CalibrationProfile`、`ProfileVerifier`（第 3c 步）；`loop/` 下的接口与状态机（第 4 步）。

测试（`core-engine/src/test/kotlin/.../engine/`）：

- `testutil/TestFixtures.kt`：经系统属性读 app 的 `factors.json` 与图标，经 classpath 读截图与 `labels.csv`。问号模板暂时从 `disable01.jpg` 现场裁取。
- `vision/ScreenshotAnswerTest`(2)：47 张截图逐槽核对答案。
- `vision/ThresholdReportTest`(1)：只出报告不断言。
- `vision/TemplateSeparabilityTest`(2)：59 个模板合成到黑色、中灰背景上识别为自己；各槽候选数 28 / 37 / 39 / 16。
- `vision/AnchorSurveyTest`(1)：锚点测量，只出报告。按问号截图、已刷新截图分组统计均值与标准差，输出字符图、前景块外框和均值 / 标准差 PNG。架构修订（`7a707e5`）的数据来源。
- `vision/RecognitionAssetGenerator`(1)：素材生成工具，平时跳过，用法见第 3 节。

2026-11-01 已运行，前 3 个测试类共 5 个测试全部通过（0 failures / 0 errors），188 个槽位全部正确，没有 `Ambiguous`。阈值实测与余量见架构方案第 3.2 节，初值 10 / 30 / 8 / 8 全部保留。2026-11-02 最后一次全量运行：6 个通过，1 个跳过（素材生成器）。之后 `RecognitionAssetGenerator.kt` 只改了文档注释，还没重新编译过。

---

## 8. 下一步

已完成（2026-10-31）：
- 领域层测试 85 个全部通过；小型清理（`c051f36`）；编辑页焦点与重复返回白屏修复（`a1e1a6e`），均已真机验证。
- 架构方案第 9 节第 1 步（`e287a7e`）：`core-engine` 模块建好，`:core-engine:test` 与 `assembleDebug` 均构建通过；截图与 `labels.csv` 已迁入 `core-engine/src/test/resources/screenshots/`。

已完成（2026-11-01）：
- 架构方案第 9 节第 2 步（`78dce74`）：识别层与前 3 类测试，`:core-engine:test` 5 个测试全部通过，详见第 7.5 节。本步只改了 `core-engine`，没有动 app，未运行 `assembleDebug`。

已完成（2026-11-02）：
- 锚点测量（`4259e92`）→ 架构军师据此修订校准方案（`7a707e5`）→ 裁出识别素材（`6514fe6`）。`6514fe6` 往 `app/src/main/assets/` 加了 5 个 PNG，没有改 app 的代码，未运行 `assembleDebug`。

建议按以下顺序推进，每一步完成后单独提交：

1. 核心功能阶段：按 `docs/ARCHITECTURE_CORE_FEATURE.md` 第 9 节实现。**第 3 步已按架构修订（`7a707e5`）拆成 3a / 3b / 3c**，以架构方案第 3.1、3.3、3.4 节和第 9 节的表为准。

   第 3a 步（自动校准）进度：
   - [x] 锚点测量（`4259e92`）：骰子 x 1475..1507、y 125..162 在开关开、关两种状态下完全相同；循环箭头和标题文字在两种状态下外观不同，箭头作废为锚点。
   - [x] 识别素材（`6514fe6`）：`assets/recognition/` 下的 `dice.png` 和 4 个问号模板，已看图核对。
   - [ ] **下一项**：`Calibration`，按像素边界换算 `屏幕 = origin + scale × 参考`，**直接用 `scaleX`、`scaleY` 两个字段**（维护者 2026-11-02 确认），自动校准两个字段填相同值。`ReferenceLayout` 补上面板竖条 x 966 / 1006 / 1747 / 1789，横边 y 236 / 350 / 690，以及剖面取样的行列范围（架构方案第 3.1 节表格）。单元测试要覆盖 scale ≠ 1。
   - [ ] `Calibrator` 第 1 段：骰子粗定位（缩放 0.80~1.20 倍、步长 0.02，1/4 分辨率全屏粗搜，再在原分辨率精修 ±4 px）。
   - [ ] `Calibrator` 第 2 段：边缘精修（梯度 + 抛物线插值、最小二乘、残差 ≤ 1.5 px、槽位验证、最多 3 帧）。
   - [ ] `CalibrationSyntheticTest`：验收第 9 节第 3 步第 ①②③ 条；`FactorRecognizer` 接入 `Calibration`，去掉只接受 2388×1080 的限制。全部通过后提交 `feat(engine): 实现骰子粗定位与面板边框精修校准`。

   动手前注意：
   - 问号模板已经在 `assets/recognition/` 里了，测试可通过系统属性 `kaze.recognitionDir` 读取（已在 `core-engine/build.gradle.kts` 配好）。`TestFixtures.questionTemplates` 还在从 `disable01.jpg` 现场裁取（代码里有 TODO），接入校准时一并改掉。
   - 第 3.1 节第 2 段纵向剖面的取样列 x 1083..1086 等，架构方案明确要求**按原样实现，不要改**。
   - 第 3.1 节要求统一按「像素边界」换算，表中坐标是「跳变后第一个像素」的下标，正好等于边界坐标。
   - 第 3c 步时横纵缩放可以不等，到时 `TemplateBank` 要支持按两个方向分别缩放，目前只支持一个 `scale`。
   - 第 2 步的阈值实测只覆盖 scale = 1，合成分辨率下要复测，并回写架构方案第 3.2 节。
   - T_anchor 和开关判断的「介于两者之间」区间，需要非因子界面截图才能验证，这批截图还没补拍（架构方案第 10 节第 2、7 条）。
   - 架构方案里有两处旧说法，留给架构军师决定是否修改：第 1.2 节「界面锚点（标题）」，现在应该是骰子；第 3.2 节「易混图标」第 1 条还写着「或者只有一方不透明」「效果要等第 2 步的测试结果」，和同一节下方的实测说明不一致。

   架构方案第 10 节事项已确认（2026-10-31）：
   - 第 1.5 节的默认值全部采用（N = 3、次数上限 = 期望 × 3 且至少 100、默认 3 次/秒、命中只震动）。
   - Android 8~10 设备暂不支持自动刷新，发布时在说明中注明。
   - 截图与识别素材同因子图标一样获准使用，不适用 GPL-3.0，已在 `ICON_LICENSE.txt` 注明。
   - 尚缺：非因子界面截图 5~10 张（第 3 步用），以及英文界面截图（如游戏支持切换语言）。
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

以 `docs/ARCHITECTURE_CORE_FEATURE.md` 为准（2026-10-31 交付，2026-11-01 两次修订，取代本节旧内容）。主要结论：

- 新建纯 Kotlin JVM 模块 `core-engine`，放识别算法和刷新状态机；依赖方向 app → core-engine → core-domain。
- 识别：纯 Kotlin 掩膜像素差模板匹配，不引入 OpenCV；模板由 `assets/factors/icons/` 原图缩放生成。
- 位置（2026-11-01 修订）：自动校准为主（骰子粗定位 + 面板边框精修），失败时手动拖框并自动吸附；设置成功后保存位置档案，之后每次开始先做快速核验。首次设置和每次出错重设都要用户确认预览。
- 截图：先做无障碍截图（`takeScreenshot`，API 30+）；录屏高速模式等真机数据出来再决定做不做。
- 点击与悬浮窗：均由无障碍服务提供（`dispatchGesture`、`TYPE_ACCESSIBILITY_OVERLAY`），不需要悬浮窗权限。
- 持久化：继续使用 DataStore。依赖按阶段加入，版本须先查证再锁定。

---

## 11. 协作约定

- AI 助手自行完成文件读写；工程初始化、安装依赖、构建、安装、git 写操作等命令整理成步骤交给维护者执行。
- 环境注意事项：
  - 终端为 PowerShell 5.1。
  - `readFile` 工具无法读取 `.kts` 文件，需改用 `type`。`type` 输出中文注释会乱码，但 `editFile` 可以正常编辑 `.kts`。
  - AI 助手可以通过 `readFile` 直接查看图片（2026-11-02 维护者清理 LobeHub 存储空间后恢复，已用 `disable01.jpg`、`enable01.jpg` 验证）。若再次出现无法读取图片的情况，通常是存储空间已满，请维护者清理后重试。
- 改动代码后必须构建，涉及领域逻辑的改动还须运行测试；未运行过的检查不得表述为「已通过」。
- 采用小步迭代：每次只做一个模块或功能点，完成后由维护者真机验证并提交。
- 维护者纠正过的事实（如 R6 不阻塞、可选语义已移除）以本文为准。
