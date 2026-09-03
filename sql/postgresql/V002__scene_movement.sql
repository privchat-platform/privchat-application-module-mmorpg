-- 场景移动的权威状态挂在会话上（spec MMO_WORLD_SCENE_SPEC §4）。
--
-- 位置不是"当前坐标"一列——那需要每帧写库。存的是**当前路径**：起点、终点、
-- 起始时间、速度；任意时刻的权威位置由这四项按定点数学向零取整推算，
-- Kotlin / GDScript 两端得到同一结果。
ALTER TABLE mmo_scene_session
    ADD COLUMN IF NOT EXISTS movement_seq   BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS entity_version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS path_id        BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS start_x        INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS start_y        INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS target_x       INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS target_y       INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS path_start_ms  BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS speed          INTEGER NOT NULL DEFAULT 0;
