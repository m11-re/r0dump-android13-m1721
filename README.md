# r0dump for Android 13 (LineageOS 20)

> **本仓库是 [tiwe0/r0dump](https://github.com/tiwe0/r0dump) 的 fork**，把它的 ART 集成式
> 脱壳（Fusion 补丁集 + FART 主动调用思路）落到 **Android 13 / LineageOS 20**
> （art @ `android-13.0.0_r75`），并补上了上游面向 Android 16 时不存在的几个 A13 专属问题。
>
> 上游作者：**tiwe0**；本 fork 只做 A13 适配与 bug 修复，脱壳架构与捕获点设计归上游。

## 相对上游的改动

| 类别 | 内容 |
| --- | --- |
| **降级适配** | 上游面向 A16 的 API 在 A13 不存在，逐个回移（见「为让 A13 能编译而做的适配」） |
| **A13 目录准备通道** | A13 的 priv_app 不能写 `persist.sys.r0dump.fusion.*`，改为 Manager → AMS → root helper → `SCM_RIGHTS` 回传目录 fd |
| **修复：containerless dex 被丢弃** | 壳（如爱加密）解密后的 dex 无 container，上游在 `r0dump_CaptureDex` 入口直接丢弃；本 fork 改为降级走 `DataBegin/DataSize` 逻辑数据路径 |
| **修复：壳尾部 trailer** | 壳在 map 段后附加数据并改大 `file_size`（违反 `data_off+data_size == file_size`），上游一律拒收；本 fork 识别并裁掉 trailer，同步改写头部 |
| **修复：`file_size` 未同步** | 裁断/回填后头部 `file_size` 未改写，导致产物自相矛盾、`dexdump` 报 `Bad file size` |
| **新增** | `r0dump_mcp_bridge`（adb stdio ↔ socket 中继）、管理器 MCP server、设备端导出与修复 |

捕获点全部做在 ART **内部**，不依赖 frida / 注入 / 外部脚本；配置与产物交付走
system_server + root helper 的固定协议。

> **本仓库只含 r0dump 相关改动。**
> KernelSU / root 相关的改动（`drivers/kernelsu/`、`vendor/kernelsu/`、
> `CONFIG_KSU*`、`build/soong` 的 `preprocessed` 属性）**不在此仓库**，需另行集成。

---

## 一键集成

```bash
git clone https://github.com/m11-re/r0dump-android13.git
cd r0dump-android13

# 1. 先检查（不改任何文件）
./apply.sh /path/to/lineageos-20 --check

# 2. 应用
./apply.sh /path/to/lineageos-20

# 只应用部分项目
./apply.sh /path/to/lineageos-20 --only art,libcore

# 撤销
./apply.sh /path/to/lineageos-20 --reverse
```

`apply.sh` 会：按补丁的 `(项目, 文件)` 对逐个 `git apply`；若目标树基线不同则自动退化为
`git apply -3` 三方合并并**提示你人工检查**；最后把 `tree/` 下的整目录拷进源码树。

### 前置条件

- 目标树是 repo 管理的 LineageOS 20（必须存在 `.repo/`）
- 补丁基线 commit 见 [`patches/baselines.txt`](patches/baselines.txt)
- `device/meizu/m1721` 只对你自己的设备树生效；换设备请自行替换或跳过该补丁

---

## 目录结构

```
patches/
  01-art.patch                    art                              48 文件
  02-frameworks-base.patch        frameworks/base                   9 文件
  03-libcore.patch                libcore                           3 文件
  04-system-sepolicy.patch        system/sepolicy                  10 文件
  05-device-meizu-m1721.patch     device/meizu/m1721                1 文件
  baselines.txt                   各补丁基线 commit
  series                          可读清单
tree/
  packages/apps/R0dumpManager/    管理器 App（整目录新增，直接拷入）
apply.sh                          集成脚本
```

---

## 改动内容

### 1. `art` — 捕获引擎（补丁 01）

| 路径 | 说明 |
| --- | --- |
| `runtime/r0dump/` | 引擎本体：43 个捕获点的枚举/掩码、捕获调度、缓冲池、落盘、配置校验、ForceInvoke、日志（8 对 `.cc/.h`） |
| `runtime/native/dalvik_system_r0dump_Runtime.cc/.h` | 14 个 JNI 入口（`gMethods[]`），注册器挂在 `runtime.cc` |
| `runtime/{class_linker,art_method,oat_file,oat_file_manager,oat_file_assistant,jit,instrumentation,runtime}.cc` | 捕获点接线 |
| `runtime/interpreter/interpreter.cc`、`interpreter/mterp/{arm64ng,armng,x86ng,x86_64ng}/main.S`、`mterp/nterp.cc` | 解释器 / nterp 入口观测 |
| `runtime/native/{dalvik_system_DexFile,java_lang_reflect_Method,java_lang_reflect_Constructor}.cc` | DEX 打开 / 反射路径 |
| `libdexfile/dex/dex_file_loader.cc/.h` | `OpenCommon` 全量 DEX 漏斗的 observer |
| `libdexfile/libdexfile.map.txt` | 导出 `r0dump_SetOpenCommonObserver`（否则 APEX 链接 undefined） |
| `runtime/handle_scope.h`、`handle_scope-inl.h` | `VariableSizedHandleScope::Size()/GetHandle()` 回移 |
| `tools/r0dump/` | host 侧修复工具：`r0dump_host_repair.py`、`r0dump_dex_rehydrate.py` + 回归测试 |

### 2. `frameworks/base` — Java 驱动层（补丁 02）

| 路径 | 说明 |
| --- | --- |
| `core/java/android/app/r0dump_FusionConfig.java` | 进程内配置读取、`generation` 原子发布校验、输出根校验 |
| `core/java/android/app/r0dump_ClassLoaderWalk.java` | 受预算约束的 ClassLoader 漫游 + `ForceInvoke` |
| `core/java/android/app/r0dump_ManifestHelper.java` | AndroidManifest 种子解析（命名空间来源） |
| `core/java/android/app/r0dump_FileWorker.java` | 延迟 flush 线程 |
| `services/core/java/com/android/server/am/r0dump_FilePreparationBridge.java` | `SOCK_SEQPACKET` 客户端，走 helper 建目录 + `SCM_RIGHTS` 收 fd |
| `core/java/android/app/ActivityThread.java` | 三处接线（bindApplication / Application.onCreate 后 / 首个 Activity 后） |
| `core/java/android/app/IActivityManager.aidl`、`services/.../ActivityManagerService.java` | `r0dump_prepareOutput` / `r0dump_exportLatest` |
| `core/res/AndroidManifest.xml` | `MANAGE_R0DUMP_EXPORT` 权限声明 |

### 3. `libcore`（补丁 03）

- `libart/src/main/java/dalvik/system/r0dump_Runtime.java`：14 个 native 声明，
  `@SystemApi(client = MODULE_LIBRARIES)`
- `api/module-lib-current.txt`：API 签名
- `non_openjdk_java_files.bp`：加入构建

### 4. `system/sepolicy`（补丁 04）

- `public/property.te`：`system_restricted_prop(r0dump_fusion_prop)`
- `private/property_contexts`：`persist.sys.r0dump.fusion.`
- `private/file_contexts`：helper 二进制与 socket
- `private/seapp_contexts`：`com.android.r0dumpmanager` → `r0dump_manager_app`
- `private/r0dump_manager_app.te`、`r0dump_file_helper.te`（新增）
- `private/compat/32.0/32.0.ignore.cil` + `prebuilts/api/33.0/` 下同名文件（兼容基线）

### 5. `device/meizu/m1721`（补丁 05）

`device.mk` 加入 `R0dumpManager` / `r0dump_file_helper` / `r0dump_mcp_bridge` 三个模块。

### 6. 树内新增：`packages/apps/R0dumpManager`

平台签名 priv-app：Compose UI、配置发布、产物修复与导出、MCP server，
以及 `helper/`（`r0dump_file_helper` init daemon、`r0dump_export_core` 设备端修复、
`r0dump_mcp_bridge` stdio↔socket 中继）。

---

## 集成后验证

```bash
# 1. 编 libart 与 helper
m libart libdexfile r0dump_file_helper r0dump_mcp_bridge R0dumpManager

# 2. 确认符号
nm out/target/product/*/system/apex/com.android.art/lib64/libart.so | grep -c r0dump
# 应有 ~265 个条目（含 14 个 JNI 入口）

# 3. 确认 APEX 链接没退化
readelf -sW out/target/product/*/system/apex/com.android.art/lib64/libart.so | grep r0dump_SetOpenCommonObserver
# 不应是 U

# 4. 刷机后
adb shell dumpsys package com.android.r0dumpmanager | grep versionName
adb shell su -c 'getprop init.svc.r0dump_file_helper'   # running

# 5. 跑一次脱壳（见下）
adb logcat -s R0dumpFusion
```

---

## 使用

### 管理器 UI（推荐）

1. 打开 **R0dump Manager**（priv-app，橙色图标）
2. 「Apps」选目标应用 → 「Capture」确认捕获点 → 「Advanced」按需开 walk / force-invoke
3. **Publish and launch**（自动写配置 + 冷启动目标）
4. 等 5–10 秒（walk + force-invoke 收敛）
5. **Repair and export latest run** → ZIP 落到 `Downloads/r0dump-exports/`
6. **Disable capture**

### adb（便于自动化）

```sh
P=persist.sys.r0dump.fusion
su -c "
setprop $P.generation 26          # 必须偶数！奇数会被判为“发布中”而静默作废
setprop $P.enabled 1
setprop $P.run_id 20260101T000000Z-app-test
setprop $P.target_package com.example.app
setprop $P.target_process com.example.app
setprop $P.capture_point_mask 0x000002800000002d
setprop $P.walk_enabled 1
setprop $P.walk_mode dex_names
setprop $P.force_invoke_enabled 1
am force-stop com.example.app
"
adb shell monkey -p com.example.app -c android.intent.category.LAUNCHER 1
sleep 10
adb shell su -c 'ls -l /sdcard/Android/data/com.example.app/files/r0dump-fusion/runs/'
```

产物落在 `Android/data/<pkg>/files/r0dump-fusion/runs/<run_id>/process-<pid>/`。

**`.raw` 不是成品 DEX**，必须过修复：

```sh
adb pull .../runs/<run>/process-<pid> ./in          # 注意：入参是 process-* 目录
python3 art/tools/r0dump/r0dump_host_repair.py ./in ./out
# out/repaired/*.dexfixed.dex  →  可直接丢 JADX
```

---

## 已知边界

1. **只覆盖标准 dex**；compact dex 标 `compact_dex_not_repaired`，已快速化进 vdex 的镜像
   依赖其在 defineClass / 反射 / 主动调用时出现的明文
2. **`generation` 必须偶数**，否则引擎静默作废（`generation_in_progress`）
3. **输出根只有一条**：`/storage/emulated/<user>/Android/data/<pkg>/files`
4. **`r0dump_host_repair.py` 入参是 `process-<pid>` 目录**，不是 run 目录；传错得到空报告
5. **MCP 默认关闭**，要用先去 Manager 打开
6. 捕获期间 `anr_protection_enabled=1` 会抑制系统杀掉正在被捕获的进程
7. `walk_delay_ms` 默认 5000，给加固壳留解密时间；调太小会抓不到壳解出来的 dex
8. Shell 域下跑 `r0dump_grouptest` 会打印 `walk failed: Bad file descriptor`，
   这是预期的（需要 root）

---

## 与 KernelSU 的关系

本移植的 root 来源是 KernelSU（让你能读写其它 App 私有数据、导出产物），
但**内核改动与 root 无关地独立**：

- 引擎落盘只依赖 helper 建的目录 fd，**不需要 root**
- Manager 是 priv-app，靠 platform 签名 + `MANAGE_R0DUMP_EXPORT` 权限
- root 只在「用 adb 手工配属性」「导出时读别的 App 数据」时更方便

如果需要 root，请自行集成 KernelSU（4.9 内核需用非 GKI 分支 + 兼容层）。

---

## 本仓库**不含**的改动（其他电脑集成时注意）

以下改动在开发树里存在，但**与 r0dump 无关，已刻意排除**，避免污染：

| 项目 | 排除内容 | 说明 |
| --- | --- | --- |
| `kernel/meizu/m1721` | `CONFIG_KSU*`、`drivers/kernelsu/`、`drivers/{Kconfig,Makefile}` | KernelSU root，与脱壳无关 |
| `vendor/kernelsu` | 整个目录 | KernelSU 管理器预编译包 |
| `build/soong` | `java/app_import.go` 的 `preprocessed` 属性 | 只为 KernelSU 管理器保住 v2 签名 |
| `device/meizu/m1721` | `BoardConfig.mk` 的 `VENDOR_SECURITY_PATCH`、`parts/Android.bp` 与两个 `parts/*Activity.java` 的 R 引用 | 与 r0dump 无关的设备树维护 |

**注意 `parts/` 那两处**：A13 上 `com.android.settingslib.collapsingtoolbar.R` 已不存在，
需改用 `com.android.settingslib.widget.R`，并在 `parts/Android.bp` 里加
`SettingsLib` 与 `androidx.palette_palette`。如果你的设备树在编 `LineageParts` 时
报 R 找不到，就是这个原因——自行处理，不属于本移植范围。

`external/chromium-webview` 的 LFS 未落地问题（A13 上 WebView 被判
`SDK version too low` 导致所有含 WebView 的应用崩溃）同样是设备树/仓库维护问题，
不在本仓库内，见提交记录另行处理。

---

## 致谢

- **上游**：[tiwe0/r0dump](https://github.com/tiwe0/r0dump) —— 本 fork 的架构、Fusion
  补丁集、捕获点设计与 ART 集成思路全部来自这里
- 本 fork 的贡献仅限于：Android 13 降级适配、A13 目录准备通道、
  以及上面列出的三个脱壳 bug 修复
