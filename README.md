# privchat-application-module-mmorpg

MMORPG 业务模块。

> **当前状态:场景最小闭环已实装,其余仍是设计稿。**
>
> 能跑通的链路:角色 → 进入场景 → `scene_session` + Room ticket → 订阅 Room
> → Transfer 心跳 → 收到他人进入/离开事件。契约与"哪些没实装"的清单见
> privchat-docs 的 `MMO_WORLD_SCENE_SPEC` §12。
>
> 客户端侧的验收在 `privchat-godot-demo/scripts/auto_mmo_check.gd`(headless e2e,双角色全闭环)。
>
> 已实装:`mmorpg/scene/move`(MoveTo / Stop / CancelPath,幂等窗口 + 单调序号,格子 A*
> 绕障碍)、地图数据 `mmo_map` 与 NPC `mmo_npc`、`mmorpg/scene/interact`(按权威位置判
> 交互距离)。尚未实装:AOI、场景状态机、场景↔战斗 saga、队伍跟随。
> 未实装的 route 返回 `21610 SceneCommandInvalid` 而**不是**静默成功。
>
> 编码当前全部走 JSON,收口在 `logic/codec/`。`protocol/generated/kotlin`
> **不在编译路径上**:flatc 的 Kotlin 后端只产出 JVM 绑定
> (`java.nio.ByteBuffer` / `com.google.flatbuffers.Table`),22 个文件里 19 个在
> Kotlin/Native 编不过。schema 与 fixtures 原样保留,等编码方案确定。

## 目录

```text
protocol/
  schemas/      FlatBuffers .fbs(场景已有,战斗待补)
  fixtures/     跨语言 golden fixtures 与负向样本
  scripts/      生成与语义校验
sql/            自持表的迁移(mmo_role / mmo_scene_channel / mmo_scene_session)
src/commonMain/kotlin/
  controller/   HTTP 面(enter / leave / snapshot / 角色)
  logic/scene/  场景生命周期、channel provision、仓储
  logic/codec/  线格式(今天 JSON,将来 FlatBuffers)
  logic/transfer/  Transfer handler
```

## 场景由后台开

`POST /admin/mmo/scenes {"scene_ref":"l-10023-7"}`(幂等)开出一个场景的 Room 与
dispatch 路由;`GET /admin/mmo/scenes` 列表;`POST /admin/mmo/scenes/{ref}/close` 关闭。
玩家 enter 只查:场景没开就 `21600`,不会替运营建一个。

## 服务注册

`privchat_business_service.name = "mmorpg"`(id 9200)。这一行由
**module-privchat 的迁移**声明——那张表的 owner 是 privchat,跨模块直写他人的表
会让两份迁移对同一形状各有假设。handler 则由本模块自己在 runtime bootstrap 里
注册,不由 `PrivchatRuntimeBootstrap` 注册,否则基础模块会反向依赖业务模块。

`service_id` 不出现在代码里:dispatcher 走
`channel_id → service_id → service.name → registry.find(name)`。

按 `MMO_ARCHITECTURE_SPEC`,本模块是 MMO 核心玩法与战斗的唯一 owner,
同时**自持**自己的协议与错误码:

## 为什么协议放在这里,而不是独立仓

协议的 owner 就是本模块。独立成仓会长出一个无人明确负责的中间物;
只有当它需要独立发布周期、被多个不同游戏消费时,拆分才有意义。

生成物的去向:

| 目标 | 用途 |
|---|---|
| Kotlin | 本模块服务端自用 |
| C++ header artifact | 带版本发布给 Menghuan 的**游戏专用** GDExtension |

## 错误码

本模块自持 **21400-21499**(战斗)与 **21600-21699**(场景)。
`privchat-protocol` 的 registry 只**保留**这两个段位、不登记具体码 ——
核心通信层不需要认识 `SceneNotFound` 这类玩法概念,
`TransferResponse.code` 本来就是整数,SDK 与客户端原样透传即可。

具体码登记在本仓的 `registry/error_codes.toml`。**不要就地新造码**:
registry 里记录过两次因此产生的真实冲突(20900 / 20920),代价是两个业务对同一个
数字有不同理解,而客户端只看得到数字。

## 与 privchat 各层的边界

`privchat-sdk` / `privchat-sdk-c-api` / `privchat-godot` / `privchat-godot-demo`
**只搬运二进制**,不认识这里的任何 schema。
