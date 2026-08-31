# privchat-mmorpg-protocol

MMORPG 的 FlatBuffers 协议 schema。**语义规范在
`privchat-docs/spec/07-application/`**(战斗:`MMO_BATTLE_PROTOCOL_SPEC`,
场景:`MMO_WORLD_SCENE_SPEC`),本仓是它们的**可执行形式**。

## 为什么不放进 privchat-protocol

`privchat-protocol` 是核心通信协议,**不得依赖 MMO 业务**。
`privchat-sdk` 与 `privchat-godot` 只搬运 `Vec<u8>` / `PackedByteArray`,
不认识这里的任何 schema。

## 每个 root 独立成文件(强制)

`root_type` 与 `file_identifier` 是**文件级**声明 —— 同一 `.fbs` 里写多个 root,
`flatc` 只生成最后一个的 getter / identifier / verifier,**前面的静默丢失**
(实测过,不是理论风险)。故:

```text
schemas/
  scene_common.fbs        无 root:enum 与共享 table
  scene_move_intent.fbs   MoveIntentEnvelope        MMI1
  scene_move_ack.fbs      MoveIntentAck             MMA1
  scene_event.fbs         SceneEventBatchEnvelope   MSE1
  scene_snapshot.fbs      SceneSnapshotEnvelope     MSS1
```

## 生成

```bash
./scripts/generate.sh [输出目录]     # 默认 ./generated
```

脚本逐个生成 C++ 与 Kotlin,并**断言四个 identifier 都存在** ——
这条断言就是防止有人把 root 合并回一个文件。

## 消费方

| 生成物 | 去向 |
|---|---|
| Kotlin | `module-mmorpg` |
| C++ | Menghuan 的**游戏专用** GDExtension(不进 privchat-godot) |

## 语义校验

FlatBuffers 只保证结构合法 —— union 可以是 `NONE`、`required` 管不到 union、
"visibility 与 payload 的搭配"它无从知晓。这些约束列在
[`schemas/VALIDATION.md`](schemas/VALIDATION.md),由
[`scripts/validate.py`](scripts/validate.py) **执行**(不是只写在文档里):

```bash
python3 scripts/validate.py --fixtures      # 跑全集
python3 scripts/validate.py event x.bin     # 校验单个样本
```

`generate.sh` 会自动带上这一步。

**负向 fixture 是校验器的自证**:`fixtures/scene/v1/invalid/` 下每个样本按
`<规则编号>__<描述>.bin` 命名,校验器**必须**以对应规则拒绝它 ——
放过任何一个即视为校验器失效。

## 待补

- 战斗协议的 `.fbs`(`MMO_BATTLE_PROTOCOL_SPEC` §13 已有候选 IDL);
- Kotlin / C++ 侧的 validator 实现(当前只有 Python 参考实现,
  它是三端的对照基准,不是运行时校验);
- 跨语言 round-trip(Kotlin 编码 → C++ 解码,反之亦然);
- CI 接入(当前 `generate.sh` 只能手工跑)。
