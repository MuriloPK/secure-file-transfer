# Secure File Transfer

Aplicação CLI em Java 21 para transportar arquivos ZIP de até 200 MB entre dois
computadores por meio de um diretório intermediário autorizado. O arquivo é
processado por streaming, dividido em chunks, criptografado individualmente com
AES-256-GCM e só é entregue no destino depois da validação integral do SHA-256.

## Objetivo

Oferecer um fluxo simples de **publicar/listar/baixar/verificar** sem banco de
dados e sem armazenar a chave no repositório. O mesmo executável é usado no PC-A
e no PC-B.

## Arquitetura

O projeto segue ports and adapters:

- `domain`: modelos imutáveis, estados e exceções.
- `application`: casos de uso, chunking, validação, retomada e temporários.
- `ports`: contratos de criptografia, hash e storage.
- `infrastructure`: adapters AES-GCM, SHA-256 e diretório local.
- `presentation`: CLI interativa e comandos não interativos.
- `configuration`: propriedades tipadas do Spring Boot.

```text
PC-A                                           PC-B
ZIP                                             manifest.json
 |                                                   |
 v                                                   v
Validate -> SHA-256 -> Split -> AES-GCM -> STORAGE <- Download
                                                  |
                       Original ZIP <- SHA-256 <- Assemble <- AES-GCM
```

## Tecnologias

- Java 21
- Spring Boot 3.4
- Maven
- Jackson
- Bean Validation
- JUnit 5 e AssertJ

Não há banco de dados.

## Requisitos

- JDK 21
- Maven 3.8+
- Um diretório de storage que ambos os computadores possam acessar
- O mesmo valor de `TRANSFER_SECRET` nos dois computadores

## Como compilar

```bash
chmod +x mvnw
./mvnw clean verify
```

O JAR executável é criado em:

```text
target/secure-file-transfer-1.0.0.jar
```

## Como executar

Defina o segredo fora do código:

Linux/macOS:

```bash
export TRANSFER_SECRET='use-um-segredo-longo-e-aleatorio'
java -jar target/secure-file-transfer-1.0.0.jar
```

PowerShell:

```powershell
$env:TRANSFER_SECRET = "use-um-segredo-longo-e-aleatorio"
java -jar target/secure-file-transfer-1.0.0.jar
```

Também é possível executar comandos diretos:

```bash
java -jar target/secure-file-transfer-1.0.0.jar publish /caminho/arquivo.zip
java -jar target/secure-file-transfer-1.0.0.jar list
java -jar target/secure-file-transfer-1.0.0.jar verify UUID-DA-TRANSFERENCIA
java -jar target/secure-file-transfer-1.0.0.jar download UUID-DA-TRANSFERENCIA ./downloads
```

## Configuração

`src/main/resources/application.yml`:

```yaml
transfer:
  max-file-size: 200MB
  chunk-size: 5MB
storage:
  path: ./storage
work:
  path: ./work
crypto:
  secret-env: TRANSFER_SECRET
```

Propriedades podem ser sobrescritas por argumentos Spring:

```bash
java -jar target/secure-file-transfer-1.0.0.jar \
  --storage.path=/mnt/transferencias \
  --transfer.chunk-size=5MB
```

## Variáveis de ambiente

| Variável | Obrigatória | Descrição |
|---|---:|---|
| `TRANSFER_SECRET` | Sim, para publicar/baixar | Segredo compartilhado usado para derivar a chave AES-256 |

O valor não é gravado no manifest, logs ou storage.

## Estrutura de diretórios

```text
storage/
└── transfers/
    └── <UUID>/
        ├── manifest.json
        └── chunks/
            ├── part-00001.bin
            └── ...
work/
├── upload/
└── download/
```

O `manifest.json` é publicado por último. Portanto, uma pasta incompleta não é
listada como transferência disponível.

## Como publicar arquivo

Escolha `1 - Publicar arquivo` na CLI ou use:

```bash
java -jar target/secure-file-transfer-1.0.0.jar publish arquivo.zip
```

O comando valida extensão e limite, calcula o SHA-256 original, criptografa cada
chunk com nonce exclusivo, publica os chunks e, por último, o manifest.

## Como listar transferências

Escolha `2 - Transferências disponíveis` ou use:

```bash
java -jar target/secure-file-transfer-1.0.0.jar list
```

Somente manifests legíveis e estruturalmente válidos são exibidos.

## Como baixar arquivo

Crie o diretório de destino e execute:

