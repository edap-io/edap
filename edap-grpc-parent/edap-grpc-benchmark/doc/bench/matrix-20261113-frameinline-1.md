# edap 原生 gRPC vs google grpc-java —— 2×2 矩阵性能对比

生成时间: Fri Nov 13 01:05:10 CST 2026

运行环境: Mac OS X 27.2.1 / aarch64 / JDK 17.0.20.1 / 10 核

被测版本: grpc-java 1.85.1 / protobuf-java 3.25.9 / protoc 3.25.9 / edap 自研 protobuf（edap-protobuf，无 google 依赖）

测量口径: 预热 1000ms + 测量窗口 3000ms（时间制）；4 个臂 × 4 轮，**拉丁方调度**（每个臂在每个位置各跑一次）；N 个 worker 线程共享一条连接；延迟单位微秒

## 一、端到端 unary 性能（E→E vs G→G）

| 负载 | 并发 | edap req/s | google req/s | edap/google | edap p50(us) | google p50(us) | edap p99(us) | google p99(us) |
|---|---|---|---|---|---|---|---|---|
| 0B | 1 | 18573 | 10205 | 1.82x | 51 | 93 | 94 | 161 |
| 0B | 8 | 63535 | 32338 | 1.96x | 121 | 241 | 204 | 377 |
| 0B | 32 | 76205 | 38208 | 1.99x | 418 | 830 | 536 | 1125 |
| 1KB | 1 | 18593 | 9996 | 1.86x | 51 | 95 | 93 | 173 |
| 1KB | 8 | 62278 | 32284 | 1.93x | 124 | 243 | 207 | 370 |
| 1KB | 32 | 73153 | 35580 | 2.06x | 435 | 885 | 563 | 1241 |
| 16KB | 1 | 13396 | 8824 | 1.52x | 72 | 108 | 123 | 193 |
| 16KB | 8 | 39129 | 25554 | 1.53x | 197 | 306 | 343 | 466 |
| 16KB | 32 | 44007 | 28562 | 1.54x | 728 | 1111 | 896 | 1704 |
| 64KB | 1 | 7632 | 6338 | 1.20x | 126 | 151 | 201 | 238 |
| 64KB | 8 | 18781 | 15207 | 1.24x | 413 | 519 | 685 | 777 |
| 64KB | 32 | 20439 | 15357 | 1.33x | 1513 | 2066 | 2773 | 2818 |

## 二、2×2 矩阵（客户端 × 服务端）

| 负载 | 并发 | E→E | E→G | G→E | G→G | 换客户端(G→E/E→E) | 换服务端(E→G/E→E) | 全栈(G→G/E→E) | 交互项 |
|---|---|---|---|---|---|---|---|---|---|
| 0B | 1 | 18573 | 10574 | 15752 | 10205 | 0.85x | 0.57x | 0.55x | 1.14x |
| 0B | 8 | 63535 | 34196 | 59664 | 32338 | 0.94x | 0.54x | 0.51x | 1.01x |
| 0B | 32 | 76205 | 40118 | 78491 | 38208 | 1.03x | 0.53x | 0.50x | 0.92x |
| 1KB | 1 | 18593 | 10826 | 15531 | 9996 | 0.84x | 0.58x | 0.54x | 1.11x |
| 1KB | 8 | 62278 | 33511 | 58693 | 32284 | 0.94x | 0.54x | 0.52x | 1.02x |
| 1KB | 32 | 73153 | 38423 | 72470 | 35580 | 0.99x | 0.53x | 0.49x | 0.93x |
| 16KB | 1 | 13396 | 8855 | 12949 | 8824 | 0.97x | 0.66x | 0.66x | 1.03x |
| 16KB | 8 | 39129 | 25957 | 34530 | 25554 | 0.88x | 0.66x | 0.65x | 1.12x |
| 16KB | 32 | 44007 | 29514 | 45823 | 28562 | 1.04x | 0.67x | 0.65x | 0.93x |
| 64KB | 1 | 7632 | 6015 | 7952 | 6338 | 1.04x | 0.79x | 0.83x | 1.01x |
| 64KB | 8 | 18781 | 14625 | 17980 | 15207 | 0.96x | 0.78x | 0.81x | 1.09x |
| 64KB | 32 | 20439 | 15140 | 22167 | 15357 | 1.08x | 0.74x | 0.75x | 0.94x |

