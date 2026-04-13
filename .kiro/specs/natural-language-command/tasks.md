# 实现计划：自然语言指令（natural-language-command）

## 概述

按照设计文档，在现有钉钉机器人指令处理链路中插入 NLU 层。新增 `BaiLianClient`、`ParseResult`、`NluService`、`CommandRouter` 四个组件，改造 `UserCommandHandler` 和 `DingDingMain`，并使用 jqwik 编写四条正确性属性测试。

## 任务

- [x] 1. 添加 jqwik 依赖并创建核心数据模型 ParseResult
  - 在 `pom.xml` 中添加 jqwik 测试依赖（`net.jqwik:jqwik`，scope=test）
  - 在 `src/main/java/com/ai/dingding/nlu/` 包下创建 `ParseResult.java`
  - 包含字段：`intent`（String）、`params`（Map<String, Object>）、`unrecognized`（boolean）、`hint`（String）
  - 提供 getter/setter 及静态工厂方法 `ofUnrecognized(String hint)`
  - _需求：1.2、1.3、6.2_

- [x] 2. 实现 BaiLianClient
  - [x] 2.1 创建 `src/main/java/com/ai/dingding/nlu/BaiLianClient.java`
    - 构造时从 `System.getProperty("bailian.apiKey")` 读取 apiKey
    - apiKey 为 null 或空时抛 `IllegalStateException` 并记录 error 日志
    - 使用 `OpenAIOkHttpClient.builder()` 配置 baseUrl 为 `https://dashscope.aliyuncs.com/compatible-mode/v1`，model 为 `qwen-plus`
    - 实现 `public String chat(String systemPrompt, String userMessage)` 方法，网络异常时抛运行时异常
    - _需求：2.1、2.2、2.3、2.4_

  - [x] 2.2 编写 BaiLianClient 单元测试
    - 测试 apiKey 未配置时构造抛 `IllegalStateException`
    - _需求：2.3_

- [x] 3. 实现 NluService（含 System Prompt 和 Markdown 清洗）
  - [x] 3.1 创建 `src/main/java/com/ai/dingding/nlu/NluService.java`
    - 构造函数接收 `BaiLianClient`
    - 定义 `SYSTEM_PROMPT` 常量（中文，描述五类指令格式、输出格式约束、模糊处理规则）
    - 实现 `static String stripMarkdownCodeBlock(String raw)`：去除 ` ```json ` 和 ` ``` ` 标记
    - 实现 `public ParseResult parse(String userText)`：调用 `BaiLianClient.chat()`，清洗输出，解析 JSON；解析失败返回 `ParseResult.ofUnrecognized(原始内容)`
    - 使用 `shade.com.alibaba.fastjson2.JSON` 解析 JSON（与项目现有依赖一致）
    - _需求：1.1、1.2、1.3、3.1、3.2、3.3、3.4、3.5、3.6、5.2_

  - [x] 3.2 编写属性测试：属性 2 - Markdown 标记清洗等价性
    - 生成随机合法 JSON 字符串，分别测试原始版本和包裹 Markdown 标记的版本
    - 断言 `stripMarkdownCodeBlock` 后两者内容相同
    - 注释：`// Feature: natural-language-command, Property 2: Markdown 标记清洗等价性`
    - **属性 2：Markdown 标记清洗的等价性**
    - **验证：需求 3.6、6.3**

  - [x] 3.3 编写属性测试：属性 3 - 解析结果意图合法性
    - 生成随机字符串（含合法 JSON 和随机字符串），调用 `NluService` 的 JSON 解析逻辑
    - 断言结果满足：`intent ∈ {CREATE_USER, SEARCH_USER, EXPORT_EXAM, STUDENT_STATS, HELP}` 或 `unrecognized=true`
    - 注释：`// Feature: natural-language-command, Property 3: 解析结果意图合法性`
    - **属性 3：解析结果的意图合法性**
    - **验证：需求 1.4、6.1**

  - [x] 3.4 编写属性测试：属性 4 - 非法 JSON 输入视为 unrecognized
    - 生成随机非 JSON 字符串（排除合法 JSON），调用解析逻辑
    - 断言结果 `unrecognized=true` 且 `hint` 非空
    - 注释：`// Feature: natural-language-command, Property 4: 非法 JSON 输入视为 unrecognized`
    - **属性 4：非法 JSON 输入视为 unrecognized**
    - **验证：需求 5.2**

