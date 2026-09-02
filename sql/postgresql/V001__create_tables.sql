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
