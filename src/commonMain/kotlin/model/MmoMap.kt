package model

import kotlinx.serialization.Serializable
import neton.database.annotations.Column
import neton.database.annotations.CreatedAt
import neton.database.annotations.Id
import neton.database.annotations.Table
import neton.database.annotations.UpdatedAt

/** 地图数据：格子、可行走网格、出生点。寻路只读它，不认识玩法。 */
@Serializable
@Table("mmo_map")
data class MmoMap(
    @Id val id: Long = 0,
    val name: String,
    @Column(name = "width_cells") val widthCells: Int,
    @Column(name = "height_cells") val heightCells: Int,
    /** 一格的毫单位边长。 */
    @Column(name = "cell_size") val cellSize: Int,
    /** `heightCells` 行 × `widthCells` 列，'.' 可走 '#' 阻挡，按行拼接。 */
    val grid: String,
    @Column(name = "spawn_x") val spawnX: Int,
    @Column(name = "spawn_y") val spawnY: Int,
    val status: Int = 1,
    @CreatedAt val createdAt: Long? = null,
    @UpdatedAt val updatedAt: Long? = null,
)

@Serializable
@Table("mmo_npc")
data class MmoNpc(
    @Id val id: Long = 0,
    @Column(name = "map_id") val mapId: Long,
    val name: String,
    val kind: String = "dialog",
    val x: Int,
    val y: Int,
    @Column(name = "interact_range") val interactRange: Int = 3000,
    val dialog: String = "",
    val status: Int = 1,
    @CreatedAt val createdAt: Long? = null,
    @UpdatedAt val updatedAt: Long? = null,
)
