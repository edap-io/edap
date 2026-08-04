package io.edap.plugin.mvn;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.jar.*;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;

@Mojo(name = "package",
        defaultPhase = LifecyclePhase.PROCESS_CLASSES,
        requiresDependencyResolution = ResolutionScope.COMPILE)
public class EdapAppPackageMojo extends AbstractMojo {

    /** 插件坐标(artifactId)。Maven 3.0+ 注入 */
    @Parameter(defaultValue = "${plugin.artifactId}", readonly = true)
    private String pluginArtifactId;

    /** 插件坐标(groupId) */
    @Parameter(defaultValue = "${plugin.groupId}", readonly = true)
    private String pluginGroupId;

    /** 插件坐标(version) */
    @Parameter(defaultValue = "${plugin.version}", readonly = true)
    private String pluginVersion;

    /** 当前 JDK specification version,如 "17" */
    @Parameter(defaultValue = "${java.specification.version}", readonly = true)
    private String javaSpecVersion;

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        getLog().info("Project.name: " + project.getName());
        // 获取解析后的所有依赖（包括传递依赖）
        Set<Artifact> artifacts = project.getArtifacts();
        for (Artifact artifact : artifacts) {
            getLog().info("Dependency: " + artifact.getFile());
            getLog().info("artifactId: " + artifact.getArtifactId());
            getLog().info("groupId: " + artifact.getGroupId());
            getLog().info("scope: " + artifact.getScope());
        }

