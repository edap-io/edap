# edap 原生 gRPC vs google grpc-java —— 2×2 矩阵性能对比

> ⚠️ **这批数据已作废** —— 测的是**服务端七个瓶颈修完之前**的服务端。
> 作废原因：根因七（出站缓冲平方级拷贝）只在**连接复用**时暴露，
> 而矩阵每臂跑 4 轮 × 3 秒 ⇒ 复用连接的退化效应被算进了平均值 ⇒ 数字被系统性拉低。
> 当前有效批次：[`matrix-20261112-fix-1.md`](.) / [`fix-2`](.) / [`fix-3`](.)，
> 中位数见 [`matrix-summary.md`](.)。


生成时间: Thu Nov 12 15:22:42 CST 2026

运行环境: Mac OS X 27.2.1 / aarch64 / JDK 17.0.20.1 / 10 核

被测版本: grpc-java 1.85.1 / protobuf-java 3.25.9 / protoc 3.25.9 / edap 自研 protobuf（edap-protobuf，无 google 依赖）

测量口径: 预热 1000ms + 测量窗口 3000ms（时间制）；4 个臂 × 4 轮，**拉丁方调度**（每个臂在每个位置各跑一次）；N 个 worker 线程共享一条连接；延迟单位微秒

## 一、端到端 unary 性能（E→E vs G→G）

| 负载 | 并发 | edap req/s | google req/s | edap/google | edap p50(us) | google p50(us) | edap p99(us) | google p99(us) |
|---|---|---|---|---|---|---|---|---|
| 0B | 1 | 19115 | 10576 | 1.81x | 50 | 92 | 83 | 153 |
| 0B | 8 | 64174 | 33540 | 1.91x | 121 | 234 | 198 | 352 |
| 0B | 32 | 73818 | 39483 | 1.87x | 433 | 802 | 543 | 1083 |
| 1KB | 1 | 19139 | 10496 | 1.82x | 50 | 93 | 83 | 152 |
| 1KB | 8 | 62370 | 33074 | 1.89x | 124 | 237 | 203 | 356 |
| 1KB | 32 | 72358 | 37311 | 1.94x | 442 | 850 | 554 | 1134 |
| 16KB | 1 | 12944 | 9088 | 1.42x | 75 | 107 | 118 | 185 |
| 16KB | 8 | 38808 | 26385 | 1.47x | 199 | 297 | 339 | 437 |
| 16KB | 32 | 44488 | 30356 | 1.47x | 711 | 1047 | 911 | 1540 |
| 64KB | 1 | 7062 | 6520 | 1.08x | 137 | 150 | 212 | 218 |
| 64KB | 8 | 17879 | 15479 | 1.16x | 432 | 513 | 741 | 737 |
| 64KB | 32 | 19410 | 15986 | 1.21x | 1556 | 1976 | 2732 | 2796 |

## 二、2×2 矩阵（客户端 × 服务端）

| 负载 | 并发 | E→E | E→G | G→E | G→G | 换客户端(G→E/E→E) | 换服务端(E→G/E→E) | 全栈(G→G/E→E) | 交互项 |
|---|---|---|---|---|---|---|---|---|---|
| 0B | 1 | 19115 | 11514 | 16375 | 10576 | 0.86x | 0.60x | 0.55x | 1.07x |
| 0B | 8 | 64174 | 34846 | 60730 | 33540 | 0.95x | 0.54x | 0.52x | 1.02x |
| 0B | 32 | 73818 | 41715 | 78732 | 39483 | 1.07x | 0.57x | 0.53x | 0.89x |
| 1KB | 1 | 19139 | 11231 | 16203 | 10496 | 0.85x | 0.59x | 0.55x | 1.10x |
| 1KB | 8 | 62370 | 34100 | 59076 | 33074 | 0.95x | 0.55x | 0.53x | 1.02x |
| 1KB | 32 | 72358 | 40674 | 73461 | 37311 | 1.02x | 0.56x | 0.52x | 0.90x |
| 16KB | 1 | 12944 | 9151 | 12934 | 9088 | 1.00x | 0.71x | 0.70x | 0.99x |
| 16KB | 8 | 38808 | 26224 | 33592 | 26385 | 0.87x | 0.68x | 0.68x | 1.16x |
| 16KB | 32 | 44488 | 30411 | 44811 | 30356 | 1.01x | 0.68x | 0.68x | 0.99x |
| 64KB | 1 | 7062 | 6051 | 7128 | 6520 | 1.01x | 0.86x | 0.92x | 1.07x |
| 64KB | 8 | 17879 | 14411 | 17288 | 15479 | 0.97x | 0.81x | 0.87x | 1.11x |
| 64KB | 32 | 19410 | 15282 | 2927 | 15986 | 0.15x | 0.79x | 0.82x | 6.94x |

