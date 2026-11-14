# edap 原生 gRPC vs google grpc-java —— 2×2 矩阵性能对比

生成时间: Sat Nov 14 11:03:01 CST 2026

运行环境: Mac OS X 27.2.1 / aarch64 / JDK 17.0.20.1 / 10 核

被测版本: grpc-java 1.85.1 / protobuf-java 3.25.9 / protoc 3.25.9 / edap 自研 protobuf（edap-protobuf，无 google 依赖）

测量口径: 预热 1000ms + 测量窗口 3000ms（时间制）；4 个臂 × 4 轮，**拉丁方调度**（每个臂在每个位置各跑一次）；N 个 worker 线程共享一条连接；延迟单位微秒

## 一、端到端 unary 性能（E→E vs G→G）

| 负载 | 并发 | edap req/s | google req/s | edap/google | edap p50(us) | google p50(us) | edap p99(us) | google p99(us) |
|---|---|---|---|---|---|---|---|---|
| 0B | 1 | 18786 | 10112 | 1.86x | 50 | 93 | 97 | 173 |
| 0B | 8 | 64556 | 32899 | 1.96x | 119 | 239 | 203 | 365 |
| 0B | 32 | 79040 | 37838 | 2.09x | 403 | 836 | 520 | 1150 |
| 1KB | 1 | 18659 | 9952 | 1.87x | 50 | 95 | 99 | 175 |
| 1KB | 8 | 63012 | 32393 | 1.95x | 122 | 242 | 208 | 370 |
| 1KB | 32 | 77332 | 36462 | 2.12x | 411 | 870 | 533 | 1175 |
| 16KB | 1 | 13285 | 8737 | 1.52x | 72 | 109 | 130 | 194 |
| 16KB | 8 | 39544 | 26153 | 1.51x | 195 | 300 | 341 | 446 |
| 16KB | 32 | 44896 | 29150 | 1.54x | 711 | 1093 | 881 | 1640 |
| 64KB | 1 | 7527 | 6272 | 1.20x | 126 | 152 | 208 | 252 |
| 64KB | 8 | 18744 | 15187 | 1.23x | 411 | 522 | 702 | 762 |
| 64KB | 32 | 20865 | 15445 | 1.35x | 1487 | 2056 | 2730 | 2808 |

## 二、2×2 矩阵（客户端 × 服务端）

| 负载 | 并发 | E→E | E→G | G→E | G→G | 换客户端(G→E/E→E) | 换服务端(E→G/E→E) | 全栈(G→G/E→E) | 交互项 |
|---|---|---|---|---|---|---|---|---|---|
| 0B | 1 | 18786 | 11049 | 15713 | 10112 | 0.84x | 0.59x | 0.54x | 1.09x |
| 0B | 8 | 64556 | 34433 | 60531 | 32899 | 0.94x | 0.53x | 0.51x | 1.02x |
| 0B | 32 | 79040 | 40188 | 75606 | 37838 | 0.96x | 0.51x | 0.48x | 0.98x |
| 1KB | 1 | 18659 | 10916 | 15577 | 9952 | 0.83x | 0.59x | 0.53x | 1.09x |
| 1KB | 8 | 63012 | 33654 | 58536 | 32393 | 0.93x | 0.53x | 0.51x | 1.04x |
| 1KB | 32 | 77332 | 39505 | 73294 | 36462 | 0.95x | 0.51x | 0.47x | 0.97x |
| 16KB | 1 | 13285 | 8857 | 12759 | 8737 | 0.96x | 0.67x | 0.66x | 1.03x |
| 16KB | 8 | 39544 | 26252 | 33528 | 26153 | 0.85x | 0.66x | 0.66x | 1.17x |
| 16KB | 32 | 44896 | 30092 | 46865 | 29150 | 1.04x | 0.67x | 0.65x | 0.93x |
| 64KB | 1 | 7527 | 5923 | 7883 | 6272 | 1.05x | 0.79x | 0.83x | 1.01x |
| 64KB | 8 | 18744 | 14534 | 18189 | 15187 | 0.97x | 0.78x | 0.81x | 1.08x |
| 64KB | 32 | 20865 | 15177 | 22101 | 15445 | 1.06x | 0.73x | 0.74x | 0.96x |

