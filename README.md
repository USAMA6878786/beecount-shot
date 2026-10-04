# BeeCount-Shot · 记账截图

为 [蜜蜂记账 BeeCount](https://github.com/TNT-Likely/BeeCount) 做的 LSPosed 模块：**按需截图记账** + **支出桌面小组件**。

BeeCount 原本只要检测到系统截图就自动 OCR 记账，随手截个图也会产生脏账。本模块把它改成**由你决定**——只有点控制中心那一下才记账。

## 功能

**按需截图记账**
- 平时的截图（电源键 / 三指 / 长截屏）不再触发 BeeCount 识别记账
- 点控制中心「记账截图」磁贴 → 自动收起面板 → 约 0.6 秒后截屏 → 交给 BeeCount 识别记账
- 磁贴副标题会显示当前是否就绪
- 记账成功后自动删除该截图；识别失败、超时的一律保留

**支出桌面小组件**
- 今日 / 本周 / 本月支出，统计口径与 BeeCount 统计页一致
- 白底圆角、背景透明度可调；默认 2×1，横向拉长后自动变成左中右三列
- 点一下刷新数字并打开 BeeCount 的记账明细页；在 BeeCount 里手动记账也会立刻同步

## 环境要求

| 项目 | 要求 |
|---|---|
| 系统 | Android 8.0（API 26）及以上 |
| 框架 | LSPosed（现代 API 101 / 102） |
| Root | **本模块和蜜蜂记账本体都要给**，见下 |
| 宿主 | 蜜蜂记账 `com.tntlikely.beecount`（也支持 `.dev` 构建） |

**两个应用都得授予 root，缺一不可**：

- **本模块**：执行 `cmd statusbar collapse` 收起控制中心、重启宿主。没给也能拦截截图，但收起面板要退回一个中转页面。
- **蜜蜂记账本体**：删除截图。删除动作是由**它自己在自己的进程里**执行的——因为那一刻它醒着、不会被系统冻结，而模块 App 退到后台会被冻结。所以必须以它的身份拿到 root。

只给模块不给蜜蜂记账的话，自动删图会退回一条较慢也不稳定的兜底路径（靠广播唤醒模块 App），其余功能不受影响。完全不想给蜜蜂记账 root，可以在设置页关掉「记账成功后删除截图」。

## 安装

1. 从 [Releases](../../releases) 下载 `BeecountShot-vX.Y.apk` 安装
2. LSPosed → 模块 → 启用「记账截图 (BeeCount)」，并确认**作用域里勾选了蜜蜂记账**
3. 打开一次蜜蜂记账并**允许它的 root 请求**——⚠️ 这一步容易漏：弹窗上写的是**「蜜蜂记账」请求超级用户权限**，不是本模块。然后强制停止再打开（hook 只在应用启动时注入）
4. 打开「记账截图」，按页面提示添加磁贴、调整等待时长

## 使用

- **记账**：拉下控制中心点磁贴 → 面板收起、自动截屏 → BeeCount 识别记账 → 约 2~3 秒后截图被删
- **小部件**：长按桌面 → 添加小部件 →「记账支出」；也可在模块设置页一键钉到桌面
- **调透明度**：模块设置页的滑块（统一应用到所有小部件）；长按某个小部件 → 重新配置则只改它自己

## 常见问题

**点了磁贴没反应？**
多半是蜜蜂记账的主界面被系统回收了——它的截图监听挂在 `MainActivity` 上，界面被回收就感知不到截图。打开一次它即可（后台存活就够，不必留在前台）。磁贴副标题和设置页顶部都会提示当前状态。

**改了配置不生效？**
hook 只在应用启动时注入。用设置页的「重启蜜蜂记账」（强制停止并重新打开）即可。

**截到的图里带着控制中心？**
把等待时长调大一档。

**小部件数字是灰色的？**
说明 BeeCount 进程没在跑，此时显示的是上次的数值；打开一次它就会恢复实时。

**识别失败了截图却被保留？**
这是刻意设计。必须同时确认"这张图被交给了 BeeCount"且"之后确实有一次自动记账成功"才会删；任何一条不满足都保留——宁可漏删，绝不误删。

## 权限与隐私

| 能力 | 用途 |
|---|---|
| LSPosed / root | 拦截宿主截图上报；收起控制中心；删除截图；重启宿主 |
| 无障碍服务 | **仅**用于触发一次系统截屏，不读取屏幕内容、不监听界面事件 |
| 网络 | **不使用**，不收集、不上传任何数据 |

模块不直接读取 BeeCount 的数据库，而是让 BeeCount 在自己的进程里读、再通过广播把结果送回来（原因见下方技术说明）。

## 从源码构建

不需要 Android Studio，用 Android SDK 自带的构建工具直接出包：

```bash
# 需要 JDK 17、build-tools 35、platforms/android-35；路径在 build.sh 顶部按你的环境改
bash build.sh
```

链路：`aapt2（含生成 R.java）→ javac → d8 → 打包 dex 与 META-INF/xposed → zipalign → apksigner`，产物输出到 `release/`。

改版本号只需改 `build.sh` 顶部的 `VERSION_NAME` / `VERSION_CODE` 两行（同时同步 `xposed/module.prop`）。

`beecount-shot.jks`（口令 `android`）是一把**故意公开**的开发用密钥，目的是让各版本签名一致、用户能直接覆盖安装。如果你要长期维护并自行发布，建议换成自己的密钥——但必须保留旧密钥，否则老用户得卸载重装。

## 目录结构

```
├─ AndroidManifest.xml / res/           组件声明、图标、无障碍配置、小组件布局
├─ src/dev/xtgxiso/beecountshot/
│   ├─ BeeShotModule.java               模块入口：四道 hook + 生命周期探针
│   ├─ HostBridge / HostProbe / HostWatcher / HostRoot / HostState
│   │  HostTelemetry / ExpenseQuery      宿主进程内：应答查询、读自己的数据、判定与删图
│   ├─ ArmSignal / ArmReceiver / LastShot   放行窗口与删除判定依据
│   ├─ ShotTileService / ShotAccessibilityService / CollapseProxyActivity
│   │                                   磁贴、截屏、非 root 时的收面板退路
│   ├─ ExpenseWidgetProvider / WidgetConfigActivity / WidgetPrefs / WidgetBridge
│   │                                   桌面小组件
│   ├─ RootShell / HostInfo / ShotCleaner / ShotBridge    模块 App 侧
│   └─ SettingsActivity / Const / Prefs / Logx / LogExport / HookStats
├─ xposed/                              现代 Xposed API 注册文件
└─ build.sh / pack.py / verify.py
```

## 技术说明

- **Hook 点抗混淆**：锚在 `io.flutter.plugin.common.MethodChannel.invokeMethod`、按方法名 `onScreenshotDetected` 过滤。宿主自己的 proguard 规则已 keep 该包，字符串常量也不会被混淆，因此抗升级。
- **跨进程通信一律用广播**：`su` 跑在全局挂载命名空间，看不到应用的数据目录，用 root 读写宿主私有数据会**静默失效**。所以凡是需要宿主数据的场景，一律让宿主自己读、再广播回来。
- **删除截图由宿主执行**：模块 App 退回后台会被系统冻结，而发给缓存态应用的广播又会被延迟投递；宿主那一刻是醒着的，让它自己动手才能把延迟压到 2~3 秒。
- **小组件统计口径**与 BeeCount 统计页一致：`type='expense'`、`exclude_from_stats=0`、金额取 `COALESCE(native_amount, amount)`、月周期跟随账本的 `month_start_day`、周从周一起算。
- **RemoteViews 限制**：布局只能用白名单视图（`<View>` 会让桌面解析失败）；背景位图走 Binder，必须限制尺寸。

## 后续计划

- 内置日志查看页；小组件跟随系统深色模式
- 截图后自动拉起宿主并重新触发扫描，进一步降低"界面被回收"的影响

## 免责声明

仅供学习与个人使用。修改第三方应用行为可能违反其用户协议，请自行评估风险；本项目与蜜蜂记账官方无关。

## 许可

[MIT](LICENSE)
