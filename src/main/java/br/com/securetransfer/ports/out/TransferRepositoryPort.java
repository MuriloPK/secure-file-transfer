package br.com.securetransfer.ports.out;

import br.com.securetransfer.domain.model.TransferChunk;
import br.com.securetransfer.domain.model.TransferId;
import br.com.securetransfer.domain.model.TransferManifest;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;

public interface TransferRepositoryPort {
    default void synchronize() throws IOException {
    }

    void publishChunk(TransferId transferId, TransferChunk chunk, Path source) throws IOException;

    void publishManifest(TransferManifest manifest) throws IOException;

    InputStream downloadChunk(TransferId transferId, TransferChunk chunk) throws IOException;

    TransferManifest downloadManifest(TransferId transferId) throws IOException;

    List<TransferManifest> listTransfers() throws IOException;

    boolean exists(TransferId transferId);
}