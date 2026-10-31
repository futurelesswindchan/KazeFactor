# KazeFactor 核心功能架构：识别与自动刷新

> 编写：架构设计方 · 2026-10-31
> 回应：`docs/HANDOFF_CORE_FEATURE.md`（基线 `c9de958`）
> 本文与 `PROJECT_STATUS.md` 第 10 节冲突时以本文为准（不用 OpenCV、不再单独做悬浮窗服务等）。
> 标「待确认」的条目需要维护者拍板，汇总在第 10 节。

---

## 0. 决策依据：截图离线实验

定方案前，用 PC 上的 C# 原型（System.Drawing，单线程，未优化，脚本不进仓库）对 47 张实机截图和 59 个原始图标做了识别实验。数字只用于判断可行性，正式阈值在第 9 节第 2 步用 Kotlin 实现重新测量。

| 项目 | 结果 |
| --- | --- |
| 分辨率 | 47 张都是 2388×1080 |
| 槽位位置 | 逐像素方差分析：4 个槽位左上角 x = 1088 / 1251 / 1414 / 1577，y = 248，各 90×90 px，间距 163，所有截图一致 |
| 其他元素（10 px 粗定位） | 标题约 x 1250–1540、y 132–180；刷新按钮长条约 x 1070–1700、y 384–480，循环箭头中心约 (1380, 432)；开关约 x 1320–1440、y 588–636 |
| 面板位置 | 槽位中心线 x≈1377，屏幕中心 x=1194。面板不在正中，布局不能假设居中 |
| 稳定锚点 | 标题区域（含骰子）在 47 张图之间最大差异 21.3，刷新按钮区域是 41.7（开关开/关时按钮外观可能不同，原因未确认）。标题文字会随游戏语言变化，所以最终选骰子图标做锚点（第 3.1 节） |
| 匹配方法 | 图标原图缩放后只比较不透明像素（alpha ≥ 200）的 RGB 平均绝对差（0~255），在 ±4 px 内取最优偏移 |
| 图标显示尺寸 | 出现过的 50 个图标里 47 个在游戏中长边约 90 px；「好运气」「天赋异禀」约 88 px；「武器重铸」约 76 px（按 90 px 匹配距离 31.8，按 76 px 降到 9.8）。所以每个因子要按自己的显示尺寸生成模板 |
| 正确匹配距离 | 按各自尺寸匹配后约 3~23 |
| 区分度 | 统一按 90 px 匹配时，除「天赋」系列和「武器重铸」外，最优与次优至少差约 29；「武器重铸」差 19（按 76 px 匹配后最优距离降到 9.8，次优未重测）。「天赋位置+1」与另外两个约差 14。**「天赋选择+1」与「天赋自助餐」只差 2~3**（两张图标彼此距离 3.8），而第 3 槽两者都可能出现 |
| 问号状态 | 7 张问号截图的 4 个槽位像素完全一致（距离 0.0），与所有因子图标距离 ≥ 35.7 |
| 覆盖率 | 40 张已刷新截图只有 27 种组合、50 个因子。未出现的 9 个：脆皮、武器过热、亢奋的敌人、无知觉、全面戒备、跟踪激光、箱子突变、跑起来的敌人、复活苏生 |
| 速度 | 59 模板 × 25 偏移，每槽约 82 ms（含 JPEG 解码）。按第 3.2 节剪枝后会明显下降，需在手机上实测 |

答案草稿 `app/src/androidTest/assets/screenshots/labels_draft.csv` 是原型的输出。已用 17 种组合和 11 组冲突校验，40 行全部合法，但仍须维护者肉眼核对，否则测试就成了识别器自己验证自己。

---

## 1. 第 5 节问题的结论

### 1.1 截图方案（5.1）

结论：**两种截图都要支持，用同一个 `ScreenCapturer` 接口隔开。先交付无障碍截图；MediaProjection 作为「高速模式」放在第 9 节第 7 步，等真机数据出来再决定做不做。** 频率设置保持 1~10 次/秒，不下调，界面上显示当前截图方式实际能达到的上限。minSdk 保持 26。

| | 无障碍截图 `takeScreenshot()` | MediaProjection（录屏） |
| --- | --- | --- |
| 系统版本 | API 30+（Android 11） | API 21+ |
| 频率 | AOSP 源码里两次调用的最小间隔常量是 333 ms[^a11y]，约 3 次/秒。各版本和厂商 ROM 是否相同未查证，必须真机实测 | 跟随屏幕刷新，没有这个限制 |
| 授权 | 开启一次无障碍服务，长期有效 | 每次开始都要用户点同意；Android 14 起一次同意只能用一次[^mp] |
| 额外负担 | 无 | 必须运行 `mediaProjection` 类型的前台服务并显示常驻通知；Android 15 QPR1 起状态栏有醒目的录屏标记，锁屏会自动停止[^mp] |

