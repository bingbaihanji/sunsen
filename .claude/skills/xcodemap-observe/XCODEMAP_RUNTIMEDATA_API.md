# RuntimeData API（精简版）

Base URL

```
http://localhost:63342/xcodemap-trace-server/7a14c3b3
```

Request

```
POST ${BASE_URL}/runtimedata/<api>
Content-Type: application/json
Accept: application/json
```

Response

```json
{"errorCode":"200","errorMsg":"","data":{}}
```

`errorCode == "200"` 表示成功；若返回 `"No active process"`，说明当前没有激活录制进程。

If the response indicates **"No active process"**, there is no active recording.

## 错误说明

- **404**：通常是路径拼接错误。特别注意 Base URL 中 `xcodemap-trace-server` 后面那一串字符（`7a14c3b3`），代表当前项目 ID，
  **不能省略**。完整格式应为 `http://localhost:63342/xcodemap-trace-server/7a14c3b3/runtimedata/<api>`。

## Terminology (API Response Data)

| Term              | Meaning                                                                             |
|-------------------|-------------------------------------------------------------------------------------|
| **callId**        | 调用 ID（如 `c_123`）；数字越小，表示越先执行。                                     |
| **scopeCallId**   | 当前结果归属的 scope 根调用 ID（通常是 endpoint 或指定 scope 子树内命中的最近根）。 |
| **coverredLines** | 命中的源码行号集合（按行号升序）；用于表示一组调用覆盖了哪些行。                    |

---

# RuntimeData API Templates

## checkRecordDataAndScope

Optional params:

- `showClassName`: `true` 时返回每个统计桶下的 `classNames` 明细（数据量可能较大）。

返回结构组织：按 `recordingScope` 汇总分组，再给出顶层统计计数（如请求数、线程数、文件大小）。

```json
{}
```

## findFunctionCalls

Optional params:

- `scopeCallIds`: 仅在指定 scope 子树内搜索。

返回结构组织：按函数分组，再按 `scopeCallId` 分组，最后列出每组命中的 `callId`。

```json
{"classNames":["UserService"],"functionNames":["getUser"],"scopeCallIds":["c_50"]}
```

## observeSourceExecution

Optional params:

- `scopeCallIds`: 仅统计这些 scope 子树内的行命中。

返回结构组织：按 `coverredLines` 分组，再在每组下按 `scopeCallId` 和 parent 调用分层展开。

```json
{"file":"com/example/UserService.java","lineRange":{"start":120,"end":128},"scopeCallIds":["c_50"]}
```

## findCoveredFunctions

Optional params:

- `classNameRegExps`: 类名正则过滤。
- `functionNameRegExps`: 方法名正则过滤。

返回结构组织：按“匹配名称列表”单层返回（无调用树分组）。

```json
{"classNameRegExps":["com\\.example\\..*"],"functionNameRegExps":["get.*"]}
```

## observeChildrenCalls

Optional params:

- 无。

返回结构组织：按子调用所在文件分组，每组下按 `coverredLines` 展开命中明细。

```json
{"callId":"c_123"}
```

## observeCallStack

Optional params:

- 无。

返回结构组织：按调用栈顺序（当前调用 -> 父链）线性返回，并附带栈摘要。

```json
{"callId":"c_123"}
```

## observeExecutionSkeleton

Optional params:

- 无。

返回结构组织：按骨架节点列表返回（属于同一个 `scopeCallId`）。

```json
{"callId":"c_123"}
```

## listRootCalls

Optional params:

- 无。

返回结构组织：按 root 调用列表单层返回，并附带整体摘要。

```json
{}
```

## observeCallDetails

Optional params:

- 无。

返回结构组织：按请求 `callIds` 的原顺序返回 `callDetails` 列表（一一对应，缺失项为 `null`）。

```json
{"callIds":["c_100","c_200"]}
```

## observeObjectValue

Optional params:

- `maxSize`: 对象展开大小上限（字节）。

返回结构组织：单对象值返回（不分组）。

```json
{"objectId":"@111","callId":"c_123","maxSize":1024}
```

## traceObjectFlow

Optional params:

- 无。

返回结构组织：按对象值相关调用的调用节点列表返回（配套映射表在同一层）。

```json
{"values":["@100"]}
```
