# edap 原生 gRPC vs google grpc-java —— 2×2 矩阵性能对比

生成时间: Fri Nov 13 01:31:53 CST 2026

运行环境: Mac OS X 27.2.1 / aarch64 / JDK 17.0.20.1 / 10 核

被测版本: grpc-java 1.85.1 / protobuf-java 3.25.9 / protoc 3.25.9 / edap 自研 protobuf（edap-protobuf，无 google 依赖）

测量口径: 预热 1000ms + 测量窗口 3000ms（时间制）；4 个臂 × 4 轮，**拉丁方调度**（每个臂在每个位置各跑一次）；N 个 worker 线程共享一条连接；延迟单位微秒

## 一、端到端 unary 性能（E→E vs G→G）

| 负载 | 并发 | edap req/s | google req/s | edap/google | edap p50(us) | google p50(us) | edap p99(us) | google p99(us) |
|---|---|---|---|---|---|---|---|---|
| 0B | 1 | 18914 | 10141 | 1.87x | 50 | 93 | 94 | 165 |
| 0B | 8 | 65005 | 33246 | 1.96x | 119 | 237 | 200 | 358 |
| 0B | 32 | 78428 | 37351 | 2.10x | 406 | 840 | 521 | 1176 |
| 1KB | 1 | 17673 | 9633 | 1.83x | 55 | 99 | 97 | 172 |
| 1KB | 8 | 62665 | 31760 | 1.97x | 123 | 247 | 211 | 378 |
| 1KB | 32 | 76285 | 35534 | 2.15x | 418 | 893 | 534 | 1180 |
| 16KB | 1 | 13238 | 8627 | 1.53x | 73 | 114 | 126 | 183 |
| 16KB | 8 | 40019 | 26380 | 1.52x | 193 | 298 | 330 | 439 |
| 16KB | 32 | 45396 | 29451 | 1.54x | 706 | 1078 | 875 | 1606 |
| 64KB | 1 | 7694 | 6405 | 1.20x | 125 | 151 | 197 | 234 |
| 64KB | 8 | 18866 | 15317 | 1.23x | 409 | 517 | 696 | 758 |
| 64KB | 32 | 21219 | 15436 | 1.37x | 1447 | 2058 | 2705 | 2820 |

## 二、2×2 矩阵（客户端 × 服务端）

| 负载 | 并发 | E→E | E→G | G→E | G→G | 换客户端(G→E/E→E) | 换服务端(E→G/E→E) | 全栈(G→G/E→E) | 交互项 |
|---|---|---|---|---|---|---|---|---|---|
| 0B | 1 | 18914 | 11105 | 16057 | 10141 | 0.85x | 0.59x | 0.54x | 1.08x |
| 0B | 8 | 65005 | 34530 | 61024 | 33246 | 0.94x | 0.53x | 0.51x | 1.03x |
| 0B | 32 | 78428 | 38956 | 77233 | 37351 | 0.98x | 0.50x | 0.48x | 0.97x |
| 1KB | 1 | 17673 | 10438 | 14843 | 9633 | 0.84x | 0.59x | 0.55x | 1.10x |
| 1KB | 8 | 62665 | 32918 | 57845 | 31760 | 0.92x | 0.53x | 0.51x | 1.05x |
| 1KB | 32 | 76285 | 38526 | 72774 | 35534 | 0.95x | 0.51x | 0.47x | 0.97x |
| 16KB | 1 | 13238 | 8805 | 12628 | 8627 | 0.95x | 0.67x | 0.65x | 1.03x |
| 16KB | 8 | 40019 | 26474 | 34107 | 26380 | 0.85x | 0.66x | 0.66x | 1.17x |
| 16KB | 32 | 45396 | 30424 | 47297 | 29451 | 1.04x | 0.67x | 0.65x | 0.93x |
| 64KB | 1 | 7694 | 6051 | 8004 | 6405 | 1.04x | 0.79x | 0.83x | 1.02x |
| 64KB | 8 | 18866 | 14523 | 18248 | 15317 | 0.97x | 0.77x | 0.81x | 1.09x |
| 64KB | 32 | 21219 | 14993 | 22125 | 15436 | 1.04x | 0.71x | 0.73x | 0.99x |

