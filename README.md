# 金钱卦 · 手表端

> 摇一摇手表，得一个卦。
> A native six-line (六爻) divination app for OPPO Watch — offline, zero-dependency, zero AndroidX.

一个跑在智能手表上的**金钱卦（六爻摇卦）**应用。摇动手表出爻，本地成卦。

全离线运行：不联网、不申请任何多余权限、不采集任何数据。

- 包名 `com.wearcast.liuyao`，显示名「**金钱卦**」
- 当前版本 **v0.3.1**（`versionCode 3`）
- 纯 Kotlin，**零第三方依赖、零 AndroidX、零 Compose**
- 已在 OPPO Watch 3（`OWW212`）上完成端到端真机验证

---

## 功能

| 能力 | 说明 |
| --- | --- |
| **两种起卦模式** | **一键**：轻摇确认后自动连抛六爻；**沉浸**：一爻一爻摇满六次 |
| **国风抛掷动画** | 每次抛掷走完整的六拍分镜（沉浸约 2.25 s；一键压到约 0.7 s 并省略翻面） |
| **本地成卦** | 本卦 + 变卦，动爻高亮；六爻安静时明确标注「无动爻」 |
| **经文原文离线内置** | 64 卦卦辞 + 384 爻爻辞（含乾/坤用九、用六） |
| **双表盘适配** | 方表全宽；圆表按内接正方形安全区与逐行弦宽精算，不裁字 |
| **卜卦全程常亮** | 摇卦页与结果页持有 `FLAG_KEEP_SCREEN_ON`，从摇第一爻到看完结果都不熄屏 |
| **音效可关** | 设置项保留，**默认关闭** |

### 起卦流程

```
首页
 ├─ 一键起卦 ── 轻摇确认（阈值 2.0 G）── 一次采样连出六爻 ──┐
 └─ 沉浸起卦 ── 摇六次，每次一爻（阈值 2.5 G）──────────┴─→ 结果页
                                                              本卦 / 变卦
                                                              卦辞 · 爻辞
```

两种模式**共用**摇卦页与结果页，并且**首屏落点一致**——都停在页面开头，不做任何自动滚动。

---

## 随机源：为什么是「物理熵 + CSPRNG」混合

六爻摇卦要求每种爻的概率严格等于「三枚独立铜钱」：

| 背数 | 爻型 | 概率 |
| --- | --- | --- |
| 3 背 | 老阳（动） | 1/8 |
| 2 背 | 少阴 | 3/8 |
| 1 背 | 少阳 | 3/8 |
| 0 背 | 老阴（动） | 1/8 |

只用手表传感器会有偏——握持方式、手臂姿态、器件零偏都会影响结果；
只用 `SecureRandom` 又跟「摇」这个动作毫无关系。所以两者混合，各取所长：

1. 采集物理熵池（加速度计 + 陀螺仪采样）
2. 与 CSPRNG 种子、`nanoTime` 一起做 SHA-256，取低位 3 bit 作为**混淆位**
3. 最终取值 `combo = rng.nextInt(8) XOR noise3`

关键在第 3 步的数学性质：**均匀随机变量与任意独立变量异或后仍严格均匀**。

因此物理熵只参与混淆、不左右分布，8 种铜钱组合严格等概率，
「背」的枚数自然服从 `Binomial(3, ½)` —— 与真实铜钱的概率完全一致。
摇动的作用是让每次结果不可复现，而不是让结果有偏。

一键模式下只有一次采集却要连出六爻，于是六爻各取熵池的不同切片做混淆位；
切片怎么取都不影响分布，六爻之间的独立性由 `rng.nextInt(8)` 的连续六次抽样保证。

---

## 目标设备与硬约束

本工程针对 **OPPO Watch 3（`OWW212`）** 开发，同时兼容 OPPO 圆表（`OPWW234`）。

