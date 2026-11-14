# edap 原生 gRPC vs google grpc-java —— 2×2 矩阵性能对比

生成时间: Sat Nov 14 11:16:25 CST 2026

运行环境: Mac OS X 27.2.1 / aarch64 / JDK 17.0.20.1 / 10 核

被测版本: grpc-java 1.85.1 / protobuf-java 3.25.9 / protoc 3.25.9 / edap 自研 protobuf（edap-protobuf，无 google 依赖）

测量口径: 预热 1000ms + 测量窗口 3000ms（时间制）；4 个臂 × 4 轮，**拉丁方调度**（每个臂在每个位置各跑一次）；N 个 worker 线程共享一条连接；延迟单位微秒

## 一、端到端 unary 性能（E→E vs G→G）

| 负载 | 并发 | edap req/s | google req/s | edap/google | edap p50(us) | google p50(us) | edap p99(us) | google p99(us) |
|---|---|---|---|---|---|---|---|---|
| 0B | 1 | 18416 | 10051 | 1.83x | 51 | 94 | 104 | 172 |
| 0B | 8 | 64095 | 32381 | 1.98x | 119 | 241 | 206 | 378 |
| 0B | 32 | 79801 | 38490 | 2.07x | 399 | 823 | 512 | 1111 |
| 1KB | 1 | 18824 | 10182 | 1.85x | 50 | 94 | 94 | 171 |
| 1KB | 8 | 63539 | 32693 | 1.94x | 122 | 241 | 207 | 363 |
| 1KB | 32 | 78130 | 36873 | 2.12x | 408 | 858 | 522 | 1158 |
| 16KB | 1 | 13518 | 8962 | 1.51x | 71 | 107 | 125 | 189 |
| 16KB | 8 | 40041 | 26243 | 1.53x | 192 | 300 | 334 | 441 |
| 16KB | 32 | 45471 | 29642 | 1.53x | 706 | 1074 | 867 | 1582 |
| 64KB | 1 | 7683 | 6392 | 1.20x | 125 | 151 | 202 | 236 |
| 64KB | 8 | 19062 | 15370 | 1.24x | 406 | 516 | 673 | 746 |
| 64KB | 32 | 21145 | 15541 | 1.36x | 1473 | 2038 | 2720 | 2808 |

## 二、2×2 矩阵（客户端 × 服务端）

| 负载 | 并发 | E→E | E→G | G→E | G→G | 换客户端(G→E/E→E) | 换服务端(E→G/E→E) | 全栈(G→G/E→E) | 交互项 |
|---|---|---|---|---|---|---|---|---|---|
| 0B | 1 | 18416 | 10839 | 15489 | 10051 | 0.84x | 0.59x | 0.55x | 1.10x |
| 0B | 8 | 64095 | 34223 | 59559 | 32381 | 0.93x | 0.53x | 0.51x | 1.02x |
| 0B | 32 | 79801 | 40598 | 77767 | 38490 | 0.97x | 0.51x | 0.48x | 0.97x |
| 1KB | 1 | 18824 | 11049 | 15735 | 10182 | 0.84x | 0.59x | 0.54x | 1.10x |
| 1KB | 8 | 63539 | 34091 | 59075 | 32693 | 0.93x | 0.54x | 0.51x | 1.03x |
| 1KB | 32 | 78130 | 39754 | 74007 | 36873 | 0.95x | 0.51x | 0.47x | 0.98x |
| 16KB | 1 | 13518 | 9109 | 12935 | 8962 | 0.96x | 0.67x | 0.66x | 1.03x |
| 16KB | 8 | 40041 | 26624 | 33907 | 26243 | 0.85x | 0.66x | 0.66x | 1.16x |
| 16KB | 32 | 45471 | 30376 | 47116 | 29642 | 1.04x | 0.67x | 0.65x | 0.94x |
| 64KB | 1 | 7683 | 6056 | 8022 | 6392 | 1.04x | 0.79x | 0.83x | 1.01x |
| 64KB | 8 | 19062 | 14737 | 18352 | 15370 | 0.96x | 0.77x | 0.81x | 1.08x |
| 64KB | 32 | 21145 | 15313 | 22304 | 15541 | 1.05x | 0.72x | 0.73x | 0.96x |