> **换客户端** = 同一个 edap 服务端上，把客户端换成 grpc-java 的提升（`G→E / E→E`）—— **服务端本身不变**，所以这一列是客户端这一侧的贡献。
> **换服务端** = 同一个 edap 客户端上，把服务端换成 grpc-java 的提升（`E→G / E→E`）—— **客户端本身不变**，所以这一列是服务端这一侧的贡献。
> **全栈** = 两边都换（`G→G / E→E`）。**交互项** = 全栈 ÷ (换客户端 × 换服务端)：接近 1 表示两个维度可以相加、互不影响；明显偏离 1 表示两者有耦合（比如 grpc-java 的服务端在 grpc-java 的客户端上才发挥得出来）。

## 三、归因小结

| 负载 | 并发 | 客户端侧差距(G→E/E→E) | 服务端侧差距(E→G/E→E) | 全栈差距 | 谁是主要瓶颈 |
|---|---|---|---|---|---|
| 0B | 1 | 0.85x | 0.57x | 0.55x | **客户端** |
| 0B | 8 | 0.94x | 0.54x | 0.51x | **客户端** |
| 0B | 32 | 1.03x | 0.53x | 0.50x | **客户端** |
| 1KB | 1 | 0.84x | 0.58x | 0.54x | **客户端** |
| 1KB | 8 | 0.94x | 0.54x | 0.52x | **客户端** |
| 1KB | 32 | 0.99x | 0.53x | 0.49x | **客户端** |
| 16KB | 1 | 0.97x | 0.66x | 0.66x | **客户端** |
| 16KB | 8 | 0.88x | 0.66x | 0.65x | **客户端** |
| 16KB | 32 | 1.04x | 0.67x | 0.65x | **客户端** |
| 64KB | 1 | 1.04x | 0.79x | 0.83x | **客户端** |
| 64KB | 8 | 0.96x | 0.78x | 0.81x | **客户端** |
| 64KB | 32 | 1.08x | 0.74x | 0.75x | **客户端** |

> **客户端侧差距** = 在**同一个 edap 服务端**上换客户端（`G→E / E→E`）—— 服务端不变，所以这一列只反映客户端。
> **服务端侧差距** = 在**同一个 edap 客户端**上换服务端（`E→G / E→E`）—— 客户端不变，所以这一列只反映服务端。

## 四、原始轮次（每臂 4 轮）