| 项 | OWW212（方表，主力） | OPWW234（圆表） |
| --- | --- | --- |
| 系统 | Android 11 / API 30，无 GMS | 同 |
| 屏幕 | 372 × 430 px @320dpi = **186 × 215 dp** | 466 × 466 px @320dpi = **233 × 233 dp** |
| 表盘 | 方屏、圆角、无挖孔 | `FLAG_ROUND` |
| 内存 | **860 MB**，带 `android.hardware.ram.low` | 约 1.87 GB |
| ABI | **仅 `armeabi-v7a` / `armeabi`（32 位）** | 同 |

### 为什么零 AndroidX / 零 Compose

目标机只有 860 MB 内存且带 `ram.low`，并且是 32 位 armeabi-v7a。
在这种机器上引入 AndroidX（更别提 Compose）会显著抬高内存占用与包体，收益不值。

因此本工程刻意做了这些取舍：

- 不引入任何第三方依赖 —— `app/build.gradle.kts` 里 `dependencies {}` 块**是空的**
- Activity 直接继承 `android.app.Activity`，不用 `AppCompatActivity`
- 界面全部用原生 `View` + `Canvas` 自绘
- `gradle.properties` 里显式声明 `android.useAndroidX=false`
- 产物为纯 dex，**不含任何 `.so`**
- 应用设置只用 `SharedPreferences`（目前唯一的设置项是音效开关）
- 历史记录若日后落地，会选用裸 `SQLiteDatabase` 或 JSON 文件而非 Room —— 该页面目前尚未实现

### 权限

只声明了一个权限：

```xml
<uses-permission android:name="android.permission.VIBRATE" />
```

API 30 下读取加速度计与陀螺仪**无需任何权限**
（`HIGH_SAMPLING_RATE_SENSORS` 是 API 31 才引入的）。应用不联网。

---

## 双表盘适配

判定表盘形态**只用 `Configuration.isScreenRound`**，禁止用分辨率或机型名 —— 这是硬规矩。

`render/DisplayGeometry.kt` 是唯一几何出口，对外只提供两个能力：

- `safeWidthPx`：圆表取 `min(W, H) / √2`（内接正方形边长），方表取全宽
- `widthAt(y)`：圆表算 `2√(R² − dy²)`（逐行弦宽），方表直接返回全宽

两个踩过的坑，写在代码注释里也写在这里：

- 自绘 View 里**不要用局部 y 去算弦宽**。视图不在屏幕顶部时坐标会算错；
  要么用与位置无关的 `safeWidthPx`，要么像 `MeanderBandView` 那样用
  `getGlobalVisibleRect` 取真实屏幕纵坐标。
- 自绘 View 的 `onDraw` **必须从视图自身的 width / height 起算**，
  屏幕安全区只能当上限钳制，否则收窄后记号会画到视图外。

---

## 项目结构

```
app/src/main/
├── java/com/wearcast/liuyao/
│   ├── core/
│   │   ├── CoinMapper.kt       熵 → 铜钱组合 → 爻型
│   │   ├── EntropySampler.kt   传感器采样与熵池
│   │   ├── HexagramEngine.kt   六爻 → 本卦 / 变卦
│   │   └── ShakeDetector.kt    摇动判定（阈值 / 时间窗 / 防抖 / 陀螺仪）
│   ├── data/
│   │   ├── HexagramData.kt     64 卦 × 6 爻的经文数据
│   │   └── Prefs.kt            偏好（音效开关）
│   ├── render/
│   │   ├── DisplayGeometry.kt  方表 / 圆表统一几何出口
│   │   └── InkPalette.kt       水墨配色
│   ├── ui/
│   │   ├── MainActivity.kt     首页
│   │   ├── CastActivity.kt     摇卦页（两种模式共用）
│   │   ├── ResultActivity.kt   结果页
│   │   └── SoundFx.kt          音效
│   └── view/
│       ├── CoinTossView.kt         六拍国风抛掷动画
│       ├── HexagramStackView.kt    摇卦页的六爻带
│       ├── HexagramView.kt         单个卦象绘制
│       ├── MeanderBandView.kt      回纹装饰带
│       └── ProgressRingView.kt     进度环
└── res/
    ├── layout/      activity_main / activity_cast / activity_result
    ├── drawable/    按钮、徽标、分隔线
    └── raw/coin.wav 铜钱音效
```

