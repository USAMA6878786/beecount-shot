# BeeCount-Shot · 记账截图

给 [蜜蜂记账 BeeCount](https://github.com/TNT-Likely/BeeCount) 做的 **LSPosed 模块**：
**按需截图记账** + **支出桌面小组件**。

BeeCount 原本只要检测到系统截图就自动 OCR 记账，随手截个图也会变成脏账。
本模块把它改成**由你决定**——只有你点控制中心那一下，才截屏交给它记账。

---

## 功能

### 按需截图记账

- **平时的截图不再触发记账**：电源键、三指、长截屏都不再产生脏账
- 点控制中心「记账截图」磁贴 → 面板自动收起 → 截屏 → 交给 BeeCount 识别记账
- **截屏三级降级**，默认走第一条：

  | 顺序 | 方式 | 说明 |
  |---|---|---|
  | ① | root 直接抓帧（`screencap`） | 主路径，不依赖按键与无障碍，约 0.25 秒出图 |
  | ② | 无障碍直调系统截屏 | ① 失败时自动降级 |
  | ③ | root 模拟「电源键 + 音量下」 | 最后兜底 |

- **可以更快**：原始像素自编码 JPEG，比 PNG 快约 37%。因为不同机器通道顺序不同，
  内置**颜色校准**页——画四块纯色自检，通过后自动启用；不通过就继续用 PNG。
- **屏幕上有反馈**：截完弹一句「截图成功 / 截图失败」，不用去相册确认
- **识别完自动删图**：识别完成就把这张截图删掉，相册不会越堆越多（可在设置里关掉）
- **磁贴副标题**实时显示当前是否就绪

### 支出桌面小组件

- 今日 / 本周 / 本月支出，统计口径与 BeeCount 统计页一致
- 白底圆角，**背景透明度可调**；默认 2×1，横向拉长后自动变成左中右三列
- 点一下刷新数字并打开 BeeCount 的记账明细页
- 在 BeeCount 里增删改账目，小组件 2~3 秒内跟着更新

### 设置页

Material 3 卡片式布局，只有两个开关：**常驻运行**、**识别后删除截图**。
另有颜色校准、检查截屏链路、导出日志、跑一次诊断几个工具入口。

---

## 环境要求

| 项目 | 要求 |
|---|---|
| 系统 | Android 8.0（API 26）及以上 |
| 框架 | LSPosed（现代 API，101 / 102） |
| Root | **本模块必须给**；蜜蜂记账本体给了更顺手 |
| 宿主 | 蜜蜂记账 `com.tntlikely.beecount` |

---

## 安装

1. 从 [Releases](../../releases) 下载最新 `BeecountShot-vX.Y.apk` 安装
2. 打开 **LSPosed** → 模块 → 启用「记账截图」，**作用域里勾选「蜜蜂记账」**
3. 打开一次**蜜蜂记账**，允许它的 root 请求

   > ⚠️ 弹窗上写的是**「蜜蜂记账」请求超级用户权限**，不是本模块——这一步最容易漏

4. 进蜜蜂记账的「智能记账」，确认**截图监听是开着的**
5. 打开「记账截图」设置页：点「root 权限」授权 → 点「添加」把磁贴加进控制中心
   → 需要的话跑一次「颜色校准」

> **每次更新模块后，点一次设置页的「重启蜜蜂记账」。**
> 模块的代码是注入到它进程里的，不重启它就还在跑旧逻辑。

---

## 使用

**记一笔**

拉下控制中心 → 点「记账截图」磁贴 → 面板收起、自动截屏 → 屏幕弹「截图成功」
→ BeeCount 识别记账 → 识别完成后截图被删。

连着点几次也没问题，每一张都会单独记账、单独清理。

**加小组件**

长按桌面空白处 → 添加小部件 →「记账支出」。

> 只能手动添加。往桌面摆小组件是桌面应用自己的事，Android 没给任何应用这个接口。

**调透明度**

设置页滑块统一应用到所有小部件；长按某个小部件 → 重新配置，则只改它自己。

---

## 常见问题

**点了磁贴没反应？**

看设置页顶部的状态点，磁贴副标题也会提示：

| 状态 | 怎么办 |
|---|---|
| `● 缺少 root` | 点「root 权限」授权 |
| `● 未连上蜜蜂记账` | 点「重启蜜蜂记账」 |
| `● 部分可用` | 打开一次蜜蜂记账 |

**改了设置不生效？**

hook 只在蜜蜂记账启动时注入 → 点「重启蜜蜂记账」。

**截到的图里带着控制中心？**

说明这台机器收面板比 0.15 秒慢。把 `Const.SHOT_SETTLE_MS` 调大即可。

**小部件数字变灰了？**

BeeCount 进程没在跑，此时显示的是上次的值；打开一次它就会恢复实时。

**截图什么时候会被删？**

蜜蜂记账识别完就删，不管有没有识别出账单。判定是**精确配对**：它每处理完一张会写一条
「AI 识别 + 落库完成」日志，模块按放行顺序一一对上，配到就删。
对不上号的一律不删——宁可漏删，绝不误删。

---

## 权限与隐私

| 能力 | 用途 | 必需 |
|---|---|---|
| LSPosed / root | 拦截截图上报；截屏；收起控制中心；删除截图；重启宿主 | ✅ |
| 无障碍服务 | 备用截屏路径（root 抓帧失败时使用），不读屏幕内容、不监听界面事件 | 否 |
| 读取照片 | 只用来判断「最新一张截图是否已落盘」，加快确认速度 | 否 |
| 显示在其他应用上层 | 让「截图成功」的提示在后台也能显示 | 否 |
| 网络 | **不使用**，不收集、不上传任何数据 | — |

---

## 从源码构建

不需要 Android Studio，用 Android SDK 自带的构建工具直接出包：

```bash
# 需要 JDK 17、build-tools 35、platforms/android-35；路径在 build.sh 顶部按环境改
bash build.sh
```

链路：`aapt2（含生成 R.java）→ javac → d8 → 打包 dex 与 META-INF/xposed → zipalign → apksigner`，
产物输出到 `release/`。

改版本号只需改 `build.sh` 顶部的 `VERSION_NAME` / `VERSION_CODE`
（同时同步 `xposed/module.prop` 与 `update.json`）。

> 项目**没有任何 AndroidX / Material 依赖**，所以设置页的 Material 3 观感是照规范用原生 View
> 手画的（`Ui.java`），开关也是自绘的（`Ui.Toggle`）。

**签名密钥（`beecount-shot.jks`）不在仓库里。** 各版本签名一致，可直接覆盖安装；
`build.sh` 找不到密钥会直接报错退出，不会悄悄生成新的。

---

## 目录结构

```
├─ AndroidManifest.xml / res/            组件声明、图标、无障碍配置、小组件布局、设置页主题
├─ src/dev/xtgxiso/beecountshot/
│   ├─ BeeShotModule.java                模块入口：hook 安装
│   ├─ HostBridge / HostProbe / HostWatcher / HostRoot / HostState / HostTelemetry
│   │                                    宿主进程内：应答查询、读数据、判定与删图
│   ├─ HostVerdict                       删图判定：按「落库完成」精确配对
│   ├─ ArmSignal / ArmReceiver / LastShot   放行额度与待结算队列
│   ├─ ShotTileService / ShotAccessibilityService / CollapseProxyActivity
│   │                                    磁贴、截屏、收面板
│   ├─ ScreencapShot / RawShot           抓帧、写相册、raw→JPEG 编码
│   ├─ ColorProbeActivity                颜色校准页
│   ├─ ShotCleaner / ShotWatcher / ShotToast / ShotBridge
│   │                                    删图任务队列、相册监听、一句话反馈
│   ├─ ExpenseWidgetProvider / WidgetConfigActivity / WidgetPrefs / WidgetBridge
│   │                                    桌面小组件
│   ├─ Ui / SettingsActivity             设置页
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
