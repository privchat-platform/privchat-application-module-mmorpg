-- 场景公共事件流的序号必须跨进程重启单调:Room 会向新订阅者回放最近的广播,
-- 内存计数器重启归 1 后,回放里上一轮的高序号会让客户端把本轮的真事件当成重复丢掉。
ALTER TABLE mmo_scene_channel ADD COLUMN IF NOT EXISTS public_scene_seq BIGINT NOT NULL DEFAULT 0;