---

## 构建

### 环境

| 组件 | 版本 | 说明 |
| --- | --- | --- |
| JDK | **17+**（实测 25） | AGP 8.13 要求 17 以上 |
| Gradle | **9.0.0** | 已由 wrapper 锁定，无需单独安装 |
| Android Gradle Plugin | 8.13.0 | |
| Kotlin | 2.2.0 | |
| Android SDK | Platform 35 + Build-Tools 35.0.1 | 对应 `compileSdk = 35` |

> ⚠️ **不要用 Gradle 8.13 配 JDK 25。**
> 8.13 内嵌的 Kotlin `JavaVersion.parse` 认不出 `25.x`，会直接抛
> `IllegalArgumentException: 25.0.3`，连 `gradle help` 都跑不起来。本工程锁定 9.0.0 正是为此。

### 命令

```bash
./gradlew :app:assembleDebug
```

产物文件名带版本号：

```
app/build/outputs/apk/debug/金钱卦-v0.3.1-debug.apk
```

首次构建需要联网拉取 AGP 与 Kotlin 插件；依赖缓存好后可以加 `--offline` 完全离线构建。

### 安装与调试

```bash
# 安装（覆盖安装用 -r）
adb install -r "app/build/outputs/apk/debug/金钱卦-v0.3.1-debug.apk"

# 看崩溃日志
adb logcat -d -b crash | grep -iE "FATAL|ANR"
```

调试构建下**长按铜钱**可模拟出爻，不必真的甩手表，便于验证链路。

### 调手感

摇动灵敏度只有一个常量：`core/ShakeDetector.kt` 里的 `DEFAULT_THRESHOLD_G`（默认 `2.5f`）。
摇不出就往 `2.2` 调。一键模式的确认阈值 = 该值 − 0.5。

---

## 真机验证状态

| 项 | 状态 |
| --- | --- |
| 方表 `OWW212` 安装 + 端到端 | ✅ 已完成 |
| 首页 / 摇卦页布局实测 | ✅ 逐项命中设计值 |
| 一键链路 | ✅ 出「火风鼎」，手工核对无误 |
| 沉浸链路 | ✅ 出「雷风恒 → 山天大畜」（动爻 1 / 4 / 6），手工核对无误 |
| 结果页首屏落点 | ✅ 两种模式均停在页面开头 |
| 卜卦全程常亮 | ✅ 两页运行期均为 `fl=KEEP_SCREEN_ON` |
| 稳定性 | ✅ 无 FATAL / ANR |
| 圆表 `OPWW234` 真机使用 | ✅ 已在真机使用中确认可用（未做逐项几何实测） |
| 币文「周易通宝」的中文衬线 | ⬜ 待确认（`Typeface.create("serif")` 在 ColorOS Watch 上是否有 CJK 衬线） |

---

## 已知限制

- **未做纳甲**：不算世应、六亲、五行，只出本卦 / 变卦与经文原文
- **白话断语留空**：结果页明确标注「断语待补（本版只出经文原文）」
- **历史卦例页与独立详解页尚未实现**
- **圆表适配以真机观感为准，未逐项量取几何数据**：内接正方形安全区（`safeWidthPx`）、
  六爻带缩放（`min(88dp, 安全区边长 × 0.30)`）、进度环是否裁切，均为真机肉眼确认可用，
  没有做逐项测量
- 产物改名借助了 AGP 内部类 `BaseVariantOutputImpl`。这是 AGP 未提供公开 API 的无奈之举，
  只影响产物命名，不影响打包内容；**AGP 大版本升级时这里可能需要调整**

---

## 免责声明

> 传统文化娱乐用途，不作任何决策依据。

## 许可证

本仓库**未附开源许可证**，默认保留所有权利（All rights reserved）。
如需转载或复用，请先联系作者。
