---
name: xcodemap-observe
description: Use this skill whenever real runtime execution evidence is relevant. 
  This includes when the user asks what actually executed (e.g., whether a function ran, a branch was hit, or what backend activity occurred), 
  as well as during debugging, root cause analysis, or any investigation involving program behavior—even if the user does not explicitly request observation. 
  If the task involves uncertainty about execution, prefer using this skill to ground conclusions in recorded runtime data. 
---

# 1. When to use

- Did this function run?
- Was this branch hit?
- What backend activity was observed for this action?

---

# 2. Rules

## Data Availability

- If runtime data is available:
  - It represents a complete recorded execution of the user's run
  - Including the user actions and bug reproduction process

- Do NOT assume:
  - the bug did not occur
  - the user action did not happen
  - the recording missed relevant execution

- Missing expected observations should be interpreted as:
  - Strong Negative (if within scope)

- Runtime data MUST be treated as:
  > the ground truth of actual execution

## Scope & Evidence

- In scope + hit → **Strong Positive**
- In scope + no hit → **Strong Negative**
- Out of scope / no data → **Unknown**

## Shell Notes
- 所有命令使用单行，避免换行在不同终端中的兼容性问题
- windows下面 cmd 和 powershell 需要注意转义问题

## Prohibited

- Do NOT explain why
- Do NOT construct causal chains
- Do NOT infer missing execution

---

# 3. How to use

3.1 先阅读说明书 `XCODEMAP_RUNTIMEDATA_API.md`, 调用 `checkRecordDataAndScope`，确认当前有可用录制数据与范围概况。


3.2 
- **想知道函数有没有执行？** 对于协议入口级、模块 API 级、插件 Hook 级函数可以使用 `findFunctionCalls`。如果已经对齐了协议入口或 API 等，记得加上 `scopeCallIds`，缩小范围。
3.3
- **想知道分支是否命中？** 首先考虑使用 `observeExecutionSkeleton`, 如果已经明确知道了 parent `callId`，也可以使用 `observeChildrenCalls`。

---


# 4. Environment Notes

## 📌 curl 跨平台使用小结

> ⚠️ **所有示例均为单行命令**，避免换行在不同终端中的兼容性问题


### ✅ 推荐优先级

1. **主文档使用（Linux / macOS / WSL / Git Bash）**

```bash 
curl -X POST "http://localhost/..." -H "Content-Type: application/json" -H "Accept: application/json" -d '{"key":"value"}'
```

2. **Windows PowerShell（必须注意）**

```powershell 
curl.exe -X POST "http://localhost/..." -H "Content-Type: application/json" -H "Accept: application/json" -d '{"key":"value"}'
```

3. **Windows cmd（兼容写法）**

```cmd 
curl -X POST "http://localhost/..." -H "Content-Type: application/json" -H "Accept: application/json" -d "{\"key\":\"value\"}"
```

---

### ⚠️ 必踩坑总结

* **PowerShell 里 `curl` ≠ curl**

  * 实际是 `Invoke-WebRequest`
  * ✅ 必须使用：`curl.exe`

* **cmd 必须转义 JSON**

  * ❌ 错误：`"{ "key": "value" }"`
  * ✅ 正确：`"{\"key\":\"value\"}"`

* **不要省略 Content-Type**

```bash 
-H "Content-Type: application/json"
```

---

# 5. Error notes

- `No active process`：当前没有激活录制进程，先激活/选择进程再调用接口。
- `404`：通常是路径拼接错误。注意 Base URL 必须包含 `7a14c3b3`：`http://localhost:63342/xcodemap-trace-server/7a14c3b3/runtimedata/<api>`。
- 其他 `4xx/5xx`：查看 `errorMsg` 获取参数缺失、解析失败等原因。
- Windows（CMD/PowerShell）下**尤其要注意请求参数引号规范**：JSON 中的双引号需要正确转义（如 CMD 常用 `\"`）；建议先用最小请求体验证，再逐步加参数，避免因引号错误触发参数解析失败。

---

# 6. Output format

Must include:
- Observation Points
- Observations with evidence strength
- Summary (facts only)

If observation is insufficient:

> output: **insufficient observation**