理由：

- 点击只能靠无障碍服务的 `dispatchGesture`，所以无障碍服务无论如何都要开。截图也用它，用户只需授权一次。
- 3 次/秒是否够用可以估算：「无限能量 + 特卖日」期望约 584 次，3 次/秒约 3.3 分钟，10 次/秒约 1 分钟。游戏本身能否每秒处理 10 次刷新也还不知道。先拿到真机数据，再决定是否多维护一条截图路径。
- minSdk 不需要为截图调高。Android 8~10 的设备照样能用配方管理，「自动刷新」入口提示需要 Android 11 及以上；做了高速模式后，这些设备也能用。

### 1.2 识别技术（5.2）

结论：**不引入 OpenCV，用纯 Kotlin 实现「带透明掩膜的像素差模板匹配」。模板由 `assets/factors/icons/` 里的图标原图在开始刷新前缩放生成并缓存；`res/drawable` 那份继续给界面用，两份都保留。**

- 第 0 节的实验表明，最朴素的逐像素比较就能把绝大多数图标拉开约 29 的差距，难点只集中在「天赋」系列。OpenCV 在这里没有提供必需的能力，反而要打包 native 库（体积大，需要按 ABI 拆分 APK），也不能直接在纯 JVM 单元测试里运行。Maven Central 上 `org.opencv:opencv` 当前最新版本是 5.0.0.1（已查证[^opencv]），留作识别不够用时的后备。
- 模板不从截图裁取。有 9 个因子从没在截图里出现过，裁不出来。原图带透明通道，正好当掩膜用，只比较图标本身的像素，不受槽位背景影响。
- 个别图标在游戏里显示得更小（「武器重铸」长边约 76 px，多数约 90 px），所以每个因子有自己的缩放系数，默认 1.0，实测值写在代码里的覆盖表中（第 3.2 节）。
- 界面锚点（标题）和 4 个问号模板只能从实机截图裁出，放在 `app/src/main/assets/recognition/`。它们同样是凉屋的美术资源，见第 10 节第 4 条。

### 1.3 模块边界（5.3）

结论：**新建纯 Kotlin JVM 模块 `core-engine`，放识别算法和刷新状态机；`app` 只负责 Android 系统对接（截图、点击、悬浮窗、权限）。**

```
app ──► core-engine ──► core-domain
 │                        ▲
 └────────────────────────┘
```

- 不放进 `core-domain`：`core-domain` 是规则与数据，识别和状态机是另一类职责，还会引入协程依赖。分开以后两个模块的测试互不干扰。
- 不放进 `app`：放在 `app` 里只能用仪器测试，必须连手机才能跑。放在 JVM 模块里，`.\gradlew.bat :core-engine:test` 在电脑上几秒就能跑完 47 张截图。
- 图片在 `core-engine` 里统一表示为 `ArgbImage(width, height, pixels: IntArray)`，不依赖 Android 的 `Bitmap`。缩放和裁剪也用纯 Kotlin 实现，这样电脑上的测试和手机上运行时生成的模板逐像素一致。
- 状态机通过 3 个接口和外界打交道，测试时换成假实现：

| 接口 | 作用 | app 中的实现 | 测试中的假实现 |
| --- | --- | --- | --- |
| `ScreenCapturer` | 取一帧画面，并报告最小截图间隔 | `A11yScreenCapturer` | 按顺序返回截图文件 |
| `Tapper` | 在屏幕坐标点击一次 | `GestureTapper` | 记录点击坐标 |
| `FactorRecognizer` | 一帧 → 4 个槽位结果 | （就在 core-engine 里） | 可直接用真实现 |

### 1.4 测试策略（5.4）

结论：**47 张截图全部用于 `core-engine` 的 JVM 单元测试；仪器测试暂不写，真机部分靠第 9 节里的手动验证步骤。**

