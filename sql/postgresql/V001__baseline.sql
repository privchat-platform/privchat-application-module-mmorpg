-- mmorpg 模块 —— 1.0.0 beta1 合并基线。
--
-- 🔴 **只给全新数据库用。** 由原来的 5 个迁移脚本按执行顺序拼接而成：
-- 顺序不变、语句不变，所以结果与逐条执行完全一致（合并时用两个库对拍验证过，
-- 表结构和种子数据都比过）。
--
-- 为什么是拼接而不是导出结构快照：这些脚本里有 init_data / seed_menus 这类
-- **种子数据**，`pg_dump --schema-only` 会把它们丢掉，而只导结构就得再手工把
-- INSERT 补回来——那一步没有任何东西能验证对错。拼接则由构造保证等价。
--
-- 拼接的代价是留下了少量互相抵消的步骤（先加列、后改列）。它们无害，但**不要**
-- 试图"顺手清理"：清理一次就等于重新引入一个没人验证过的结构。
--
-- 存量库怎么办：本发布不提供原地升级。Neton 的迁移器按 checksum 校验，V001 变了
-- 就会拒绝启动——这是有意的，见 MigrationEngine 的 CHECKSUM_MISMATCH。
--
-- 加新东西请新增 V002、V003…，不要改这个文件。


-- ─────────────────────────────────────────────────────────────
-- 原 V001__create_tables.sql
-- ─────────────────────────────────────────────────────────────

-- module-mmorpg 自持的表。
--
-- 这里没有 privchat_business_service / privchat_business_channel：那两张表的
-- owner 是 privchat 模块，跨模块直写他人表违反 MMO_ARCHITECTURE_SPEC §3 的数据
-- 所有权纪律。service 声明由 privchat 侧 migration 负责，channel 路由由
-- PrivchatBusinessChannelResolver.bind() 在运行时写入。

-- id 用 BIGSERIAL：insert 时实体的 @Id 为 0，框架把该列留给数据库填。
-- 写成裸 BIGINT（无 default）会让 insert 直接违反 NOT NULL，症状是 500 而没有堆栈。
CREATE TABLE IF NOT EXISTS mmo_role (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT      NOT NULL,
    name        VARCHAR(64) NOT NULL,
    status      SMALLINT    NOT NULL DEFAULT 1,
    created_at  BIGINT      NOT NULL DEFAULT 0,
    updated_at  BIGINT      NOT NULL DEFAULT 0
);
-- 一个账号可有多个角色，所以 user_id 不唯一；名字全服唯一。
CREATE UNIQUE INDEX IF NOT EXISTS idx_mmo_role_name ON mmo_role (name);
CREATE INDEX IF NOT EXISTS idx_mmo_role_user ON mmo_role (user_id);

CREATE TABLE IF NOT EXISTS mmo_scene_channel (
    id          BIGSERIAL PRIMARY KEY,
    scene_ref   VARCHAR(64) NOT NULL,
    channel_id  BIGINT      NOT NULL,
    status      SMALLINT    NOT NULL DEFAULT 1,
    created_at  BIGINT      NOT NULL DEFAULT 0,
    updated_at  BIGINT      NOT NULL DEFAULT 0
);
-- 一个场景一个 channel，一个 channel 只服务一个场景。两个方向都要唯一，
-- 否则重启时的幂等恢复会挑到多行而无法判断该用哪个。
CREATE UNIQUE INDEX IF NOT EXISTS idx_mmo_scene_channel_ref ON mmo_scene_channel (scene_ref);
CREATE UNIQUE INDEX IF NOT EXISTS idx_mmo_scene_channel_cid ON mmo_scene_channel (channel_id);

CREATE TABLE IF NOT EXISTS mmo_scene_session (
    id           BIGSERIAL PRIMARY KEY,
    role_id      BIGINT      NOT NULL,
    scene_ref    VARCHAR(64) NOT NULL,
    channel_id   BIGINT      NOT NULL,
    session_epoch BIGINT     NOT NULL DEFAULT 1,
    status       SMALLINT    NOT NULL DEFAULT 1,
    last_seen_at BIGINT      NOT NULL DEFAULT 0,
    created_at   BIGINT      NOT NULL DEFAULT 0,
    updated_at   BIGINT      NOT NULL DEFAULT 0
);
-- 查"这个角色现在在哪"：按 role 找在场会话。
CREATE INDEX IF NOT EXISTS idx_mmo_scene_session_role ON mmo_scene_session (role_id, status);
-- 查"这个场景里有谁"：presence 事件与 AOI 都要用。
CREATE INDEX IF NOT EXISTS idx_mmo_scene_session_scene ON mmo_scene_session (scene_ref, status);


-- ─────────────────────────────────────────────────────────────
-- 原 V002__scene_movement.sql
-- ─────────────────────────────────────────────────────────────

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


-- ─────────────────────────────────────────────────────────────
-- 原 V003__map_and_npc.sql
-- ─────────────────────────────────────────────────────────────

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


-- ─────────────────────────────────────────────────────────────
-- 原 V004__battle.sql
-- ─────────────────────────────────────────────────────────────

