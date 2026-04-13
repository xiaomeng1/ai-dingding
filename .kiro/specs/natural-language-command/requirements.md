# 需求文档

## 简介

本功能为钉钉机器人添加自然语言理解能力。当前系统要求用户严格按照固定格式输入指令，容易出错。通过接入阿里百炼（通义千问）大模型，用户可以用自然语言描述意图，系统自动识别并映射到对应的结构化指令和参数，再路由到现有的指令处理逻辑执行。

## 词汇表

- **NLU_Service**：自然语言理解服务，负责调用百炼大模型解析用户意图
- **Intent**：意图，指用户消息所对应的系统指令类型（如创建用户、搜索用户等）
- **CommandRouter**：指令路由器，根据 NLU_Service 返回的结构化结果分发到对应处理逻辑
- **UserCommandHandler**：现有的指令处理入口，接收并处理钉钉机器人消息
- **BaiLian_Client**：封装阿里百炼 OpenAI 兼容接口的 HTTP 客户端
- **ParseResult**：NLU_Service 返回的解析结果，包含 intent 字段和 params 字段，或 unrecognized 标志及提示信息
- **System_Prompt**：发送给大模型的系统提示词，约束模型输出结构化 JSON 格式的 ParseResult

## 需求

### 需求 1：自然语言意图解析

**用户故事：** 作为钉钉机器人用户，我希望能用自然语言描述操作意图，这样我不需要记忆严格的指令格式也能完成操作。

#### 验收标准

1. WHEN 用户发送一条钉钉消息，THE UserCommandHandler SHALL 在执行指令路由前先将消息文本传入 NLU_Service 进行意图解析。
2. WHEN NLU_Service 成功识别意图，THE NLU_Service SHALL 返回包含 `intent`（指令名称）和 `params`（参数键值对）字段的 ParseResult JSON 对象。
3. WHEN NLU_Service 无法识别意图，THE NLU_Service SHALL 返回包含 `unrecognized: true` 和 `hint`（给用户的提示信息）字段的 ParseResult JSON 对象。
4. THE NLU_Service SHALL 仅识别系统当前支持的五类意图：`CREATE_USER`、`SEARCH_USER`、`EXPORT_EXAM`、`STUDENT_STATS`、`HELP`。
5. WHEN NLU_Service 返回 `unrecognized: true`，THE UserCommandHandler SHALL 将 ParseResult 中的 `hint` 字段内容直接回复给用户，不执行任何指令。

---

### 需求 2：百炼大模型客户端

**用户故事：** 作为开发者，我希望有一个封装好的百炼模型客户端，这样 NLU_Service 可以复用统一的 HTTP 调用逻辑。

#### 验收标准

1. THE BaiLian_Client SHALL 使用 OpenAI SDK 2.6.0，baseUrl 配置为 `https://dashscope.aliyuncs.com/compatible-mode/v1`，模型为 `qwen-plus`。
2. THE BaiLian_Client SHALL 从 JVM 启动参数 `-Dbailian.apiKey=xxx` 读取 API Key。
3. WHEN API Key 未配置（为 null 或空字符串），THE BaiLian_Client SHALL 抛出包含明确说明的 IllegalStateException，并在日志中记录错误。
4. WHEN 调用百炼接口发生网络异常或超时，THE BaiLian_Client SHALL 抛出运行时异常，由调用方处理降级逻辑。

---

### 需求 3：System Prompt 设计与结构化输出约束

**用户故事：** 作为开发者，我希望通过精心设计的 System Prompt 约束模型输出，这样模型能稳定返回可解析的 JSON 格式结果。

#### 验收标准

