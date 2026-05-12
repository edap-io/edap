package io.edap.auth.jwt;

import java.util.Map;

public class JwtPayload {

    private String issuer;
    private String subject;
    private String audience;
    private long expiresAt;
    private long notBefore;
    private long issuedAt;
    private String jwtId;
    private Map<String, Object> customerClaims;

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public String getAudience() {
        return audience;
    }

    public void setAudience(String audience) {
        this.audience = audience;
    }

    public long getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(long expiresAt) {
        this.expiresAt = expiresAt;
    }

    public long getNotBefore() {
        return notBefore;
    }

    public void setNotBefore(long notBefore) {
        this.notBefore = notBefore;
    }

    public long getIssuedAt() {
        return issuedAt;
    }

    public void setIssuedAt(long issuedAt) {
        this.issuedAt = issuedAt;
    }

    public String getJwtId() {
        return jwtId;
    }

    public void setJwtId(String jwtId) {
        this.jwtId = jwtId;
    }

    public Map<String, Object> getCustomerClaims() {
        return customerClaims;
    }

    public void setCustomerClaims(Map<String, Object> customerClaims) {
        this.customerClaims = customerClaims;
    }
}
