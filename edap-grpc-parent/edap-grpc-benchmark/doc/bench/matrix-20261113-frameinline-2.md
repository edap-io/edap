# edap 原生 gRPC vs google grpc-java —— 2×2 矩阵性能对比

生成时间: Fri Nov 13 01:18:32 CST 2026

运行环境: Mac OS X 27.2.1 / aarch64 / JDK 17.0.20.1 / 10 核

被测版本: grpc-java 1.85.1 / protobuf-java 3.25.9 / protoc 3.25.9 / edap 自研 protobuf（edap-protobuf，无 google 依赖）

测量口径: 预热 1000ms + 测量窗口 3000ms（时间制）；4 个臂 × 4 轮，**拉丁方调度**（每个臂在每个位置各跑一次）；N 个 worker 线程共享一条连接；延迟单位微秒

## 一、端到端 unary 性能（E→E vs G→G）

| 负载 | 并发 | edap req/s | google req/s | edap/google | edap p50(us) | google p50(us) | edap p99(us) | google p99(us) |
|---|---|---|---|---|---|---|---|---|
| 0B | 1 | 18667 | 10014 | 1.86x | 50 | 94 | 97 | 171 |
| 0B | 8 | 64384 | 32123 | 2.00x | 119 | 241 | 203 | 382 |
| 0B | 32 | 78808 | 38103 | 2.07x | 404 | 830 | 515 | 1125 |
| 1KB | 1 | 18592 | 9864 | 1.88x | 51 | 96 | 94 | 172 |
| 1KB | 8 | 63001 | 32063 | 1.96x | 122 | 243 | 207 | 383 |
| 1KB | 32 | 76636 | 34859 | 2.20x | 414 | 905 | 538 | 1313 |
| 16KB | 1 | 13489 | 8825 | 1.53x | 72 | 108 | 119 | 184 |
| 16KB | 8 | 39903 | 26267 | 1.52x | 194 | 299 | 332 | 441 |
| 16KB | 32 | 44963 | 29336 | 1.53x | 715 | 1083 | 879 | 1612 |
| 64KB | 1 | 7665 | 6400 | 1.20x | 125 | 150 | 195 | 236 |
| 64KB | 8 | 18766 | 15335 | 1.22x | 412 | 517 | 695 | 752 |
| 64KB | 32 | 20811 | 15452 | 1.35x | 1491 | 2056 | 2809 | 2800 |

## 二、2×2 矩阵（客户端 × 服务端）

| 负载 | 并发 | E→E | E→G | G→E | G→G | 换客户端(G→E/E→E) | 换服务端(E→G/E→E) | 全栈(G→G/E→E) | 交互项 |
|---|---|---|---|---|---|---|---|---|---|
| 0B | 1 | 18667 | 10978 | 16014 | 10014 | 0.86x | 0.59x | 0.54x | 1.06x |
| 0B | 8 | 64384 | 34199 | 59190 | 32123 | 0.92x | 0.53x | 0.50x | 1.02x |
| 0B | 32 | 78808 | 39852 | 77477 | 38103 | 0.98x | 0.51x | 0.48x | 0.97x |
| 1KB | 1 | 18592 | 10873 | 15421 | 9864 | 0.83x | 0.58x | 0.53x | 1.09x |
| 1KB | 8 | 63001 | 33442 | 58084 | 32063 | 0.92x | 0.53x | 0.51x | 1.04x |
| 1KB | 32 | 76636 | 38809 | 72511 | 34859 | 0.95x | 0.51x | 0.45x | 0.95x |
| 16KB | 1 | 13489 | 9009 | 12942 | 8825 | 0.96x | 0.67x | 0.65x | 1.02x |
| 16KB | 8 | 39903 | 26502 | 35384 | 26267 | 0.89x | 0.66x | 0.66x | 1.12x |
| 16KB | 32 | 44963 | 30222 | 47037 | 29336 | 1.05x | 0.67x | 0.65x | 0.93x |
| 64KB | 1 | 7665 | 6035 | 8050 | 6400 | 1.05x | 0.79x | 0.83x | 1.01x |
| 64KB | 8 | 18766 | 14685 | 18110 | 15335 | 0.97x | 0.78x | 0.82x | 1.08x |
| 64KB | 32 | 20811 | 15114 | 22114 | 15452 | 1.06x | 0.73x | 0.74x | 0.96x |

