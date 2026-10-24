# 下载引擎维护指南（2026-10 变更）

> 适用版本：1.2.9（2026-10-21 构建）
> 本文覆盖本轮四项改动：Issue #17 缓存/磁盘保护、Issue #12 夸克/UC 分页、任务级保存目录、实验性直写。
> 目标：以后改下载逻辑或排查问题时，能快速定位「数据从哪来、写到哪、失败后留下什么」。

---

## 1. 一条下载任务的生命周期

```text
UI（网盘页/解析页/手动添加）
  └─ DownloadManager.enqueue(url, fileName, headers, size, platform, saveDir, ...)
       ├─ 写入 download_task（含任务级 saveDir）
       ├─ start(id) → runTaskWithRetry → runTask
       │    ├─ HLS（.m3u8/.m3u）→ hlsDownload / hlsDownloadDirect
       │    ├─ 探测总大小 getTotalSize()
       │    ├─ 选择模式（直写 / 缓存分片）
       │    ├─ 空间守卫
       │    ├─ 下载（直写 .part 或缓存 part_N/seg_*）
       │    └─ 提交（改名 / 合并）→ completeWithAvg()
       └─ 完成回调（清理网盘临时转存）→ 切下载页
```

**默认路径没有改变**：缓存分片 + 合并。只有在「设置 → 实验性功能」里打开直写开关，才会走新的 `.yunx-*.part` 路径。

---

## 2. 关键文件与职责

| 文件 | 职责 |
| --- | --- |
| `data/download/DownloadManager.kt` | 任务生命周期、模式选择、空间守卫、直写编排、合并、暂停/恢复/删除 |
| `data/download/ChunkDownloader.kt` | HTTP Range 分片、重试、慢连接抢占、`downloadChunk` / `downloadFull`；本轮新增 `downloadRangeToChannel`（多线程直写按偏移写） |
| `data/download/DownloadSaver.kt` | 最终保存路径、同名防重、直写 `.part` 的创建/提交/删除 |
| `data/download/DirectPartManifest.kt` | 多线程直写的 `.plan` / `.done` 清单、区间 SHA-256 |
| `data/download/DownloadCacheMaintenance.kt` | 缓存占用统计、孤儿分片/直写临时文件清理、磁盘空间检查 |
| `data/db/Entities.kt` / `AppDatabase.kt` / `Daos.kt` | `download_task` 新增 `saveDir` / `directPart` / `directMode` 及迁移 |
| `data/prefs/SettingsRepository.kt` | 直写开关持久化（`direct_write_single_thread` / `direct_write_multi_thread`） |
| `ui/screens/ExperimentalFeaturesScreen.kt` | 两个实验开关的 UI、说明、重置 |
| `ui/MainScreen.kt` | 把设置里的开关注入 `DownloadManager` |
| `ui/components/DownloadDirPicker.kt` | 通用「下载到…」目录选择器 |
| 各 `*CloudViewModel.kt` / `*CloudScreen.kt` | 单文件/文件夹/批量的「下载到…」入口，把 `saveDir` 传给 `enqueue` |
| `ui/viewmodel/ResolveViewModel.kt` / `ui/screens/ResolveScreen.kt` / `ui/resolve/ShareDetailScreen.kt` | 分享页单文件/批量/文件夹的直写目标选择 |
| `ui/screens/DownloadScreen.kt` | 手动添加下载的保存目录；直写校验阶段的「校验中」文案 |

---

## 3. 任务级保存目录

### 数据字段

`DownloadTaskEntity`：

- `saveDir: String`：任务级保存目录；空串 = 跟随「设置 → 储存目录」的全局下载目录。
- `directPart: String`：实验性直写临时文件绝对路径；空串 = 非直写。
- `directMode: Int`：0 = 非直写，1 = 单线程直写，2 = 多线程直写。

迁移：

