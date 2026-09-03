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
