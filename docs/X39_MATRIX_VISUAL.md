# X39 MATRIX · actualización visual

Fuente oficial entregada por el propietario: `logo-x39.png` (SHA-256 `14fe9a539345474233d6783b75e5dc232d5b104ed9fce997f2864c5aed32215f`). Se conserva una copia exacta en `sample-app/src/main/res/drawable-nodpi/x39_matrix_logo.png`; el archivo temporal de raíz está ignorado por Git.

Iconos de lanzador y round: 48, 72, 96, 144 y 192 píxeles. Fastlane ES/EN y web: PNG 512 × 512. Redimensionado determinista con Lanczos, sin regeneración artística. Adaptive icon: fondo negro puro y zona de seguridad para mantener todo el logo visible; Android puede imponer su propia máscara de lanzador.

Paleta visual: negro #090A0D, tarjetas #12141A, rojo #FF1E1E, oro #F7931A / #FFB703, blanco #FFFFFF, plata #A0AAB8. Bordes en rojo oscuro #492327. Cabecera principal y de billetera con arte oficial; marco del visor QR rojo/ámbar. Cambios limitados a recursos, composición de cabecera, colores y número de versión. Flujos de cámara, QR, firma, túnel, billetera y telemetría conservados.

Nueva compilación sin firmar: 1.0.8-x39, versionCode 45. El APK firmado 1.0.7-x39 (44) y sus comprobantes se conservan; no se publica automáticamente la nueva compilación. El código de integración de una nueva bóveda no forma parte de esta actualización de marca.

La billetera vive en el árbol hermano `x39-wallet`; sus cambios de estilo se documentan en `wallet-matrix-style.patch` para poder aplicarlos junto al proyecto principal.
