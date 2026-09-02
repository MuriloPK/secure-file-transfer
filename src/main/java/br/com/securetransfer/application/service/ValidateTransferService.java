package br.com.securetransfer.application.service;

import br.com.securetransfer.domain.model.TransferId;
import br.com.securetransfer.domain.model.TransferManifest;
import br.com.securetransfer.ports.out.TransferRepositoryPort;
import org.springframework.stereotype.Service;

import java.io.IOException;

@Service
public class ValidateTransferService {
    private final TransferRepositoryPort repository;
    private final TransferManifestValidator validator;

    public ValidateTransferService(TransferRepositoryPort repository, TransferManifestValidator validator) {
        this.repository = repository;
        this.validator = validator;
    }

    public TransferManifest validate(TransferId transferId) throws IOException {
        TransferManifest manifest = repository.downloadManifest(transferId);
        validator.validate(manifest);
        return manifest;
    }
}