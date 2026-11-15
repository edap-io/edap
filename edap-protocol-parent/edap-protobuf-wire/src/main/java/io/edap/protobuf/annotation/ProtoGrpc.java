package io.edap.protobuf.annotation;

import java.lang.annotation.*;

/**
 * 标记 proto 服务方法可经 gRPC 协议调用，并携带 gRPC 运行时所需的元信息。
 *
 * <p>由 proto 代码生成器（{@code JavaBuilder}）为每个 rpc 方法自动生成。
 *
 * <p><b>为什么必须显式携带请求/响应 DTO 类型</b>：流式方法的 Java 签名为
 * {@code StreamObserver<Resp> method(StreamObserver<Req>)}，泛型在 JVM descriptor 中已被擦除，
 * 运行时只能看到 {@code io.edap.rpc.StreamObserver}，拿不到真实的 DTO 类型。
 * 而 gRPC 需要 DTO 类型才能完成 protobuf 的编解码，因此这里是唯一的类型载体。
 */
@Target({ ElementType.METHOD })
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Documented
public @interface ProtoGrpc {

    /**
     * 方法的调用类型。
     */
    MethodType type() default MethodType.UNARY;

    /**
     * 请求消息的 DTO 类型。
     */
    Class<?> req();

    /**
     * 响应消息的 DTO 类型。
     */
    Class<?> resp();

    /**
     * gRPC 的 {@code :path}，形如 {@code /pkg.Svc/Method}。
     *
     * <p>由代码生成器填入 proto 中的原始方法名（不是生成的 Java 方法名——Java 方法名会把
     * 首字母改成小写，无法从 Java 侧可靠还原 proto 的方法名）。
     *
     * <p>为空串表示由容器按 {@code @ProtoService.serviceName()} 与方法名推导。
     */
    String path() default "";

    /**
     * gRPC 的四种调用类型。
     */
    enum MethodType {
        /**
         * 一元调用：一个请求消息，一个响应消息。
         */
        UNARY,
        /**
         * 服务端流式：一个请求消息，多个响应消息。
         */
        SERVER_STREAM,
        /**
         * 客户端流式：多个请求消息，一个响应消息。
         */
        CLIENT_STREAM,
        /**
         * 双向流式：多个请求消息，多个响应消息。
         */
        BIDIRECTIONAL
    }
}
