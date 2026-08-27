# 本地 Docker 部署（电力监控告警推送平台）

一条命令拉起整套依赖（MySQL + Redis + Kafka）和应用本身。

## 组成

| 服务   | 镜像                                             | 端口（宿主机） | 作用 |
|--------|--------------------------------------------------|----------------|------|
| mysql  | `docker.m.daocloud.io/library/mysql:8.0`          | 3307           | 遗留推送内核表（`t_msg_*`、限额表）由 `sql/msgcenter.sql` 初始化；电力域 `pg_*` 表由应用启动时的 Flyway（`V2`）自动创建。宿主机映射到 3307，避开本机已被 DataGrip SSH 隧道占用的 3306 |
| redis  | `docker.m.daocloud.io/library/redis:7-alpine`     | 6379           | 限流、分布式锁（Leader 选举）、定时消息 ZSet |
| kafka  | `docker.m.daocloud.io/apache/kafka:3.7.0`（官方镜像） | 仅内网      | 单节点 KRaft（无 ZooKeeper）；默认 `mysql-as-mq=true`，Kafka 为可选链路 |
| app    | 由 `Dockerfile` 构建                              | 8082           | 电力遥测模拟 + 告警规则引擎 + 多渠道推送内核 |

镜像统一走 `docker.m.daocloud.io` 镜像加速前缀，因为部分网络环境下 `docker.io` 直连会超时；如果你的机器能直连 Docker Hub，也可以把 `docker-compose.yml` / `Dockerfile` 里的前缀去掉。

## 前置条件

- 安装 Docker Desktop（含 Docker Compose v2）。
- 无需本地安装 JDK/Maven：镜像内多阶段构建会自动编译（父 POM(-N) → `bitstorm-svr-api` → `bitstorm-svr-common` → 本模块，按依赖顺序）。

## 启动

```bash
cd bitstorm-svr-msgcenter/deploy
cp .env.example .env        # 按需修改密码
docker compose up -d --build
```

首次构建会下载 Maven 依赖，耗时较长。启动完成后 `app` 健康检查通过（约 40s 起）。

查看状态与日志：

```bash
docker compose ps
docker compose logs -f app
```

## 验证

健康检查：

```bash
curl -fsS http://localhost:8082/actuator/health
```

触发一次高并发电力遥测模拟（内存告警管道，无需外部依赖）：

```bash
curl -s "http://localhost:8082/api/powergrid/simulate?substations=20&meters=100&threads=8&seconds=5" | jq
```

返回中包含吞吐量（QPS）与告警统计（raised/updated/active），可直接用于性能报告。

### 可视化面板