- 截图从 `app/src/androidTest/assets/screenshots/` 移到 `core-engine/src/test/resources/screenshots/`，因为它们只给识别测试用。
- 图标继续只保留 `app/src/main/assets/factors/icons/` 一份。测试通过 Gradle 传入的系统属性读取这个目录，不复制。
- 答案文件 `labels.csv`，与截图放在一起，格式：`file,slot1,slot2,slot3,slot4`。值用因子 `key`（比 id 好核对），问号状态写 `?`。测试启动时通过 `factors.json` 把 key 换成 id，未知 key 直接报错。
- 由维护者核对 `labels_draft.csv`，确认无误后改名为 `labels.csv`。
- 测试分 4 类：
  1. **逐张答案测试**：每张截图 4 个槽位的结果必须等于答案。
  2. **阈值报告**：打印所有正确匹配的最大距离和最小差距，作为调整阈值的依据。
  3. **模板可分性**：59 个模板两两比较。这一项也覆盖了截图里没出现过的 9 个因子。
  4. **合成分辨率**：把截图缩放、加黑边，模拟其他分辨率，验证校准（第 3.1 节）。
- 目前缺少「不是因子界面」的截图，这类负样本要请维护者补拍（第 10 节）。

### 1.5 交互细节（5.5）

| 问题 | 结论 | 理由 |
| --- | --- | --- |
| 命中提醒 | 悬浮窗展开并高亮 4 个因子，同时短震动（可在设置里关闭）。不发通知，不放声音 | 命中时玩家正看着屏幕。通知在 Android 13+ 要额外申请运行时权限；联机时玩家常开语音，声音容易打扰 |
| 识别失败 N | **N = 3**。任一槽位 UNKNOWN、或 4 个结果不符合 17 种组合/冲突规则时，**不点刷新**，重新截图；连续 3 帧仍失败就暂停 | 看不懂的画面绝不能刷掉，否则可能把玩家想要的组合刷没。不点击时画面不变，重试只用来排除偶发的截图问题，3 次足够 |
| 刷新无效 | 点击后连续 3 轮识别出完全相同的 4 个因子，判定点击没生效并暂停 | 合法刷新连续 3 次相同的概率可以忽略 |
| 总次数上限 | 需要。默认值 = 期望次数 × 3（至少 100），用户可改或关闭 | 按 `ProbabilityCalculator` 的等概率假设，约 95% 的情况会在上限内命中（1 − e⁻³）。等概率假设未经验证 |
| 默认频率 | 3 次/秒 | 与无障碍截图的上限一致 |

---

## 2. 模块与包结构

包名前缀 `io.github.futurelesswindchan.kazefactor`，下文省略。

### 2.1 core-engine（新建，纯 Kotlin JVM）

```
core-engine/src/main/kotlin/.../engine/
  image/
    ArgbImage.kt          width、height、pixels: IntArray（0xAARRGGBB）
    ImageOps.kt           crop、面积平均缩放、贴到指定背景上
  vision/
    ReferenceLayout.kt    参考分辨率 2388×1080 下各元素的坐标常量（第 0 节测得）
    Calibration.kt        scale、originX、originY；把参考坐标换算成当前屏幕坐标
    Calibrator.kt         在整帧里搜索锚点，得到 Calibration
    TemplateBank.kt       图标原图 → 各槽候选模板（按 scale 和每个因子的尺寸系数生成并缓存）
    SlotMatcher.kt        掩膜像素差 + 偏移搜索 + 易混图标二次比较
    FactorRecognizer.kt   一帧 → FrameResult
    RecognitionModels.kt  ScreenState、SlotResult、FrameResult
  loop/
    ScreenCapturer.kt     interface：suspend fun capture(): Frame；val minIntervalMs: Long
    Frame.kt              interface：width、height、crop(x, y, w, h): ArgbImage、close()
                          只按需读取锚点和槽位区域的像素，不把整屏 260 万像素转成 IntArray
    Tapper.kt             interface：suspend fun tap(x: Float, y: Float): Boolean
    RefreshSettings.kt    频率、次数上限、N
    Decision.kt           FrameResult + Recipe → HIT / MISS / UNCERTAIN
    RefreshEngine.kt      状态机，对外暴露 StateFlow<EngineState>
    EngineState.kt        Idle / Calibrating / Running / Hit / Paused(reason) / Stopped

core-engine/src/test/
  kotlin/.../engine/
    testutil/   ImageIO 读图转 ArgbImage、读 labels.csv、读 factors.json
    vision/     ScreenshotAnswerTest、ThresholdReportTest、TemplateSeparabilityTest、CalibrationSyntheticTest
    loop/       RefreshEngineTest（假截图器 + 假点击器 + 虚拟时间）
  resources/screenshots/   47 张 jpg + labels.csv（从 app/src/androidTest/assets 移来）
```

依赖方向：`core-engine` 用 `api(project(":core-domain"))`，因为它的公开接口里直接用到 `Recipe`、`FactorDatabase`。

### 2.2 app（新增部分）

