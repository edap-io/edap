# edap 原生 gRPC vs google grpc-java —— 2×2 矩阵性能对比

> ⚠️ **这批数据已作废** —— 测的是**服务端七个瓶颈修完之前**的服务端。
> 作废原因：根因七（出站缓冲平方级拷贝）只在**连接复用**时暴露，
> 而矩阵每臂跑 4 轮 × 3 秒 ⇒ 复用连接的退化效应被算进了平均值 ⇒ 数字被系统性拉低。
> 当前有效批次：[`matrix-20261112-fix-1.md`](.) / [`fix-2`](.) / [`fix-3`](.)，
> 中位数见 [`matrix-summary.md`](.)。


生成时间: Thu Nov 12 15:09:24 CST 2026

运行环境: Mac OS X 27.2.1 / aarch64 / JDK 17.0.20.1 / 10 核

被测版本: grpc-java 1.85.1 / protobuf-java 3.25.9 / protoc 3.25.9 / edap 自研 protobuf（edap-protobuf，无 google 依赖）

测量口径: 预热 1000ms + 测量窗口 3000ms（时间制）；4 个臂 × 4 轮，**拉丁方调度**（每个臂在每个位置各跑一次）；N 个 worker 线程共享一条连接；延迟单位微秒

## 一、端到端 unary 性能（E→E vs G→G）

| 负载 | 并发 | edap req/s | google req/s | edap/google | edap p50(us) | google p50(us) | edap p99(us) | google p99(us) |
|---|---|---|---|---|---|---|---|---|
| 0B | 1 | 18185 | 9975 | 1.82x | 51 | 94 | 103 | 169 |
| 0B | 8 | 62677 | 33432 | 1.87x | 124 | 235 | 203 | 356 |
| 0B | 32 | 71545 | 37693 | 1.90x | 445 | 829 | 580 | 1246 |
| 1KB | 1 | 18630 | 10014 | 1.86x | 51 | 95 | 93 | 167 |
| 1KB | 8 | 60804 | 32691 | 1.86x | 128 | 240 | 210 | 363 |
| 1KB | 32 | 70109 | 37020 | 1.89x | 455 | 858 | 580 | 1143 |
| 16KB | 1 | 12668 | 8719 | 1.45x | 76 | 111 | 126 | 186 |
| 16KB | 8 | 37897 | 26366 | 1.44x | 204 | 298 | 349 | 436 |
| 16KB | 32 | 42926 | 29660 | 1.45x | 745 | 1070 | 948 | 1599 |
| 64KB | 1 | 6858 | 6293 | 1.09x | 138 | 153 | 218 | 239 |
| 64KB | 8 | 17212 | 15293 | 1.13x | 448 | 519 | 820 | 748 |
| 64KB | 32 | 19062 | 15824 | 1.20x | 1582 | 2002 | 2756 | 2812 |

## 二、2×2 矩阵（客户端 × 服务端）

| 负载 | 并发 | E→E | E→G | G→E | G→G | 换客户端(G→E/E→E) | 换服务端(E→G/E→E) | 全栈(G→G/E→E) | 交互项 |
|---|---|---|---|---|---|---|---|---|---|
| 0B | 1 | 18185 | 10848 | 15777 | 9975 | 0.87x | 0.60x | 0.55x | 1.06x |
| 0B | 8 | 62677 | 34657 | 60206 | 33432 | 0.96x | 0.55x | 0.53x | 1.00x |
| 0B | 32 | 71545 | 40000 | 76908 | 37693 | 1.07x | 0.56x | 0.53x | 0.88x |
| 1KB | 1 | 18630 | 10894 | 15671 | 10014 | 0.84x | 0.58x | 0.54x | 1.09x |
| 1KB | 8 | 60804 | 33747 | 58652 | 32691 | 0.96x | 0.56x | 0.54x | 1.00x |
| 1KB | 32 | 70109 | 39294 | 73969 | 37020 | 1.06x | 0.56x | 0.53x | 0.89x |
| 16KB | 1 | 12668 | 8703 | 12507 | 8719 | 0.99x | 0.69x | 0.69x | 1.01x |
| 16KB | 8 | 37897 | 26141 | 32942 | 26366 | 0.87x | 0.69x | 0.70x | 1.16x |
| 16KB | 32 | 42926 | 30060 | 42872 | 29660 | 1.00x | 0.70x | 0.69x | 0.99x |
| 64KB | 1 | 6858 | 5862 | 7250 | 6293 | 1.06x | 0.85x | 0.92x | 1.02x |
| 64KB | 8 | 17212 | 14598 | 16529 | 15293 | 0.96x | 0.85x | 0.89x | 1.09x |
| 64KB | 32 | 19062 | 15396 | 4011 | 15824 | 0.21x | 0.81x | 0.83x | 4.88x |

