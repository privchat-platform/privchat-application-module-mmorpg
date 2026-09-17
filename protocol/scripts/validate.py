#!/usr/bin/env python3
"""场景协议的语义校验器(参考实现)。

FlatBuffers 只保证结构合法:union 可以是 NONE、`required` 管不到 union、
"visibility 与 payload 的搭配"它更无从知晓。VALIDATION.md 把这些约束列成了
编号规则,但**规则文档不是校验器** —— 本文件是它的可执行形式。

做法:用 `flatc --json` 把二进制按 schema 解成 JSON,再对 JSON 施加规则。
这样规则与语言无关,Kotlin 与 C++ 实现可以拿它当对照基准。

用法:
    validate.py <kind> <file.bin>      校验单个样本;kind 见 KIND_SCHEMA
    validate.py --fixtures             跑 fixtures 全集(scene + battle)
退出码 0 = 全部符合预期。
"""
from __future__ import annotations

import json
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SCHEMAS = ROOT / "schemas"

KIND_SCHEMA = {
    # scene
    "intent": "scene_move_intent.fbs",
    "ack": "scene_move_ack.fbs",
    "event": "scene_event.fbs",
    "snapshot": "scene_snapshot.fbs",
    "heartbeat": "scene_heartbeat_request.fbs",
    "interact": "scene_interact_request.fbs",
    # battle(fixtures/battle/v1/<kind>/)
    "command": "battle_command.fbs",
    "battle_event": "battle_event.fbs",
    "instant": "battle_instant_request.fbs",
    "battle_snapshot": "battle_snapshot.fbs",
}

MAX_REQUEST_ID = 64
MAX_EVENTS_PER_BATCH = 128


class Violation(Exception):
    def __init__(self, rule: str, detail: str) -> None:
        super().__init__(f"{rule}: {detail}")
        self.rule = rule


def decode(kind: str, path: Path) -> dict:
    """二进制 → JSON。解码失败本身就是校验失败(结构不合法)。"""
    schema = SCHEMAS / KIND_SCHEMA[kind]
    with tempfile.TemporaryDirectory() as tmp:
        r = subprocess.run(
            ["flatc", "--json", "--strict-json", "--raw-binary",
             "-o", tmp, str(schema), "--", str(path)],
            capture_output=True, text=True,
        )
        if r.returncode != 0:
            raise Violation("DECODE", r.stderr.strip() or "flatc 解码失败")
        out = Path(tmp) / (path.stem + ".json")
        if not out.exists():
            raise Violation("DECODE", "flatc 未产出 JSON")
        return json.loads(out.read_text())


# --------------------------------------------------------------------------
# 上行:MoveIntentEnvelope
# --------------------------------------------------------------------------

def check_intent(d: dict) -> None:
    # V-I1:union 不得为 NONE。flatc 解出的 JSON 中,NONE 表现为缺 command_type
    # 或其值为 "NONE"。
    ct = d.get("command_type")
    if ct in (None, "NONE"):
        raise Violation("V-I1", "command 为 MoveCommand_NONE")

    # V-I2:request_id 非空且不超长。
    rid = d.get("request_id") or ""
    if not rid:
        raise Violation("V-I2", "request_id 为空")
    if len(rid.encode()) > MAX_REQUEST_ID:
        raise Violation("V-I2", f"request_id 超过 {MAX_REQUEST_ID} 字节")

    # V-I3:会话必须存在(此处只能校验非 0;归属校验需要服务端上下文)。
    if not d.get("scene_session_id"):
        raise Violation("V-I3", "scene_session_id 为 0")

    # V-I4 的地图边界、V-I5 的序号比较都依赖服务端状态,
    # 不在离线校验器范围内 —— VALIDATION.md 已注明它们属服务端职责。


# --------------------------------------------------------------------------
# 下行:SceneEventBatchEnvelope
# --------------------------------------------------------------------------

# V-E2 / V-E5:PUBLIC 允许的载荷。MovementStarted 在 AOI 未实装的过渡期允许走 PUBLIC
# (scene_event.fbs 矩阵注释),AOI 落地后从这里移除并补负向 fixture。
PUBLIC_ONLY = {"PublicSceneChanged", "RolePresence"}
PUBLIC_ALLOWED = PUBLIC_ONLY | {"MovementStarted"}


