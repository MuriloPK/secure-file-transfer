package br.com.securetransfer.application.service;

import br.com.securetransfer.domain.model.TransferManifest;
import br.com.securetransfer.ports.out.TransferRepositoryPort;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;

@Service
public class ListTransfersService {
    private final TransferRepositoryPort repository;
    private final TransferManifestValidator validator;

    public ListTransfersService(TransferRepositoryPort repository, TransferManifestValidator validator) {
        this.repository = repository;
        this.validator = validator;
    }

    public List<TransferManifest> list() throws IOException {
        return repository.listTransfers().stream().filter(manifest -> {
            try {
                validator.validate(manifest);
                return true;
            } catch (RuntimeException exception) {
                return false;
            }
        }).toList();
    }
}