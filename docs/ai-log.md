# AI 改动日志

本仓库由多个 AI 助手接力维护。**任何改动都必须在这里留一条记录**，规则见 [`../AGENTS.md`](../AGENTS.md)。

查某段代码「为什么长这样」，先从这里翻；读的时候按时间倒着找，最新的一条在文件最底下。

## 约定

- **追加式**：新记录加在文件末尾，`===` 分隔。不要插入到中间，不要改别人的条目，减少并行修改的冲突。
- **四要素**：`时间（带时区）`、`模型/工具`、`改了哪里（路径 + 函数/行号）`、`为什么 + 验证到什么程度`。
- **诚实的验证**：跑了什么就写什么命令，没跑就写「未验证」。不写「理论上没问题」。
- **一次提交一条**，多次提交可以合并成一条但列全提交号。

---

## 往期记录（规则生效前）

规则生效之前的历史提交没有逐条记录，需要时用 `git log -p <路径>` 查。从下面第一条开始，所有改动都必须记录。

---

### 2026-11-20 20:05 (UTC+8) · Qoder (6cd6cb10c78d43b7)
- 改了：
  - `AGENTS.md`（新建，全文）
  - `docs/ai-log.md`（新建，全文）
  - `CLAUDE.md`（新建，指向 AGENTS.md 的入口）
  - `.cursorrules`（新建，同上）
  - `.github/copilot-instructions.md`（新建，同上）
  - `README.md`（新增「AI 协作与改动日志」一节，位于「已知限制」之前）
- 变更：确立多 AI 接力协作规则——开工前必须读本日志，收工前必须追加记录（时间 / 时区、模型或工具标识、改动位置到函数与行号、原因与验证程度）。补充「项目底线」一节，写明两端独立实现、发布纪律、测试零跳过、安全边界不得放宽。入口文件做成三个指向 `AGENTS.md` 的短文件，覆盖不同 AI 工具默认读取的路径。
- 验证：仅静态检查——`AGENTS.md` 内链接指向的 `docs/ai-log.md`、`VERSIONING.md` 在仓库中均存在；未运行构建与测试（纯文档改动，按 `VERSIONING.md` 属「不发版」类，产物不变）。
- 未做：没有加 CI 校验（例如「diff 触及代码但未改 `docs/ai-log.md` 则失败」）。如果实际使用中发现漏记频繁，下次再加。
- 提交：与本条目同一次提交（规则与日志一起落盘）

---

### 2026-11-20 20:47 (UTC+8) · Qoder (6cd6cb10c78d43b7)
- 改了：
  - `app/src/main/java/com/dlguard/plugin/PluginManifest.kt`（companion，新增 `isValidId()` 与 `ID_PATTERN`）
  - `app/src/main/java/com/dlguard/plugin/PluginManager.kt`（`installFromBytes()` 约 770–785 行、`reloadInstalled()` 过滤链、`createFromJson()` 约 898–903 行、`downloadPolicy()`、builtins 新增 `ID_NAME_TEMPLATE` 条目）
  - `app/src/main/java/com/dlguard/download/DownloadCenter.kt`（`runTask()` 策略合并，约 280–288 行）
  - `app/src/main/java/com/dlguard/download/FileNameTemplate.kt`（`apply()` 结尾处理）
  - `app/src/test/java/com/dlguard/plugin/PluginIdValidationTest.kt`（新建）
  - `app/src/test/java/com/dlguard/download/FileNameTemplateTest.kt`（新建）
  - `README.md`（插件列表 15 → 16、schema 类型名更正）
  - `VERSIONING.md`（补 3.4.3 行）
- 变更：
  1. **安全修复**：`installFromBytes()` 解析出 manifest 后只检查 id 非空，没检查形态，而 id 会拼成 `files/plugins/<id>/` 当目录名并先 `deleteRecursively()`。恶意 zip 把 id 写成 `../../databases` 即可删改应用私有目录任意文件；`createFromJson()` 反倒校验了，属于两条安装路径不一致。现统一走 `PluginManifest.isValidId()`，`reloadInstalled()` 也跳过历史遗留的非法目录。
  2. **新插件「文件名模板」**（`official.name-template`）：把 `{name}/{ext}/{host}/{date}/{rule}` 渲染成文件名，接到既有 `DownloadPolicy.fileNameTemplate`，`ResumableDownloader.resolveFileName()` 本来就会消费该字段，无新增执行路径。
  3. **修复**：站点规则未配置 `fileNameTemplate` 时（默认空串）会把全局/插件模板覆盖成空，改为 `ifBlank { basePolicy.fileNameTemplate }`。
  4. **修复**：原名无扩展名时模板 `{name}.{ext}` 会留下结尾的点，`apply()` 现去掉。
  5. 文档：README 插件数与 schema 类型名（`SWITCH` 实为 `toggle`）更正；VERSIONING 补 3.4.3。
- 验证：桌面版与本改动无关，仍为 `./build.sh` 通过、`--selftest` 66/0/0、`e2e.py` 100/0。Android 端**未在本地编译**（沙箱无 Android SDK），Kotlin 改动依赖 CI 的单元测试与 Debug 编译验证；新增 2 个测试类覆盖 id 校验与模板边界。
- 未做：next release 建议按 `VERSIONING.md` 升 `3.5.0`（新增一个用户可感知插件），本次未改 `versionName`，留给发版时决定。第三方插件 id 格式文档（除 `docs/site-rules.md` 之外）尚无专门章节。
- 提交：见下一次 push 的 commit
