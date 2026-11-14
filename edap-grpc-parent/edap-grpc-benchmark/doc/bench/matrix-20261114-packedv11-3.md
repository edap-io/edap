# edap 原生 gRPC vs google grpc-java —— 2×2 矩阵性能对比

生成时间: Sat Nov 14 11:29:47 CST 2026

运行环境: Mac OS X 27.2.1 / aarch64 / JDK 17.0.20.1 / 10 核

被测版本: grpc-java 1.85.1 / protobuf-java 3.25.9 / protoc 3.25.9 / edap 自研 protobuf（edap-protobuf，无 google 依赖）

测量口径: 预热 1000ms + 测量窗口 3000ms（时间制）；4 个臂 × 4 轮，**拉丁方调度**（每个臂在每个位置各跑一次）；N 个 worker 线程共享一条连接；延迟单位微秒

## 一、端到端 unary 性能（E→E vs G→G）

| 负载 | 并发 | edap req/s | google req/s | edap/google | edap p50(us) | google p50(us) | edap p99(us) | google p99(us) |
|---|---|---|---|---|---|---|---|---|
| 0B | 1 | 19083 | 10308 | 1.85x | 50 | 93 | 92 | 166 |
| 0B | 8 | 64578 | 33197 | 1.95x | 120 | 236 | 200 | 358 |
| 0B | 32 | 77641 | 38872 | 2.00x | 410 | 816 | 525 | 1096 |
| 1KB | 1 | 18946 | 10211 | 1.86x | 50 | 94 | 94 | 168 |
| 1KB | 8 | 63203 | 32559 | 1.94x | 123 | 240 | 205 | 365 |
| 1KB | 32 | 76392 | 36521 | 2.09x | 417 | 866 | 535 | 1170 |
| 16KB | 1 | 13510 | 8957 | 1.51x | 71 | 108 | 127 | 189 |
| 16KB | 8 | 39749 | 26604 | 1.49x | 194 | 295 | 332 | 437 |
| 16KB | 32 | 44826 | 29675 | 1.51x | 718 | 1073 | 882 | 1592 |
| 64KB | 1 | 7678 | 6409 | 1.20x | 125 | 151 | 203 | 239 |
| 64KB | 8 | 18981 | 15419 | 1.23x | 409 | 513 | 666 | 744 |
| 64KB | 32 | 21114 | 15540 | 1.36x | 1458 | 2048 | 2695 | 2919 |

## 二、2×2 矩阵（客户端 × 服务端）

| 负载 | 并发 | E→E | E→G | G→E | G→G | 换客户端(G→E/E→E) | 换服务端(E→G/E→E) | 全栈(G→G/E→E) | 交互项 |
|---|---|---|---|---|---|---|---|---|---|
| 0B | 1 | 19083 | 11363 | 16045 | 10308 | 0.84x | 0.60x | 0.54x | 1.08x |
| 0B | 8 | 64578 | 34781 | 60751 | 33197 | 0.94x | 0.54x | 0.51x | 1.01x |
| 0B | 32 | 77641 | 40352 | 77584 | 38872 | 1.00x | 0.52x | 0.50x | 0.96x |
| 1KB | 1 | 18946 | 11160 | 15829 | 10211 | 0.84x | 0.59x | 0.54x | 1.10x |
| 1KB | 8 | 63203 | 34082 | 59136 | 32559 | 0.94x | 0.54x | 0.52x | 1.02x |
| 1KB | 32 | 76392 | 40027 | 73736 | 36521 | 0.97x | 0.52x | 0.48x | 0.95x |
| 16KB | 1 | 13510 | 9088 | 12977 | 8957 | 0.96x | 0.67x | 0.66x | 1.03x |
| 16KB | 8 | 39749 | 26627 | 33875 | 26604 | 0.85x | 0.67x | 0.67x | 1.17x |
| 16KB | 32 | 44826 | 30712 | 47637 | 29675 | 1.06x | 0.69x | 0.66x | 0.91x |
| 64KB | 1 | 7678 | 6084 | 8055 | 6409 | 1.05x | 0.79x | 0.83x | 1.00x |
| 64KB | 8 | 18981 | 14801 | 18572 | 15419 | 0.98x | 0.78x | 0.81x | 1.06x |
| 64KB | 32 | 21114 | 15255 | 22254 | 15540 | 1.05x | 0.72x | 0.74x | 0.97x |

