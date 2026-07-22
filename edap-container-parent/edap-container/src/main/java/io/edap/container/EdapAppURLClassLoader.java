package io.edap.container;

import io.edap.launcher.EdapContainerURLClassLoader;

import java.io.File;
import java.io.IOException;

public class EdapAppURLClassLoader extends EdapContainerURLClassLoader {

    /**
     * 初始化一个edap应用的类加载器
     * @param jarFile edap应用的ear的包
     * @param parent 父的类加载器
     */
    public EdapAppURLClassLoader(File jarFile, ClassLoader parent) throws IOException {
        super(jarFile, parent);
    }
}
