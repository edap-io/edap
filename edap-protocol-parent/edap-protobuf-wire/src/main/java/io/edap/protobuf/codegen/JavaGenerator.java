/*
 * Copyright 2021 The edap Project
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

package io.edap.protobuf.codegen;

import io.edap.protobuf.builder.JavaBuildOption;
import io.edap.protobuf.wire.Proto;
import io.edap.protobuf.wire.exceptions.ProtoParseException;
import io.edap.protobuf.wire.parser.ProtoParser;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.*;

/**
 * 根据proto文件生成服务edap-protobuf标准的java代码的生成器，包含service接口生成以及普通JavaBean的生成
 */
public class JavaGenerator {

    /**
     * 使用命令行生成java的文件的功能
     * @param args
     */
    public static void main(String[] args) {
        if (args.length == 0) {
            printHelpInfo();
            return;
        }
        Map<String, String> params = parseParam(args);
        if (params.containsKey("-h")) {
            printHelpInfo();
            return;
        }

        JavaBuildOption buildOption = new JavaBuildOption();

        //获取项目的目录
        String protoPath;
        String projPath;
        if (!params.containsKey("-proj")) {
            projPath = "";
        } else {
            projPath = params.get("-proj");
        }
        if (!projPath.endsWith(File.separator)) {
            projPath += File.separator;
        }
        if (!params.containsKey("-proto")) {
            protoPath = projPath + "src/main/resources/proto";
        } else {
            protoPath = params.get("-proto");
        }

        //dto是的Bean是否为链式操作，默认为链式操作
        boolean chain = true;
        if (params.containsKey("-chain")) {
            chain = Boolean.parseBoolean(params.get("-chain"));
        }
        buildOption.setChainOper(chain);

        String dto = "";
        if (params.containsKey("-dto")) {
            dto = params.get("-dto");
        }
        buildOption.setDtoPrefix(dto);

        boolean useBoxed = false;
        if (params.containsKey("-useBoxed")) {
            useBoxed = Boolean.parseBoolean(params.get("-useBoxed"));
        }
        buildOption.setUseBoxed(useBoxed);

        boolean hasDefaultVal = false;
        if (params.containsKey("-hasDefaultValue")) {
            hasDefaultVal = Boolean.parseBoolean(params.get("-hasDefaultValue"));
        }
        buildOption.setHasDefaultValue(hasDefaultVal);

        boolean edapRpc = false;
        System.out.println("params.containsKey(\"-edap\")=" + params.get("-edap"));
        if (params.containsKey("-edap")) {
            edapRpc = Boolean.parseBoolean(params.get("-edap"));
        }
        System.out.println("edapRpc=" + edapRpc);
        buildOption.setEdapRpc(edapRpc);

        //获取需要构建的proto文件列表
        File proto = new File(protoPath);


        File srcPath;
        if (params.containsKey("-src")) {
            srcPath = new File(params.get("-src"));
        } else {
            srcPath = new File(projPath + "src/main/java");
        }
        System.out.println("srcPath=" + srcPath);
        if (!srcPath.exists()) {
            srcPath.mkdirs();
        }
        if (!srcPath.isDirectory()) {
            System.out.println("项目的src/main/java不是目录");
            return;
        }
        generate(proto, srcPath, buildOption);

        String createImpl = params.get("-createImpl");
        boolean isCreateImpl = false;
        //是否创建接口实现模块以及实现类的模板。配置文件等
        if (createImpl != null) {
            if ("true".equals(createImpl.toLowerCase(Locale.ENGLISH))
                    || "1".equals(createImpl.toLowerCase(Locale.ENGLISH))
                    || "t".equals(createImpl.toLowerCase(Locale.ENGLISH))) {
                isCreateImpl = true;
            }
        }

        //服务的端口
        int port = 0;
        if (params.containsKey("-port")) {
            try {
                port = Integer.parseInt(params.get("-port"));
            } catch (Exception e) {
                System.out.println("port不是整数");
            }
        }
        //服务名称
        String serviceName = null;
        if (params.containsKey("-service")) {
            serviceName = params.get("-service");
        }
        if (serviceName == null || serviceName.isEmpty()) {
            File file = new File(projPath);
            if (file.exists()) {
                String name = file.getName();
                if (name.endsWith("-api")) {
                    serviceName = name.substring(0, name.length() - 4);
                } else {
                    serviceName = name;
                }
            }
        }
    }

