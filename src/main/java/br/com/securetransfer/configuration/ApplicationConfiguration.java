package br.com.securetransfer.configuration;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({
        TransferProperties.class,
        StorageProperties.class,
        WorkProperties.class,
        CryptoProperties.class
})
public class ApplicationConfiguration {
}