# Alarm-Push Test Delivery Package Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a self-contained Alarm-Push tester delivery folder containing the approved documents, Postman import files, and test SQL resources, then update the existing PR #1.

**Architecture:** Keep all canonical source documents and Maven test resources in their current locations. Create a versioned snapshot under `doc/Alarm-Push测试交付包/`, add one entry README and one Postman import guide, and verify every copied asset against its source before committing only the package directory.

**Tech Stack:** Markdown, Postman Collection v2.1 JSON, MySQL 8 SQL, PowerShell, Git, GitHub REST API.

## Global Constraints

- Work only on branch `codex/alarm-push-test-docs` in `D:/studyProject/hpis2.0/hpis/hpis-alarm`.
- Preserve every unrelated dirty worktree change.
- Do not stage `doc/Alarm-Push运行配置与数据库同步说明-20260715.md`; copy its current working-tree content into the package.
- Do not move or delete canonical documents or Maven resources.
- Do not copy production migration SQL from `src/main/resources/sql/`; the package includes only the three approved test SQL files.
- Do not add real tokens, passwords, corporate secrets, tenant production IDs, or database credentials.
- State that the Postman collection covers HTTP alarm `messageType=10` and workorder `messageType=25`, but not WeCom configuration and recipient routing.
- Update the existing open PR `https://github.com/644362807/hpis-alarm/pull/1`; do not create a second PR.

---

### Task 1: Create the package structure and entry documentation

**Files:**
- Create: `doc/Alarm-Push测试交付包/README.md`
- Create: `doc/Alarm-Push测试交付包/docs/04-Postman导入与执行说明.md`

**Interfaces:**
- Consumes: approved design in `docs/superpowers/specs/2026-07-18-alarm-push-test-delivery-package-design.md`.
- Produces: a single tester entry point and a Postman import/run guide used by all copied resources.

- [ ] **Step 1: Create package directories**

Run:

```powershell
New-Item -ItemType Directory -Force -Path `
  'doc/Alarm-Push测试交付包/docs', `
  'doc/Alarm-Push测试交付包/postman', `
  'doc/Alarm-Push测试交付包/sql' | Out-Null
```

Expected: all three subdirectories exist and no existing file is removed.

- [ ] **Step 2: Add the package README with the exact execution contract**

Create `README.md` containing these sections and facts:

```markdown
# Alarm-Push 测试交付包

## 1. 适用人员

本文档包面向不了解 Alarm、Push 内部实现的接口测试人员。

## 2. 推荐执行顺序

1. 阅读运行配置与 SQL 同步说明。
2. 执行测试环境只读预检 SQL。
3. 导入 Postman Collection 和 Environment。
4. 按全流程测试手册执行配置、上报、查看、处理、工单和推送测试。
5. 使用 API 文档核对字段和输入输出。
6. 执行只读证据 SQL。
7. 先通过 API 删除配置，再按授权执行运行数据清理 SQL。

## 3. 目录说明

列出 docs、postman、sql 下每一个文件的职责。

## 4. 自动化覆盖边界

明确 Postman 覆盖 HTTP 报警 10 和工单 25，不覆盖企业微信；企业微信必须按全流程手册人工验证。

## 5. SQL 安全边界

明确 setup/check 只读，cleanup 包含删除，生产迁移 SQL 不在本包内。

## 6. 通过标准

明确报警记录、最终通道、收件人、数据库证据和清理结果都必须满足手册判定。
```

Expected: a zero-context tester can identify the first file to read, the next command category, and the cleanup risk without opening source directories.

- [ ] **Step 3: Add the Postman import and execution guide**

The guide must include:

```markdown
# Postman 导入与执行说明

## 导入顺序

1. Import `../postman/hpis-alarm-push.postman_collection.json`.
2. Import `../postman/hpis-alarm-push.postman_environment.json`.
3. Select environment `HPIS Alarm Push - Fresh Environment`.

## 必填变量

Explain `alarmBaseUrl`, `pushBaseUrl`, receiver URLs, `token`, `tenantId`, `userId`, three device IDs/SNs, gateway SNs, `workorderConfigId`, and `runId`.

## 执行前检查

Require services, receiver endpoints, current-tenant devices, workorder template, permissions, and `sql/alarm-push-api-setup.sql`.

## Collection 执行顺序

Run folders 00 through 06 in order and explain when IDs are captured automatically and when `internalAlarmId` must be filled from an Alarm query or `sql/alarm-push-api-check.sql`.

## 结果判定

Separate HTTP 2xx from business success and require Alarm record and final receiver evidence.

## 企业微信边界

State that WeCom application, bindings, groups, ordinary-alarm recipients, workorder assignee, and transfer recipient are manual tests from the full-flow guide.
```

Expected: the guide names every non-empty environment variable source and prevents treating a completed Collection run as WeCom PASS.

- [ ] **Step 4: Run the first documentation checks**

Run:

```powershell
rg -n 'TODO|TBD|待补充|稍后填写|Bearer\s+[A-Za-z0-9_-]{30,}' `
  'doc/Alarm-Push测试交付包/README.md' `
  'doc/Alarm-Push测试交付包/docs/04-Postman导入与执行说明.md'