- 旧库启动时 `ALTER TABLE download_task ADD COLUMN saveDir/directPart/directMode ...`，见 `AppDatabase.open()`。
- `readTask` 对旧库列做了 `runCatching` 兜底。
- 新增/修改库字段时，**必须同步**：DDL、迁移、`readTask`、`insert` 列和占位符、DAO。

### 生效规则

`DownloadManager.effectiveSaveDir(task)`：

1. `task.saveDir` 非空 → 用它；
2. 否则 `saveDirProvider()`（全局设置）。

`DownloadSaver.openDestination(fileName, saveDir)` / `save(...)` 负责最终落盘；`fileName` 可以是相对路径（文件夹下载保持目录结构）。

### UI 入口

- 10 个网盘页：文件操作菜单「下载到…」、文件夹「下载文件夹到…」、批量栏「下载到…」。
- 解析页：下载确认弹窗「选择/更改保存目录」；批量栏「下载到…」。
- 下载页「添加下载任务」：保存目录行 +「选择目录」。

### 常见问题

| 现象 | 排查 |
| --- | --- |
| 任务仍保存到全局目录 | 检查调用方是否真的传了 `saveDir`；`PendingDownload.saveDir` / `enqueue(saveDir=...)` |
| 暂停后换全局设置，任务跑错盘 | 带任务级 `saveDir` 的任务不受全局变更影响；空串任务才会跟随 |
| 重新下载丢目录 | `redownload()` 会 `saveDir = task.saveDir`，检查该调用是否被改掉 |
| Gopeed 引擎任务目录不对 | `startViaEngine(..., saveDirOverride)`、`engineBaseDir(override)`、`completeEngineTask()` 的 `expectedDest` 逻辑 |

---

## 4. 缓存与磁盘空间（Issue #17）

### 目录

- 缓存根：`AppContext.cacheDir`（默认 `<数据目录>/cache`，可在设置里改）。
- 下载分片：`AppContext.downloadTmpDir = <cache>/download_tmp`。
- 实际任务目录：`<cache>/download_tmp/download_tmp/<id>`（历史遗留的双层 `download_tmp`；`DownloadCacheMaintenance.chunkDirs()` 同时兼容单层和双层）。
- 合并暂存：`AppContext.mergeDir`（HLS 旧路径的 `hls_<id>`）。

### 峰值磁盘

- **缓存/成品不同盘**：缓存 1 份分片，成品 1 份；合并阶段两份在不同盘。
- **缓存/成品同盘**：合并阶段分片 + 成品同时存在 → **2 倍文件大小**。
- 直写模式：缓存 0，目标盘 1 份（无合并副本）。

### 空间守卫

| 函数 | 场景 | 规则 |
| --- | --- | --- |
| `checkBeforeDownload` | 缓存分片开始前 | 同盘按 2×total - 已下分片；异盘缓存按 remaining、成品按 total；含其它任务的缓存预留 |
| `checkBeforeMerge` | 合并前 | 目标盘 free ≥ total + 余量 + 其它正在合并任务的预留 |
| `checkBeforeDirectDownload` | 直写开始前 | **只查目标盘**，free ≥ total + 余量；不占缓存 |

- `plannedCacheBytes`（`DownloadManager`）：缓存模式任务的并发预留，避免 3 个任务同时把缓存盘写爆。
- `mergingDestBytes`：并发合并的目标盘预留。
- **空间暂时被其它缓存任务占用**：任务进入 PENDING「等待中」，等空间释放后继续。
- **单任务本身放不下**：直接失败并给出原因（`LowDiskSpaceException`，不自动重试）。
- **真 ENOSPC**：删除该任务分片释放空间，错误信息会说明。
- 启动清理：`DownloadCacheMaintenance.cleanAfterStartup()` 清已完成/已删除任务的分片、合并残留、已完成任务的直写临时文件；缓存盘 <2GB 时额外清失败任务分片。

### 手动清理