> **换客户端** = 同一个 edap 服务端上，把客户端换成 grpc-java 的提升（`G→E / E→E`）—— **服务端本身不变**，所以这一列是客户端这一侧的贡献。
> **换服务端** = 同一个 edap 客户端上，把服务端换成 grpc-java 的提升（`E→G / E→E`）—— **客户端本身不变**，所以这一列是服务端这一侧的贡献。
> **全栈** = 两边都换（`G→G / E→E`）。**交互项** = 全栈 ÷ (换客户端 × 换服务端)：接近 1 表示两个维度可以相加、互不影响；明显偏离 1 表示两者有耦合（比如 grpc-java 的服务端在 grpc-java 的客户端上才发挥得出来）。

## 三、归因小结

| 负载 | 并发 | 客户端侧差距(G→E/E→E) | 服务端侧差距(E→G/E→E) | 全栈差距 | 谁是主要瓶颈 |
|---|---|---|---|---|---|
| 0B | 1 | 0.84x | 0.60x | 0.54x | **客户端** |
| 0B | 8 | 0.94x | 0.54x | 0.51x | **客户端** |
| 0B | 32 | 1.00x | 0.52x | 0.50x | **客户端** |
| 1KB | 1 | 0.84x | 0.59x | 0.54x | **客户端** |
| 1KB | 8 | 0.94x | 0.54x | 0.52x | **客户端** |
| 1KB | 32 | 0.97x | 0.52x | 0.48x | **客户端** |
| 16KB | 1 | 0.96x | 0.67x | 0.66x | **客户端** |
| 16KB | 8 | 0.85x | 0.67x | 0.67x | **客户端** |
| 16KB | 32 | 1.06x | 0.69x | 0.66x | **客户端** |
| 64KB | 1 | 1.05x | 0.79x | 0.83x | **客户端** |
| 64KB | 8 | 0.98x | 0.78x | 0.81x | **客户端** |
| 64KB | 32 | 1.05x | 0.72x | 0.74x | **客户端** |

> **客户端侧差距** = 在**同一个 edap 服务端**上换客户端（`G→E / E→E`）—— 服务端不变，所以这一列只反映客户端。
> **服务端侧差距** = 在**同一个 edap 客户端**上换服务端（`E→G / E→E`）—— 客户端不变，所以这一列只反映服务端。

## 四、原始轮次（每臂 4 轮）

