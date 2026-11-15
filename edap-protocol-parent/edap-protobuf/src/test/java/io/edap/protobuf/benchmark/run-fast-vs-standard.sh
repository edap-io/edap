#!/bin/bash
# ★ 隔离测量：每个 (负载, 候选) 组合一个 JVM，跑 N 遍取最小值。
#
#   被测的四条路径（都是 io.edap.protobuf.ProtoBuf 的公开方法）：
#     std-enc  = ProtoBuf.toByteArray(Object)        标准版编码
#     fast-enc = ProtoBuf.fastToByteArray(Object)     改进版编码
#     std-dec  = ProtoBuf.toObject(byte[], Class)     标准版解码
#     fast-dec = ProtoBuf.fastToObject(byte[], Class)  改进版解码
#
#   ★ 为什么「一个候选一个 JVM」（方法学同 run-string-candidates.sh）：
#     多个候选塞进同一个 JVM 会让调用点变成 megamorphic，
#     且 HotSpot 会把 C2 编译成「针对上一个负载的分支形态」后不再为新负载重编译
#     ⇒ 测出来的数字里混着 JIT profile 污染，不是被测代码的真实成本。
#
#   ★ 为什么还要「多 launch 取 min + 候选顺序按 launch 轮转」：
#     本机会随持续负载降频/升温 ⇒ 「排在后面的候选」会被系统性压慢
#     （同一份代码跨位置测出过 1.54 倍的差）⇒ 每个候选都要在不同位置各测一次，取 min。
#
#   用法： ./run-fast-vs-standard.sh <MILLIS> <LAUNCHES> <ROUNDS>
set -u
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
MVN=/opt/homebrew/bin/mvn

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT=""
d="$SCRIPT_DIR"
while [ "$d" != "/" ]; do
  if [ -f "$d/edap-protocol-parent/edap-protobuf/pom.xml" ]; then ROOT="$d"; break; fi
  d="$(dirname "$d")"
done
[ -n "$ROOT" ] || { echo "★ 找不到仓库根目录"; exit 1; }
cd "$ROOT/edap-protocol-parent/edap-protobuf" || exit 1

MILLIS=${1:-300}
LAUNCHES=${2:-3}
ROUNDS=${3:-3}

# ★ 必须带 --add-opens：
#     java.lang 不开 ⇒ StringUtil 静态初始化炸 ⇒ IS_BYTE_ARRAY=false
#                  ⇒ 标准版的「纯 ASCII 直拷内部 byte[]」快路径被关掉 ⇒ 标准版被测得人为偏慢
#     java.nio  不开 ⇒ UnsafeUtil 静态初始化直接抛 AssertionError
OPENS="--add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.nio=ALL-UNNAMED --add-opens java.base/sun.nio.ch=ALL-UNNAMED --add-opens java.base/java.util=ALL-UNNAMED"

# ── 1. 保证 target/classes 与源码一致（ProtoBuf.fastToByteArray 是新加的，编译产物可能是旧的）──
if [ "${SKIP_BUILD:-0}" != "1" ]; then
  echo "== 重新编译 edap-common + edap-protobuf =="
  # ★ edap-common 必须一起编译：它提供 StringUtil.fastInstance / NATIVE_LITTLE_ENDIAN /
  #   swapUtf16ByteOrder（FAST codec 的字符串路径要用），m2 里的 jar 可能是旧的。
  "$MVN" -o -q -f "$ROOT/edap-common/pom.xml" compile || exit 1
  "$MVN" -o -q compile || exit 1
  "$MVN" -o -q dependency:build-classpath -Dmdep.outputFile=target/cp.txt 2>/dev/null \
    || echo "（build-classpath 失败，沿用已有的 target/cp.txt）"
fi
[ -f target/cp.txt ] || { echo "★ 缺少 target/cp.txt"; exit 1; }