设置 → 储存目录 →「清理缓存」：

- 删除暂停/失败任务的分片；
- 删除非进行中任务的直写 `.part/.plan/.done`；
- 保留正在下载/等待中的任务；
- 删除后任务下次从 0 开始。

### 常见问题

| 现象 | 排查/处理 |
| --- | --- |
| C 盘仍被占满 | 看 `cacheDir`、`usage()`、任务状态；用「清理缓存」；检查是否有大量失败/暂停任务 |
| 任务一直「等待中」 | 被 `plannedCacheBytes` 预留挡住；看日志 `等待磁盘空间` |
| 下载到 100% 卡住 | 可能在做合并/校验；大文件合并耗时正常；看 `mergePercent` / `verifying` |
| 提示「缓存目录磁盘空间不足」 | 缓存盘 free < 2×total（同盘）或 < total（异盘）；换缓存目录或清理 |
| 提示「下载目录磁盘空间不足」 | 成品盘放不下；直写模式只要求 1×total |

---

## 5. 实验性直写（两个开关）

### 开关

设置 → 实验性功能：

- **单线程直写（实验性）**：默认关；线程数 = 1 时生效。
- **多线程直写（实验性）**：默认关；线程数 > 1 时生效。
- 两个都开：1 线程走单线程直写，>1 走多线程直写。
- 只开一个：另一种线程数仍走缓存分片。
- **只影响新任务**；已开始的任务按 DB 里的 `directMode` 恢复。
- **仅内置下载器**；Gopeed 引擎任务本身直接落盘，不受影响。
- HLS（`.m3u8`/`.m3u`）在单线程直写开启时也走直写（HLS 无断点续传，失败/取消会删除临时文件）。

### 模式选择

`runTask()` 中：

```text
forceCacheMode[id] == true        → 缓存分片（直写回退后）
task.directMode 1/2 且 .part 存在 → 按记录模式恢复
单线程开关 && 线程数==1           → 单线程直写
多线程开关 && 线程数>1 && total>0 → 多线程直写
其余                              → 缓存分片
```

### 文件布局

在任务保存目录（含文件夹下载的相对子目录）中：

```text
<最终文件名>.yunx-<任务id>.part        # 临时目标文件
<最终文件名>.yunx-<任务id>.part.plan   # 多线程：total / chunks / chunkSize
<最终文件名>.yunx-<任务id>.part.done   # 多线程：每行 "<分片序号> <sha256>"
```

- 任务 id 保证同名任务不会共用临时文件。
- 路径超过 235 字符 / 无法建目录 → 回退缓存分片（`DirectWriteFallbackException`）。
- 完成后：`.part` 原子改名成最终文件（同名自动加时间戳），`.plan/.done` 删除。
- 暂停/失败：保留三者以便续传。
- 删除任务 / 重新下载 / 启动清理 / 手动清理：删除三者。

### 单线程直写

`runDirectSingle()`：

1. 打开/复用 `.part`；`total` 已知时长度 == total 直接提交。
2. `downloadChunk(start=0, end=total-1 或 LONG_MAX, partFile=.part)`，按文件长度续传。
3. 服务器忽略 Range（200）→ `downloadFull()` 截断后从头写。
4. 校验 `part.length() == total`，`DownloadSaver.commitDirectPart()` 改名。
5. 失败保留 `.part`；无 `.done` 分片校验，只有大小校验。

### 多线程直写

`runDirectMulti()`：