| 负载 | 并发 | 臂 | 客户端 | 服务端 | 轮次 req/s | 轮次 p99(us) | 错误数 |
|---|---|---|---|---|---|---|---|
| 0B | 1 | E→E | edap | edap | 18228 | 97 | 0 |
| 0B | 1 | E→E | edap | edap | 18580 | 93 | 0 |
| 0B | 1 | E→E | edap | edap | 19137 | 89 | 0 |
| 0B | 1 | E→E | edap | edap | 18566 | 96 | 0 |
| 0B | 1 | E→G | edap | google | 10094 | 173 | 0 |
| 0B | 1 | E→G | edap | google | 10326 | 179 | 0 |
| 0B | 1 | E→G | edap | google | 11005 | 149 | 0 |
| 0B | 1 | E→G | edap | google | 10821 | 155 | 0 |
| 0B | 1 | G→E | google | edap | 15133 | 128 | 0 |
| 0B | 1 | G→E | google | edap | 16045 | 107 | 0 |
| 0B | 1 | G→E | google | edap | 16002 | 106 | 0 |
| 0B | 1 | G→E | google | edap | 15502 | 118 | 0 |
| 0B | 1 | G→G | google | google | 10273 | 158 | 0 |
| 0B | 1 | G→G | google | google | 10244 | 160 | 0 |
| 0B | 1 | G→G | google | google | 10167 | 162 | 0 |
| 0B | 1 | G→G | google | google | 9989 | 169 | 0 |
| 0B | 8 | E→E | edap | edap | 62971 | 213 | 0 |
| 0B | 8 | E→E | edap | edap | 63504 | 205 | 0 |
| 0B | 8 | E→E | edap | edap | 63567 | 203 | 0 |
| 0B | 8 | E→E | edap | edap | 64773 | 198 | 0 |
| 0B | 8 | E→G | edap | google | 33729 | 368 | 0 |
| 0B | 8 | E→G | edap | google | 34198 | 360 | 0 |
| 0B | 8 | E→G | edap | google | 34195 | 360 | 0 |
| 0B | 8 | E→G | edap | google | 34919 | 349 | 0 |
| 0B | 8 | G→E | google | edap | 58814 | 197 | 0 |
| 0B | 8 | G→E | google | edap | 59095 | 197 | 0 |
| 0B | 8 | G→E | google | edap | 60232 | 195 | 0 |
| 0B | 8 | G→E | google | edap | 60497 | 189 | 0 |
| 0B | 8 | G→G | google | google | 32274 | 376 | 0 |
| 0B | 8 | G→G | google | google | 32155 | 393 | 0 |
| 0B | 8 | G→G | google | google | 32402 | 378 | 0 |
| 0B | 8 | G→G | google | google | 32602 | 373 | 0 |
| 0B | 32 | E→E | edap | edap | 76370 | 533 | 0 |
| 0B | 32 | E→E | edap | edap | 76039 | 542 | 0 |
| 0B | 32 | E→E | edap | edap | 76485 | 535 | 0 |
| 0B | 32 | E→E | edap | edap | 76023 | 536 | 0 |
| 0B | 32 | E→G | edap | google | 40353 | 1137 | 0 |
| 0B | 32 | E→G | edap | google | 39883 | 1185 | 0 |
| 0B | 32 | E→G | edap | google | 40798 | 1128 | 0 |
| 0B | 32 | E→G | edap | google | 39287 | 1260 | 0 |
| 0B | 32 | G→E | google | edap | 79407 | 483 | 0 |
| 0B | 32 | G→E | google | edap | 78541 | 500 | 0 |
| 0B | 32 | G→E | google | edap | 78441 | 520 | 0 |
| 0B | 32 | G→E | google | edap | 77477 | 527 | 0 |
| 0B | 32 | G→G | google | google | 37666 | 1163 | 0 |
| 0B | 32 | G→G | google | google | 38507 | 1090 | 0 |
| 0B | 32 | G→G | google | google | 38440 | 1116 | 0 |
| 0B | 32 | G→G | google | google | 37975 | 1133 | 0 |
| 1KB | 1 | E→E | edap | edap | 18524 | 93 | 0 |
| 1KB | 1 | E→E | edap | edap | 18598 | 92 | 0 |
| 1KB | 1 | E→E | edap | edap | 18587 | 93 | 0 |
| 1KB | 1 | E→E | edap | edap | 18686 | 93 | 0 |
| 1KB | 1 | E→G | edap | google | 10938 | 154 | 0 |
| 1KB | 1 | E→G | edap | google | 10827 | 154 | 0 |
| 1KB | 1 | E→G | edap | google | 10825 | 156 | 0 |
| 1KB | 1 | E→G | edap | google | 10534 | 163 | 0 |
| 1KB | 1 | G→E | google | edap | 15527 | 112 | 0 |
| 1KB | 1 | G→E | google | edap | 15607 | 115 | 0 |
| 1KB | 1 | G→E | google | edap | 15504 | 112 | 0 |
| 1KB | 1 | G→E | google | edap | 15534 | 112 | 0 |
| 1KB | 1 | G→G | google | google | 10090 | 167 | 0 |
| 1KB | 1 | G→G | google | google | 9655 | 195 | 0 |
| 1KB | 1 | G→G | google | google | 10060 | 171 | 0 |
| 1KB | 1 | G→G | google | google | 9932 | 174 | 0 |
| 1KB | 8 | E→E | edap | edap | 62275 | 207 | 0 |
| 1KB | 8 | E→E | edap | edap | 62372 | 207 | 0 |
| 1KB | 8 | E→E | edap | edap | 62280 | 205 | 0 |
| 1KB | 8 | E→E | edap | edap | 60764 | 225 | 0 |
| 1KB | 8 | E→G | edap | google | 33349 | 366 | 0 |
| 1KB | 8 | E→G | edap | google | 33849 | 357 | 0 |
| 1KB | 8 | E→G | edap | google | 33672 | 359 | 0 |
| 1KB | 8 | E→G | edap | google | 31914 | 416 | 0 |
| 1KB | 8 | G→E | google | edap | 58651 | 194 | 0 |
| 1KB | 8 | G→E | google | edap | 58735 | 195 | 0 |
| 1KB | 8 | G→E | google | edap | 59111 | 194 | 0 |
| 1KB | 8 | G→E | google | edap | 57922 | 200 | 0 |
| 1KB | 8 | G→G | google | google | 32193 | 369 | 0 |
| 1KB | 8 | G→G | google | google | 32333 | 365 | 0 |
| 1KB | 8 | G→G | google | google | 32282 | 371 | 0 |
| 1KB | 8 | G→G | google | google | 32287 | 370 | 0 |
| 1KB | 32 | E→E | edap | edap | 73509 | 572 | 0 |
| 1KB | 32 | E→E | edap | edap | 73190 | 561 | 0 |
| 1KB | 32 | E→E | edap | edap | 72836 | 565 | 0 |
| 1KB | 32 | E→E | edap | edap | 73117 | 560 | 0 |
| 1KB | 32 | E→G | edap | google | 38183 | 1261 | 0 |
| 1KB | 32 | E→G | edap | google | 38140 | 1240 | 0 |
| 1KB | 32 | E→G | edap | google | 38706 | 1208 | 0 |
| 1KB | 32 | E→G | edap | google | 38664 | 1225 | 0 |
| 1KB | 32 | G→E | google | edap | 72265 | 559 | 0 |
| 1KB | 32 | G→E | google | edap | 72259 | 564 | 0 |
| 1KB | 32 | G→E | google | edap | 72715 | 549 | 0 |
| 1KB | 32 | G→E | google | edap | 72675 | 547 | 0 |
| 1KB | 32 | G→G | google | google | 35470 | 1267 | 0 |
| 1KB | 32 | G→G | google | google | 35703 | 1222 | 0 |
| 1KB | 32 | G→G | google | google | 35659 | 1226 | 0 |
| 1KB | 32 | G→G | google | google | 35500 | 1256 | 0 |
| 16KB | 1 | E→E | edap | edap | 13468 | 120 | 0 |
| 16KB | 1 | E→E | edap | edap | 13576 | 116 | 0 |
| 16KB | 1 | E→E | edap | edap | 13325 | 127 | 0 |
| 16KB | 1 | E→E | edap | edap | 13280 | 128 | 0 |
| 16KB | 1 | E→G | edap | google | 8973 | 172 | 0 |
| 16KB | 1 | E→G | edap | google | 9027 | 171 | 0 |
| 16KB | 1 | E→G | edap | google | 8423 | 222 | 0 |
| 16KB | 1 | E→G | edap | google | 8737 | 182 | 0 |
| 16KB | 1 | G→E | google | edap | 13027 | 120 | 0 |
| 16KB | 1 | G→E | google | edap | 12988 | 121 | 0 |
| 16KB | 1 | G→E | google | edap | 12911 | 124 | 0 |
| 16KB | 1 | G→E | google | edap | 12819 | 132 | 0 |
| 16KB | 1 | G→G | google | google | 8806 | 192 | 0 |
| 16KB | 1 | G→G | google | google | 8841 | 195 | 0 |
| 16KB | 1 | G→G | google | google | 8921 | 192 | 0 |
| 16KB | 1 | G→G | google | google | 7996 | 252 | 0 |
| 16KB | 8 | E→E | edap | edap | 38709 | 353 | 0 |
| 16KB | 8 | E→E | edap | edap | 39083 | 340 | 0 |
| 16KB | 8 | E→E | edap | edap | 39175 | 337 | 0 |
| 16KB | 8 | E→E | edap | edap | 39237 | 346 | 0 |
| 16KB | 8 | E→G | edap | google | 24991 | 500 | 0 |
| 16KB | 8 | E→G | edap | google | 25906 | 465 | 0 |
| 16KB | 8 | E→G | edap | google | 26008 | 455 | 0 |
| 16KB | 8 | E→G | edap | google | 26132 | 452 | 0 |
| 16KB | 8 | G→E | google | edap | 34330 | 336 | 0 |
| 16KB | 8 | G→E | google | edap | 34531 | 339 | 0 |
| 16KB | 8 | G→E | google | edap | 34530 | 336 | 0 |
| 16KB | 8 | G→E | google | edap | 34552 | 332 | 0 |
| 16KB | 8 | G→G | google | google | 25531 | 465 | 0 |
| 16KB | 8 | G→G | google | google | 25576 | 467 | 0 |
| 16KB | 8 | G→G | google | google | 25522 | 468 | 0 |
| 16KB | 8 | G→G | google | google | 25974 | 447 | 0 |
| 16KB | 32 | E→E | edap | edap | 43995 | 918 | 0 |
| 16KB | 32 | E→E | edap | edap | 44183 | 894 | 0 |
| 16KB | 32 | E→E | edap | edap | 44018 | 893 | 0 |
| 16KB | 32 | E→E | edap | edap | 43884 | 899 | 0 |
| 16KB | 32 | E→G | edap | google | 29217 | 1687 | 0 |
| 16KB | 32 | E→G | edap | google | 29291 | 1672 | 0 |
| 16KB | 32 | E→G | edap | google | 29737 | 1591 | 0 |
| 16KB | 32 | E→G | edap | google | 29828 | 1597 | 0 |
| 16KB | 32 | G→E | google | edap | 45696 | 996 | 0 |
| 16KB | 32 | G→E | google | edap | 45950 | 972 | 0 |
| 16KB | 32 | G→E | google | edap | 46337 | 973 | 0 |
| 16KB | 32 | G→E | google | edap | 43954 | 1193 | 0 |
| 16KB | 32 | G→G | google | google | 28047 | 1838 | 0 |
| 16KB | 32 | G→G | google | google | 28603 | 1657 | 0 |
| 16KB | 32 | G→G | google | google | 28521 | 1752 | 0 |
| 16KB | 32 | G→G | google | google | 28976 | 1637 | 0 |
| 64KB | 1 | E→E | edap | edap | 7476 | 208 | 0 |
| 64KB | 1 | E→E | edap | edap | 7623 | 202 | 0 |
| 64KB | 1 | E→E | edap | edap | 7641 | 201 | 0 |
| 64KB | 1 | E→E | edap | edap | 7663 | 195 | 0 |
| 64KB | 1 | E→G | edap | google | 5826 | 260 | 0 |
| 64KB | 1 | E→G | edap | google | 5969 | 253 | 0 |
| 64KB | 1 | E→G | edap | google | 6062 | 249 | 0 |
| 64KB | 1 | E→G | edap | google | 6112 | 247 | 0 |
| 64KB | 1 | G→E | google | edap | 7774 | 213 | 0 |
| 64KB | 1 | G→E | google | edap | 7943 | 207 | 0 |
| 64KB | 1 | G→E | google | edap | 7961 | 202 | 0 |
| 64KB | 1 | G→E | google | edap | 8139 | 190 | 0 |
| 64KB | 1 | G→G | google | google | 6348 | 236 | 0 |
| 64KB | 1 | G→G | google | google | 6329 | 241 | 0 |
| 64KB | 1 | G→G | google | google | 6327 | 242 | 0 |
| 64KB | 1 | G→G | google | google | 6410 | 234 | 0 |
| 64KB | 8 | E→E | edap | edap | 18877 | 663 | 0 |
| 64KB | 8 | E→E | edap | edap | 18963 | 652 | 0 |
| 64KB | 8 | E→E | edap | edap | 18686 | 706 | 0 |
| 64KB | 8 | E→E | edap | edap | 18524 | 723 | 0 |
| 64KB | 8 | E→G | edap | google | 14687 | 823 | 0 |
| 64KB | 8 | E→G | edap | google | 14794 | 814 | 0 |
| 64KB | 8 | E→G | edap | google | 14563 | 845 | 0 |
| 64KB | 8 | E→G | edap | google | 14329 | 950 | 0 |
| 64KB | 8 | G→E | google | edap | 18370 | 696 | 0 |
| 64KB | 8 | G→E | google | edap | 18403 | 708 | 0 |
| 64KB | 8 | G→E | google | edap | 17332 | 785 | 0 |
| 64KB | 8 | G→E | google | edap | 17591 | 755 | 0 |
| 64KB | 8 | G→G | google | google | 15338 | 758 | 0 |
| 64KB | 8 | G→G | google | google | 15348 | 750 | 0 |
| 64KB | 8 | G→G | google | google | 14823 | 807 | 0 |
| 64KB | 8 | G→G | google | google | 15075 | 796 | 0 |
| 64KB | 32 | E→E | edap | edap | 20376 | 2773 | 0 |
| 64KB | 32 | E→E | edap | edap | 20622 | 2773 | 0 |
| 64KB | 32 | E→E | edap | edap | 20474 | 2791 | 0 |
| 64KB | 32 | E→E | edap | edap | 20405 | 2771 | 0 |
| 64KB | 32 | E→G | edap | google | 14940 | 3084 | 0 |
| 64KB | 32 | E→G | edap | google | 15187 | 2957 | 0 |
| 64KB | 32 | E→G | edap | google | 15099 | 3054 | 0 |
| 64KB | 32 | E→G | edap | google | 15181 | 3016 | 0 |
| 64KB | 32 | G→E | google | edap | 22190 | 2862 | 0 |
| 64KB | 32 | G→E | google | edap | 22144 | 2875 | 0 |
| 64KB | 32 | G→E | google | edap | 22140 | 2930 | 0 |
| 64KB | 32 | G→E | google | edap | 22210 | 2894 | 0 |
| 64KB | 32 | G→G | google | google | 15365 | 3090 | 0 |
| 64KB | 32 | G→G | google | google | 15349 | 2802 | 0 |
| 64KB | 32 | G→G | google | google | 15303 | 2825 | 0 |
| 64KB | 32 | G→G | google | google | 15409 | 2810 | 0 |

## 五、编解码器 micro 对比

| 负载 | 线上字节数 | 两边字节完全相同 | edap 编码核心 ns/op | google 编码核心 ns/op | 编码核心比 | edap 编码(产出 byte[]) ns/op | google 编码(产出 byte[]) ns/op | 编码比 | edap 解码 ns/op | google 解码 ns/op | 解码比 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 0B | 23 | 是 | 14 | 28 | 0.52x | 24 | 30 | 0.79x | 23 | 26 | 0.89x |
| 1KB | 1050 | 是 | 31 | 46 | 0.68x | 61 | 66 | 0.92x | 52 | 56 | 0.94x |
| 16KB | 16411 | 是 | 363 | 403 | 0.90x | 1028 | 719 | 1.43x | 776 | 768 | 1.01x |
| 64KB | 65563 | 是 | 1357 | 1346 | 1.01x | 3045 | 2018 | 1.51x | 1617 | 1604 | 1.01x |

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
