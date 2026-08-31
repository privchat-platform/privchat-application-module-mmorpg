#!/usr/bin/env python3
"""场景协议的语义校验器(参考实现)。

FlatBuffers 只保证结构合法:union 可以是 NONE、`required` 管不到 union、
"visibility 与 payload 的搭配"它更无从知晓。VALIDATION.md 把这些约束列成了
编号规则,但**规则文档不是校验器** —— 本文件是它的可执行形式。

做法:用 `flatc --json` 把二进制按 schema 解成 JSON,再对 JSON 施加规则。
这样规则与语言无关,Kotlin 与 C++ 实现可以拿它当对照基准。

用法:
    validate.py <intent|event|snapshot> <file.bin>      校验单个样本
    validate.py --fixtures                              跑 fixtures 全集
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
    "intent": "scene_move_intent.fbs",
    "ack": "scene_move_ack.fbs",
    "event": "scene_event.fbs",
    "snapshot": "scene_snapshot.fbs",
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

PUBLIC_ONLY = {"PublicSceneChanged"}


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
        if vis == "PUBLIC" and pt not in PUBLIC_ONLY:
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


CHECKERS = {"intent": check_intent, "event": check_event,
            "snapshot": lambda d: check_snapshot(d)}


def validate(kind: str, path: Path) -> None:
    CHECKERS[kind](decode(kind, path))


# --------------------------------------------------------------------------
# fixtures 全集
# --------------------------------------------------------------------------

def run_fixtures() -> int:
    base = ROOT / "fixtures" / "scene" / "v1"
    if not base.exists():
        print("fixtures 目录不存在", file=sys.stderr)
        return 1

    failures = []
    checked = 0

    # valid/**：必须全部通过
    for f in sorted((base / "valid").rglob("*.bin")):
        kind = f.parent.name
        checked += 1
        try:
            validate(kind, f)
            print(f"  ok    valid/{kind}/{f.name}")
        except Violation as e:
            failures.append(f"valid/{kind}/{f.name} 本应通过,却报 {e}")
            print(f"  FAIL  valid/{kind}/{f.name} → {e}")

    # invalid/**：必须全部被拒,且命中文件名声明的规则
    for f in sorted((base / "invalid").rglob("*.bin")):
        kind = f.parent.name
        want_rule = f.stem.split("__")[0].upper().replace("_", "-")
        checked += 1
        try:
            validate(kind, f)
            failures.append(f"invalid/{kind}/{f.name} 本应被拒,却通过了")
            print(f"  FAIL  invalid/{kind}/{f.name} → 未被拒绝")
        except Violation as e:
            if e.rule != want_rule:
                failures.append(
                    f"invalid/{kind}/{f.name} 期望 {want_rule},实际 {e.rule}")
                print(f"  FAIL  invalid/{kind}/{f.name} → 期望 {want_rule},实际 {e.rule}")
            else:
                print(f"  ok    invalid/{kind}/{f.name} → 正确拒绝({e.rule})")

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