1. **计划**：优先复用已有 `.plan`（total 一致）；否则 `chunkCountFor(total, threadCount)` 生成固定分片，`RandomAccessFile.setLength(total)` 预分配，写 `.plan`。
2. **恢复**：读 `.done`；对每个已完成分片重读区间算 SHA-256，与清单比对；不一致/缺失的分片从起点重下（覆盖写）。
3. **并发写**：`FileChannel.open(part, READ, WRITE)`，每个 worker 用 `downloadRangeToChannel` 写 `[start,end]`，`FileChannel.write(buf, position)` 线程安全；区间不重叠。
4. **进度**：每片下载时算 SHA-256，成功后追加 `.done`；DB `downloadedSize` 按已验证 + 新增字节更新。
5. **提交**：所有分片完成后，逐片重读校验；再校验总大小；`commitDirectPart()` 改名。
6. **取消**：worker/校验循环都检查 `isTaskActive()`；暂停/删除会取消网络 Call。

### 回退

- 多线程直写遇到任意分片返回 `RANGE_IGNORED`（服务器忽略 Range）：
  - 单线程直写开关也开 → 删除多线程临时文件，改 `runDirectSingle()`（完整 GET）。
  - 否则 → `forceCacheMode.add(id)`，删除直写临时文件、清 DB `directPart/directMode`，抛 `DirectWriteFallbackException`；`runTaskWithRetry` 不消耗重试次数，下一轮走缓存分片。
- 直写临时文件创建失败（路径过长/目录不可写）→ 同样回退缓存。

### 并发/限速

- 多线程直写的每个请求都走全进程 `inflightLimiter`（与缓存分片共享在飞上限）。
- `speedLimiter` 仍生效。
- 迅雷 CDN 单文件 Range 并发上限 8（`RANGE_WORKERS_CAP`），直写同样钳制。
- `MAX_INFLIGHT_CHUNKS` 仍限制单任务 worker 数。

### 已知限制

- 多线程直写提交前要**重读整个文件**逐片校验；大文件会多一次全量读（不额外写）。
- 单线程直写只有大小校验，没有逐片 SHA-256。
- HLS 直写不支持断点续传；失败/取消会删除 `.part`。
- 直写临时文件会出现在用户保存目录（文件名带 `.yunx-<id>.part`）。
- 目标盘是移动硬盘/网络盘时，断开后该任务无法续传。
- 多个直写任务同时开始时，目标盘空间预留依赖预分配/OS；极端并发下可能有一个任务在写入时遇到 ENOSPC 并失败（会清理自己的临时文件）。
- Gopeed 引擎任务不走这套逻辑。

### 常见问题

| 现象 | 说明/处理 |
| --- | --- |
| 保存目录出现 `.yunx-*.part/.plan/.done` | 任务正在下载/暂停/失败，属于正常续传文件；完成后自动改名/删除 |
| `.part` 大小等于完整文件 | 多线程直写预分配，大小不代表已下载量；看任务卡片进度 |
| 想取消但保留进度 | 在应用里「暂停」，不要手动删 `.done/.plan` |
| 已完成但临时文件还在 | 异常；删除任务或「清理缓存」；看日志 `直写完成` / `commitDirectPart` |
| 日志出现「直写回退缓存模式」 | 服务器不支持 Range；任务已自动改走缓存，无需处理 |
| 卡在「校验中」 | 多线程直写在提交前逐片重读；大文件耗时正常；看磁盘是否被占用 |
| 直写任务续传后从 0 开始 | `.plan` 不匹配（total 变了/计划损坏）、`.done` 缺失、或服务器忽略 Range；看日志 |
| 想彻底不用直写 | 关闭两个实验开关；新任务回缓存；已有直写任务仍按记录模式完成或删除 |

---

## 6. 夸克 / UC 分页（Issue #12）

- 云盘列表接口单页上限 50，旧代码只取一页 → 只显示/只下载前 50 项。
- 新方法：
  - `QuarkApi.listAllCloudFiles()` / `UCApi.listAllCloudFiles()`：按页循环、按 fid 去重、整页无新增即停止（防止服务端忽略页码导致死循环）。
  - 云盘页的 `load / refresh / moveLoad / collectFolderFiles` 全部改用 `listAllCloudFiles`。
  - 分享页 `QuarkResolveRepository.listFiles` / `UCResolveRepository.listFiles` 改为按 50 条翻页 + 去重，不再用「本页是否满 100」判断结束。