git diff --check -- 'doc/Alarm-Push测试交付包'
```

Expected: `rg` returns no findings and `git diff --check` exits 0.

### Task 2: Copy the approved canonical documents and executable resources

**Files:**
- Create: `doc/Alarm-Push测试交付包/docs/01-全流程测试使用手册.md`
- Create: `doc/Alarm-Push测试交付包/docs/02-API接口文档.md`
- Create: `doc/Alarm-Push测试交付包/docs/03-运行配置与SQL同步说明.md`
- Create: `doc/Alarm-Push测试交付包/postman/hpis-alarm-push.postman_collection.json`
- Create: `doc/Alarm-Push测试交付包/postman/hpis-alarm-push.postman_environment.json`
- Create: `doc/Alarm-Push测试交付包/sql/alarm-push-api-setup.sql`
- Create: `doc/Alarm-Push测试交付包/sql/alarm-push-api-check.sql`
- Create: `doc/Alarm-Push测试交付包/sql/alarm-push-api-cleanup.sql`

**Interfaces:**
- Consumes: current canonical files under `doc/` and `src/test/resources/`.
- Produces: byte-identical snapshot files consumed by the package README and import guide.

- [ ] **Step 1: Copy the three documents**

Run PowerShell `Copy-Item -LiteralPath` for these exact mappings:

```text
doc/Alarm-Push-全流程测试使用手册.md
  -> doc/Alarm-Push测试交付包/docs/01-全流程测试使用手册.md
doc/Alarm-Push-API接口文档.md
  -> doc/Alarm-Push测试交付包/docs/02-API接口文档.md
doc/Alarm-Push运行配置与数据库同步说明-20260715.md
  -> doc/Alarm-Push测试交付包/docs/03-运行配置与SQL同步说明.md
```

Expected: source files remain present and unchanged; destination files exist.

- [ ] **Step 2: Copy Postman JSON files**

Copy both files from `src/test/resources/postman/` into the package `postman/` directory without modifying their values.

Expected: both destination JSON files parse with `ConvertFrom-Json`.

- [ ] **Step 3: Copy the three test SQL files**

Copy `alarm-push-api-setup.sql`, `alarm-push-api-check.sql`, and `alarm-push-api-cleanup.sql` from `src/test/resources/sql/` into package `sql/`.

Expected: no file from `src/main/resources/sql/` is copied.

- [ ] **Step 4: Verify byte identity**

Use `Get-FileHash -Algorithm SHA256` for every source/destination mapping and fail if any pair differs.

Expected: all eight copied source/destination pairs have identical SHA256 values.

### Task 3: Validate the complete tester package

**Files:**
- Verify: `doc/Alarm-Push测试交付包/**`

**Interfaces:**
- Consumes: all files produced by Tasks 1 and 2.
- Produces: reproducible validation evidence for the commit and PR.

- [ ] **Step 1: Parse Postman JSON and inventory requests**

Parse both JSON files with `ConvertFrom-Json`, recursively count Collection requests, and print each HTTP method and raw URL.

Expected: JSON parsing succeeds and the Collection contains 34 requests.

- [ ] **Step 2: Validate Postman variable sources**

Extract every `{{name}}` occurrence from Collection JSON. Verify each name is present in Environment values or in this allowed runtime-capture set:

```text
emergencyAlarmConfigureId
normalAlarmConfigureId
pushConfig10Id
pushConfig25Id
internalAlarmId
```

Expected: no unresolved variable remains.

- [ ] **Step 3: Validate Markdown links and safety text**

Resolve every relative Markdown link in `README.md` and the Postman guide against its containing directory. Check that cleanup warnings, WeCom boundary, HTTP 2xx caveat, and variable source explanations are present.

Expected: every link target exists and all required safety statements are found.

- [ ] **Step 4: Parse PowerShell and JSON examples in copied documents**

Reuse the existing fenced-block validation: parse JSON blocks using `ConvertFrom-Json` and PowerShell blocks using `System.Management.Automation.Language.Parser`.

Expected: 24 API JSON blocks and 31 full-flow PowerShell blocks parse with zero errors.

- [ ] **Step 5: Check Git scope**

Run:

```powershell
git diff --check -- 'doc/Alarm-Push测试交付包'
git status --short -- 'doc/Alarm-Push测试交付包' `
  'doc/Alarm-Push运行配置与数据库同步说明-20260715.md'
```

Expected: the package is untracked/modified as intended; the source sync document remains modified but unstaged.

### Task 4: Commit, push, and update PR #1

**Files:**
- Commit: `doc/Alarm-Push测试交付包/**`
- Preserve unstaged: every other dirty worktree file.

**Interfaces:**
- Consumes: validated package and current branch.
- Produces: one package commit pushed to the existing PR.

- [ ] **Step 1: Stage only the package**

Run:

```powershell
git add -- 'doc/Alarm-Push测试交付包'
git diff --cached --name-status
```

Expected: every staged path starts with `doc/Alarm-Push测试交付包/`.

- [ ] **Step 2: Re-run staged checks and commit**

Run `git diff --cached --check`, the JSON parsers, variable validator, link validator, and hash validator again.

Expected: all checks pass immediately before commit.

Commit:

```powershell
git commit -m "docs: add alarm push tester delivery package"
```

- [ ] **Step 3: Push the branch**

Run:

```powershell
git push origin codex/alarm-push-test-docs
```

Expected: remote branch advances to the new local HEAD.

- [ ] **Step 4: Update and verify PR #1**

Use GitHub REST API with the existing Git credential to append these facts to PR #1:

- tester package entry path;
- Postman covers HTTP alarm 10 and workorder 25;
- WeCom remains a manual flow from the full guide;
- package JSON, links, variables, hashes, and fenced examples passed validation.

Then GET PR #1 and verify `state=open`, `base=main`, `head=codex/alarm-push-test-docs`, and `head.sha` equals local HEAD.

Expected: the existing PR URL remains `https://github.com/644362807/hpis-alarm/pull/1` and no new PR is created.
