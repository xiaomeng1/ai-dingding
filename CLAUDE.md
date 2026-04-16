# CLAUDE.md

使用中文进行交互

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is a DingTalk (钉钉) chatbot for vaccine management system. It receives messages via DingTalk Stream SDK, uses Alibaba Bailian LLM for natural language understanding, and interacts with an external system API (os.zhida-keji.com.cn) for user/exam management operations.

## Build and Run Commands

```bash
# Build the project
mvn clean package

# Run tests
mvn test

# Run a single test class
mvn test -Dtest=UserCommandHandlerTest

# Run a single test method
mvn test -Dtest=UserCommandHandlerTest#testBatchCreateArgs_singleUser
```

## Runtime

The application requires JVM parameters (no Spring Boot web server is started):

```bash
java -Dding.appKey=xxx \
     -Dding.appSecret=xxx \
     -Dsys.username=xxx \
     -Dsys.password=xxx \
     -Dbailian.apiKey=xxx \
     -jar ai-dingding-0.0.1-SNAPSHOT.jar
```

Required parameters: `ding.appKey`, `ding.appSecret`, `sys.username`, `sys.password`
Optional: `bailian.apiKey` (enables NLU natural language parsing)

## High-Level Architecture

### Message Flow
1. **DingDingMain** - Entry point, initializes DingTalk Stream client and registers message callback
2. **UserCommandHandler** - Parses incoming messages, handles license validation, routes to NLU or direct command processing
3. **NluService + BaiLianClient** - Sends user text to Bailian LLM (qwen-plus model) with system prompt to extract structured intent/params
4. **CommandRouter** - Routes parsed NLU results (CREATE_USER, SEARCH_USER, EXPORT_EXAM, STUDENT_STATS, HELP) to handler methods
5. **SystemApiService** - Makes HTTP calls to external system API with automatic token retry
6. **DingTalkMessageService** - Sends text/file responses back to DingTalk (group or private chat)

### Key Components

- **AuthTokenHolder** - Manages two tokens: system JWT (os.zhida-keji.com.cn) and DingTalk accessToken (with 5-min refresh buffer)
- **LicenseChecker** - Validates authorization via Gitee JSON with 1-hour cache, maintains last state on network failure
- **ExcelService** - Post-processes exported Excel files by inserting a "区域" (region) column extracted from user nicknames in parentheses

### Supported Intents (via NLU)
- CREATE_USER - Batch create users with name and phone
- SEARCH_USER - Search users by nickname
- EXPORT_EXAM - Export exam records (by time range, region, or student)
- STUDENT_STATS - Statistics on new students by region
- HELP - Display help information

### Dual Mode
The bot supports both NLU mode (natural language) and direct command parsing. When `bailian.apiKey` is not configured, it falls back to提示 the user to use standard command formats.
