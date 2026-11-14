# edap 原生 gRPC vs google grpc-java —— 2×2 矩阵性能对比

> ⚠️ **这批数据已作废** —— 测的是**服务端七个瓶颈修完之前**的服务端。
> 作废原因：根因七（出站缓冲平方级拷贝）只在**连接复用**时暴露，
> 而矩阵每臂跑 4 轮 × 3 秒 ⇒ 复用连接的退化效应被算进了平均值 ⇒ 数字被系统性拉低。
> 当前有效批次：[`matrix-20261112-fix-1.md`](.) / [`fix-2`](.) / [`fix-3`](.)，
> 中位数见 [`matrix-summary.md`](.)。


生成时间: Thu Nov 12 14:56:12 CST 2026

运行环境: Mac OS X 27.2.1 / aarch64 / JDK 17.0.20.1 / 10 核

被测版本: grpc-java 1.85.1 / protobuf-java 3.25.9 / protoc 3.25.9 / edap 自研 protobuf（edap-protobuf，无 google 依赖）

测量口径: 预热 1000ms + 测量窗口 3000ms（时间制）；4 个臂 × 4 轮，**拉丁方调度**（每个臂在每个位置各跑一次）；N 个 worker 线程共享一条连接；延迟单位微秒

## 一、端到端 unary 性能（E→E vs G→G）

| 负载 | 并发 | edap req/s | google req/s | edap/google | edap p50(us) | google p50(us) | edap p99(us) | google p99(us) |
|---|---|---|---|---|---|---|---|---|
| 0B | 1 | 18728 | 10172 | 1.84x | 50 | 93 | 94 | 165 |
| 0B | 8 | 63342 | 33344 | 1.90x | 122 | 235 | 203 | 359 |
| 0B | 32 | 73862 | 38558 | 1.92x | 432 | 821 | 551 | 1125 |
| 1KB | 1 | 17948 | 9968 | 1.80x | 52 | 95 | 104 | 173 |
| 1KB | 8 | 60038 | 31926 | 1.88x | 127 | 244 | 225 | 395 |
| 1KB | 32 | 71589 | 34848 | 2.05x | 443 | 901 | 589 | 1471 |
| 16KB | 1 | 12175 | 8467 | 1.44x | 77 | 112 | 138 | 200 |
| 16KB | 8 | 37524 | 25692 | 1.46x | 203 | 303 | 368 | 498 |
| 16KB | 32 | 42874 | 28763 | 1.49x | 737 | 1103 | 973 | 1690 |
| 64KB | 1 | 6709 | 6247 | 1.07x | 140 | 154 | 223 | 243 |
| 64KB | 8 | 17135 | 15096 | 1.14x | 450 | 524 | 842 | 767 |
| 64KB | 32 | 18873 | 15536 | 1.21x | 1603 | 2033 | 2878 | 2869 |

## 二、2×2 矩阵（客户端 × 服务端）

| 负载 | 并发 | E→E | E→G | G→E | G→G | 换客户端(G→E/E→E) | 换服务端(E→G/E→E) | 全栈(G→G/E→E) | 交互项 |
|---|---|---|---|---|---|---|---|---|---|
| 0B | 1 | 18728 | 10983 | 16010 | 10172 | 0.85x | 0.59x | 0.54x | 1.08x |
| 0B | 8 | 63342 | 34626 | 60159 | 33344 | 0.95x | 0.55x | 0.53x | 1.01x |
| 0B | 32 | 73862 | 40766 | 76224 | 38558 | 1.03x | 0.55x | 0.52x | 0.92x |
| 1KB | 1 | 17948 | 10509 | 15235 | 9968 | 0.85x | 0.59x | 0.56x | 1.12x |
| 1KB | 8 | 60038 | 33056 | 57065 | 31926 | 0.95x | 0.55x | 0.53x | 1.02x |
| 1KB | 32 | 71589 | 38043 | 71236 | 34848 | 1.00x | 0.53x | 0.49x | 0.92x |
| 16KB | 1 | 12175 | 8602 | 11959 | 8467 | 0.98x | 0.71x | 0.70x | 1.00x |
| 16KB | 8 | 37524 | 25386 | 32610 | 25692 | 0.87x | 0.68x | 0.68x | 1.16x |
| 16KB | 32 | 42874 | 29220 | 41954 | 28763 | 0.98x | 0.68x | 0.67x | 1.01x |
| 64KB | 1 | 6709 | 5759 | 7127 | 6247 | 1.06x | 0.86x | 0.93x | 1.02x |
| 64KB | 8 | 17135 | 14290 | 16479 | 15096 | 0.96x | 0.83x | 0.88x | 1.10x |
| 64KB | 32 | 18873 | 14608 | 4912 | 15536 | 0.26x | 0.77x | 0.82x | 4.09x |

