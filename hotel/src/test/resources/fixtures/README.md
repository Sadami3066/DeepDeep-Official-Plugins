# fixtures

`hotel-detail-live.json`: `rgh hotel-detail` output from a real device on 2026-11-15 for a Guangzhou hotel (2026-11-16 → 2026-11-17, 16 room types / 70 rate plans). The CLI notice lines and surrounding noise were removed before saving; only the business JSON is kept.

The sample matches what RollingGo returns live today: room types carry only `averagePrice` (nightly average) and no `totalPrice`; the cancellation policy is `cancelPolicy` text plus `cancelable`; breakfast is `mealTypeStr`; room information is in `roomInfo`. It is used to regress parsing, night conversion and price display in cards and text.