> **换客户端** = 同一个 edap 服务端上，把客户端换成 grpc-java 的提升（`G→E / E→E`）—— **服务端本身不变**，所以这一列是客户端这一侧的贡献。
> **换服务端** = 同一个 edap 客户端上，把服务端换成 grpc-java 的提升（`E→G / E→E`）—— **客户端本身不变**，所以这一列是服务端这一侧的贡献。
> **全栈** = 两边都换（`G→G / E→E`）。**交互项** = 全栈 ÷ (换客户端 × 换服务端)：接近 1 表示两个维度可以相加、互不影响；明显偏离 1 表示两者有耦合（比如 grpc-java 的服务端在 grpc-java 的客户端上才发挥得出来）。

## 三、归因小结

| 负载 | 并发 | 客户端侧差距(G→E/E→E) | 服务端侧差距(E→G/E→E) | 全栈差距 | 谁是主要瓶颈 |
|---|---|---|---|---|---|
| 0B | 1 | 0.87x | 0.60x | 0.55x | **客户端** |
| 0B | 8 | 0.96x | 0.55x | 0.53x | **客户端** |
| 0B | 32 | 1.07x | 0.56x | 0.53x | **客户端** |
| 1KB | 1 | 0.84x | 0.58x | 0.54x | **客户端** |
| 1KB | 8 | 0.96x | 0.56x | 0.54x | **客户端** |
| 1KB | 32 | 1.06x | 0.56x | 0.53x | **客户端** |
| 16KB | 1 | 0.99x | 0.69x | 0.69x | **客户端** |
| 16KB | 8 | 0.87x | 0.69x | 0.70x | **客户端** |
| 16KB | 32 | 1.00x | 0.70x | 0.69x | **客户端** |
| 64KB | 1 | 1.06x | 0.85x | 0.92x | **客户端** |
| 64KB | 8 | 0.96x | 0.85x | 0.89x | 两侧相当 |
| 64KB | 32 | 0.21x | 0.81x | 0.83x | **服务端** |

> **客户端侧差距** = 在**同一个 edap 服务端**上换客户端（`G→E / E→E`）—— 服务端不变，所以这一列只反映客户端。
> **服务端侧差距** = 在**同一个 edap 客户端**上换服务端（`E→G / E→E`）—— 客户端不变，所以这一列只反映服务端。

## 四、原始轮次（每臂 4 轮）