> **换客户端** = 同一个 edap 服务端上，把客户端换成 grpc-java 的提升（`G→E / E→E`）—— **服务端本身不变**，所以这一列是客户端这一侧的贡献。
> **换服务端** = 同一个 edap 客户端上，把服务端换成 grpc-java 的提升（`E→G / E→E`）—— **客户端本身不变**，所以这一列是服务端这一侧的贡献。
> **全栈** = 两边都换（`G→G / E→E`）。**交互项** = 全栈 ÷ (换客户端 × 换服务端)：接近 1 表示两个维度可以相加、互不影响；明显偏离 1 表示两者有耦合（比如 grpc-java 的服务端在 grpc-java 的客户端上才发挥得出来）。

## 三、归因小结

| 负载 | 并发 | 客户端侧差距(G→E/E→E) | 服务端侧差距(E→G/E→E) | 全栈差距 | 谁是主要瓶颈 |
|---|---|---|---|---|---|
| 0B | 1 | 0.85x | 0.59x | 0.54x | **客户端** |
| 0B | 8 | 0.94x | 0.53x | 0.51x | **客户端** |
| 0B | 32 | 0.98x | 0.50x | 0.48x | **客户端** |
| 1KB | 1 | 0.84x | 0.59x | 0.55x | **客户端** |
| 1KB | 8 | 0.92x | 0.53x | 0.51x | **客户端** |
| 1KB | 32 | 0.95x | 0.51x | 0.47x | **客户端** |
| 16KB | 1 | 0.95x | 0.67x | 0.65x | **客户端** |
| 16KB | 8 | 0.85x | 0.66x | 0.66x | **客户端** |
| 16KB | 32 | 1.04x | 0.67x | 0.65x | **客户端** |
| 64KB | 1 | 1.04x | 0.79x | 0.83x | **客户端** |
| 64KB | 8 | 0.97x | 0.77x | 0.81x | **客户端** |
| 64KB | 32 | 1.04x | 0.71x | 0.73x | **客户端** |

> **客户端侧差距** = 在**同一个 edap 服务端**上换客户端（`G→E / E→E`）—— 服务端不变，所以这一列只反映客户端。
> **服务端侧差距** = 在**同一个 edap 客户端**上换服务端（`E→G / E→E`）—— 客户端不变，所以这一列只反映服务端。

## 四、原始轮次（每臂 4 轮）

