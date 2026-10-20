# 任务交接文档：dsh-pet 安卓悬浮窗桌宠

> 给 AI 编码助手（Codex）的完整上下文。本项目已可运行，本文档说明现状、技术要点、已知问题与下一步方向。

## 一、项目背景

- 灵感来源：GitHub `PC2005-cloud/dsh-pet`（MIT 协议，DeepSeek Harness 桌面宠物插件），自带 106 个 VP9-alpha 透明 webm 手绘动画（640×360）
- 目标：把同款桌宠做到**安卓手机**上，不需要任何 AI 功能（无碎碎念/对话/余额）
- 本机无 Android Studio/JDK/SDK，**构建走 GitHub Actions 云端出 APK**

## 二、当前状态（已完成并实机验证）

代码位置：`dsh-pet-android/`（Kotlin，无第三方依赖）

| 能力 | 实现方式 |
|---|---|
| 悬浮窗 | `PetService` 前台服务 + `WindowManager` `TYPE_APPLICATION_OVERLAY`，`foregroundServiceType="specialUse"` |
| 透明渲染 | WebView 加载 `assets/pet/index.html`，`<video>` 播 webm → Canvas `drawImage` 逐帧绘制（保留 alpha） |
| 动画链 | JS 实现：待机/动作播完按权重随机衔接（idle 10 / actions 5），轻点 → 随机「点击回应」动画 |
| 素材 | 90 个动画（45.5MB，已重命名 `pet-001..090.webm`，ASCII 名规避 WebView 中文路径问题）+ `pet-config.json`（idle 1 / actions 84 / tap 5） |
| 窗口贴合 | JS 每 ~250ms 测量当前帧的 alpha 不透明边界 → `@JavascriptInterface` 回传 Kotlin → 窗口高度按边界纵横比收缩（宽度=设定宠物宽度，gravity=BOTTOM 下保持中心） |
| 交互 | 容器 `onInterceptTouchEvent=true` 统一接管触摸；单指拖动（位移>10px 判定）、轻点触发 tap 动画 |
| 大小设置 | 三档 200/280/360dp，SharedPreferences 持久化，切换后重启 Service 生效 |
| 构建 | `.github/workflows/android.yml`：setup-java 17 + gradle/actions/setup-gradle 8.7 → `gradle assembleDebug` → 上传 artifact |

仓库：`github.com/hua02572/-`（私有，注意仓库名是一个减号——建仓时误留，建议改名为 `dsh-pet-android`，改名后旧 URL 自动重定向）

## 三、关键技术要点（踩坑记录，务必了解）

1. **VP9 alpha 的坑**：这类 webm 的 alpha 通道存在 Matroska `BlockAdditions` 里，**ffmpeg、PyAV 都解不出来**（实测），只有 Chromium 内核（WebView）能正确渲染与读取。因此像素级分析只能在 WebView 内用 `getImageData` 做
2. **file:// 画布污染**：WebView 默认禁止 `file://` 页面读取画布像素（`getImageData` 抛 SecurityError），必须开 `settings.allowFileAccessFromFileURLs = true` + `allowUniversalAccessFromFileURLs = true`（已开，已标 @Suppress("DEPRECATION")）
3. **触控架构**：WebView 会吞触摸事件，必须由外层容器（自定义 FrameLayout）`onInterceptTouchEvent` 恒真接管，拖动/点按判定全在容器的 `OnTouchListener`
4. **边界测量算法**：抽行采样（每 2px）统计列/行不透明密度，alpha 阈值 40（过滤淡阴影）、密度阈值 3（过滤噪点），`timeupdate` 驱动（约 4Hz），变化 >3% 才上报防抖
5. **窗口几何**：gravity=BOTTOM|START，`y` 是窗口底边距；改高度时 `y += (oldH-newH)/2` 保持中心
6. **构建网络**：直连 push 大文件会被重置（curl 55），用 `git -c http.version=HTTP/1.1 push` + 大提交拆小（每批约 5MB）可稳定通过

## 四、已知问题与候选改进（按优先级）

1. **透明区域仍遮挡触摸**：窗口矩形内的透明像素依然拦截触摸。彻底解法是逐像素穿透（JS 上报 alpha 位图/鼠标位置 → 动态切 `FLAG_NOT_TOUCHABLE`），Android 无法把已消费的触摸再转发给下层应用，需要预判式方案
2. **动画切换时窗口尺寸跳变**：每段动画 bbox 不同，切换瞬间窗口宽高变化明显。可做：每种动画预采样一次 bbox 并缓存，切换时用旧 bbox 播放首帧、平滑过渡到新 bbox
3. **老机型 WebView 不支持 VP9 alpha → 黑底**：备选方案是把 webm 批量转 APNG（带 alpha，`ImageDecoder` 原生支持），需要引入 ffmpeg 转换管线
4. **国产 ROM 杀后台**：服务被杀后需重新打开 App 开启；可加开机自启/前台保活（白名单引导）
5. **桌面版同款物理未移植**：无甩抛惯性/屏幕反弹/宠物互撞（`dsh-pet/src/shared/physics.ts` 有现成纯函数实现，可移植成 Kotlin）
6. **点击无点播菜单**：桌面版右键可点播任意动画，手机版可加长按菜单
7. 杂项：长按应与拖动区分开；`measure()` 的 getImageData 每次约 900KB，低端机可降采样到 320×180

## 五、操作约定（用户环境）

- Windows 11 + 手机安卓（国产 ROM）；用户不是专业开发者，交付物要"双击/一键"化
- 修改代码后：`git add -A && git commit && git -c http.version=HTTP/1.1 push` → 用户去 Actions 下载最新 artifact 覆盖安装（无需卸载）
- 仓库为私有，匿名 API 404，查状态用登录态浏览器
- 每次交付需明确告诉用户：去哪下载、怎么安装、预期看到什么

## 六、验收标准

- 宠物透明显示、窗口贴合身形（无明显空白遮挡）
- 可拖动、轻点有点击回应、动画链自动轮播不中断
- 大小三档切换即时生效
- Actions 构建绿、APK 可直接覆盖安装
