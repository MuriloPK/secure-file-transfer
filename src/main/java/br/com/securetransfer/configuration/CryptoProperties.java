package br.com.securetransfer.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "crypto")
public class CryptoProperties {
    private String secretEnv = "TRANSFER_SECRET";

    public String getSecretEnv() {
        return secretEnv;
    }

    public void setSecretEnv(String secretEnv) {
        this.secretEnv = secretEnv;
    }
}