# 云擎——消息推送平台

面向多业务线的统一消息调度与通知中台。业务系统只负责「该不该通知」，中台负责模板、排队、限速与多渠道投递。接口立即返回表示消息已被可靠收下，不阻塞调用方业务流程。

内置四条演示业务线：账户安全、交易订单、营销触达、系统告警。渠道支持邮件、短信、飞书。

## 平台演示效果

![运行总览](https://github.com/default8421/bitstorm-svr-msgcenter/releases/download/screenshots/overview.png)

![发送与定时](https://github.com/default8421/bitstorm-svr-msgcenter/releases/download/screenshots/messaging.png)

如需学习沟通，请联系 Wechat：15803528241

## 架构

采用「接入层 — 中转层 — 处理层 — 存储层」分层：

| 层级 | 职责 |
|------|------|
| 接入层 | HTTP 接口与控制台：模板校验、准入、入队 |
| 中转层 | 统一消息队列接口，Kafka 与 MySQL 两种方案可切换 |
| 处理层 | 按优先级消费、配额调度、渠道投递、重试与幂等 |
| 存储层 | MySQL 持久化、Redis 缓存模板与限额、Kafka Topic |

入口只做校验、过载保护和入队。消息记录由消费侧维护：处理到哪一步就把状态写到哪一步（处理中 → 成功 / 失败 / 防打扰抑制）。

## 能力

- **多渠道投递**：策略模式接入邮件 / 短信 / 飞书，未配置真实通道时打日志，不影响本地运行
- **优先级调度**：Kafka 用独立 Topic 隔离高 / 中 / 低；MySQL 分表消费，`FOR UPDATE SKIP LOCKED` 抢占，避免低优饿死
- **限流削峰**：消费侧按平台、渠道、来源配额控制发送节奏；入口只挡过载，不在入口直接丢普通业务消息
- **可靠投递**：`acks=all` 入队、多级重试、幂等校验，避免重复真实推送
- **定时推送**：MySQL 全量 + Redis 近期待触发快照
- **租户隔离**：模板与消息记录按登录用户隔离
- **控制台**：运行总览、业务模拟、消息记录、模板配置、发送与定时

## 快速开始

需要 Docker Compose v2。镜像构建上下文是**父工程根目录**（需同时存在 `bitstorm-svr-api`、`bitstorm-svr-common` 与本模块）。

```bash
cd bitstorm-svr-msgcenter/deploy
cp .env.example .env
docker compose up -d --build
```

启动后：

- 控制台：http://localhost:8082/
- 健康检查：`curl -fsS http://localhost:8082/actuator/health`

默认运维账号见 `.env` 的 `OPS_USERNAME` / `OPS_PASSWORD`（示例为 `operator` / `powergrid-demo`）。只读总览可匿名打开，发送、模拟、改模板需要登录。

更完整的启动、渠道接通与停止说明见 [`deploy/README.md`](deploy/README.md)。

## 发送一条消息

```bash
# 1. 创建模板（channel: 1=邮件 2=短信 3=飞书）
curl -u operator:powergrid-demo -X POST http://localhost:8082/msg/create_template \
  -H 'Content-Type: application/json' \
  -d '{"name":"订单通知","channel":2,"sourceId":"biz-trade","subject":"订单通知","content":"【订单通知】${event}\n订单号：${orderNo}"}'

# 2. 激活模板（新建默认 PENDING=1，需改为 NORMAL=2）
curl -u operator:powergrid-demo -X POST http://localhost:8082/msg/update_template \
  -H 'Content-Type: application/json' \
  -d '{"templateId":"<上一步返回的 data>","name":"订单通知","channel":2,"sourceId":"biz-trade","subject":"订单通知","content":"【订单通知】${event}\n订单号：${orderNo}","status":2}'

# 3. 发送（priority: 1=低 2=中 3=高）
curl -u operator:powergrid-demo -X POST http://localhost:8082/msg/send_msg \
  -H 'Content-Type: application/json' \
  -d '{"to":"13800000000","priority":3,"templateId":"<templateId>","templateData":{"event":"支付成功","orderNo":"T20260827001"}}'
```

返回的 `data` 是 `msgId`。消息已入队即返回；`/msg/get_msg_record` 要等消费侧写出投影后才能查到状态。

## 主要接口

| 方法 | 路径 | 说明 | 认证 |
|------|------|------|------|
| POST | `/msg/send_msg` | 发送 / 定时发送 | Basic |
| GET | `/msg/get_msg_record` | 按 msgId 查记录 | Basic |
| POST/GET | `/msg/*_template` | 模板增删改查 | Basic |
| GET | `/api/hub/stats` | 运行总览 | 公开（未登录为空数据） |
| GET | `/api/hub/messages` | 最近消息 | 公开（未登录为空列表） |
| POST | `/api/hub/simulate` | 按业务线批量模拟发送 | Basic |
| POST | `/api/hub/emit` | 按业务线发送一条 | Basic |

## 队列后端

`send-msg-conf.mysql-as-mq`（环境变量 `MYSQL_AS_MQ`）：

- `true`（默认）：MySQL 队列表中转，不依赖 Kafka
- `false`：走 Kafka 的 high / middle / low / retry Topic

## 技术栈

Spring Boot 2.7、Java 17、MyBatis、MySQL、Redis、Kafka、Flyway、Spring Security（HTTP Basic）。

## 配置与密钥

真实密码、短信密钥、邮箱授权码、飞书 Webhook **不要写入仓库**。复制 `deploy/.env.example` 为 `deploy/.env` 后在本地填写。`application.yml` 只通过环境变量读取这些值。
