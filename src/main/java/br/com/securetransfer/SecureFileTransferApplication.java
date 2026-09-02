package br.com.securetransfer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
public class SecureFileTransferApplication {
    public static void main(String[] args) {
        SpringApplication.run(SecureFileTransferApplication.class, args);
    }
}