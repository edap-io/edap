package io.edap.launcher;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ZIP / JAR 中央目录解析器。
 *
 * ZIP 文件结构:
 *   [Local File Header + File Data] * N
 *   [Central Directory Entry]      * N   ← 我们要解析这一段
 *   [End of Central Directory Record]      ← 入口点:从文件末尾找到它
 *
 * 中央目录每个 entry 包含 name、压缩方法、crc32、压缩大小、未压缩大小、
 * 以及**数据在文件中的偏移**(relative offset of local header)。
 */
final class CentralDirectoryParser {

    static final int EOCD_SIGNATURE      = 0x06054b50;
    static final int ZIP64_EOCD_SIGNATURE = 0x06064b50;
    static final int ZIP64_EOCD_LOC_SIGNATURE = 0x07064b50;
    static final int CD_ENTRY_SIGNATURE   = 0x02014b50;

    static final long EOCD_MIN_SIZE = 22;
    static final long EOCD_MAX_SIZE = 22 + 0xFFFF;          // 最大注释长度
    static final int  MAX_COMMENT_LEN = 0xFFFF;

    private CentralDirectoryParser() {}

    /**
     * 从 RandomAccessFile 末尾往前找 EOCD(EOCD 可能在 ZIP64 情况下被 Zip64 EOCD 取代)。
     * 返回 EOCD 起始位置;失败抛 IllegalArgumentException。
     */
    static long findEocd(RandomAccessFile raf) throws IOException {
        long fileLength = raf.length();
        long searchStart = Math.max(0, fileLength - EOCD_MAX_SIZE);
        long searchEnd   = fileLength;

        // 在 [searchStart, searchEnd) 范围内倒序查找 EOCD signature
        // EOCD signature = 0x06054b50
        byte[] tail = readRange(raf, searchStart, searchEnd - searchStart);
        int sig = EOCD_SIGNATURE;
        for (int i = tail.length - 22; i >= 0; i--) {
            int v = readIntLE(tail, i);
            if (v == sig) {
                // 校验一下 comment length 字段是否合理
                int commentLen = readShortLE(tail, i + 20) & 0xFFFF;
                long expectedSize = (i + 22L + commentLen) - searchStart;
                if (expectedSize == tail.length) {
                    return searchStart + i;
                }
            }
        }
        throw new IllegalArgumentException("End of Central Directory Record not found");
    }

    /**
     * 解析中央目录,返回有序的 entry 列表(name → EntryData)。
     * EOCD 位置由 {@link #findEocd} 给出。
     */
    static Map<String, EntryData> parse(RandomAccessFile raf, long eocdOffset) throws IOException {
        // EOCD 头部布局:
        //   0  : signature       (4)
        //   4  : disk number     (2)
        //   6  : disk with CD    (2)
        //   8  : entries on disk (2)
        //   10 : total entries   (2)
        //   12 : CD size         (4)
        //   16 : CD offset       (4) ← 中央目录起点
        //   20 : comment length  (2)
        byte[] eocd = readRange(raf, eocdOffset, 22);
        long cdSize   = readIntLE(eocd, 12) & 0xFFFFFFFFL;
        long cdOffset = readIntLE(eocd, 16) & 0xFFFFFFFFL;

        // 简易 ZIP64 处理:如果 cdSize 或 cdOffset 是 0xFFFFFFFF,需要再读 Zip64 EOCD
        boolean needZip64 =
                (readIntLE(eocd, 12) == 0xFFFFFFFFL) ||
                        (readIntLE(eocd, 16) == 0xFFFFFFFFL) ||
                        (readShortLE(eocd, 10) == 0xFFFF);   // total entries
        if (needZip64) {
            long zip64EocdOffset = findZip64Eocd(raf, eocdOffset);
            byte[] zip64 = readRange(raf, zip64EocdOffset, 56);
            cdSize   = readLongLE(zip64, 40);
            cdOffset = readLongLE(zip64, 48);
        }

        byte[] cd = readRange(raf, cdOffset, cdSize);
        Map<String, EntryData> result = new LinkedHashMap<>();
        int pos = 0;
        while (pos < cd.length) {
            int sig = readIntLE(cd, pos);
            if (sig != CD_ENTRY_SIGNATURE) {
                throw new IOException("Bad Central Directory signature at offset " + (cdOffset + pos));
            }
            int  method        = readShortLE(cd, pos + 10) & 0xFFFF;
            long crc           = readIntLE(cd, pos + 16) & 0xFFFFFFFFL;
            long csize         = readIntLE(cd, pos + 20) & 0xFFFFFFFFL;
            long usize         = readIntLE(cd, pos + 24) & 0xFFFFFFFFL;
            int  nameLen       = readShortLE(cd, pos + 28) & 0xFFFF;
            int  extraLen      = readShortLE(cd, pos + 30) & 0xFFFF;
            int  commentLen    = readShortLE(cd, pos + 32) & 0xFFFF;
            long localHdrOff   = readIntLE(cd, pos + 42) & 0xFFFFFFFFL;

            // ZIP64:如果字段是 0xFFFFFFFF,去 extra 段取真实值
            if (csize == 0xFFFFFFFFL || usize == 0xFFFFFFFFL || localHdrOff == 0xFFFFFFFFL) {
                int extraPos = pos + 46 + nameLen;
                int extraEnd = extraPos + extraLen;
                while (extraPos < extraEnd - 4) {
                    int hdrId = readShortLE(cd, extraPos) & 0xFFFF;
                    int hdrSz = readShortLE(cd, extraPos + 2) & 0xFFFF;
                    if (hdrId == 0x0001) {      // Zip64 extended info
                        int p = extraPos + 4;
                        if (usize == 0xFFFFFFFFL) { usize = readLongLE(cd, p); p += 8; }
                        if (csize == 0xFFFFFFFFL) { csize = readLongLE(cd, p); p += 8; }
                        if (localHdrOff == 0xFFFFFFFFL) { localHdrOff = readLongLE(cd, p); p += 8; }
                        break;
                    }
                    extraPos += 4 + hdrSz;
                }
            }

            String name = new String(cd, pos + 46, nameLen, java.nio.charset.StandardCharsets.UTF_8);
            EntryData data = new EntryData(method, crc, csize, usize, localHdrOff);
            result.put(name, data);
            pos += 46 + nameLen + extraLen + commentLen;
        }
        return result;
    }

