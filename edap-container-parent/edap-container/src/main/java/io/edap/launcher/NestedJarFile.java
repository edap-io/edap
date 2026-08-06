package io.edap.launcher;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;
import java.util.zip.ZipEntry;

import static io.edap.launcher.CentralDirectoryParser.*;

/**
 * 不写临时目录的 JAR 读取器。
 *
 * 设计要点:
 *  - 最外层 jar 使用 RandomAccessFile,所有 entry 的字节按需通过 seek + read 取出(零拷贝)。
 *  - 嵌套 jar 在外层 jar 里通常以 DEFLATED 形式存储 — 外层压缩后,内层 EOCD 字节已不可识别,
 *    所以 getNestedJarFile 必须先把外层 entry 的字节**完整解压**到内存,再用 byte[] 解析内层 jar。
 *  - 嵌套 jar 一旦解压到内存后,内部 entry 的 STORED/DEFLATED 都通过 Inflater 处理。
 *
 * 这是 Spring Boot spring-boot-loader 中 org.springframework.boot.loader.jar.JarFile
 * 的精简实现,核心思想一致:不展开、不复制,直接在字节流上工作。
 */
public final class NestedJarFile implements Closeable {

    /** 最外层 jar 的物理文件;嵌套 jar 的 NestedJarFile 也指向同一物理文件(byte[] 模式下为 null)。 */
    private final RandomAccessFile physicalFile;

    /** 内层 jar 的完整原始字节(byte[] 模式);RandomAccessFile 模式下为 null。 */
    private final byte[] rawBytes;

    /** 这个 jar 在 physicalFile 中的数据起始偏移(嵌套 jar > 0,最外层 = 0)。 */
    private final long dataOffsetInParent;

    /** 这个 jar 的总字节数。 */
    private final long dataLength;

    /** 这个 jar 的中央目录项:entry name → EntryData。 */
    private final Map<String, CentralDirectoryParser.EntryData> entries;

    private final String name;     // 用于调试和 URL 区分

    /** 最外层 jar:用文件路径构造。 */
    public NestedJarFile(java.io.File file) throws IOException {
        this(new RandomAccessFile(file, "r"), 0L, file.length(), file.getName());
    }

    /** 嵌套 jar:在外层 jar 字节流中,数据范围 [dataOffset, dataOffset+dataLength)。 */
    NestedJarFile(RandomAccessFile physicalFile, long dataOffset, long dataLength, String name) throws IOException {
        this.physicalFile       = physicalFile;
        this.rawBytes           = null;
        this.dataOffsetInParent = dataOffset;
        this.dataLength         = dataLength;
        this.name               = name;

        // 从 physicalFile 的指定 slice 里找 EOCD
        long   eocdOffsetInPhysical = findEocdInFileSlice();
        byte[] eocd                 = readRange(physicalFile, eocdOffsetInPhysical, 22);
        long   cdSize               = readIntLE(eocd, 12) & 0xFFFFFFFFL;
        long   cdOffset             = readIntLE(eocd, 16) & 0xFFFFFFFFL;
        // 解析中央目录
        byte[] cd = readRange(physicalFile, dataOffsetInParent + cdOffset, cdSize);
        this.entries = parseCentralDirectory(cd);
    }

    /**
     * 内存模式构造:用于嵌套 jar 的解压后字节。
     *
     * 为什么需要这个:嵌套 jar 在外层 jar 里通常是 DEFLATED 的,字节流中找不到内层 EOCD signature,
     * 所以必须先解压成完整的原始字节,再用 byte[] 解析中央目录。解压后字节通常 1-5 MB,
     * 几十个三方 jar 同时加载也才几十 MB,内存开销可接受。
     */
    NestedJarFile(byte[] rawBytes, String name) throws IOException {
        this.physicalFile       = null;
        this.rawBytes           = rawBytes;
        this.dataOffsetInParent = 0;
        this.dataLength         = rawBytes.length;
        this.name               = name;

        // 在 rawBytes 里找 EOCD
        int    eocdOffsetInSlice = findEocdInBytes(rawBytes);
        byte[] eocd              = Arrays.copyOfRange(rawBytes, eocdOffsetInSlice, eocdOffsetInSlice + 22);
        long   cdSize            = readIntLE(eocd, 12) & 0xFFFFFFFFL;
        long   cdOffset          = readIntLE(eocd, 16) & 0xFFFFFFFFL;
        byte[] cd                = Arrays.copyOfRange(rawBytes, (int) cdOffset, (int) (cdOffset + cdSize));
        this.entries = parseCentralDirectory(cd);
    }

