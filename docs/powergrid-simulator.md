# 电力监控告警推送 · 真实场景模拟器

本模块把消息中心改造成 **电力系统监控告警推送中台** 的告警接入/分级/去重/推送层，并内置一个
**真实电力场景的遥测模拟器**，可产生真实并发数据用于压测与演示。

> 定位边界：本平台是「告警通知/推送」这一层（接入 → 分级 → 去重抑制 → 关联 → 多渠道推送/看板）。
> 真正的跳闸/继电保护属于硬实时回路，由保护装置与 SCADA 完成，**不**经过本平台。这条边界在面试里要主动讲清楚。

## 真实性从哪来

- **设备台账贴近变电站实际**（`GridTopologyFactory`）：每座变电站包含主变、线路馈线、断路器、母线、GIS、
  电容器组、环境传感器，以及大量智能电表（用采测点，数量主导上报量）。
- **量测量与工程单位真实**（`MetricType` / `SignalCatalog`）：油温/绕组温度、负荷电流、母线电压、有功功率、
  SF6 气压、局部放电、频率、环境温度，基线与量纲取自典型值。
- **日负荷曲线**（`DiurnalLoadCurve`）：负荷因子按真实电网需求曲线随时间起伏——夜间低谷（约 04:00）、
  早高峰（约 10:00）、更高的晚高峰（约 20:00）。电流/功率/温度都跟随负荷"呼吸"，而非平铺噪声。
- **故障注入与告警风暴**（`FaultScenario` / `TelemetrySimulator`）：过压、SF6 泄漏、局放骤升、馈线过流、
  主变过温；母线故障以"风暴"形式在同一时刻扇出到多台设备（共享 `correlationKey`），供下游做去重/关联。

## 高并发 / 可靠性设计点（面试可讲）

- **优先级隔离**：告警级别映射到 LOW/MIDDLE/HIGH 优先级（`Severity`），紧急故障不会被低优先级噪声饿死。
- **告警去重（storm dedup）**：规则引擎（`RuleEvaluator`）对同一 `(设备,指标)` 的持续越限只 RAISE 一次、
  其余 UPDATE；数据库侧由 `pg_alert_event.active_key` 生成列唯一索引强制「同设备同指标至多一个未恢复告警」。
- **持续时间抑制 + 迟滞恢复**：慢变量（温度/电流）需持续越限一段时间才告警（抑制毛刺）；恢复需回落过
  `clear_threshold`（抑制抖动/反复告警）。
- **无锁并发**：`RuleEvaluator` 用 `ConcurrentHashMap#compute` 做每键原子状态机；`AlertPipelineSink` 用
  `LongAdder` 计数；并发压测证明多线程同时打同一越限批次仍只产生一个未恢复告警（`AlertPipelineSinkTest`）。
- **可复用的可靠性内核（推送层）**：Kafka 分级优先级 Topic、`FOR UPDATE SKIP LOCKED` 队列抢占、
  Redis 分布式锁 Leader 选举、Redis 原子限流（Lua）、定时消息两级存储、有界线程池 + 优雅停机——沿用工程既有实现
  （`consumer/`、`manager/`、`redis/`、`mapper/`）。告警落库 + 幂等推送链路已打通（见下「全流程」）；
  Outbox 事务发布 → 分级 Topic + DLT 的强可靠增量为后续。

## 如何运行（产生真实并发数据）

本地一键起全套依赖（MySQL + Redis + Kafka + 应用），见 [`../deploy/README.md`](../deploy/README.md)：

```bash
cd deploy && cp .env.example .env && docker compose up -d --build
```

模拟服务本身不依赖任何外部组件（纯内存管道），应用启动后按需触发即可：

```bash
# 20 座变电站、每座 100 个电表、8 线程、跑 15 秒、全速压测（targetQps=0）
curl -X POST 'http://localhost:8082/api/powergrid/simulate?substations=20&meters=100&threads=8&seconds=15'

# 指定目标 QPS 做限速压测
curl -X POST 'http://localhost:8082/api/powergrid/simulate?substations=50&meters=200&threads=16&seconds=20&targetQps=200000'
```

返回 JSON 示例：

```json
{
  "fleetSize": 3160,
  "threads": 8,
  "targetQps": 0,
  "durationSeconds": 15,
  "totalReadings": 210034512,
  "elapsedMillis": 15002,
  "achievedQps": 14000167,
  "alertsRaised": 84,
  "alertsUpdated": 20873411,
  "alertsRecovered": 0,
  "activeIncidents": 84
}
```

### 参数说明

| 参数 | 默认 | 含义 |
|------|------|------|
| `substations` | 20 | 变电站数量（上限 1000） |
| `meters` | 100 | 每座变电站的智能电表数（上限 2000，用于放大上报量） |
| `threads` | 8 | 并发生产线程数（上限 128） |
| `seconds` | 15 | 压测时长（上限 120） |
| `targetQps` | 0 | 全局目标吞吐；`0` 表示全速跑以测峰值 |

> 提示：短时压测（<10s）主要触发「立即型」故障（过压/SF6/局放）；`seconds≥10` 才会触发过流（10s 持续），
> `seconds≥20` 才会触发过温（20s 持续）——这正好演示了「持续时间抑制」。

## 关键代码位置

- 领域模型：`cn.bitoffer.msgcenter.powergrid.domain`
- 规则引擎：`cn.bitoffer.msgcenter.powergrid.rule`（`RuleEvaluator` / `RuleCatalog`）
- 模拟器：`cn.bitoffer.msgcenter.powergrid.simulator`（`DiurnalLoadCurve` / `SignalCatalog` /
  `TelemetrySimulator` / `GridTopologyFactory` / `SimulationDriver`）
- 管道/编排：`cn.bitoffer.msgcenter.powergrid.pipeline`（`AlertPipelineSink` / `FaultScenario` /
  `SimulationService`）
- REST 入口：`cn.bitoffer.msgcenter.powergrid.web.PowerGridSimulationController`
- 库表：`src/main/resources/db/migration/V2__create_powergrid_alerting.sql`

## 全流程（已打通）

`POST /api/powergrid/simulate?persist=true&notify=true` 走「持久化告警管道」`PersistingAlertSink`：
规则评估 → `pg_alert_event` 落库（`active_key` 去重，风暴收敛成一个 incident）→ 幂等
`pg_alert_notification`（键 `eventId:LARK:RAISE`）→ `SendMsgService.SendMsg` 经 `t_msg_queue` →
消费者 → `LarkServiceImpl` → 飞书 webhook 真实投递。首次调用自动创建并激活飞书告警模板
（`sourceId=powergrid-alert`，channel=3，配 2000/s 的 source 级限流）。核心桥接见
`powergrid/notify/AlertNotifier` + `powergrid/pipeline/PersistingAlertSink`，用例见 `AlertNotifierTest`。

内存基准管道 `AlertPipelineSink`（`persist=false`）保留，用于可复用的高吞吐基准。

## 下一步（强可靠增量）

Outbox 事务发布 → Kafka 分级 Topic + DLT 的严格 exactly-once 链路为后续增量；所需 MySQL/Kafka/Redis 已由
`deploy/docker-compose.yml` 提供，可直接在其上联调与集成测试。
