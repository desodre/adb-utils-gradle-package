# Android sample

This minimal application resolves `adb-utils` from Maven and connects directly to a reachable ADB Server over TCP.

For the Android emulator, `10.0.2.2` points to the development machine. The desktop ADB Server normally listens only on loopback, so exposing it to a device or emulator requires an intentional network configuration. Do not expose an ADB Server to an untrusted network.

Build against the local publication repository:

```sh
../../gradlew -p ../.. validatePublication
../../gradlew :app:assembleDebug \
  -PadbUtilsRepository=../../build/publication-check-repository \
  -PadbUtilsVersion=0.2.0
```

The sample targets Android 16 / API 36 and declares `INTERNET`. Apps targeting Android 17 / API 37 or newer must also implement Android's runtime local-network permission before connecting to a LAN address.