    private long findEocdInFileSlice() throws IOException {
        // EOCD 必须在 [dataOffsetInParent, dataOffsetInParent + dataLength) 范围内
        long   end         = dataOffsetInParent + dataLength;
        long   searchStart = Math.max(dataOffsetInParent, end - EOCD_MAX_SIZE);
        long   len         = end - searchStart;
        byte[] tail        = readRange(physicalFile, searchStart, len);
        int    sig         = EOCD_SIGNATURE;
        for (int i = tail.length - 22; i >= 0; i--) {
            if (readIntLE(tail, i) == sig) {
                int commentLen = readShortLE(tail, i + 20) & 0xFFFF;
                if (i + 22 + commentLen == tail.length) {
                    return searchStart + i;
                }
            }
        }
        throw new IllegalArgumentException("EOCD not found in slice " + name);
    }

    private static int findEocdInBytes(byte[] data) {
        int  sig      = EOCD_SIGNATURE;
        long maxStart = Math.max(0, data.length - (int) EOCD_MAX_SIZE);
        for (int i = data.length - 22; i >= maxStart; i--) {
            if (readIntLE(data, i) == sig) {
                int commentLen = readShortLE(data, i + 20) & 0xFFFF;
                if (i + 22 + commentLen == data.length) {
                    return i;
                }
            }
        }
        throw new IllegalArgumentException("EOCD not found in byte[] slice");
    }

    private static Map<String, CentralDirectoryParser.EntryData> parseCentralDirectory(byte[] cd) throws IOException {
        Map<String, CentralDirectoryParser.EntryData> result = new LinkedHashMap<>();
        int pos = 0;
        while (pos < cd.length) {
            int sig = readIntLE(cd, pos);
            if (sig != CD_ENTRY_SIGNATURE) {
                throw new IOException("Bad CD signature at offset " + pos);
            }
            int    method     = readShortLE(cd, pos + 10) & 0xFFFF;
            long   crc        = readIntLE  (cd, pos + 16) & 0xFFFFFFFFL;
            long   csize      = readIntLE  (cd, pos + 20) & 0xFFFFFFFFL;
            long   usize      = readIntLE  (cd, pos + 24) & 0xFFFFFFFFL;
            int    nameLen    = readShortLE(cd, pos + 28) & 0xFFFF;
            int    extraLen   = readShortLE(cd, pos + 30) & 0xFFFF;
            int    commentLen = readShortLE(cd, pos + 32) & 0xFFFF;
            long   localOff   = readIntLE  (cd, pos + 42) & 0xFFFFFFFFL;
            String name       = new String (cd, pos + 46, nameLen, StandardCharsets.UTF_8);
            result.put(name, new CentralDirectoryParser.EntryData(method, crc, csize, usize, localOff));
            pos += 46 + nameLen + extraLen + commentLen;
        }
        return result;
    }

    public boolean hasEntry(String name) {
        return entries.containsKey(name);
    }

    public CentralDirectoryParser.EntryData getEntry(String name) {
        return entries.get(name);
    }

    public Set<String> entryNames() {
        return entries.keySet();
    }

    /** 这个 jar 的逻辑名字:最外层 jar 用文件名,嵌套 jar 用在外层里的 entry 路径。 */
    public String getName() {
        return name;
    }

    /**
     * 按名称读取 entry 的字节内容(全部加载到内存)。
     */
    public byte[] readEntryBytes(String name) throws IOException {
        CentralDirectoryParser.EntryData e = entries.get(name);
        if (e == null) {
            throw new IOException("Entry not found: " + name);
        }
        return readEntryBytes(e);
    }