> **换客户端** = 同一个 edap 服务端上，把客户端换成 grpc-java 的提升（`G→E / E→E`）—— **服务端本身不变**，所以这一列是客户端这一侧的贡献。
> **换服务端** = 同一个 edap 客户端上，把服务端换成 grpc-java 的提升（`E→G / E→E`）—— **客户端本身不变**，所以这一列是服务端这一侧的贡献。
> **全栈** = 两边都换（`G→G / E→E`）。**交互项** = 全栈 ÷ (换客户端 × 换服务端)：接近 1 表示两个维度可以相加、互不影响；明显偏离 1 表示两者有耦合（比如 grpc-java 的服务端在 grpc-java 的客户端上才发挥得出来）。

## 三、归因小结

| 负载 | 并发 | 客户端侧差距(G→E/E→E) | 服务端侧差距(E→G/E→E) | 全栈差距 | 谁是主要瓶颈 |
|---|---|---|---|---|---|
| 0B | 1 | 0.85x | 0.59x | 0.54x | **客户端** |
| 0B | 8 | 0.95x | 0.55x | 0.53x | **客户端** |
| 0B | 32 | 1.03x | 0.55x | 0.52x | **客户端** |
| 1KB | 1 | 0.85x | 0.59x | 0.56x | **客户端** |
| 1KB | 8 | 0.95x | 0.55x | 0.53x | **客户端** |
| 1KB | 32 | 1.00x | 0.53x | 0.49x | **客户端** |
| 16KB | 1 | 0.98x | 0.71x | 0.70x | **客户端** |
| 16KB | 8 | 0.87x | 0.68x | 0.68x | **客户端** |
| 16KB | 32 | 0.98x | 0.68x | 0.67x | **客户端** |
| 64KB | 1 | 1.06x | 0.86x | 0.93x | **客户端** |
| 64KB | 8 | 0.96x | 0.83x | 0.88x | 两侧相当 |
| 64KB | 32 | 0.26x | 0.77x | 0.82x | **服务端** |

> **客户端侧差距** = 在**同一个 edap 服务端**上换客户端（`G→E / E→E`）—— 服务端不变，所以这一列只反映客户端。
> **服务端侧差距** = 在**同一个 edap 客户端**上换服务端（`E→G / E→E`）—— 客户端不变，所以这一列只反映服务端。

## 四、原始轮次（每臂 4 轮）

