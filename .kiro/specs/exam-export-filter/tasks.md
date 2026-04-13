# 实现计划：exam-export-filter

## 概述

在不破坏现有全量导出逻辑的前提下，扩展"导出考试记录"指令，支持按区域和按学生姓名筛选，并将文件消息类型改为 `sampleSpreadSheet` 以支持钉钉在线预览。

## 任务

- [x] 1. 扩展 SystemApiService — 支持 nickName 参数
  - [x] 1.1 修改 `buildExportUrl`，新增 `nickName` 参数，对非空值进行 URL 编码，空值保持空字符串
    - 方法签名：`private String buildExportUrl(String nickName, String beginStart, String beginEnd)`
    - _需求：5.2、5.3、5.4_
  - [x] 1.2 新增三参数重载 `exportExamRecords(String nickName, String beginStart, String beginEnd)`
    - 原有两参数方法委托调用新重载：`return exportExamRecords("", beginStart, beginEnd)`
    - _需求：5.1、5.3_
  - [ ]* 1.3 为 `buildExportUrl` 编写属性测试（属性 3：nickName 编码 round-trip）
    - **属性 3：URL 构建 — nickName 编码 round-trip**
    - **验证：需求 5.2、5.3**
    - 使用 jqwik 生成随机字符串（含中文、特殊字符、空字符串），断言 URL 解码后等于原始 nickName

- [x] 2. 扩展 UserCommandHandler — 解析逻辑
  - [x] 2.1 新增内部类 `ExportParams` 和枚举 `ExportMode`
    - 字段：`mode`、`nickName`、`label`、`beginStart`、`beginEnd`
    - _需求：1.1、2.1、3.1、4.1_
  - [x] 2.2 新增 `parseExportArg(String arg)` 方法，替换现有 `parseTimeRange` 调用
    - 按优先级匹配：`学生 <姓名>` → STUDENT；`近一周/近一个月 <区域名>` → REGION；`<日期> <日期> <区域名>` → REGION；无区域名 → ALL（兼容旧逻辑）
    - 学生姓名为空时返回 null；日期格式非法时返回 null
    - _需求：1.1、2.1、3.1、3.4、4.1、4.4_
  - [ ]* 2.3 为 `parseExportArg` 编写属性测试（属性 1：区域解析 nickName 前缀正确性）
    - **属性 1：区域解析 — nickName 前缀正确性**
    - **验证：需求 1.1、2.1、3.1**
    - 生成随机非空区域名 + 随机时间关键词或合法日期对，断言 `nickName == "（" + region` 且 `mode == REGION`
  - [ ]* 2.4 为 `parseExportArg` 编写属性测试（属性 2：学生解析正确性）
    - **属性 2：学生解析 — nickName 与时间参数正确性**
    - **验证：需求 4.1**
    - 生成随机非空学生姓名，断言 `nickName == name`，`beginStart/beginEnd` 为 null 或空字符串，`mode == STUDENT`

- [x] 3. 扩展 UserCommandHandler — 导出处理与文件名
  - [x] 3.1 修改 `handleExportExam`，使用 `parseExportArg` 替换 `parseTimeRange`，并将 `params.nickName` 传入 `systemApiService.exportExamRecords`
    - _需求：1.1、2.1、3.1、4.1_
  - [x] 3.2 修改 `buildExportFileName`，按 `ExportMode` 生成对应格式文件名
    - STUDENT 模式：`考试记录_<label>.xlsx`
    - REGION 模式：`考试记录_<label>_<yyyyMMdd>_<yyyyMMdd>.xlsx`
    - ALL 模式：`考试记录_<yyyyMMdd>_<yyyyMMdd>.xlsx`（兼容旧逻辑）
    - _需求：1.3、2.3、3.3、4.3_
  - [ ]* 3.3 为 `buildExportFileName` 编写属性测试（属性 4：区域模式文件名格式）
    - **属性 4：文件名生成 — 区域模式格式正确性**
    - **验证：需求 1.3、2.3、3.3**
    - 生成随机非空区域名和合法日期对，断言文件名匹配 `考试记录_.+_\d{8}_\d{8}\.xlsx` 且包含区域名
  - [ ]* 3.4 为 `buildExportFileName` 编写属性测试（属性 5：学生模式文件名格式）
    - **属性 5：文件名生成 — 学生模式格式正确性**
    - **验证：需求 4.3**
    - 生成随机非空学生姓名，断言文件名匹配 `考试记录_<name>.xlsx`

- [x] 4. 更新帮助文本与错误提示
  - [x] 4.1 修改 `buildHelpText`，新增四种指令格式示例
    - `导出考试记录 近一周 <区域名>`
    - `导出考试记录 近一个月 <区域名>`
    - `导出考试记录 2026-04-01 2026-04-11 <区域名>`
    - `导出考试记录 学生 <学生姓名>`
    - _需求：6.1_
  - [x] 4.2 新增 `buildExportErrorHint` 方法，返回包含全部支持格式（含新增格式）的错误提示
    - 在 `handleExportExam` 中解析失败时调用此方法
    - _需求：6.2、1.4、2.4、3.4、3.5、4.4_

- [x] 5. 检查点 — 确保所有测试通过
  - 确保所有测试通过，如有疑问请向用户确认。

- [x] 6. 修改 DingTalkMessageService — 支持在线预览
  - [x] 6.1 将 `sendFileMessage` 中的 `msgKey` 从 `"sampleFile"` 改为 `"sampleSpreadSheet"`
    - _需求：7.1_
  - [x] 6.2 将 `sendPrivateFileMessage` 中的 `msgKey` 从 `"sampleFile"` 改为 `"sampleSpreadSheet"`
    - _需求：7.2_
  - [ ]* 6.3 为 `sendFileMessage` 和 `sendPrivateFileMessage` 编写单元测试
    - 断言发送的 JSON body 中 `msgKey == "sampleSpreadSheet"`
    - _需求：7.1、7.2_

- [x] 7. 最终检查点 — 确保所有测试通过
  - 确保所有测试通过，如有疑问请向用户确认。

## 备注

- 标有 `*` 的子任务为可选项，可跳过以加快 MVP 交付
- 每个任务均引用具体需求条款以保证可追溯性
- 属性测试使用 [jqwik](https://jqwik.net/)，每个属性最少运行 100 次
- 任务 1.2 保留原有两参数方法签名，确保向后兼容