    /** 解析 local file header,返回数据相对 local header 起始位置的偏移(跳过 filename+extra)。 */
    static int parseLocalHeaderSkip(byte[] localHeader) {
        // Local header 布局:
        //   0 : signature    (4)
        //   4 : version      (2)
        //   6 : flags        (2)
        //   8 : method       (2)
        //   10: mod time     (2)
        //   12: mod date     (2)
        //   14: crc32        (4)
        //   18: csize        (4)
        //   22: usize        (4)
        //   26: nameLen      (2)
        //   28: extraLen     (2)
        //   30: filename     (nameLen)
        //      : extra        (extraLen)
        //      : <data>      (csize)
        int nameLen  = readShortLE(localHeader, 26) & 0xFFFF;
        int extraLen = readShortLE(localHeader, 28) & 0xFFFF;
        return 30 + nameLen + extraLen;
    }

    /** 从 EOCD 往前找 Zip64 EOCD Locator(在 EOCD 之前)。 */
    private static long findZip64Eocd(RandomAccessFile raf, long eocdOffset) throws IOException {
        // Zip64 EOCD Locator 紧接在 EOCD 之前,长度固定 20 字节
        long start = eocdOffset - 20;
        byte[] buf = readRange(raf, start, 20);
        if (readIntLE(buf, 0) != ZIP64_EOCD_LOC_SIGNATURE) {
            throw new IllegalStateException("Expected Zip64 EOCD Locator");
        }
        long zip64EocdOffset = readLongLE(buf, 8);
        // 验证 Zip64 EOCD signature
        byte[] sig = readRange(raf, zip64EocdOffset, 4);
        if (readIntLE(sig, 0) != ZIP64_EOCD_SIGNATURE) {
            throw new IllegalStateException("Bad Zip64 EOCD signature");
        }
        return zip64EocdOffset;
    }

    static byte[] readRange(RandomAccessFile raf, long offset, long length) throws IOException {
        if (length > Integer.MAX_VALUE) {
            throw new IOException("Range too large: " + length);
        }
        byte[] buf = new byte[(int) length];
        raf.seek(offset);
        raf.readFully(buf);
        return buf;
    }

    static int readShortLE(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    static int readIntLE(byte[] b, int off) {
        return (b[off] & 0xFF)
                | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16)
                | ((b[off + 3] & 0xFF) << 24);
    }

    static long readLongLE(byte[] b, int off) {
        return (b[off] & 0xFFL)
                | ((b[off + 1] & 0xFFL) << 8)
                | ((b[off + 2] & 0xFFL) << 16)
                | ((b[off + 3] & 0xFFL) << 24)
                | ((b[off + 4] & 0xFFL) << 32)
                | ((b[off + 5] & 0xFFL) << 40)
                | ((b[off + 6] & 0xFFL) << 48)
                | ((b[off + 7] & 0xFFL) << 56);
    }

    /** 中央目录里的一条 entry。 */
    static final class EntryData {
        final int  method;
        final long crc;
        final long csize;
        final long usize;
        final long localHeaderOffset;     // 相对文件起点的偏移

        EntryData(int method, long crc, long csize, long usize, long localHeaderOffset) {
            this.method = method;
            this.crc = crc;
            this.csize = csize;
            this.usize = usize;
            this.localHeaderOffset = localHeaderOffset;
        }
    }
}