    private byte[] readEntryBytes(CentralDirectoryParser.EntryData e) throws IOException {
        long absLocalHdr = dataOffsetInParent + e.localHeaderOffset;

        // 1. 读 local header
        byte[] lh;
        if (physicalFile != null) {
            lh = readRange(physicalFile, absLocalHdr, 30);
        } else {
            lh = Arrays.copyOfRange(rawBytes, (int) absLocalHdr, (int) (absLocalHdr + 30));
        }
        int skip = parseLocalHeaderSkip(lh);
        long absDataStart = absLocalHdr + skip;

        // 2. 读 entry 数据(可能是 STORED 或 DEFLATED 压缩字节)
        byte[] compressed;
        if (physicalFile != null) {
            compressed = readRange(physicalFile, absDataStart, e.csize);
        } else {
            compressed = Arrays.copyOfRange(rawBytes, (int) absDataStart, (int) (absDataStart + e.csize));
        }

        // 3. 解压(如果是 DEFLATED)或直接返回(如果是 STORED)
        if (e.method == ZipEntry.STORED) {
            return compressed;
        } else if (e.method == ZipEntry.DEFLATED) {
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) e.usize);
            try (InflaterInputStream iis = new InflaterInputStream(
                    new ByteArrayInputStream(compressed), new Inflater(true))) {
                iis.transferTo(out);
            }
            byte[] result = out.toByteArray();
            if (result.length != e.usize) {
                throw new IOException("Decompressed size mismatch for entry: " + result.length + " vs " + e.usize);
            }
            return result;
        } else {
            throw new IOException("Unsupported compression method: " + e.method);
        }
    }

    /**
     * 按名称读取 entry 的 InputStream(流式解压)。调用方负责关闭。
     */
    public InputStream getInputStream(String name) throws IOException {
        CentralDirectoryParser.EntryData e = entries.get(name);
        if (e == null) {
            throw new IOException("Entry not found: " + name);
        }

        long absLocalHdr = dataOffsetInParent + e.localHeaderOffset;
        byte[] lh;
        if (physicalFile != null) {
            lh = readRange(physicalFile, absLocalHdr, 30);
        } else {
            lh = Arrays.copyOfRange(rawBytes, (int) absLocalHdr, (int) (absLocalHdr + 30));
        }
        long absDataStart = absLocalHdr + parseLocalHeaderSkip(lh);

        InputStream raw;
        if (physicalFile != null) {
            physicalFile.seek(absDataStart);
            raw = new InputStream() {
                long remaining = e.csize;
                @Override
                public int read() throws IOException {
                    if (remaining <= 0) return -1;
                    int b = physicalFile.read();
                    if (b >= 0) remaining--;
                    return b;
                }

                @Override
                public int read(byte[] buf, int off, int len) throws IOException {
                    if (remaining <= 0) return -1;
                    int n = (int) Math.min(len, remaining);
                    int got = physicalFile.read(buf, off, n);
                    if (got > 0) remaining -= got;
                    return got;
                }
            };
        } else {
            byte[] entryBytes = Arrays.copyOfRange(
                    rawBytes, (int) absDataStart, (int) (absDataStart + e.csize));
            raw = new ByteArrayInputStream(entryBytes);
        }

        if (e.method == ZipEntry.STORED) {
            return raw;
        }
        if (e.method == ZipEntry.DEFLATED) {
            return new InflaterInputStream(raw, new Inflater(true));
        }
        throw new IOException("Unsupported compression method: " + e.method);
    }

    public Enumeration<String> entryNamesEnum() {
        return Collections.enumeration(entries.keySet());
    }

    /**
     * 在当前 NestedJarFile 中找一个嵌套 jar(典型场景:BOOT-INF/lib/foo.jar),
     * 并以新的 NestedJarFile 返回。
     *
     * 关键:无论外层 entry 是 STORED 还是 DEFLATED,**先把字节解压成完整的内层 jar 字节**,
     * 再用 byte[] 构造模式解析内层 jar。
     *
     * 为什么不能直接传 RandomAccessFile + slice 范围:
     * - 如果外层 entry 是 DEFLATED,字节是压缩的,内层 EOCD signature (0x06054b50) 不可见。
     *   直接在压缩字节里找 EOCD 必然失败。
     * - 即使是 STORED,理论上可行,但统一解压让代码逻辑更简洁。
     */
    public NestedJarFile getNestedJarFile(String entryName) throws IOException {
        CentralDirectoryParser.EntryData e = entries.get(entryName);
        if (e == null) {
            return null;
        }

        // 1. 读外层 entry 的字节(可能压缩)
        byte[] outerBytes = readEntryBytes(e);

        // 2. 直接作为内层 jar 的 byte[] 传入
        return new NestedJarFile(outerBytes, entryName);
    }

    @Override
    public void close() {
        // physicalFile 由最外层 JarLauncher 关闭,嵌套 jar 不关
    }
}