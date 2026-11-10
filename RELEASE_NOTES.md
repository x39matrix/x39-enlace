# X39 1.0.7-x39 · preparación de release / release preparation

## Español

- Edición completa pública `escritorio`: billetera nativa Bitcoin mainnet, comunicaciones, x39tunel y telemetría de lectura.
- Integración de pendientes y stash QR: Camera2 actual con zoom 2× y enfoque central/táctil; intervalo de QR por partes de 1,2 s. Stash conservado como respaldo.
- Cambios de interfaz pendientes: diálogo nativo en lugar de Material y eliminación de layouts/clases del ejemplo no usados.
- Eliminada la excepción HTTP de la IP LAN del laboratorio; acceso privado HTTP solo por la IP del túnel.
- Telemetría honesta ante campos ausentes: sin sincronización al 100% inferida por altura, sin Electrs asumido activo ni porcentajes derivados de contadores CKPool con unidades no verificadas.
- Sin GMS/Firebase ni SDK de telemetría comercial declarado; bibliotecas open-source autorizadas y documentadas. No se afirma «cero dependencias de terceros».
- Keystores y credenciales locales excluidos de Git. Firma diferida al propietario mediante `firmar-y-empaquetar-x39.sh`.

Artefacto inicial: APK release **sin firmar** y zipaligned. La ejecución interactiva del script genera el APK firmado, su SHA-256 definitivo, el hash en HTML y opcionalmente `.ots`. No se publica el APK sin firmar como instalable. Paquete: `org.x39matrix.llamadas.escritorio`; versión 44. Sin instalación en dispositivos ni publicación remota en esta fase.

Pool: `stratum+tcp://mining.x39matrix.org:3333`; SOLO mainnet, reparto configurado 99% minero / 1% operador, sin registro. La API pública verificada en esta preparación informó nodo online en `main`, pero `synced: null` y contadores de unidades no verificadas: no se certifica validación completa ni semántica de shares. El estado en vivo cambia.

## English

- Public complete `escritorio` edition: native mainnet Bitcoin wallet, encrypted communications, x39tunel and read-only telemetry.
- Pending QR changes integrated while retaining current Camera2 zoom/focus behavior; multipart QR interval 1.2 seconds. Original stash retained as backup.
- Pending native dialog/layout cleanup included; laboratory LAN cleartext exception removed.
- Missing node synchronization, Electrs service status and CKPool counter units are no longer presented as verified facts.
- Authorized open-source dependencies; no declared GMS/Firebase or commercial telemetry SDK. Local signing credentials excluded from Git.
- Initial artifact is an **unsigned, zipaligned release APK**. The owner's interactive signing script creates the signed APK, final checksums, HTML hash and optional OTS proof. Application ID `org.x39matrix.llamadas.escritorio`, version code 44.

SOLO endpoint: `stratum+tcp://mining.x39matrix.org:3333`; configured split 99% miner / 1% operator, no account registration. Full node validation and raw counter units were not established by the public response. Remote dashboard deployment and GitHub Release publication are not performed in this local preparation.

## Verificación / Validation

Compilación release y lint esencial correctos. 10 pruebas de la app y 71 de la billetera sin fallos. Firma del script probada de extremo a extremo con una clave X39 temporal aislada; keystore de producción sin utilizar. APK de entrega sin firmar, alineado con `zipalign -c -P 16 4`. No se han realizado pruebas físicas de cámara, túnel ni pagos. Los módulos hermanos se identifican mediante sus hashes de fuentes en `X39_RELEASE_AUDIT.json`; el commit de la app no incluye esos árboles externos.

Release build and essential lint passed; 10 app tests and 71 wallet tests passed. Signing script validated end-to-end using an isolated temporary X39 test key. Production signing remains deferred. Device camera, tunnel and payment tests were not performed. Sibling source tree fingerprints are recorded in `X39_RELEASE_AUDIT.json`; those external trees are not included in the app commit.

## Entrega firmada y web / Signed distribution and web

APK SHA-256: `79799e3f2cb0fba71a320028da29ddc2862d0969270cc5f3eea26be64adbf85f`. Certificado SHA-256: `8d46f850509ebfd4778156195c14bb69719dd09f799a4fb661b940e7e2b22fbe`. Firmas v1/v2/v3 verificadas. APK original conservado sin recompilar ni modificar. Sello de las sumas presente con atestaciones pendientes de confirmación en Bitcoin. Documentación ES/EN y página de descarga actualizadas con los cuatro pilares del ecosistema X39.

Signed APK preserved byte-for-byte. Both fingerprints appear in the README and download HTML. OTS receipt present, pending Bitcoin confirmation. The four X39 ecosystem pillars are documented in Spanish and English.

Publicación web completada: https://mining.x39matrix.org/app/index.html, enlazada desde portada. Nueve rutas verificadas con curl HTTP 200; archivos públicos y APK descargado coinciden con los hashes locales. Respaldo y reversión documentados en `deploy_web/README.md`. Todo en `billetera-motor`.

Web publication completed and linked from the homepage. Nine HTTP 200 curl checks passed; all public files, including the downloaded APK, match their local hashes. Backup and rollback documented in `deploy_web/README.md`.
