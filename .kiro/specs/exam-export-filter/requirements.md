# 需求文档

## 简介

为钉钉机器人的"导出考试记录"功能新增筛选能力。当前该功能仅支持按时间段全量导出，本次扩展支持：按区域筛选（近一周、近一个月、自定义时间段）以及按学生姓名筛选（查询该学生全部考试记录）。

筛选逻辑复用现有导出接口 `/api/test/record/export`，通过传入不同的 `nickName` 参数实现：
- 按区域筛选时传入 `（+区域名`（如 `（阿勒泰市`），同时传时间段参数；
- 按学生姓名筛选时传入学生姓名（不含括号），不传时间段参数。

---

## 词汇表

- **CommandHandler**：`UserCommandHandler`，负责解析钉钉机器人消息并路由到对应处理逻辑的组件。
- **SystemApiService**：封装对后端系统 API 调用的服务组件，包含 `buildExportUrl` 和 `exportExamRecords` 方法。
- **nickName**：后端系统中用户的显示名称，格式为 `姓名（区域）`，例如 `刘登高（阿勒泰市）`。
- **区域筛选前缀**：传给 `nickName` 参数的区域匹配字符串，格式为 `（+区域名`，例如 `（阿勒泰市`，利用后端模糊匹配实现按区域过滤。
- **时间段参数**：`beginStart` 和 `beginEnd`，格式为 `yyyy-MM-dd HH:mm:ss`，用于限定考试记录的时间范围。
- **全量时间段**：不限制时间范围时使用的占位值，`beginStart` 和 `beginEnd` 均传空字符串或由接口默认处理。

---

## 需求

### 需求 1：按区域导出近一周考试记录

**用户故事：** 作为钉钉群管理员，我希望通过发送指令导出指定区域近一周的考试记录，以便快速了解该区域最近一周的考试情况。

#### 验收标准

1. WHEN 用户发送 `导出考试记录 近一周 <区域名>`，THE CommandHandler SHALL 将区域名解析为区域筛选前缀，并将时间范围设置为当前日期往前推 7 天至当天 23:59:59。
2. WHEN CommandHandler 解析到区域筛选前缀和时间范围，THE SystemApiService SHALL 构建包含 `nickName=（<区域名>` 及对应 `beginStart`、`beginEnd` 参数的导出 URL。
3. WHEN 导出文件成功返回，THE CommandHandler SHALL 将文件以 `考试记录_<区域名>_<开始日期>_<结束日期>.xlsx` 格式的文件名发送到对话。
4. IF 区域名为空，THEN THE CommandHandler SHALL 回复格式错误提示，内容包含正确的指令格式示例。

---

### 需求 2：按区域导出近一个月考试记录

**用户故事：** 作为钉钉群管理员，我希望通过发送指令导出指定区域近一个月的考试记录，以便进行月度区域考试情况分析。

#### 验收标准

1. WHEN 用户发送 `导出考试记录 近一个月 <区域名>`，THE CommandHandler SHALL 将区域名解析为区域筛选前缀，并将时间范围设置为当前日期往前推 1 个月至当天 23:59:59。
2. WHEN CommandHandler 解析到区域筛选前缀和时间范围，THE SystemApiService SHALL 构建包含 `nickName=（<区域名>` 及对应 `beginStart`、`beginEnd` 参数的导出 URL。
3. WHEN 导出文件成功返回，THE CommandHandler SHALL 将文件以 `考试记录_<区域名>_<开始日期>_<结束日期>.xlsx` 格式的文件名发送到对话。
4. IF 区域名为空，THEN THE CommandHandler SHALL 回复格式错误提示，内容包含正确的指令格式示例。

---

### 需求 3：按区域导出自定义时间段考试记录

**用户故事：** 作为钉钉群管理员，我希望通过发送指令导出指定区域在自定义时间段内的考试记录，以便灵活查询任意时间范围的区域考试数据。

#### 验收标准

