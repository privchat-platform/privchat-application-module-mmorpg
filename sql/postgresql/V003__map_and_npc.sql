-- 地图数据与 NPC（spec MMO_WORLD_SCENE_SPEC §12.9 → §12.10）。
--
-- 地图是运营内容：格子尺寸、可行走网格、出生点都在数据里，寻路只读数据。
-- grid 是 height_cells 行、每行 width_cells 个字符：'.' 可走，'#' 阻挡。
CREATE TABLE IF NOT EXISTS mmo_map (
    id            BIGSERIAL PRIMARY KEY,
    name          VARCHAR(64) NOT NULL,
    width_cells   INTEGER     NOT NULL,
    height_cells  INTEGER     NOT NULL,
    -- 一格多少毫单位；100 世界单位的地图配 40 格 = 2500。
    cell_size     INTEGER     NOT NULL,
    grid          TEXT        NOT NULL,
    spawn_x       INTEGER     NOT NULL,
    spawn_y       INTEGER     NOT NULL,
    status        SMALLINT    NOT NULL DEFAULT 1,
    created_at    BIGINT      NOT NULL DEFAULT 0,
    updated_at    BIGINT      NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS mmo_npc (
    id             BIGSERIAL PRIMARY KEY,
    map_id         BIGINT      NOT NULL,
    name           VARCHAR(64) NOT NULL,
    -- 玩法层的取值表；底座只透传。
    kind           VARCHAR(32) NOT NULL DEFAULT 'dialog',
    x              INTEGER     NOT NULL,
    y              INTEGER     NOT NULL,
    -- 交互距离（毫单位）：角色的权威位置离 NPC 超过它就 21612。
    interact_range INTEGER     NOT NULL DEFAULT 3000,
    dialog         TEXT        NOT NULL DEFAULT '',
    status         SMALLINT    NOT NULL DEFAULT 1,
    created_at     BIGINT      NOT NULL DEFAULT 0,
    updated_at     BIGINT      NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_mmo_npc_map ON mmo_npc (map_id, status);

-- 场景挂在哪张地图上。
ALTER TABLE mmo_scene_channel ADD COLUMN IF NOT EXISTS map_id BIGINT NOT NULL DEFAULT 1;

-- 路径不再只是一个终点：寻路会绕障碍，路径是点列（JSON 数组，不含起点）。
ALTER TABLE mmo_scene_session ADD COLUMN IF NOT EXISTS path_points TEXT NOT NULL DEFAULT '[]';

-- 种子地图「长安城郊」：40x40 格 = 100x100 世界单位，几块障碍，出生点在正中。
INSERT INTO mmo_map (id, name, width_cells, height_cells, cell_size, grid, spawn_x, spawn_y, created_at, updated_at)
VALUES (1, '长安城郊', 40, 40, 2500,
'........................................' ||
'........................................' ||
'........................................' ||
'........................................' ||
'........................................' ||
'..........########......................' ||
'..........########......................' ||
'..........########......................' ||
'..........########......................' ||
'........................................' ||
'........................................' ||
'........................................' ||
'........................................' ||
'........................................' ||
'........................................' ||
'........................................' ||
'........................................' ||
'........................................' ||
'........................................' ||
'........................................' ||
'........................................' ||
'........................................' ||
'..........................##############' ||
'..........................##############' ||
'..........................##############' ||
'........................................' ||
'........................................' ||
'........................................' ||
'........................................' ||
'........................................' ||
'......####..............................' ||
'......####..............................' ||
'......####..............................' ||
'......####..............................' ||
'........................................' ||
'........................................' ||
'........................................' ||
'........................................' ||
'........................................' ||
'........................................',
50000, 50000, 0, 0)
ON CONFLICT (id) DO NOTHING;

INSERT INTO mmo_npc (id, map_id, name, kind, x, y, interact_range, dialog, created_at, updated_at) VALUES
  (1, 1, '驿站老板', 'dialog', 20000, 12000, 3000, '客官打尖还是住店？', 0, 0),
  (1 + 1, 1, '巡城捕快', 'dialog', 72000, 60000, 3000, '城外最近不太平，夜里莫要独行。', 0, 0)
ON CONFLICT (id) DO NOTHING;
