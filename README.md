# BeeCount-Shot · 记账截图

为 [蜜蜂记账 BeeCount](https://github.com/TNT-Likely/BeeCount) 做的 LSPosed 模块：
**按需截图记账** + **支出桌面小组件**。

> BeeCount 原本只要检测到系统截图就会自动 OCR 记账，随手截个图也会产生脏账。
> 本模块把它改成**由你决定**：只有点控制中心那一下才记账。

---

## 功能

### 按需截图记账
- **默认拦截**：平时的普通截图（电源键 / 三指 / 长截屏）不再触发 BeeCount 识别记账
- **控制中心磁贴**：点「记账截图」磁贴 → 自动收起面板 → 等约 0.6 秒 → 截当前屏幕 → 交给 BeeCount 识别记账
- **磁贴会显示状态**：下拉面板时副标题直接告诉你"能不能用"
- **记账成功后自动删除该截图**：判定两条证据齐备才删，识别失败的截图一律保留

### 支出桌面小组件
- 同时显示 **今日 / 本周 / 本月支出**
- **白底圆角**，背景透明度可调（按小部件分别保存）
- 默认 2×1，**横向拉长后自动从"上下三行"变成"左中右三列"**
- 点一下：刷新数字 + 打开 BeeCount 的记账明细页
- 在 BeeCount 里手动记一笔，小部件也会立刻跟着更新

---

## 环境要求

| 项目 | 要求 |
|---|---|
| 系统 | Android 9（API 28）及以上 |
| 框架 | LSPosed（现代 API 101/102） |
| Root | **需要**（用于收起控制中心、删除截图、重启宿主） |
| 宿主 | 蜜蜂记账 `com.tntlikely.beecount`（也支持 `.dev` 构建） |

> 为什么需要 root：收起控制中心必须执行系统命令 `cmd statusbar collapse`
> （标准的 `startActivityAndCollapse` 在不少定制 ROM 上不生效）；另外删除公共存储里的
> 截图、以及"一键重启宿主"也都需要它。

---

## 安装

1. 从 [Releases](../../releases) 下载最新的 `BeeCountShot-vX.Y.apk` 并安装
2. 打开 **LSPosed → 模块**，启用「记账截图 (BeeCount)」
3. **检查作用域**：确认里面勾选的是你实际安装的蜜蜂记账（模块列出了 prod/dev 两个候选包名，
   但作用域是可手动调整的，以防你的包名不在候选里）
4. 打开一次 **蜜蜂记账**，会弹出 root 授权框 —— **请允许**（删除截图需要）
5. 强制停止蜜蜂记账，再重新打开（hook 只在应用启动时注入）
6. 打开「记账截图」应用，按页面顺序完成：加磁贴 → 申请 root → 设等待时长

---

## 使用

**记账**：在要记录的页面拉下控制中心 → 点「记账截图」磁贴 → 面板收起、自动截屏 →
BeeCount 识别记账 → 约 2~3 秒后截图被自动删除。

**小部件**：长按桌面空白处 → 添加小部件 → 「记账支出」；
也可在「记账截图」里点「添加「记账支出」小部件」一键钉到桌面。
背景透明度在模块设置页调（统一应用到所有小部件），长按某个小部件 → 重新配置则只改它自己。

---

## 常见问题

**点了磁贴没反应？**
先看磁贴副标题和模块设置页顶部状态。最常见的原因是**蜜蜂记账的主界面被系统回收了**——
它的截图监听挂在 `MainActivity` 上，界面被回收就彻底感知不到截图。打开一次它即可
（后台存活就够，不必留在前台）。

**改了配置不生效？**
hook 只在应用启动时注入，用设置页的「重启蜜蜂记账」按钮，或手动强停后重开。

**截到的图里带着控制中心？**
把等待时长调大一档（等待是从"收起命令发出"之后起算的）。

**小部件数字是灰色的？**
说明 BeeCount 进程没在跑，此时显示的是上次的数值；打开一次它就会恢复实时。

**识别失败了但截图没被删？**
这是**刻意设计**。只有确认"这张图被交给了 BeeCount"且"之后确实有一次自动记账成功"
才会删；识别失败、超时、任何一条不满足都保留。宁可漏删，绝不误删。

**需要每次改配置都强停吗？**
是。这是 LSPosed 模块的通用行为，不是本模块的限制。

---

## 权限与隐私

| 能力 | 用途 |
|---|---|
| LSPosed / root | 拦截宿主截图上报；执行 `cmd statusbar collapse`；删除截图；重启宿主 |
| 无障碍服务 | **仅**用于触发一次系统截屏。不读取屏幕内容、不监听任何界面事件 |
| 网络 | **不使用**。模块完全离线，不收集、不上传任何数据 |

模块读取 BeeCount 的支出数据时，**不直接读取它的数据库文件**，而是让 BeeCount 自己
在自己的进程里读，再通过广播把结果送过来（原因是应用私有数据目录无法从外部访问，
见下方技术说明）。

---

## 从源码构建

不需要 Android Studio，用 Android SDK 的构建工具直接出包：

```bash
# 需要：JDK 17、Android build-tools 35、platforms/android-35
# 路径在 build.sh 顶部按你的环境改一下
bash build.sh
```

链路：`aapt2 compile/link（含生成 R.java）→ javac → d8 → 打包 dex 与 META-INF/xposed → zipalign → apksigner`

产物：`BeecountShot-vX.Y.apk`。

**签名密钥**：仓库里的 `beecount-shot.jks`（口令 `android`）是一把**故意公开的开发用密钥**，
目的是保证每次构建签名一致、用户能直接覆盖安装升级。如果你要自己维护并重新发布，
建议换成你自己的密钥（同时也要保留旧密钥，否则老用户必须卸载重装）。

---

## 目录结构

```
├─ AndroidManifest.xml          组件声明（磁贴 / 无障碍 / 小组件 / 透明中转页 / 设置页）
├─ res/                         图标、无障碍配置、小组件布局
├─ src/dev/xtgxiso/beecountshot/
│   ├─ BeeShotModule.java       模块入口（宿主进程）：四道 hook + 生命周期探针
│   ├─ LastShot.java            闸门放行的那张图（删截图的判定依据之一）
│   ├─ HostState.java           宿主截图监听是否在位
│   ├─ HostWatcher.java         盯 BeeCount 的成功日志，由宿主自己删截图
│   ├─ HostRoot.java            宿主侧 root：确认授权 + 执行删除
│   ├─ ExpenseQuery.java        宿主读自己的 SQLite，算日/周/月支出
│   ├─ HostProbe.java           宿主给出「可删」最终判定 + 回广播
│   ├─ HostBridge.java          注册各接收器；启动监视
│   ├─ ArmSignal.java           宿主侧「限时放行」窗口
│   ├─ ShotTileService.java     控制中心磁贴
│   ├─ ShotAccessibilityService.java  按下快门（唯一的无障碍职责）
│   ├─ CollapseProxyActivity.java     非 root 时的收面板退路
│   ├─ ShotCleaner.java         模块 App 侧执行删除（兜底路径）
│   ├─ ExpenseWidgetProvider.java     桌面小组件
│   ├─ WidgetConfigActivity.java      添加/重配置时的透明度页
│   ├─ WidgetPrefs.java / WidgetBridge.java / HookStats.java
│   ├─ RootShell.java           所有 root 命令
│   ├─ SettingsActivity.java    设置页
│   └─ Const.java / Prefs.java / Logx.java / LogExport.java / HostInfo.java / HostTelemetry.java
├─ xposed/                      现代 Xposed API 的注册文件（java_init.list / module.prop / scope.list）
├─ build.sh / pack.py / verify.py
└─ 安装与使用说明.md             更详细的使用手册
```

---

## 技术说明

几个绕不开的点，都写在代码注释里了：

- **Hook 点选择**：BeeCount 的 release 版开了 R8 混淆，不能按类名 hook。本模块锚在
  `io.flutter.plugin.common.MethodChannel.invokeMethod` 上、按方法名
  `onScreenshotDetected` 过滤——宿主自己的 proguard 规则已经 keep 了
  `io.flutter.plugin.common.**`，且字符串常量不会被混淆，因此抗升级。
- **跨进程通信一律用广播**：`su` 跑在全局挂载命名空间，看不到应用的数据目录，
  用 root 读写宿主私有数据会**静默失效**。所以"需要宿主数据"的场景一律让宿主自己读、
  再广播回来。
- **删除截图由宿主执行**：模块 App 退回后台会被系统冻结，而发给缓存态应用的广播会被
  延迟投递；宿主那一刻是醒着的，让它自己动手才能把延迟压到 2~3 秒。
- **小组件统计口径**：与 BeeCount 统计页**一字不差**（`type='expense'`、
  `exclude_from_stats=0`、金额取 `COALESCE(native_amount, amount)`、月周期跟随账本的
  `month_start_day`、周从周一起算）。
- **RemoteViews 限制**：小组件布局只能用白名单视图，`<View>` 会导致桌面解析失败；
  背景位图走 Binder，必须限制尺寸。

---

## 后续计划

- 内置日志查看页（现在需要导出到 Download 目录再看）
- 小组件跟随系统深色模式
- 截图后自动拉起宿主并重新触发扫描，进一步降低"界面被回收"的影响

---

## 免责声明

仅供学习与个人使用。修改第三方应用行为可能违反其用户协议，请自行评估风险。
本项目与蜜蜂记账官方无关。

## 许可

[MIT](LICENSE)