| 负载 | 并发 | 臂 | 客户端 | 服务端 | 轮次 req/s | 轮次 p99(us) | 错误数 |
|---|---|---|---|---|---|---|---|
| 0B | 1 | E→E | edap | edap | 18666 | 94 | 0 |
| 0B | 1 | E→E | edap | edap | 19087 | 91 | 0 |
| 0B | 1 | E→E | edap | edap | 18878 | 95 | 0 |
| 0B | 1 | E→E | edap | edap | 18950 | 94 | 0 |
| 0B | 1 | E→G | edap | google | 10661 | 164 | 0 |
| 0B | 1 | E→G | edap | google | 11884 | 152 | 0 |
| 0B | 1 | E→G | edap | google | 11108 | 150 | 0 |
| 0B | 1 | E→G | edap | google | 11103 | 154 | 0 |
| 0B | 1 | G→E | google | edap | 14221 | 216 | 0 |
| 0B | 1 | G→E | google | edap | 16156 | 111 | 0 |
| 0B | 1 | G→E | google | edap | 16086 | 106 | 0 |
| 0B | 1 | G→E | google | edap | 16028 | 112 | 0 |
| 0B | 1 | G→G | google | google | 10129 | 165 | 0 |
| 0B | 1 | G→G | google | google | 10153 | 165 | 0 |
| 0B | 1 | G→G | google | google | 10223 | 164 | 0 |
| 0B | 1 | G→G | google | google | 10064 | 181 | 0 |
| 0B | 8 | E→E | edap | edap | 63961 | 203 | 0 |
| 0B | 8 | E→E | edap | edap | 65173 | 199 | 0 |
| 0B | 8 | E→E | edap | edap | 64837 | 200 | 0 |
| 0B | 8 | E→E | edap | edap | 65248 | 199 | 0 |
| 0B | 8 | E→G | edap | google | 33826 | 364 | 0 |
| 0B | 8 | E→G | edap | google | 34730 | 349 | 0 |
| 0B | 8 | E→G | edap | google | 34640 | 349 | 0 |
| 0B | 8 | E→G | edap | google | 34419 | 351 | 0 |
| 0B | 8 | G→E | google | edap | 60847 | 190 | 0 |
| 0B | 8 | G→E | google | edap | 60290 | 192 | 0 |
| 0B | 8 | G→E | google | edap | 61200 | 188 | 0 |
| 0B | 8 | G→E | google | edap | 61325 | 189 | 0 |
| 0B | 8 | G→G | google | google | 33210 | 359 | 0 |
| 0B | 8 | G→G | google | google | 33051 | 362 | 0 |
| 0B | 8 | G→G | google | google | 33374 | 357 | 0 |
| 0B | 8 | G→G | google | google | 33281 | 358 | 0 |
| 0B | 32 | E→E | edap | edap | 79241 | 512 | 0 |
| 0B | 32 | E→E | edap | edap | 78899 | 518 | 0 |
| 0B | 32 | E→E | edap | edap | 77958 | 525 | 0 |
| 0B | 32 | E→E | edap | edap | 77409 | 523 | 0 |
| 0B | 32 | E→G | edap | google | 39325 | 1218 | 0 |
| 0B | 32 | E→G | edap | google | 40254 | 1151 | 0 |
| 0B | 32 | E→G | edap | google | 38387 | 1214 | 0 |
| 0B | 32 | E→G | edap | google | 38587 | 1208 | 0 |
| 0B | 32 | G→E | google | edap | 77531 | 498 | 0 |
| 0B | 32 | G→E | google | edap | 77890 | 495 | 0 |
| 0B | 32 | G→E | google | edap | 75693 | 518 | 0 |
| 0B | 32 | G→E | google | edap | 76934 | 507 | 0 |
| 0B | 32 | G→G | google | google | 37764 | 1152 | 0 |
| 0B | 32 | G→G | google | google | 37145 | 1200 | 0 |
| 0B | 32 | G→G | google | google | 36395 | 1203 | 0 |
| 0B | 32 | G→G | google | google | 37558 | 1149 | 0 |
| 1KB | 1 | E→E | edap | edap | 17782 | 93 | 0 |
| 1KB | 1 | E→E | edap | edap | 17677 | 98 | 0 |
| 1KB | 1 | E→E | edap | edap | 17587 | 98 | 0 |
| 1KB | 1 | E→E | edap | edap | 17669 | 97 | 0 |
| 1KB | 1 | E→G | edap | google | 10515 | 150 | 0 |
| 1KB | 1 | E→G | edap | google | 10433 | 155 | 0 |
| 1KB | 1 | E→G | edap | google | 10443 | 156 | 0 |
| 1KB | 1 | E→G | edap | google | 10341 | 156 | 0 |
| 1KB | 1 | G→E | google | edap | 14853 | 117 | 0 |
| 1KB | 1 | G→E | google | edap | 15000 | 114 | 0 |
| 1KB | 1 | G→E | google | edap | 14670 | 130 | 0 |
| 1KB | 1 | G→E | google | edap | 14834 | 119 | 0 |
| 1KB | 1 | G→G | google | google | 9625 | 170 | 0 |
| 1KB | 1 | G→G | google | google | 10240 | 172 | 0 |
| 1KB | 1 | G→G | google | google | 9559 | 175 | 0 |
| 1KB | 1 | G→G | google | google | 9642 | 171 | 0 |
| 1KB | 8 | E→E | edap | edap | 61344 | 224 | 0 |
| 1KB | 8 | E→E | edap | edap | 62810 | 211 | 0 |
| 1KB | 8 | E→E | edap | edap | 62663 | 209 | 0 |
| 1KB | 8 | E→E | edap | edap | 62667 | 211 | 0 |
| 1KB | 8 | E→G | edap | google | 32816 | 370 | 0 |
| 1KB | 8 | E→G | edap | google | 32955 | 368 | 0 |
| 1KB | 8 | E→G | edap | google | 32894 | 367 | 0 |
| 1KB | 8 | E→G | edap | google | 32943 | 366 | 0 |
| 1KB | 8 | G→E | google | edap | 57757 | 199 | 0 |
| 1KB | 8 | G→E | google | edap | 57763 | 199 | 0 |
| 1KB | 8 | G→E | google | edap | 57926 | 200 | 0 |
| 1KB | 8 | G→E | google | edap | 59882 | 213 | 0 |
| 1KB | 8 | G→G | google | google | 31742 | 376 | 0 |
| 1KB | 8 | G→G | google | google | 31807 | 375 | 0 |
| 1KB | 8 | G→G | google | google | 31641 | 381 | 0 |
| 1KB | 8 | G→G | google | google | 31778 | 379 | 0 |
| 1KB | 32 | E→E | edap | edap | 76436 | 533 | 0 |
| 1KB | 32 | E→E | edap | edap | 76359 | 540 | 0 |
| 1KB | 32 | E→E | edap | edap | 75779 | 535 | 0 |
| 1KB | 32 | E→E | edap | edap | 76212 | 531 | 0 |
| 1KB | 32 | E→G | edap | google | 38241 | 1224 | 0 |
| 1KB | 32 | E→G | edap | google | 38159 | 1203 | 0 |
| 1KB | 32 | E→G | edap | google | 38810 | 1164 | 0 |
| 1KB | 32 | E→G | edap | google | 39097 | 1126 | 0 |
| 1KB | 32 | G→E | google | edap | 71889 | 544 | 0 |
| 1KB | 32 | G→E | google | edap | 71559 | 552 | 0 |
| 1KB | 32 | G→E | google | edap | 73941 | 538 | 0 |
| 1KB | 32 | G→E | google | edap | 73660 | 526 | 0 |
| 1KB | 32 | G→G | google | google | 35757 | 1167 | 0 |
| 1KB | 32 | G→G | google | google | 35128 | 1207 | 0 |
| 1KB | 32 | G→G | google | google | 35312 | 1193 | 0 |
| 1KB | 32 | G→G | google | google | 36113 | 1143 | 0 |
| 16KB | 1 | E→E | edap | edap | 13293 | 110 | 0 |
| 16KB | 1 | E→E | edap | edap | 12974 | 129 | 0 |
| 16KB | 1 | E→E | edap | edap | 13209 | 127 | 0 |
| 16KB | 1 | E→E | edap | edap | 13267 | 126 | 0 |
| 16KB | 1 | E→G | edap | google | 8822 | 165 | 0 |
| 16KB | 1 | E→G | edap | google | 8840 | 167 | 0 |
| 16KB | 1 | E→G | edap | google | 8789 | 183 | 0 |
| 16KB | 1 | E→G | edap | google | 8771 | 184 | 0 |
| 16KB | 1 | G→E | google | edap | 12647 | 117 | 0 |
| 16KB | 1 | G→E | google | edap | 12609 | 118 | 0 |
| 16KB | 1 | G→E | google | edap | 12242 | 137 | 0 |
| 16KB | 1 | G→E | google | edap | 12871 | 136 | 0 |
| 16KB | 1 | G→G | google | google | 8625 | 172 | 0 |
| 16KB | 1 | G→G | google | google | 8636 | 175 | 0 |
| 16KB | 1 | G→G | google | google | 8237 | 191 | 0 |
| 16KB | 1 | G→G | google | google | 8628 | 191 | 0 |
| 16KB | 8 | E→E | edap | edap | 39974 | 329 | 0 |
| 16KB | 8 | E→E | edap | edap | 40036 | 330 | 0 |
| 16KB | 8 | E→E | edap | edap | 40181 | 328 | 0 |
| 16KB | 8 | E→E | edap | edap | 40001 | 331 | 0 |
| 16KB | 8 | E→G | edap | google | 26468 | 442 | 0 |
| 16KB | 8 | E→G | edap | google | 26407 | 443 | 0 |
| 16KB | 8 | E→G | edap | google | 26559 | 438 | 0 |
| 16KB | 8 | E→G | edap | google | 26481 | 441 | 0 |
| 16KB | 8 | G→E | google | edap | 33861 | 323 | 0 |
| 16KB | 8 | G→E | google | edap | 34008 | 324 | 0 |
| 16KB | 8 | G→E | google | edap | 34206 | 324 | 0 |
| 16KB | 8 | G→E | google | edap | 34832 | 320 | 0 |
| 16KB | 8 | G→G | google | google | 26516 | 437 | 0 |
| 16KB | 8 | G→G | google | google | 26287 | 441 | 0 |
| 16KB | 8 | G→G | google | google | 26318 | 443 | 0 |
| 16KB | 8 | G→G | google | google | 26442 | 437 | 0 |
| 16KB | 32 | E→E | edap | edap | 45580 | 871 | 0 |
| 16KB | 32 | E→E | edap | edap | 45339 | 872 | 0 |
| 16KB | 32 | E→E | edap | edap | 45454 | 878 | 0 |
| 16KB | 32 | E→E | edap | edap | 45057 | 878 | 0 |
| 16KB | 32 | E→G | edap | google | 30320 | 1591 | 0 |
| 16KB | 32 | E→G | edap | google | 30657 | 1564 | 0 |
| 16KB | 32 | E→G | edap | google | 30448 | 1596 | 0 |
| 16KB | 32 | E→G | edap | google | 30400 | 1585 | 0 |
| 16KB | 32 | G→E | google | edap | 46861 | 937 | 0 |
| 16KB | 32 | G→E | google | edap | 47269 | 947 | 0 |
| 16KB | 32 | G→E | google | edap | 47389 | 947 | 0 |
| 16KB | 32 | G→E | google | edap | 47326 | 944 | 0 |
| 16KB | 32 | G→G | google | google | 29512 | 1599 | 0 |
| 16KB | 32 | G→G | google | google | 29515 | 1598 | 0 |
| 16KB | 32 | G→G | google | google | 29390 | 1612 | 0 |
| 16KB | 32 | G→G | google | google | 29255 | 1617 | 0 |
| 64KB | 1 | E→E | edap | edap | 7633 | 208 | 0 |
| 64KB | 1 | E→E | edap | edap | 7685 | 194 | 0 |
| 64KB | 1 | E→E | edap | edap | 7770 | 199 | 0 |
| 64KB | 1 | E→E | edap | edap | 7702 | 196 | 0 |
| 64KB | 1 | E→G | edap | google | 6028 | 251 | 0 |
| 64KB | 1 | E→G | edap | google | 6055 | 245 | 0 |
| 64KB | 1 | E→G | edap | google | 6047 | 246 | 0 |
| 64KB | 1 | E→G | edap | google | 6065 | 250 | 0 |
| 64KB | 1 | G→E | google | edap | 7990 | 200 | 0 |
| 64KB | 1 | G→E | google | edap | 7999 | 199 | 0 |
| 64KB | 1 | G→E | google | edap | 8008 | 199 | 0 |
| 64KB | 1 | G→E | google | edap | 8026 | 195 | 0 |
| 64KB | 1 | G→G | google | google | 6456 | 233 | 0 |
| 64KB | 1 | G→G | google | google | 6384 | 236 | 0 |
| 64KB | 1 | G→G | google | google | 6383 | 235 | 0 |
| 64KB | 1 | G→G | google | google | 6426 | 230 | 0 |
| 64KB | 8 | E→E | edap | edap | 19033 | 673 | 0 |
| 64KB | 8 | E→E | edap | edap | 18957 | 687 | 0 |
| 64KB | 8 | E→E | edap | edap | 17660 | 904 | 0 |
| 64KB | 8 | E→E | edap | edap | 18774 | 706 | 0 |
| 64KB | 8 | E→G | edap | google | 14676 | 824 | 0 |
| 64KB | 8 | E→G | edap | google | 14611 | 831 | 0 |
| 64KB | 8 | E→G | edap | google | 14385 | 854 | 0 |
| 64KB | 8 | E→G | edap | google | 14436 | 833 | 0 |
| 64KB | 8 | G→E | google | edap | 18331 | 707 | 0 |
| 64KB | 8 | G→E | google | edap | 18393 | 709 | 0 |
| 64KB | 8 | G→E | google | edap | 18165 | 724 | 0 |
| 64KB | 8 | G→E | google | edap | 17800 | 738 | 0 |
| 64KB | 8 | G→G | google | google | 15299 | 760 | 0 |
| 64KB | 8 | G→G | google | google | 15357 | 750 | 0 |
| 64KB | 8 | G→G | google | google | 15335 | 756 | 0 |
| 64KB | 8 | G→G | google | google | 15200 | 767 | 0 |
| 64KB | 32 | E→E | edap | edap | 21000 | 2630 | 0 |
| 64KB | 32 | E→E | edap | edap | 21118 | 2754 | 0 |
| 64KB | 32 | E→E | edap | edap | 21320 | 2716 | 0 |
| 64KB | 32 | E→E | edap | edap | 21324 | 2694 | 0 |
| 64KB | 32 | E→G | edap | google | 14737 | 9979 | 0 |
| 64KB | 32 | E→G | edap | google | 14895 | 3077 | 0 |
| 64KB | 32 | E→G | edap | google | 15111 | 2983 | 0 |
| 64KB | 32 | E→G | edap | google | 15090 | 2943 | 0 |
| 64KB | 32 | G→E | google | edap | 21953 | 2840 | 0 |
| 64KB | 32 | G→E | google | edap | 22044 | 2824 | 0 |
| 64KB | 32 | G→E | google | edap | 22206 | 2903 | 0 |
| 64KB | 32 | G→E | google | edap | 22224 | 2825 | 0 |
| 64KB | 32 | G→G | google | google | 15185 | 2862 | 0 |
| 64KB | 32 | G→G | google | google | 15319 | 2859 | 0 |
| 64KB | 32 | G→G | google | google | 15552 | 2780 | 0 |
| 64KB | 32 | G→G | google | google | 15632 | 2778 | 0 |

