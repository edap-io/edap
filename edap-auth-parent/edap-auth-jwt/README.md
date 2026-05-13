### edap生成和验证JWT的工具

#### 创建JWT的token

```java
JwtBuilder builder = JWT.create();
builder.withIssuer("edap");
builder.withSubject("edap");
```
