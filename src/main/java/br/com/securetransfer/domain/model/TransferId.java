package br.com.securetransfer.domain.model;

import java.util.UUID;

public record TransferId(UUID value) {
    public TransferId {
        if (value == null) {
            throw new IllegalArgumentException("transferId não pode ser nulo");
        }
    }

    public static TransferId newId() {
        return new TransferId(UUID.randomUUID());
    }

    public static TransferId parse(String value) {
        try {
            return new TransferId(UUID.fromString(value));
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new IllegalArgumentException("transferId inválido: " + value, exception);
        }
    }

    @Override
    public String toString() {
        return value.toString();
    }
}