- 排查「只显示 50 项」：确认调用的是 `listAllCloudFiles`，不是 `listCloudFiles`；检查服务端是否返回了重复页（去重会提前停止）。

---

## 7. 数据库变更

`download_task` 新增：

```sql
saveDir     TEXT NOT NULL DEFAULT ''
directPart  TEXT NOT NULL DEFAULT ''
directMode  INTEGER NOT NULL DEFAULT 0
```

- 新库：`AppDatabase.DDL` 已包含。
- 旧库：`AppDatabase.open()` 里三处 `ALTER TABLE ... ADD COLUMN`（`runCatching`，列已存在时忽略）。
- 读写：`JdbcDownloadTaskDao.readTask` / `insert` / `updateDirect`。
- 删除任务、重新下载、完成提交都会清理/清空 `directPart`；启动清理会处理已完成/文件丢失的记录。

**改表检查清单**：DDL → 迁移 → `readTask` → `insert` 列/占位符/绑定 → 需要的 DAO update → 旧库启动验证。

---

## 8. 构建、测试、打包

### 编译

```powershell
.\run.ps1 build        # 只编译 Kotlin（不需要 C++ 编译器）
.\run.ps1 run          # 编译并启动（需要 darkmode.dll）
```

或直接：

```bash
JAVA_HOME=<jdk17> java -jar gradle/wrapper/gradle-wrapper.jar :desktop:compileKotlin
```

### 测试

仓库的 Gradle `test` 任务存在**历史遗留的测试运行时 classpath 问题**（`ClassNotFoundException`），所以本轮的测试是用 JUnitCore 直接跑的：

1. 取测试运行时 classpath（临时 init 脚本）：

```kotlin
// print-test-cp.gradle.kts
allprojects {
    tasks.register("printTestCp") {
        doLast { println("TEST_RUNTIME_CP=" + configurations["testRuntimeClasspath"].asPath) }
    }
}
```

```bash
JAVA_HOME=<jdk17> java -jar gradle/wrapper/gradle-wrapper.jar \
  -I print-test-cp.gradle.kts :desktop:printTestCp -q
```

2. 运行：

```bash
YUNX_DESKTOP_DATA_DIR=<临时目录> \
java -cp "desktop/build/classes/kotlin/test;desktop/build/classes/kotlin/main;desktop/build/resources/main;<TEST_RUNTIME_CP>" \
  org.junit.runner.JUnitCore \
  com.yunx.app.data.download.DownloadManagerDirectIntegrationTest \
  com.yunx.app.data.download.ChunkDownloaderDirectTest \
  ...
```

- 集成测试会走真实 `DownloadManager`；测试内已设置 `WindowsToastNotifier.enabled = false`，不会弹系统通知。
- 直写集成测试覆盖：单线程、多线程、单线程续传、多线程 `.plan/.done` 续传、Range 忽略→单线程回退、Range 忽略→缓存回退。
- 当前完整套件：**51 个测试通过**。

### 打包

```powershell
.\run.ps1 installer     # 便携版 + 单文件安装程序
.\run.ps1 package       # 仅便携版
```

**PATH 坑**：从 Git Bash 启动打包脚本时，Git 自带的 `tar` 会抢在 Windows bsdtar 前，JCEF 语言包裁剪会报 `tar (child): Cannot connect to F: resolve failed`。解决：把 `C:\Windows\System32` 放到 PATH 最前面再运行：

```powershell
$env:PATH="C:\Windows\System32;"+$env:PATH
& "F:\...\YunX-Desktop\run.ps1" installer
```

产物：

- 便携版：`release\YunX-Desktop\YunX-Desktop.exe`
- 安装包：`release\YunX-Desktop-setup-<version>.exe`
- 版本号唯一来源：根目录 `version.txt`（当前 1.2.9）。
- 安装包未做代码签名，首次运行可能触发 SmartScreen。