        String earFile = project.getBasedir().getAbsolutePath() + "/target/" + project.getName() + "-"
                + project.getVersion() + ".ear";
        getLog().info("earFile: " + earFile);
        Path outJar = Paths.get(earFile);
        try {
            Files.createDirectories(outJar.getParent());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        List<String> edapContainerLibs = new ArrayList<>();
        try (BufferedReader bin = new BufferedReader(new InputStreamReader(
                EdapAppPackageMojo.class.getResourceAsStream("/edap-container-boot-lib.txt")))) {
            String line = bin.readLine();
            while (line != null) {
                if (line.trim().length() > 0) {
                    System.out.println(line);
                    edapContainerLibs.add(line);
                }
                line = bin.readLine();
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        Set<String> written = new HashSet<>();
        written.add(JarFile.MANIFEST_NAME);

        Manifest manifest = buildManifestMf();
        try (JarOutputStream jos = new JarOutputStream(
                new FileOutputStream(earFile), manifest)) {

            addMavenFiles(jos);

            // 编译输出目录:默认是 ${project.basedir}/target/classes
            File classesDir = new File(project.getBuild().getOutputDirectory());

            String classesPrefix = "";
            if (classesDir.isDirectory()) {
                System.out.println("classesDir=" + classesDir.getAbsolutePath());
                if (classesDir.list().length != 0) {
                    addDirectoryRecursive(jos, classesDir.toPath(), classesDir.toPath(),
                            ensureTrailingSlash(classesPrefix), written);
                }
            }

            jos.putNextEntry(new JarEntry("APP-INF/"));
            jos.closeEntry();
            jos.putNextEntry(new JarEntry("APP-INF/lib/"));
            jos.closeEntry();
            for (Artifact artifact : artifacts) {
                if (!"compile".equalsIgnoreCase(artifact.getScope())) {
                    continue;
                }
                String key = artifact.getGroupId() + ":" + artifact.getArtifactId();
                if (edapContainerLibs.contains(key)) {
                    continue;
                }
                addNestedJar(jos, artifact.getFile().toPath(), "APP-INF/lib/" + artifact.getFile().getName());
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 把 nested.jar 整个写进外层 jar 的 entryName 位置
     */
    public static void addNestedJar(JarOutputStream jos, Path nestedJar, String entryName) throws IOException {
        JarEntry entry = new JarEntry(entryName);
        entry.setTime(Files.getLastModifiedTime(nestedJar).toMillis());
        entry.setSize(Files.size(nestedJar));          // 重要:声明大小
        entry.setMethod(ZipEntry.DEFLATED);            // 压缩
        jos.putNextEntry(entry);
        Files.copy(nestedJar, jos);                    // 直接字节拷贝
        jos.closeEntry();
    }

    private Manifest buildManifestMf() {
        Manifest mf = new Manifest();
        Attributes attrs = mf.getMainAttributes();
        attrs.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attrs.putValue("Created-By", pluginArtifactId + "-" + pluginVersion);
        attrs.putValue("Build-Jdk-Spec", javaSpecVersion);
        try {
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            mf.write(bout);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return mf;
    }

    private void addMavenFiles(JarOutputStream jos) throws IOException {
        jos.putNextEntry(new JarEntry("META-INF/maven/" + project.getGroupId() + "/" +
                project.getArtifactId() + "/"));
        jos.closeEntry();

        String mavenPath = "META-INF/maven/" + project.getGroupId() + "/" + project.getArtifactId();
        String pomPropName = mavenPath + "/pom.properties";
        StringBuilder pomProps = new StringBuilder();
        pomProps.append("artifactId=").append(project.getArtifactId()).append("\r\n");
        pomProps.append("groupId=").append(project.getGroupId()).append("\r\n");
        pomProps.append("version=").append(project.getVersion()).append("\r\n");
        addEntry(jos, pomPropName, pomProps.toString().getBytes(StandardCharsets.UTF_8));

        String pomXml = mavenPath + "/pom.xml";
        addFile(jos, pomXml, Paths.get(project.getBasedir() + "/pom.xml"));
    }

    private static void addEntry(JarOutputStream jos, String name, byte[] content)
            throws IOException {
        if (!name.equals(JarFile.MANIFEST_NAME)) {   // manifest 由构造器处理
            jos.putNextEntry(new JarEntry(name));
            jos.write(content);
            jos.closeEntry();
        }
    }

    private static void addFile(JarOutputStream jos, String entryName, Path srcFile)
            throws IOException {
        jos.putNextEntry(new JarEntry(entryName));
        Files.copy(srcFile, jos);
        jos.closeEntry();
    }

    /**
     * 把 sourceDir 递归加到 jar。
     * @param jos         JarOutputStream
     * @param rootDir     递归的根(用于 relativize)
     * @param currentDir  当前正在处理的目录
     * @param prefixSlash jar 内的路径前缀,以 / 结尾
     * @param written     已写入的 entry 名集合,跨调用共享
     */
    private void addDirectoryRecursive(JarOutputStream jos,
                                       Path rootDir,
                                       Path currentDir,
                                       String prefixSlash,
                                       Set<String> written) throws IOException {
        // 显式栈模拟递归(防栈溢出),栈里放"待处理的目录"
        Deque<Path> stack = new ArrayDeque<>();
        stack.push(currentDir);

        while (!stack.isEmpty()) {
            Path dir = stack.pop();

            // 1. 写当前目录的占位 entry
            String dirRel = rootDir.relativize(dir).toString()
                    .replace(File.separatorChar, '/');
            String dirEntry = dirRel.isEmpty()
                    ? prefixSlash
                    : prefixSlash + dirRel + "/";
            if (dirEntry != null && dirEntry.trim().length() > 0) {
                getLog().info("dirEntry: " + dirEntry);
                if (written.add(dirEntry)) {
                    jos.putNextEntry(new JarEntry(dirEntry));
                    jos.closeEntry();
                }
            }

            // 2. 遍历子项:文件直接写,目录入栈
            List<Path> subDirs = new ArrayList<>();
            try (Stream<Path> children = Files.list(dir)) {
                children.sorted().forEach(child -> {
                    try {
                        if (Files.isDirectory(child)) {
                            subDirs.add(child);
                        } else {
                            String rel = rootDir.relativize(child).toString()
                                    .replace(File.separatorChar, '/');
                            String entryName = prefixSlash + rel;
                            if (entryName.startsWith("/")) {
                                entryName = entryName.substring(1);
                            }
                            getLog().info("entryName: " + entryName);
                            if (written.add(entryName)) {
                                jos.putNextEntry(new JarEntry(entryName));
                                Files.copy(child, jos);
                                jos.closeEntry();
                            }
                        }
                    } catch (IOException ex) {
                        throw new UncheckedIOException(ex);
                    }
                });
            }

            // 3. 子目录倒序入栈 → 出栈时正序处理(保持字典序)
            for (int i = subDirs.size() - 1; i >= 0; i--) {
                stack.push(subDirs.get(i));
            }
        }
    }

    private static String ensureTrailingSlash(String s) {
        String name = s.endsWith("/") ? s : s + "/";
        return name.startsWith("/")?name.substring(1):name;
    }
}
