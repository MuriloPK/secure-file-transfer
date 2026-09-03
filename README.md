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
# Disponível somente com storage.type=object ou storage.type=s3:
java -jar target/secure-file-transfer-1.0.0.jar --storage.type=object cleanup-orphaned-blobs
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

Para publicar diretamente em um clone de Git hospedado, use `storage.type=git` e
configure o remoto sem credenciais embutidas:

```yaml
storage:
  type: git
  path: ./git-storage
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

### Blobs maiores que o limite do servidor Git

Servidores Git hospedados podem ter limites diferentes para blobs Git comuns.
Para transferências que precisam de chunks maiores que o limite do servidor,
a estratégia suportada é **Git LFS**:

1. Instale o Git LFS (`git lfs install`) nos dois computadores.
2. Configure `storage.git.large-blob-strategy: lfs`.
3. Ajuste `transfer.max-file-size` e `transfer.chunk-size` conforme o tamanho
   desejado; `storage.git.max-blob-size` deixa de bloquear chunks nesse modo.

O adapter adiciona `transfers/**/*.bin` ao `.gitattributes`. O histórico Git
guarda apenas ponteiros LFS; o conteúdo dos chunks é enviado ao armazenamento
LFS do mesmo remoto e continua sendo lido pela mesma `TransferRepositoryPort`.
O manifest ainda é publicado por último, e a validação existente de tamanho,
SHA-256 do chunk, autenticação AES-GCM, tamanho final e SHA-256 do ZIP não muda.
O servidor Git precisa oferecer Git LFS. O cliente Git LFS descobre o endpoint
do provedor a partir do remoto Git e da configuração do próprio clone; não há
um endpoint específico de GitHub no adapter. Dependendo do provedor, Git e Git
LFS podem exigir credenciais ou escopos diferentes. Configure isso no SSH agent,
credential helper, configuração do Git ou mecanismo equivalente do provedor,
sempre fora do repositório. A aplicação não recebe nem grava tokens, senhas ou
o segredo `TRANSFER_SECRET` em configuração, manifests ou histórico.

O modo `reject` mantém o comportamento conservador: qualquer chunk acima de
`storage.git.max-blob-size` é recusado antes do commit. Ele deve ser usado
quando o remoto não oferece Git LFS.

### Teste de contrato Git LFS hospedado em provedores compatíveis

Os testes locais usam um repositório bare e não validam a autenticação nem o
armazenamento LFS do provedor. O mesmo teste de contrato hospedado pode apontar
para GitHub, GitLab, Gitea/Forgejo, Bitbucket ou outro servidor compatível sem
alterar o código de produção. Ele é opt-in, publica um chunk grande, confirma
que o histórico contém apenas o ponteiro LFS e valida autenticação, publicação
do ponteiro, publicação do manifest, listagem e download em um segundo clone.
Cada etapa falha com um nome próprio no resultado do teste:

```bash
export SECURE_TRANSFER_GIT_LFS_CONTRACT_TEST=true
export SECURE_TRANSFER_GIT_LFS_TEST_REMOTE='git@github.com:organizacao/repositorio-lfs-de-teste.git'
# Opcional: branch diferente de main ou tamanho mínimo diferente da capacidade
# do provedor. O padrão é 100 MiB + 1 byte.
export SECURE_TRANSFER_GIT_LFS_TEST_BRANCH=main
export SECURE_TRANSFER_GIT_LFS_TEST_MIN_CHUNK_BYTES=104857601
./mvnw -Dtest=GitHubTransferRepositoryAdapterTest test
```

Por exemplo, para executar o mesmo contrato em um GitLab, Gitea ou Forgejo
dedicado, altere somente o remoto e, se necessário, o tamanho mínimo:

```bash
export SECURE_TRANSFER_GIT_LFS_CONTRACT_TEST=true
export SECURE_TRANSFER_GIT_LFS_TEST_REMOTE='git@gitlab.com:organizacao/repositorio-lfs-de-teste.git'
export SECURE_TRANSFER_GIT_LFS_TEST_BRANCH=main
# Use um valor aceito pelo limite de LFS do provedor e acima do limite
# de blob Git que se deseja validar.
export SECURE_TRANSFER_GIT_LFS_TEST_MIN_CHUNK_BYTES=52428801
./mvnw -Dtest=GitHubTransferRepositoryAdapterTest test
```

Use um repositório descartável/dedicado com Git LFS habilitado e configure a
credencial e as permissões de Git LFS pelo SSH agent, credential helper ou
mecanismo específico do provedor antes de executar. Alguns provedores separam
o endpoint ou o escopo de autenticação do Git LFS; essa diferença é resolvida
na configuração do cliente Git, não por uma credencial no teste.
O remoto não pode conter usuário, senha ou token; o teste não recebe credenciais
por argumento, não imprime a URL nem a saída do provedor e fica ignorado quando
`SECURE_TRANSFER_GIT_LFS_CONTRACT_TEST` não é `true`. O branch, o clone
temporário e os arquivos baixados são descartáveis. Por padrão, depois da
listagem e do download, o contrato remove do branch somente o diretório
`transfers/<UUID>` que ele acabou de criar. Antes da remoção, o manifest é lido
e precisa conter exatamente o mesmo UUID; assim, a limpeza não varre nem altera
transferências de outros usuários. A remoção é publicada em um commit separado.
Se a limpeza falhar, o resultado das etapas principais continua visível no log e
o UUID e o caminho exato para a remoção manual são informados como aviso.

Para inspecionar a transferência no remoto antes de removê-la, configure a
variável protegida `SECURE_TRANSFER_GIT_LFS_TEST_RETAIN_TRANSFER=true` no
ambiente `git-lfs-contract`. O teste imprimirá o UUID criado e manterá a
transferência; após a inspeção, remova somente `transfers/<UUID>` nesse branch
descartável, faça commit e push. Para a execução agendada normal, mantenha a
variável ausente ou em `false`.

#### Retenção dos objetos LFS no GitHub

A limpeza do contrato remove e publica o diretório `transfers/<UUID>`, mas isso
remove somente os ponteiros LFS alcançáveis pelo branch. No GitHub, o objeto
LFS correspondente continua no armazenamento remoto e continua contando para a
cota de Git LFS; apagar o arquivo do histórico não inicia um garbage
collection. Essa é a política documentada pelo GitHub em
[Removing files from Git Large File Storage](https://docs.github.com/en/repositories/working-with-files/managing-large-files/removing-files-from-git-large-file-storage#git-lfs-objects-in-your-repository).

O teste local `cleanupRemovesLfsPointerButDoesNotPurgeRemoteLfsObject` mantém
essa fronteira explícita: o ponteiro desaparece do branch, enquanto o objeto
LFS ainda existe no armazenamento do remoto bare. O contrato hospedado também
inclui uma etapa de `cleanup publication` que sincroniza um clone novo e
confirma que o diretório não está mais listado. Essa etapa confirma a limpeza
do catálogo no provedor, não uma redução da cota LFS.

Para confirmar a liberação de armazenamento no GitHub, use somente o
repositório descartável do contrato:

1. Execute o workflow com `SECURE_TRANSFER_GIT_LFS_TEST_RETAIN_TRANSFER=false`
   e confirme no resumo que `cleanup publication` removeu o diretório.
2. Registre a métrica de armazenamento LFS exibida pelo GitHub para esse
   repositório. Ela pode continuar incluindo o objeto mesmo sem nenhum
   ponteiro no branch.
3. Para purgar o objeto, apague e recrie o repositório descartável e confira a
   métrica novamente após o GitHub processar a remoção. Se o repositório não
   puder ser recriado, solicite ao suporte do GitHub o purge do objeto
   órfão. A exclusão do repositório também remove issues, estrelas e forks,
   por isso essa verificação nunca deve usar um repositório de produção.

Não há uma opção de produção nem um token de exclusão no workflow para
automatizar essa ação destrutiva. O remoto, as credenciais e a branch usados
para o contrato permanecem configurados somente no ambiente protegido
`git-lfs-contract`.

#### Execução agendada contra o segundo provedor (GitLab HTTPS)

O workflow `Git LFS hosted contract` (`.github/workflows/git-lfs-contract.yml`)
executa esse contrato manualmente ou em dias úteis, usando exclusivamente HTTPS
contra um repositório GitLab dedicado. O job usa o ambiente protegido
`git-lfs-contract`; esse ambiente deve apontar para o segundo provedor,
não para o repositório que hospeda o código. O remoto e a autenticação nunca
ficam no repositório:

| Tipo                 | Nome                                           | Conteúdo                                                                                       |
| -------------------- | ---------------------------------------------- | ---------------------------------------------------------------------------------------------- |
| Variável do ambiente | `SECURE_TRANSFER_GIT_LFS_TEST_PROVIDER`       | `gitlab`; impede que este job seja apontado por engano para outro provedor                    |
| Secret do ambiente   | `SECURE_TRANSFER_GIT_LFS_TEST_REMOTE`          | URL `https://gitlab.com/grupo/repositorio.git`, sem usuário, senha ou token                   |
| Modo do workflow     | `SECURE_TRANSFER_GIT_LFS_TEST_AUTH_MODE`       | fixo em `https`; o job falha se o contrato não estiver em HTTPS                              |
| Secret do ambiente   | `GIT_LFS_CONTRACT_HTTPS_TOKEN`                 | token para o remoto HTTPS, consumido por um credential helper temporário                       |
| Variável do ambiente | `GIT_LFS_CONTRACT_HTTPS_USERNAME`              | `oauth2` para PAT; para token de projeto, o usuário gerado pelo GitLab                        |
| Variável do ambiente | `SECURE_TRANSFER_GIT_LFS_TEST_BRANCH`          | branch descartável usada pelo contrato                                                         |
| Variável do ambiente | `SECURE_TRANSFER_GIT_LFS_TEST_MIN_CHUNK_BYTES` | menor chunk que deve ser aceito pelo armazenamento LFS                                         |
| Variável do ambiente | `SECURE_TRANSFER_GIT_LFS_TEST_CHUNK_BYTES`     | tamanho realmente exercitado; deve ser maior ou igual ao mínimo                                |
| Variável do ambiente | `SECURE_TRANSFER_GIT_LFS_TEST_RETAIN_TRANSFER` | `true` mantém a transferência para inspeção; `false` (padrão) remove-a após o download          |

