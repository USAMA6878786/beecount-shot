# BeeCount-Shot · 记账截图

给 [蜜蜂记账 BeeCount](https://github.com/TNT-Likely/BeeCount) 用的 **LSPosed 模块**，两件事：

1. **按需截图记账** —— 只有你点控制中心磁贴那一下，才截屏交给 BeeCount 识别记账
2. **支出桌面小组件** —— 今日 / 本周 / 本月支出，白底可调透明度

> BeeCount 原本只要检测到系统截图就自动 OCR 记账，随手截个图也会变成脏账。
> 本模块把它改成**由你决定**。

---

## 它解决什么，不解决什么

先把边界说清楚，省得你试半天：

| | 状态 |
|---|---|
| 平时的截图（电源键 / 三指）不再触发记账 | ✅ |
| 点磁贴 → 截屏 → BeeCount 识别 → 记账 → 删图 | ✅ |
| 关掉界面几秒内点磁贴，仍然记账 | ✅ |
| **界面被系统回收后（约 40 秒），点磁贴不记账** | ❌ **做不到**，原因见「能力边界」 |
| 桌面小组件显示支出 | ✅ |

**这不是实现没做好，是 BeeCount 的架构决定的。** 完整证据链见「能力边界」一节。

---

## 安装

1. 从 [Releases](../../releases) 下载最新 APK 安装
2. LSPosed → 模块 → 启用「记账截图」，**作用域勾选「蜜蜂记账」**
3. 打开一次蜜蜂记账，**允许它的 root 请求**
   （⚠️ 弹窗写的是**「蜜蜂记账」请求超级用户权限**，不是本模块，容易看漏）
4. 进蜜蜂记账的「智能记账」，**确认截图监听是开着的**
5. 打开「记账截图」，按提示添加磁贴

> **装了新版本后，一定要点一次「重启蜜蜂记账」。**
> 模块的代码是注入到它进程里的，不重启它就还在跑旧逻辑。

---

## 使用

**记账**

拉下控制中心 → 点「记账截图」磁贴 → 面板收起 → 约 0.15 秒后截屏 → 屏幕弹「截图成功」
→ BeeCount 识别记账 → 识别完成后截图被删。

**桌面小组件**

长按桌面空白处 → 添加小部件 →「记账支出」。

> 只能手动加。往桌面摆小组件是桌面应用自己的事，Android 没给任何应用这个接口——**root 也没有**。

**调透明度**

设置页的滑块（统一应用到所有小部件）；长按某个小部件 → 重新配置，则只改它自己。

---

## 能力边界

这是本项目最重要的一节。**下面是查源码 + 真机验证得出的，不是推测。**

### BeeCount 的记账链路有四环

```
截图进相册
   ↓ ① 相册观察者（Kotlin，注册在它进程里）
   ↓ ② 闸门派发（MethodChannel）
   ↓ ③ Dart 处理器（识别 + AI + 落库）
   ↓ ④ 写「落库完成」日志
```

**关键事实：③ 那个 Dart 处理器只存在于它的 UI 树活着的时候。**

`ScreenshotObserver` 是在 Dart 侧调 `startScreenshotObserver(engine)` 时才挂上的，
在 `onDestroy` 里被注销；而实测**它的 Activity 打开后约 38 秒就被系统回收**。

于是：

```
系统回收 Activity → 观察者注销 + Dart 处理器随之销毁
   → 点磁贴，图进了相册但没人接
   → 直到它的兜底扫描跑到才补上（实测 49 秒）
   → 已经超过它 30 秒的「旧图阈值」→ 整张被丢弃
```

### 本模块做了能做的部分

- **观察者重挂**（v2.59）：`onDestroy` 之后立刻用 `startScreenshotObserver(engine)` 挂回去，
  实测 **47 毫秒**内完成 → 通知能及时到达，**"30 秒旧图"这一环被修掉了**
- **闸门派发**：修好后 `[gate-channel] ALLOWED` 正常出现，说明确实派发出去了

### 但第三环修不了

Dart 处理器的注册状态在 **Dart 的堆里**，Java 层没有任何接口重建它。
引擎对象明明还活着（4 个探针在后台 2.5 分钟内 **0 命中**——`destroy` / `Registry.removeAll` /
`FlutterJNI.detach` / `Delegate.onDetach` 都没被调用），但**通道对面已经没人接**。

### 结论

> **这个模块的能力上限是：打开过一次，且在系统回收界面之前使用。**
>
> 想让它在界面死了以后还能记账，只剩两条路，都超出「给别人的 App 打钩子」的范围：
> 让 UI 常驻在不可见的副屏上（耗电、依赖 ROM 支持），
> 或者自己再造一个 headless FlutterEngine 跑它的 Dart 入口（等于重新实现半个 App）。
> 两条都没做。

---

## 常见问题

**点了磁贴没反应？**

看设置页顶部的状态点，磁贴副标题也会说话：

| 状态 | 含义 | 怎么办 |
|---|---|---|
| `● 缺少 root` | 主截屏路径不可用 | 点「root 权限」授权 |
| `● 未连上蜜蜂记账` | 进程不在，或 hook 没注入 | 点「重启蜜蜂记账」 |
| `● 部分可用` | 进程在，但监听不在位 | 打开一次蜜蜂记账 |

**弹了「截图成功」但没记账？**

就是上面说的第三环：Dart 处理器已经随界面销毁了。**打开一次蜜蜂记账即可恢复**，
直到它再次被回收（约 40 秒后）。

**从点击到截图被删要多久？**

正常 **15~25 秒**（截图 0.25 秒 + 它检测数秒 + AI 识别 3~5 秒）。
如果超过 40 秒还没删，基本就是「没识别成功」，最终会由模块的 60 秒兜底清理删掉。

> **截图被删 ≠ 记账成功。** 成功时是它自己删（很快）；超时兜底删是模块在收拾残局。

**改了配置不生效？**

hook 只在它启动时注入 → 点「重启蜜蜂记账」。

**截到的图里带着控制中心？**

说明这台机器收面板比 0.15 秒慢。把 `Const.SHOT_SETTLE_MS` 调大。

**小部件数字是灰色的？**

BeeCount 进程没在跑，显示的是上次的值；打开一次它即可恢复实时。

**截图什么时候会被删？**

**蜜蜂记账识别完就删**——不管有没有识别到账单（图已经读完了，留着只占相册）。
判定依据是**精确配对**：它每处理完一张就写一条「AI 识别 + 落库完成」日志，
按「闸门放行的先后」与日志一一对上，配到就删。

配不上号、日志没读到、这张还没出结果 —— 一律**不删**。宁可漏删，绝不误删。

---

## 权限与隐私

| 能力 | 用途 | 必需 |
|---|---|---|
| LSPosed / root | 拦截截图上报；`screencap` 截屏；收起控制中心；删除截图；重启宿主 | ✅ |
| 无障碍服务 | **备用截屏路径**（root 抓帧失败时才用）；不读屏幕内容、不监听界面事件 | 否 |
| 读取照片 | 只用来判断「最新一张截图是否落盘」，以便更快确认截屏成功 | 否（首次打开会问一次） |
| 显示在其他应用上层 | Android 12+ 会丢弃后台应用的 Toast，这个权限只让「截图成功」能显示 | 否 |
| 网络 | **不使用**，不收集、不上传任何数据 | — |

模块不直接读写 BeeCount 的业务数据做判断（桌面小组件的只读统计除外）；
需要宿主数据时，一律让宿主自己读、再通过广播送回来。

**广播通道的身份校验**：接收器必须 `RECEIVER_EXPORTED`，所以加了发送方校验，
策略是**只做能确认时的拦截**——查不出包名等一切不确定的情况一律放行。
最坏结果只是校验没生效，不会把功能拦死。

---

## 技术实现

### 构建方式很特别

**没有任何 AndroidX / Material 依赖。** `build.sh` 用 `javac` 直接对着 `android.jar` + `libxposed`
编译，链路是：

```
aapt2（含生成 R.java） → javac → d8 → 打包 dex 与 META-INF/xposed → zipalign → apksigner
```

所以：

- 设置页的 Material 3 观感是**照规范用原生 View 手画**的（`Ui.java`：色彩令牌、圆角、字号层级、水波纹），
  不是用 Material Components
- 开关也是自绘的（`Ui.Toggle`）——系统 `Switch` 的轨道在 Android 12+ 不吃 `setTrackTintList`，
  开/关分不清

### 截屏是三级降级

| 顺序 | 路径 | 说明 |
|---|---|---|
| ① | **`screencap`**（root） | 主路径：不依赖按键、不依赖无障碍、跟面板状态无关，约 0.25 秒 |
| ② | 无障碍直调系统截屏 | ① 失败时 |
| ③ | root 模拟「电源键 + 音量下」 | 兜底，偶尔要重试 |

`screencap` 不加 `-p` 出的是原始像素，自编码 JPEG 比 PNG 快约 37%（403ms → 255ms）。
但通道顺序（RGBA/BGRA）因 ROM 而异，所以内置**纯色块校准页**（`ColorProbeActivity`）：
画红/绿/蓝/青四块纯色再抓自己，按已知色值判定，**校准不通过就继续用 PNG**。

### hook 一览（v2.59 共 7 个）

| id | 作用 |
|---|---|
| `gate-flutter-channel` | 拦 `MethodChannel.invokeMethod`：只有被闸门放行过的那张图才允许去记账 |
| `capture-media-observer` | 记录宿主注册的相册观察者（用于状态判断与统计） |
| `gate-media-observer` | 相册变化时配合闸门决定是否放行 |
| `host-context-app` / `host-context-activity` | 取宿主 Context（读它的偏好与日志） |
| `host-lifecycle-probe` | 界面销毁探针，并**触发观察者重挂** |
| `engine-capture` | 捕获 `FlutterEngine` 引用（供重挂使用） |

### 删图判定

```
闸门放行一张图 → 记进「待结算」队列（按放行先后排序）
   ↓ 它每写一条「AI 识别 + 落库完成」
   ↓ 按时间顺序领走队首那张
配到结果 → 删（不看识别出几笔）
配不上 / 还没结果 → 等，最多 60 秒兜底
```

删图有硬门槛：绝对路径 / 图片扩展名 / 含「截图」字样 / 非相机目录 / 无 shell 特殊字符；
跳过小米的 `.pending-` 临时文件；同一张只删一次。

### 设置页只有两个开关

`常驻运行`、`识别后删除截图`。两个都经过真机双向验证。

> 之前有过「相册监听」「日志读不到时兜底」两个开关，已删除：
> 前者只是"有没有权限"的指示灯（没有开关状态），后者是内部兜底、用户无从判断。
> **不提供没有实际作用的开关。**

---

## 已放弃的方案（附原因，避免重复踩）

这些不是"没试过"，是**试过并验证不可行**：

| 方案 | 为什么不行 |
|---|---|
| 主动往它的 Flutter 通道推「有新截图」 | Dart 侧按需注册处理器，没注册时连回执都没有 |
| `ACTION_SEND` 把图分享给它 | 必然弹到前台，且与它自己的观察者重复认领 |
| `beecount://` 深链 | 同样要拉起界面 |
| root 直接写文件到相册目录 | **不会**触发它的观察者，它只认 MediaStore 的 insert/update 通知 |
| 拦 `stopScreenshotObserver` 阻止注销 | **按 Home 只触发 `onStop`，不触发 `onDestroy`** → 这个 hook 在"按 Home"场景下压根不会被调用 |
| hook `FlutterEngine.destroy()` 保活引擎 | 4 个探针（`destroy` / `Registry.removeAll` / `FlutterJNI.detach` / `Delegate.onDetach`）在后台 2.5 分钟内 **0 命中** → 引擎根本没被拆，问题在 Dart 侧 |
| hook `Cursor.getLong` 伪造 `dateAdded` | 全 App 热路径（每秒几千次调用），且 `getLong` 同时读**金额列**，改错会动到钱 |
| 刷新 `DATE_ADDED` 骗过 30 秒检查 | 实测**不可写**：`content update --bind date_added:i:...` 之后值纹丝不动 |
| 靠"厂商推送"静默唤醒 | 它的 APK 里**没有任何推送 SDK**（MiPush / HMS / vivo / OPPO / 个推 / 极光 / 友盟 / FCM 全无），也没有 Service、没有导出的 Provider、没有 sync adapter |

---

## 从源码构建

不需要 Android Studio：

```bash
# 需要 JDK 17、build-tools 35、platforms/android-35；路径在 build.sh 顶部按环境改
bash build.sh
```

产物输出到 `release/`。改版本号只需改 `build.sh` 顶部的 `VERSION_NAME` / `VERSION_CODE`
（同时同步 `xposed/module.prop` 与 `update.json`）。

⚠️ `AndroidManifest.xml` 里**不要**写 `android:versionCode` / `android:versionName`——
aapt2 link 以 manifest 为准，写死会盖掉命令行传的值，表现就是"版本号一直不涨"。

**签名密钥（`beecount-shot.jks`）不在仓库里。** 各版本签名一致，所以可以直接覆盖安装；
一旦密钥丢失就没法再签出同签名的 APK，已装设备必须**卸载重装**（会连带清掉 root 授权和
LSPosed 作用域）。`build.sh` 找不到密钥会**直接报错退出**，而不是悄悄生成新的。

---

## 目录结构

```
├─ AndroidManifest.xml / res/            组件声明、图标、无障碍配置、小组件布局、设置页主题
├─ src/dev/xtgxiso/beecountshot/
│   ├─ BeeShotModule.java                模块入口：7 道 hook + 引擎捕获
│   ├─ HostBridge / HostProbe / HostWatcher / HostRoot / HostState / HostTelemetry
│   │                                    宿主进程内：应答查询、读自己的数据、判定与删图
│   ├─ HostVerdict                       删图判定的唯一来源：按「落库完成」精确配对
│   ├─ HostRearm                         界面销毁后重挂截图观察者
│   ├─ ArmSignal / ArmReceiver / LastShot   放行额度与待结算队列
│   ├─ ShotTileService / ShotAccessibilityService / CollapseProxyActivity
│   │                                    磁贴、截屏、非 root 时的收面板退路
│   ├─ ScreencapShot / RawShot           主截屏路径：抓帧、写相册、raw→JPEG 编码
│   ├─ ColorProbeActivity                颜色校准页（纯色块自检通道顺序）
│   ├─ ShotCleaner / ShotWatcher / ShotToast / ShotBridge
│   │                                    删图任务队列、相册监听快路、一句话反馈
│   ├─ ExpenseWidgetProvider / WidgetConfigActivity / WidgetPrefs / WidgetBridge
│   │                                    桌面小组件
│   ├─ Ui / SettingsActivity             设置页：Material 3 视觉规范 + 自绘开关
│   └─ RootShell / HostInfo / Const / Prefs / Logx / LogExport / HookStats
├─ xposed/                               现代 Xposed API 注册文件
└─ build.sh / pack.py / verify.py
```

---

## 免责声明

仅供学习与个人使用。修改第三方应用行为可能违反其用户协议，请自行评估风险。
本项目与蜜蜂记账官方无关。

## 许可

[MIT](LICENSE)
