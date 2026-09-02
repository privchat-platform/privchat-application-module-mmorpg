package init

import neton.core.annotations.Module

/**
 * mmorpg 模块声明锚点。
 *
 * KSP ModuleInitializerProcessor 据此生成
 * `neton.module.mmorpg.generated.MmorpgModuleManifest`，并按约定 FQN 探测到
 * [MmorpgRuntimeBootstrap]，在装配阶段调用它——transfer handler 的注册就发生在
 * 那里，模块不需要 application 侧写任何一行接线代码。
 *
 * `dependsOn = ["privchat"]`：本模块要从 ctx 取 `PrivChatTransferServiceRegistry`，
 * 必须等 module-privchat 先把它绑好。
 *
 * `migrations = false`：v1 只打通链路，没有自己的表。
 * `privchat_business_service(name='mmorpg')` 与 channel 绑定属于 privchat 侧的
 * 数据，由运维/那边的迁移负责，不是本模块的 owner 范围。
 *
 * id 省略：取 build.gradle.kts 的 `ksp { arg("neton.moduleId", "mmorpg") }`。
 */
@Module(dependsOn = ["privchat"], migrations = false)
object MmorpgModule