| 负载 | 并发 | 臂 | 客户端 | 服务端 | 轮次 req/s | 轮次 p99(us) | 错误数 |
|---|---|---|---|---|---|---|---|
| 0B | 1 | E→E | edap | edap | 18190 | 98 | 0 |
| 0B | 1 | E→E | edap | edap | 18719 | 93 | 0 |
| 0B | 1 | E→E | edap | edap | 18737 | 94 | 0 |
| 0B | 1 | E→E | edap | edap | 19027 | 92 | 0 |
| 0B | 1 | E→G | edap | google | 10312 | 175 | 0 |
| 0B | 1 | E→G | edap | google | 11049 | 157 | 0 |
| 0B | 1 | E→G | edap | google | 10918 | 152 | 0 |
| 0B | 1 | E→G | edap | google | 11157 | 147 | 0 |
| 0B | 1 | G→E | google | edap | 15234 | 133 | 0 |
| 0B | 1 | G→E | google | edap | 15951 | 110 | 0 |
| 0B | 1 | G→E | google | edap | 16068 | 106 | 0 |
| 0B | 1 | G→E | google | edap | 16201 | 106 | 0 |
| 0B | 1 | G→G | google | google | 10197 | 165 | 0 |
| 0B | 1 | G→G | google | google | 10148 | 165 | 0 |
| 0B | 1 | G→G | google | google | 10043 | 187 | 0 |
| 0B | 1 | G→G | google | google | 10195 | 163 | 0 |
| 0B | 8 | E→E | edap | edap | 63050 | 204 | 0 |
| 0B | 8 | E→E | edap | edap | 63360 | 202 | 0 |
| 0B | 8 | E→E | edap | edap | 63325 | 202 | 0 |
| 0B | 8 | E→E | edap | edap | 63552 | 207 | 0 |
| 0B | 8 | E→G | edap | google | 34209 | 354 | 0 |
| 0B | 8 | E→G | edap | google | 34793 | 348 | 0 |
| 0B | 8 | E→G | edap | google | 34511 | 349 | 0 |
| 0B | 8 | E→G | edap | google | 34740 | 346 | 0 |
| 0B | 8 | G→E | google | edap | 60355 | 191 | 0 |
| 0B | 8 | G→E | google | edap | 59726 | 192 | 0 |
| 0B | 8 | G→E | google | edap | 59962 | 190 | 0 |
| 0B | 8 | G→E | google | edap | 61728 | 184 | 0 |
| 0B | 8 | G→G | google | google | 33443 | 356 | 0 |
| 0B | 8 | G→G | google | google | 33145 | 361 | 0 |
| 0B | 8 | G→G | google | google | 33372 | 356 | 0 |
| 0B | 8 | G→G | google | google | 33316 | 361 | 0 |
| 0B | 32 | E→E | edap | edap | 73723 | 540 | 0 |
| 0B | 32 | E→E | edap | edap | 73958 | 550 | 0 |
| 0B | 32 | E→E | edap | edap | 73939 | 552 | 0 |
| 0B | 32 | E→E | edap | edap | 73784 | 556 | 0 |
| 0B | 32 | E→G | edap | google | 41816 | 1067 | 0 |
| 0B | 32 | E→G | edap | google | 41131 | 1117 | 0 |
| 0B | 32 | E→G | edap | google | 40401 | 1143 | 0 |
| 0B | 32 | E→G | edap | google | 38692 | 1297 | 0 |
| 0B | 32 | G→E | google | edap | 78456 | 474 | 0 |
| 0B | 32 | G→E | google | edap | 77777 | 502 | 0 |
| 0B | 32 | G→E | google | edap | 74670 | 527 | 0 |
| 0B | 32 | G→E | google | edap | 74054 | 533 | 0 |
| 0B | 32 | G→G | google | google | 39151 | 1091 | 0 |
| 0B | 32 | G→G | google | google | 37228 | 1212 | 0 |
| 0B | 32 | G→G | google | google | 38505 | 1121 | 0 |
| 0B | 32 | G→G | google | google | 38611 | 1130 | 0 |
| 1KB | 1 | E→E | edap | edap | 17809 | 109 | 0 |
| 1KB | 1 | E→E | edap | edap | 17627 | 113 | 0 |
| 1KB | 1 | E→E | edap | edap | 18407 | 94 | 0 |
| 1KB | 1 | E→E | edap | edap | 18088 | 100 | 0 |
| 1KB | 1 | E→G | edap | google | 10616 | 161 | 0 |
| 1KB | 1 | E→G | edap | google | 10403 | 168 | 0 |
| 1KB | 1 | E→G | edap | google | 10994 | 154 | 0 |
| 1KB | 1 | E→G | edap | google | 9888 | 204 | 0 |
| 1KB | 1 | G→E | google | edap | 14732 | 129 | 0 |
| 1KB | 1 | G→E | google | edap | 15262 | 118 | 0 |
| 1KB | 1 | G→E | google | edap | 15484 | 112 | 0 |
| 1KB | 1 | G→E | google | edap | 15209 | 118 | 0 |
| 1KB | 1 | G→G | google | google | 9776 | 173 | 0 |
| 1KB | 1 | G→G | google | google | 9910 | 174 | 0 |
| 1KB | 1 | G→G | google | google | 10026 | 173 | 0 |
| 1KB | 1 | G→G | google | google | 10192 | 176 | 0 |
| 1KB | 8 | E→E | edap | edap | 60065 | 221 | 0 |
| 1KB | 8 | E→E | edap | edap | 60604 | 217 | 0 |
| 1KB | 8 | E→E | edap | edap | 60011 | 228 | 0 |
| 1KB | 8 | E→E | edap | edap | 59781 | 232 | 0 |
| 1KB | 8 | E→G | edap | google | 33255 | 366 | 0 |
| 1KB | 8 | E→G | edap | google | 32803 | 386 | 0 |
| 1KB | 8 | E→G | edap | google | 33244 | 368 | 0 |
| 1KB | 8 | E→G | edap | google | 32868 | 386 | 0 |
| 1KB | 8 | G→E | google | edap | 55580 | 233 | 0 |
| 1KB | 8 | G→E | google | edap | 57346 | 202 | 0 |
| 1KB | 8 | G→E | google | edap | 57243 | 203 | 0 |
| 1KB | 8 | G→E | google | edap | 56886 | 206 | 0 |
| 1KB | 8 | G→G | google | google | 31956 | 383 | 0 |
| 1KB | 8 | G→G | google | google | 30978 | 486 | 0 |
| 1KB | 8 | G→G | google | google | 32024 | 379 | 0 |
| 1KB | 8 | G→G | google | google | 31896 | 408 | 0 |
| 1KB | 32 | E→E | edap | edap | 71509 | 578 | 0 |
| 1KB | 32 | E→E | edap | edap | 71668 | 571 | 0 |
| 1KB | 32 | E→E | edap | edap | 68933 | 922 | 0 |
| 1KB | 32 | E→E | edap | edap | 71720 | 599 | 0 |
| 1KB | 32 | E→G | edap | google | 37972 | 1555 | 0 |
| 1KB | 32 | E→G | edap | google | 38425 | 1257 | 0 |
| 1KB | 32 | E→G | edap | google | 37218 | 1395 | 0 |
| 1KB | 32 | E→G | edap | google | 38114 | 1261 | 0 |
| 1KB | 32 | G→E | google | edap | 73389 | 581 | 0 |
| 1KB | 32 | G→E | google | edap | 70254 | 739 | 0 |
| 1KB | 32 | G→E | google | edap | 71920 | 574 | 0 |
| 1KB | 32 | G→E | google | edap | 70551 | 592 | 0 |
| 1KB | 32 | G→G | google | google | 35047 | 1276 | 0 |
| 1KB | 32 | G→G | google | google | 34659 | 1562 | 0 |
| 1KB | 32 | G→G | google | google | 34887 | 1527 | 0 |
| 1KB | 32 | G→G | google | google | 34809 | 1416 | 0 |
| 16KB | 1 | E→E | edap | edap | 11715 | 160 | 0 |
| 16KB | 1 | E→E | edap | edap | 12340 | 136 | 0 |
| 16KB | 1 | E→E | edap | edap | 12011 | 140 | 0 |
| 16KB | 1 | E→E | edap | edap | 12411 | 133 | 0 |
| 16KB | 1 | E→G | edap | google | 8238 | 202 | 0 |
| 16KB | 1 | E→G | edap | google | 9030 | 211 | 0 |
| 16KB | 1 | E→G | edap | google | 8586 | 185 | 0 |
| 16KB | 1 | E→G | edap | google | 8618 | 188 | 0 |
| 16KB | 1 | G→E | google | edap | 12061 | 147 | 0 |
| 16KB | 1 | G→E | google | edap | 11950 | 148 | 0 |
| 16KB | 1 | G→E | google | edap | 11659 | 171 | 0 |
| 16KB | 1 | G→E | google | edap | 11967 | 157 | 0 |
| 16KB | 1 | G→G | google | google | 8307 | 207 | 0 |
| 16KB | 1 | G→G | google | google | 8514 | 200 | 0 |
| 16KB | 1 | G→G | google | google | 8552 | 193 | 0 |
| 16KB | 1 | G→G | google | google | 8419 | 200 | 0 |
| 16KB | 8 | E→E | edap | edap | 37711 | 360 | 0 |
| 16KB | 8 | E→E | edap | edap | 37033 | 397 | 0 |
| 16KB | 8 | E→E | edap | edap | 37504 | 370 | 0 |
| 16KB | 8 | E→E | edap | edap | 37544 | 366 | 0 |
| 16KB | 8 | E→G | edap | google | 25675 | 469 | 0 |
| 16KB | 8 | E→G | edap | google | 25199 | 517 | 0 |
| 16KB | 8 | E→G | edap | google | 25534 | 492 | 0 |
| 16KB | 8 | E→G | edap | google | 25239 | 535 | 0 |
| 16KB | 8 | G→E | google | edap | 32244 | 384 | 0 |
| 16KB | 8 | G→E | google | edap | 32624 | 354 | 0 |
| 16KB | 8 | G→E | google | edap | 32596 | 381 | 0 |
| 16KB | 8 | G→E | google | edap | 33462 | 353 | 0 |
| 16KB | 8 | G→G | google | google | 24971 | 608 | 0 |
| 16KB | 8 | G→G | google | google | 26017 | 452 | 0 |
| 16KB | 8 | G→G | google | google | 25528 | 531 | 0 |
| 16KB | 8 | G→G | google | google | 25857 | 464 | 0 |
| 16KB | 32 | E→E | edap | edap | 42668 | 974 | 0 |
| 16KB | 32 | E→E | edap | edap | 42679 | 1104 | 0 |
| 16KB | 32 | E→E | edap | edap | 43070 | 972 | 0 |
| 16KB | 32 | E→E | edap | edap | 43296 | 946 | 0 |
| 16KB | 32 | E→G | edap | google | 29254 | 1724 | 0 |
| 16KB | 32 | E→G | edap | google | 29121 | 1805 | 0 |
| 16KB | 32 | E→G | edap | google | 29565 | 1693 | 0 |
| 16KB | 32 | E→G | edap | google | 29187 | 1653 | 0 |
| 16KB | 32 | G→E | google | edap | 41211 | 2853 | 0 |
| 16KB | 32 | G→E | google | edap | 41693 | 2120 | 0 |
| 16KB | 32 | G→E | google | edap | 42214 | 2207 | 0 |
| 16KB | 32 | G→E | google | edap | 42699 | 1953 | 0 |
| 16KB | 32 | G→G | google | google | 28522 | 1749 | 0 |
| 16KB | 32 | G→G | google | google | 28948 | 1699 | 0 |
| 16KB | 32 | G→G | google | google | 28729 | 1682 | 0 |
| 16KB | 32 | G→G | google | google | 28798 | 1675 | 0 |
| 64KB | 1 | E→E | edap | edap | 6904 | 216 | 0 |
| 64KB | 1 | E→E | edap | edap | 6714 | 225 | 0 |
| 64KB | 1 | E→E | edap | edap | 6703 | 222 | 0 |
| 64KB | 1 | E→E | edap | edap | 6680 | 224 | 0 |
| 64KB | 1 | E→G | edap | google | 5772 | 266 | 0 |
| 64KB | 1 | E→G | edap | google | 5748 | 258 | 0 |
| 64KB | 1 | E→G | edap | google | 5699 | 262 | 0 |
| 64KB | 1 | E→G | edap | google | 5770 | 262 | 0 |
| 64KB | 1 | G→E | google | edap | 7100 | 222 | 0 |
| 64KB | 1 | G→E | google | edap | 7103 | 225 | 0 |
| 64KB | 1 | G→E | google | edap | 7192 | 216 | 0 |
| 64KB | 1 | G→E | google | edap | 7151 | 223 | 0 |
| 64KB | 1 | G→G | google | google | 6320 | 242 | 0 |
| 64KB | 1 | G→G | google | google | 6219 | 245 | 0 |
| 64KB | 1 | G→G | google | google | 6241 | 243 | 0 |
| 64KB | 1 | G→G | google | google | 6254 | 243 | 0 |
| 64KB | 8 | E→E | edap | edap | 17120 | 864 | 0 |
| 64KB | 8 | E→E | edap | edap | 17150 | 858 | 0 |
| 64KB | 8 | E→E | edap | edap | 17179 | 802 | 0 |
| 64KB | 8 | E→E | edap | edap | 16988 | 826 | 0 |
| 64KB | 8 | E→G | edap | google | 14141 | 1053 | 0 |
| 64KB | 8 | E→G | edap | google | 14589 | 843 | 0 |
| 64KB | 8 | E→G | edap | google | 14197 | 950 | 0 |
| 64KB | 8 | E→G | edap | google | 14384 | 854 | 0 |
| 64KB | 8 | G→E | google | edap | 16459 | 1021 | 0 |
| 64KB | 8 | G→E | google | edap | 16521 | 1004 | 0 |
| 64KB | 8 | G→E | google | edap | 16499 | 1103 | 0 |
| 64KB | 8 | G→E | google | edap | 15935 | 1356 | 0 |
| 64KB | 8 | G→G | google | google | 15099 | 771 | 0 |
| 64KB | 8 | G→G | google | google | 15094 | 763 | 0 |
| 64KB | 8 | G→G | google | google | 15123 | 764 | 0 |
| 64KB | 8 | G→G | google | google | 14812 | 822 | 0 |
| 64KB | 32 | E→E | edap | edap | 18898 | 2879 | 0 |
| 64KB | 32 | E→E | edap | edap | 18973 | 2826 | 0 |
| 64KB | 32 | E→E | edap | edap | 18848 | 2876 | 0 |
| 64KB | 32 | E→E | edap | edap | 18615 | 2957 | 0 |
| 64KB | 32 | E→G | edap | google | 14534 | 3175 | 0 |
| 64KB | 32 | E→G | edap | google | 14766 | 3113 | 0 |
| 64KB | 32 | E→G | edap | google | 14681 | 3144 | 0 |
| 64KB | 32 | E→G | edap | google | 14016 | 4505 | 0 |
| 64KB | 32 | G→E | google | edap | 4688 | 19886 | 0 |
| 64KB | 32 | G→E | google | edap | 4922 | 19282 | 0 |
| 64KB | 32 | G→E | google | edap | 4959 | 18089 | 0 |
| 64KB | 32 | G→E | google | edap | 4902 | 17673 | 0 |
| 64KB | 32 | G→G | google | google | 15671 | 2795 | 0 |
| 64KB | 32 | G→G | google | google | 15618 | 2821 | 0 |
| 64KB | 32 | G→G | google | google | 15166 | 3017 | 0 |
| 64KB | 32 | G→G | google | google | 15454 | 2917 | 0 |