---

## 9. 快速排障索引

| 症状 | 优先看 |
| --- | --- |
| 只显示/只下载 50 项 | `listAllCloudFiles` 是否被调用；分页去重逻辑 |
| C 盘被下载占满 | `cacheDir`、`DownloadCacheMaintenance.usage()`、失败/暂停任务、清理缓存 |
| 任务一直等待 | `plannedCacheBytes` 预留；日志 `等待磁盘空间` |
| 目标目录有 `.yunx-*` | 直写任务未完成/暂停/失败；完成后应改名/删除 |
| 直写任务失败 | 日志 `空间不足`、`直写回退缓存`、`直写文件校验失败`、`DirectWriteFallbackException` |
| 续传从 0 开始 | `.plan/.done` 是否存在、total 是否变化、服务器是否支持 Range |
| 任务保存到错误目录 | `task.saveDir`、`effectiveSaveDir()`、调用方是否传 `saveDir` |
| 完成但临时文件残留 | `commitDirectPart()` 是否执行、`directPart` 是否清空；删除任务/清理缓存 |
| Gopeed 下载整个文件夹，完成后变成一个个单文件卡片 | 旧版 `completeEngineTask` 用纯文件名覆盖了相对路径；现已保留相对目录，启动时会从 `savePath` 反推修复旧任务 |
| 同一个文件夹下到两个本地位置，下载页合成一个组、展开后每个文件两条 | 分组键已改为「保存根目录 + 顶级文件夹名」（`DownloadGrouping`）；组头会显示「保存到：…」区分 |
| 已完成任务显示「已下载 < 总大小」 | `download_task.complete` 会补齐；旧任务启动时 `repairCompletedProgress()` 批量修复（total=0 时保留实际值） |
| 测试弹系统通知 | 集成测试是否设置了 `WindowsToastNotifier.enabled=false` |
| 开发模式留下窗口 | 结束 `java ... com.yunx.app.MainKt` 进程 |

---

## 10. 本轮新增文件

- `data/download/DownloadCacheMaintenance.kt`
- `data/download/DirectPartManifest.kt`
- `ui/components/DownloadDirPicker.kt`
- `test/.../DownloadCacheMaintenanceTest.kt`
- `test/.../DownloadSaverTest.kt`
- `test/.../DirectPartManifestTest.kt`
- `test/.../ChunkDownloaderDirectTest.kt`
- `test/.../DownloadManagerDirectIntegrationTest.kt`

主要修改文件：`DownloadManager.kt`、`ChunkDownloader.kt`、`DownloadSaver.kt`、`AppDatabase.kt`、`Entities.kt`、`Daos.kt`、`SettingsRepository.kt`、`ExperimentalFeaturesScreen.kt`、`MainScreen.kt`、`DownloadScreen.kt`、`StorageDirectoryScreen.kt`、`ResolveViewModel.kt`、`ResolveScreen.kt`、`ShareDetailScreen.kt`、10 个云盘 ViewModel/Screen、`QuarkApi.kt`、`UCApi.kt`、`QuarkResolveRepository.kt`、`UCResolveRepository.kt`、`WindowsToastNotifier.kt`、`docs/FEATURES.md`。

---

## 11. 本轮验证记录

- `:desktop:compileKotlin` / `:desktop:compileTestKotlin` 通过。
- 完整测试套件 51 个测试通过（含 6 个直写端到端测试、2 个 Gopeed 文件夹显示测试、3 个文件夹分组键测试）。
- 旧 schema 数据库启动后自动补 `saveDir` / `directPart` / `directMode` 列，应用正常打开。
- 安装包已实际启动验证（数据库、暗色模式、JCEF 正常）。
- 当前安装包：`release\YunX-Desktop-setup-1.2.9.exe`，约 202 MB。
