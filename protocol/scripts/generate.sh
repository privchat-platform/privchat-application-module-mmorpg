#!/usr/bin/env bash
# 生成 Kotlin 与 C++ 绑定,并验证四个 root 的 identifier 都存在。
#
# `root_type` / `file_identifier` 是**文件级**声明:同一 .fbs 里写多个 root
# 只有最后一个生效,前面的静默丢失。因此每个 root 独立成文件、分别生成,
# 并在此断言 identifier 齐全 —— 这条断言就是防止有人把它们合并回去。
set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
# 输出目录**必须**在仓库内的 generated/ 下 —— 脚本会清空它,
# 对调用者传入的任意路径执行 rm -rf 是不可接受的风险。
OUT_NAME="${1:-generated}"
case "$OUT_NAME" in
    */*|..*|/*) echo "输出目录只能是 generated/ 下的名字,不能是路径:$OUT_NAME" >&2; exit 1 ;;
esac
OUT="$HERE/generated"
[ "$OUT_NAME" = "generated" ] || OUT="$HERE/generated/$OUT_NAME"

# 锁定 flatc 版本:不同版本的生成结果与校验行为可能不同,
# 不锁版本会让"本地通过、CI 失败"变成常态。
REQUIRED_FLATC="24.3.25"
ACTUAL="$(flatc --version | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1)"
if [ "$ACTUAL" != "$REQUIRED_FLATC" ]; then
    echo "flatc 版本不符:需要 $REQUIRED_FLATC,实际 $ACTUAL" >&2
    echo "(如需升级,请同时重跑 golden fixtures 并更新本脚本)" >&2
    exit 1
fi

# 先生成到临时目录,成功后再原子替换 —— 中途失败不会留下半套产物,
# 也不会因为参数写错而删掉调用者的目录。
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/cpp" "$TMP/kotlin"

ROOTS=(scene_move_intent scene_move_ack scene_event scene_snapshot)
for f in "${ROOTS[@]}"; do
    flatc --cpp -o "$TMP/cpp" "$HERE/schemas/$f.fbs"
    flatc --kotlin -o "$TMP/kotlin" "$HERE/schemas/$f.fbs"
done

echo "== 校验 file identifier =="
declare -a EXPECT=("MMI1" "MMA1" "MSE1" "MSS1")
fail=0
for i in "${!ROOTS[@]}"; do
    f="${ROOTS[$i]}"; want="${EXPECT[$i]}"
    if grep -q "return \"$want\"" "$TMP/cpp/${f}_generated.h"; then
        echo "  ok  $f → $want"
    else
        echo "  FAIL $f 缺少 identifier $want"; fail=1
    fi
done
[ "$fail" -eq 0 ] || exit 1

# 全部校验通过才落盘,并且只动 generated/ 下自己创建的目录。
mkdir -p "$HERE/generated"
rm -rf "$OUT"
mv "$TMP" "$OUT"
trap - EXIT
echo "生成完成:$OUT"

# 语义校验:FlatBuffers 不执行 VALIDATION.md 的规则,由 validate.py 执行,
# 并用负向 fixture 反证校验器本身有效(全部非法样本必须被拒)。
if [ "${RUN_VALIDATE:-1}" = "1" ]; then
    echo
    python3 "$HERE/scripts/validate.py" --fixtures
fi