# ── 2. 编译 benchmark（不挂 Maven test 生命周期：仓库别处有预存在的测试失败）──
BENCH_SRC=src/test/java/io/edap/protobuf/benchmark
BENCH_CLASSES=target/bench-classes
mkdir -p "$BENCH_CLASSES"
"$JAVA_HOME/bin/javac" -nowarn -encoding UTF-8 \
    -cp "target/classes:$(cat target/cp.txt)" \
    -d "$BENCH_CLASSES" \
    "$BENCH_SRC"/*.java || exit 1

# ★ edap-common 的源码编译产物放在最前面 ⇒ 一定用当前源码，而不是 m2 里可能陈旧的 jar
CP="$BENCH_CLASSES:$ROOT/edap-common/target/classes:target/classes:$(cat target/cp.txt)"

PAYLOADS=("ascii-1k" "ascii-16k" "cn-1k" "cn-16k" "mixed-16k" "latin1-high-1k"
          "mix-cn10" "mix-cn25" "mix-cn50" "mix-cn75" "mix-cn90" "mix-1cn"
          "nest-64-x8" "nest-1k-x8" "nest-20k-x4" "nest-cn-1k-x8"
          "packed-int-16k" "map-1k" "bytes-16k")
CANDS=("std-enc" "fast-enc" "std-dec" "fast-dec")
NCAND=${#CANDS[@]}

OUT=/tmp/fastvstd-$$
mkdir -p "$OUT"
# KEEP=1 时保留每个 JVM 的原始输出，便于事后重新汇总
if [ "${KEEP:-0}" != "1" ]; then
  trap 'rm -rf "$OUT"' EXIT
else
  echo "（原始输出保留在 ${OUT}）"
fi

echo
echo "== 测量：每项 ${MILLIS}ms × ${ROUNDS} 轮取 min；每个 (负载,候选) 一个 JVM；共 ${LAUNCHES} 轮 launch =="
echo

for p in "${PAYLOADS[@]}"; do
  for l in $(seq 1 $LAUNCHES); do
    # ★ 候选顺序按 launch 轮转：第 l 轮把候选表左移 l-1 位
    for k in $(seq 0 $((NCAND - 1))); do
      idx=$(( (k + l - 1) % NCAND ))
      c=${CANDS[$idx]}
      "$JAVA_HOME/bin/java" $OPENS -cp "$CP" \
        io.edap.protobuf.benchmark.FastVsStandardBench "$p" "$c" "$MILLIS" "$ROUNDS" \
        > "$OUT/$p.$c.$l" 2>&1
      if ! grep -q "^RESULT " "$OUT/$p.$c.$l"; then
        echo "★ 测量失败：负载=$p 候选=$c launch=$l"
        cat "$OUT/$p.$c.$l"
        exit 1
      fi
    done
  done
done

best_of() { # $1=负载 $2=候选 → 全部 launch 里的最小 ns
  grep -h "^RESULT payload=$1 candidate=$2 " "$OUT/$1.$2."* 2>/dev/null \
    | sed -E 's/.* ns=([0-9.]+) .*/\1/' | sort -g | head -1
}
field_of() { # $1=负载 $2=字段名
  grep -h "^RESULT payload=$1 " "$OUT/$1."* 2>/dev/null \
    | sed -E "s/.* $2=([0-9]+) .*/\1/" | head -1
}
launch_ns() { # $1=负载 $2=候选 → 各 launch 的原始 ns
  for l in $(seq 1 $LAUNCHES); do
    v=$(grep -h "^RESULT payload=$1 candidate=$2 " "$OUT/$1.$2.$l" 2>/dev/null \
        | sed -E 's/.* ns=([0-9.]+) .*/\1/')
    printf "%s " "${v:-NA}"
  done
}

echo "================ 标准版 vs 改进版（FAST）编解码性能对比 ================"
printf "%-16s | %11s %11s %8s | %11s %11s %8s | %9s %9s %8s\n" \
  "负载" "编码std(ns)" "编码fast(ns)" "比值" "解码std(ns)" "解码fast(ns)" "比值" "字节std" "字节fast" "体积"
echo "------------------------------------------------------------------------------------------------------------------"
for p in "${PAYLOADS[@]}"; do
  se=$(best_of "$p" std-enc); fe=$(best_of "$p" fast-enc)
  sd=$(best_of "$p" std-dec); fd=$(best_of "$p" fast-dec)
  bs=$(field_of "$p" stdBytes); bf=$(field_of "$p" fastBytes)
  if [ -z "$se" ] || [ -z "$fe" ] || [ -z "$sd" ] || [ -z "$fd" ]; then
    printf "%-16s | ★数据缺失\n" "$p"
    continue
  fi
  awk -v p="$p" -v se="$se" -v fe="$fe" -v sd="$sd" -v fd="$fd" -v bs="$bs" -v bf="$bf" 'BEGIN{
    # ★ 先把三元算成变量再进 printf：BWK awk 会把 printf 参数里的 ">" 当成输出重定向
    r1 = (fe > 0) ? se / fe : 0
    r2 = (fd > 0) ? sd / fd : 0
    r3 = (bs > 0) ? bf / bs : 0
    printf "%-16s | %11.1f %11.1f %7.2fx | %11.1f %11.1f %7.2fx | %9d %9d %7.2fx\n",
      p, se, fe, r1, sd, fd, r2, bs, bf, r3
  }'
done
echo
echo "（比值 = 标准版 / 改进版，>1 表示改进版更快；体积 = 改进版字节数 / 标准版字节数，<1 表示改进版更小）"

echo
echo "== 各 launch 原始值（ns）—— 暴露噪声幅度 =="
for p in "${PAYLOADS[@]}"; do
  for c in "${CANDS[@]}"; do
    printf "%-16s %-9s " "$p" "$c"
    launch_ns "$p" "$c"
    printf "\n"
  done
done