1. WHEN 用户发送 `导出考试记录 <开始日期> <结束日期> <区域名>`（日期格式 `yyyy-MM-dd`），THE CommandHandler SHALL 将区域名解析为区域筛选前缀，并将时间范围设置为开始日期 00:00:00 至结束日期 23:59:59。
2. WHEN CommandHandler 解析到区域筛选前缀和时间范围，THE SystemApiService SHALL 构建包含 `nickName=（<区域名>` 及对应 `beginStart`、`beginEnd` 参数的导出 URL。
3. WHEN 导出文件成功返回，THE CommandHandler SHALL 将文件以 `考试记录_<区域名>_<开始日期>_<结束日期>.xlsx` 格式的文件名发送到对话。
4. IF 日期格式不符合 `yyyy-MM-dd`，THEN THE CommandHandler SHALL 回复格式错误提示，内容包含正确的指令格式示例。
5. IF 区域名为空，THEN THE CommandHandler SHALL 回复格式错误提示，内容包含正确的指令格式示例。

---

### 需求 4：按学生姓名导出全部考试记录

**用户故事：** 作为钉钉群管理员，我希望通过发送指令导出指定学生的全部考试记录，以便追踪该学生的历史考试情况。

#### 验收标准

1. WHEN 用户发送 `导出考试记录 学生 <学生姓名>`，THE CommandHandler SHALL 将学生姓名作为 `nickName` 筛选值，且不传时间段参数（`beginStart` 和 `beginEnd` 均为空字符串）。
2. WHEN CommandHandler 解析到学生姓名筛选模式，THE SystemApiService SHALL 构建包含 `nickName=<学生姓名>` 且 `beginStart=`、`beginEnd=` 为空的导出 URL。
3. WHEN 导出文件成功返回，THE CommandHandler SHALL 将文件以 `考试记录_<学生姓名>.xlsx` 格式的文件名发送到对话。
4. IF 学生姓名为空，THEN THE CommandHandler SHALL 回复格式错误提示，内容包含正确的指令格式示例。

---

### 需求 5：扩展 SystemApiService 导出接口支持 nickName 参数

**用户故事：** 作为开发者，我希望 `SystemApiService.exportExamRecords` 方法支持传入 `nickName` 参数，以便上层逻辑可以灵活控制筛选条件。

#### 验收标准

1. THE SystemApiService SHALL 提供接受 `nickName`、`beginStart`、`beginEnd` 三个参数的 `exportExamRecords` 重载或方法签名更新。
2. WHEN `nickName` 不为空，THE SystemApiService SHALL 将其 URL 编码后拼入导出 URL 的 `nickName` 参数。
3. WHEN `nickName` 为空，THE SystemApiService SHALL 将导出 URL 的 `nickName` 参数保持为空字符串，与现有行为一致。
4. WHEN `beginStart` 或 `beginEnd` 为空字符串，THE SystemApiService SHALL 将对应参数以空字符串传入导出 URL，不进行 URL 编码处理。

---

### 需求 6：更新帮助文本与错误提示

**用户故事：** 作为钉钉群用户，我希望机器人的帮助文本和错误提示能够反映新增的筛选指令格式，以便我知道如何正确使用新功能。

#### 验收标准

1. THE CommandHandler SHALL 在帮助文本中包含以下四种新指令格式的示例：
   - `导出考试记录 近一周 <区域名>`
   - `导出考试记录 近一个月 <区域名>`
   - `导出考试记录 2026-04-01 2026-04-11 <区域名>`
   - `导出考试记录 学生 <学生姓名>`
2. WHEN 导出考试记录指令格式不正确，THE CommandHandler SHALL 回复包含全部支持格式（含新增格式）的错误提示。

---

### 需求 7：导出文件支持钉钉在线预览

**用户故事：** 作为钉钉群用户，我希望机器人发送的考试记录 Excel 文件可以在钉钉内直接预览，而不是只能下载，以便快速查看数据。

#### 验收标准

1. WHEN 机器人向群会话发送考试记录文件，THE DingTalkMessageService SHALL 使用 `sampleSpreadSheet` 消息类型（而非 `sampleFile`）发送文件，使文件在钉钉内可在线预览。
2. WHEN 机器人向用户私聊发送考试记录文件，THE DingTalkMessageService SHALL 同样使用 `sampleSpreadSheet` 消息类型发送文件。
3. WHEN 文件上传成功但消息发送失败，THE DingTalkMessageService SHALL 记录错误日志，行为与现有 `sampleFile` 失败处理一致。
