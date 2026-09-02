package model

import kotlinx.serialization.Serializable
import neton.database.annotations.Column
import neton.database.annotations.CreatedAt
import neton.database.annotations.Id
import neton.database.annotations.Table
import neton.database.annotations.UpdatedAt

/**
 * 玩家角色。
 *
 * **不能拿 `user_id` 当角色用**：一个账号可以有多个角色，角色是场景、背包、
 * 战斗归属的主体，而账号是登录主体。把两者等同会让「换角色」和「多角色同时在线」
 * 这类基本需求在数据层就无法表达，事后再拆要动所有引用。
 */
@Serializable
@Table("mmo_role")
data class MmoRole(
    @Id
    val id: Long = 0,

    /** 账号。一个账号可有多个角色。 */
    @Column(name = "user_id")
    val userId: Long,

    val name: String,

    /** 1 = 正常，0 = 停用。 */
    val status: Int = 1,

    @CreatedAt
    val createdAt: Long? = null,

    @UpdatedAt
    val updatedAt: Long? = null,
)