```
app/src/main/kotlin/.../
  service/
    KazeAccessibilityService.kt   无障碍服务：持有截图器、点击器、悬浮窗和 RefreshEngine
    A11yScreenCapturer.kt         takeScreenshot → HardwareBuffer → ArgbImage
    GestureTapper.kt              dispatchGesture 点击，挂起等待完成回调
    ServiceLifecycleOwner.kt      给 Compose 提供 Lifecycle 和 SavedState（服务里没有 Activity）
  overlay/
    OverlayWindow.kt              把 ComposeView 加到 WindowManager，负责移动、缩放、避让
    OverlayContent.kt             小球 / 面板 / 运行中 / 命中 四种形态
    OverlayViewModel.kt           连接 RefreshEngine.state 与界面
  data/datastore/
    EngineSettingsDataStore.kt    频率、次数上限倍数、震动开关、上次选的配方（新文件 engine_settings）
  ui/screen/
    PermissionGuideScreen.kt      权限引导与「进入悬浮窗模式」入口
  OverlayController.kt            进程内单例：App 界面请求显示悬浮窗，服务监听并响应
```

### 2.3 Android 概念速查（给维护者）

| 概念 | 在本项目里的用途 |
| --- | --- |
| AccessibilityService（无障碍服务） | 系统授予的高权限后台服务。用户在系统设置里手动开启后，系统负责启动它。本项目用它的三项能力：截屏、模拟点击、显示悬浮窗 |
| `TYPE_ACCESSIBILITY_OVERLAY` | 无障碍服务专用的悬浮窗类型。不需要「显示在其他应用上层」权限，服务关闭时窗口自动消失 |
| WindowManager | 系统窗口管理器。悬浮窗就是用它把一个 View 直接加到屏幕上 |
| ComposeView + ViewTree 所有者 | Compose 要靠 Lifecycle 和 SavedState 才能运行，Activity 会自动提供，服务里要自己提供，这就是 `ServiceLifecycleOwner` 的作用 |
| `dispatchGesture` | 让系统在指定坐标注入一次触摸。触摸会落到该位置最上层的窗口，所以悬浮窗不能盖住刷新按钮 |
| HardwareBuffer | `takeScreenshot` 返回的显存图像。要先复制成普通 Bitmap 才能读像素 |
| DataStore | 已在使用的键值持久化库 |

---

## 3. 识别算法

### 3.1 校准（只在开始时做一次）

目的：求出当前屏幕相对参考截图（2388×1080）的缩放系数 `scale` 和面板偏移 `(originX, originY)`。之后所有坐标都按 `参考坐标 × scale + origin` 换算。

1. 初始猜测 `scale = 屏幕高度 / 1080`。Unity 横屏游戏的界面多数按高度缩放，但这只是猜测，所以第 2 步会搜索。
2. 主锚点用标题右侧的**骰子图标**，副锚点用刷新按钮中央的**循环箭头**。不用标题文字，因为英文等其他语言版本的标题文字不同（现有截图全是中文，这一点未验证）。两个锚点的精确矩形在第 9 节第 3 步从截图里量出。在 `scale × 0.80~1.20`（步长 0.02）范围内，先在 1/4 分辨率下粗搜位置，再在原分辨率下精修 ±4 px。面板不居中，所以横向要搜整个屏幕宽度。
3. 验证：按结果算出 4 个槽位，每个槽位必须要么像问号，要么能匹配到某个因子。验证不过就判定校准失败，暂停并提示「请确认停在挑战因子界面」。
4. 结果按「屏幕宽 × 高」缓存在内存里。截图尺寸变化（旋转、分屏）时重新校准。

只有一台测试机，多分辨率只能靠合成测试（把截图缩放并加黑边）验证算法本身。真实设备上的表现要等用户反馈，所以悬浮窗要提供「保存当前画面」按钮，方便用户提交问题。

### 3.2 槽位匹配（每帧 4 次）

**模板生成**（校准后一次性完成，缓存在内存）：

- 图标原图按「长边 = 90 × scale × 尺寸系数」缩放，居中贴到槽位大小的透明画布上。缩放用纯 Kotlin 的面积平均算法，测试和运行时结果一致。
- 尺寸系数默认 1.0。实测覆盖值：`reforge_weapon` 0.84、`good_luck` 0.98、`exceptionally_talented` 0.98。截图中没出现过的 9 个因子暂用 1.0，属于**未验证**，需要真机遇到时补测。
- 缩放后 alpha ≥ 200 的像素作为掩膜，只比较这些像素。

**候选剪枝**：每个槽位只比较 `Factor.canAppearInSlot()` 允许的因子。第 1~4 槽分别是 28、37、39、16 个，合计 120 次比较，原来是 236 次。

