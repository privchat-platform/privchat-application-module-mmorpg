-- 并发正确性(评审 2026-09-18):把"应该唯一"的东西交给数据库,而不是靠先读后写。
--
-- 1) 一个角色同时只能有一条在场会话。历史数据里可能已有重复(双击 enter 留下的),
--    先只保留每个角色最新的一条,再建部分唯一索引。
UPDATE mmo_scene_session s
SET status = 0
WHERE s.status = 1
  AND s.id <> (SELECT max(id) FROM mmo_scene_session t WHERE t.role_id = s.role_id AND t.status = 1);
CREATE UNIQUE INDEX IF NOT EXISTS idx_mmo_scene_session_active_role
    ON mmo_scene_session (role_id) WHERE status = 1;

-- 2) 幂等真源必须唯一:同一战斗内 request_id 只能落一行,并发重试由约束裁决,
--    服务层捕获后按 spec §4.3 回放首次 ACK。
DROP INDEX IF EXISTS idx_mmo_battle_command_request;
CREATE UNIQUE INDEX IF NOT EXISTS idx_mmo_battle_command_request
    ON mmo_battle_command (battle_id, request_id);

-- 3) 一个单位一回合只有一个 PRIMARY slot;两次结算同一回合会撞这条约束而不是悄悄双开。
CREATE UNIQUE INDEX IF NOT EXISTS idx_mmo_battle_slot_actor_round
    ON mmo_battle_slot (battle_id, round_no, actor_id, slot_kind);