def check_event(d: dict) -> None:
    vis = d.get("visibility", "PUBLIC")
    recipient = d.get("recipient_role_id", 0) or 0
    events = d.get("events") or []

    # V-E8
    if not events:
        raise Violation("V-E8", "events 为空")
    if len(events) > MAX_EVENTS_PER_BATCH:
        raise Violation("V-E8", f"events 超过 {MAX_EVENTS_PER_BATCH}")

    # V-E3 / V-E4
    if vis == "PUBLIC" and recipient != 0:
        raise Violation("V-E3", "PUBLIC 批次的 recipient_role_id 非 0")
    if vis == "PRIVATE" and recipient == 0:
        raise Violation("V-E4", "PRIVATE 批次缺 recipient_role_id")

    # V-E6
    ci = d.get("chunk_index", 0) or 0
    cc = d.get("chunk_count", 0) or 0
    if cc < 1 or ci >= cc:
        raise Violation("V-E6", f"chunk_index={ci} chunk_count={cc}")

    # V-E7
    if (d.get("first_stream_seq", 0) or 0) > (d.get("last_stream_seq", 0) or 0):
        raise Violation("V-E7", "first_stream_seq > last_stream_seq")

    for i, ev in enumerate(events):
        pt = ev.get("payload_type")
        # V-E1
        if pt in (None, "NONE"):
            raise Violation("V-E1", f"events[{i}] payload 为 NONE")
        # V-E2:视野泄露防线
        if vis == "PUBLIC" and pt not in PUBLIC_ALLOWED:
            raise Violation("V-E2", f"PUBLIC 批次携带 {pt}")
        # V-E5:公共状态不得走私有流,否则同一状态落在两条水位上
        if vis == "PRIVATE" and pt in PUBLIC_ONLY:
            raise Violation("V-E5", f"PRIVATE 批次携带 {pt}")
        # V-E9
        if pt == "AoiRebase":
            p = ev.get("payload") or {}
            ids = p.get("entity_ids") or []
            ents = p.get("entities") or []
            if ents and len(ents) != len(ids):
                raise Violation(
                    "V-E9", f"entities({len(ents)}) 与 entity_ids({len(ids)}) 不等长")


# --------------------------------------------------------------------------
# Snapshot
# --------------------------------------------------------------------------

def check_snapshot(d: dict, expect: str | None = None) -> None:
    bt = d.get("body_type")
    # V-S1
    if bt in (None, "NONE"):
        raise Violation("V-S1", "body 为 SnapshotBody_NONE")
    # V-S2
    if expect and bt != expect:
        raise Violation("V-S2", f"期望 {expect},实际 {bt}")

    body = d.get("body") or {}
    common = body.get("common") or {}
    # V-S3:字段必须存在(可为空数组)
    if "public_states" not in common:
        raise Violation("V-S3", "common.public_states 字段缺失")

    if bt == "PrivateSnapshot":
        self_e = body.get("self_entity")
        # V-S4
        if not self_e:
            raise Violation("V-S4", "PrivateSnapshot 缺 self_entity")
        # V-S5
        self_id = self_e.get("entity_id")
        for e in body.get("aoi_entities") or []:
            if e.get("entity_id") == self_id:
                raise Violation("V-S5", "aoi_entities 包含自身实体")




# --------------------------------------------------------------------------
# 心跳 / 交互(V-H1 / V-N1:离线只能校验 request_id;其余依赖服务端状态)
# --------------------------------------------------------------------------

def _check_request_id(d: dict, rule: str) -> None:
    rid = d.get("request_id") or ""
    if not rid:
        raise Violation(rule, "request_id 为空")
    if len(rid.encode()) > MAX_REQUEST_ID:
        raise Violation(rule, f"request_id 超过 {MAX_REQUEST_ID} 字节")


def check_heartbeat(d: dict) -> None:
    _check_request_id(d, "V-H1")


def check_interact(d: dict) -> None:
    _check_request_id(d, "V-N1")