- [x] 4. 实现 CommandRouter
  - [x] 4.1 创建 `src/main/java/com/ai/dingding/nlu/CommandRouter.java`
    - 定义 `VALID_INTENTS = Set.of("CREATE_USER", "SEARCH_USER", "EXPORT_EXAM", "STUDENT_STATS", "HELP")`
    - 构造函数接收 `UserCommandHandler`（用于调用现有 handle* 方法）和 `DingTalkMessageService`（用于回复帮助文本）
    - 实现 `public void route(ParseResult result, String conversationType, String conversationId, String senderId)`
    - `CREATE_USER`：从 `params.users` 构造指令字符串，调用 `UserCommandHandler` 中的 `parseBatchCreateArgs` + `handleBatchCreate`（需将这两个方法改为包级可见或提取为 package-private）
    - `SEARCH_USER`：从 `params.nickName` 调用 `handleSearch`
    - `EXPORT_EXAM`：从 `params.timeRange`、`params.region`、`params.studentName` 拼接参数字符串，调用 `handleExportExam`
    - `STUDENT_STATS`：从 `params.timeRange` 调用 `handleStudentStats`
    - `HELP`：调用 `buildHelpText()` 并回复
    - 未知 intent：回复帮助文本，记录 warn 日志
    - _需求：4.1、4.2、4.3、4.4、4.5、4.6_

  - [x] 4.2 编写 CommandRouter 单元测试
    - 使用 mock 验证五类 intent 各自路由到正确的 handle* 方法
    - 验证未知 intent 回复帮助文本
    - _需求：4.1–4.6_

- [x] 5. 检查点 - 确保所有测试通过
  - 确保所有测试通过，如有问题请向用户说明。

- [x] 6. 改造 UserCommandHandler
  - [x] 6.1 将 `parseBatchCreateArgs`、`handleBatchCreate`、`handleSearch`、`handleExportExam`、`handleStudentStats`、`buildHelpText`、`reply`、`replyFile` 方法访问修饰符改为 package-private（去掉 `private`），供 `CommandRouter` 调用
  - [x] 6.2 在 `UserCommandHandler` 中新增 `NluService nluService` 和 `CommandRouter commandRouter` 字段（可为 null）
  - [x] 6.3 新增构造函数重载，接收 `NluService` 和 `CommandRouter` 参数
  - [x] 6.4 改造 `handle()` 方法：在授权检查通过后，若 `nluService != null` 则调用 `nluService.parse(text)`；`unrecognized=true` 时回复 hint；解析成功时调用 `commandRouter.route()`；NLU 异常时回复降级提示及帮助文本；`nluService == null` 时回复"NLU 功能未启用"提示
  - _需求：1.1、1.5、5.1、5.3_

  - [x] 6.5 编写 UserCommandHandler 单元测试
    - NLU 异常时回复降级提示
    - `unrecognized=true` 时回复 hint 内容
    - apiKey 未配置（nluService=null）时回复"NLU 功能未启用"
    - _需求：5.1、5.3_

- [x] 7. 改造 DingDingMain，初始化新组件
  - 读取 `System.getProperty("bailian.apiKey")`
  - 若 apiKey 已配置：构造 `BaiLianClient` → `NluService` → `CommandRouter`，并传入 `UserCommandHandler` 的新构造函数
  - 若 apiKey 未配置：记录 warn 日志提示配置缺失，使用原有构造函数（nluService=null）
  - _需求：2.2、5.3_

- [x] 8. 编写属性测试：属性 1 - ParseResult 序列化往返
  - [x] 8.1 在测试目录创建 `ParseResultPropertyTest.java`
    - 生成随机合法 `ParseResult`（随机 intent + 随机 params，或随机 hint）
    - 序列化为 JSON 字符串后再反序列化，断言字段值相等
    - 注释：`// Feature: natural-language-command, Property 1: 合法 ParseResult 的序列化往返`
    - **属性 1：合法 ParseResult 的序列化往返**
    - **验证：需求 6.2**

- [x] 9. 最终检查点 - 确保所有测试通过
  - 确保所有测试通过，如有问题请向用户说明。

## 备注

- 标有 `*` 的子任务为可选项，可跳过以加快 MVP 交付
- `CommandRouter` 调用 `UserCommandHandler` 的 handle* 方法需要将相关方法改为 package-private，两者放在同一包下（`com.ai.dingding.handler`）或新建 `com.ai.dingding.nlu` 包并调整访问权限
- jqwik 属性测试最少运行 100 次迭代（`@Property(tries = 100)`）
- JSON 解析统一使用 `shade.com.alibaba.fastjson2.JSON`，与项目现有依赖保持一致
- OpenAI SDK 版本为项目现有版本（pom.xml 中为 `3.5.0`，与需求文档描述的 2.6.0 存在差异，实现时以 pom.xml 实际版本为准，参考 `BaiLianTest.java` 中已验证的用法）
