# 本地 Docker 部署

一条命令拉起 MySQL、Redis、Kafka 和云擎应用。

## 组成

| 服务 | 镜像 | 宿主机端口 | 作用 |
|------|------|------------|------|
| mysql | `docker.m.daocloud.io/library/mysql:8.0` | 3307 | `t_msg_*` 表由 `sql/msgcenter.sql` 初始化 |
| redis | `docker.m.daocloud.io/library/redis:7-alpine` | 6379 | 模板 / 限额缓存、分布式锁、定时消息 ZSet |
| kafka | `docker.m.daocloud.io/apache/kafka:3.7.0` | 仅内网 | 单节点 KRaft；`MYSQL_AS_MQ=true` 时可不走 Kafka |
| app | 本仓库 `Dockerfile` | 8082 | 消息中台 + 控制台 |

镜像走 `docker.m.daocloud.io` 加速前缀。能直连 Docker Hub 时可自行去掉。

MySQL 映射到 3307，避免和本机 3306 冲突。

## 前置条件

- Docker Desktop（Compose v2）
- 构建上下文是父工程根目录，需同时有 `bitstorm-svr-api`、`bitstorm-svr-common` 和本模块
- 不必本机安装 JDK / Maven，镜像内会编译

## 启动

```bash
cd bitstorm-svr-msgcenter/deploy
cp .env.example .env
docker compose up -d --build
```

首次会拉依赖，时间较长。`app` 健康检查通过后即可访问。

```bash
docker compose ps
docker compose logs -f app
curl -fsS http://localhost:8082/actuator/health
```

控制台：http://localhost:8082/

公开仓库只提供 `.env.example`。真实密钥写在 `.env`，该文件已被 git 忽略。

同机已有反向代理占用 80/443 时，使用 overlay：

```bash
docker compose -f docker-compose.yml -f docker-compose.server.yml up -d
```

先把 `.env.server.example` 复制为 `.env`，按本机反向代理改 `CONTEXT_PATH`、`APP_PUBLISH`，不要把真实密码提交进 git。

## 控制台

静态页在 `src/main/resources/static`。未登录可看总览空态；登录后可：

- 业务模拟：按账户 / 交易 / 营销 / 系统四条线批量入队
- 消息记录：查看投递状态
- 模板配置、发送与定时

默认账号来自 `.env` 的 `OPS_USERNAME` / `OPS_PASSWORD`。

## 调用发送接口

```bash
# 创建模板 channel: 1=邮件 2=短信 3=飞书
curl -u operator:powergrid-demo -X POST http://localhost:8082/msg/create_template \
  -H 'Content-Type: application/json' \
  -d '{"name":"订单通知","channel":2,"sourceId":"biz-trade","subject":"订单通知","content":"【订单通知】${event}\n订单号：${orderNo}"}'

# 激活（status=2）
curl -u operator:powergrid-demo -X POST http://localhost:8082/msg/update_template \
  -H 'Content-Type: application/json' \
  -d '{"templateId":"<templateId>","name":"订单通知","channel":2,"sourceId":"biz-trade","subject":"订单通知","content":"【订单通知】${event}\n订单号：${orderNo}","status":2}'

# 发送 priority: 1=低 2=中 3=高
curl -u operator:powergrid-demo -X POST http://localhost:8082/msg/send_msg \
  -H 'Content-Type: application/json' \
  -d '{"to":"13800000000","priority":3,"templateId":"<templateId>","templateData":{"event":"支付成功","orderNo":"T20260827001"}}'
```

## 接通飞书

1. 飞书群 → 群机器人 → 添加自定义机器人，复制 Webhook
2. 写入 `.env`：`LARK_WEBHOOK_ENABLED=true`、`LARK_WEBHOOK_URL=...`（开了签名校验再填 `LARK_WEBHOOK_SECRET`）
3. `docker compose up -d app`，`channel:3` 的消息会打到该群

## 接通腾讯云短信

1. [短信控制台](https://console.cloud.tencent.com/smsv2) 完成实名、应用、签名、正文模板
2. [API 密钥](https://console.cloud.tencent.com/cam/capi) 创建 SecretId / SecretKey
3. 写入 `.env`：`SMS_TENCENT_ENABLED=true` 以及 SecretId、SecretKey、SdkAppId、签名、模板 ID
4. `docker compose up -d app`。`templateData` 按插入顺序对应腾讯模板 `{1}` `{2}`；手机号无国家码时补 `+86`

邮件走 SMTP，在 `.env` 填 `EMAIL_ENABLED` 与账号授权码。未开启时三个渠道都只打日志。

## 队列切换

`.env` 中 `MYSQL_AS_MQ=true`（默认）走 MySQL 队列表；`false` 走 Kafka Topic。改完重启 `app`。

## 停止

```bash
docker compose down          # 保留数据卷
docker compose down -v       # 同时删除 MySQL / Kafka 数据
```

## 说明

- Compose 把 `.env` 注入为 `POWERGRID_*` / `MSGCENTER_*`，覆盖 `application.yml`
- 表结构由 `sql/msgcenter.sql` 初始化；应用启动时 Flyway 继续跑已有迁移
- 接口无浏览器会话，CSRF 已关闭；写接口用 HTTP Basic
