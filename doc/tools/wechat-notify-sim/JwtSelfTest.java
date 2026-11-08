import io.edap.auth.jwt.JWT;
import io.edap.auth.jwt.VerifyResult;

/**
 * 用**真实的 edap-auth-jwt 实现**校验 gen_jwt.py 生成的 token 能不能被 verify 通过。
 *
 * 用法: java -cp <cp> JwtSelfTest <token> [signKey]
 */
public class JwtSelfTest {
    public static void main(String[] args) {
        String token = args[0];
        String signKey = args.length > 1 ? args[1] : "20a4d5e1-3c3d-4259-962f-78b2c07b2b06";

        VerifyResult vr = JWT.verify(token, signKey);
        System.out.println("verify code = " + vr.getCode() + "  (0 = 通过)");
        System.out.println("verify message = " + vr.getMessage());
        if (vr.getPayload() != null) {
            System.out.println("subject(sub) = " + vr.getPayload().getSubject());
            System.out.println("expiresAt(exp) = " + vr.getPayload().getExpiresAt());
            System.out.println("customerClaims = " + vr.getPayload().getCustomerClaims());
        }
    }
}
