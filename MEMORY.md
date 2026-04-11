# Project Memory

## Facts（事实）

- [2026-04-11] Java 版本：17，Spring Boot 3.5.13 — 来源：pom.xml
- [2026-04-11] mvnw 脚本默认无执行权限，需 `chmod +x mvnw` 才能运行 — 来源：对话回顾
- [2026-04-11] fastjson2 通过 shade 包引入，import 路径为 `shade.com.alibaba.fastjson2.*` — 来源：现有代码
- [2026-04-11] 系统 API 地址：`https://os.zhida-keji.com.cn/api`，登录账号 `19513764334`/`zdlb` — 来源：步骤.txt
- [2026-04-11] 钉钉应用 AppKey：`ding3wlhmzygb3m67t3i` — 来源：DingDingMain.java

## Rules（规则）

- [2026-04-11] 创建用户时省份固定为"新疆省"、站点"乌鲁木齐站"、级别"中级监控"、密码"123456" — 来源：用户要求
- [2026-04-11] Token 策略：先用已有 token，失效才重新登录，不每次都登录 — 来源：用户要求
- [2026-04-11] 删除用户需先搜索拿 userId 再删除，搜索到多条时告知用户提供更精确的名称 — 来源：用户要求

## Lessons（教训）

- [2026-04-11] 钉钉机器人群消息需用 `openConversationId`（非 `conversationId`）调 `/v1.0/robot/groupMessages/send`，发消息 Header 用 `x-acs-dingtalk-access-token` 而非 `Authorization` — 来源：对话回顾
