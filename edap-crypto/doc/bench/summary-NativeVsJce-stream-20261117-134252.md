# edap-crypto 基准汇总（20261117-134252）

轮数：5；变异系数 CV = 标准差 / 均值 × 100%。

| benchmark | 参数 | 轮数 | 中位数 (ns/op) | 最小 | 最大 | 均值 | CV% |
|---|---|---|---|---|---|---|---|
| `jceDigest_16t` | payloadSize=1024 | 5 | 10,580 | 10,387 | 10,656 | 10,546 | 0.97 |
| `jceDigest_16t` | payloadSize=64 | 5 | 1,459 | 1,415 | 1,488 | 1,454 | 2.05 |
| `jceDigest_16t` | payloadSize=65536 | 5 | 635,262 | 621,293 | 711,880 | 647,585 | 5.70 |
| `jceDigest_16t` | payloadSize=8192 | 5 | 79,597 | 78,112 | 84,835 | 80,530 | 3.56 |
| `jceDigest_1t` | payloadSize=1024 | 5 | 4,946 | 4,920 | 4,970 | 4,946 | 0.36 |
| `jceDigest_1t` | payloadSize=64 | 5 | 613.34 | 612.99 | 616.07 | 614.21 | 0.23 |
| `jceDigest_1t` | payloadSize=65536 | 5 | 295,817 | 294,558 | 296,108 | 295,623 | 0.22 |
| `jceDigest_1t` | payloadSize=8192 | 5 | 37,234 | 37,102 | 37,274 | 37,212 | 0.19 |
| `jceDigest_4t` | payloadSize=1024 | 5 | 5,166 | 5,134 | 5,190 | 5,164 | 0.43 |
| `jceDigest_4t` | payloadSize=64 | 5 | 707.93 | 697.46 | 721.16 | 708.35 | 1.54 |
| `jceDigest_4t` | payloadSize=65536 | 5 | 300,886 | 300,457 | 301,191 | 300,876 | 0.09 |
| `jceDigest_4t` | payloadSize=8192 | 5 | 37,862 | 37,849 | 38,167 | 37,926 | 0.36 |
| `jceMac_16t` | payloadSize=1024 | 5 | 12,619 | 12,562 | 12,956 | 12,705 | 1.28 |
| `jceMac_16t` | payloadSize=64 | 5 | 3,525 | 3,386 | 3,627 | 3,506 | 2.53 |
| `jceMac_16t` | payloadSize=65536 | 5 | 628,016 | 621,754 | 637,184 | 629,159 | 1.13 |
| `jceMac_16t` | payloadSize=8192 | 5 | 81,237 | 80,236 | 91,473 | 83,046 | 5.71 |
| `jceMac_1t` | payloadSize=1024 | 5 | 5,857 | 5,845 | 5,867 | 5,857 | 0.14 |
| `jceMac_1t` | payloadSize=64 | 5 | 1,540 | 1,533 | 1,541 | 1,539 | 0.21 |
| `jceMac_1t` | payloadSize=65536 | 5 | 296,500 | 296,395 | 296,820 | 296,568 | 0.06 |
| `jceMac_1t` | payloadSize=8192 | 5 | 38,157 | 38,084 | 38,188 | 38,148 | 0.11 |
| `jceMac_4t` | payloadSize=1024 | 5 | 6,037 | 6,024 | 6,084 | 6,052 | 0.46 |
| `jceMac_4t` | payloadSize=64 | 5 | 1,634 | 1,616 | 1,720 | 1,657 | 2.65 |
| `jceMac_4t` | payloadSize=65536 | 5 | 301,696 | 301,269 | 302,204 | 301,720 | 0.14 |
| `jceMac_4t` | payloadSize=8192 | 5 | 38,853 | 38,845 | 38,871 | 38,857 | 0.03 |
| `nativeDigest_16t` | payloadSize=1024 | 5 | 2,080 | 1,978 | 2,231 | 2,073 | 4.83 |
| `nativeDigest_16t` | payloadSize=64 | 5 | 1,400 | 1,059 | 1,414 | 1,278 | 13.99 |
| `nativeDigest_16t` | payloadSize=65536 | 5 | 57,443 | 57,285 | 58,716 | 57,751 | 1.03 |
| `nativeDigest_16t` | payloadSize=8192 | 5 | 8,199 | 8,153 | 8,273 | 8,206 | 0.55 |
| `nativeDigest_1t` | payloadSize=1024 | 5 | 790.86 | 790.53 | 802.19 | 794.37 | 0.67 |
| `nativeDigest_1t` | payloadSize=64 | 5 | 393.56 | 388.22 | 399.30 | 393.02 | 1.16 |
| `nativeDigest_1t` | payloadSize=65536 | 5 | 27,796 | 27,739 | 27,850 | 27,802 | 0.17 |
| `nativeDigest_1t` | payloadSize=8192 | 5 | 3,801 | 3,782 | 3,813 | 3,799 | 0.30 |
| `nativeDigest_4t` | payloadSize=1024 | 5 | 949.48 | 934.37 | 966.70 | 951.47 | 1.31 |
| `nativeDigest_4t` | payloadSize=64 | 5 | 523.78 | 496.13 | 524.97 | 515.78 | 2.43 |
| `nativeDigest_4t` | payloadSize=65536 | 5 | 28,362 | 28,322 | 28,375 | 28,353 | 0.08 |
| `nativeDigest_4t` | payloadSize=8192 | 5 | 4,007 | 3,989 | 4,026 | 4,008 | 0.35 |
| `nativeMac_16t` | payloadSize=1024 | 5 | 2,446 | 2,384 | 2,611 | 2,481 | 4.34 |
| `nativeMac_16t` | payloadSize=64 | 5 | 1,742 | 1,574 | 1,819 | 1,695 | 6.61 |
| `nativeMac_16t` | payloadSize=65536 | 5 | 57,656 | 57,525 | 58,535 | 57,799 | 0.72 |
| `nativeMac_16t` | payloadSize=8192 | 5 | 8,645 | 8,354 | 8,723 | 8,596 | 1.66 |
| `nativeMac_1t` | payloadSize=1024 | 5 | 1,017 | 1,009 | 1,021 | 1,015 | 0.53 |
| `nativeMac_1t` | payloadSize=64 | 5 | 611.00 | 606.41 | 613.87 | 610.51 | 0.51 |
| `nativeMac_1t` | payloadSize=65536 | 5 | 27,971 | 27,939 | 28,076 | 28,004 | 0.22 |
| `nativeMac_1t` | payloadSize=8192 | 5 | 4,021 | 4,011 | 4,036 | 4,022 | 0.23 |
| `nativeMac_4t` | payloadSize=1024 | 5 | 1,125 | 1,114 | 1,143 | 1,128 | 1.20 |
| `nativeMac_4t` | payloadSize=64 | 5 | 706.13 | 691.21 | 765.71 | 717.68 | 4.00 |
| `nativeMac_4t` | payloadSize=65536 | 5 | 28,599 | 28,578 | 28,726 | 28,623 | 0.21 |
| `nativeMac_4t` | payloadSize=8192 | 5 | 4,175 | 4,158 | 4,199 | 4,176 | 0.42 |
| | | | | | | | |
