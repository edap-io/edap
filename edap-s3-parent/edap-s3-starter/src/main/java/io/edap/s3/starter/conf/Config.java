package io.edap.s3.starter.conf;

import io.edap.microservice.annotation.Bean;
import io.edap.microservice.annotation.Configuration;
import io.edap.s3.starter.S3UrlMapping;

@Configuration
public class Config {

    @Bean
    public S3UrlMapping s3UrlMapping() {
        S3UrlMapping s3UrlMapping = new S3UrlMapping();

        return s3UrlMapping;
    }
}