-- 回合制战斗 v1（spec MMO_BATTLE_PROTOCOL_SPEC §15.3 数据所有权）。
--
-- 场景会话多一个 state：非 ACTIVE 时不能移动、不能再发起战斗（21613）。
ALTER TABLE mmo_scene_session ADD COLUMN IF NOT EXISTS state VARCHAR(24) NOT NULL DEFAULT 'ACTIVE';
-- NPC 的遭遇配置（JSON 数组，空 = 不可战）：底座只按它生成怪物阵营，数值属玩法。
ALTER TABLE mmo_npc ADD COLUMN IF NOT EXISTS encounter TEXT NOT NULL DEFAULT '';

CREATE TABLE IF NOT EXISTS mmo_battle (
    id                 BIGSERIAL PRIMARY KEY,
    scene_ref          VARCHAR(64) NOT NULL,
    scene_session_id   BIGINT      NOT NULL,
    role_id            BIGINT      NOT NULL,
    channel_id         BIGINT      NOT NULL DEFAULT 0,
    mode               VARCHAR(32) NOT NULL DEFAULT 'pve_basic',
    phase              VARCHAR(16) NOT NULL DEFAULT 'CREATED',
    round_no           INTEGER     NOT NULL DEFAULT 0,
    phase_version      BIGINT      NOT NULL DEFAULT 0,
    state_version      BIGINT      NOT NULL DEFAULT 0,
    public_event_seq   BIGINT      NOT NULL DEFAULT 0,
    private_event_seq  BIGINT      NOT NULL DEFAULT 0,
    -- 只存不发：客户端拿到 seed 就能预知随机结果。
    rng_seed           BIGINT      NOT NULL,
    rng_cursor         BIGINT      NOT NULL DEFAULT 0,
    winner_side        SMALLINT    NOT NULL DEFAULT -1,
    -- 当前阶段的截止（COMMAND：提交截止；SETTLE：转 CLOSED 的时刻）。
    deadline_at_ms     BIGINT      NOT NULL DEFAULT 0,
    initiative_order   TEXT        NOT NULL DEFAULT '[]',
    created_at         BIGINT      NOT NULL DEFAULT 0,
    updated_at         BIGINT      NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_mmo_battle_due ON mmo_battle (phase, deadline_at_ms);

CREATE TABLE IF NOT EXISTS mmo_battle_transition (
    id                 BIGSERIAL PRIMARY KEY,
    role_id            BIGINT      NOT NULL,
    scene_session_id   BIGINT      NOT NULL,
    battle_id          BIGINT      NOT NULL,
    -- PENDING / READY / FAILED / DONE
    status             VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    channel_id         BIGINT      NOT NULL DEFAULT 0,
    ticket             TEXT        NOT NULL DEFAULT '',
    ticket_exp         BIGINT      NOT NULL DEFAULT 0,
    reason             TEXT        NOT NULL DEFAULT '',
    created_at         BIGINT      NOT NULL DEFAULT 0,
    updated_at         BIGINT      NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_mmo_battle_transition_battle ON mmo_battle_transition (battle_id);

CREATE TABLE IF NOT EXISTS mmo_battle_actor (
    id                 BIGSERIAL PRIMARY KEY,
    battle_id          BIGINT      NOT NULL,
    -- 0 = 玩家侧，1 = 怪物侧
    side               SMALLINT    NOT NULL,
    owner_role_id      BIGINT      NOT NULL DEFAULT 0,
    name               VARCHAR(64) NOT NULL,
    kind               VARCHAR(16) NOT NULL,
    position           INTEGER     NOT NULL DEFAULT 0,
    hp                 BIGINT      NOT NULL,
    max_hp             BIGINT      NOT NULL,
    mp                 BIGINT      NOT NULL DEFAULT 0,
    max_mp             BIGINT      NOT NULL DEFAULT 0,
    atk                BIGINT      NOT NULL,
    defense            BIGINT      NOT NULL,
    speed              INTEGER     NOT NULL,
    alive              SMALLINT    NOT NULL DEFAULT 1,
    control_state      VARCHAR(24) NOT NULL DEFAULT 'MANUAL',
    defending          SMALLINT    NOT NULL DEFAULT 0,
    created_at         BIGINT      NOT NULL DEFAULT 0,
    updated_at         BIGINT      NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_mmo_battle_actor_battle ON mmo_battle_actor (battle_id);

-- command_slot_id 就是这张表的 id：服务端签发，客户端回传。
CREATE TABLE IF NOT EXISTS mmo_battle_slot (
    id                  BIGSERIAL PRIMARY KEY,
    battle_id           BIGINT      NOT NULL,
    round_no            INTEGER     NOT NULL,
    actor_id            BIGINT      NOT NULL,
    slot_kind           VARCHAR(16) NOT NULL DEFAULT 'PRIMARY',
    allowed_commands    TEXT        NOT NULL DEFAULT '[]',
    is_required         SMALLINT    NOT NULL DEFAULT 1,
    deadline_at_ms      BIGINT      NOT NULL,
    accepted_action_seq INTEGER     NOT NULL DEFAULT 0,
    -- 已受理指令的规范化载荷；空 = 还没提交（截止时按默认动作）。
    payload             TEXT        NOT NULL DEFAULT '',
    created_at          BIGINT      NOT NULL DEFAULT 0,
    updated_at          BIGINT      NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_mmo_battle_slot_round ON mmo_battle_slot (battle_id, round_no);

-- 幂等真源（spec §4.3）：同 request_id 回放首次 ACK；同 action_seq 不同内容拒绝。
CREATE TABLE IF NOT EXISTS mmo_battle_command (
    id                 BIGSERIAL PRIMARY KEY,
    battle_id          BIGINT      NOT NULL,
    actor_id           BIGINT      NOT NULL,
    action_seq         INTEGER     NOT NULL,
    request_id         VARCHAR(64) NOT NULL,
    command_slot_id    BIGINT      NOT NULL,
    payload            TEXT        NOT NULL,
    ack                TEXT        NOT NULL,
    created_at         BIGINT      NOT NULL DEFAULT 0,
    updated_at         BIGINT      NOT NULL DEFAULT 0,
    UNIQUE (battle_id, actor_id, action_seq)
);
CREATE INDEX IF NOT EXISTS idx_mmo_battle_command_request ON mmo_battle_command (battle_id, request_id);

-- 事件 outbox：与状态同事务写入，提交后再投递；published_at = 0 表示待投递。
CREATE TABLE IF NOT EXISTS mmo_battle_event (
    id                     BIGSERIAL PRIMARY KEY,
    battle_id              BIGINT      NOT NULL,
    round_no               INTEGER     NOT NULL,
    visibility             VARCHAR(8)  NOT NULL,
    recipient_role_id      BIGINT      NOT NULL DEFAULT 0,
    stream_seq             BIGINT      NOT NULL,
    state_version          BIGINT      NOT NULL,
    critical               SMALLINT    NOT NULL DEFAULT 1,
    default_action_applied SMALLINT    NOT NULL DEFAULT 0,
    request_id             VARCHAR(64) NOT NULL DEFAULT '',
    server_time_ms         BIGINT      NOT NULL,
    payload                TEXT        NOT NULL,
    published_at           BIGINT      NOT NULL DEFAULT 0,
    created_at             BIGINT      NOT NULL DEFAULT 0,
    updated_at             BIGINT      NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_mmo_battle_event_pending ON mmo_battle_event (battle_id, published_at);

CREATE TABLE IF NOT EXISTS mmo_battle_lease (
    id                 BIGSERIAL PRIMARY KEY,
    battle_id          BIGINT      NOT NULL UNIQUE,
    owner_node         VARCHAR(64) NOT NULL,
    owner_epoch        BIGINT      NOT NULL DEFAULT 1,
    lease_until        BIGINT      NOT NULL,
    created_at         BIGINT      NOT NULL DEFAULT 0,
    updated_at         BIGINT      NOT NULL DEFAULT 0
);

-- v1 只落 PENDING：经济 / 道具模块落地后由 outbox 推进到 COMPLETED（§15.6）。
CREATE TABLE IF NOT EXISTS mmo_reward_settlement (
    id                     BIGSERIAL PRIMARY KEY,
    settlement_request_id  VARCHAR(64) NOT NULL UNIQUE,
    battle_id              BIGINT      NOT NULL,
    role_id                BIGINT      NOT NULL,
    winner_side            SMALLINT    NOT NULL,
    status                 VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    created_at             BIGINT      NOT NULL DEFAULT 0,
    updated_at             BIGINT      NOT NULL DEFAULT 0
);

-- 种子：一个可战的 NPC。数值是 §15.5 的占位。
INSERT INTO mmo_npc (id, map_id, name, kind, x, y, interact_range, dialog, encounter, created_at, updated_at) VALUES
  (3, 1, '山贼', 'monster', 60000, 30000, 3000, '此山是我开！留下买路财！',
   '[{"name":"山贼","hp":40,"mp":0,"atk":12,"def":3,"speed":6},{"name":"山贼头目","hp":60,"mp":10,"atk":15,"def":5,"speed":9}]',
   0, 0)
ON CONFLICT (id) DO NOTHING;


-- ─────────────────────────────────────────────────────────────
-- 原 V005__tune_bandit_encounter.sql
-- ─────────────────────────────────────────────────────────────

-- 调整种子遭遇的占位数值,让 e2e 里的单人战斗大概率在十回合内以玩家胜利结束
-- (MMO_BATTLE_PROTOCOL_SPEC §15.5:数值是占位,属玩法 spec)。
-- V004 已经应用过,不能改它:迁移脚本一旦落库就是历史(checksum 校验)。
UPDATE mmo_npc
SET encounter = '[{"name":"山贼","hp":40,"mp":0,"atk":10,"def":2,"speed":6},{"name":"山贼头目","hp":50,"mp":10,"atk":12,"def":4,"speed":9}]'
WHERE id = 3;