> **换客户端** = 同一个 edap 服务端上，把客户端换成 grpc-java 的提升（`G→E / E→E`）—— **服务端本身不变**，所以这一列是客户端这一侧的贡献。
> **换服务端** = 同一个 edap 客户端上，把服务端换成 grpc-java 的提升（`E→G / E→E`）—— **客户端本身不变**，所以这一列是服务端这一侧的贡献。
> **全栈** = 两边都换（`G→G / E→E`）。**交互项** = 全栈 ÷ (换客户端 × 换服务端)：接近 1 表示两个维度可以相加、互不影响；明显偏离 1 表示两者有耦合（比如 grpc-java 的服务端在 grpc-java 的客户端上才发挥得出来）。

## 三、归因小结

| 负载 | 并发 | 客户端侧差距(G→E/E→E) | 服务端侧差距(E→G/E→E) | 全栈差距 | 谁是主要瓶颈 |
|---|---|---|---|---|---|
| 0B | 1 | 0.84x | 0.59x | 0.55x | **客户端** |
| 0B | 8 | 0.93x | 0.53x | 0.51x | **客户端** |
| 0B | 32 | 0.97x | 0.51x | 0.48x | **客户端** |
| 1KB | 1 | 0.84x | 0.59x | 0.54x | **客户端** |
| 1KB | 8 | 0.93x | 0.54x | 0.51x | **客户端** |
| 1KB | 32 | 0.95x | 0.51x | 0.47x | **客户端** |
| 16KB | 1 | 0.96x | 0.67x | 0.66x | **客户端** |
| 16KB | 8 | 0.85x | 0.66x | 0.66x | **客户端** |
| 16KB | 32 | 1.04x | 0.67x | 0.65x | **客户端** |
| 64KB | 1 | 1.04x | 0.79x | 0.83x | **客户端** |
| 64KB | 8 | 0.96x | 0.77x | 0.81x | **客户端** |
| 64KB | 32 | 1.05x | 0.72x | 0.73x | **客户端** |

> **客户端侧差距** = 在**同一个 edap 服务端**上换客户端（`G→E / E→E`）—— 服务端不变，所以这一列只反映客户端。
> **服务端侧差距** = 在**同一个 edap 客户端**上换服务端（`E→G / E→E`）—— 客户端不变，所以这一列只反映服务端。

## 四、原始轮次（每臂 4 轮）