**距离**：掩膜内像素 RGB 三通道平均绝对差，范围 0~255。偏移搜索分两轮：所有候选在 ±2 px（步长 2）内搜索，距离最小的 3 个再在 ±3 px（步长 1）内精修。

**判定**（阈值是初值，第 9 节第 2 步按测试报告调整）：

| 条件 | 结果 |
| --- | --- |
| 与该槽问号模板距离 < 10 | `Question` |
| 最优距离 ≤ 30，且次优 − 最优 ≥ 8 | `Factor(id)` |
| 最优距离 ≤ 30，但差距不足 | `Ambiguous(候选 id 集合)`，集合里放距离在最优 + 8 以内的因子 |
| 其他 | `Unknown` |

**易混图标**：「天赋选择+1」和「天赋自助餐」的差距只有 2~3，正常情况下会落到 `Ambiguous`。处理分两层：

1. 二次比较：只在两张模板差异明显的像素上（两者都不透明且 RGB 差 > 40，或者只有一方不透明）重新计算距离。原型没测过这一步，效果要等第 2 步的测试结果。
2. 就算二次比较还是分不开，也不一定要停。`Decision` 会把每个 `Ambiguous` 槽位展开成所有可能的组合，先去掉违反 17 种组合或冲突规则的组合，再逐个用配方判断。如果所有可能都是「未命中」，照常刷新；都是「命中」，照常停下；只有结论不一致时才算识别失败。配方里没有勾选这两个因子时，它们分不分得清对结果没有影响。

### 3.3 画面状态（每帧判断）

- `FACTOR_SCREEN`：按校准位置比较骰子锚点，距离 ≤ T_anchor。
- `UNREFRESHED`：锚点正常，4 个槽位都是 `Question`。
- `NOT_FACTOR_SCREEN`：锚点不匹配，例如切出游戏、弹出对话框或通知栏被拉下。

T_anchor 初值 45（47 张因子界面截图中，包含骰子的标题区域最大差异是 21.3；只取骰子后的数值在第 3 步重测）。现在没有「非因子界面」的截图，这个阈值还不能验证，需要维护者补拍负样本（第 10 节第 2 条）。

---

## 4. 刷新状态机（RefreshEngine）

```
Idle ──start(recipe)──► Calibrating ──失败──► Paused(CALIBRATION_FAILED)
                            │成功
                            ▼
              ┌──────► Capture ──► Recognize ──► Decide
              │                                   │
              │   UNCERTAIN（< N 次）：不点击，重新截图
              │   MISS：Tap ──► 等待画面变化 ──┘
              │   HIT ──────────────────────────► Hit（震动，展开悬浮窗）
              │
   resume ◄── Paused(reason) ◄── NOT_FACTOR_SCREEN / 切出游戏 / 连续 N 次 UNCERTAIN /
                                 点击无效 / 达到次数上限 / 用户暂停
   任意状态 ──stop──► Stopped
```

关键规则：

1. **节拍**：两次截图的间隔 = max(1000 / 用户频率, `capturer.minIntervalMs`)。无障碍截图的 `minIntervalMs` 先取 333，第 9 节第 5 步实测后修正。
2. **点击后必须确认画面变了再判定**。点击后截到的 4 个结果如果和点击前完全相同，说明游戏可能还没刷新这一帧，这时**只重新截图，不再点击**。否则可能出现「真正的新结果是命中，却被下一次点击刷掉」的情况。连续 3 次都相同才判定「点击无效」并暂停。
3. **UNCERTAIN 不点击**：见第 1.5 节。
4. **未刷新状态**（4 个问号）：开始时如果是问号，直接点一次刷新，不计入识别失败。
5. **前台检测**：无障碍服务监听 `TYPE_WINDOW_STATE_CHANGED` 事件。点击开始时记下当时的前台包名（玩家此时一定停在游戏里），之后前台包名一变就立即暂停，不再截图。忽略本应用自己和输入法的事件。这样不需要维护游戏各渠道版本的包名列表。截图判断出 `NOT_FACTOR_SCREEN` 是第二道保险。
6. **线程**：引擎跑在 `Dispatchers.Default` 上的协程里，识别不占主线程；状态通过 `StateFlow` 推给悬浮窗。
7. **统计**：已刷新次数、实际速度（最近 10 次的平均）、识别失败次数、期望次数（`ProbabilityCalculator`）。

`Decision` 的输入是 4 个 `SlotResult`，输出 HIT / MISS / UNCERTAIN（见第 3.2 节的展开规则）。它是纯函数，单独测试。

---

## 5. 悬浮窗

