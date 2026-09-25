# adb-utils

[English](README.md) · [Português (Brasil)](README.pt-BR.md)

Biblioteca Kotlin/JVM para comunicação direta com o ADB Server por TCP, normalmente em `127.0.0.1:5037`. **Versão atual: `0.2.0`, API ainda sujeita a mudanças.**

adb-utils implementa o protocolo de smart sockets do servidor. Não é um wrapper de subprocessos: não executa `adb`, não inicia o servidor automaticamente e não usa `ProcessBuilder`.

Esta é a implementação Kotlin/JVM da família adb-utils. A implementação Dart, versionada de forma independente, está em [desodre/adb_utils](https://github.com/desodre/adb_utils) e no [pub.dev](https://pub.dev/packages/adb_utils). As duas compartilham objetivos de protocolo, mas não código-fonte nem números de versão.

## Requisitos e build

- JDK 17 para compilar e executar a biblioteca. O Gradle pode provisionar a toolchain pelo resolver Foojay.
- Gradle Wrapper incluído (9.6.0), Kotlin 2.4.10 e Coroutines 1.11.0.
- Para uso real: Android Platform Tools instalado, ADB Server já iniciado (`adb start-server`) e depuração USB/Wi-Fi autorizada no dispositivo.
- Os testes usam transportes simulados e sockets loopback em portas efêmeras; não exigem ADB nem celular.

```sh
./gradlew test
./gradlew build
```

O artefato fica em `build/libs/adb-utils-0.2.0.jar`. A automação da primeira publicação no Maven Central está pronta; até a tag ser publicada, use `./gradlew publishToMavenLocal` ou o repositório local de validação. O JAR não empacota Kotlin stdlib ou Coroutines; o POM fornece essas dependências transitivas aos consumidores.

## Exemplo mínimo

```kotlin
import io.github.desodre.adbutils.client.AdbClient
import io.github.desodre.adbutils.model.DeviceSerial
import io.github.desodre.adbutils.model.TcpPort
import java.nio.file.Path

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

    device.pullTo("/sdcard/large.bin", Path.of("large.bin"))
    device.push(Path.of("upload.bin"), "/data/local/tmp/upload.bin")

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
- `waitForDevice()` para aguardar serial e estado específicos com timeout.
- ADB SYNC v1: `stat`, `list`, operações em memória, streaming e arquivos locais.
- `install`/`uninstall` via SYNC e Package Manager, sem subprocessos.
- Forward/reverse TCP, listagem e remoção com endpoints tipados.
- Snapshot estruturado de bateria, armazenamento, memória, uptime, Android e hardware.

`devices()` inclui todos os estados. `device()` considera disponíveis somente entradas em estado `DEVICE`: zero produz `NoDevicesException` (com a lista detectada); uma é selecionada; duas ou mais produzem `MultipleDevicesException`, com os seriais. A seleção explícita diferencia serial ausente, offline, unauthorized e outros estados indisponíveis. Recovery, bootloader e sideload são modelados, mas não selecionáveis nesta milestone.

O parser preserva nomes como `Pixel_7`, ignora atributos futuros e mapeia estados desconhecidos para `UNKNOWN`. `transportId` usa `Long` positivo para não ficar limitado ao alcance de `Int`; valores fora desse alcance são rejeitados como erro de protocolo.

O shell retorna uma `String` UTF-8 até EOF, preservando quebras de linha. O padrão é até 16 MiB de saída, ajustável em `shell(command, maxOutputBytes = ...)`. O comando é enviado literalmente ao shell remoto; cuide das aspas e não interpole dados não confiáveis. `getprop(name)` valida o nome, protege o argumento e remove somente CR/LF finais; propriedade ausente retorna string vazia.

Limitações do shell legado: stdout/stderr combinados, sem exit code, sem stdin e sem garantia de distinguir EOF normal de uma interrupção remota que também finalize o fluxo. Uma falha do comando remoto não equivale necessariamente a `FAIL` do protocolo. Saída binária não é suportada; UTF-8 inválido gera erro. A saída é acumulada em memória, por isso esta API não deve ser usada para streams contínuos.

`shellV2()` resolve essas limitações para comandos textuais compatíveis, retornando `ShellResult`. `trackDevices()` emite cada snapshot recebido; não reconecta automaticamente após EOF ou erro.

Para aguardar um dispositivo após reboot sem polling manual:

```kotlin
val ready = adb.waitForDevice(
    serial = DeviceSerial("R58M..."),
    state = DeviceState.DEVICE,
    timeoutMillis = 30_000,
)
```

Ausência temporária e estados intermediários continuam sendo observados. O timeout lança `AdbTimeoutException`; sucesso, timeout e cancelamento sempre encerram a conexão de tracking.

SYNC aceita caminhos de até 1024 bytes UTF-8. `pull()` mantém o limite padrão de 64 MiB em memória. `pullChunks()` fornece `Flow<ByteArray>` e `pullTo()` grava por arquivo temporário, substituindo o destino atomicamente quando possível. `pushChunks()` emite `SyncTransferProgress`; a sobrecarga com `Path` transmite o arquivo sem carregá-lo inteiro e deriva permissões POSIX e data de modificação. Limites são configuráveis, cancelamento fecha a sessão e os frames permanecem limitados a 64 KiB.

No Android, as sobrecargas baseadas em `java.nio.file.Path` exigem API 26 ou superior; as sobrecargas baseadas em Flow continuam disponíveis independentemente dos helpers para arquivos locais.

`install()` envia o APK para `/data/local/tmp`, executa `pm install` por Shell v2 e tenta remover o temporário no final. O resultado informa sucesso e mensagem; a execução real de instalação não fez parte do smoke test 0.2 por não haver APK de fixture. `uninstall()` exige package name validado e lança `PackageOperationException` em falha.

Forward e reverse suportam somente endpoints `tcp:<port>` fixos nesta versão; porta zero e outros namespaces ainda não são aceitos. A remoção é responsabilidade do chamador.

## Diagnóstico de saúde

```kotlin
val health = device.healthSnapshot(
    DeviceHealthOptions(sectionTimeoutMillis = 2_000),
)

when (val battery = health.battery) {
    is HealthSection.Available -> println("bateria=${battery.value.levelPercent}%")
    is HealthSection.Unavailable -> println("indisponível: ${battery.failure.kind}")
}
```

Bateria, armazenamento de `/data`, memória, uptime, versão Android e hardware são coletados concorrentemente. Cada seção possui timeout e falha próprios, portanto uma fonte ausente, incompatível ou malformada não elimina os demais resultados. Tamanhos usam bytes, uptime usa milissegundos e temperatura da bateria usa décimos de grau Celsius. Seriais de hardware não são consultados por padrão; habilite explicitamente `includeIdentifiers = true` quando esse dado for necessário.

## Estrutura e erros

```text
src/main/kotlin/io/github/desodre/adbutils/
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
- Suporte a endpoints de forward que não sejam TCP.
- Inspeção de pacotes/processos e diagnósticos adicionais.
- CLI e Compose Desktop sobre o SDK.
- Avaliar Kotlin Multiplatform/Native.

As referências de protocolo incluem os [serviços ADB no AOSP](https://android.googlesource.com/platform/system/adb/+/refs/heads/main/SERVICES.TXT) e o [manual atual](https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/docs/user/adb.1.md). A documentação histórica contém particularidades antigas; esta milestone usa `OKAY` + payload com prefixo para `host:version`.

## Publicação e implementação relacionada

Para validar localmente os artefatos Maven, checksums e metadados obrigatórios:

```sh
./gradlew validatePublication
./gradlew checkKotlinAbi consumerTest
```

O repositório de validação sem assinatura fica em `build/publication-check-repository`; `consumerTest` resolve a amostra JVM somente por esse repositório. O modo de API explícita e `checkKotlinAbi` protegem a baseline pública versionada. Use `./gradlew updateKotlinAbi` apenas após revisar uma mudança intencional.

A tarefa `releaseBundle` é exclusiva para releases assinadas e falha sem `signingKey` e `signingPassword`. Uma tag semântica igual ao arquivo `VERSION` envia o bundle ao Central Portal, aguarda o estado `PUBLISHED` e cria a GitHub Release. Consulte [RELEASING.md](RELEASING.md). As amostras estão em [samples/kotlin-jvm](samples/kotlin-jvm) e [samples/android](samples/android); a documentação Dokka é publicada no [GitHub Pages](https://desodre.github.io/adb-utils-gradle-package/).

- [adb_utils para Dart](https://github.com/desodre/adb_utils), distribuída pelo [pub.dev](https://pub.dev/packages/adb_utils).

As funcionalidades e versões evoluem de forma independente em cada ecossistema.
