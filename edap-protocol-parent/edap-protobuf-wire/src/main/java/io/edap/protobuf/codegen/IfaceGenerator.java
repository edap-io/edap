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
import io.edap.protobuf.builder.JavaBuilder;
import io.edap.protobuf.wire.*;
import io.edap.protobuf.wire.parser.ProtoParser;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static io.edap.protobuf.builder.JavaBuilder.formatTypeName;
import static io.edap.protobuf.builder.JavaBuilder.saveJavaFile;

public class IfaceGenerator {
    private final File srcPath;
    private final List<Proto> files;
    private JavaBuildOption buildOption;
    private CodeCreatePrint codeCreatePrint;

    public IfaceGenerator(File srcPath, List<Proto> files) {
        this.srcPath = srcPath;
        this.files = files;
    }

    public void setBuildOption(JavaBuildOption buildOption) {
        this.buildOption = buildOption;
    }

    public void generate() {
        List<Proto> protos = files;
        Map<String, Proto> allProtos = new HashMap<>();
        Set<String> impProtos = new HashSet<>();
        for (Proto proto : protos) {
            List<String> imps = proto.getImports();
            if (imps != null && imps.size() > 0) {
                for (String imp : imps) {
                    impProtos.add(imp);
                }
            }
            allProtos.put(proto.getName(), proto);
        }

        for (Proto proto : protos) {
            impProtos.remove(proto.getName());
        }
        for (String name : impProtos) {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(
                    IfaceGenerator.class.getResourceAsStream("/proto/" + name), StandardCharsets.UTF_8))) {
                String line = in.readLine();
                StringBuilder builder = new StringBuilder();
                while (line != null) {
                    builder.append(line).append('\n');
                    line = in.readLine();
                }
                ProtoParser parser = new ProtoParser(builder.toString());
                parser.setPrintParseInfo(true);
                parser.setCodeCreatePrint((codeCreatePrint==null)?msg -> System.out.println(msg):codeCreatePrint);
                Proto proto = parser.parse();
                proto.setName(name);
                allProtos.put(name, proto);
            } catch (Exception e) {
                System.out.println("parse error name=" + name);
                e.printStackTrace();
            }
        }

        protos.forEach(p -> {
            generateProtoItem(p, allProtos);
        });
    }

    private void generateProtoItem(Proto proto, Map<String, Proto> protos) {

        List<Option> options = proto.getOptions();
        Map<String, String> protoOptions = new HashMap<>();
        if (options != null) {
            options.forEach(o -> protoOptions.put(o.getName(), o.getValue()));
        }
        String basePackName = null;
        if (protoOptions.containsKey("java_package")) {
            basePackName = protoOptions.get("java_package");
        }
        if ((basePackName == null || basePackName.isEmpty())
                && proto.getProtoPackage() != null) {
            basePackName = proto.getProtoPackage();
        }

        JavaBuildOption buildOps = new JavaBuildOption();
        buildOps.setJavaPackage(basePackName);
        buildOps.setIsMultipleFiles(true);
        buildOps.setOuterClassName("");
        buildOps.setChainOper(true);
        buildOps.setEdapRpc(this.buildOption.isEdapRpc());
        buildOps.setUseBoxed(buildOption.isUseBoxed());
        buildOps.setHasDefaultValue(buildOption.isHasDefaultValue());
        if (buildOption != null) {
            if (!buildOption.isChainOper()) {
                buildOps.setChainOper(false);
            }
        }

        generateDtoMessage(proto, buildOps, protos);
    }

    private static String getDtoSrcPath(String packName, String dto) {
        if (dto != null && !dto.isEmpty()) {
            if (!packName.endsWith("." + dto)) {
                packName += "." + dto;
            }
        }
        return packToPath(packName);
    }

    private static String getIfaceSrcPath(String packName) {
        return packToPath(packName);
    }

    private static String packToPath(String packName) {
        StringBuilder path = new StringBuilder(100);
        int index = packName.indexOf('.');
        while (index != -1) {
            path.append(packName.substring(0, index));
            packName = packName.substring(index + 1);
            index = packName.indexOf('.');
            path.append(File.separatorChar);
        }
        path.append(packName);
        return path.toString();
    }

    private static boolean checkSrcPath(File srcPath) {
        if (srcPath.exists() && srcPath.isDirectory()) {
            return true;
        }
        if (!srcPath.exists()) {
            return srcPath.mkdirs();
        }
        return false;
    }

    public void generateDtoMessage(Proto proto, JavaBuildOption buildOps, Map<String, Proto> protos) {
        JavaBuilder builder = new JavaBuilder();

        buildOps.setDtoPrefix(buildOption.getDtoPrefix());

        if (buildOps.getDtoPrefix() != null && !buildOps.getDtoPrefix().isEmpty()) {
            if (!buildOps.getJavaPackage().endsWith("." + buildOps.getDtoPrefix())) {
                buildOps.setJavaPackage(buildOps.getJavaPackage() + "." + buildOps.getDtoPrefix());
            }
        }

        Map<String, String> msgComments = new HashMap<>();
        List<ProtoEnum> enums = proto.getEnums();
        if (enums != null && !enums.isEmpty()) {
            String enumPath = srcPath + File.separator
                    + getDtoSrcPath(buildOps.getJavaPackage(), buildOps.getDtoPrefix());
            checkSrcPath(new File(enumPath));
            enums.stream()
                    .sorted(Comparator.comparing(ProtoEnum::getName))
                    .forEach(e -> {
                                String code = builder.buildEnum(e, 1, buildOps);
                                try {
                                    saveJavaFile(enumPath + File.separator
                                            + formatTypeName(e.getName(), Service.ServiceType.UNARY) + ".java", code);
                                } catch (IOException ex) {
                                    ex.printStackTrace();
                                }
                            }
                    );

        }

        Map<String, Message> needSaveJavaFile = new HashMap<>();

        List<Message> msgs = proto.getMessages();
        if (msgs != null && !msgs.isEmpty()) {
            String msgPath = srcPath + File.separator
                    + getDtoSrcPath(buildOps.getJavaPackage(), buildOps.getDtoPrefix());
            checkSrcPath(new File(msgPath));
            msgs.stream()
                    .sorted(Comparator.comparing(Message::getName))
                    .forEach(e -> {
                        StringBuilder mc = new StringBuilder();
                        if (e.getComment() != null) {
                            mc.append(e.getComment());
                        } else {
                            if (e.getComment() != null && e.getComment().getLines() != null) {
                                e.getComment().getLines().forEach(c -> mc.append(c));
                            }
                        }
                        msgComments.put(e.getName(), mc.toString());

                        String code = builder.buildMessage(proto, e, 1, null, buildOps, protos,
                                needSaveJavaFile);
                        try {
                            saveJavaFile(msgPath + File.separator
                                    + formatTypeName(e.getName(), Service.ServiceType.UNARY) + ".java", code);
                        } catch (IOException ex) {
                            ex.printStackTrace();
                        }
                    });

        }

        List<Service> services = proto.getServices();
        if (services != null && !services.isEmpty()) {
            String pack = buildOps.getJavaPackage();
            if (buildOps.getDtoPrefix() != null
                    && buildOps.getDtoPrefix().length() > 0) {
                pack = pack.substring(0, pack.length() - buildOps.getDtoPrefix().length() - 1);
            }
            buildOps.setJavaPackage(pack);
            String servicePath = srcPath + File.separator
                    + getIfaceSrcPath(pack);
            checkSrcPath(new File(servicePath));

            services.stream()
                    .sorted(Comparator.comparing(Service::getName))
                    .forEach(s -> {
                        String code = builder.buildService(s, 1, buildOps, msgComments, proto, protos, needSaveJavaFile);
                        try {
                            saveJavaFile(servicePath + File.separator
                                    + formatTypeName(s.getName(), Service.ServiceType.UNARY) + ".java", code);
                        } catch (IOException ex) {
                            ex.printStackTrace();
                        }

                    });

        }

        if (!needSaveJavaFile.isEmpty()) {
            for (Map.Entry<String, Message> entry : needSaveJavaFile.entrySet()) {
                Message msg = entry.getValue();
                String fileDir;
                String name = entry.getKey();
                int index = name.lastIndexOf('.');
                if (index == -1) {
                    fileDir = "";
                } else {
                    fileDir = name.substring(0, index);
                }
                fileDir = fileDir.replace('.', '/');
                File file = new File(srcPath + File.separator + fileDir + File.separator
                        + formatTypeName(entry.getKey(), Service.ServiceType.UNARY) + ".java");
                if (!file.getParentFile().exists()) {
                    file.getParentFile().mkdirs();
                }
                String code = builder.buildMessage(msg.getProto(), msg, 1, new HashMap<>(), buildOps, protos,
                        needSaveJavaFile);
                try {
                    saveJavaFile(file.getAbsolutePath(), code);
                } catch (IOException ex) {
                    ex.printStackTrace();
                }
            }
        }
    }

    public CodeCreatePrint getCodeCreatePrint() {
        return codeCreatePrint;
    }

    public void setCodeCreatePrint(CodeCreatePrint codeCreatePrint) {
        this.codeCreatePrint = codeCreatePrint;
    }
}