Configure esses valores somente no ambiente protegido
`git-lfs-contract`, habilite a aprovação necessária para ele e dê ao
workflow acesso ao ambiente. O remoto deve ser dedicado/descartável, ter Git
LFS habilitado e usar `https://gitlab.com/...`, sempre sem usuário, senha ou
token embutido na URL. O workflow grava o token e o usuário somente em arquivos
temporários protegidos, e o helper os fornece ao Git/Git LFS sem colocar o token
na URL, nos logs ou no resumo. A execução é serializada para que duas execuções
não publiquem no mesmo repositório ao mesmo tempo.

O resumo de cada execução registra o resultado (`success` ou `failure`), a
branch, o modo de autenticação, o tamanho mínimo validado, o tamanho exercitado
e as seis etapas: autenticação, publicação do ponteiro LFS, publicação do
manifest, listagem, download e publicação da limpeza. Uma falha ao publicar a
limpeza é fatal: o teste termina com `failure`, em vez de registrar somente um
aviso, e o log informa o UUID e o caminho exato `transfers/<UUID>` para a
remoção manual. Se uma etapa principal e a limpeza falharem na mesma execução,
a falha da etapa principal permanece como resultado principal e a falha de
limpeza aparece como diagnóstico suplementar. Assim, o maior valor configurado
que passou é o limite observado para essa execução; aumente
`SECURE_TRANSFER_GIT_LFS_TEST_CHUNK_BYTES` em uma execução posterior para
verificar uma capacidade maior. O remoto e o token permanecem redigidos no
resumo e nos erros do adapter. Uma falha de autenticação não deve ser
interpretada como falha de capacidade: o nome da etapa e a mensagem sanitizada
distinguem as duas situações.

