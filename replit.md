# Secure File Transfer

CLI Java 21 que publica e baixa ZIPs em chunks AES-256-GCM com validação SHA-256 e retomada.

## Run & Operate

- `./mvnw clean verify` — compila e executa todos os testes
- `java -jar target/secure-file-transfer-1.0.0.jar` — abre a CLI
- Required env for transfer operations: `TRANSFER_SECRET`

## Stack

- Java 21, Spring Boot 3.4, Maven
- Jackson, Bean Validation, JUnit 5, AssertJ
- Sem banco de dados

## Where things live

- Código principal: `src/main/java/br/com/securetransfer`
- Configuração: `src/main/resources/application.yml`
- Testes: `src/test/java/br/com/securetransfer`
- Guia operacional: `README.md`

## Architecture decisions

- O manifest é sempre publicado por último.
- Downloads incompletos mantêm chunks validados para retomada.
- O arquivo final só é movido após SHA-256 e tamanho conferirem.
- Storage é uma porta; a primeira implementação usa diretório local/compartilhado.

## Product

Publica, lista, verifica e baixa arquivos ZIP de até 200 MB usando chunks configuráveis, AES-GCM e SHA-256.

## User preferences

_Populate as you build — explicit user instructions worth remembering across sessions._

## Gotchas

- Os dois computadores precisam usar o mesmo `TRANSFER_SECRET`.
- O diretório de destino deve existir e o arquivo final não pode existir.

## Pointers

- See the `pnpm-workspace` skill for workspace structure, TypeScript setup, and package details