> **换客户端** = 同一个 edap 服务端上，把客户端换成 grpc-java 的提升（`G→E / E→E`）—— **服务端本身不变**，所以这一列是客户端这一侧的贡献。
> **换服务端** = 同一个 edap 客户端上，把服务端换成 grpc-java 的提升（`E→G / E→E`）—— **客户端本身不变**，所以这一列是服务端这一侧的贡献。
> **全栈** = 两边都换（`G→G / E→E`）。**交互项** = 全栈 ÷ (换客户端 × 换服务端)：接近 1 表示两个维度可以相加、互不影响；明显偏离 1 表示两者有耦合（比如 grpc-java 的服务端在 grpc-java 的客户端上才发挥得出来）。

## 三、归因小结

| 负载 | 并发 | 客户端侧差距(G→E/E→E) | 服务端侧差距(E→G/E→E) | 全栈差距 | 谁是主要瓶颈 |
|---|---|---|---|---|---|
| 0B | 1 | 0.84x | 0.59x | 0.54x | **客户端** |
| 0B | 8 | 0.94x | 0.53x | 0.51x | **客户端** |
| 0B | 32 | 0.96x | 0.51x | 0.48x | **客户端** |
| 1KB | 1 | 0.83x | 0.59x | 0.53x | **客户端** |
| 1KB | 8 | 0.93x | 0.53x | 0.51x | **客户端** |
| 1KB | 32 | 0.95x | 0.51x | 0.47x | **客户端** |
| 16KB | 1 | 0.96x | 0.67x | 0.66x | **客户端** |
| 16KB | 8 | 0.85x | 0.66x | 0.66x | **客户端** |
| 16KB | 32 | 1.04x | 0.67x | 0.65x | **客户端** |
| 64KB | 1 | 1.05x | 0.79x | 0.83x | **客户端** |
| 64KB | 8 | 0.97x | 0.78x | 0.81x | **客户端** |
| 64KB | 32 | 1.06x | 0.73x | 0.74x | **客户端** |

> **客户端侧差距** = 在**同一个 edap 服务端**上换客户端（`G→E / E→E`）—— 服务端不变，所以这一列只反映客户端。
> **服务端侧差距** = 在**同一个 edap 客户端**上换服务端（`E→G / E→E`）—— 客户端不变，所以这一列只反映服务端。

## 四、原始轮次（每臂 4 轮）

