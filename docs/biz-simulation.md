# 业务模拟

控制台「业务模拟」对应 `/api/hub/simulate`，用来给中台灌入演示流量，不是电力遥测引擎。

四条业务线（`BizSource`）各自有来源、渠道、优先级和模板：

| sourceId | 名称 | 默认渠道 | 优先级 |
|----------|------|----------|--------|
| `biz-account` | 账户安全 | 短信 | 高 |
| `biz-trade` | 交易订单 | 短信 | 中 |
| `biz-marketing` | 营销触达 | 邮件 | 低 |
| `biz-system` | 系统告警 | 飞书 | 高 |

首次模拟会按租户自动建好并激活对应模板。发送仍走 `/msg/send_msg` 同一套入队与消费链路。

```bash
# 登录后批量模拟 60 条（需 HTTP Basic）
curl -u operator:powergrid-demo -X POST \
  "http://localhost:8082/api/hub/simulate?count=60&includeLark=false"
```

`includeLark=true` 才会走系统告警（飞书）那条线。未配置飞书 Webhook 时该渠道只打日志。

单条发送：

```bash
curl -u operator:powergrid-demo -X POST http://localhost:8082/api/hub/emit \
  -H 'Content-Type: application/json' \
  -d '{"source":"biz-trade","to":"13800000000","data":{"event":"支付成功","orderNo":"T001"}}'
```
