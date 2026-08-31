# 语义校验规则

FlatBuffers 只保证**结构**合法,以下约束它一概不执行 —— union 可以是
`NONE`、`required` 管不到 union、"visibility 与 payload 的搭配"更是它无从知晓。
写在注释里的"强制矩阵"生成代码不会执行,所以在此列成**可执行规则**:
每条都有编号,发送方与接收方各自实现,并由负向 fixture 守护。

## 上行:MoveIntentEnvelope(MMI1)

| # | 规则 | 违反时 |
|---|---|---|
| V-I1 | `command` 不得为 `MoveCommand_NONE` | 拒绝 `21610 SceneCommandInvalid` |
| V-I2 | `request_id` 非空且 ≤ 64 字节 | 拒绝 `21608` |
| V-I3 | `scene_session_id` 必须非 0 且属于请求者 | 拒绝 `21601` |
| V-I4 | `MoveTo.target_position` 必须在地图边界内 | 拒绝 `21603` |
| V-I5 | `movement_seq` 必须大于该 session 已接受的最大值 | 拒绝 `21605` |

> **V-I5 必须在幂等命中之后判**。顺序反了会把合法的重试
> (同 `request_id` + 同规范化载荷)判成 stale:重试用的正是原序号,
> 它当然不大于已接受的最大值。正确顺序:
>
> ```text
> 1. 查 request_id → 命中且载荷相同 → 直接回放首次 ACK,**不再走 V-I5**
> 2. 命中但载荷不同 → 拒绝 21606 SceneIdempotencyKeyReuse
> 3. 未命中 → 才执行 V-I5 的序号比较
> ```

## 下行:SceneEventBatchEnvelope(MSE1)

| # | 规则 | 违反时 |
|---|---|---|
| V-E1 | 每个 `SceneEvent.payload` 不得为 `SceneEventPayload_NONE` | 接收方**丢弃整批**并拉 snapshot |
| V-E2 | `visibility == PUBLIC` 时,payload **只能**是 `PublicSceneChanged` | 同上 —— 这是**视野泄露**防线 |
| V-E3 | `visibility == PUBLIC` 时 `recipient_role_id` 必须为 0 | 同上 |
| V-E4 | `visibility == PRIVATE` 时 `recipient_role_id` 必须非 0 且等于接收者 | 同上 |
| V-E5 | `visibility == PRIVATE` 时 payload **不得**是 `PublicSceneChanged` | 同上 —— 公共状态只走公共流,否则同一状态出现在两条水位里,客户端无法判断以哪条为准 |
| V-E6 | `chunk_index < chunk_count`,且 `chunk_count >= 1` | 丢弃整批 |
| V-E7 | `first_stream_seq <= last_stream_seq` | 丢弃整批 |
| V-E8 | `events` 非空,数量 ≤ 128 | 丢弃整批 |
| V-E9 | `AoiRebase.entities` 要么为空、要么与 `entity_ids` 等长 | 丢弃整批 |

## Snapshot(MSS1)

| # | 规则 | 违反时 |
|---|---|---|
| V-S1 | `body` 不得为 `SnapshotBody_NONE` | 视为响应损坏,重试或报错 |
| V-S2 | public 端点**必须**返回 `PublicSnapshot`;private 端点必须返回 `PrivateSnapshot` | 视为服务端缺陷 |
| V-S3 | `common.public_states` 必填(可为空数组,但字段必须存在) | 同上 |
| V-S4 | `PrivateSnapshot.self_entity` 必填 | 同上 |
| V-S5 | `aoi_entities` **不得**包含 `self_entity.entity_id` | 同上 |
| V-S6 | 解压后 ≤ 1 MiB | 客户端拒绝解压 |

## 负向 fixture 覆盖

`fixtures/scene/v1/invalid/` 下每条规则至少一个样本;
`scripts/generate.sh --validate` 会断言校验器对它们**全部返回失败** ——
校验器若放过任何一条,即视为它已失效(同错误码门禁的负向自检)。