## 五、编解码器 micro 对比

| 负载 | 线上字节数 | 两边字节完全相同 | edap 编码 ns/op | google 编码 ns/op | edap 解码 ns/op | google 解码 ns/op | 编码比 | 解码比 |
|---|---|---|---|---|---|---|---|---|
| 0B | 25 | **否** | 22 | 30 | 43 | 26 | 0.72x | 1.65x |
| 1KB | 1050 | 是 | 57 | 66 | 60 | 56 | 0.87x | 1.08x |
| 16KB | 16411 | 是 | 1063 | 726 | 747 | 749 | 1.46x | 1.00x |
| 64KB | 65563 | 是 | 3043 | 2019 | 1606 | 1624 | 1.51x | 0.99x |

> 编码口径：edap 是 `GrpcCodec.encode`；google 是预构造消息的 `toByteArray()`（不含 builder 构造）。
> 解码口径：edap 是 `GrpcCodec.decode`；google 是 `Bench.Payload.parseFrom`。
> 「编码比 / 解码比」= edap 耗时 / google 耗时，**大于 1 表示 edap 更慢**。
> 0 字节负载下两边字节**不完全相同**：edap 的编码器会把空的 bytes 字段也写出去（`1a 00`，共 25 字节），protobuf-java 按 proto3 默认值规则省略该字段（共 23 字节）。两者解码后的**语义完全相同**（blob 都是空），不影响互操作；其余负载下两边的字节逐字节相同。

## 六、说明

- 两侧服务端在同一个 JVM 里，业务逻辑逐行对齐（name 加前缀、value ×2、blob 原样回显），所以「edap 客户端打 google 服务端」与「google 客户端打 edap 服务端」的断言有意义。
- 互操作自检在跑基准**之前**执行，两条跨框架链路都必须通，否则直接失败退出。
- 四个臂各自**只开一条连接**，所有 worker 线程共享 —— 与 grpc-java 官方推荐用法一致。
- edap 侧客户端是**阻塞 socket + 单读线程**的实现（`GrpcChannel`），google 侧是 netty 的 NIO 实现，两者的并发模型不同，这正是本对比要暴露的差异。
- 报告里的数字**没有做绝对阈值断言**，只作横向对比用；机器负载会影响绝对值。
- google 系依赖（grpc-java / protobuf-java）**只存在于本基准模块**，不进入任何 edap 运行时产物（`edap-container-boot-lib.txt` 不含本模块）。
