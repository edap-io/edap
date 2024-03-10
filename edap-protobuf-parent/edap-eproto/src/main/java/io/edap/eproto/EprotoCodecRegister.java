package io.edap.eproto;

public class EprotoCodecRegister {


    public static final EprotoCodecRegister instance() {
        return EprotoCodecRegister.SingletonHolder.INSTANCE;
    }

    private static class SingletonHolder {
        private static final EprotoCodecRegister INSTANCE = new EprotoCodecRegister();
    }

    class EprotoCodecLoader extends ClassLoader {

        public EprotoCodecLoader(ClassLoader parent) {
            super(parent);
        }

        public Class define(String className, byte[] bs, int offset, int len) {
            return super.defineClass(className, bs, offset, len);
        }
    }
}
