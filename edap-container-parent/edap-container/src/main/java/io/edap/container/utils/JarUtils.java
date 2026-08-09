package io.edap.container.utils;

import io.edap.container.mw.BuildInfo;
import io.edap.container.mw.MavenInfo;
import io.edap.json.Eson;
import io.edap.launcher.NestedJarFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public class JarUtils {

    private JarUtils() {}

    public static MavenInfo scanMavenInfo(NestedJarFile earFile, String name) {
        Properties props = new Properties();
        try {
            props.load(earFile.getInputStream(name));
            MavenInfo mavenInfo = new MavenInfo();
            mavenInfo.setArtifactId(props.getProperty("artifactId"));
            mavenInfo.setGroupId(props.getProperty("groupId"));
            mavenInfo.setVersion(props.getProperty("version"));

            return mavenInfo;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static BuildInfo scanBuildInfo(NestedJarFile earFile, String name) {
        try {
            InputStream in = earFile.getInputStream(name);
            byte[] data = in.readAllBytes();
            return Eson.parseObject(data, BuildInfo.class);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
