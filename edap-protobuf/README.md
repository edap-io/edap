## edap-protobuf

edap-protobuf 为protocol buffer协议的实现框架，可以直接序列化反序列化Javabean。

1. 添加以下依赖：

    ```xml
    <dependency>
        <groupId>io.edap</groupId>
        <artifactId>edap-protobuf-wire</artifactId>
        <version>0.1-SNAPSHOT</version>
    </dependency>
    ```

2. 使用方法：

    ```java
    
    public class User {
       private Long uid;
       private String username;
       public Long getUid() {
        return uid;
       }
       
       public void setUid(Long uid) {
           this.uid = uid;
       }
       
       public String getUsername() {
           return username;
       }
    
       public void setUsername(String username) {
           this.username = username; 
       }
    }
 
    User user = new User();
    user.setUid(10000L);
    user.setUsername("louis");
 
    //将Javabean序列化为protobuf的二进制数据
    byte[] bs = ProtoBuf.toByteArray(user);
    
    //将protobuf协议的数据反序列化为java对象
    User userInfo = ProtoBuf.toObject(byte[] bs, User.class);
    ```
    
3. 如果Field没有添加ProtoField的注解，框架将使用Javabean的class中Field的声明顺序作为protobuf的tag，从1开始进行编码。