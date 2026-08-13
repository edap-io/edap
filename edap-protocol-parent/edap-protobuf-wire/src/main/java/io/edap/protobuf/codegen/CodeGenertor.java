package io.edap.protobuf.codegen;

import io.edap.protobuf.builder.JavaBuildOption;
import io.edap.protobuf.wire.Proto;
import io.edap.protobuf.wire.exceptions.ProtoParseException;
import io.edap.protobuf.wire.parser.ProtoParser;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

public class CodeGenertor {

    private static String[] EMPTY_STRING_ARRAY = new String[0];

    public static void main(String[] args) throws IOException {
        FuncParams params = parseFuncParams(args);
        String protoPath = params.options.get("-I");
        if (protoPath == null || protoPath.length() == 0) {
            protoPath = params.options.get("--proto_path");
        }
        if (isEmpty(protoPath)) {
            protoPath = "./";
        }
        String javaOut = params.options.get("--java_out");
        if (isEmpty(javaOut)) {
            if (protoPath.equals("./")) {
                System.out.println("使用--java_out 指定java代码存放路径");
                return;
            } else {
                javaOut = "./";
            }
        }

        generate(protoPath, javaOut);

    }

    private static boolean isEmpty(String str) {
        return str == null || str.isEmpty();
    }

    public static void generate(String protoPath, String javaOut) throws IOException {
        long startTime = System.currentTimeMillis();
        List<Path> protoPaths = findByExtension(Paths.get(protoPath), "proto");
        if (protoPaths == null || protoPaths.isEmpty()) {
            System.out.println(new File(protoPath).getAbsolutePath() + " 目录没有.proto的文件");
            return;
        }
        Collections.sort(protoPaths, Comparator.comparing(o -> o.getFileName().toString()));
        StringBuilder builder = new StringBuilder();

        List<Proto> protos = new ArrayList<>();
        String protoPathAbs = new File(protoPath).getAbsolutePath();
        for (Path path : protoPaths) {
            //System.out.println("path=" + path.toString());
            try {
                Proto proto = parseProto(path, builder);
                String absPath = path.toString();
                String name = path.toString();
                if (absPath.startsWith(protoPath)) {
                    name = absPath.substring(protoPath.length());
                }
                if (name.startsWith("/")) {
                    name = name.substring(1);
                }
                proto.setName(name);
                protos.add(proto);
            } catch (ProtoParseException e) {
                e.printStackTrace();
            }
        }

        IfaceGenerator ifaceGenerator = new IfaceGenerator(new File(javaOut), protos);
        JavaBuildOption javaBuildOption = new JavaBuildOption();
        ifaceGenerator.setBuildOption(javaBuildOption);
        ifaceGenerator.generate();
        System.out.println("time=" + (System.currentTimeMillis() - startTime));
    }

    private static Proto parseProto(Path path, StringBuilder build) throws ProtoParseException {
        readToStringBuilder(path, build);
        ProtoParser protoParser = new ProtoParser(build.toString());
        //protoParser.setPrintParseInfo(true);
        return protoParser.parse();
    }

    private static void readToStringBuilder(Path path, StringBuilder builder) {
        builder.delete(0, builder.length());
        try (BufferedReader reader = new BufferedReader(new FileReader(path.toFile()))) {
            String line = reader.readLine();
            while (line != null) {
                builder.append(line).append("\n");
                line = reader.readLine();
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static List<Path> findByExtension(Path root, String ext) throws IOException {
        List<Path> result = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (file.toString().endsWith(ext)) {
                    result.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return result;
    }

    static class FuncParams {
        private Map<String, String> options;
        private String[] args;

        public Map<String, String> getOptions() {
            return options;
        }

        public void setOptions(Map<String, String> options) {
            this.options = options;
        }

        public String[] getArgs() {
            return args;
        }

        public void setArgs(String[] args) {
            this.args = args;
        }
    }

    private static FuncParams parseFuncParams(String[] args) {
        List<String> params = new ArrayList<>();
        String arg;
        Map<String, String> options = new HashMap<>();
        for (int i=0;i<args.length;i++) {
            arg = args[i];
            if (arg.startsWith("-")) {
                if (i < args.length - 1) {
                    if (!options.containsKey(arg)) {
                        options.put(arg, args[i + 1]);
                    }
                    i++;
                }
            } else {
                params.add(arg);
            }
        }

        FuncParams fp = new FuncParams();
        if (params.size() > 0) {
            fp.setArgs(params.toArray(new String[params.size()]));
        } else {
            fp.setArgs(EMPTY_STRING_ARRAY);
        }
        fp.setOptions(options);

        return fp;
    }
}