- 由 `KazeAccessibilityService` 用 `TYPE_ACCESSIBILITY_OVERLAY` 创建，**不需要悬浮窗权限，也不需要单独的悬浮窗服务**。代价是必须先开启无障碍服务才能看到悬浮窗，对本工具没有影响，因为不开无障碍也无法刷新。
- 形态：
  - **小球**：可拖动。
  - **面板**：选配方、设频率、开始。
  - **运行中**：缩成一个小胶囊（次数 / 速度 / 暂停按钮）。
  - **命中**：展开，显示 4 个因子图标和「继续刷」「完成」按钮。
- **避让**：校准后得到「禁区」= 标题 + 4 个槽位 + 刷新按钮的屏幕矩形（各外扩 24 dp）。运行时如果悬浮窗与禁区重叠，自动移到禁区外最近的屏幕边缘。原因有两个：`dispatchGesture` 的触摸会落到最上层窗口；无障碍截图可能把悬浮窗也截进去（待真机确认），盖住槽位就会识别失败。
- 刷新按钮的点击位置取循环箭头中心（参考坐标约 (1380, 432)，校准后换算）。点击坐标在按钮范围内随机偏移 ±10 px，不是为了防检测，而是避免每次正好点在按钮边缘的同一个像素上。

---

## 6. 新增依赖

| 制品 | 用在 | 版本 | 查证情况 |
| --- | --- | --- | --- |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core` | core-engine | 1.9.0 | 本机 Gradle 缓存里只有 1.9.0，推断是 app 现有依赖传递引入的版本。Maven 上最新是 1.11.0（2026-05-09，来自 mvnrepository 搜索结果）。选 1.9.0 是为了和 app 保持同一版本，并避开新版协程与 Kotlin 2.2.10 的兼容风险（这个风险未验证）。**锁定前请维护者执行下面的命令确认 app 实际解析到的版本** |
| `org.jetbrains.kotlinx:kotlinx-coroutines-test` | core-engine 测试 | 同上 | 同上 |
| `kotlinx-serialization-json`（已有） | core-engine 测试 | 1.8.0 | 测试里解析 `factors.json` 用 |
| OpenCV | 不引入 | — | 后备方案：`org.opencv:opencv:5.0.0.1`，已在 Maven Central 查证 |

app 不新增依赖。悬浮窗需要的 Lifecycle 和 SavedState 已经通过 `lifecycle-runtime-ktx` 和 `activity-compose` 传递引入。

确认协程版本的命令（维护者执行）：

```powershell
.\gradlew.bat :app:dependencies --configuration releaseRuntimeClasspath | Select-String "kotlinx-coroutines"
```

`gradle/libs.versions.toml` 新增：

```toml
[versions]
kotlinxCoroutines = "1.9.0"

