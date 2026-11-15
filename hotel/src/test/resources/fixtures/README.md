# fixtures

`hotel-detail-live.json`：2026-11-15 真机 `rgh hotel-detail` 输出（广州威珀斯酒店，2026-11-16 → 2026-11-17，16 个房型 / 70 个价格方案）。落盘前去掉了 CLI 的提示行与首尾噪声，只保留业务 JSON。

该样本对应 RollingGo 当前实时返回：房型只有 `averagePrice`（每晚均价），没有 `totalPrice`；取消政策是 `cancelPolicy` 文案 + `cancelable`；早餐是 `mealTypeStr`；房间信息在 `roomInfo`。用于回归解析、晚数换算与卡片/文本价格展示。