| 负载 | 并发 | 臂 | 客户端 | 服务端 | 轮次 req/s | 轮次 p99(us) | 错误数 |
|---|---|---|---|---|---|---|---|
| 0B | 1 | E→E | edap | edap | 17101 | 133 | 0 |
| 0B | 1 | E→E | edap | edap | 18896 | 98 | 0 |
| 0B | 1 | E→E | edap | edap | 18419 | 104 | 0 |
| 0B | 1 | E→E | edap | edap | 18414 | 103 | 0 |
| 0B | 1 | E→G | edap | google | 9506 | 199 | 0 |
| 0B | 1 | E→G | edap | google | 11044 | 157 | 0 |
| 0B | 1 | E→G | edap | google | 10669 | 167 | 0 |
| 0B | 1 | E→G | edap | google | 11008 | 156 | 0 |
| 0B | 1 | G→E | google | edap | 14227 | 141 | 0 |
| 0B | 1 | G→E | google | edap | 15778 | 115 | 0 |
| 0B | 1 | G→E | google | edap | 15776 | 116 | 0 |
| 0B | 1 | G→E | google | edap | 15201 | 129 | 0 |
| 0B | 1 | G→G | google | google | 10015 | 173 | 0 |
| 0B | 1 | G→G | google | google | 10138 | 170 | 0 |
| 0B | 1 | G→G | google | google | 10086 | 171 | 0 |
| 0B | 1 | G→G | google | google | 9838 | 181 | 0 |
| 0B | 8 | E→E | edap | edap | 62914 | 224 | 0 |
| 0B | 8 | E→E | edap | edap | 64299 | 204 | 0 |
| 0B | 8 | E→E | edap | edap | 64056 | 207 | 0 |
| 0B | 8 | E→E | edap | edap | 64134 | 205 | 0 |
| 0B | 8 | E→G | edap | google | 34174 | 361 | 0 |
| 0B | 8 | E→G | edap | google | 34371 | 359 | 0 |
| 0B | 8 | E→G | edap | google | 34273 | 356 | 0 |
| 0B | 8 | E→G | edap | google | 33978 | 365 | 0 |
| 0B | 8 | G→E | google | edap | 59070 | 207 | 0 |
| 0B | 8 | G→E | google | edap | 58512 | 205 | 0 |
| 0B | 8 | G→E | google | edap | 60048 | 198 | 0 |
| 0B | 8 | G→E | google | edap | 60572 | 193 | 0 |
| 0B | 8 | G→G | google | google | 32242 | 379 | 0 |
| 0B | 8 | G→G | google | google | 32388 | 378 | 0 |
| 0B | 8 | G→G | google | google | 32375 | 380 | 0 |
| 0B | 8 | G→G | google | google | 32551 | 374 | 0 |
| 0B | 32 | E→E | edap | edap | 80618 | 516 | 0 |
| 0B | 32 | E→E | edap | edap | 79617 | 511 | 0 |
| 0B | 32 | E→E | edap | edap | 79986 | 510 | 0 |
| 0B | 32 | E→E | edap | edap | 79093 | 514 | 0 |
| 0B | 32 | E→G | edap | google | 40636 | 1127 | 0 |
| 0B | 32 | E→G | edap | google | 40560 | 1142 | 0 |
| 0B | 32 | E→G | edap | google | 40548 | 1127 | 0 |
| 0B | 32 | E→G | edap | google | 40881 | 1107 | 0 |
| 0B | 32 | G→E | google | edap | 77265 | 517 | 0 |
| 0B | 32 | G→E | google | edap | 76966 | 510 | 0 |
| 0B | 32 | G→E | google | edap | 78337 | 501 | 0 |
| 0B | 32 | G→E | google | edap | 78269 | 504 | 0 |
| 0B | 32 | G→G | google | google | 38429 | 1116 | 0 |
| 0B | 32 | G→G | google | google | 37193 | 1176 | 0 |
| 0B | 32 | G→G | google | google | 38550 | 1103 | 0 |
| 0B | 32 | G→G | google | google | 38617 | 1107 | 0 |
| 1KB | 1 | E→E | edap | edap | 18838 | 94 | 0 |
| 1KB | 1 | E→E | edap | edap | 18788 | 95 | 0 |
| 1KB | 1 | E→E | edap | edap | 18810 | 94 | 0 |
| 1KB | 1 | E→E | edap | edap | 18905 | 94 | 0 |
| 1KB | 1 | E→G | edap | google | 11218 | 153 | 0 |
| 1KB | 1 | E→G | edap | google | 11314 | 147 | 0 |
| 1KB | 1 | E→G | edap | google | 10880 | 156 | 0 |
| 1KB | 1 | E→G | edap | google | 10876 | 154 | 0 |
| 1KB | 1 | G→E | google | edap | 15734 | 110 | 0 |
| 1KB | 1 | G→E | google | edap | 15723 | 110 | 0 |
| 1KB | 1 | G→E | google | edap | 15758 | 111 | 0 |
| 1KB | 1 | G→E | google | edap | 15735 | 108 | 0 |
| 1KB | 1 | G→G | google | google | 10261 | 168 | 0 |
| 1KB | 1 | G→G | google | google | 10369 | 168 | 0 |
| 1KB | 1 | G→G | google | google | 10103 | 180 | 0 |
| 1KB | 1 | G→G | google | google | 9972 | 174 | 0 |
| 1KB | 8 | E→E | edap | edap | 63592 | 205 | 0 |
| 1KB | 8 | E→E | edap | edap | 62875 | 211 | 0 |
| 1KB | 8 | E→E | edap | edap | 63485 | 208 | 0 |
| 1KB | 8 | E→E | edap | edap | 63683 | 206 | 0 |
| 1KB | 8 | E→G | edap | google | 33907 | 358 | 0 |
| 1KB | 8 | E→G | edap | google | 34286 | 351 | 0 |
| 1KB | 8 | E→G | edap | google | 33954 | 358 | 0 |
| 1KB | 8 | E→G | edap | google | 34228 | 352 | 0 |
| 1KB | 8 | G→E | google | edap | 59266 | 196 | 0 |
| 1KB | 8 | G→E | google | edap | 59329 | 196 | 0 |
| 1KB | 8 | G→E | google | edap | 58748 | 195 | 0 |
| 1KB | 8 | G→E | google | edap | 58883 | 196 | 0 |
| 1KB | 8 | G→G | google | google | 32730 | 363 | 0 |
| 1KB | 8 | G→G | google | google | 32757 | 362 | 0 |
| 1KB | 8 | G→G | google | google | 32656 | 363 | 0 |
| 1KB | 8 | G→G | google | google | 32619 | 367 | 0 |
| 1KB | 32 | E→E | edap | edap | 78955 | 521 | 0 |
| 1KB | 32 | E→E | edap | edap | 78435 | 522 | 0 |
| 1KB | 32 | E→E | edap | edap | 77803 | 525 | 0 |
| 1KB | 32 | E→E | edap | edap | 77824 | 523 | 0 |
| 1KB | 32 | E→G | edap | google | 39938 | 1155 | 0 |
| 1KB | 32 | E→G | edap | google | 40291 | 1106 | 0 |
| 1KB | 32 | E→G | edap | google | 39570 | 1135 | 0 |
| 1KB | 32 | E→G | edap | google | 39519 | 1163 | 0 |
| 1KB | 32 | G→E | google | edap | 73984 | 530 | 0 |
| 1KB | 32 | G→E | google | edap | 73968 | 537 | 0 |
| 1KB | 32 | G→E | google | edap | 74069 | 534 | 0 |
| 1KB | 32 | G→E | google | edap | 74030 | 531 | 0 |
| 1KB | 32 | G→G | google | google | 37007 | 1143 | 0 |
| 1KB | 32 | G→G | google | google | 36812 | 1165 | 0 |
| 1KB | 32 | G→G | google | google | 36935 | 1151 | 0 |
| 1KB | 32 | G→G | google | google | 36723 | 1211 | 0 |
| 16KB | 1 | E→E | edap | edap | 13625 | 125 | 0 |
| 16KB | 1 | E→E | edap | edap | 13510 | 125 | 0 |
| 16KB | 1 | E→E | edap | edap | 13525 | 125 | 0 |
| 16KB | 1 | E→E | edap | edap | 13512 | 123 | 0 |
| 16KB | 1 | E→G | edap | google | 9109 | 180 | 0 |
| 16KB | 1 | E→G | edap | google | 9056 | 179 | 0 |
| 16KB | 1 | E→G | edap | google | 9108 | 178 | 0 |
| 16KB | 1 | E→G | edap | google | 9171 | 178 | 0 |
| 16KB | 1 | G→E | google | edap | 12897 | 137 | 0 |
| 16KB | 1 | G→E | google | edap | 12973 | 128 | 0 |
| 16KB | 1 | G→E | google | edap | 12898 | 130 | 0 |
| 16KB | 1 | G→E | google | edap | 12980 | 131 | 0 |
| 16KB | 1 | G→G | google | google | 8938 | 190 | 0 |
| 16KB | 1 | G→G | google | google | 8971 | 188 | 0 |
| 16KB | 1 | G→G | google | google | 8972 | 190 | 0 |
| 16KB | 1 | G→G | google | google | 8952 | 189 | 0 |
| 16KB | 8 | E→E | edap | edap | 39954 | 334 | 0 |
| 16KB | 8 | E→E | edap | edap | 40057 | 333 | 0 |
| 16KB | 8 | E→E | edap | edap | 40025 | 335 | 0 |
| 16KB | 8 | E→E | edap | edap | 40104 | 333 | 0 |
| 16KB | 8 | E→G | edap | google | 26609 | 442 | 0 |
| 16KB | 8 | E→G | edap | google | 26640 | 441 | 0 |
| 16KB | 8 | E→G | edap | google | 26698 | 435 | 0 |
| 16KB | 8 | E→G | edap | google | 26605 | 440 | 0 |
| 16KB | 8 | G→E | google | edap | 33953 | 328 | 0 |
| 16KB | 8 | G→E | google | edap | 33860 | 329 | 0 |
| 16KB | 8 | G→E | google | edap | 33840 | 328 | 0 |
| 16KB | 8 | G→E | google | edap | 33985 | 326 | 0 |
| 16KB | 8 | G→G | google | google | 26089 | 444 | 0 |
| 16KB | 8 | G→G | google | google | 26238 | 443 | 0 |
| 16KB | 8 | G→G | google | google | 26268 | 439 | 0 |
| 16KB | 8 | G→G | google | google | 26249 | 437 | 0 |
| 16KB | 32 | E→E | edap | edap | 45677 | 861 | 0 |
| 16KB | 32 | E→E | edap | edap | 45514 | 867 | 0 |
| 16KB | 32 | E→E | edap | edap | 45254 | 867 | 0 |
| 16KB | 32 | E→E | edap | edap | 45429 | 868 | 0 |
| 16KB | 32 | E→G | edap | google | 30937 | 1547 | 0 |
| 16KB | 32 | E→G | edap | google | 30500 | 1575 | 0 |
| 16KB | 32 | E→G | edap | google | 29999 | 1570 | 0 |
| 16KB | 32 | E→G | edap | google | 30251 | 1565 | 0 |
| 16KB | 32 | G→E | google | edap | 46535 | 941 | 0 |
| 16KB | 32 | G→E | google | edap | 47039 | 943 | 0 |
| 16KB | 32 | G→E | google | edap | 47210 | 938 | 0 |
| 16KB | 32 | G→E | google | edap | 47193 | 947 | 0 |
| 16KB | 32 | G→G | google | google | 29573 | 1592 | 0 |
| 16KB | 32 | G→G | google | google | 29711 | 1572 | 0 |
| 16KB | 32 | G→G | google | google | 28565 | 1638 | 0 |
| 16KB | 32 | G→G | google | google | 29748 | 1570 | 0 |
| 64KB | 1 | E→E | edap | edap | 7688 | 201 | 0 |
| 64KB | 1 | E→E | edap | edap | 7678 | 205 | 0 |
| 64KB | 1 | E→E | edap | edap | 7705 | 202 | 0 |
| 64KB | 1 | E→E | edap | edap | 7672 | 202 | 0 |
| 64KB | 1 | E→G | edap | google | 6066 | 252 | 0 |
| 64KB | 1 | E→G | edap | google | 6045 | 257 | 0 |
| 64KB | 1 | E→G | edap | google | 6045 | 252 | 0 |
| 64KB | 1 | E→G | edap | google | 6080 | 248 | 0 |
| 64KB | 1 | G→E | google | edap | 8006 | 201 | 0 |
| 64KB | 1 | G→E | google | edap | 8038 | 198 | 0 |
| 64KB | 1 | G→E | google | edap | 7980 | 202 | 0 |
| 64KB | 1 | G→E | google | edap | 8069 | 196 | 0 |
| 64KB | 1 | G→G | google | google | 6393 | 234 | 0 |
| 64KB | 1 | G→G | google | google | 6424 | 235 | 0 |
| 64KB | 1 | G→G | google | google | 6382 | 238 | 0 |
| 64KB | 1 | G→G | google | google | 6392 | 238 | 0 |
| 64KB | 8 | E→E | edap | edap | 18980 | 683 | 0 |
| 64KB | 8 | E→E | edap | edap | 19041 | 675 | 0 |
| 64KB | 8 | E→E | edap | edap | 19083 | 670 | 0 |
| 64KB | 8 | E→E | edap | edap | 19122 | 657 | 0 |
| 64KB | 8 | E→G | edap | google | 14702 | 820 | 0 |
| 64KB | 8 | E→G | edap | google | 14743 | 816 | 0 |
| 64KB | 8 | E→G | edap | google | 14731 | 816 | 0 |
| 64KB | 8 | E→G | edap | google | 14775 | 813 | 0 |
| 64KB | 8 | G→E | google | edap | 18298 | 707 | 0 |
| 64KB | 8 | G→E | google | edap | 18406 | 705 | 0 |
| 64KB | 8 | G→E | google | edap | 18517 | 702 | 0 |
| 64KB | 8 | G→E | google | edap | 18189 | 722 | 0 |
| 64KB | 8 | G→G | google | google | 15316 | 751 | 0 |
| 64KB | 8 | G→G | google | google | 15371 | 747 | 0 |
| 64KB | 8 | G→G | google | google | 15415 | 742 | 0 |
| 64KB | 8 | G→G | google | google | 15370 | 746 | 0 |
| 64KB | 32 | E→E | edap | edap | 21213 | 2611 | 0 |
| 64KB | 32 | E→E | edap | edap | 20978 | 2772 | 0 |
| 64KB | 32 | E→E | edap | edap | 21160 | 2716 | 0 |
| 64KB | 32 | E→E | edap | edap | 21130 | 2725 | 0 |
| 64KB | 32 | E→G | edap | google | 14833 | 3720 | 0 |
| 64KB | 32 | E→G | edap | google | 15305 | 2967 | 0 |
| 64KB | 32 | E→G | edap | google | 15414 | 2968 | 0 |
| 64KB | 32 | E→G | edap | google | 15322 | 2955 | 0 |
| 64KB | 32 | G→E | google | edap | 22460 | 2862 | 0 |
| 64KB | 32 | G→E | google | edap | 22282 | 2917 | 0 |
| 64KB | 32 | G→E | google | edap | 22326 | 2992 | 0 |
| 64KB | 32 | G→E | google | edap | 22142 | 2974 | 0 |
| 64KB | 32 | G→G | google | google | 15510 | 2830 | 0 |
| 64KB | 32 | G→G | google | google | 15519 | 2914 | 0 |
| 64KB | 32 | G→G | google | google | 15604 | 2786 | 0 |
| 64KB | 32 | G→G | google | google | 15562 | 2776 | 0 |

## 五、编解码器 micro 对比

| 负载 | 线上字节数 | 两边字节完全相同 | edap 编码核心 ns/op | google 编码核心 ns/op | 编码核心比 | edap 编码(产出 byte[]) ns/op | google 编码(产出 byte[]) ns/op | 编码比 | edap 解码 ns/op | google 解码 ns/op | 解码比 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 0B | 23 | 是 | 11 | 28 | 0.40x | 21 | 30 | 0.69x | 23 | 26 | 0.89x |
| 1KB | 1050 | 是 | 28 | 45 | 0.61x | 58 | 65 | 0.90x | 53 | 57 | 0.93x |
| 16KB | 16411 | 是 | 335 | 358 | 0.93x | 1055 | 721 | 1.46x | 734 | 740 | 0.99x |
| 64KB | 65563 | 是 | 1302 | 1330 | 0.98x | 2857 | 2000 | 1.43x | 1583 | 1586 | 1.00x |

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