[libraries]
kotlinx-coroutines-core = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-core", version.ref = "kotlinxCoroutines" }
kotlinx-coroutines-test = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-test", version.ref = "kotlinxCoroutines" }
```

`core-engine/build.gradle.kts`：

```kotlin
plugins {
    id("java-library")
    alias(libs.plugins.jetbrains.kotlin.jvm)
}
java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}
kotlin {
    compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11 }
}
dependencies {
    api(project(":core-domain"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlinx.serialization.json)
}
tasks.test {
    // 测试直接读 app 里的运行时资源，不再多复制一份
    systemProperty("kaze.iconDir", rootDir.resolve("app/src/main/assets/factors/icons").absolutePath)
    systemProperty("kaze.factorsJson", rootDir.resolve("app/src/main/assets/factors.json").absolutePath)
}
```

另外：`settings.gradle.kts` 加 `include(":core-engine")`；`app/build.gradle.kts` 加 `implementation(project(":core-engine"))`。

---

## 7. Manifest 与无障碍配置

`AndroidManifest.xml`（在 `<application>` 内新增服务，在外面新增一条权限）：

```xml
<uses-permission android:name="android.permission.VIBRATE" />

<service
    android:name=".service.KazeAccessibilityService"
    android:exported="true"
    android:label="@string/a11y_service_label"
    android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
    <intent-filter>
        <action android:name="android.accessibilityservice.AccessibilityService" />
    </intent-filter>
    <meta-data
        android:name="android.accessibilityservice"
        android:resource="@xml/accessibility_service_config" />
</service>
```

- `BIND_ACCESSIBILITY_SERVICE` 保证只有系统能绑定这个服务；`exported="true"` 是系统绑定所必需的，有这条权限保护，其他应用调不了。
- `VIBRATE` 是普通权限，安装时自动授予，不弹窗。

新建 `res/xml/accessibility_service_config.xml`：

```xml
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:description="@string/a11y_service_description"
    android:accessibilityEventTypes="typeWindowStateChanged"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:accessibilityFlags="flagDefault"
    android:notificationTimeout="100"
    android:canRetrieveWindowContent="false"
    android:canPerformGestures="true"
    android:canTakeScreenshot="true" />
```

| 属性 | 用途 |
| --- | --- |
| `accessibilityEventTypes="typeWindowStateChanged"` | 只接收「前台窗口切换」事件，用于前台检测。不监听其他事件，耗电和隐私影响都最小 |
| `canRetrieveWindowContent="false"` | 不读取界面控件树（Unity 游戏里也读不到因子）。如果真机上发现事件里拿不到包名，再改成 `true` |
| `canPerformGestures="true"` | 允许 `dispatchGesture` 点击 |
| `canTakeScreenshot="true"` | 允许 `takeScreenshot`，Android 11 以下系统会忽略这个属性 |

`strings.xml` 新增 `a11y_service_label`（例如「KazeFactor 自动刷新」）和 `a11y_service_description`。描述要写明：只在玩家点击开始后截取屏幕并点击刷新按钮，截图只在内存中处理，不保存（「保存当前画面」按钮除外），不联网。

**不需要**的权限：悬浮窗权限 `SYSTEM_ALERT_WINDOW`、前台服务、通知权限。如果以后做第 9 节第 7 步的高速模式，再加 `FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_MEDIA_PROJECTION` 和一个 `foregroundServiceType="mediaProjection"` 的服务。

**权限引导页必须处理的两个情况：**

1. **Android 13 起的「受限设置」**：从浏览器或文件管理器安装的 APK（也就是从 GitHub 下载安装的用户），系统默认不让开启无障碍服务，开关是灰的。用户要先到「应用信息 → 右上角菜单 → 允许受限设置」。本项目通过 GitHub 和 B 站分发，几乎所有用户都会遇到，引导页要放一个直达应用信息页的按钮，并配图说明。
2. **国产系统后台限制**：部分系统会在后台杀掉无障碍服务，表现为服务开关自己关掉。引导页提示用户把本应用加入自启动或电池优化白名单。各品牌入口不同，只做文字提示，不做跳转适配。

---

## 8. 对现有文件的影响

| 文件 / 目录 | 处理 |
| --- | --- |
| `app/src/androidTest/assets/screenshots/` | 整体移到 `core-engine/src/test/resources/screenshots/`（第 9 节第 1 步，由维护者用 `git mv` 执行以保留历史） |
| `app/src/main/assets/factors/icons/` | **保留**，作为识别模板的唯一来源。`PROJECT_STATUS.md` 第 9 节「图标资源重复」一条就此结案 |
| `app/src/main/res/drawable/*.png` | 保留，继续给界面用。不能拿来做模板，因为 `res/drawable` 解码时会按屏幕密度缩放 |
| `factors.json` | 不增加第三份。`core-engine` 测试通过系统属性直接读 `app/src/main/assets/factors.json` |
| `app/src/main/assets/recognition/`（新建） | 骰子锚点、循环箭头锚点、4 个问号模板，从实机截图裁出 |
| `ICON_LICENSE.txt` | 适用范围扩大到 `recognition/` 和测试截图，见第 10 节第 4 条 |
| `Factor.canAppearInSlot()` | 不改。已核对它与 17 种组合一致，各槽候选数 28 / 37 / 39 / 16 |
| `PROJECT_STATUS.md` 第 10 节 | 改为指向本文 |
| `README.md` | 删掉「帧差检测」和「OpenCV 模板匹配」两条，改为「纯 Kotlin 模板匹配」 |

---

## 9. 实现顺序

每一步单独提交。第 1~4 步全部在电脑上完成，不需要真机和权限。

| 步 | 内容 | 验证方式 | 建议提交信息 |
| --- | --- | --- | --- |
| 1 | 新建 `core-engine` 模块（第 6 节的配置）；`git mv` 截图目录；维护者核对答案草稿并改名为 `labels.csv` | Gradle 同步成功；`.\gradlew.bat :core-engine:test --console=plain` 能运行（此时还没有测试） | `chore(engine): 新建 core-engine 模块并迁移实机截图` |
| 2 | `ArgbImage`、`ImageOps`、`ReferenceLayout`、`TemplateBank`、`SlotMatcher`、`FactorRecognizer`，先用固定的参考坐标（scale = 1，不做校准）；4 类测试中的前 3 类 | 47 张截图答案测试全部通过；阈值报告打印最大距离和最小差距，据此修正第 3.2 节的阈值并回写本文 | `feat(engine): 实现槽位模板匹配与截图答案测试` |
| 3 | 从截图裁出锚点和问号模板放入 `assets/recognition/`；`Calibrator`；合成分辨率测试 | 缩放 0.75 / 1.25 倍、加黑边后，校准误差 ≤ 2 px，答案测试仍通过 | `feat(engine): 实现骰子锚点校准与多分辨率合成测试` |
| 4 | `Decision`、`RefreshEngine`、`EngineState`，用假截图器和假点击器测试 | 覆盖：命中停止、未命中点击、UNCERTAIN 不点击、连续 N 次暂停、点击无效暂停、次数上限、问号状态先点一次、节拍间隔（用虚拟时间） | `feat(engine): 实现刷新状态机与决策逻辑` |
| 5 | `KazeAccessibilityService`、`A11yScreenCapturer`、`GestureTapper`、Manifest 和无障碍配置；临时加一个调试按钮，执行「截图一次并保存到应用目录」「点击刷新一次」「连续截图 20 次」 | **真机**：①保存的截图与手机截屏一致，记录截图里是否带着悬浮窗；②点击后游戏确实刷新；③记录连续截图的实际最小间隔和失败码，回写第 1.1 节和 `minIntervalMs` | `feat(service): 接入无障碍截图与手势点击` |
| 6 | 悬浮窗（四种形态、避让）、`EngineSettingsDataStore`、权限引导页；删除第 5 步的调试按钮，改为悬浮窗里的「保存当前画面」 | **真机**：选配方 → 开始 → 自动刷到命中并震动；切到桌面立即暂停；拉下通知栏会暂停；用「只要无限能量」配方测 20 次，记录平均刷新次数 | `feat(overlay): 新增悬浮窗与权限引导，打通自动刷新流程` |
| 7 | （可选）MediaProjection 高速模式。按第 5 步测得的截图上限和第 6 步的使用体验决定做不做 | 真机：频率设为 10 时实际速度明显高于无障碍模式；录屏被系统停止时引擎暂停而不是崩溃 | `feat(capture): 新增录屏高速模式` |
| 8 | 更新 `README.md`、`PROJECT_STATUS.md` | 与代码现状一致 | `docs: 同步核心功能完成情况` |

---

## 10. 需要维护者确认或补充的事

1. **核对答案草稿**：`app/src/androidTest/assets/screenshots/labels_draft.csv`（中文对照版 `labels_draft_zh.csv` 在同一目录，只供人工核对，测试不读取）。重点看 `enable04`、`enable08`、`enable11`、`enable32` 里的「天赋选择+1」（`buff_choice_plus1`）和「天赋自助餐」（`buff_buffet`），这两个识别器最容易混。核对时可以在游戏里点开图标看说明，或者对照截图肉眼辨认。
2. **补拍截图**（原图，不压缩）：
   - 非因子界面 5~10 张：大厅、暂停菜单、因子界面上弹出对话框、通知栏拉下一半、其他游戏界面。用于验证第 3.3 节的阈值。
   - 如果游戏支持切换语言，英文界面的因子界面 2~3 张，用于确认骰子锚点不受语言影响。
   - 以后刷到第 0 节列出的 9 个未出现因子时顺手截图，补进测试。
3. **默认值**：第 1.5 节的 N = 3、次数上限 = 期望 × 3（至少 100）、默认频率 3 次/秒、命中时震动不发通知。有不同意见请直接改本文。
4. **截图的版权（需要尽快确认）**：已获的授权针对的是因子图标。测试截图和 `recognition/` 里裁出的锚点也是凉屋的游戏画面，而它们会随源码公开到 GitHub。请确认之前的授权是否覆盖截图。如果不覆盖，可选做法是：截图不进公开仓库（加入 `.gitignore`，识别测试在截图缺失时自动跳过），锚点改为在用户设备上首次校准时现场截取。确认之前，第 1 步先不提交截图的迁移。
5. **Android 8~10 的设备**在高速模式做出来之前不能自动刷新，只能用配方管理。请确认可以接受。

[^a11y]: AOSP `AccessibilityService.java` 中 `ACCESSIBILITY_TAKE_SCREENSHOT_REQUEST_INTERVAL_TIMES_MS = 333`（@hide @TestApi）：https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/accessibilityservice/AccessibilityService.java 。以真机实测为准。
[^mp]: Android Developers, Media projection：https://developer.android.com/media/grow/media-projection
[^opencv]: Maven Central, org.opencv:opencv：https://central.sonatype.com/artifact/org.opencv/opencv