> **换客户端** = 同一个 edap 服务端上，把客户端换成 grpc-java 的提升（`G→E / E→E`）—— **服务端本身不变**，所以这一列是客户端这一侧的贡献。
> **换服务端** = 同一个 edap 客户端上，把服务端换成 grpc-java 的提升（`E→G / E→E`）—— **客户端本身不变**，所以这一列是服务端这一侧的贡献。
> **全栈** = 两边都换（`G→G / E→E`）。**交互项** = 全栈 ÷ (换客户端 × 换服务端)：接近 1 表示两个维度可以相加、互不影响；明显偏离 1 表示两者有耦合（比如 grpc-java 的服务端在 grpc-java 的客户端上才发挥得出来）。

## 三、归因小结

| 负载 | 并发 | 客户端侧差距(G→E/E→E) | 服务端侧差距(E→G/E→E) | 全栈差距 | 谁是主要瓶颈 |
|---|---|---|---|---|---|
| 0B | 1 | 0.86x | 0.60x | 0.55x | **客户端** |
| 0B | 8 | 0.95x | 0.54x | 0.52x | **客户端** |
| 0B | 32 | 1.07x | 0.57x | 0.53x | **客户端** |
| 1KB | 1 | 0.85x | 0.59x | 0.55x | **客户端** |
| 1KB | 8 | 0.95x | 0.55x | 0.53x | **客户端** |
| 1KB | 32 | 1.02x | 0.56x | 0.52x | **客户端** |
| 16KB | 1 | 1.00x | 0.71x | 0.70x | **客户端** |
| 16KB | 8 | 0.87x | 0.68x | 0.68x | **客户端** |
| 16KB | 32 | 1.01x | 0.68x | 0.68x | **客户端** |
| 64KB | 1 | 1.01x | 0.86x | 0.92x | 两侧相当 |
| 64KB | 8 | 0.97x | 0.81x | 0.87x | 两侧相当 |
| 64KB | 32 | 0.15x | 0.79x | 0.82x | **服务端** |

> **客户端侧差距** = 在**同一个 edap 服务端**上换客户端（`G→E / E→E`）—— 服务端不变，所以这一列只反映客户端。
> **服务端侧差距** = 在**同一个 edap 客户端**上换服务端（`E→G / E→E`）—— 客户端不变，所以这一列只反映服务端。

## 四、原始轮次（每臂 4 轮）

