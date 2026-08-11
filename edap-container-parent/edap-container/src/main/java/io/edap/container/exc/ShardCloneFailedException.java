package io.edap.container.exc;

public class ShardCloneFailedException extends RuntimeException {

    public ShardCloneFailedException(Class<?> beanClass, int shardIdx, Throwable cause) {
        super("Failed to clone shard #" + shardIdx + " for " + beanClass.getName(), cause);
    }
}