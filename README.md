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
- `infrastructure`: adapters AES-GCM, SHA-256, diretório local e armazenamento
  de objetos S3-compatible.
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
  type: local
  path: ./storage
  s3:
    endpoint:
    bucket:
    region: us-east-1
    metadata-prefix: metadata
    blob-prefix: blobs
    path-style-access: true
    multipart-threshold: 100MB
    multipart-part-size: 8MB
  git:
    remote:
    branch: main
    max-blob-size: 100MB
    # reject (padrão) ou lfs para blobs acima de max-blob-size
    large-blob-strategy: reject
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

Para publicar diretamente em um clone de Git/GitHub, use `storage.type=git` e
configure o remoto sem credenciais embutidas:

```yaml
storage:
  type: git
  path: ./github-storage
  git:
    remote: git@github.com:organizacao/repositorio-privado.git
    branch: main
    max-blob-size: 100MB
    large-blob-strategy: lfs
```

O remoto pode ser HTTPS com um credential helper do Git ou SSH com uma chave
carregada no agente. A aplicação desativa prompts interativos, nunca recebe
tokens como argumento e não registra a saída do Git. Se `storage.path` ainda não
existir, o adapter clona o remoto; depois, cada publicação sincroniza, envia os
chunks em commits individuais e envia o manifest no último commit. Listar,
verificar e baixar executam `git pull --ff-only` antes de ler os arquivos.

### Blobs maiores que o limite do GitHub

O GitHub mantém um limite de aproximadamente 100 MB para cada blob Git. Para
transferências que precisam de chunks maiores que esse limite, a estratégia
suportada é **Git LFS**:

1. Instale o Git LFS (`git lfs install`) nos dois computadores.
2. Configure `storage.git.large-blob-strategy: lfs`.
3. Ajuste `transfer.max-file-size` e `transfer.chunk-size` conforme o tamanho
   desejado; `storage.git.max-blob-size` deixa de bloquear chunks nesse modo.

O adapter adiciona `transfers/**/*.bin` ao `.gitattributes`. O histórico Git
guarda apenas ponteiros LFS; o conteúdo dos chunks é enviado ao armazenamento
LFS do mesmo remoto e continua sendo lido pela mesma `TransferRepositoryPort`.
O manifest ainda é publicado por último, e a validação existente de tamanho,
SHA-256 do chunk, autenticação AES-GCM, tamanho final e SHA-256 do ZIP não muda.
O servidor Git precisa oferecer Git LFS e a mesma credencial configurada no Git
é usada para os objetos LFS. A aplicação não recebe nem grava tokens, senhas ou
o segredo `TRANSFER_SECRET` em configuração, manifests ou histórico.

O modo `reject` mantém o comportamento conservador: qualquer chunk acima de
`storage.git.max-blob-size` é recusado antes do commit. Ele deve ser usado
quando o remoto não oferece Git LFS.

### Teste de contrato Git LFS hospedado

Os testes locais usam um repositório bare e não validam a autenticação nem o
armazenamento LFS do provedor. O teste de contrato hospedado é opt-in, publica
um chunk padrão de 100 MiB + 1 byte, confirma que o histórico contém apenas o
ponteiro LFS e valida listagem e download em um segundo clone:

```bash
export SECURE_TRANSFER_GIT_LFS_CONTRACT_TEST=true
export SECURE_TRANSFER_GIT_LFS_TEST_REMOTE='git@github.com:organizacao/repositorio-lfs-de-teste.git'
# Opcional: branch diferente de main ou um tamanho maior que 100 MiB.
export SECURE_TRANSFER_GIT_LFS_TEST_BRANCH=main
./mvnw -Dtest=GitHubTransferRepositoryAdapterTest test
```

Use um repositório descartável/dedicado com Git LFS habilitado e configure a
credencial pelo SSH agent ou por um credential helper do Git antes de executar.
O remoto não pode conter usuário, senha ou token; o teste não recebe credenciais
por argumento, não imprime a URL nem a saída do provedor e fica ignorado quando
`SECURE_TRANSFER_GIT_LFS_CONTRACT_TEST` não é `true`. O branch, o clone
temporário e os arquivos baixados são descartáveis; a transferência criada
permanece no remoto para permitir a inspeção do contrato.

### Armazenamento de objetos sem Git LFS

Para usar um bucket S3 ou compatível com S3 sem colocar blobs no histórico Git,
configure `storage.type=object`:

```yaml
storage:
  type: object
  s3:
    endpoint: https://s3.example.com       # opcional na AWS
    bucket: transferencias
    region: us-east-1
    metadata-prefix: metadata
    blob-prefix: blobs
    path-style-access: true                # útil para MinIO e endpoints locais
```

