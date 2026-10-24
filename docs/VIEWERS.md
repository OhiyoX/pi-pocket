# 看图器 / 文件查看器（2026-10-24 重做）

用户口径：**「现在的看图和看文字组件都非常简陋，好好设计一下，看看网上的项目，简洁优雅美观强大，Material 设计」**。
这一轮把两个只读查看器按 M3 重排，并补上"能读代码"该有的阅读功能。

## 参考（都是公开资料，2026-10-24 查证）

| 来源 | 拿它什么 |
| --- | --- |
| [Material 3 · Dialogs](https://m3.material.io/components/dialogs/guidelines) | **全屏对话框里 ✕ 是唯一导航**，其它位置只放动作 —— 所以两个查看器顶部只有「✕ + 标题/信息 + 动作」，没有返回箭头、没有侧栏。 |
| [Material 3 · Gestures](https://m3.material.io/foundations/interaction/gestures) | 图片"双指外扩 → 全屏看"的交互约定（我们反过来：先内联预览，点一下进全屏）。 |
| [Baseflow/PhotoView](https://github.com/Baseflow/PhotoView) | 手势集合与**点图外关闭**（`OnOutsidePhotoTapListener`）：多指缩放、双击缩放、放大后平滑拖动；**点图片本身不关闭**。 |
| [davemorrissey/subsampling-scale-image-view](https://github.com/davemorrissey/subsampling-scale-image-view) | 超大图要降采样 + 分块（我们用的是 `inSampleSize` + 2048 上限，够手机看，未做分块）。 |
| [Rosemoe/sora-editor](https://github.com/Rosemoe/sora-editor) | 阅读代码该有的功能清单里，只读能用的那部分：**行号（code block lines）**、**自动换行开关**、**搜索定位**、**sticky 行号**、等宽 + 高亮；编辑类（撤销/补全/自动缩进）不适用。 |

没引第三方库：项目一直是「不引 UI 依赖」，全部用 Compose 基础件自绘（主题色走 `MaterialTheme` + `TerminalColors`）。

## 看图器 `ui/ImageViewer.kt`

- **全屏 Dialog + 黑底**（`decorFitsSystemWindows=false`），顶部信息层：✕、文件名（有就显示）、
  `1440×3168 · 158 KB · png`、页码 `2 / 5`。
- **点一下切 chrome**：1× 时点图片 = 隐藏/显示信息层（专注看图）；放大时点图片 = 复位。
- **点黑底关闭**（PhotoView 的 outside-tap 语义）；旧版"点图片就关"是 bug，已去掉。
- **手势**：捏合 1×–4×（以手指中心为锚，边界钳制）、拖动平移、双击 1×⇄2.5×（200ms 缓动）。
- **放大后有「复位」小胶囊**，不用靠猜双击。
- **多张图**：底部胶片条（选中描边 + 点按跳页），缩略图异步解码。
- 解码在 IO 线程 + 12 张内存 LRU；解码中给线性进度条，解不了给可读提示。
- 纯数学在 `ImageViewerMath`（钳制/锚点缩放/命中测试/体积估算/信息行），JVM 单测覆盖。

## 文件查看器 `ui/FileViewer.kt`

顶部同样是「✕ + 文件名 + 路径 + 复制」；`复制` 对文本复制正文、对图片/目录复制路径（复制完按钮变「已复制」）。

### 文本 / 代码
- **行号 gutter**：与正文共用同一个纵向滚动（gutter 只做 `translationY = -scroll`），所以不会两栏错位；
  换行模式下自动收起（视觉行与逻辑行不再一一对应）。
- **换行开关**：关（默认）= 不折行 + 横向滚动，等价于「看代码」；开 = 按屏宽折行，等价于「读长文本」。
- **查找**：输入即算命中行（大小写不敏感），`n / m` 计数 + 「下一个」按行高跳到下一处命中。
- **底部 sticky 行号范围**：`第 12–34 行 / 共 210 行`，滚动时实时更新。
- 语言、行数、体积做成小徽标；超过 `CodeHighlight.MAX_HIGHLIGHT_CHARS`（150k）退化成纯文本并注明。

### 图片
- 内联预览（圆角卡片 + `ContentScale.Fit`），上方徽标显示尺寸/体积/格式；
- **点图片进全屏看图器**，带上文件名当标题（复用 `ImageViewerPager`）。

### 目录 / 出错
- 目录：圆角行 + 文件夹/文件图标 + 箭头，点进去继续看（不离开 app）。
- 出错：图标 + 「打不开这个文件」+ 原始原因 + 路径 + **重试**。

## 已知边界

- 图片全屏**没有**下拉关闭：下拉手势和"放大后拖动"抢事件，要做就得手写 `awaitEachGesture` 统一裁决；
  当前用「✕ / 点黑底」关闭，够用。
- 大图仍是 `inSampleSize` 降采样（2048 上限），不是分块加载；>8MB 的文件桥直接拒（`/v1/file` 的口径）。
- 行号范围是按固定行高估算（`17sp` → 60px 假设），换行模式不显示；字号设置改变后估算会偏，属已知近似。

## 测试

`app/src/test/java/id/harness/pipocket/ViewerChromeTest.kt`
—— 体积估算/信息行/缩放边界/页码归一；查找命中行（大小写、同行去重、空查询）；行号右对齐。
全套 353 个 JVM 用例通过，lint 0 Error。

## 第二轮修正（2026-10-24 03:10，0.4.2）—— 真机截图驱动

用户连续三条口径：「全屏时顶部乱七八糟」「全屏支持无边界拖拽」「缩小也要支持」「非全屏缩小放大也要支持」。

### 1. 顶部为什么乱，怎么改的
真机上「顶部」同时叠了三层：系统状态栏（时间/信号）、**图片自带的状态栏**（很多截图有）、我们的 chrome。
试过两个「正规」做法，都在这台机器（ColorOS/Android 16）上对 **Dialog 窗口**无效：

- `WindowInsetsControllerCompat.hide(systemBars())` —— 调用成功但 `dumpsys window` 里 dialog 窗口的
  `vsysui` 始终没有 `HIDE_NAVIGATION/FULLSCREEN`；
- 追加 `FLAG_FULLSCREEN` —— dialog 窗口的 `fl=` 里也没出现。

结论：**不跟系统栏较劲**。chrome 让位到状态栏下面（`statusBarsPadding()`）并改成**纯黑实心条**
（`Color.Black`），把图片自带的状态行完全盖住，顶部就是一条干净工具栏；想看整张图就点一下隐藏 chrome。

### 2. 无边界拖拽
`detectTransformGestures` 里不再 `clampPan`：`offset = zoomed + pan`（双击同理）。
拖飞了用右下角「复位」。`ImageViewerMath.clampPan` 保留（单测仍在用，将来要"回弹"时还有现成的）。

### 3. 缩小
`MIN_SCALE = 0.25f`、新增 `FIT_SCALE = 1f`（= 铺满视口的基准，也是「复位」目标）。
`doubleTapTarget()`：不在 1× 就回 1×，在 1× 才放到 2.5×。

⚠️ 踩坑记录：`MIN_SCALE` 之前是 1f，全屏页的初始值写的是 `mutableStateOf(MIN_SCALE)`；
改成 0.25f 之后打开全屏直接就是 0.25 倍（真机截图里图小得只有中间一小块）。现在初始值显式用 `FIT_SCALE`。

### 4. 非全屏也缩放
新增 `InlineZoomableImage(bitmap, onTap, ...)`：文件面板的内联预览复用它 ——
捏合 0.25×–4×、双击 1×⇄2.5×、无边界拖动、非 1× 时给「复位」；单击仍是进全屏。

### 5. 尺寸显示
`rememberOriginalSize()`：`inJustDecodeBounds` 只读文件头拿原图宽高（带 32 条缓存）。
之前是拿 512 上限的解码图量，所以 720×1600 的图显示成 180×400。

### 6. Dialog 窗口塌成一条（第一轮的真 bug）
Compose 的 `Dialog` 窗口是 **fillxwrap**（`dumpsys` 实测），`Surface(Modifier.fillMaxSize())` 在高度无界时会
塌成内容高度 —— 表现为"全屏打开后只剩顶部一条，下面露出聊天界面"。
修法：`fullScreenWindowSize()` 用 `displayMetrics` 显式给整屏 dp 尺寸（`ui/ViewerWindow.kt`）。

## 第三轮：从 Dialog 改成根布局 overlay（2026-10-24 03:20，0.4.3）

用户：「要」（要彻底全屏黑）。

**结论先写**：系统栏**只有应用自己的窗口**能隐藏。Dialog 是独立窗口，在 ColorOS/Android 16 上
`WindowInsetsControllerCompat.hide(systemBars())` 与 `FLAG_FULLSCREEN` 都不生效（两次实测、dumpsys 无变化）。
所以这一轮把两个查看器从 `Dialog` 改成 **overlay**：

- `ImageViewerPager(images, initial, onDismiss)` → `ViewerOverlays()`（读 `AppState.imageViewer`）+
  `ImageViewerOverlay(request, onDismiss)`，铺在 app 根布局最上层；
- `FileViewerDialog()` → `FileViewerOverlay()`，同样在 overlay 层；
- 打开状态收到 `AppState`：`imageViewer: ImageViewerRequest?` + `openImageViewer()/closeImageViewer()`
  （模型在 `Models.kt`；原来各处的局部 `viewing` 状态删掉，点图直接 `AppState.openImageViewer(...)`）；
- `ui/ViewerWindow.kt::ImmersiveSystemBars()`：在 overlay 里对 **Activity 窗口** `hide(systemBars())`，
  dispose 时还原；两个查看器各调一次（同时开也只会 hide 两次，无害）；
- `BackHandler` 各一：返回键先关查看器，不会误退会话；
- 层序：文件面板在下、看图在上（从文件面板点图进全屏，关掉回到文件面板）。

**顺带**：沉浸后 `WindowInsets` 仍可能报非 0（实测顶部漏出一条露出图片自带状态行）⇒ chrome 改用固定内边距 +
**纯黑**条（`Color.Black`），把图片自带状态行完全压掉。0.5/0.9 透明度都试过，会透出"影子"，最终选纯黑。

**回归点**：`MainActivity.setContent` 里包了一层 `Box`，末尾 `ViewerOverlays()`；
`ChatScreen` 不再自己渲染查看器；`FileViewer` 里点图改成 `AppState.openImageViewer(...)`。
