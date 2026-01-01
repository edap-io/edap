package io.edap.nio.nativeimpl.test;

import java.util.Properties;

public class AarchShow {

    public static void main(String[] args) {
        Properties props = System.getProperties();
        String archKey = "os.arch";
        String osKey   = "os.name";
        String os      = (String)props.get(osKey);
        System.out.println("os=" + os);
        System.out.println("arch=" + props.get(archKey));
    }
}
