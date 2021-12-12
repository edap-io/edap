/*
 * Copyright 2020 The edap Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.edap.protobuf.persisters;

import io.edap.protobuf.ProtoPersister;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

import static io.edap.util.AsmUtil.toInternalName;

/**
 * 使用本地文件存储的proto持久化器
 * @author : louis@easyea.com
 */
public class FileProtoPersister implements ProtoPersister {

    private String protoPath;

    /**
     * 使用指定proto文件保存目录初始化一个FileProtoPersister
     * @param protoPath
     */
    public FileProtoPersister(final String protoPath) {
        if (!protoPath.endsWith(File.separator)) {
            this.protoPath = protoPath + File.separator;
        } else {
            this.protoPath = protoPath;
        }
    }

    @Override
    public void persist(String beanName, String proto) throws IOException {
        String name = checkPath(beanName);
        try {
            RandomAccessFile file = new RandomAccessFile(name, "rw");
            file.setLength(0);
            file.write(proto.getBytes(StandardCharsets.UTF_8));
        } catch (Throwable ex) {
            throw new IOException("create " + name + " error!", ex);
        }
    }

    @Override
    public String getProto(String beanName) throws IOException {
        String name = checkPath(beanName);
        RandomAccessFile file;
        try {
            file = new RandomAccessFile(name, "r");
            byte[] bs = new byte[(int)file.length()];
            file.read(bs);
            return new String(bs, "utf-8");
        } catch (Throwable ex) {
            return null;
        }
    }

    private String checkPath(String beanName) throws IOException {
        String name = protoPath + toInternalName(beanName) + ".proto";
        try {
            int index = name.lastIndexOf("/");
            File dir;
            if (index == -1) {
                dir = new File(protoPath);
            } else {
                dir = new File(name.substring(0, index));
            }
            if (!dir.exists()) {
                if (!dir.mkdirs()) {
                    throw new IOException("mkdir " + dir.getAbsolutePath() + " error!");
                }
            }
            return name;
        } catch (Throwable ex) {
            throw new IOException("create " + name + " error!", ex);
        }
    }
}
