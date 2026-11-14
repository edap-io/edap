#!/bin/bash
# ★ 隔离测量：每个 (候选, 串) 组合一个 JVM，跑 N 遍取最小值。
#
#   为什么「一个候选一个 JVM」还不够（实测教训）：
#     HotSpot 把编码循环 C2 编译成「针对上一个负载的分支形态」后，
#     换分支分布完全不同的负载时不会自动重编译
#     ⇒ emoji-1k 之后测 mixed-16k：15188ns；把 mixed 放在 emoji 之前测：9194ns
#     ⇒ 差 1.65 倍，纯粹是 JIT profile 污染，与代码无关
#     ⇒ 唯一可靠的做法：(候选, 串) 各起一个 JVM。
#
#   为什么还要「多 launch 取 min」：
#     本机 load 8~10（10 核），单次 launch 仍可能撞上负载尖峰
#     ⇒ 取 min + 打印各 launch 原始值（暴露噪声幅度）。
#
#   用法： ./run-string-candidates.sh <MILLIS> <LAUNCHES> <ROUNDS>
set -u
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
cd "$(dirname "$0")"

MILLIS=${1:-300}
ROUNDS=${3:-4}
LAUNCHES=${2:-3}

CP="target/classes:$(cat target/cp.txt)"
OPENS="--add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.nio=ALL-UNNAMED --add-opens java.base/sun.nio.ch=ALL-UNNAMED --add-opens java.base/java.util=ALL-UNNAMED"

CANDS=${CANDS_OVERRIDE:-"0 3 15 20 21 22 23 24"}
# 拆成数组（CANDS_OVERRIDE 是空格分隔的字符串）
CANDS_ARR=($CANDS)

label_of() {
  case "$1" in
    0)  echo "0 现状复刻";;
    3)  echo "3 生产形态复刻";;
    14) echo "14 Latin1直写";;
    15) echo "15 UTF16+ASCII批量";;
    17) echo "17 Latin1主路径";;
    18) echo "18 判定long批量";;
    19) echo "19 判定无分支求和";;
    20) echo "20 int偏移+预留";;
    21) echo "21 long偏移+右移";;
    22) echo "22 分段+ASCII批量";;
    23) echo "23 只分段";;
    24) echo "24 真实生产路径";;
    25) echo "25 查表byte[][]";;
    26) echo "26 查表扁平byte[]";;
    27) echo "27 查表int+putInt";;
    28) echo "28 只getBytes(UTF8)";;
    29) echo "29 内部数组+copy";;
    30) echo "30 只getBytes(L1)";;
    31) echo "31 普通写+long偏移";;
    32) echo "32 混合式查表";;
    33) echo "33 SIMD判定+直写";;
    34) echo "34 SIMD判定+公开API";;
    *)  echo "$1 ?";;
  esac
}

LABELS=()
for c in "${CANDS_ARR[@]}"; do LABELS+=("$(label_of "$c")"); done

STRINGS=("ascii-1k" "ascii-16k" "cn-1k" "cn-16k" "emoji-1k" "latin1-high" "mixed-16k" "latin1-16k"
         "cn-2k" "cn-4k" "cn-8k" "cn-32k" "ascii-64")

OUT=/tmp/strcand-$$
mkdir -p "$OUT"

# ★ 每个 (候选, 串) 一个 JVM
# ★★ 候选顺序<b>按 launch 轮转</b>：第 l 轮把候选表左移 l 位。
#   原因：机器会随持续负载降频/升温 ⇒ 「排在后面的候选」会被系统性压慢。
#   实测（2026-11-13）：同一批次里 c27 在「重候选之后」测出 15869ns，
#   在「轻候选之间」测出 10284ns（同一份代码，差 1.54 倍）。
#   ⇒ 每个候选都要在不同位置各测一次，取 min，才能抵消漂移。
NCAND=${#CANDS_ARR[@]}
for s in "${!STRINGS[@]}"; do
  for l in $(seq 1 $LAUNCHES); do
    for k in $(seq 0 $((NCAND - 1))); do
      idx=$(( (k + l - 1) % NCAND ))
      c=${CANDS_ARR[$idx]}
      $JAVA_HOME/bin/java $OPENS -cp "$CP" io.edap.grpc.benchmark.StringEncodeCandidates \
        "$MILLIS" "$ROUNDS" "$c" "$s" > "$OUT/c$c.s$s.$l" 2>&1
    done
  done
done

# 汇总：每个 (串, 候选) 取所有 launch 的最小值
printf "%-12s" "串"
for lab in "${LABELS[@]}"; do printf " %16s |" "$lab"; done
printf "\n"
printf -- "-%.0s" {1..190}; printf "\n"

for s in "${!STRINGS[@]}"; do
  printf "%-12s" "${STRINGS[$s]}"
  for c in "${CANDS_ARR[@]}"; do
    best=$(cat "$OUT"/c$c.s$s.* | grep -F "${STRINGS[$s]} " | awk '{print $3}' | sort -g | head -1)
    printf " %16s |" "${best:-NA}"
  done
  printf "\n"
done

# ★ 各 launch 原始值（暴露噪声幅度）：同一候选不同 launch 差 50% 的情况出现过
printf "\n各 launch 原始值（ns）：\n"
for s in "${!STRINGS[@]}"; do
  printf "%-12s" "${STRINGS[$s]}"
  for c in "${CANDS_ARR[@]}"; do
    printf " %14s" "c$c"
    for l in $(seq 1 $LAUNCHES); do
      v=$(grep -F "${STRINGS[$s]} " "$OUT/c$c.s$s.$l" 2>/dev/null | awk '{print $3}')
      printf " %10s" "${v:-NA}"
    done
    printf "\n"
  done
  printf "%-12s" ""
  for c in "${CANDS_ARR[@]}"; do printf " %14s" "c$c"; done
  printf "\n"
done
rm -rf "$OUT"