Para a execução protegida que confirma o contrato HTTPS no GitLab, use um remoto
`https://gitlab.com/...` sem qualquer `usuario:senha@`, configure
`GIT_LFS_CONTRACT_HTTPS_TOKEN` como secret do ambiente e mantenha
`SECURE_TRANSFER_GIT_LFS_TEST_PROVIDER=gitlab` (o padrão do job). O modo HTTPS é
fixo no workflow para evitar que uma execução de confirmação caia
silenciosamente no caminho SSH. O valor de
`GIT_LFS_CONTRACT_HTTPS_USERNAME` é enviado como o usuário do Basic Auth e o
token é enviado como a senha, sempre por um helper temporário com permissão
restrita.

Requisitos comuns dos provedores para esse modo:

| Provedor | `GIT_LFS_CONTRACT_HTTPS_USERNAME` | Token e permissões mínimas |
| -------- | -------------------------------- | -------------------------- |
| GitHub  | login do usuário que criou o token | PAT clássico com `repo`, ou PAT fine-grained com acesso ao repositório e `Contents: Read and write`; Git LFS precisa estar habilitado |
| GitLab (segundo provedor validado pelo workflow) | `oauth2` para PAT; para project access token, o usuário gerado pelo GitLab | PAT ou project access token com `read_repository` e `write_repository` no projeto; Git LFS precisa estar habilitado |
| Gitea/Forgejo | nome do usuário ou o usuário indicado pelo tipo de token | token com leitura e escrita no repositório e Git LFS habilitado |
| Bitbucket Cloud | nome de usuário do workspace, não o e-mail | app password com `Repositories: Read` e `Repositories: Write`; confirme que Git LFS está habilitado |

