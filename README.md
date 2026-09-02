# privchat-application-module-mmorpg

MMORPG 业务模块。

> **当前状态:可运行的链路验证 demo,不是 MMORPG 业务实现。**
>
> 模块只注册一个诊断 handler(`mmorpg/diagnostic/echo`),把收到的字节原样回传
> 并附一份身份摘要。它验证的是「字节与调用者身份能否穿过 privchat-server →
> ServerEvent dispatch → 两级路由 → 本模块 → 原路返回」,**不实现任何玩法**。
>
> 正式的 scene / battle 协议(`scene_session_id`、`movement_seq`、`MoveCommand`、
> 幂等、权威位置、AOI)见 privchat-docs 的 `MMO_*_SPEC`,尚未实现。诊断 route
> 与正式 route 刻意分开,正式实现落地时删除
> `MmorpgDiagnosticEchoHandler` 与错误码 21699 即可。
>
> `protocol/generated/kotlin` **不在编译路径上**:flatc 的 Kotlin 后端只产出 JVM
> 绑定(`java.nio.ByteBuffer` / `com.google.flatbuffers.Table`),22 个文件里 19 个
> 在 Kotlin/Native 编不过。schema 与 fixtures 原样保留,等编码方案确定。

按 `MMO_ARCHITECTURE_SPEC`,本模块是 MMO 核心玩法与战斗的唯一 owner,
同时**自持**自己的协议与错误码:

```text
protocol/
  schemas/      FlatBuffers .fbs(场景已有,战斗待补)
  fixtures/     跨语言 golden fixtures 与负向样本
  scripts/      生成与语义校验
src/            业务实现(待建)
```

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

## 与 privchat 各层的边界

`privchat-sdk` / `privchat-sdk-c-api` / `privchat-godot` / `privchat-godot-demo`
**只搬运二进制**,不认识这里的任何 schema。
