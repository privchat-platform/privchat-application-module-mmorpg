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

## 实现与覆盖现状(如实标注)

**20 条规则已定义,11 条有负向 fixture,`validate.py` 只实现其中可离线
判定的子集。** 其余规则依赖运行时上下文,离线校验器**原理上做不到**:

| 规则 | 负向 fixture | validate.py | 未实现的原因 |
|---|---|---|---|
| V-I1 V-I2 V-I3 | ✅ | ✅ | — |
| V-I4 | ❌ | ❌ | 需要**地图边界数据** |
| V-I5 | ❌ | ❌ | 需要**服务端序号与幂等状态** |
| V-E1 | ❌ | ✅ | 规则已实现,缺样本 |
| V-E2 V-E4 V-E5 V-E6 V-E8 V-E9 | ✅ | ✅ | — |
| V-E3 V-E7 | ❌ | ✅ | 规则已实现,缺样本 |
| V-E4(完整) | ✅ | ⚠️ 部分 | 只校验 recipient 非 0;"等于当前接收者"需要接收方身份 |
| V-S1 V-S5 | ✅ | ✅ | — |
| V-S2 | ❌ | ❌ | 需要知道调用的是 public 还是 private **端点** |
| V-S3 V-S4 | ❌ | ✅ | 规则已实现,缺样本 |
| V-S6 | ❌ | ❌ | 需要 **HTTP 解压前后**的上下文 |

**依赖运行时上下文的规则(V-I4 / V-I5 / V-E4 完整形态 / V-S2 / V-S6)
由服务端与客户端各自实现**,离线校验器只作对照基准,不承担它们。

负向 fixture 按 `<规则编号>__<描述>.bin` 命名,校验器**必须以对应编号拒绝**
—— 放过任何一条即视为它已失效(同错误码门禁的负向自检)。

---

# 战斗(MMO_BATTLE_PROTOCOL_SPEC)

同上原则:FlatBuffers 不执行的约束在此列成可执行规则,编号 V-B*,每条由负向
fixture 守护。JSON 过渡期(§15.4)同样适用——规则是语义的,不是编码的。

## 上行:BattleCommandEnvelope(MBC1)

| # | 规则 | 违反时 |
|---|---|---|
| V-BC1 | `payload` 不得为 `CommandPayload_NONE` | 拒绝 `21407 BattleCommandRejected` |
| V-BC2 | `request_id` 非空且 ≤ 64 字节 | 拒绝 `21410 BattlePayloadTooLarge` |
| V-BC3 | `phase` 必须是 `COMMAND` | 拒绝 `21401 BattlePhaseMismatch` |
| V-BC4 | `command_slot_id` 必须是服务端签发、未失效、属于 `(role_id, actor_id)` 的 slot | 拒绝 `21413` / `21414` |
| V-BC5 | payload 的指令类型必须在该 slot 的 `allowed_commands` 内 | 拒绝 `21415` |
| V-BC6 | `round` / `phase_version` 必须与服务端一致 | 拒绝 `21402` / `21403` |
| V-BC7 | `action_seq` 必须大于该 slot 的 `accepted_action_seq` | 拒绝 `21416` |
| V-BC8 | 幂等矩阵(spec §4.3):同 `request_id` 载荷同 → 回放;载荷不同 → `21411`;同 `action_seq` 内容不同 → `21412` | 见左 |

> **判定顺序冻结**:V-BC2 → 身份链(user 拥有 role,role 在战斗中,21404/21405)
> → **V-BC8 幂等命中** → V-BC3 / V-BC6 → V-BC4 / V-BC5 → V-BC7 → V-BC1。
> 幂等命中必须早于阶段与序号判定,否则合法重试会在 LOCKED 之后被当成非法提交。

## 下行:BattleEventBatchEnvelope(MBE1)

| # | 规则 | 违反时 |
|---|---|---|
| V-BE1 | 每个 `BattleEvent.payload` 不得为 `EventPayload_NONE` | 接收方丢弃整批并拉 snapshot |
| V-BE2 | `visibility == PUBLIC` 时 `recipient_role_id` 必须为 0;PRIVATE 时必须等于接收者 | 同上 |
| V-BE3 | PUBLIC 批次内**不得**出现 `CommandAccepted` / `SlotsOffered`(私有事件) | 同上——这是 spec §6.2「指令在 RESOLVE 前不得泄漏」的防线 |
| V-BE4 | `chunk_index < chunk_count`,`chunk_count >= 1`,`first_stream_seq <= last_stream_seq` | 丢弃整批 |
| V-BE5 | `events` 非空且 ≤ 128;批内 `stream_seq` 严格递增 | 丢弃整批 |
| V-BE6 | `default_action_applied` 与 `auto_played` 不得同时为 true | 丢弃整批 |

## Snapshot:BattleSnapshotEnvelope(MBS1)

| # | 规则 | 违反时 |
|---|---|---|
| V-BS1 | public 端点返回的快照 `recipient_role_id == 0`,且 `open_slots` / `submitted_commands` / `private_actor_states` 为空 | 视为服务端缺陷(视野泄露) |
| V-BS2 | private 端点返回的快照 `recipient_role_id` 等于路径中的 `role_id` | 视为服务端缺陷 |
| V-BS3 | `actors` 必填(可为空数组);`hp_percent` / `mp_percent` ∈ 0..100 | 视为响应损坏 |
| V-BS4 | 解压后 ≤ 1 MiB | 客户端拒绝解压 |
