# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run Commands

```bash
# Build
./mvnw clean package -DskipTests

# Run (requires JVM properties)
java -Dding.appKey=xxx -Dding.appSecret=xxx -Dsys.username=xxx -Dsys.password=xxx [-Dbailian.apiKey=xxx] -jar target/ai-dingding-0.0.1-SNAPSHOT.jar

# Run a single test
./mvnw test -Dtest=ClassName#methodName
```

## Architecture

Spring Boot application with embedded Tomcat (port 8080). DingTalk stream client runs as a `CommandLineRunner`.

### Message Flow

```
DingTalk → DingTalk Stream SDK → DingDingConfig (CommandLineRunner)
         → UserCommandHandler.handle() (normalizes + dispatches)
         → NluService.parse() (calls BaiLian AI to translate natural language → structured intent)
         → CommandRouter.route() (routes intent to handler method)
         → UserCommandHandler methods (createUser, searchUser, exportExam, studentStats, registerSuccess)
         → SystemApiService (calls os.zhida-keji.com.cn backend)
         → DingTalkMessageService (sends replies back to DingTalk)
```

### Key Components

| Component | Type | Responsibility |
|----------|------|----------------|
| `Application` | `@SpringBootApplication` | Entry point; excludes `DataSourceAutoConfiguration` |
| `DingDingConfig` | `@Configuration` | Creates all service beans; starts DingTalk stream client via `CommandLineRunner` |
| `RegistrationService` | `@Service` | SQLite 内存数据库：报名 CRUD、审批状态查询/更新 |
| `UserCommandHandler` | Bean | Parses commands, orchestrates NLU + routing; handles async export/stats |
| `NluService` | Bean | Calls BaiLian AI to parse natural language into `ParseResult` |
| `CommandRouter` | Bean | Routes `ParseResult` to the appropriate `UserCommandHandler` method |
| `RegistrationController` | `@Controller` | Web UI (`/registrations`) + REST API (`/registrations/api/*`) |
| `BaiLianClient` | Bean | Thin wrapper around OpenAI SDK calling Alibaba's BaiLian API (qwen-plus) |
| `SystemApiService` | Bean | Calls backend at `os.zhida-keji.com.cn` (user CRUD, exam records) |
| `DingTalkMessageService` | Bean | Sends text/file messages back to DingTalk (group + private chat) |
| `AuthTokenHolder` | Static holder | In-memory token cache for DingTalk + backend API tokens |
| `LicenseChecker` | Static | Gitee-based license validation (30s cache) |

### NLU Intent System

The `NluService` sends user text to BaiLian AI with a system prompt that defines 6 intents:
- `CREATE_USER` — params: `users` (format: "姓名 手机号, ...")
- `SEARCH_USER` — params: `nickName`
- `EXPORT_EXAM` — params: `timeRange`, `region?`, `studentName?` (mutually exclusive)
- `STUDENT_STATS` — params: `timeRange`
- `REGISTER_SUCCESS` — params: `users` (format: "姓名 手机号, ...")
- `HELP` — no params

AI returns JSON like `{"intent": "...", "params": {...}}` or `{"unrecognized": true, "hint": "..."}`.

### Registration SQLite Schema

```sql
CREATE TABLE registrations (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL UNIQUE,
    phone TEXT NOT NULL,
    ding_approved INTEGER DEFAULT 0,
    meeting_approved INTEGER DEFAULT 0
)
```

### REST API & Web Page

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/registrations` | 报名管理 Web 页面 |
| `GET` | `/registrations/api` | 全部报名记录（JSON） |
| `GET` | `/registrations/api/unapproved?field=ding&name=张三` | 未审批用户（JSON） |
| `GET` | `/registrations/api/pending-names` | 钉钉群或会议录播有任意一项未审批的用户姓名列表 `List<String>`（JSON） |
| `POST` | `/registrations/api/approve` | 更新审批状态，Body: `{"name":"张三","dingApproved":true}` |

### Async Operations

Long-running operations (exam export, student stats) run in a cached thread pool (`asyncExecutor`) to avoid blocking DingTalk's callback timeout.

### Token Strategy

- **DingTalk token**: cached in `AuthTokenHolder`, refreshed when within 5min of expiry
- **Backend token**: lazy login on first use, auto-retry on 401/500 responses via `executeWithTokenRetry()`

### Dev Profile & Simulate Endpoint

When running with `--spring.profiles.active=dev`, a `SimulateController` is activated at `POST /simulate/message`. It accepts a JSON body and returns captured replies — useful for testing without a real DingTalk connection:

```json
// Request
{ "content": "创建用户 张三 13800138000", "conversationType": "2" }

// Response
{ "replies": [{ "type": "text", "target": "sim-conv-001", "text": "..." }] }
```

`ReplyCapture` intercepts replies during simulation (waits up to 30s for async ops).

### Test Structure

Tests in `src/test/` cover: `BaiLianClientTest`, `NluServicePropertyTest`, `CommandRouterTest`, `UserCommandHandlerTest`, `ParseResultPropertyTest`, `SimulateApiTest` (requires dev profile + running server).

## Important Patterns

- **All DingTalk replies** must go through `UserCommandHandler.reply()` or `replyFile()` — never directly call `sendTextMessage()` or `sendExamFile()`. This ensures correct routing between group chat (conversationId) and private chat (senderId).
- **Fastjson2** is shade/fastjson2 — imports use `shade.com.alibaba.fastjson2.*`
- **Date formats**: API uses `yyyy-MM-dd HH:mm:ss`; file names use `yyyyMMdd`
- **Region extraction**: Students' region is parsed from trailing parentheses in `nickName` (e.g., "张三（五家渠）" → "五家渠")
- **SQLite connection**: managed by Spring as a singleton; `@PreDestroy` closes it on shutdown
- **No Spring DataSource**: `DataSourceAutoConfiguration` is excluded — `RegistrationService` manages its own SQLite `Connection`