1. THE System_Prompt SHALL 以中文描述系统支持的全部指令格式及参数说明，供模型理解上下文。
2. THE System_Prompt SHALL 要求模型仅输出纯 JSON，不包含任何 Markdown 代码块标记（如 ` ```json ` ）或额外解释文字。
3. THE System_Prompt SHALL 定义识别成功时的输出格式：`{"intent": "<指令名>", "params": {<参数键值对>}}`。
4. THE System_Prompt SHALL 定义无法识别时的输出格式：`{"unrecognized": true, "hint": "<给用户的中文提示>"}`。
5. WHEN 用户消息语义模糊或缺少必要参数，THE System_Prompt SHALL 指示模型在 `hint` 字段中给出具体的补充说明，告知用户缺少哪些信息。
6. THE NLU_Service SHALL 在解析模型返回的 JSON 前，去除可能存在的 Markdown 代码块标记（` ```json ` 和 ` ``` `），以提高解析鲁棒性。

---

### 需求 4：意图到指令的路由映射

**用户故事：** 作为开发者，我希望 NLU 解析结果能无缝对接现有指令处理逻辑，这样不需要重写现有功能。

#### 验收标准

1. WHEN ParseResult 的 `intent` 为 `CREATE_USER`，THE CommandRouter SHALL 从 `params` 中提取 `users` 字段（支持批量，格式与现有 `parseBatchCreateArgs` 兼容）并调用现有创建用户逻辑。
2. WHEN ParseResult 的 `intent` 为 `SEARCH_USER`，THE CommandRouter SHALL 从 `params` 中提取 `nickName` 字段并调用现有搜索用户逻辑。
3. WHEN ParseResult 的 `intent` 为 `EXPORT_EXAM`，THE CommandRouter SHALL 从 `params` 中提取 `timeRange`（近一周/近一个月/自定义日期）和可选的 `region` 或 `studentName` 字段，并调用现有导出考试记录逻辑。
4. WHEN ParseResult 的 `intent` 为 `STUDENT_STATS`，THE CommandRouter SHALL 从 `params` 中提取 `timeRange` 字段并调用现有统计新增学员逻辑。
5. WHEN ParseResult 的 `intent` 为 `HELP`，THE CommandRouter SHALL 回复现有的帮助文本（`buildHelpText()` 返回内容）。
6. WHEN ParseResult 的 `intent` 为上述五类之外的未知值，THE CommandRouter SHALL 回复帮助文本，并在日志中记录 warn 级别的未知意图信息。

---

### 需求 5：NLU 调用异常降级处理

**用户故事：** 作为系统运维人员，我希望当 NLU 服务不可用时系统能优雅降级，这样不会因为模型调用失败而完全中断服务。

#### 验收标准

1. WHEN NLU_Service 调用百炼接口发生异常（网络超时、API Key 无效等），THE UserCommandHandler SHALL 捕获异常并回复用户"自然语言解析服务暂时不可用，请使用标准指令格式"及帮助文本。
2. WHEN NLU_Service 返回的内容无法解析为合法 JSON，THE NLU_Service SHALL 将其视为 `unrecognized: true`，并将原始返回内容作为 `hint` 回复给用户。
3. IF API Key 未配置，THEN THE UserCommandHandler SHALL 在启动时记录 warn 日志提示配置缺失，并在收到消息时回复用户"NLU 功能未启用，请使用标准指令格式"及帮助文本，不抛出异常中断服务。

---

### 需求 6：NLU 解析结果的正确性

**用户故事：** 作为测试工程师，我希望 NLU 解析逻辑有明确的正确性保证，这样可以通过自动化测试验证模型集成的准确性。

#### 验收标准

1. THE NLU_Service SHALL 对模型返回的 JSON 字符串进行解析，解析结果中的 `intent` 字段值 SHALL 属于 `{CREATE_USER, SEARCH_USER, EXPORT_EXAM, STUDENT_STATS, HELP}` 集合或 `unrecognized` 为 true。
2. FOR ALL 合法的 ParseResult JSON 字符串，将其序列化后再反序列化 SHALL 得到语义等价的对象（往返属性）。
3. WHEN 模型返回包含 Markdown 代码块标记的 JSON，THE NLU_Service 清洗后 SHALL 能正确解析出与不含标记时相同的结果（幂等清洗属性）。