```bash
mkdir -p downloads
java -jar target/secure-file-transfer-1.0.0.jar download <UUID> ./downloads
```

O arquivo final não é sobrescrito. A aplicação monta um `.download` temporário e
faz a movimentação final apenas depois da validação.

## Como funciona a criptografia

Cada chunk usa `AES/GCM/NoPadding` com chave de 256 bits e nonce aleatório de 12
bytes. O GCM fornece confidencialidade e autenticação: senha errada, tag alterada
ou qualquer byte adulterado causam falha. O segredo deve ser longo, aleatório e
distribuído por um canal separado.

## Como funciona a integridade

- SHA-256 do ZIP original no manifest.
- SHA-256 individual de cada chunk criptografado.
- Autenticação GCM na descriptografia.
- Comparação do tamanho e SHA-256 do arquivo remontado.
- Entrega final somente após todas as validações.

## Como funciona a retomada

Chunks baixados ficam em `work/download/<UUID>` enquanto a transferência estiver
incompleta. Em uma nova execução, cada chunk existente é validado por tamanho e
SHA-256. Somente chunks ausentes ou inválidos são baixados novamente.

## Como testar dois computadores

1. Configure o mesmo storage compartilhado nos dois computadores.
2. Configure o mesmo `TRANSFER_SECRET` por um canal seguro.
3. No PC-A, publique e anote o UUID.
4. No PC-B, liste e baixe usando o UUID.
5. Compare:

```bash
sha256sum arquivo-original.zip arquivo-baixado.zip
```

Os hashes devem ser idênticos.

### Usando um repositório Git/GitHub autorizado

A implementação inicial é deliberadamente desacoplada de GitHub. Para uma
simulação controlada, `storage.path` pode apontar para uma pasta dentro de um
clone privado autorizado; o operador faz `git pull` antes de listar/baixar e
`git add/commit/push` depois de publicar. Não versione o segredo, `work/` ou
arquivos originais. Para uso frequente, prefira Git LFS ou um adapter dedicado
de S3/Azure/MinIO, evitando commits grandes no Git comum.

## Testes

```bash
./mvnw test
./mvnw clean verify
```

Há testes de criptografia/autenticação, validação de manifest e integração
publicar → storage → baixar → comparação byte a byte e SHA-256.

## Segurança

- Não contém mecanismos de ocultação, bypass de DLP/firewall/EDR ou logs falsos.
- Não registra conteúdo, segredo ou chave.
- Rejeita nomes com traversal e normaliza caminhos.
- Não confia no manifest apenas por ele existir.
- Não carrega o arquivo inteiro em memória; o processamento é por streaming.
- Não reutiliza nonce entre chunks.
- Não entrega arquivos parciais.

## Troubleshooting

- **`TRANSFER_SECRET não configurada`**: defina a variável nos dois PCs.
- **`falha de autenticação ou senha incorreta`**: confirme que o segredo é igual.
- **`arquivo de destino já existe`**: remova ou renomeie o arquivo existente.
- **`manifest inválido`**: não processe; restaure uma cópia legítima.
- **`chunk corrompido`**: restaure o chunk no storage; a retomada tentará somente
  a parte inválida.
- **storage compartilhado indisponível**: confira montagem, permissões e
  `--storage.path`.

## Como criar novo adapter de storage

Implemente `TransferRepositoryPort` sem alterar os serviços de aplicação:

```text
publishChunk
publishManifest
downloadChunk
downloadManifest
listTransfers
exists
```

O novo adapter deve manter a regra de publicar o manifest por último, usar
streaming, validar caminhos e nunca armazenar o segredo. Exemplos futuros:
`S3TransferRepositoryAdapter`, `AzureBlobTransferRepositoryAdapter`,
`MinioTransferRepositoryAdapter`, `GitLfsTransferRepositoryAdapter`.

## Estrutura do projeto

```text
src/main/java/br/com/securetransfer/
├── application/service/
├── configuration/
├── domain/{exception,model}/
├── infrastructure/{crypto,hash,repository}/
├── ports/out/
└── presentation/cli/
```

## Commits recomendados

1. `feat: initialize secure file transfer project`
2. `feat: add transfer domain and validation`
3. `feat: add streaming SHA-256 and AES-GCM adapters`
4. `feat: add local transfer repository adapter`
5. `feat: implement publish and resumable download flows`
6. `feat: add interactive and command-line interface`
7. `test: add crypto, manifest and transfer integration tests`
8. `docs: add configuration and two-computer guide`