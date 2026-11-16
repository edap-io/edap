package io.edap.erpc.client;

/**
 * 测试用的业务 DTO —— 不带协议注解，靠 getter/setter 被 protobuf 推导。
 *
 * <p>★ <b>简单名要带前缀</b>：protobuf 的解码器类名是「包名 + 简单名 + Decoder」，
 * 同包下两个同名 DTO 会生成同一个解码器类 ⇒ 解码出别的 DTO 的实例。</p>
 */
public class ClientDemoDto {

    private String name;
    private int    value;

    public ClientDemoDto() {
    }

    public ClientDemoDto(String name, int value) {
        this.name = name;
        this.value = value;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getValue() {
        return value;
    }

    public void setValue(int value) {
        this.value = value;
    }

    @Override
    public String toString() {
        return "ClientDemoDto{name=" + name + ", value=" + value + "}";
    }
}