# --------------------------------------------------------------------------
# 战斗上行:BattleCommandEnvelope(V-BC*)
# --------------------------------------------------------------------------

def check_command(d: dict) -> None:
    # 判定顺序按 VALIDATION.md 冻结:V-BC2 最先;V-BC3 只需要请求本身;
    # V-BC4~8 依赖服务端 slot / 版本 / 幂等状态,不在离线范围;V-BC1 最后。
    _check_request_id(d, "V-BC2")
    if d.get("phase", "CREATED") != "COMMAND":
        raise Violation("V-BC3", f"phase={d.get('phase')} 不是 COMMAND")
    if d.get("payload_type") in (None, "NONE"):
        raise Violation("V-BC1", "payload 为 CommandPayload_NONE")


# --------------------------------------------------------------------------
# 战斗下行:BattleEventBatchEnvelope(V-BE*)
# --------------------------------------------------------------------------

BATTLE_PRIVATE_ONLY = {"CommandAccepted", "SlotsOffered"}


def check_battle_event(d: dict) -> None:
    vis = d.get("visibility", "PUBLIC")
    recipient = d.get("recipient_role_id", 0) or 0
    events = d.get("events") or []

    # V-BE5(数量)
    if not events:
        raise Violation("V-BE5", "events 为空")
    if len(events) > MAX_EVENTS_PER_BATCH:
        raise Violation("V-BE5", f"events 超过 {MAX_EVENTS_PER_BATCH}")

    # V-BE2:PUBLIC 无收件人;PRIVATE 必须有(是否等于当前接收者需要接收方身份)
    if vis == "PUBLIC" and recipient != 0:
        raise Violation("V-BE2", "PUBLIC 批次的 recipient_role_id 非 0")
    if vis == "PRIVATE" and recipient == 0:
        raise Violation("V-BE2", "PRIVATE 批次缺 recipient_role_id")

    # V-BE4
    ci = d.get("chunk_index", 0) or 0
    cc = d.get("chunk_count", 0) or 0
    if cc < 1 or ci >= cc:
        raise Violation("V-BE4", f"chunk_index={ci} chunk_count={cc}")
    if (d.get("first_stream_seq", 0) or 0) > (d.get("last_stream_seq", 0) or 0):
        raise Violation("V-BE4", "first_stream_seq > last_stream_seq")

    prev_seq = None
    for i, ev in enumerate(events):
        pt = ev.get("payload_type")
        # V-BE1
        if pt in (None, "NONE"):
            raise Violation("V-BE1", f"events[{i}] payload 为 NONE")
        # V-BE3:指令在 RESOLVE 前不得泄漏
        if vis == "PUBLIC" and pt in BATTLE_PRIVATE_ONLY:
            raise Violation("V-BE3", f"PUBLIC 批次携带 {pt}")
        # V-BE5(批内 stream_seq 严格递增)
        seq = ev.get("stream_seq", 0) or 0
        if prev_seq is not None and seq <= prev_seq:
            raise Violation("V-BE5", f"events[{i}].stream_seq={seq} 不大于前一条 {prev_seq}")
        prev_seq = seq
        # V-BE6
        if ev.get("default_action_applied") and ev.get("auto_played"):
            raise Violation("V-BE6", f"events[{i}] default_action_applied 与 auto_played 同时为 true")


# --------------------------------------------------------------------------
# 战斗即时操作:BattleInstantRequest(V-BQ*)
# --------------------------------------------------------------------------

def check_instant(d: dict) -> None:
    _check_request_id(d, "V-BQ1")
    # V-BQ4 的"已知值":flatc --strict-json 解不出未知枚举,DECODE 已经拦下;
    # SURRENDER 是否处于 COMMAND 阶段依赖服务端状态。


# --------------------------------------------------------------------------
# 战斗 Snapshot:BattleSnapshotEnvelope(V-BS*)
# --------------------------------------------------------------------------