> **换客户端** = 同一个 edap 服务端上，把客户端换成 grpc-java 的提升（`G→E / E→E`）—— **服务端本身不变**，所以这一列是客户端这一侧的贡献。
> **换服务端** = 同一个 edap 客户端上，把服务端换成 grpc-java 的提升（`E→G / E→E`）—— **客户端本身不变**，所以这一列是服务端这一侧的贡献。
> **全栈** = 两边都换（`G→G / E→E`）。**交互项** = 全栈 ÷ (换客户端 × 换服务端)：接近 1 表示两个维度可以相加、互不影响；明显偏离 1 表示两者有耦合（比如 grpc-java 的服务端在 grpc-java 的客户端上才发挥得出来）。

## 三、归因小结

| 负载 | 并发 | 客户端侧差距(G→E/E→E) | 服务端侧差距(E→G/E→E) | 全栈差距 | 谁是主要瓶颈 |
|---|---|---|---|---|---|
| 0B | 1 | 0.86x | 0.59x | 0.54x | **客户端** |
| 0B | 8 | 0.92x | 0.53x | 0.50x | **客户端** |
| 0B | 32 | 0.98x | 0.51x | 0.48x | **客户端** |
| 1KB | 1 | 0.83x | 0.58x | 0.53x | **客户端** |
| 1KB | 8 | 0.92x | 0.53x | 0.51x | **客户端** |
| 1KB | 32 | 0.95x | 0.51x | 0.45x | **客户端** |
| 16KB | 1 | 0.96x | 0.67x | 0.65x | **客户端** |
| 16KB | 8 | 0.89x | 0.66x | 0.66x | **客户端** |
| 16KB | 32 | 1.05x | 0.67x | 0.65x | **客户端** |
| 64KB | 1 | 1.05x | 0.79x | 0.83x | **客户端** |
| 64KB | 8 | 0.97x | 0.78x | 0.82x | **客户端** |
| 64KB | 32 | 1.06x | 0.73x | 0.74x | **客户端** |

> **客户端侧差距** = 在**同一个 edap 服务端**上换客户端（`G→E / E→E`）—— 服务端不变，所以这一列只反映客户端。
> **服务端侧差距** = 在**同一个 edap 客户端**上换服务端（`E→G / E→E`）—— 客户端不变，所以这一列只反映服务端。

## 四、原始轮次（每臂 4 轮）