| 负载 | 并发 | 臂 | 客户端 | 服务端 | 轮次 req/s | 轮次 p99(us) | 错误数 |
|---|---|---|---|---|---|---|---|
| 0B | 1 | E→E | edap | edap | 17835 | 109 | 0 |
| 0B | 1 | E→E | edap | edap | 18754 | 98 | 0 |
| 0B | 1 | E→E | edap | edap | 18897 | 97 | 0 |
| 0B | 1 | E→E | edap | edap | 18818 | 97 | 0 |
| 0B | 1 | E→G | edap | google | 9518 | 202 | 0 |
| 0B | 1 | E→G | edap | google | 11522 | 164 | 0 |
| 0B | 1 | E→G | edap | google | 10996 | 156 | 0 |
| 0B | 1 | E→G | edap | google | 11101 | 153 | 0 |
| 0B | 1 | G→E | google | edap | 14179 | 146 | 0 |
| 0B | 1 | G→E | google | edap | 15548 | 128 | 0 |
| 0B | 1 | G→E | google | edap | 15879 | 120 | 0 |
| 0B | 1 | G→E | google | edap | 16126 | 112 | 0 |
| 0B | 1 | G→G | google | google | 10180 | 174 | 0 |
| 0B | 1 | G→G | google | google | 10096 | 170 | 0 |
| 0B | 1 | G→G | google | google | 10040 | 175 | 0 |
| 0B | 1 | G→G | google | google | 10129 | 172 | 0 |
| 0B | 8 | E→E | edap | edap | 64585 | 201 | 0 |
| 0B | 8 | E→E | edap | edap | 64737 | 202 | 0 |
| 0B | 8 | E→E | edap | edap | 64527 | 203 | 0 |
| 0B | 8 | E→E | edap | edap | 64311 | 208 | 0 |
| 0B | 8 | E→G | edap | google | 34410 | 354 | 0 |
| 0B | 8 | E→G | edap | google | 34456 | 356 | 0 |
| 0B | 8 | E→G | edap | google | 34471 | 354 | 0 |
| 0B | 8 | E→G | edap | google | 34396 | 352 | 0 |
| 0B | 8 | G→E | google | edap | 60535 | 194 | 0 |
| 0B | 8 | G→E | google | edap | 60510 | 193 | 0 |
| 0B | 8 | G→E | google | edap | 61426 | 192 | 0 |
| 0B | 8 | G→E | google | edap | 60527 | 197 | 0 |
| 0B | 8 | G→G | google | google | 32622 | 370 | 0 |
| 0B | 8 | G→G | google | google | 33177 | 360 | 0 |
| 0B | 8 | G→G | google | google | 33254 | 360 | 0 |
| 0B | 8 | G→G | google | google | 32410 | 375 | 0 |
| 0B | 32 | E→E | edap | edap | 79364 | 517 | 0 |
| 0B | 32 | E→E | edap | edap | 77967 | 545 | 0 |
| 0B | 32 | E→E | edap | edap | 78968 | 519 | 0 |
| 0B | 32 | E→E | edap | edap | 79113 | 520 | 0 |
| 0B | 32 | E→G | edap | google | 40768 | 1115 | 0 |
| 0B | 32 | E→G | edap | google | 38940 | 1219 | 0 |
| 0B | 32 | E→G | edap | google | 40216 | 1145 | 0 |
| 0B | 32 | E→G | edap | google | 40160 | 1172 | 0 |
| 0B | 32 | G→E | google | edap | 77091 | 513 | 0 |
| 0B | 32 | G→E | google | edap | 73774 | 583 | 0 |
| 0B | 32 | G→E | google | edap | 74121 | 556 | 0 |
| 0B | 32 | G→E | google | edap | 79604 | 507 | 0 |
| 0B | 32 | G→G | google | google | 37804 | 1149 | 0 |
| 0B | 32 | G→G | google | google | 37341 | 1151 | 0 |
| 0B | 32 | G→G | google | google | 38204 | 1123 | 0 |
| 0B | 32 | G→G | google | google | 37871 | 1152 | 0 |
| 1KB | 1 | E→E | edap | edap | 18643 | 99 | 0 |
| 1KB | 1 | E→E | edap | edap | 18675 | 98 | 0 |
| 1KB | 1 | E→E | edap | edap | 18725 | 97 | 0 |
| 1KB | 1 | E→E | edap | edap | 18576 | 99 | 0 |
| 1KB | 1 | E→G | edap | google | 10874 | 158 | 0 |
| 1KB | 1 | E→G | edap | google | 10757 | 163 | 0 |
| 1KB | 1 | E→G | edap | google | 10970 | 157 | 0 |
| 1KB | 1 | E→G | edap | google | 10958 | 155 | 0 |
| 1KB | 1 | G→E | google | edap | 15580 | 119 | 0 |
| 1KB | 1 | G→E | google | edap | 15575 | 119 | 0 |
| 1KB | 1 | G→E | google | edap | 15492 | 118 | 0 |
| 1KB | 1 | G→E | google | edap | 15714 | 117 | 0 |
| 1KB | 1 | G→G | google | google | 9959 | 175 | 0 |
| 1KB | 1 | G→G | google | google | 9944 | 174 | 0 |
| 1KB | 1 | G→G | google | google | 9964 | 175 | 0 |
| 1KB | 1 | G→G | google | google | 9934 | 176 | 0 |
| 1KB | 8 | E→E | edap | edap | 63024 | 209 | 0 |
| 1KB | 8 | E→E | edap | edap | 62999 | 207 | 0 |
| 1KB | 8 | E→E | edap | edap | 62800 | 209 | 0 |
| 1KB | 8 | E→E | edap | edap | 63034 | 207 | 0 |
| 1KB | 8 | E→G | edap | google | 33662 | 362 | 0 |
| 1KB | 8 | E→G | edap | google | 33770 | 362 | 0 |
| 1KB | 8 | E→G | edap | google | 33647 | 361 | 0 |
| 1KB | 8 | E→G | edap | google | 33592 | 362 | 0 |
| 1KB | 8 | G→E | google | edap | 58486 | 201 | 0 |
| 1KB | 8 | G→E | google | edap | 58424 | 201 | 0 |
| 1KB | 8 | G→E | google | edap | 58860 | 199 | 0 |
| 1KB | 8 | G→E | google | edap | 58587 | 201 | 0 |
| 1KB | 8 | G→G | google | google | 32304 | 370 | 0 |
| 1KB | 8 | G→G | google | google | 32513 | 367 | 0 |
| 1KB | 8 | G→G | google | google | 32231 | 376 | 0 |
| 1KB | 8 | G→G | google | google | 32482 | 369 | 0 |
| 1KB | 32 | E→E | edap | edap | 77990 | 533 | 0 |
| 1KB | 32 | E→E | edap | edap | 77458 | 532 | 0 |
| 1KB | 32 | E→E | edap | edap | 77142 | 534 | 0 |
| 1KB | 32 | E→E | edap | edap | 77206 | 533 | 0 |
| 1KB | 32 | E→G | edap | google | 39774 | 1167 | 0 |
| 1KB | 32 | E→G | edap | google | 39636 | 1158 | 0 |
| 1KB | 32 | E→G | edap | google | 39374 | 1177 | 0 |
| 1KB | 32 | E→G | edap | google | 39222 | 1170 | 0 |
| 1KB | 32 | G→E | google | edap | 73244 | 548 | 0 |
| 1KB | 32 | G→E | google | edap | 73246 | 544 | 0 |
| 1KB | 32 | G→E | google | edap | 73342 | 543 | 0 |
| 1KB | 32 | G→E | google | edap | 74319 | 549 | 0 |
| 1KB | 32 | G→G | google | google | 36261 | 1187 | 0 |
| 1KB | 32 | G→G | google | google | 36517 | 1185 | 0 |
| 1KB | 32 | G→G | google | google | 36551 | 1166 | 0 |
| 1KB | 32 | G→G | google | google | 36407 | 1164 | 0 |
| 16KB | 1 | E→E | edap | edap | 13302 | 130 | 0 |
| 16KB | 1 | E→E | edap | edap | 13268 | 131 | 0 |
| 16KB | 1 | E→E | edap | edap | 13189 | 130 | 0 |
| 16KB | 1 | E→E | edap | edap | 13327 | 129 | 0 |
| 16KB | 1 | E→G | edap | google | 8860 | 184 | 0 |
| 16KB | 1 | E→G | edap | google | 8824 | 184 | 0 |
| 16KB | 1 | E→G | edap | google | 8853 | 186 | 0 |
| 16KB | 1 | E→G | edap | google | 8910 | 184 | 0 |
| 16KB | 1 | G→E | google | edap | 12761 | 140 | 0 |
| 16KB | 1 | G→E | google | edap | 12736 | 139 | 0 |
| 16KB | 1 | G→E | google | edap | 12758 | 140 | 0 |
| 16KB | 1 | G→E | google | edap | 12931 | 136 | 0 |
| 16KB | 1 | G→G | google | google | 8747 | 195 | 0 |
| 16KB | 1 | G→G | google | google | 8727 | 194 | 0 |
| 16KB | 1 | G→G | google | google | 8753 | 194 | 0 |
| 16KB | 1 | G→G | google | google | 8701 | 192 | 0 |
| 16KB | 8 | E→E | edap | edap | 39436 | 342 | 0 |
| 16KB | 8 | E→E | edap | edap | 39576 | 341 | 0 |
| 16KB | 8 | E→E | edap | edap | 39512 | 341 | 0 |
| 16KB | 8 | E→E | edap | edap | 39593 | 338 | 0 |
| 16KB | 8 | E→G | edap | google | 26247 | 454 | 0 |
| 16KB | 8 | E→G | edap | google | 26256 | 450 | 0 |
| 16KB | 8 | E→G | edap | google | 26362 | 446 | 0 |
| 16KB | 8 | E→G | edap | google | 26227 | 450 | 0 |
| 16KB | 8 | G→E | google | edap | 33494 | 339 | 0 |
| 16KB | 8 | G→E | google | edap | 33563 | 336 | 0 |
| 16KB | 8 | G→E | google | edap | 33440 | 339 | 0 |
| 16KB | 8 | G→E | google | edap | 34260 | 336 | 0 |
| 16KB | 8 | G→G | google | google | 26274 | 441 | 0 |
| 16KB | 8 | G→G | google | google | 26117 | 446 | 0 |
| 16KB | 8 | G→G | google | google | 26130 | 449 | 0 |
| 16KB | 8 | G→G | google | google | 26176 | 446 | 0 |
| 16KB | 32 | E→E | edap | edap | 45378 | 874 | 0 |
| 16KB | 32 | E→E | edap | edap | 44949 | 883 | 0 |
| 16KB | 32 | E→E | edap | edap | 44843 | 880 | 0 |
| 16KB | 32 | E→E | edap | edap | 44831 | 884 | 0 |
| 16KB | 32 | E→G | edap | google | 30458 | 1582 | 0 |
| 16KB | 32 | E→G | edap | google | 30039 | 1595 | 0 |
| 16KB | 32 | E→G | edap | google | 29963 | 1588 | 0 |
| 16KB | 32 | E→G | edap | google | 30144 | 1595 | 0 |
| 16KB | 32 | G→E | google | edap | 46953 | 973 | 0 |
| 16KB | 32 | G→E | google | edap | 46783 | 978 | 0 |
| 16KB | 32 | G→E | google | edap | 46775 | 979 | 0 |
| 16KB | 32 | G→E | google | edap | 46948 | 983 | 0 |
| 16KB | 32 | G→G | google | google | 29188 | 1626 | 0 |
| 16KB | 32 | G→G | google | google | 29129 | 1635 | 0 |
| 16KB | 32 | G→G | google | google | 28808 | 1662 | 0 |
| 16KB | 32 | G→G | google | google | 29171 | 1646 | 0 |
| 64KB | 1 | E→E | edap | edap | 7494 | 209 | 0 |
| 64KB | 1 | E→E | edap | edap | 7524 | 208 | 0 |
| 64KB | 1 | E→E | edap | edap | 7531 | 206 | 0 |
| 64KB | 1 | E→E | edap | edap | 7540 | 207 | 0 |
| 64KB | 1 | E→G | edap | google | 5917 | 259 | 0 |
| 64KB | 1 | E→G | edap | google | 5922 | 260 | 0 |
| 64KB | 1 | E→G | edap | google | 5924 | 259 | 0 |
| 64KB | 1 | E→G | edap | google | 5956 | 259 | 0 |
| 64KB | 1 | G→E | google | edap | 7894 | 213 | 0 |
| 64KB | 1 | G→E | google | edap | 7803 | 224 | 0 |
| 64KB | 1 | G→E | google | edap | 7906 | 212 | 0 |
| 64KB | 1 | G→E | google | edap | 7872 | 211 | 0 |
| 64KB | 1 | G→G | google | google | 6100 | 268 | 0 |
| 64KB | 1 | G→G | google | google | 6296 | 247 | 0 |
| 64KB | 1 | G→G | google | google | 6248 | 254 | 0 |
| 64KB | 1 | G→G | google | google | 6298 | 250 | 0 |
| 64KB | 8 | E→E | edap | edap | 18732 | 705 | 0 |
| 64KB | 8 | E→E | edap | edap | 18822 | 690 | 0 |
| 64KB | 8 | E→E | edap | edap | 18756 | 704 | 0 |
| 64KB | 8 | E→E | edap | edap | 18730 | 699 | 0 |
| 64KB | 8 | E→G | edap | google | 14516 | 834 | 0 |
| 64KB | 8 | E→G | edap | google | 14548 | 831 | 0 |
| 64KB | 8 | E→G | edap | google | 14616 | 831 | 0 |
| 64KB | 8 | E→G | edap | google | 14520 | 837 | 0 |
| 64KB | 8 | G→E | google | edap | 18158 | 722 | 0 |
| 64KB | 8 | G→E | google | edap | 18092 | 726 | 0 |
| 64KB | 8 | G→E | google | edap | 18220 | 725 | 0 |
| 64KB | 8 | G→E | google | edap | 18238 | 710 | 0 |
| 64KB | 8 | G→G | google | google | 15185 | 759 | 0 |
| 64KB | 8 | G→G | google | google | 15189 | 765 | 0 |
| 64KB | 8 | G→G | google | google | 15151 | 765 | 0 |
| 64KB | 8 | G→G | google | google | 15222 | 755 | 0 |
| 64KB | 32 | E→E | edap | edap | 20931 | 2682 | 0 |
| 64KB | 32 | E→E | edap | edap | 21200 | 2707 | 0 |
| 64KB | 32 | E→E | edap | edap | 20786 | 2769 | 0 |
| 64KB | 32 | E→E | edap | edap | 20800 | 2753 | 0 |
| 64KB | 32 | E→G | edap | google | 14980 | 7181 | 0 |
| 64KB | 32 | E→G | edap | google | 15216 | 2954 | 0 |
| 64KB | 32 | E→G | edap | google | 15170 | 2978 | 0 |
| 64KB | 32 | E→G | edap | google | 15184 | 2957 | 0 |
| 64KB | 32 | G→E | google | edap | 22097 | 2879 | 0 |
| 64KB | 32 | G→E | google | edap | 22180 | 2902 | 0 |
| 64KB | 32 | G→E | google | edap | 22105 | 2890 | 0 |
| 64KB | 32 | G→E | google | edap | 22064 | 2955 | 0 |
| 64KB | 32 | G→G | google | google | 15472 | 2795 | 0 |
| 64KB | 32 | G→G | google | google | 15458 | 2783 | 0 |
| 64KB | 32 | G→G | google | google | 15366 | 2821 | 0 |
| 64KB | 32 | G→G | google | google | 15432 | 2833 | 0 |

## 五、编解码器 micro 对比

| 负载 | 线上字节数 | 两边字节完全相同 | edap 编码核心 ns/op | google 编码核心 ns/op | 编码核心比 | edap 编码(产出 byte[]) ns/op | google 编码(产出 byte[]) ns/op | 编码比 | edap 解码 ns/op | google 解码 ns/op | 解码比 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 0B | 23 | 是 | 11 | 28 | 0.39x | 21 | 30 | 0.69x | 22 | 26 | 0.87x |
| 1KB | 1050 | 是 | 28 | 46 | 0.61x | 59 | 65 | 0.90x | 53 | 55 | 0.96x |
| 16KB | 16411 | 是 | 330 | 380 | 0.87x | 1023 | 720 | 1.42x | 735 | 749 | 0.98x |
| 64KB | 65563 | 是 | 1304 | 1350 | 0.97x | 2836 | 2013 | 1.41x | 1594 | 1595 | 1.00x |

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
