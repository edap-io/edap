package io.edap.container.exc;

/**
 * 资源不存在（ResourceLoader.getBytes / getString 严格语义下抛出）。
 *
 * <p>与 {@link NoSuchBeanException} 一致——都是"按名找不到"，都继承
 * {@link RuntimeException}，不污染上层方法签名。</p>
 */
public class NoSuchResourceException extends RuntimeException {

    public NoSuchResourceException(String name) {
        super("Resource not found in app classpath: " + name);
    }
}