浏览器打开 `http://localhost:8082/` 即是一个只读演示面板（`src/main/resources/static`，用开源组件
[Tabler](https://github.com/tabler/tabler)（CDN 引入，零构建）+ [Chart.js](https://github.com/chartjs/Chart.js)
搭的单页面）：表单触发模拟、指标卡展示 QPS/告警统计、按严重度的活跃告警图、最近告警事件表、
`pg_alert_notification` 推送台账表（SENT/FAILED 一目了然）。数据来自 `/api/powergrid/alerts`
与 `/api/powergrid/notifications` 两个新只读接口，与 `/api/powergrid/simulate` 一样全部开放无需登录。

### 打通全流程：模拟越限 → 落库 → 自动推送飞书

加上 `persist=true`（落库到 `pg_alert_event`/`pg_alert_notification`）与 `notify=true`（把每个新增/恢复的告警经推送内核发到飞书）即可跑通端到端链路：

```bash
curl -s "http://localhost:8082/api/powergrid/simulate?substations=2&meters=2&threads=4&seconds=3&persist=true&notify=true" | jq
```

返回里的 `alertsPushed` 即真实发出的告警数（对应飞书群里收到的消息）。链路要点：

- 每条遥测过规则引擎，只有**新增(RAISE)/恢复(RECOVER)** 才落库并推送；重复越限(UPDATE)仅内存计数，所以一场百万级读数的告警风暴会被 `active_key` 唯一索引收敛成**一个** incident、只推**一次**。
- 落库后写 `pg_alert_notification`（幂等键 `eventId:LARK:RAISE`），只有抢到该键的调用才真正调 `SendMsgService.SendMsg`，经 `t_msg_queue` → 消费者 → `LarkServiceImpl` → 飞书 webhook 投递；`at-least-once` 也不会重复推送。
- 首次调用会自动创建并激活飞书告警模板（`name=电网告警-飞书`，`sourceId=powergrid-alert`，channel=3）。
- 该 `sourceId` 在 `sql/msgcenter.sql` 里配了 2000 条/秒的 source 级配额（`t_source_quota`），既保留限流机制、又能让告警突发真正发出；channel 3 的全局默认仍是 1 条/秒以演示限流。
- 落库路径的机队规模被限制得较小（≤20 变电站/≤20 电表），避免真实推送与写库被打爆。

> 想反复演示投递效果时，可先清掉历史告警行（`DELETE FROM pg_alert_notification; DELETE FROM pg_alert_event;`），否则上一轮仍处于 ACTIVE 的 incident 会被幂等机制正确地跳过、不再重复推送。

调用受保护的遗留推送 API（HTTP Basic，默认账号见 `.env` 的 `OPS_USERNAME`/`OPS_PASSWORD`）：

```bash
# 1. 建模板（channel: 1=邮件 2=短信 3=Lark），name 必填
curl -u operator:powergrid-demo -X POST http://localhost:8082/msg/create_template \
  -H 'Content-Type: application/json' \
  -d '{"name":"告警短信模板","channel":2,"sourceId":"demo","subject":"电网告警","content":"[告警] ${device} ${metric}越限，当前值${value}"}'

# 2. 激活模板（新建默认是 PENDING=1，需置为 NORMAL=2 才能发送）
curl -u operator:powergrid-demo -X POST http://localhost:8082/msg/update_template \
  -H 'Content-Type: application/json' \
  -d '{"templateId":"<上一步返回的 data>","name":"告警短信模板","channel":2,"sourceId":"demo","subject":"电网告警","content":"[告警] ${device} ${metric}越限，当前值${value}","status":2}'

# 3. 发送（priority: 1=low 2=middle 3=high 4=retry）
curl -u operator:powergrid-demo -X POST http://localhost:8082/msg/send_msg \
  -H 'Content-Type: application/json' \
  -d '{"to":"13800000000","subject":"电网告警","priority":3,"templateId":"<templateId>","templateData":{"device":"1号变电站-主变T1","metric":"温度","value":"92°C"}}'
```

> 注意：`EmailServiceImpl` 走真实 SMTP（配置 `POWERGRID_EMAIL_*`）。`SMSServiceImpl`/`LarkServiceImpl` 默认都是日志打点，但都已经接好了真实通道——短信见下方「接通腾讯云真实短信」，飞书见下方「接通飞书机器人」；未配置时自动回退为日志打点，不影响本地零依赖运行。

### 接通飞书机器人（推荐先做，最简单）

飞书自定义机器人不需要企业认证、不需要模板审核，几分钟就能跑通：

1. 打开飞书 App（手机或桌面端均可），进入一个群聊（没有的话新建一个，自己一个人的群也行）。
2. 群设置 -> 群机器人 -> 添加机器人 -> 自定义机器人，起个名字（如"电网告警"），点击「添加」。
3. 复制生成的 **Webhook 地址**（形如 `https://open.feishu.cn/open-apis/bot/v2/hook/xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx`）。
4. 把地址填进 `deploy/.env`：`LARK_WEBHOOK_ENABLED=true`、`LARK_WEBHOOK_URL=<你复制的地址>`。
   （如果在第 2 步给机器人开启了「签名校验」，还要把对应的 Secret 填进 `LARK_WEBHOOK_SECRET`。）
5. `docker compose up -d app` 重启即可，`send_msg` 时 `channel:3` 会真实推送到这个群。

### 接通腾讯云真实短信

1. 打开[短信控制台](https://console.cloud.tencent.com/smsv2)，完成实名认证（个人认证只能在控制台手动申请签名/模板，无法走 API）。
2. 在[访问管理 API 密钥管理](https://console.cloud.tencent.com/cam/capi)里创建 SecretId / SecretKey。
3. 在短信控制台里：新建「应用」拿到 `SmsSdkAppId`（格式 `1400xxxxxx`）；申请「签名」；申请「正文模板」（如 `{1}为您的登录验证码，请于{2}分钟内使用`），等审核通过拿到模板 ID。
4. 把以上 5 项填进 `deploy/.env`：`SMS_TENCENT_ENABLED=true`、`SMS_TENCENT_SECRET_ID`、`SMS_TENCENT_SECRET_KEY`、`SMS_TENCENT_SDK_APP_ID`、`SMS_TENCENT_SIGN_NAME`、`SMS_TENCENT_TEMPLATE_ID`。
5. `docker compose up -d app` 重启即可。`send_msg` 请求里 `templateData` 的取值会按插入顺序映射成腾讯短信模板里的 `{1}`、`{2}`……，顺序需要和你申请的模板占位符顺序保持一致；`to` 字段若不带国家码会自动补 `+86`。

## 停止 / 清理

```bash
docker compose down          # 停止并删除容器（保留数据卷）
docker compose down -v       # 同时删除 MySQL / Kafka 数据卷
```

## 说明

- `POWERGRID_*` 环境变量在 `docker-compose.yml` 中注入，覆盖 `application.yml` 的默认值。
- 数据库 schema 分两处：遗留表来自 `sql/msgcenter.sql`（MySQL 初始化脚本），电力 `pg_*` 表来自 Flyway 迁移 `V2__create_powergrid_alerting.sql`（应用启动时执行，`baseline-on-migrate` 已开启，可在已有遗留表之上安全建立基线）。
- 默认 `send-msg-conf.mysql-as-mq=true`，因此应用启动不强依赖 Kafka；如需走 Kafka 推送链路，将其设为 `false` 并重启 `app`。
- `/msg/**`、`/api/powergrid/**` 均是纯 JSON 接口（HTTP Basic 逐请求认证，不建立浏览器会话），因此 CSRF 保护已整体关闭（`SecurityConfiguration#csrf().disable()`）。
- Flyway 8.5.13 自 8.2 起把 MySQL 方言拆到独立的 `flyway-mysql` 依赖里，`pom.xml` 已同步加上，否则会在启动时报 `Unsupported Database: MySQL 8.0`。