O adapter usa a cadeia padrão de credenciais do AWS SDK: variáveis de ambiente
do provedor, role da máquina/workload ou profile configurado no ambiente. Não há
campos de access key ou secret key na configuração. Nunca coloque credenciais na
URL do endpoint, no repositório ou no manifest.

Os manifests ficam em `metadata/<UUID>/manifest.json` e os chunks criptografados
em `blobs/<UUID>/chunks/<parte>.bin`. O manifest só é criado depois que todos os
chunks foram enviados; por isso, transferências incompletas não aparecem na
listagem. Downloads continuam validando tamanho e SHA-256 dos chunks, preservam
chunks válidos para retomada e só entregam o ZIP depois da validação final.
`storage.s3.metadata-prefix` e `storage.s3.blob-prefix` devem ser diferentes.
Chunks maiores que `storage.s3.multipart-threshold` usam o upload multipart do
S3, com partes de `storage.s3.multipart-part-size` lidas diretamente do arquivo
sem carregar o chunk inteiro em memória. O tamanho padrão da parte é 8 MiB e
deve ser de pelo menos 5 MiB para atender ao contrato do S3. Se uma parte falhar,
a sessão multipart é abortada e o manifest continua sem ser publicado.
Se uma publicação falhar antes do envio do manifest, o adapter remove
explicitamente os chunks parciais. A limpeza verifica a ausência do manifest
antes de cada remoção e para sem alterar os blobs quando o manifest já existe;
falhas durante o próprio envio do manifest não acionam essa limpeza, pois o
resultado da operação remota pode ser indeterminado. O total removido e as
interrupções da limpeza são registrados no log da aplicação.

### Teste de contrato S3-compatible hospedado

O teste de contrato usa o AWS SDK contra um bucket real da AWS, MinIO ou outro
serviço compatível. Ele é opt-in, publica um chunk e o manifest, confirma que o
manifest só aparece depois do chunk, lista a transferência, baixa o conteúdo por
streaming, valida o tamanho e verifica que os objetos usam prefixes separados.
Cada execução usa um prefixo isolado e remove os dois objetos ao terminar.

Configure um bucket de teste descartável e forneça as credenciais pela cadeia
padrão do AWS SDK (variáveis de ambiente do provedor, role ou profile), sem
colocar valores de credenciais no código, na URL ou nos argumentos:

```bash
export SECURE_TRANSFER_S3_CONTRACT_TEST=true
export SECURE_TRANSFER_S3_TEST_BUCKET=transferencias-de-teste
# Opcional para MinIO ou outro endpoint compatível:
export SECURE_TRANSFER_S3_TEST_ENDPOINT=http://localhost:9000
export SECURE_TRANSFER_S3_TEST_REGION=us-east-1
export SECURE_TRANSFER_S3_TEST_PATH_STYLE_ACCESS=true
./mvnw -Dtest=S3TransferRepositoryAdapterTest test
```

`SECURE_TRANSFER_S3_TEST_ENDPOINT` pode ficar vazio para AWS S3. Os prefixes
podem ser ajustados com `SECURE_TRANSFER_S3_TEST_METADATA_PREFIX` e
`SECURE_TRANSFER_S3_TEST_BLOB_PREFIX`; ambos devem apontar para namespaces
diferentes e o bucket deve permitir `PutObject`, `GetObject`, `HeadObject`,
`ListBucket` e `DeleteObject`. Sem a flag de habilitação, o teste é ignorado.

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

O limite padrão de cada blob é 100 MB, alinhado ao limite de arquivos do GitHub.
No modo `reject`, chunks maiores são rejeitados antes do commit. No modo `lfs`,
eles são enviados como objetos Git LFS e não entram como blobs no histórico Git.
Rejeições remotas por limite, conflitos de push/pull e falhas de autenticação são
convertidas em mensagens operacionais sem reproduzir a saída do Git. Reduza
`git.max-blob-size` se o servidor Git tiver um limite menor e não oferecer LFS.
Não versione o segredo, `work/` ou arquivos originais.

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
- **falha de GitHub**: confirme que o remoto está correto e que o SSH agent ou
  credential helper do Git tem acesso ao repositório privado.
- **falha de armazenamento de objetos**: confirme bucket, região, endpoint e as
  permissões de leitura/listagem e gravação da credencial fornecida ao SDK.
- **conflito no Git**: faça `git pull --ff-only` no clone, resolva alterações
  locais e tente a operação novamente.

## Como criar novo adapter de storage

Implemente `TransferRepositoryPort` sem alterar os serviços de aplicação:

```text
publishChunk
publishManifest
synchronize
downloadChunk
downloadManifest
listTransfers
exists
```

O `S3TransferRepositoryAdapter` incluído é compatível com AWS S3 e serviços
S3-compatible, como MinIO. Ele mantém a regra de publicar o manifest por último,
usa streaming, valida nomes de chunks e nunca armazena credenciais ou o segredo
de transferência.

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