-- module-mmorpg 的 dispatch 数据声明。
--
-- 不放在本模块的 migrations 里：这两张表的 owner 是 privchat 模块（数据所有权
-- 纪律见 MMO_ARCHITECTURE_SPEC §3，禁止跨模块写他人表）。这里只作为运维手册，
-- 由 privchat 侧或 DBA 执行。
--
-- callback_url 为 NULL = 走进程内 handler；非空会改走 external HTTP callback，
-- 那时 MmorpgTransferHandler 完全不会被调用（dispatch spec §6）。
INSERT INTO privchat_business_service (id, name, callback_url, status, description, created_at, updated_at)
VALUES (9200, 'mmorpg', NULL, 1, 'MMORPG scene/battle module (internal handler)',
        EXTRACT(EPOCH FROM now())::bigint * 1000, EXTRACT(EPOCH FROM now())::bigint * 1000)
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, status = 1;

-- 每个 scene channel 绑一行。channel_id 由 privchat-server 分配。
-- dispatch_transfer_enabled = 1 是 transfer 能进来的开关。
INSERT INTO privchat_business_channel
  (channel_id, service_id, business_ref_id, business_ref_type, status,
   created_at, updated_at, dispatch_transfer_enabled, dispatch_message_enabled)
VALUES (900000000000000001, 9200, NULL, 'mmorpg_scene', 1,
        EXTRACT(EPOCH FROM now())::bigint * 1000, EXTRACT(EPOCH FROM now())::bigint * 1000, 1, 0)
ON CONFLICT (channel_id) DO UPDATE SET service_id = 9200, status = 1, dispatch_transfer_enabled = 1;
