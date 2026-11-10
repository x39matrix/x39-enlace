# X39 · Android / Android app

> Construido sobre el [client-sdk-android de LiveKit](https://github.com/livekit/client-sdk-android) (Apache-2.0, commit 923ce1d0 del 16/09/2026). Los cambios al SDK se limitan a `RTCEngine.kt` y `SignalClient.kt`: sin servidores STUN por defecto; si el servidor propio no envía servidores ICE, no hay llamada. El README original del SDK está en `README.livekit.md`; su licencia y avisos, en `LICENSE` y `NOTICE`.

## Entrega firmada / Signed release

- **APK SHA-256:** `79799e3f2cb0fba71a320028da29ddc2862d0969270cc5f3eea26be64adbf85f`
- **Certificado del firmante / Signing certificate SHA-256:** `8d46f850509ebfd4778156195c14bb69719dd09f799a4fb661b940e7e2b22fbe`
- **APK:** `x39-app-latest.apk`, versión 1.0.7-x39 (44), firmado y verificado con esquemas v1/v2/v3.
- **OpenTimestamps:** `SHA256SUMS.txt.ots` sella `SHA256SUMS.txt`, que contiene el hash del APK. El comprobante recibido contiene atestaciones pendientes de calendarios; requiere actualización y verificación posterior para acreditar el anclaje confirmado en Bitcoin. El APK firmado se conserva byte a byte.

### Los cuatro pilares de X39

1. **Evidencias para reporteros y ciudadanos:** sellado criptográfico con OpenTimestamps (`.ots`) para detectar cambios y acreditar la existencia previa de un archivo al confirmar su anclaje. Conserva el original y la prueba. Este pilar pertenece al flujo X39 Evidencia del ecosistema; en esta distribución se aporta el sello de las sumas del APK. El sello no impide modificar un archivo ni prueba que su contenido sea verdadero.
2. **Dinero bajo tu custodia:** billetera Bitcoin nativa `libx39wallet.so` y Electrs propio, para disponer de bitcoin sin depender de una cuenta bancaria, también cuando el banco bloquea su acceso. Las claves permanecen bajo custodia local.
3. **Comunicaciones privadas cifradas:** mensajería y llamadas cifradas con infraestructura X39 y túnel privado `x39tunel` / WireGuard.
4. **Infraestructura verificable en directo:** telemetría de solo lectura del nodo Bitcoin Core mainnet y de CKPool SOLO, exclusivamente `stratum+tcp://mining.x39matrix.org:3333`; reparto configurado 99% minero / 1% operador, sin registro.

### The four X39 pillars

1. **Evidence for reporters and citizens:** OpenTimestamps (`.ots`) seals provide tamper evidence and proof of prior existence once Bitcoin anchoring is confirmed. Keep the original file and proof. X39 Evidence is the ecosystem's evidence workflow; this distribution includes the APK checksum timestamp. A timestamp does not establish the truth of the content.
2. **Self-custodied money:** native Bitcoin wallet `libx39wallet.so` with self-hosted Electrs, independent of a bank account, including when access to banking is blocked.
3. **Private encrypted communications:** encrypted messaging and calls over X39 infrastructure, with the private `x39tunel` / WireGuard tunnel.
4. **Live infrastructure telemetry:** read-only Bitcoin Core mainnet and CKPool SOLO statistics; the sole mining endpoint is `stratum+tcp://mining.x39matrix.org:3333`.

Signed APK SHA-256 and official certificate fingerprint are listed above. Signatures v1/v2/v3 have been verified. The OTS receipt currently contains pending calendar attestations; confirmed Bitcoin anchoring needs later upgrading and verification.

## Español

X39 integra comunicaciones cifradas, billetera Bitcoin nativa y telemetría del nodo y del pool propio. La edición completa pública se construye con el flavor `escritorio`; mantiene el identificador `org.x39matrix.llamadas.escritorio`, distinto del de Comunicaciones. Versión 1.0.7-x39 (44), ABI arm64-v8a. No reemplaza una instalación con otro identificador o una firma diferente.

**Soberanía:** sin Google Play Services (GMS), Firebase ni telemetría comercial externa. Utiliza bibliotecas libres de terceros: LiveKit/WebRTC, AndroidX, Kotlin, OkHttp, WireGuard, ZXing, Protobuf, JNA y dependencias del motor Rust. Los repositorios Maven de Google y el Android SDK son herramientas de compilación; no equivalen a incorporar GMS. Las métricas técnicas de llamadas, cuando las habilita LiveKit, pertenecen a la sesión con el servidor configurado; no constituyen una prueba de ausencia de tráfico mediante análisis dinámico.

- **Billetera:** `../x39-wallet/android` es un build compuesto local. `libx39wallet.so` y los bindings Kotlin se generan desde Rust con UniFFI. La compilación de esta entrega habilita `mainnet` tanto en Rust como en Android; consulta el Electrs propio configurado en `NodoX39` o por el usuario. Custodia local de claves: no se incluyen semillas ni claves privadas en el APK o en Git.
- **Comunicaciones:** llamadas con LiveKit/WebRTC y E2EE; mensajería del módulo local `../x39-chat`. La infraestructura de señalización y transporte la configura X39. El código y las licencias originales de LiveKit permanecen en este árbol; documentación upstream en `README.livekit.md`.
- **x39tunel:** WireGuard integrado. HTTPS por defecto; la excepción HTTP privada de la edición completa queda limitada a la dirección interna del nodo dentro del túnel, sin la IP LAN del banco de pruebas.
- **Telemetría:** `TelemetriaPi5.kt` realiza peticiones GET a `https://mining.x39matrix.org/api/public/stats` y consultas individuales, con opción de API privada de lectura por túnel. El panel de telemetría no firma transacciones ni administra Bitcoin Core/CKPool. La billetera tiene sus propias funciones de firma, separadas de la telemetría.
- **Minería:** Bitcoin Core mainnet + CKPool SOLO, exclusivamente `stratum+tcp://mining.x39matrix.org:3333`; reparto configurado 99% al minero y 1% de fee al operador, sin registro de cuenta. La dirección Bitcoin identifica al minero. No es una promesa de encontrar bloques ni de ingresos.

La consulta pública de esta preparación informó `chain: main`, nodo online y Stratum :3333 verificado. También devolvió `synced: null` y `counterSemantics: unverified_raw_counters`: no demuestra validación completa del nodo ni unidades de shares. El panel muestra los datos recibidos y su antigüedad; no inventa sincronización, cabeceras, estado de Electrs ni porcentaje de aceptación cuando la API no los informa. Los importes de recompensa potencial no son saldo recibido.

### Compilar y firmar

Requiere los árboles locales hermanos `x39-wallet` y `x39-chat`, Android SDK/JDK configurados, NDK 30.0.16248370 y herramientas Rust fijadas por `x39-wallet/android/build-native.sh`.

```bash
cd /home/x39matrix/x39-wallet
source /home/x39matrix/Android/env.sh
export ANDROID_NDK_HOME=/home/x39matrix/Android/sdk/ndk/30.0.16248370
X39_MAINNET=1 ./android/build-native.sh --actualizar
cd /home/x39matrix/client-sdk-android
X39_MAINNET=1 ./gradlew --no-daemon -Px39.mainnet=true :sample-app:assembleEscritorioRelease :sample-app:testEscritorioReleaseUnitTest
```

Para futuras compilaciones, el artefacto previo a la firma es: `dist_web/x39-app-release-unsigned.apk`, alineado y todavía no instalable. `SHA256SUMS-UNSIGNED.txt` verifica solo ese artefacto. No publiques este archivo como APK de producción. Ejecuta desde una terminal:

```bash
cd /home/x39matrix/client-sdk-android
./firmar-y-empaquetar-x39.sh
```

El script pide la contraseña sin eco, utiliza `/home/x39matrix/x39-release.jks` (alias `x39`), firma, verifica firma y alineación, genera `dist_web/x39-app-latest.apk`, `FIRMA_APK.txt` y el `SHA256SUMS.txt` definitivo, e inserta el hash firmado en `download-x39-app.html`. Intenta sellar las sumas con OpenTimestamps si está instalado. No modifica Git ni sube archivos. Requiere Bash, Python 3, `sha256sum`, `timeout` y Android Build Tools 35.0.0; se puede indicar otra ruta mediante `X39_BUILD_TOOLS`. La contraseña no se guarda; se pasa solo al proceso de firma mediante una variable de entorno temporal.

### Verificar antes de instalar

1. Descarga `x39-app-latest.apk`, `SHA256SUMS.txt` y `FIRMA_APK.txt` del canal oficial.
2. En la misma carpeta ejecuta `sha256sum -c SHA256SUMS.txt`; exige `OK`.
3. Ejecuta `apksigner verify --verbose --print-certs x39-app-latest.apk`. Compara la huella SHA-256 del certificado con una copia oficial obtenida por un canal independiente; una firma válida por sí sola no autentica a X39.
4. Si hay `SHA256SUMS.txt.ots`, ejecuta `ots verify SHA256SUMS.txt.ots`. Un sello pendiente requiere `ots upgrade SHA256SUMS.txt.ots` y confirmación posterior; el sello prueba existencia temporal, no autoría.
5. Solo entonces instala el APK. Nunca introduzcas semillas o contraseñas en el panel web.

### Web y Git

Después de firmar, el fragmento HTML oscuro usa únicamente recursos locales y permite descargar el APK firmado con su hash visible. Integra el fragmento en el frontend y sirve el contenido público de `dist_web/` bajo `/app/`. La ruta SSH del VPS debe verificarse antes de subirlo. La entrega firmada se publica bajo `/app/index.html`, enlazada desde la portada. La publicación web sirve exclusivamente los archivos públicos firmados; el APK sin firmar permanece local. GitHub Release es un canal separado.

El upstream `origin` de LiveKit se conserva sin push. Se prepara commit/tag local con identidad `x39matrix <x39matrix@outlook.es>`. El repositorio X39 de destino aún debe elegirse. Los APK, firmas y sumas finales son artefactos locales ignorados por Git; el script, la plantilla y la documentación sí se versionan. El stash QR se conserva como respaldo: zoom 2×, enfoque central/táctil y diagnóstico de debug ya estaban en Camera2; se conserva la pausa de 1,2 s por QR sin reintroducir CameraX.

## English

X39 combines encrypted communications, a native Bitcoin wallet and read-only node/pool telemetry. Public complete flavor: `escritorio`; application ID `org.x39matrix.llamadas.escritorio`; version 1.0.7-x39 (44), arm64-v8a.

**No Google Play Services, Firebase or third-party commercial telemetry.** Open-source libraries are used, including LiveKit/WebRTC, AndroidX, Kotlin, OkHttp, WireGuard, ZXing, Protobuf and JNA. Build repositories and SDK tooling are distinct from bundled proprietary Google services. LiveKit session metrics, if enabled, go to the configured session server; static auditing does not replace runtime network analysis.

The native `libx39wallet.so` wallet uses the sibling `x39-wallet` project, UniFFI and the configured self-hosted Electrs. Mainnet must be enabled in both the Rust library and Android. Encrypted communications also use sibling `x39-chat`. WireGuard provides the private x39tunel; HTTPS is the default, with a private HTTP exception only for the VPN node `10.39.0.3`.

`TelemetriaPi5.kt` reads the public HTTPS stats API and optional private read-only API using GET. Mining endpoint: **stratum+tcp://mining.x39matrix.org:3333**, Bitcoin Core mainnet and CKPool SOLO, configured 99% miner reward / 1% operator fee, no account registration. The Bitcoin address identifies the miner. Rewards remain probabilistic. Public API data currently does not establish full node synchronization or counter units; missing information remains explicitly unknown in the UI.

Build using the commands above. For future builds, the pre-signing APK is unsigned and zipaligned: `dist_web/x39-app-release-unsigned.apk`. Run `./firmar-y-empaquetar-x39.sh` in an interactive terminal: it securely prompts for the existing release keystore password, signs and verifies `dist_web/x39-app-latest.apk`, writes the final `SHA256SUMS.txt`, updates the HTML hash and attempts an OpenTimestamps proof. No password is stored, and no upload or Git mutation is performed by the script.

Before installing, run `sha256sum -c SHA256SUMS.txt` and `apksigner verify --verbose --print-certs x39-app-latest.apk`. Compare the certificate SHA-256 fingerprint through an independent trusted channel. If present, verify the OTS proof with `ots verify SHA256SUMS.txt.ots`; pending proofs need later upgrading/confirmation. Hashes establish file integrity, signatures identify a signing key, and timestamps establish prior existence.

Only deploy the package after signing. The dark download HTML has no remote scripts, fonts or trackers. The signed web download is served under `/app/index.html`, linked from the mining homepage. GitHub Release publication is separate. Keep upstream licensing and attribution intact.