Os nomes e escopos acima devem corresponder ao tipo de token escolhido no
provedor. Em particular, um token que só permite leitura autentica o clone,
mas falha na publicação do ponteiro ou do objeto LFS; o contrato só é
considerado aprovado quando conclui autenticação, publicação do ponteiro,
publicação do manifest, listagem e download. Se o provedor usar outro formato
de usuário ou escopo, registre a exceção nesta tabela e mantenha o token
somente no secret protegido.

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
    multipart-resume-enabled: true         # reutiliza partes multipart confirmadas
    orphan-retention: 24h                  # retenção antes da varredura de órfãos
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
a sessão multipart válida fica disponível para uma nova tentativa, que lista e
reutiliza as partes confirmadas. Partes ausentes ou com tamanho/ETag inválido
são reenviadas; quando o tamanho revela uma mudança de layout, a retomada
recomeça na primeira parte divergente para não reutilizar offsets incorretos.
Use `storage.s3.multipart-resume-enabled: false` para desativar
a descoberta de sessões anteriores. Sessões expiradas ou incompatíveis são
abortadas e recriadas, e o manifest continua sem ser publicado enquanto o chunk
não estiver completo.
Se uma publicação falhar antes do envio do manifest, o adapter remove
explicitamente os chunks parciais. A limpeza verifica a ausência do manifest
antes de cada remoção e para sem alterar os blobs quando o manifest já existe;
falhas durante o próprio envio do manifest não acionam essa limpeza, pois o
resultado da operação remota pode ser indeterminado. O total removido e as
interrupções da limpeza são registrados no log da aplicação.

Para recuperar blobs deixados por um encerramento abrupto, o
`S3TransferRepositoryAdapter` também oferece a varredura explícita
`cleanupOrphanedBlobs()`, disponível na CLI pelo comando
`cleanup-orphaned-blobs`. O comando existe somente quando
`storage.type` está configurado como `object` ou `s3`; em outros storages ele
não é oferecido. A operação lista apenas chunks com formato de transferência
válido, considera órfãos os objetos cuja última alteração é anterior a
`storage.s3.orphan-retention` e verifica o manifest imediatamente antes de cada
remoção. Transferências que já ficaram disponíveis são preservadas, e a rotina
pode ser executada novamente com segurança. O padrão é `24h`; defina um período
maior que o tempo máximo esperado para uma publicação. A operação registra
candidatos, remoções, objetos preservados e falhas, e o comando exibe essas
quatro contagens. Uma transferência que já possui manifest nunca é removida,
mesmo que seus chunks sejam antigos ou que a limpeza seja executada novamente.

### Teste de contrato S3-compatible hospedado

O teste de contrato usa o AWS SDK contra um bucket real da AWS, MinIO ou outro
serviço compatível. Ele é opt-in, publica um chunk e o manifest, confirma que o
manifest só aparece depois do chunk, lista a transferência, baixa o conteúdo por
streaming, valida o tamanho e verifica que os objetos usam prefixes separados.
Ele também cria um chunk antigo sem manifest, executa
`cleanup-orphaned-blobs` pela CLI, confirma as quatro contagens do relatório e
verifica que o chunk de uma transferência com manifest permanece intacto.
Cada execução usa prefixes isolados e remove todos os objetos temporários ao
terminar.

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
`ListBucket`, `ListBucketMultipartUploads`, `ListMultipartUploadParts`,
`AbortMultipartUpload` e `DeleteObject`. Sem a flag de habilitação, o teste é
ignorado.