## 五、编解码器 micro 对比

| 负载 | 线上字节数 | 两边字节完全相同 | edap 编码核心 ns/op | google 编码核心 ns/op | 编码核心比 | edap 编码(产出 byte[]) ns/op | google 编码(产出 byte[]) ns/op | 编码比 | edap 解码 ns/op | google 解码 ns/op | 解码比 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 0B | 23 | 是 | 13 | 28 | 0.48x | 24 | 30 | 0.81x | 24 | 26 | 0.92x |
| 1KB | 1050 | 是 | 30 | 46 | 0.65x | 62 | 65 | 0.95x | 53 | 56 | 0.94x |
| 16KB | 16411 | 是 | 373 | 373 | 1.00x | 1038 | 722 | 1.44x | 735 | 745 | 0.99x |
| 64KB | 65563 | 是 | 1280 | 1338 | 0.96x | 3040 | 2002 | 1.52x | 1601 | 1607 | 1.00x |

> ★ **「编码核心」是主口径 —— 两边量的是同一件事**：edap 写进 writer 缓冲只读 `size()`（不产出 byte[]），google 是 `writeTo(CodedOutputStream)` 写进已有数组（两边都是 1 次拷贝）。
> 「编码(产出 byte[])」是**不对等**的一档：edap 的 `GrpcCodec.encode` = 写进 writer 缓冲 **+ 拷成新 byte[]**（2 次拷贝），google 的 `toByteArray()` = 直接写进最终数组（1 次拷贝）⇒ edap 多一次整块拷贝。16KB 上差 ~690ns、64KB 上差 ~1660ns，而一次 64KB 的 `System.arraycopy` 实测 ~1335ns。**拿这一档比「谁更快」是不对的。**
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
