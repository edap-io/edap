package io.edap.log.test.spi;

import io.edap.log.LogCompression;
import net.jpountz.lz4.LZ4Compressor;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.lz4.LZ4FrameOutputStream;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

public class Lz4Compression implements LogCompression {

    private static final int BUFFER_SIZE = 8192;

    LZ4Factory factory = LZ4Factory.fastestInstance();

    @Override
    public String getSuffix() {
        return "lz4";
    }

    @Override
    public void compress(File file2gz, File gzFile) {
        if (!file2gz.exists()) {
            throw new RuntimeException(file2gz.getAbsolutePath() + " file not founc");
        }

        if (gzFile.exists()) {
            throw new RuntimeException("The target compressed file named [" + gzFile.getName()
                    + "] exist already. Aborting file compression.");
        }
        long start = System.currentTimeMillis();
        long size = file2gz.length();
        try (BufferedInputStream bis = new BufferedInputStream(new FileInputStream(file2gz));
             LZ4FrameOutputStream lz4os = new LZ4FrameOutputStream(new FileOutputStream(gzFile), LZ4FrameOutputStream.BLOCKSIZE.SIZE_64KB)) {
            byte[] inbuf = new byte[BUFFER_SIZE];
            int n;

            while ((n = bis.read(inbuf)) != -1) {
                lz4os.write(inbuf, 0, n);
            }
            System.out.println("time: " + (System.currentTimeMillis() - start) + "ms");
        } catch (Exception e) {
            throw new RuntimeException("GzCompression compress error", e);
        }
    }
}