| 负载 | 并发 | 臂 | 客户端 | 服务端 | 轮次 req/s | 轮次 p99(us) | 错误数 |
|---|---|---|---|---|---|---|---|
| 0B | 1 | E→E | edap | edap | 16747 | 134 | 0 |
| 0B | 1 | E→E | edap | edap | 19007 | 92 | 0 |
| 0B | 1 | E→E | edap | edap | 19159 | 92 | 0 |
| 0B | 1 | E→E | edap | edap | 19272 | 91 | 0 |
| 0B | 1 | E→G | edap | google | 9765 | 182 | 0 |
| 0B | 1 | E→G | edap | google | 11340 | 149 | 0 |
| 0B | 1 | E→G | edap | google | 11430 | 146 | 0 |
| 0B | 1 | E→G | edap | google | 11386 | 146 | 0 |
| 0B | 1 | G→E | google | edap | 14480 | 129 | 0 |
| 0B | 1 | G→E | google | edap | 16024 | 108 | 0 |
| 0B | 1 | G→E | google | edap | 16065 | 108 | 0 |
| 0B | 1 | G→E | google | edap | 16163 | 108 | 0 |
| 0B | 1 | G→G | google | google | 10315 | 167 | 0 |
| 0B | 1 | G→G | google | google | 10300 | 165 | 0 |
| 0B | 1 | G→G | google | google | 10259 | 177 | 0 |
| 0B | 1 | G→G | google | google | 10461 | 162 | 0 |
| 0B | 8 | E→E | edap | edap | 64536 | 200 | 0 |
| 0B | 8 | E→E | edap | edap | 64580 | 200 | 0 |
| 0B | 8 | E→E | edap | edap | 64577 | 199 | 0 |
| 0B | 8 | E→E | edap | edap | 64713 | 199 | 0 |
| 0B | 8 | E→G | edap | google | 34415 | 351 | 0 |
| 0B | 8 | E→G | edap | google | 34902 | 346 | 0 |
| 0B | 8 | E→G | edap | google | 34782 | 346 | 0 |
| 0B | 8 | E→G | edap | google | 34780 | 348 | 0 |
| 0B | 8 | G→E | google | edap | 61284 | 190 | 0 |
| 0B | 8 | G→E | google | edap | 60359 | 192 | 0 |
| 0B | 8 | G→E | google | edap | 61143 | 189 | 0 |
| 0B | 8 | G→E | google | edap | 59294 | 198 | 0 |
| 0B | 8 | G→G | google | google | 33127 | 358 | 0 |
| 0B | 8 | G→G | google | google | 33267 | 357 | 0 |
| 0B | 8 | G→G | google | google | 33304 | 356 | 0 |
| 0B | 8 | G→G | google | google | 33086 | 360 | 0 |
| 0B | 32 | E→E | edap | edap | 78189 | 523 | 0 |
| 0B | 32 | E→E | edap | edap | 77324 | 526 | 0 |
| 0B | 32 | E→E | edap | edap | 77959 | 525 | 0 |
| 0B | 32 | E→E | edap | edap | 77230 | 528 | 0 |
| 0B | 32 | E→G | edap | google | 40354 | 1137 | 0 |
| 0B | 32 | E→G | edap | google | 40976 | 1114 | 0 |
| 0B | 32 | E→G | edap | google | 40351 | 1154 | 0 |
| 0B | 32 | E→G | edap | google | 40162 | 1169 | 0 |
| 0B | 32 | G→E | google | edap | 77716 | 508 | 0 |
| 0B | 32 | G→E | google | edap | 77452 | 502 | 0 |
| 0B | 32 | G→E | google | edap | 76657 | 530 | 0 |
| 0B | 32 | G→E | google | edap | 80601 | 490 | 0 |
| 0B | 32 | G→G | google | google | 39102 | 1085 | 0 |
| 0B | 32 | G→G | google | google | 38587 | 1092 | 0 |
| 0B | 32 | G→G | google | google | 38931 | 1113 | 0 |
| 0B | 32 | G→G | google | google | 38814 | 1101 | 0 |
| 1KB | 1 | E→E | edap | edap | 19014 | 94 | 0 |
| 1KB | 1 | E→E | edap | edap | 18877 | 92 | 0 |
| 1KB | 1 | E→E | edap | edap | 18811 | 94 | 0 |
| 1KB | 1 | E→E | edap | edap | 19215 | 94 | 0 |
| 1KB | 1 | E→G | edap | google | 11161 | 150 | 0 |
| 1KB | 1 | E→G | edap | google | 11174 | 151 | 0 |
| 1KB | 1 | E→G | edap | google | 11160 | 151 | 0 |
| 1KB | 1 | E→G | edap | google | 10789 | 154 | 0 |
| 1KB | 1 | G→E | google | edap | 15849 | 109 | 0 |
| 1KB | 1 | G→E | google | edap | 15809 | 109 | 0 |
| 1KB | 1 | G→E | google | edap | 15890 | 109 | 0 |
| 1KB | 1 | G→E | google | edap | 15746 | 113 | 0 |
| 1KB | 1 | G→G | google | google | 10292 | 165 | 0 |
| 1KB | 1 | G→G | google | google | 10264 | 168 | 0 |
| 1KB | 1 | G→G | google | google | 10158 | 168 | 0 |
| 1KB | 1 | G→G | google | google | 9977 | 170 | 0 |
| 1KB | 8 | E→E | edap | edap | 62759 | 205 | 0 |
| 1KB | 8 | E→E | edap | edap | 63292 | 205 | 0 |
| 1KB | 8 | E→E | edap | edap | 63113 | 204 | 0 |
| 1KB | 8 | E→E | edap | edap | 63340 | 205 | 0 |
| 1KB | 8 | E→G | edap | google | 34135 | 352 | 0 |
| 1KB | 8 | E→G | edap | google | 34198 | 354 | 0 |
| 1KB | 8 | E→G | edap | google | 33971 | 355 | 0 |
| 1KB | 8 | E→G | edap | google | 34030 | 355 | 0 |
| 1KB | 8 | G→E | google | edap | 59233 | 194 | 0 |
| 1KB | 8 | G→E | google | edap | 59176 | 195 | 0 |
| 1KB | 8 | G→E | google | edap | 59095 | 197 | 0 |
| 1KB | 8 | G→E | google | edap | 58911 | 195 | 0 |
| 1KB | 8 | G→G | google | google | 32548 | 366 | 0 |
| 1KB | 8 | G→G | google | google | 32570 | 365 | 0 |
| 1KB | 8 | G→G | google | google | 32503 | 364 | 0 |
| 1KB | 8 | G→G | google | google | 32588 | 362 | 0 |
| 1KB | 32 | E→E | edap | edap | 76494 | 535 | 0 |
| 1KB | 32 | E→E | edap | edap | 76261 | 537 | 0 |
| 1KB | 32 | E→E | edap | edap | 76448 | 533 | 0 |
| 1KB | 32 | E→E | edap | edap | 76336 | 535 | 0 |
| 1KB | 32 | E→G | edap | google | 39933 | 1156 | 0 |
| 1KB | 32 | E→G | edap | google | 39641 | 1156 | 0 |
| 1KB | 32 | E→G | edap | google | 40234 | 1103 | 0 |
| 1KB | 32 | E→G | edap | google | 40121 | 1123 | 0 |
| 1KB | 32 | G→E | google | edap | 74197 | 536 | 0 |
| 1KB | 32 | G→E | google | edap | 73413 | 535 | 0 |
| 1KB | 32 | G→E | google | edap | 73120 | 536 | 0 |
| 1KB | 32 | G→E | google | edap | 74059 | 529 | 0 |
| 1KB | 32 | G→G | google | google | 37094 | 1129 | 0 |
| 1KB | 32 | G→G | google | google | 36632 | 1162 | 0 |
| 1KB | 32 | G→G | google | google | 35470 | 1177 | 0 |
| 1KB | 32 | G→G | google | google | 36409 | 1184 | 0 |
| 16KB | 1 | E→E | edap | edap | 13492 | 127 | 0 |
| 16KB | 1 | E→E | edap | edap | 13529 | 127 | 0 |
| 16KB | 1 | E→E | edap | edap | 13487 | 126 | 0 |
| 16KB | 1 | E→E | edap | edap | 13553 | 125 | 0 |
| 16KB | 1 | E→G | edap | google | 9064 | 179 | 0 |
| 16KB | 1 | E→G | edap | google | 9096 | 178 | 0 |
| 16KB | 1 | E→G | edap | google | 9134 | 179 | 0 |
| 16KB | 1 | E→G | edap | google | 9079 | 179 | 0 |
| 16KB | 1 | G→E | google | edap | 12973 | 131 | 0 |
| 16KB | 1 | G→E | google | edap | 12946 | 132 | 0 |
| 16KB | 1 | G→E | google | edap | 12982 | 132 | 0 |
| 16KB | 1 | G→E | google | edap | 13050 | 133 | 0 |
| 16KB | 1 | G→G | google | google | 8972 | 188 | 0 |
| 16KB | 1 | G→G | google | google | 8911 | 189 | 0 |
| 16KB | 1 | G→G | google | google | 8942 | 188 | 0 |
| 16KB | 1 | G→G | google | google | 9045 | 189 | 0 |
| 16KB | 8 | E→E | edap | edap | 39664 | 334 | 0 |
| 16KB | 8 | E→E | edap | edap | 39621 | 335 | 0 |
| 16KB | 8 | E→E | edap | edap | 39834 | 331 | 0 |
| 16KB | 8 | E→E | edap | edap | 39858 | 330 | 0 |
| 16KB | 8 | E→G | edap | google | 26546 | 443 | 0 |
| 16KB | 8 | E→G | edap | google | 26677 | 439 | 0 |
| 16KB | 8 | E→G | edap | google | 26612 | 439 | 0 |
| 16KB | 8 | E→G | edap | google | 26643 | 439 | 0 |
| 16KB | 8 | G→E | google | edap | 33845 | 328 | 0 |
| 16KB | 8 | G→E | google | edap | 33905 | 329 | 0 |
| 16KB | 8 | G→E | google | edap | 33758 | 330 | 0 |
| 16KB | 8 | G→E | google | edap | 34209 | 327 | 0 |
| 16KB | 8 | G→G | google | google | 26598 | 437 | 0 |
| 16KB | 8 | G→G | google | google | 26540 | 437 | 0 |
| 16KB | 8 | G→G | google | google | 26668 | 435 | 0 |
| 16KB | 8 | G→G | google | google | 26610 | 437 | 0 |
| 16KB | 32 | E→E | edap | edap | 44876 | 881 | 0 |
| 16KB | 32 | E→E | edap | edap | 44994 | 882 | 0 |
| 16KB | 32 | E→E | edap | edap | 44776 | 886 | 0 |
| 16KB | 32 | E→E | edap | edap | 44683 | 882 | 0 |
| 16KB | 32 | E→G | edap | google | 30753 | 1569 | 0 |
| 16KB | 32 | E→G | edap | google | 30579 | 1560 | 0 |
| 16KB | 32 | E→G | edap | google | 30671 | 1556 | 0 |
| 16KB | 32 | E→G | edap | google | 30813 | 1554 | 0 |
| 16KB | 32 | G→E | google | edap | 47511 | 938 | 0 |
| 16KB | 32 | G→E | google | edap | 47899 | 943 | 0 |
| 16KB | 32 | G→E | google | edap | 47507 | 970 | 0 |
| 16KB | 32 | G→E | google | edap | 47763 | 943 | 0 |
| 16KB | 32 | G→G | google | google | 29679 | 1595 | 0 |
| 16KB | 32 | G→G | google | google | 29670 | 1593 | 0 |
| 16KB | 32 | G→G | google | google | 29570 | 1592 | 0 |
| 16KB | 32 | G→G | google | google | 29950 | 1567 | 0 |
| 64KB | 1 | E→E | edap | edap | 7679 | 200 | 0 |
| 64KB | 1 | E→E | edap | edap | 7677 | 205 | 0 |
| 64KB | 1 | E→E | edap | edap | 7636 | 204 | 0 |
| 64KB | 1 | E→E | edap | edap | 7682 | 202 | 0 |
| 64KB | 1 | E→G | edap | google | 6061 | 258 | 0 |
| 64KB | 1 | E→G | edap | google | 6082 | 251 | 0 |
| 64KB | 1 | E→G | edap | google | 6086 | 250 | 0 |
| 64KB | 1 | E→G | edap | google | 6090 | 248 | 0 |
| 64KB | 1 | G→E | google | edap | 8045 | 198 | 0 |
| 64KB | 1 | G→E | google | edap | 8040 | 200 | 0 |
| 64KB | 1 | G→E | google | edap | 8066 | 199 | 0 |
| 64KB | 1 | G→E | google | edap | 8066 | 200 | 0 |
| 64KB | 1 | G→G | google | google | 6400 | 241 | 0 |
| 64KB | 1 | G→G | google | google | 6418 | 238 | 0 |
| 64KB | 1 | G→G | google | google | 6389 | 239 | 0 |
| 64KB | 1 | G→G | google | google | 6423 | 239 | 0 |
| 64KB | 8 | E→E | edap | edap | 19042 | 663 | 0 |
| 64KB | 8 | E→E | edap | edap | 18951 | 670 | 0 |
| 64KB | 8 | E→E | edap | edap | 18995 | 661 | 0 |
| 64KB | 8 | E→E | edap | edap | 18968 | 670 | 0 |
| 64KB | 8 | E→G | edap | google | 14799 | 820 | 0 |
| 64KB | 8 | E→G | edap | google | 14849 | 813 | 0 |
| 64KB | 8 | E→G | edap | google | 14762 | 818 | 0 |
| 64KB | 8 | E→G | edap | google | 14804 | 810 | 0 |
| 64KB | 8 | G→E | google | edap | 18408 | 703 | 0 |
| 64KB | 8 | G→E | google | edap | 18610 | 688 | 0 |
| 64KB | 8 | G→E | google | edap | 18581 | 697 | 0 |
| 64KB | 8 | G→E | google | edap | 18564 | 695 | 0 |
| 64KB | 8 | G→G | google | google | 15424 | 751 | 0 |
| 64KB | 8 | G→G | google | google | 15446 | 744 | 0 |
| 64KB | 8 | G→G | google | google | 15414 | 744 | 0 |
| 64KB | 8 | G→G | google | google | 15414 | 743 | 0 |
| 64KB | 32 | E→E | edap | edap | 21135 | 2594 | 0 |
| 64KB | 32 | E→E | edap | edap | 21051 | 2675 | 0 |
| 64KB | 32 | E→E | edap | edap | 21163 | 2714 | 0 |
| 64KB | 32 | E→E | edap | edap | 21092 | 2718 | 0 |
| 64KB | 32 | E→G | edap | google | 15097 | 2940 | 0 |
| 64KB | 32 | E→G | edap | google | 15252 | 2944 | 0 |
| 64KB | 32 | E→G | edap | google | 15262 | 2930 | 0 |
| 64KB | 32 | E→G | edap | google | 15258 | 2939 | 0 |
| 64KB | 32 | G→E | google | edap | 22387 | 2868 | 0 |
| 64KB | 32 | G→E | google | edap | 22175 | 2857 | 0 |
| 64KB | 32 | G→E | google | edap | 22223 | 2875 | 0 |
| 64KB | 32 | G→E | google | edap | 22286 | 2896 | 0 |
| 64KB | 32 | G→G | google | google | 15537 | 2887 | 0 |
| 64KB | 32 | G→G | google | google | 15521 | 2974 | 0 |
| 64KB | 32 | G→G | google | google | 15608 | 2921 | 0 |
| 64KB | 32 | G→G | google | google | 15544 | 2918 | 0 |

