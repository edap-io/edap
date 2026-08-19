package io.edap.protobuf.codegen;

import io.edap.protobuf.internal.CodeBuilder;
import io.edap.protobuf.wire.Message;
import io.edap.protobuf.wire.Proto;
import io.edap.protobuf.wire.Service;
import io.edap.protobuf.wire.ServiceMethod;

import java.io.File;
import java.io.IOException;
import java.util.*;

import static io.edap.protobuf.builder.JavaBuilder.*;

public class EmptyImplGenerator {

    private File javaOut;
    private List<Proto> protos;
    private CodeCreatePrint codeCreatePrint;

    public EmptyImplGenerator(File javaOut, List<Proto> protos) {
        this.javaOut = javaOut;
        this.protos  = protos;
    }

    public void generate() {

        List<ServiceInfo> services = new ArrayList<>();
        Set<String> impProtos = new HashSet<>();
        Map<String, Proto> protoMap = new HashMap<>();
        Map<String, String> messageMap = new HashMap<>();
        messageMap.put("google.protobuf.Empty", "com.google.protobuf.Empty");
        for (Proto proto : protos) {
            protoMap.put(proto.getName(), proto);
            List<Service> ss = proto.getServices();
            List<Message> msgs = proto.getMessages();
            if (msgs != null && !msgs.isEmpty()) {
                for (Message m : msgs) {
                    String msgJavaName = getJavaPackage(proto) + "." + m.getName();
                    messageMap.put(proto.getProtoPackage() + "." + m.getName(), msgJavaName);
                }
            }
            if (ss == null || ss.isEmpty()) {
                continue;
            }
            String javaPackName = getJavaPackage(proto);
            for (Service s : ss) {
                ServiceInfo serviceInfo = new ServiceInfo();
                serviceInfo.setService(s);
                serviceInfo.setPackName(proto.getProtoPackage());
                serviceInfo.setJavaPackName(javaPackName);
                services.add(serviceInfo);
            }
            List<String> imps = proto.getImports();
            if (imps != null && !imps.isEmpty()) {
                for (String imp : imps) {
                    impProtos.add(imp);
                }
            }
        }

        for (ServiceInfo si : services) {
            generateServiceImpl(si, messageMap);
        }
    }

    private void generateServiceImpl(ServiceInfo serviceInfo, Map<String, String> messageMap) {
        String packName = getImplPackName(serviceInfo.getJavaPackName());
        String ifaceName = serviceInfo.getJavaPackName() + "." + serviceInfo.service.getName();
        List<String> imps = new ArrayList<>();
        imps.add(ifaceName);
        imps.add("io.edap.microservice.annotation.MicroServiceBean");
        for (ServiceMethod sm : serviceInfo.service.getMethods()) {
            String respName = sm.getResponse();
            String reqName = sm.getRequest();
            if (respName.startsWith(serviceInfo.getPackName())) {
                respName = serviceInfo.getJavaPackName() + "." +
                        respName.substring(serviceInfo.getPackName().length() + 1);
            } else {
                if (respName.indexOf('.') != -1) {
                    respName = messageMap.get(respName);
                } else {
                    respName = serviceInfo.getJavaPackName() + "." + respName;
                }
            }
            if (reqName.startsWith(serviceInfo.getPackName())) {
                reqName = serviceInfo.getJavaPackName() + "." +
                        reqName.substring(serviceInfo.getPackName().length() + 1);
            } else {
                if (reqName.indexOf('.') != -1) {
                    reqName = messageMap.get(reqName);
                } else {
                    reqName = serviceInfo.getJavaPackName() + "." + reqName;
                }
            }
            if (!imps.contains(respName)) {
                imps.add(respName);
            }
            if (!imps.contains(reqName)) {
                imps.add(reqName);
            }
        }
        String ifaceSimpleName;
        int index = ifaceName.lastIndexOf('.');
        if (index == -1) {
            ifaceSimpleName = ifaceName;
        } else {
            ifaceSimpleName = ifaceName.substring(index + 1);
        }
        CodeBuilder cb = new CodeBuilder();
        cb.c("package ").c(packName).c(";").ln(2);

        Collections.sort(imps);
        for (String imp : imps) {
            cb.c("import ").c(imp).c(";").ln();
        }

        cb.ln();
        cb.c("@MicroServiceBean").ln();
        cb.c("public class ").c(ifaceSimpleName).c("Impl implements ").c(ifaceSimpleName).c(" {").ln(2);

        for (ServiceMethod sm : serviceInfo.service.getMethods()) {
            String respSimpleName = simpleName(sm.getResponse());
            String reqSimpleName = simpleName(sm.getRequest());
            cb.t(1).c("public ").c(respSimpleName).c(" ").c(lowerFirstChar(sm.getName())).c("(")
                    .c(reqSimpleName).c(" ").c(lowerFirstChar(reqSimpleName)).c(") {").ln();
            String respVar = "resp";
            cb.t(2).c(respSimpleName).c(" ").c(respVar).c(" = new ").c(respSimpleName).c("();").ln();

            cb.t(2).c("return ").c(respVar).c(";").ln();
            cb.t(1).c("}").ln(2);
        }

        cb.c("}").ln();

        try {
            File fileDir = new File(javaOut.getAbsolutePath() + File.separator +
                    packName.replace('.', File.separatorChar));
            if (!fileDir.exists()) {
                fileDir.mkdirs();
            }
            File file = new File(fileDir.getAbsolutePath() + File.separator + ifaceSimpleName + "Impl.java");
            if (!file.exists()) {
                String dir = file.getCanonicalPath();
                String projJavaDir = javaOut.getCanonicalPath();
                if (codeCreatePrint != null) {
                    codeCreatePrint.print("create java file: " + dir.substring(projJavaDir.length()));
                } else {
                    System.out.println("create java file: " + dir.substring(projJavaDir.length()));
                }
                saveJavaFile(fileDir.getAbsolutePath() + File.separator + ifaceSimpleName + "Impl.java",
                        cb.toString(), false);
            }

        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }

    private static String lowerFirstChar(String name) {
        return name.substring(0, 1).toLowerCase(Locale.ENGLISH) + name.substring(1);
    }

    private static String simpleName(String name) {
        int index = name.lastIndexOf('.');
        if (index == -1) {
            return name;
        }
        return name.substring(index + 1);
    }

    private static String getImplPackName(String packName) {
        return packName + ".impl";
    }

    public CodeCreatePrint getCodeCreatePrint() {
        return codeCreatePrint;
    }

    public void setCodeCreatePrint(CodeCreatePrint codeCreatePrint) {
        this.codeCreatePrint = codeCreatePrint;
    }

    static class ServiceInfo {
        private String javaPackName;
        private String packName;
        private Service service;

        public String getJavaPackName() {
            return javaPackName;
        }

        public void setJavaPackName(String javaPackName) {
            this.javaPackName = javaPackName;
        }

        public Service getService() {
            return service;
        }

        public void setService(Service service) {
            this.service = service;
        }

        public String getPackName() {
            return packName;
        }

        public void setPackName(String packName) {
            this.packName = packName;
        }
    }

}
