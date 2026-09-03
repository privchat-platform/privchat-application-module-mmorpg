-- 调整种子遭遇的占位数值,让 e2e 里的单人战斗大概率在十回合内以玩家胜利结束
-- (MMO_BATTLE_PROTOCOL_SPEC §15.5:数值是占位,属玩法 spec)。
-- V004 已经应用过,不能改它:迁移脚本一旦落库就是历史(checksum 校验)。
UPDATE mmo_npc
SET encounter = '[{"name":"山贼","hp":40,"mp":0,"atk":10,"def":2,"speed":6},{"name":"山贼头目","hp":50,"mp":10,"atk":12,"def":4,"speed":9}]'
WHERE id = 3;
