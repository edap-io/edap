package io.edap.protobuf;

public class ProtoPersisterManager {

    public ProtoPersister getProtoPersister() {
        return null;
    }

    public static final ProtoPersisterManager instance() {
        return ProtoPersisterManager.SingletonHolder.INSTANCE;
    }

    private static class SingletonHolder {
        private static final ProtoPersisterManager INSTANCE = new ProtoPersisterManager();
    }
}
