# X39 Enlace · app Android de x39

> Construido sobre el [client-sdk-android de LiveKit](https://github.com/livekit/client-sdk-android) (Apache-2.0, commit 923ce1d0 del 16/09/2026). Los cambios al SDK se limitan a `RTCEngine.kt` y `SignalClient.kt`: sin servidores STUN por defecto; si el servidor propio no envía servidores ICE, no hay llamada. El README original del SDK está en `README.livekit.md`; su licencia y avisos, en `LICENSE` y `NOTICE`.

Llamadas, vídeo y mensajes cifrados, un túnel WireGuard propio y una billetera Bitcoin de autocustodia, en una sola app Android y sobre infraestructura propia: sin Google Play Services, sin Firebase, sin SDK de rastreo. Es la app hermana de [X39 Evidencia](https://github.com/x39matrix/x39-android) y lleva dentro el motor de billetera [x39-wallet](https://github.com/x39matrix/x39-wallet). Está pensada para quien necesita hablar y recibir bitcoin sin depender de un intermediario; no promete anonimato en la red ni es un producto auditado (véase «Límites»).

En la tienda aparece todavía como «x39 Comunicaciones» y «x39»; el nombre X39 Enlace llegará con la próxima versión.

## Ediciones publicadas

Las dos se distribuyen desde el repositorio F-Droid propio `https://mining.x39matrix.org/app/fdroid/repo` (huella del índice y del certificado de firma, SHA-256: `8d46f850509ebfd4778156195c14bb69719dd09f799a4fb661b940e7e2b22fbe`, CN=x39matrix).

| Paquete | Nombre en tienda | Versión | Qué incluye | SHA-256 del APK |
|---|---|---|---|---|
| `org.x39matrix.llamadas` | x39 Comunicaciones | 1.0.8 (46) | llamadas, vídeo y mensajes cifrados, túnel, billetera; sin módulos de minería | `34f382f7a5b0d0645281502afd6e9c30af74cf72e84943bb409bc74369ba6225` |
| `org.x39matrix.llamadas.escritorio` | x39 | 1.0.8-x39 (45) | lo anterior más telemetría de solo lectura del nodo Bitcoin y del pool propios | `02927c51c88a7a94fdd19338459fa916c3f4dd6a01139788a5ced080da8b1b26` |

Pruebas de existencia de cada APK, todas verificadas contra nuestro propio nodo Bitcoin Core:

- 46: OpenTimestamps, primer bloque 975174 (09/11/2026), `.ots` junto al APK en el repositorio F-Droid. Sello de tiempo cualificado (RFC 3161, ANF AC Qualified TSA, servicio cualificado conforme al Reglamento eIDAS de la UE) emitido el 10/11/2026, serie `0xC04082135132D07DCC478923CCBEC7`.
- 45: OpenTimestamps por dos vías: el hash figura en [`SHA256SUMS.txt`](https://mining.x39matrix.org/app/SHA256SUMS.txt) sellado el 07/11/2026 (primer bloque 974899) y prueba directa del APK sellada el 09/11/2026 (bloque 975211). Sello cualificado emitido el 10/11/2026, serie `0xC04082135132D07DCC478923CCBEC3`.
- Los `.tsr`, la cadena de certificados y el acta de cada sello cualificado están en `https://mining.x39matrix.org/app/fdroid/sellos/`.
- Biblioteca nativa de la billetera en el APK 46 (`libx39wallet.so`, SHA-256): `244eaa581adbaa822f724c44f5922c627fb96a1488ceca4eaf4580f26bff5a43`.

## Verificar antes de instalar

```bash
sha256sum org.x39matrix.llamadas_46.apk                 # debe coincidir con la tabla
apksigner verify --print-certs org.x39matrix.llamadas_46.apk   # CN=x39matrix, SHA-256 8d46f850…
ots verify org.x39matrix.llamadas_46.apk.ots            # con un nodo Bitcoin propio, sin terceros
openssl ts -verify -data org.x39matrix.llamadas_46.apk -in org.x39matrix.llamadas_46.apk.tsr \
  -CAfile anf_root.pem -untrusted org.x39matrix.llamadas_46.apk.cadena.pem
```

Una huella correcta prueba que el fichero es el publicado; la firma, que lo firmó la clave de x39; los sellos, que existía en esa fecha. Ninguna de las tres prueba que el software sea seguro: eso solo lo da una auditoría independiente, que todavía no se ha hecho.

## Qué hay en este repositorio

| Carpeta | Contenido |
|---|---|
| `sample-app/` | la app de x39 (sabores `general` y `escritorio`): llamadas, mensajes, túnel, billetera, telemetría |
| `sample-app-common/` | código común heredado del ejemplo de LiveKit, adaptado |
| `livekit-android-sdk/`, `livekit-android-camerax/`, … | el SDK de LiveKit con los dos cambios descritos arriba |
| `protocol/` | el protocolo de LiveKit, como submódulo fijado a `v1.50.4` |
| `dist_web/` | la página de descarga pública y las plantillas de publicación |
| `firmar-y-empaquetar-x39.sh` | firma, verificación y empaquetado de una entrega (la contraseña se pide sin eco y no se guarda) |
| `docs/`, `RELEASE_NOTES.md`, `X39_RELEASE_AUDIT.json` | notas de versión y auditoría de entrega |

## Compilar

Requisitos: JDK 17, Android SDK y NDK `30.0.16248370`, Rust con los objetivos Android, y el repositorio hermano `x39-wallet` clonado al lado de este.

```bash
git clone --recurse-submodules https://github.com/x39matrix/x39-enlace.git
git clone https://github.com/x39matrix/x39-wallet.git
cd x39-wallet && X39_MAINNET=1 ./android/build-native.sh --actualizar && cd ..
cd x39-enlace
X39_MAINNET=1 ./gradlew --no-daemon -Px39.mainnet=true :sample-app:assembleGeneralRelease     # edición 46
X39_MAINNET=1 ./gradlew --no-daemon -Px39.mainnet=true :sample-app:assembleEscritorioRelease  # edición 45
```

La mensajería usa el módulo `x39-chat`, que todavía no está publicado; hasta que lo esté, la compilación completa requiere ese árbol al lado de este. Es el siguiente repositorio en salir. La firma de las entregas la hace el mantenedor con su propio almacén; los APK sin firmar no se publican.

## Límites

- El módulo de comunicaciones no ha pasado una auditoría externa. El acuerdo de clave ML-KEM-1024 autenticado con ML-DSA-87 y el cifrado AES-256-GCM son propiedades de la implementación pendientes de comprobación independiente.
- El servidor de señalización propio ve direcciones IP, horas y qué dos claves públicas se conectan; nunca el contenido. Esta app no es una herramienta de anonimato en la red.
- La compilación reproducible byte a byte por terceros está en curso; hoy se publican las huellas de cada entrega y de la biblioteca nativa.
- La billetera es de autocustodia: las claves no salen del dispositivo y nadie puede recuperarlas por el usuario.

## Licencia

Apache-2.0, la misma del SDK de LiveKit sobre el que se construye; véanse `LICENSE` y `NOTICE`. Las partes de x39 son © X39matrix. Contacto: security@x39matrix.org.

---

# X39 Enlace · x39 Android app

> Built on LiveKit's [client-sdk-android](https://github.com/livekit/client-sdk-android) (Apache-2.0, commit 923ce1d0 of 2026-09-16). Changes to the SDK are limited to `RTCEngine.kt` and `SignalClient.kt`: no default STUN servers; if our own server sends no ICE servers, the call does not happen. The SDK's original README is `README.livekit.md`; its license and notices are in `LICENSE` and `NOTICE`.

Encrypted calls, video and messaging, a private WireGuard tunnel and a non-custodial Bitcoin wallet in one Android app on self-hosted infrastructure: no Google Play Services, no Firebase, no tracking SDKs. It is the sister app of [X39 Evidencia](https://github.com/x39matrix/x39-android) and embeds the [x39-wallet](https://github.com/x39matrix/x39-wallet) engine. It is meant for people who need to talk and receive bitcoin without an intermediary; it does not promise network anonymity and it has not been audited (see "Limits").

In the store it still appears as "x39 Comunicaciones" and "x39"; the name X39 Enlace arrives with the next version.

## Published editions

Both are distributed from our own F-Droid repository `https://mining.x39matrix.org/app/fdroid/repo` (index and signing-certificate fingerprint, SHA-256: `8d46f850509ebfd4778156195c14bb69719dd09f799a4fb661b940e7e2b22fbe`, CN=x39matrix).

| Package | Store name | Version | Contents | APK SHA-256 |
|---|---|---|---|---|
| `org.x39matrix.llamadas` | x39 Comunicaciones | 1.0.8 (46) | encrypted calls, video and messaging, tunnel, wallet; no mining modules | `34f382f7a5b0d0645281502afd6e9c30af74cf72e84943bb409bc74369ba6225` |
| `org.x39matrix.llamadas.escritorio` | x39 | 1.0.8-x39 (45) | the above plus read-only telemetry of our own Bitcoin node and pool | `02927c51c88a7a94fdd19338459fa916c3f4dd6a01139788a5ced080da8b1b26` |

Proofs of existence for each APK, all verified against our own Bitcoin Core node:

- 46: OpenTimestamps, first block 975174 (2026-11-09), `.ots` next to the APK in the F-Droid repository. Qualified time stamp (RFC 3161, ANF AC Qualified TSA, qualified service under the EU eIDAS Regulation) issued 2026-11-10, serial `0xC04082135132D07DCC478923CCBEC7`.
- 45: OpenTimestamps in two ways: the hash is listed in [`SHA256SUMS.txt`](https://mining.x39matrix.org/app/SHA256SUMS.txt) stamped 2026-11-07 (first block 974899), and a direct proof of the APK stamped 2026-11-09 (block 975211). Qualified time stamp issued 2026-11-10, serial `0xC04082135132D07DCC478923CCBEC3`.
- The `.tsr` files, certificate chains and records of the qualified stamps are at `https://mining.x39matrix.org/app/fdroid/sellos/`.
- Native wallet library in APK 46 (`libx39wallet.so`, SHA-256): `244eaa581adbaa822f724c44f5922c627fb96a1488ceca4eaf4580f26bff5a43`.

## Verify before installing

```bash
sha256sum org.x39matrix.llamadas_46.apk                 # must match the table
apksigner verify --print-certs org.x39matrix.llamadas_46.apk   # CN=x39matrix, SHA-256 8d46f850…
ots verify org.x39matrix.llamadas_46.apk.ots            # against your own Bitcoin node, no third parties
openssl ts -verify -data org.x39matrix.llamadas_46.apk -in org.x39matrix.llamadas_46.apk.tsr \
  -CAfile anf_root.pem -untrusted org.x39matrix.llamadas_46.apk.cadena.pem
```

A matching hash proves the file is the published one; the signature, that x39's key signed it; the stamps, that it existed on that date. None of the three proves the software is secure: only an independent audit does, and it has not happened yet.

## What is in this repository

| Folder | Contents |
|---|---|
| `sample-app/` | the x39 app (`general` and `escritorio` flavors): calls, messaging, tunnel, wallet, telemetry |
| `sample-app-common/` | shared code inherited from the LiveKit sample, adapted |
| `livekit-android-sdk/`, `livekit-android-camerax/`, … | LiveKit's SDK with the two changes described above |
| `protocol/` | LiveKit's protocol, as a submodule pinned to `v1.50.4` |
| `dist_web/` | the public download page and publishing templates |
| `firmar-y-empaquetar-x39.sh` | signing, verification and packaging of a release (the password is read without echo and never stored) |
| `docs/`, `RELEASE_NOTES.md`, `X39_RELEASE_AUDIT.json` | release notes and release audit |

## Build

Requirements: JDK 17, Android SDK and NDK `30.0.16248370`, Rust with the Android targets, and the sibling repository `x39-wallet` cloned next to this one.

```bash
git clone --recurse-submodules https://github.com/x39matrix/x39-enlace.git
git clone https://github.com/x39matrix/x39-wallet.git
cd x39-wallet && X39_MAINNET=1 ./android/build-native.sh --actualizar && cd ..
cd x39-enlace
X39_MAINNET=1 ./gradlew --no-daemon -Px39.mainnet=true :sample-app:assembleGeneralRelease     # edition 46
X39_MAINNET=1 ./gradlew --no-daemon -Px39.mainnet=true :sample-app:assembleEscritorioRelease  # edition 45
```

Messaging uses the `x39-chat` module, which is not published yet; until it is, a complete build requires that tree next to this one. It is the next repository to be released. Releases are signed by the maintainer with his own keystore; unsigned APKs are never published.

## Limits

- The communications module has not undergone an external audit. The ML-KEM-1024 key agreement authenticated with ML-DSA-87 and the AES-256-GCM encryption are properties of the implementation pending independent verification.
- Our own signalling server sees IP addresses, times and which two public keys connect; never the content. This app is not a network anonymity tool.
- Byte-for-byte reproducible builds by third parties are in progress; today we publish the hashes of each release and of the native library.
- The wallet is non-custodial: keys never leave the device and nobody can recover them on the user's behalf.

## License

Apache-2.0, the same as the LiveKit SDK it is built on; see `LICENSE` and `NOTICE`. The x39 parts are © X39matrix. Contact: security@x39matrix.org.
