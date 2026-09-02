package br.com.securetransfer.ports.out;

import java.io.IOException;
import java.io.InputStream;

public interface HashPort {
    String sha256(InputStream input) throws IOException;
}