| 负载 | 并发 | 臂 | 客户端 | 服务端 | 轮次 req/s | 轮次 p99(us) | 错误数 |
|---|---|---|---|---|---|---|---|
| 0B | 1 | E→E | edap | edap | 18867 | 84 | 0 |
| 0B | 1 | E→E | edap | edap | 19132 | 85 | 0 |
| 0B | 1 | E→E | edap | edap | 19098 | 83 | 0 |
| 0B | 1 | E→E | edap | edap | 19238 | 83 | 0 |
| 0B | 1 | E→G | edap | google | 9883 | 240 | 0 |
| 0B | 1 | E→G | edap | google | 11517 | 143 | 0 |
| 0B | 1 | E→G | edap | google | 11719 | 140 | 0 |
| 0B | 1 | E→G | edap | google | 11510 | 142 | 0 |
| 0B | 1 | G→E | google | edap | 16214 | 104 | 0 |
| 0B | 1 | G→E | google | edap | 16357 | 97 | 0 |
| 0B | 1 | G→E | google | edap | 16488 | 98 | 0 |
| 0B | 1 | G→E | google | edap | 16393 | 98 | 0 |
| 0B | 1 | G→G | google | google | 10513 | 155 | 0 |
| 0B | 1 | G→G | google | google | 10565 | 153 | 0 |
| 0B | 1 | G→G | google | google | 10586 | 154 | 0 |
| 0B | 1 | G→G | google | google | 10602 | 151 | 0 |
| 0B | 8 | E→E | edap | edap | 63556 | 201 | 0 |
| 0B | 8 | E→E | edap | edap | 64056 | 198 | 0 |
| 0B | 8 | E→E | edap | edap | 64343 | 197 | 0 |
| 0B | 8 | E→E | edap | edap | 64291 | 197 | 0 |
| 0B | 8 | E→G | edap | google | 34354 | 356 | 0 |
| 0B | 8 | E→G | edap | google | 34771 | 348 | 0 |
| 0B | 8 | E→G | edap | google | 34977 | 342 | 0 |
| 0B | 8 | E→G | edap | google | 34922 | 342 | 0 |
| 0B | 8 | G→E | google | edap | 59988 | 190 | 0 |
| 0B | 8 | G→E | google | edap | 60529 | 187 | 0 |
| 0B | 8 | G→E | google | edap | 60930 | 186 | 0 |
| 0B | 8 | G→E | google | edap | 61223 | 184 | 0 |
| 0B | 8 | G→G | google | google | 33032 | 364 | 0 |
| 0B | 8 | G→G | google | google | 33508 | 352 | 0 |
| 0B | 8 | G→G | google | google | 33924 | 348 | 0 |
| 0B | 8 | G→G | google | google | 33572 | 353 | 0 |
| 0B | 32 | E→E | edap | edap | 74145 | 542 | 0 |
| 0B | 32 | E→E | edap | edap | 73570 | 544 | 0 |
| 0B | 32 | E→E | edap | edap | 74060 | 543 | 0 |
| 0B | 32 | E→E | edap | edap | 73577 | 542 | 0 |
| 0B | 32 | E→G | edap | google | 41745 | 1092 | 0 |
| 0B | 32 | E→G | edap | google | 41968 | 1059 | 0 |
| 0B | 32 | E→G | edap | google | 41685 | 1099 | 0 |
| 0B | 32 | E→G | edap | google | 41654 | 1067 | 0 |
| 0B | 32 | G→E | google | edap | 78276 | 480 | 0 |
| 0B | 32 | G→E | google | edap | 78074 | 493 | 0 |
| 0B | 32 | G→E | google | edap | 79187 | 470 | 0 |
| 0B | 32 | G→E | google | edap | 79492 | 470 | 0 |
| 0B | 32 | G→G | google | google | 39462 | 1078 | 0 |
| 0B | 32 | G→G | google | google | 39194 | 1096 | 0 |
| 0B | 32 | G→G | google | google | 39675 | 1071 | 0 |
| 0B | 32 | G→G | google | google | 39503 | 1088 | 0 |
| 1KB | 1 | E→E | edap | edap | 19139 | 83 | 0 |
| 1KB | 1 | E→E | edap | edap | 19177 | 83 | 0 |
| 1KB | 1 | E→E | edap | edap | 19059 | 84 | 0 |
| 1KB | 1 | E→E | edap | edap | 19139 | 83 | 0 |
| 1KB | 1 | E→G | edap | google | 11340 | 138 | 0 |
| 1KB | 1 | E→G | edap | google | 11348 | 139 | 0 |
| 1KB | 1 | E→G | edap | google | 11123 | 142 | 0 |
| 1KB | 1 | E→G | edap | google | 11053 | 145 | 0 |
| 1KB | 1 | G→E | google | edap | 16209 | 96 | 0 |
| 1KB | 1 | G→E | google | edap | 16223 | 94 | 0 |
| 1KB | 1 | G→E | google | edap | 16175 | 95 | 0 |
| 1KB | 1 | G→E | google | edap | 16198 | 96 | 0 |
| 1KB | 1 | G→G | google | google | 10510 | 152 | 0 |
| 1KB | 1 | G→G | google | google | 10483 | 150 | 0 |
| 1KB | 1 | G→G | google | google | 10518 | 153 | 0 |
| 1KB | 1 | G→G | google | google | 10288 | 156 | 0 |
| 1KB | 8 | E→E | edap | edap | 62506 | 203 | 0 |
| 1KB | 8 | E→E | edap | edap | 62064 | 203 | 0 |
| 1KB | 8 | E→E | edap | edap | 62599 | 203 | 0 |
| 1KB | 8 | E→E | edap | edap | 62234 | 203 | 0 |
| 1KB | 8 | E→G | edap | google | 34432 | 361 | 0 |
| 1KB | 8 | E→G | edap | google | 34057 | 352 | 0 |
| 1KB | 8 | E→G | edap | google | 34144 | 351 | 0 |
| 1KB | 8 | E→G | edap | google | 34049 | 352 | 0 |
| 1KB | 8 | G→E | google | edap | 59142 | 190 | 0 |
| 1KB | 8 | G→E | google | edap | 59196 | 190 | 0 |
| 1KB | 8 | G→E | google | edap | 59010 | 190 | 0 |
| 1KB | 8 | G→E | google | edap | 58945 | 190 | 0 |
| 1KB | 8 | G→G | google | google | 33059 | 358 | 0 |
| 1KB | 8 | G→G | google | google | 33088 | 356 | 0 |
| 1KB | 8 | G→G | google | google | 33221 | 355 | 0 |
| 1KB | 8 | G→G | google | google | 33046 | 356 | 0 |
| 1KB | 32 | E→E | edap | edap | 72431 | 552 | 0 |
| 1KB | 32 | E→E | edap | edap | 72178 | 556 | 0 |
| 1KB | 32 | E→E | edap | edap | 72545 | 553 | 0 |
| 1KB | 32 | E→E | edap | edap | 72285 | 555 | 0 |
| 1KB | 32 | E→G | edap | google | 40729 | 1083 | 0 |
| 1KB | 32 | E→G | edap | google | 40824 | 1082 | 0 |
| 1KB | 32 | E→G | edap | google | 40187 | 1124 | 0 |
| 1KB | 32 | E→G | edap | google | 40620 | 1094 | 0 |
| 1KB | 32 | G→E | google | edap | 73190 | 535 | 0 |
| 1KB | 32 | G→E | google | edap | 79335 | 505 | 0 |
| 1KB | 32 | G→E | google | edap | 73442 | 542 | 0 |
| 1KB | 32 | G→E | google | edap | 73481 | 527 | 0 |
| 1KB | 32 | G→G | google | google | 37217 | 1143 | 0 |
| 1KB | 32 | G→G | google | google | 37145 | 1148 | 0 |
| 1KB | 32 | G→G | google | google | 37405 | 1126 | 0 |
| 1KB | 32 | G→G | google | google | 37446 | 1113 | 0 |
| 16KB | 1 | E→E | edap | edap | 12940 | 118 | 0 |
| 16KB | 1 | E→E | edap | edap | 12915 | 118 | 0 |
| 16KB | 1 | E→E | edap | edap | 12955 | 117 | 0 |
| 16KB | 1 | E→E | edap | edap | 12948 | 118 | 0 |
| 16KB | 1 | E→G | edap | google | 9161 | 164 | 0 |
| 16KB | 1 | E→G | edap | google | 9142 | 166 | 0 |
| 16KB | 1 | E→G | edap | google | 9117 | 168 | 0 |
| 16KB | 1 | E→G | edap | google | 9187 | 164 | 0 |
| 16KB | 1 | G→E | google | edap | 12943 | 117 | 0 |
| 16KB | 1 | G→E | google | edap | 12942 | 117 | 0 |
| 16KB | 1 | G→E | google | edap | 12918 | 120 | 0 |
| 16KB | 1 | G→E | google | edap | 12925 | 121 | 0 |
| 16KB | 1 | G→G | google | google | 9057 | 186 | 0 |
| 16KB | 1 | G→G | google | google | 9115 | 184 | 0 |
| 16KB | 1 | G→G | google | google | 9099 | 182 | 0 |
| 16KB | 1 | G→G | google | google | 9077 | 189 | 0 |
| 16KB | 8 | E→E | edap | edap | 38727 | 344 | 0 |
| 16KB | 8 | E→E | edap | edap | 38765 | 340 | 0 |
| 16KB | 8 | E→E | edap | edap | 38921 | 336 | 0 |
| 16KB | 8 | E→E | edap | edap | 38852 | 339 | 0 |
| 16KB | 8 | E→G | edap | google | 26230 | 443 | 0 |
| 16KB | 8 | E→G | edap | google | 26218 | 443 | 0 |
| 16KB | 8 | E→G | edap | google | 26255 | 442 | 0 |
| 16KB | 8 | E→G | edap | google | 25936 | 472 | 0 |
| 16KB | 8 | G→E | google | edap | 33569 | 322 | 0 |
| 16KB | 8 | G→E | google | edap | 33656 | 321 | 0 |
| 16KB | 8 | G→E | google | edap | 33616 | 323 | 0 |
| 16KB | 8 | G→E | google | edap | 33565 | 323 | 0 |
| 16KB | 8 | G→G | google | google | 26462 | 434 | 0 |
| 16KB | 8 | G→G | google | google | 26308 | 440 | 0 |
| 16KB | 8 | G→G | google | google | 26552 | 432 | 0 |
| 16KB | 8 | G→G | google | google | 26246 | 446 | 0 |
| 16KB | 32 | E→E | edap | edap | 40791 | 949 | 0 |
| 16KB | 32 | E→E | edap | edap | 42773 | 916 | 0 |
| 16KB | 32 | E→E | edap | edap | 46203 | 903 | 0 |
| 16KB | 32 | E→E | edap | edap | 46323 | 907 | 0 |
| 16KB | 32 | E→G | edap | google | 30187 | 1602 | 0 |
| 16KB | 32 | E→G | edap | google | 30605 | 1580 | 0 |
| 16KB | 32 | E→G | edap | google | 30684 | 1584 | 0 |
| 16KB | 32 | E→G | edap | google | 30217 | 1592 | 0 |
| 16KB | 32 | G→E | google | edap | 44751 | 1795 | 0 |
| 16KB | 32 | G→E | google | edap | 44314 | 1801 | 0 |
| 16KB | 32 | G→E | google | edap | 44872 | 1469 | 0 |
| 16KB | 32 | G→E | google | edap | 45448 | 1319 | 0 |
| 16KB | 32 | G→G | google | google | 30484 | 1527 | 0 |
| 16KB | 32 | G→G | google | google | 30463 | 1529 | 0 |
| 16KB | 32 | G→G | google | google | 30249 | 1550 | 0 |
| 16KB | 32 | G→G | google | google | 29440 | 1660 | 0 |
| 64KB | 1 | E→E | edap | edap | 7084 | 211 | 0 |
| 64KB | 1 | E→E | edap | edap | 7058 | 213 | 0 |
| 64KB | 1 | E→E | edap | edap | 7062 | 210 | 0 |
| 64KB | 1 | E→E | edap | edap | 7062 | 213 | 0 |
| 64KB | 1 | E→G | edap | google | 6076 | 245 | 0 |
| 64KB | 1 | E→G | edap | google | 6019 | 244 | 0 |
| 64KB | 1 | E→G | edap | google | 6026 | 245 | 0 |
| 64KB | 1 | E→G | edap | google | 6084 | 245 | 0 |
| 64KB | 1 | G→E | google | edap | 7147 | 199 | 0 |
| 64KB | 1 | G→E | google | edap | 7133 | 201 | 0 |
| 64KB | 1 | G→E | google | edap | 7124 | 201 | 0 |
| 64KB | 1 | G→E | google | edap | 7122 | 204 | 0 |
| 64KB | 1 | G→G | google | google | 6492 | 219 | 0 |
| 64KB | 1 | G→G | google | google | 6522 | 218 | 0 |
| 64KB | 1 | G→G | google | google | 6517 | 216 | 0 |
| 64KB | 1 | G→G | google | google | 6526 | 219 | 0 |
| 64KB | 8 | E→E | edap | edap | 17868 | 735 | 0 |
| 64KB | 8 | E→E | edap | edap | 17875 | 753 | 0 |
| 64KB | 8 | E→E | edap | edap | 17963 | 730 | 0 |
| 64KB | 8 | E→E | edap | edap | 17884 | 746 | 0 |
| 64KB | 8 | E→G | edap | google | 14394 | 836 | 0 |
| 64KB | 8 | E→G | edap | google | 14390 | 830 | 0 |
| 64KB | 8 | E→G | edap | google | 14427 | 830 | 0 |
| 64KB | 8 | E→G | edap | google | 14472 | 823 | 0 |
| 64KB | 8 | G→E | google | edap | 17308 | 805 | 0 |
| 64KB | 8 | G→E | google | edap | 17267 | 768 | 0 |
| 64KB | 8 | G→E | google | edap | 17078 | 890 | 0 |
| 64KB | 8 | G→E | google | edap | 17346 | 763 | 0 |
| 64KB | 8 | G→G | google | google | 15464 | 738 | 0 |
| 64KB | 8 | G→G | google | google | 15495 | 736 | 0 |
| 64KB | 8 | G→G | google | google | 15433 | 745 | 0 |
| 64KB | 8 | G→G | google | google | 15505 | 736 | 0 |
| 64KB | 32 | E→E | edap | edap | 19371 | 2683 | 0 |
| 64KB | 32 | E→E | edap | edap | 19449 | 2718 | 0 |
| 64KB | 32 | E→E | edap | edap | 19462 | 2745 | 0 |
| 64KB | 32 | E→E | edap | edap | 19331 | 2761 | 0 |
| 64KB | 32 | E→G | edap | google | 15286 | 2975 | 0 |
| 64KB | 32 | E→G | edap | google | 15362 | 3020 | 0 |
| 64KB | 32 | E→G | edap | google | 15278 | 3064 | 0 |
| 64KB | 32 | E→G | edap | google | 15265 | 2992 | 0 |
| 64KB | 32 | G→E | google | edap | 2863 | 25443 | 0 |
| 64KB | 32 | G→E | google | edap | 3133 | 20591 | 0 |
| 64KB | 32 | G→E | google | edap | 784 | 25100 | 0 |
| 64KB | 32 | G→E | google | edap | 2990 | 24704 | 0 |
| 64KB | 32 | G→G | google | google | 15957 | 2812 | 0 |
| 64KB | 32 | G→G | google | google | 15892 | 2837 | 0 |
| 64KB | 32 | G→G | google | google | 16081 | 2759 | 0 |
| 64KB | 32 | G→G | google | google | 16015 | 2779 | 0 |

## 五、编解码器 micro 对比

| 负载 | 线上字节数 | 两边字节完全相同 | edap 编码 ns/op | google 编码 ns/op | edap 解码 ns/op | google 解码 ns/op | 编码比 | 解码比 |
|---|---|---|---|---|---|---|---|---|
| 0B | 25 | **否** | 21 | 30 | 47 | 26 | 0.71x | 1.85x |
| 1KB | 1050 | 是 | 58 | 65 | 60 | 56 | 0.90x | 1.09x |
| 16KB | 16411 | 是 | 1010 | 735 | 738 | 743 | 1.37x | 0.99x |
| 64KB | 65563 | 是 | 2970 | 1988 | 1593 | 1593 | 1.49x | 1.00x |

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