def check_battle_snapshot(d: dict, expect: str | None = None) -> None:
    """expect: "public" / "private" / None(不知道调用的是哪个端点)。"""
    # V-BS3
    if "actors" not in d:
        raise Violation("V-BS3", "actors 字段缺失")
    for i, a in enumerate(d.get("actors") or []):
        for k in ("hp_percent", "mp_percent"):
            v = a.get(k, 0) or 0
            if not 0 <= v <= 100:
                raise Violation("V-BS3", f"actors[{i}].{k}={v} 超出 0..100")
    recipient = d.get("recipient_role_id", 0) or 0
    private_fields = [k for k in ("open_slots", "submitted_commands", "private_actor_states") if d.get(k)]
    # V-BS1:没有收件人的快照就是公开投影,不得带私有字段(视野泄露)
    if recipient == 0 and private_fields:
        raise Violation("V-BS1", f"public 快照携带私有字段 {private_fields}")
    if expect == "public" and recipient != 0:
        raise Violation("V-BS1", "public 端点返回了带 recipient_role_id 的快照")
    # V-BS2:private 端点必须有收件人(是否等于路径 role_id 需要调用上下文)
    if expect == "private" and recipient == 0:
        raise Violation("V-BS2", "private 端点返回的快照 recipient_role_id 为 0")


CHECKERS = {
    "intent": check_intent, "event": check_event, "snapshot": lambda d: check_snapshot(d),
    "heartbeat": check_heartbeat, "interact": check_interact,
    "command": check_command, "battle_event": check_battle_event,
    "instant": check_instant, "battle_snapshot": lambda d: check_battle_snapshot(d),
}

# fixtures/<domain>/v1/<dir>/ 的目录名 → kind。战斗目录用短名,kind 上加前缀区分。
DIR_KIND = {
    "scene": {"intent": "intent", "event": "event", "snapshot": "snapshot",
              "heartbeat": "heartbeat", "interact": "interact"},
    "battle": {"command": "command", "event": "battle_event",
               "instant": "instant", "snapshot": "battle_snapshot"},
}


def validate(kind: str, path: Path) -> None:
    CHECKERS[kind](decode(kind, path))


# --------------------------------------------------------------------------
# fixtures 全集
# --------------------------------------------------------------------------

def run_fixtures() -> int:
    failures = []
    checked = 0
    for domain, dir_kind in DIR_KIND.items():
        base = ROOT / "fixtures" / domain / "v1"
        if not base.exists():
            print(f"fixtures/{domain}/v1 目录不存在", file=sys.stderr)
            return 1
        # valid/**：必须全部通过
        for f in sorted((base / "valid").rglob("*.bin")):
            kind = dir_kind[f.parent.name]
            label = f"{domain}/valid/{f.parent.name}/{f.name}"
            checked += 1
            try:
                validate(kind, f)
                print(f"  ok    {label}")
            except Violation as e:
                failures.append(f"{label} 本应通过,却报 {e}")
                print(f"  FAIL  {label} → {e}")
        # invalid/**：必须全部被拒,且命中文件名声明的规则
        for f in sorted((base / "invalid").rglob("*.bin")):
            kind = dir_kind[f.parent.name]
            label = f"{domain}/invalid/{f.parent.name}/{f.name}"
            want_rule = f.stem.split("__")[0].upper().replace("_", "-")
            checked += 1
            try:
                validate(kind, f)
                failures.append(f"{label} 本应被拒,却通过了")
                print(f"  FAIL  {label} → 未被拒绝")
            except Violation as e:
                if e.rule != want_rule:
                    failures.append(f"{label} 期望 {want_rule},实际 {e.rule}")
                    print(f"  FAIL  {label} → 期望 {want_rule},实际 {e.rule}")
                else:
                    print(f"  ok    {label} → 正确拒绝({e.rule})")

    print(f"\n共 {checked} 个样本,{len(failures)} 项不符预期")
    return 1 if failures else 0


def main() -> int:
    if len(sys.argv) == 2 and sys.argv[1] == "--fixtures":
        return run_fixtures()
    if len(sys.argv) == 3 and sys.argv[1] in CHECKERS:
        try:
            validate(sys.argv[1], Path(sys.argv[2]))
        except Violation as e:
            print(f"校验失败:{e}", file=sys.stderr)
            return 1
        print("校验通过")
        return 0
    print(__doc__, file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main())