O repositório também inclui o workflow `S3-compatible contract`, que executa o
teste em pull requests destinados a `main`, em cada atualização de `main`, em
tags de release e em dias úteis. O job inicia um MinIO descartável, cria o
bucket pela cadeia padrão do AWS CLI e fornece endpoint, região, path-style e
credenciais somente por variáveis de ambiente. Para habilitar o workflow,
configure os secrets de CI
`S3_CONTRACT_ACCESS_KEY_ID` e `S3_CONTRACT_SECRET_ACCESS_KEY`; eles devem ser
credenciais temporárias ou dedicadas ao MinIO do job e nunca devem ser
adicionados ao código ou aos argumentos do Maven. O teste usa prefixes isolados
por transferência e remove os objetos temporários no `finally`, inclusive
quando uma asserção falha.

#### Proteção do caminho de release

O check `S3-compatible contract / s3-contract` é obrigatório para integrar
alterações em `main` e, portanto, para o caminho protegido de publicação de
releases. Configure-o no GitHub em **Settings → Branches → Branch protection
rules** (ou em **Settings → Rules → Rulesets**), criando uma regra/ruleset que
se aplique à branch `main` e habilitando:

1. **Require a pull request before merging**.
2. **Require status checks to pass before merging** e o check
   `S3-compatible contract / s3-contract`.
3. **Require branches to be up to date before merging**, para que o contrato
   valide a revisão que será integrada.
4. Configure o bypass de forma intencional: permita-o somente a administradores
   se a política exigir uma exceção operacional explícita; habilite **Do not
   allow bypassing the above settings** se nem administradores puderem ignorar
   a proteção.

Um resultado `failure`, `cancelled` ou `skipped` (inclusive quando o check não
é reportado) impede o merge e a publicação pelo caminho protegido. A única
exceção é um administrador usar explicitamente o bypass permitido pela regra;
isso deve ser uma decisão operacional registrada. Crie tags de release somente
depois do merge em `main`; o workflow também executa novamente em cada tag
`v*`, como verificação final do commit publicado.

#### Ruleset para tags de release

A proteção de `main` sozinha não protege uma tag criada diretamente. Configure
também um ruleset de tags em **Settings → Rules → Rulesets → New ruleset** com
as seguintes definições:

| Definição | Valor |
|---|---|
| Nome | `Protect release tags` |
| Enforcement status | **Active** |
| Target | **Tags**, padrão incluído `v*` |
| Bypass list | Somente **Repository administrators** e, se houver, o GitHub App ou a conta de automação de release explicitamente designada |
| Regras | **Restrict creations**, **Restrict updates** e **Restrict deletions** |

Administradores e a automação de release designada são os únicos atores
autorizados a criar, atualizar ou excluir tags `v*`. Não inclua
maintainers/writers, usuários individuais ou workflows comuns no bypass. Se a
política não admitir nenhuma exceção, ative também **Do not allow bypassing the
above settings** e remova o bypass de administradores.

O workflow contém uma segunda barreira: o job `Verify release tag policy` busca
`main` e exige que a revisão apontada pela tag seja ancestral de
`origin/main`. Se essa validação falhar, o job `s3-contract` não é executado.
Isso impede que uma tag `v*` criada por configuração incorreta ou por uma
alteração fora do fluxo protegido inicie a verificação/publicação como se fosse
uma release válida.

O procedimento de release é:

1. Abra um pull request para `main` e aguarde o check
   `S3-compatible contract / s3-contract` passar.
2. Faça o merge pela proteção de `main` e aguarde a revisão aparecer em
   `origin/main`.
3. Como ator autorizado pelo ruleset, crie a tag a partir de `main` e publique-a:

   ```bash
   git fetch origin main
   git switch --detach origin/main
   git tag -a vX.Y.Z -m "Release vX.Y.Z"
   git push origin vX.Y.Z
   ```

4. Confirme o workflow `S3-compatible contract` iniciado pela tag. Não crie
   tags a partir de branches de feature, não force atualizações e não exclua
   uma tag publicada para tentar republicá-la. Corrija o código por um novo
   pull request e use uma nova versão de tag.

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

### Usando um repositório Git hospedado autorizado

O limite padrão de cada blob é 100 MB, mas o servidor pode usar outro valor.
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
- **falha do servidor Git**: confirme que o remoto está correto e que o SSH
  agent, credential helper do Git ou configuração específica do provedor tem
  acesso ao repositório e ao armazenamento LFS.
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