| 负载 | 并发 | 臂 | 客户端 | 服务端 | 轮次 req/s | 轮次 p99(us) | 错误数 |
|---|---|---|---|---|---|---|---|
| 0B | 1 | E→E | edap | edap | 18600 | 98 | 0 |
| 0B | 1 | E→E | edap | edap | 18734 | 96 | 0 |
| 0B | 1 | E→E | edap | edap | 18751 | 95 | 0 |
| 0B | 1 | E→E | edap | edap | 18408 | 101 | 0 |
| 0B | 1 | E→G | edap | google | 10890 | 159 | 0 |
| 0B | 1 | E→G | edap | google | 11031 | 152 | 0 |
| 0B | 1 | E→G | edap | google | 10925 | 153 | 0 |
| 0B | 1 | E→G | edap | google | 11074 | 153 | 0 |
| 0B | 1 | G→E | google | edap | 16306 | 106 | 0 |
| 0B | 1 | G→E | google | edap | 15943 | 114 | 0 |
| 0B | 1 | G→E | google | edap | 15680 | 118 | 0 |
| 0B | 1 | G→E | google | edap | 16086 | 110 | 0 |
| 0B | 1 | G→G | google | google | 8084 | 346 | 0 |
| 0B | 1 | G→G | google | google | 10142 | 168 | 0 |
| 0B | 1 | G→G | google | google | 9913 | 174 | 0 |
| 0B | 1 | G→G | google | google | 10115 | 166 | 0 |
| 0B | 8 | E→E | edap | edap | 64705 | 201 | 0 |
| 0B | 8 | E→E | edap | edap | 64203 | 204 | 0 |
| 0B | 8 | E→E | edap | edap | 64066 | 205 | 0 |
| 0B | 8 | E→E | edap | edap | 64565 | 201 | 0 |
| 0B | 8 | E→G | edap | google | 34168 | 361 | 0 |
| 0B | 8 | E→G | edap | google | 33682 | 378 | 0 |
| 0B | 8 | E→G | edap | google | 34231 | 359 | 0 |
| 0B | 8 | E→G | edap | google | 34507 | 354 | 0 |
| 0B | 8 | G→E | google | edap | 59340 | 196 | 0 |
| 0B | 8 | G→E | google | edap | 59012 | 202 | 0 |
| 0B | 8 | G→E | google | edap | 59040 | 199 | 0 |
| 0B | 8 | G→E | google | edap | 60501 | 190 | 0 |
| 0B | 8 | G→G | google | google | 31250 | 421 | 0 |
| 0B | 8 | G→G | google | google | 31711 | 388 | 0 |
| 0B | 8 | G→G | google | google | 32535 | 376 | 0 |
| 0B | 8 | G→G | google | google | 32611 | 375 | 0 |
| 0B | 32 | E→E | edap | edap | 78990 | 514 | 0 |
| 0B | 32 | E→E | edap | edap | 78626 | 517 | 0 |
| 0B | 32 | E→E | edap | edap | 78565 | 516 | 0 |
| 0B | 32 | E→E | edap | edap | 79059 | 513 | 0 |
| 0B | 32 | E→G | edap | google | 39523 | 1195 | 0 |
| 0B | 32 | E→G | edap | google | 40181 | 1160 | 0 |
| 0B | 32 | E→G | edap | google | 39416 | 1195 | 0 |
| 0B | 32 | E→G | edap | google | 40556 | 1140 | 0 |
| 0B | 32 | G→E | google | edap | 77688 | 492 | 0 |
| 0B | 32 | G→E | google | edap | 77266 | 495 | 0 |
| 0B | 32 | G→E | google | edap | 77864 | 498 | 0 |
| 0B | 32 | G→E | google | edap | 75682 | 534 | 0 |
| 0B | 32 | G→G | google | google | 38194 | 1119 | 0 |
| 0B | 32 | G→G | google | google | 37013 | 1170 | 0 |
| 0B | 32 | G→G | google | google | 38013 | 1132 | 0 |
| 0B | 32 | G→G | google | google | 38196 | 1117 | 0 |
| 1KB | 1 | E→E | edap | edap | 18266 | 98 | 0 |
| 1KB | 1 | E→E | edap | edap | 18614 | 94 | 0 |
| 1KB | 1 | E→E | edap | edap | 18571 | 94 | 0 |
| 1KB | 1 | E→E | edap | edap | 18996 | 92 | 0 |
| 1KB | 1 | E→G | edap | google | 10901 | 154 | 0 |
| 1KB | 1 | E→G | edap | google | 10870 | 156 | 0 |
| 1KB | 1 | E→G | edap | google | 10498 | 157 | 0 |
| 1KB | 1 | E→G | edap | google | 10876 | 149 | 0 |
| 1KB | 1 | G→E | google | edap | 15391 | 113 | 0 |
| 1KB | 1 | G→E | google | edap | 15378 | 118 | 0 |
| 1KB | 1 | G→E | google | edap | 15451 | 114 | 0 |
| 1KB | 1 | G→E | google | edap | 15770 | 110 | 0 |
| 1KB | 1 | G→G | google | google | 9900 | 174 | 0 |
| 1KB | 1 | G→G | google | google | 10564 | 171 | 0 |
| 1KB | 1 | G→G | google | google | 9631 | 177 | 0 |
| 1KB | 1 | G→G | google | google | 9828 | 170 | 0 |
| 1KB | 8 | E→E | edap | edap | 63293 | 204 | 0 |
| 1KB | 8 | E→E | edap | edap | 63328 | 204 | 0 |
| 1KB | 8 | E→E | edap | edap | 62617 | 213 | 0 |
| 1KB | 8 | E→E | edap | edap | 62710 | 209 | 0 |
| 1KB | 8 | E→G | edap | google | 33821 | 358 | 0 |
| 1KB | 8 | E→G | edap | google | 33789 | 358 | 0 |
| 1KB | 8 | E→G | edap | google | 32892 | 380 | 0 |
| 1KB | 8 | E→G | edap | google | 33094 | 371 | 0 |
| 1KB | 8 | G→E | google | edap | 58649 | 196 | 0 |
| 1KB | 8 | G→E | google | edap | 58670 | 196 | 0 |
| 1KB | 8 | G→E | google | edap | 56652 | 217 | 0 |
| 1KB | 8 | G→E | google | edap | 57520 | 201 | 0 |
| 1KB | 8 | G→G | google | google | 32588 | 366 | 0 |
| 1KB | 8 | G→G | google | google | 32584 | 367 | 0 |
| 1KB | 8 | G→G | google | google | 31542 | 405 | 0 |
| 1KB | 8 | G→G | google | google | 31202 | 399 | 0 |
| 1KB | 32 | E→E | edap | edap | 76712 | 545 | 0 |
| 1KB | 32 | E→E | edap | edap | 76188 | 543 | 0 |
| 1KB | 32 | E→E | edap | edap | 76561 | 533 | 0 |
| 1KB | 32 | E→E | edap | edap | 77193 | 527 | 0 |
| 1KB | 32 | E→G | edap | google | 38249 | 1278 | 0 |
| 1KB | 32 | E→G | edap | google | 38572 | 1228 | 0 |
| 1KB | 32 | E→G | edap | google | 39185 | 1160 | 0 |
| 1KB | 32 | E→G | edap | google | 39045 | 1170 | 0 |
| 1KB | 32 | G→E | google | edap | 72891 | 574 | 0 |
| 1KB | 32 | G→E | google | edap | 71229 | 578 | 0 |
| 1KB | 32 | G→E | google | edap | 72132 | 562 | 0 |
| 1KB | 32 | G→E | google | edap | 73541 | 528 | 0 |
| 1KB | 32 | G→G | google | google | 34628 | 1317 | 0 |
| 1KB | 32 | G→G | google | google | 34695 | 1309 | 0 |
| 1KB | 32 | G→G | google | google | 35116 | 1322 | 0 |
| 1KB | 32 | G→G | google | google | 35024 | 1211 | 0 |
| 16KB | 1 | E→E | edap | edap | 13499 | 119 | 0 |
| 16KB | 1 | E→E | edap | edap | 13434 | 121 | 0 |
| 16KB | 1 | E→E | edap | edap | 13479 | 120 | 0 |
| 16KB | 1 | E→E | edap | edap | 13558 | 118 | 0 |
| 16KB | 1 | E→G | edap | google | 8889 | 180 | 0 |
| 16KB | 1 | E→G | edap | google | 9015 | 174 | 0 |
| 16KB | 1 | E→G | edap | google | 9003 | 175 | 0 |
| 16KB | 1 | E→G | edap | google | 9136 | 175 | 0 |
| 16KB | 1 | G→E | google | edap | 12839 | 131 | 0 |
| 16KB | 1 | G→E | google | edap | 12968 | 127 | 0 |
| 16KB | 1 | G→E | google | edap | 12916 | 127 | 0 |
| 16KB | 1 | G→E | google | edap | 12994 | 131 | 0 |
| 16KB | 1 | G→G | google | google | 8824 | 187 | 0 |
| 16KB | 1 | G→G | google | google | 8827 | 184 | 0 |
| 16KB | 1 | G→G | google | google | 8851 | 184 | 0 |
| 16KB | 1 | G→G | google | google | 8820 | 185 | 0 |
| 16KB | 8 | E→E | edap | edap | 39917 | 331 | 0 |
| 16KB | 8 | E→E | edap | edap | 39842 | 332 | 0 |
| 16KB | 8 | E→E | edap | edap | 39951 | 333 | 0 |
| 16KB | 8 | E→E | edap | edap | 39889 | 332 | 0 |
| 16KB | 8 | E→G | edap | google | 26510 | 442 | 0 |
| 16KB | 8 | E→G | edap | google | 26601 | 440 | 0 |
| 16KB | 8 | E→G | edap | google | 26383 | 449 | 0 |
| 16KB | 8 | E→G | edap | google | 26495 | 443 | 0 |
| 16KB | 8 | G→E | google | edap | 35352 | 318 | 0 |
| 16KB | 8 | G→E | google | edap | 35415 | 318 | 0 |
| 16KB | 8 | G→E | google | edap | 35546 | 316 | 0 |
| 16KB | 8 | G→E | google | edap | 35308 | 319 | 0 |
| 16KB | 8 | G→G | google | google | 26242 | 443 | 0 |
| 16KB | 8 | G→G | google | google | 26305 | 440 | 0 |
| 16KB | 8 | G→G | google | google | 26292 | 438 | 0 |
| 16KB | 8 | G→G | google | google | 26206 | 447 | 0 |
| 16KB | 32 | E→E | edap | edap | 45305 | 877 | 0 |
| 16KB | 32 | E→E | edap | edap | 44745 | 883 | 0 |
| 16KB | 32 | E→E | edap | edap | 44844 | 882 | 0 |
| 16KB | 32 | E→E | edap | edap | 45083 | 876 | 0 |
| 16KB | 32 | E→G | edap | google | 29982 | 1740 | 0 |
| 16KB | 32 | E→G | edap | google | 30043 | 1599 | 0 |
| 16KB | 32 | E→G | edap | google | 30400 | 1572 | 0 |
| 16KB | 32 | E→G | edap | google | 30486 | 1564 | 0 |
| 16KB | 32 | G→E | google | edap | 46674 | 956 | 0 |
| 16KB | 32 | G→E | google | edap | 47005 | 946 | 0 |
| 16KB | 32 | G→E | google | edap | 47068 | 949 | 0 |
| 16KB | 32 | G→E | google | edap | 47218 | 944 | 0 |
| 16KB | 32 | G→G | google | google | 29380 | 1607 | 0 |
| 16KB | 32 | G→G | google | google | 29271 | 1616 | 0 |
| 16KB | 32 | G→G | google | google | 29291 | 1623 | 0 |
| 16KB | 32 | G→G | google | google | 29485 | 1587 | 0 |
| 64KB | 1 | E→E | edap | edap | 7659 | 195 | 0 |
| 64KB | 1 | E→E | edap | edap | 7670 | 198 | 0 |
| 64KB | 1 | E→E | edap | edap | 7710 | 195 | 0 |
| 64KB | 1 | E→E | edap | edap | 7634 | 193 | 0 |
| 64KB | 1 | E→G | edap | google | 6027 | 246 | 0 |
| 64KB | 1 | E→G | edap | google | 6042 | 245 | 0 |
| 64KB | 1 | E→G | edap | google | 6056 | 247 | 0 |
| 64KB | 1 | E→G | edap | google | 5986 | 250 | 0 |
| 64KB | 1 | G→E | google | edap | 8072 | 198 | 0 |
| 64KB | 1 | G→E | google | edap | 8044 | 196 | 0 |
| 64KB | 1 | G→E | google | edap | 7990 | 205 | 0 |
| 64KB | 1 | G→E | google | edap | 8057 | 199 | 0 |
| 64KB | 1 | G→G | google | google | 6420 | 237 | 0 |
| 64KB | 1 | G→G | google | google | 6484 | 233 | 0 |
| 64KB | 1 | G→G | google | google | 6372 | 235 | 0 |
| 64KB | 1 | G→G | google | google | 6379 | 241 | 0 |
| 64KB | 8 | E→E | edap | edap | 18627 | 714 | 0 |
| 64KB | 8 | E→E | edap | edap | 18664 | 710 | 0 |
| 64KB | 8 | E→E | edap | edap | 18869 | 679 | 0 |
| 64KB | 8 | E→E | edap | edap | 18948 | 675 | 0 |
| 64KB | 8 | E→G | edap | google | 14670 | 831 | 0 |
| 64KB | 8 | E→G | edap | google | 14527 | 834 | 0 |
| 64KB | 8 | E→G | edap | google | 14701 | 824 | 0 |
| 64KB | 8 | E→G | edap | google | 14709 | 825 | 0 |
| 64KB | 8 | G→E | google | edap | 17900 | 729 | 0 |
| 64KB | 8 | G→E | google | edap | 17879 | 737 | 0 |
| 64KB | 8 | G→E | google | edap | 18806 | 697 | 0 |
| 64KB | 8 | G→E | google | edap | 18320 | 714 | 0 |
| 64KB | 8 | G→G | google | google | 15452 | 742 | 0 |
| 64KB | 8 | G→G | google | google | 15312 | 762 | 0 |
| 64KB | 8 | G→G | google | google | 15359 | 751 | 0 |
| 64KB | 8 | G→G | google | google | 15293 | 752 | 0 |
| 64KB | 32 | E→E | edap | edap | 20966 | 2579 | 0 |
| 64KB | 32 | E→E | edap | edap | 20222 | 2978 | 0 |
| 64KB | 32 | E→E | edap | edap | 20893 | 2782 | 0 |
| 64KB | 32 | E→E | edap | edap | 20730 | 2837 | 0 |
| 64KB | 32 | E→G | edap | google | 15042 | 3029 | 0 |
| 64KB | 32 | E→G | edap | google | 15187 | 2998 | 0 |
| 64KB | 32 | E→G | edap | google | 14861 | 3187 | 0 |
| 64KB | 32 | E→G | edap | google | 15247 | 2942 | 0 |
| 64KB | 32 | G→E | google | edap | 22080 | 2787 | 0 |
| 64KB | 32 | G→E | google | edap | 22139 | 2825 | 0 |
| 64KB | 32 | G→E | google | edap | 22130 | 2870 | 0 |
| 64KB | 32 | G→E | google | edap | 22098 | 2905 | 0 |
| 64KB | 32 | G→G | google | google | 15471 | 2801 | 0 |
| 64KB | 32 | G→G | google | google | 15359 | 2858 | 0 |
| 64KB | 32 | G→G | google | google | 15594 | 2798 | 0 |
| 64KB | 32 | G→G | google | google | 15433 | 2795 | 0 |

## 五、编解码器 micro 对比

| 负载 | 线上字节数 | 两边字节完全相同 | edap 编码核心 ns/op | google 编码核心 ns/op | 编码核心比 | edap 编码(产出 byte[]) ns/op | google 编码(产出 byte[]) ns/op | 编码比 | edap 解码 ns/op | google 解码 ns/op | 解码比 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 0B | 23 | 是 | 14 | 28 | 0.49x | 23 | 30 | 0.77x | 22 | 26 | 0.87x |
| 1KB | 1050 | 是 | 30 | 46 | 0.65x | 60 | 66 | 0.92x | 55 | 56 | 0.99x |
| 16KB | 16411 | 是 | 372 | 373 | 1.00x | 1059 | 723 | 1.47x | 737 | 751 | 0.98x |
| 64KB | 65563 | 是 | 1292 | 1379 | 0.94x | 2897 | 2006 | 1.44x | 1586 | 1596 | 0.99x |

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
