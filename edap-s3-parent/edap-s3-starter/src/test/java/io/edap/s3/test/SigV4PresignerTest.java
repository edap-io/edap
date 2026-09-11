package io.edap.s3.test;

import io.edap.s3.auth.AccessKeyResolver;
import io.edap.s3.auth.ConfigAccessKeyResolver;
import io.edap.s3.auth.SigV4Presigner;
import io.edap.s3.auth.SigV4Verifier;

import java.time.Duration;
import java.util.Properties;

public class SigV4PresignerTest {

    public static void main(String[] args) throws Exception {

        String AKID = "AKIDEXAMPLE";
        String SECRET = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY";
        String REGION = "us-east-1";
        String HOST = "localhost";
        int PORT =8080;

        Properties p = new Properties();
        p.setProperty("s3.accessKey." + AKID + ".secret", SECRET);
        p.setProperty("s3.accessKey." + AKID + ".region", REGION);
        AccessKeyResolver resolver = ConfigAccessKeyResolver.fromProperties(p);
        SigV4Presigner presigner = new SigV4Presigner();
        SigV4Verifier verifier = new SigV4Verifier(resolver);

        SigV4Presigner.PresignedUrl u = presigner.presignPutObject(
                AKID, SECRET, REGION, "my-bucket", "photos/cat.jpg",
                HOST, PORT, Duration.ofMinutes(15));

        System.out.println(u.url());
        System.out.println(u.queryParams());
    }
}