| 负载 | 并发 | 臂 | 客户端 | 服务端 | 轮次 req/s | 轮次 p99(us) | 错误数 |
|---|---|---|---|---|---|---|---|
| 0B | 1 | E→E | edap | edap | 15474 | 174 | 0 |
| 0B | 1 | E→E | edap | edap | 19001 | 88 | 0 |
| 0B | 1 | E→E | edap | edap | 17869 | 109 | 0 |
| 0B | 1 | E→E | edap | edap | 18501 | 97 | 0 |
| 0B | 1 | E→G | edap | google | 9917 | 185 | 0 |
| 0B | 1 | E→G | edap | google | 10876 | 155 | 0 |
| 0B | 1 | E→G | edap | google | 10820 | 153 | 0 |
| 0B | 1 | E→G | edap | google | 11047 | 150 | 0 |
| 0B | 1 | G→E | google | edap | 15278 | 123 | 0 |
| 0B | 1 | G→E | google | edap | 15685 | 116 | 0 |
| 0B | 1 | G→E | google | edap | 16145 | 103 | 0 |
| 0B | 1 | G→E | google | edap | 15869 | 111 | 0 |
| 0B | 1 | G→G | google | google | 9936 | 172 | 0 |
| 0B | 1 | G→G | google | google | 10015 | 167 | 0 |
| 0B | 1 | G→G | google | google | 10270 | 166 | 0 |
| 0B | 1 | G→G | google | google | 9868 | 171 | 0 |
| 0B | 8 | E→E | edap | edap | 62250 | 206 | 0 |
| 0B | 8 | E→E | edap | edap | 62770 | 203 | 0 |
| 0B | 8 | E→E | edap | edap | 62707 | 202 | 0 |
| 0B | 8 | E→E | edap | edap | 62647 | 202 | 0 |
| 0B | 8 | E→G | edap | google | 34414 | 353 | 0 |
| 0B | 8 | E→G | edap | google | 34626 | 349 | 0 |
| 0B | 8 | E→G | edap | google | 34689 | 345 | 0 |
| 0B | 8 | E→G | edap | google | 34749 | 349 | 0 |
| 0B | 8 | G→E | google | edap | 59979 | 192 | 0 |
| 0B | 8 | G→E | google | edap | 60004 | 191 | 0 |
| 0B | 8 | G→E | google | edap | 60408 | 189 | 0 |
| 0B | 8 | G→E | google | edap | 60773 | 193 | 0 |
| 0B | 8 | G→G | google | google | 33310 | 357 | 0 |
| 0B | 8 | G→G | google | google | 33372 | 357 | 0 |
| 0B | 8 | G→G | google | google | 33492 | 355 | 0 |
| 0B | 8 | G→G | google | google | 33528 | 354 | 0 |
| 0B | 32 | E→E | edap | edap | 72327 | 563 | 0 |
| 0B | 32 | E→E | edap | edap | 70239 | 593 | 0 |
| 0B | 32 | E→E | edap | edap | 70763 | 579 | 0 |
| 0B | 32 | E→E | edap | edap | 72572 | 582 | 0 |
| 0B | 32 | E→G | edap | google | 39973 | 1174 | 0 |
| 0B | 32 | E→G | edap | google | 39925 | 1216 | 0 |
| 0B | 32 | E→G | edap | google | 40169 | 1174 | 0 |
| 0B | 32 | E→G | edap | google | 40027 | 1203 | 0 |
| 0B | 32 | G→E | google | edap | 77833 | 507 | 0 |
| 0B | 32 | G→E | google | edap | 72830 | 839 | 0 |
| 0B | 32 | G→E | google | edap | 77310 | 506 | 0 |
| 0B | 32 | G→E | google | edap | 76506 | 542 | 0 |
| 0B | 32 | G→G | google | google | 36982 | 1242 | 0 |
| 0B | 32 | G→G | google | google | 37419 | 1261 | 0 |
| 0B | 32 | G→G | google | google | 37968 | 1249 | 0 |
| 0B | 32 | G→G | google | google | 38417 | 1193 | 0 |
| 1KB | 1 | E→E | edap | edap | 18620 | 92 | 0 |
| 1KB | 1 | E→E | edap | edap | 18624 | 94 | 0 |
| 1KB | 1 | E→E | edap | edap | 18635 | 92 | 0 |
| 1KB | 1 | E→E | edap | edap | 18639 | 93 | 0 |
| 1KB | 1 | E→G | edap | google | 10889 | 149 | 0 |
| 1KB | 1 | E→G | edap | google | 10899 | 150 | 0 |
| 1KB | 1 | E→G | edap | google | 10879 | 151 | 0 |
| 1KB | 1 | E→G | edap | google | 10981 | 148 | 0 |
| 1KB | 1 | G→E | google | edap | 15581 | 111 | 0 |
| 1KB | 1 | G→E | google | edap | 15658 | 110 | 0 |
| 1KB | 1 | G→E | google | edap | 15685 | 109 | 0 |
| 1KB | 1 | G→E | google | edap | 15839 | 110 | 0 |
| 1KB | 1 | G→G | google | google | 10000 | 165 | 0 |
| 1KB | 1 | G→G | google | google | 9685 | 174 | 0 |
| 1KB | 1 | G→G | google | google | 10028 | 163 | 0 |
| 1KB | 1 | G→G | google | google | 10115 | 169 | 0 |
| 1KB | 8 | E→E | edap | edap | 60842 | 209 | 0 |
| 1KB | 8 | E→E | edap | edap | 60822 | 210 | 0 |
| 1KB | 8 | E→E | edap | edap | 60525 | 211 | 0 |
| 1KB | 8 | E→E | edap | edap | 60786 | 209 | 0 |
| 1KB | 8 | E→G | edap | google | 33771 | 355 | 0 |
| 1KB | 8 | E→G | edap | google | 33837 | 355 | 0 |
| 1KB | 8 | E→G | edap | google | 33491 | 361 | 0 |
| 1KB | 8 | E→G | edap | google | 33722 | 355 | 0 |
| 1KB | 8 | G→E | google | edap | 58221 | 197 | 0 |
| 1KB | 8 | G→E | google | edap | 58847 | 194 | 0 |
| 1KB | 8 | G→E | google | edap | 58486 | 195 | 0 |
| 1KB | 8 | G→E | google | edap | 58818 | 195 | 0 |
| 1KB | 8 | G→G | google | google | 32597 | 366 | 0 |
| 1KB | 8 | G→G | google | google | 32785 | 360 | 0 |
| 1KB | 8 | G→G | google | google | 32207 | 371 | 0 |
| 1KB | 8 | G→G | google | google | 32866 | 360 | 0 |
| 1KB | 32 | E→E | edap | edap | 71004 | 572 | 0 |
| 1KB | 32 | E→E | edap | edap | 70227 | 580 | 0 |
| 1KB | 32 | E→E | edap | edap | 69887 | 582 | 0 |
| 1KB | 32 | E→E | edap | edap | 69991 | 580 | 0 |
| 1KB | 32 | E→G | edap | google | 39668 | 1148 | 0 |
| 1KB | 32 | E→G | edap | google | 39049 | 1184 | 0 |
| 1KB | 32 | E→G | edap | google | 38682 | 1203 | 0 |
| 1KB | 32 | E→G | edap | google | 39539 | 1147 | 0 |
| 1KB | 32 | G→E | google | edap | 73579 | 536 | 0 |
| 1KB | 32 | G→E | google | edap | 73619 | 529 | 0 |
| 1KB | 32 | G→E | google | edap | 74319 | 526 | 0 |
| 1KB | 32 | G→E | google | edap | 77177 | 542 | 0 |
| 1KB | 32 | G→G | google | google | 37253 | 1142 | 0 |
| 1KB | 32 | G→G | google | google | 37131 | 1143 | 0 |
| 1KB | 32 | G→G | google | google | 36574 | 1169 | 0 |
| 1KB | 32 | G→G | google | google | 36909 | 1135 | 0 |
| 16KB | 1 | E→E | edap | edap | 12672 | 126 | 0 |
| 16KB | 1 | E→E | edap | edap | 12611 | 129 | 0 |
| 16KB | 1 | E→E | edap | edap | 12665 | 126 | 0 |
| 16KB | 1 | E→E | edap | edap | 12686 | 126 | 0 |
| 16KB | 1 | E→G | edap | google | 8677 | 178 | 0 |
| 16KB | 1 | E→G | edap | google | 8702 | 177 | 0 |
| 16KB | 1 | E→G | edap | google | 8703 | 179 | 0 |
| 16KB | 1 | E→G | edap | google | 8790 | 179 | 0 |
| 16KB | 1 | G→E | google | edap | 12477 | 133 | 0 |
| 16KB | 1 | G→E | google | edap | 12523 | 132 | 0 |
| 16KB | 1 | G→E | google | edap | 12491 | 135 | 0 |
| 16KB | 1 | G→E | google | edap | 12572 | 134 | 0 |
| 16KB | 1 | G→G | google | google | 8701 | 186 | 0 |
| 16KB | 1 | G→G | google | google | 8659 | 185 | 0 |
| 16KB | 1 | G→G | google | google | 8740 | 185 | 0 |
| 16KB | 1 | G→G | google | google | 8737 | 186 | 0 |
| 16KB | 8 | E→E | edap | edap | 37896 | 349 | 0 |
| 16KB | 8 | E→E | edap | edap | 37899 | 349 | 0 |
| 16KB | 8 | E→E | edap | edap | 37896 | 351 | 0 |
| 16KB | 8 | E→E | edap | edap | 37954 | 348 | 0 |
| 16KB | 8 | E→G | edap | google | 26144 | 448 | 0 |
| 16KB | 8 | E→G | edap | google | 26151 | 447 | 0 |
| 16KB | 8 | E→G | edap | google | 26139 | 448 | 0 |
| 16KB | 8 | E→G | edap | google | 26086 | 449 | 0 |
| 16KB | 8 | G→E | google | edap | 32723 | 342 | 0 |
| 16KB | 8 | G→E | google | edap | 33024 | 336 | 0 |
| 16KB | 8 | G→E | google | edap | 32862 | 340 | 0 |
| 16KB | 8 | G→E | google | edap | 33022 | 337 | 0 |
| 16KB | 8 | G→G | google | google | 26334 | 438 | 0 |
| 16KB | 8 | G→G | google | google | 26398 | 433 | 0 |
| 16KB | 8 | G→G | google | google | 26413 | 436 | 0 |
| 16KB | 8 | G→G | google | google | 26332 | 435 | 0 |
| 16KB | 32 | E→E | edap | edap | 42960 | 948 | 0 |
| 16KB | 32 | E→E | edap | edap | 43025 | 946 | 0 |
| 16KB | 32 | E→E | edap | edap | 42726 | 947 | 0 |
| 16KB | 32 | E→E | edap | edap | 42893 | 948 | 0 |
| 16KB | 32 | E→G | edap | google | 29608 | 1628 | 0 |
| 16KB | 32 | E→G | edap | google | 30084 | 1607 | 0 |
| 16KB | 32 | E→G | edap | google | 30036 | 1610 | 0 |
| 16KB | 32 | E→G | edap | google | 30222 | 1610 | 0 |
| 16KB | 32 | G→E | google | edap | 43430 | 1930 | 0 |
| 16KB | 32 | G→E | google | edap | 43093 | 1976 | 0 |
| 16KB | 32 | G→E | google | edap | 42651 | 2073 | 0 |
| 16KB | 32 | G→E | google | edap | 42324 | 2070 | 0 |
| 16KB | 32 | G→G | google | google | 28701 | 1656 | 0 |
| 16KB | 32 | G→G | google | google | 30062 | 1550 | 0 |
| 16KB | 32 | G→G | google | google | 29257 | 1634 | 0 |
| 16KB | 32 | G→G | google | google | 30080 | 1564 | 0 |
| 64KB | 1 | E→E | edap | edap | 7004 | 217 | 0 |
| 64KB | 1 | E→E | edap | edap | 6824 | 220 | 0 |
| 64KB | 1 | E→E | edap | edap | 6891 | 216 | 0 |
| 64KB | 1 | E→E | edap | edap | 6814 | 219 | 0 |
| 64KB | 1 | E→G | edap | google | 5877 | 255 | 0 |
| 64KB | 1 | E→G | edap | google | 5847 | 258 | 0 |
| 64KB | 1 | E→G | edap | google | 5801 | 261 | 0 |
| 64KB | 1 | E→G | edap | google | 5893 | 252 | 0 |
| 64KB | 1 | G→E | google | edap | 7284 | 215 | 0 |
| 64KB | 1 | G→E | google | edap | 7253 | 219 | 0 |
| 64KB | 1 | G→E | google | edap | 7188 | 219 | 0 |
| 64KB | 1 | G→E | google | edap | 7248 | 216 | 0 |
| 64KB | 1 | G→G | google | google | 6278 | 238 | 0 |
| 64KB | 1 | G→G | google | google | 6316 | 234 | 0 |
| 64KB | 1 | G→G | google | google | 6302 | 245 | 0 |
| 64KB | 1 | G→G | google | google | 6285 | 239 | 0 |
| 64KB | 8 | E→E | edap | edap | 17115 | 868 | 0 |
| 64KB | 8 | E→E | edap | edap | 17176 | 847 | 0 |
| 64KB | 8 | E→E | edap | edap | 17251 | 793 | 0 |
| 64KB | 8 | E→E | edap | edap | 17249 | 769 | 0 |
| 64KB | 8 | E→G | edap | google | 14560 | 839 | 0 |
| 64KB | 8 | E→G | edap | google | 14637 | 840 | 0 |
| 64KB | 8 | E→G | edap | google | 14638 | 833 | 0 |
| 64KB | 8 | E→G | edap | google | 14549 | 836 | 0 |
| 64KB | 8 | G→E | google | edap | 16492 | 1141 | 0 |
| 64KB | 8 | G→E | google | edap | 16512 | 1077 | 0 |
| 64KB | 8 | G→E | google | edap | 16584 | 1070 | 0 |
| 64KB | 8 | G→E | google | edap | 16546 | 1087 | 0 |
| 64KB | 8 | G→G | google | google | 15249 | 753 | 0 |
| 64KB | 8 | G→G | google | google | 15318 | 743 | 0 |
| 64KB | 8 | G→G | google | google | 15300 | 749 | 0 |
| 64KB | 8 | G→G | google | google | 15286 | 747 | 0 |
| 64KB | 32 | E→E | edap | edap | 19001 | 2748 | 0 |
| 64KB | 32 | E→E | edap | edap | 19100 | 2745 | 0 |
| 64KB | 32 | E→E | edap | edap | 19065 | 2764 | 0 |
| 64KB | 32 | E→E | edap | edap | 19058 | 2797 | 0 |
| 64KB | 32 | E→G | edap | google | 15071 | 3011 | 0 |
| 64KB | 32 | E→G | edap | google | 15414 | 2950 | 0 |
| 64KB | 32 | E→G | edap | google | 15378 | 2995 | 0 |
| 64KB | 32 | E→G | edap | google | 15430 | 3023 | 0 |
| 64KB | 32 | G→E | google | edap | 4411 | 18482 | 0 |
| 64KB | 32 | G→E | google | edap | 4552 | 17455 | 0 |
| 64KB | 32 | G→E | google | edap | 2562 | 30825 | 0 |
| 64KB | 32 | G→E | google | edap | 3610 | 25635 | 0 |
| 64KB | 32 | G→G | google | google | 15699 | 2856 | 0 |
| 64KB | 32 | G→G | google | google | 15874 | 2767 | 0 |
| 64KB | 32 | G→G | google | google | 15970 | 2751 | 0 |
| 64KB | 32 | G→G | google | google | 15774 | 2931 | 0 |

## 五、编解码器 micro 对比

| 负载 | 线上字节数 | 两边字节完全相同 | edap 编码 ns/op | google 编码 ns/op | edap 解码 ns/op | google 解码 ns/op | 编码比 | 解码比 |
|---|---|---|---|---|---|---|---|---|
| 0B | 25 | **否** | 21 | 30 | 48 | 26 | 0.72x | 1.81x |
| 1KB | 1050 | 是 | 57 | 65 | 61 | 56 | 0.88x | 1.09x |
| 16KB | 16411 | 是 | 1076 | 736 | 764 | 754 | 1.46x | 1.01x |
| 64KB | 65563 | 是 | 2943 | 1998 | 1618 | 1601 | 1.47x | 1.01x |

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
