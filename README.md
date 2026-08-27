# spring boot 模板服务

构建子服务项目模板

## 接入能力

* spring cloud nacos 服务注册
* spring cloud nacos 配置中心
* spring cloud open feign 服务通信
* lombok 简化开发

## 模板使用

1. 复制`bitstorm-svr-demo`修改项目名为新项目名称(建议本地文件夹复制，不要在idel中复制)
2. 修改新项目`pom.xml`文件`artifactId`节点为新项目名称 
3. 修改根项目`pom.xml`文件`modules`节点，加入新项目
4. 修改`package`包名，包括 import 的包名 
5. 修改`启动类名称`

## 电力监控告警推送平台（唯一主线）

本服务是一个电力设备遥测监控与告警推送平台：模拟电网设备遥测 → 阈值规则引擎（持续时间/回差/去重）
判定 → 多渠道告警推送内核（优先级 Topic、幂等、限流、重试）。

- 电力域代码：`cn.bitoffer.msgcenter.powergrid.*`（领域模型、规则引擎、真实模拟器、并发驱动、内存告警管道）。
- 推送内核（复用）：`consumer/`、`manager/`、`redis/`、`mapper/`（MySQL 优先级队列、`FOR UPDATE SKIP LOCKED`
  抢占、Leader 选举、Redis 限流、定时消息两级存储）。
- 高并发模拟入口：`GET /api/powergrid/simulate?threads=..&durationSeconds=..&injectFaults=true`
  （返回吞吐 QPS 与告警统计，可直接用于性能报告）。详见 `docs/powergrid-simulator.md`。

## 安全说明

- `/api/powergrid/**` 为自包含的负载/演示端点（无数据副作用），开放且免 CSRF，便于压测。
- `/actuator/health` 开放，供容器编排健康检查。
- 遗留 `/msg/**` 推送 API 使用 HTTP Basic 保护，凭据来自环境变量的内存用户（无用户表）：
  用户名 `POWERGRID_OPS_USERNAME`（默认 `operator`），密码 `POWERGRID_OPS_PASSWORD`（默认 `powergrid-demo`）。

## 本地 Docker 部署

见 [`deploy/README.md`](deploy/README.md)：`cd deploy && cp .env.example .env && docker compose up -d --build`
即可拉起 MySQL + Redis + Kafka + 应用。