    public static void generateImpl() {

    }

    public static void generate(File proto, File srcPath, JavaBuildOption buildOption) {

        List<File> protoFiles = new ArrayList<>(16);
        if (proto.isDirectory()) {
            File[] files = proto.listFiles();
            for (File f : files) {
                if (f.getName().toLowerCase(Locale.ENGLISH).endsWith(".proto")) {
                    protoFiles.add(f);
                }
            }
        } else {
            if (proto.getName().toLowerCase(Locale.ENGLISH).endsWith(".proto")) {
                protoFiles.add(proto);
            }
        }
        if (protoFiles.isEmpty()) {
            System.out.println("没有需要构建的proto文件");
            return;
        }
        List<Proto> protos = parseProtoFile(protoFiles);
        IfaceGenerator ifaceGenerator = new IfaceGenerator(srcPath, protos);
        ifaceGenerator.setBuildOption(buildOption);
        ifaceGenerator.generate();
    }

    public static List<Proto> parseProtoFile(List<File> files) {
        List<Proto> protos = new ArrayList<>(15);
        files.forEach((File f) -> {
            try {
                String protoString = readString(f);
                if (protoString != null && !protoString.isEmpty()) {
                    ProtoParser parser = new ProtoParser(protoString);
                    Proto protoFile = parser.parse();
                    protoFile.setFile(f);
                    protos.add(protoFile);
                }
            } catch (ProtoParseException e) {
                e.printStackTrace();
            }
        });
        return protos;
    }

    /**
     * 打印工具使用的帮助信息
     */
    public static void printHelpInfo() {
        String help = "\n" +
                "-proj       api项目模块路径，路径下resources/proto/中需包含proto的服务定义\n" +
                "-src        保存生成的源代码路径,该选项优先级高于-proj\n" +
                "-proto      proto文件所在路径,该选项优先级高于-proj\n" +
                "-edap       是否添加edap-rpc框架的注解\"true\",\"false\"\n" +
                "-service    接口模块的名称，默认名称为模块名 + “-api”\n" +
                "-useBoxed   基础类型是否使用装箱的类型\n" +
                "-chain      DTO的Bean是否采用链式操作，默认是采用链式操作\"true\",\"false\"\n" +
                "-dto        DTO的Bean是否在接口的包内创建单独的子包的名称\n" +
                "-hasDefaultValue 是否设置默认值\n";
//        help +=
//                "\n" +
//                "-createImpl 是否自动创建接口的默认实现模块\"true\",\"false\"\n" +
//                "-container  接口实现的容器类型，默认为\"edap\"现在只支持edap\n" +
//                "-port       实现模块服务的端口号\n" +
//                "-implModulePath 接口实现模块的项目路名称，默认是去掉接口-api后添加-impl";
        System.out.println(help);
    }

    public static String readString(File proto) {
        StringBuilder sb = new StringBuilder((int)proto.length());
        try (RandomAccessFile protoFile = new RandomAccessFile(proto, "r")) {
            String line = protoFile.readLine();
            while (line != null) {
                if (sb.length() > 0) {
                    sb.append("\r\n");
                }
                sb.append(new String(line.getBytes("8859_1"), "utf-8"));
                line = protoFile.readLine();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return sb.toString();
    }

    /**
     * 将命令行的参数解析为Map，将"-"开头的字符串作为键，后面的字符串作为值，如果第一个没有"-"开头的字符串为protoFile的值。
     * @param args
     * @return
     */
    private static Map<String, String> parseParam(String[] args) {
        Map<String, String> params = new HashMap<>();
        if (args == null || args.length == 0) {
            return params;
        }
        for (int i=0;i<args.length;i++) {
            String arg = args[i];
            if (arg == null || arg.length() == 0) {
                continue;
            }
            if (arg.startsWith("-")) {
                if ("-h".equals(arg)) {
                    params.put("-h", "");
                } else if (i < args.length - 1) {
                    params.put(arg, args[i+1]);
                    i++;
                }
            } else if ("?".equals(arg)) {
                params.put("-h", "");
            } else {
                params.put("protoFile", args[i]);
            }
        }
        return params;
    }
}
