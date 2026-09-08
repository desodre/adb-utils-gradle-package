# DroidScope — adb-utils

Biblioteca Kotlin/JVM para comunicação direta com o ADB Server por TCP, normalmente em `127.0.0.1:5037`. **Versão atual: `0.2.0`, API ainda sujeita a mudanças.**

DroidScope implementa o protocolo de smart sockets do servidor. Não é um wrapper de subprocessos: não executa `adb`, não inicia o servidor automaticamente e não usa `ProcessBuilder`. O projeto Dart [adb_utils](https://pub.dev/packages/adb_utils) é uma referência conceitual e comportamental; esta implementação tem arquitetura própria em Kotlin.

## Requisitos e build

- JDK 21 para compilar e executar a biblioteca. O Gradle pode provisionar a toolchain pelo resolver Foojay.
- Gradle Wrapper incluído (9.6.0), Kotlin 2.4.10 e Coroutines 1.11.0.
- Para uso real: Android Platform Tools instalado, ADB Server já iniciado (`adb start-server`) e depuração USB/Wi-Fi autorizada no dispositivo.
- Os testes usam transportes simulados e sockets loopback em portas efêmeras; não exigem ADB nem celular.

```sh
./gradlew test
./gradlew build
```

O artefato fica em `build/libs/adb-utils-0.2.0.jar`. Ainda não há publicação em repositório Maven; use este projeto como módulo Gradle local. O JAR não empacota Kotlin stdlib ou Coroutines: consumidores precisam das dependências de runtime transitivas do módulo.

## Exemplo mínimo

```kotlin
import org.desodre.droidscope.client.AdbClient
import org.desodre.droidscope.model.DeviceSerial
import org.desodre.droidscope.model.TcpPort

suspend fun main() {
    val adb = AdbClient()

    println(adb.version()) // Número interno do protocolo, por exemplo 41.
    adb.devices().forEach {
        println("${it.serial}: ${it.model}")
    }

    val device = adb.device()
    println(device.shell("getprop ro.product.model"))
    println(device.getprop("ro.product.model"))

    val result = device.shellV2("id")
    println("${result.exitCode}: ${result.stdout}")

    device.push("hello".encodeToByteArray(), "/data/local/tmp/hello.txt")
    println(device.pull("/data/local/tmp/hello.txt").decodeToString())

    // Para selecionar explicitamente:
    // val selected = adb.device(DeviceSerial("R58M..."))
}
```

Todas as operações ADB são `suspend`. Cada operação cria e fecha sua conexão, inclusive em falha ou cancelamento; não é necessário fechar `AdbClient` ou `AdbDevice`. Chamadas concorrentes usam sessões independentes. Um `AdbDevice` guarda apenas o serial, sem conexão persistente.

```kotlin
val adb = AdbClient(host = "127.0.0.1", port = 5037, timeoutMillis = 10_000)
```

O timeout limita sessões finitas completas e a conexão TCP. Tracking não possui prazo total: o cancelamento do collector fecha o socket. Cancelamento do chamador permanece `CancellationException`. Com host personalizado, a resolução DNS da JVM pode atrasar a liberação da thread de I/O; o endereço IP padrão dispensa DNS.

## Funcionalidades atuais

- TCP JVM com I/O em `Dispatchers.IO` e fechamento do socket ao cancelar.
- Framing hexadecimal calculado pelo tamanho UTF-8, validação de `OKAY`/`FAIL`, payloads com prefixo e leituras parciais.
- `host:version` e `host:devices-l`, com modelos tipados e campos opcionais.
- Seleção por serial e `host:transport:<serial>` seguido de shell na mesma sessão.
- Shell legado não interativo e leitura de uma propriedade por `getprop`, sem cache.
- Shell v2 com stdout/stderr separados e exit code.
- `trackDevices()` como cold `Flow`, com sessão independente por collector.
- ADB SYNC v1: `stat`, `list`, `push` e `pull` em memória.
- `install`/`uninstall` via SYNC e Package Manager, sem subprocessos.
- Forward/reverse TCP, listagem e remoção com endpoints tipados.

`devices()` inclui todos os estados. `device()` considera disponíveis somente entradas em estado `DEVICE`: zero produz `NoDevicesException` (com a lista detectada); uma é selecionada; duas ou mais produzem `MultipleDevicesException`, com os seriais. A seleção explícita diferencia serial ausente, offline, unauthorized e outros estados indisponíveis. Recovery, bootloader e sideload são modelados, mas não selecionáveis nesta milestone.

O parser preserva nomes como `Pixel_7`, ignora atributos futuros e mapeia estados desconhecidos para `UNKNOWN`. `transportId` usa `Long` positivo para não ficar limitado ao alcance de `Int`; valores fora desse alcance são rejeitados como erro de protocolo.

O shell retorna uma `String` UTF-8 até EOF, preservando quebras de linha. O padrão é até 16 MiB de saída, ajustável em `shell(command, maxOutputBytes = ...)`. O comando é enviado literalmente ao shell remoto; cuide das aspas e não interpole dados não confiáveis. `getprop(name)` valida o nome, protege o argumento e remove somente CR/LF finais; propriedade ausente retorna string vazia.

Limitações do shell legado: stdout/stderr combinados, sem exit code, sem stdin e sem garantia de distinguir EOF normal de uma interrupção remota que também finalize o fluxo. Uma falha do comando remoto não equivale necessariamente a `FAIL` do protocolo. Saída binária não é suportada; UTF-8 inválido gera erro. A saída é acumulada em memória, por isso esta API não deve ser usada para streams contínuos.

`shellV2()` resolve essas limitações para comandos textuais compatíveis, retornando `ShellResult`. `trackDevices()` emite cada snapshot recebido; não reconecta automaticamente após EOF ou erro.

SYNC aceita caminhos de até 1024 bytes UTF-8. `pull()` acumula o arquivo em memória e aplica limite padrão de 64 MiB; `push()` recebe `ByteArray` e envia chunks de até 64 KiB. Streaming de arquivos e metadados SYNC v2 ficam para uma etapa futura.

`install()` envia o APK para `/data/local/tmp`, executa `pm install` por Shell v2 e tenta remover o temporário no final. O resultado informa sucesso e mensagem; a execução real de instalação não fez parte do smoke test 0.2 por não haver APK de fixture. `uninstall()` exige package name validado e lança `PackageOperationException` em falha.

Forward e reverse suportam somente endpoints `tcp:<port>` fixos nesta versão; porta zero e outros namespaces ainda não são aceitos. A remoção é responsabilidade do chamador.

## Estrutura e erros

```text
src/main/kotlin/org/desodre/droidscope/
├── client/         AdbClient, AdbDevice e ciclo de vida das sessões
├── protocol/       framing, respostas e parsing (internos; sem java.*)
├── transport/      contrato de comunicação injetável
│   └── jvm/        implementação TCP e detalhes de socket
├── model/          DeviceSerial, DeviceState, DeviceInfo, AdbVersion
└── error/          hierarquia AdbException
src/test/resources/adb/   fixtures representativas, sintéticas
```

`AdbTransport.read(maxBytes)` retorna um bloco de até esse tamanho; array vazio significa EOF. O protocolo reúne os blocos quando precisa de tamanho exato. Para testes ou outro backend, injete `AdbClient(transportFactory = { ... })`; a fábrica deve fornecer uma instância nova por sessão. O contrato é sequencial por conexão. O protocolo não depende de sockets nem classes de I/O JVM; não há implementação Multiplatform nesta versão.

Erros distinguíveis: `AdbServerUnavailableException` (conexão recusada), `AdbConnectionException`, `AdbTimeoutException`, `AdbProtocolException`, `AdbFailException` (com `reason`), `NoDevicesException`, `MultipleDevicesException`, `DeviceNotFoundException`, `DeviceUnauthorizedException`, `DeviceOfflineException`, `DeviceUnavailableException` e `ShellOutputLimitException`. Argumentos inválidos geram `IllegalArgumentException`. Falhas conhecidas de seleção de transport são convertidas para erros de dispositivo, preservando o `AdbFailException` como causa; outros `FAIL` preservam a mensagem original.

## Roadmap (não implementado)

- logcat com `Flow` e screenshot.
- Streaming SYNC, arquivos locais e suporte a endpoints não TCP.
- Inspeção de pacotes/processos e diagnósticos de dispositivos.
- CLI e Compose Desktop sobre o SDK.
- Avaliar Kotlin Multiplatform/Native e bindings Dart para Flutter.

As referências de protocolo incluem os [serviços ADB no AOSP](https://android.googlesource.com/platform/system/adb/+/refs/heads/main/SERVICES.TXT) e o [manual atual](https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/docs/user/adb.1.md). A documentação histórica contém particularidades antigas; esta milestone usa `OKAY` + payload com prefixo para `host:version`.