## 五、编解码器 micro 对比

| 负载 | 线上字节数 | 两边字节完全相同 | edap 编码核心 ns/op | google 编码核心 ns/op | 编码核心比 | edap 编码(产出 byte[]) ns/op | google 编码(产出 byte[]) ns/op | 编码比 | edap 解码 ns/op | google 解码 ns/op | 解码比 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 0B | 23 | 是 | 11 | 28 | 0.41x | 21 | 30 | 0.71x | 22 | 25 | 0.87x |
| 1KB | 1050 | 是 | 29 | 46 | 0.64x | 60 | 66 | 0.91x | 56 | 56 | 0.99x |
| 16KB | 16411 | 是 | 330 | 397 | 0.83x | 1005 | 725 | 1.39x | 753 | 740 | 1.02x |
| 64KB | 65563 | 是 | 1313 | 1368 | 0.96x | 2868 | 2002 | 1.43x | 1599 | 1595 | 1.00x |

> ★ **「编码核心」是主口径 —— 两边量的是同一件事**：edap 写进 writer 缓冲只读 `size()`（不产出 byte[]），google 是 `writeTo(CodedOutputStream)` 写进已有数组（两边都是 1 次拷贝）。
> 「编码(产出 byte[])」是**不对等**的一档：edap 的 `GrpcCodec.encode` = 写进 writer 缓冲 **+ 拷成新 byte[]**（2 次拷贝），google 的 `toByteArray()` = 直接写进最终数组（1 次拷贝）⇒ edap 多一次整块拷贝。16KB 上差 ~690ns、64KB 上差 ~1660ns，而一次 64KB 的 `System.arraycopy` 实测 ~1335ns。**拿这一档比「谁更快」是不对的。**
> ★ 2026-11-13 起，**gRPC 运行时已不走这一档了** —— 帧头内联（`GrpcFrameEncoder`）之后，发送侧只拷一次（writer 缓冲 → 目标缓冲）。`GrpcCodec.encode` / `GrpcFrame.encode` 现在只留在测试与基准里当对照基准。
> 解码口径：edap 是 `GrpcCodec.decode`；google 是 `Bench.Payload.parseFrom`（两边同构）。
> 「比」= edap 耗时 / google 耗时，**大于 1 表示 edap 更慢**。
> 本批**所有负载下两边的字节都逐字节相同**（包括 0 字节负载 —— edap 的编码器会省略空的 bytes 字段，与 protobuf-java 的 proto3 默认值规则一致）。

## 六、说明

- 两侧服务端在同一个 JVM 里，业务逻辑逐行对齐（name 加前缀、value ×2、blob 原样回显），所以「edap 客户端打 google 服务端」与「google 客户端打 edap 服务端」的断言有意义。
- 互操作自检在跑基准**之前**执行，两条跨框架链路都必须通，否则直接失败退出。
- 四个臂各自**只开一条连接**，所有 worker 线程共享 —— 与 grpc-java 官方推荐用法一致。
- edap 侧客户端是**阻塞 socket + 单读线程**的实现（`GrpcChannel`），google 侧是 netty 的 NIO 实现，两者的并发模型不同，这正是本对比要暴露的差异。
- 报告里的数字**没有做绝对阈值断言**，只作横向对比用；机器负载会影响绝对值。
- google 系依赖（grpc-java / protobuf-java）**只存在于本基准模块**，不进入任何 edap 运行时产物（`edap-container-boot-lib.txt` 不含本模块）。
