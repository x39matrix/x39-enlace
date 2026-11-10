#!/usr/bin/env bash
# X39: firma local interactiva; nunca guarda la contraseña ni utiliza una clave de prueba.
set -euo pipefail
umask 077
X39_RAIZ="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
X39_DIST="$X39_RAIZ/dist_web"
X39_SDK="${ANDROID_HOME:-/home/x39matrix/Android/sdk}"
X39_BT="${X39_BUILD_TOOLS:-$X39_SDK/build-tools/35.0.0}"
X39_KEYSTORE="${X39_KEYSTORE:-/home/x39matrix/x39-release.jks}"
X39_ALIAS="${X39_KEY_ALIAS:-x39}"
X39_UNSIGNED="$X39_DIST/x39-app-release-unsigned.apk"
for X39_TOOL in "$X39_BT/apksigner" "$X39_BT/zipalign"; do
    [[ -x "$X39_TOOL" ]] || { printf 'X39: falta %s\n' "$X39_TOOL" >&2; exit 1; }
done
command -v python3 >/dev/null
command -v sha256sum >/dev/null
[[ -f "$X39_KEYSTORE" && -f "$X39_UNSIGNED" ]] || { printf 'X39: falta el keystore o el APK sin firmar.\n' >&2; exit 1; }
[[ -t 0 ]] || { printf 'X39: ejecuta este script en una terminal interactiva.\n' >&2; exit 1; }
(cd "$X39_DIST" && sha256sum -c SHA256SUMS-UNSIGNED.txt)
"$X39_BT/zipalign" -c -P 16 4 "$X39_UNSIGNED"
X39_TMP="$(mktemp -d "$X39_DIST/.x39-firma.XXXXXXXX")"
trap 'unset X39_SIGNING_PASSWORD; rm -rf -- "$X39_TMP"' EXIT
IFS= read -r -s -p 'X39 — contraseña del keystore (oculta): ' X39_SIGNING_PASSWORD
printf '\n'
[[ -n "$X39_SIGNING_PASSWORD" ]] || { printf 'X39: contraseña vacía.\n' >&2; exit 1; }
export X39_SIGNING_PASSWORD
"$X39_BT/apksigner" sign --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true --ks "$X39_KEYSTORE" --ks-key-alias "$X39_ALIAS" \
    --ks-pass env:X39_SIGNING_PASSWORD --key-pass env:X39_SIGNING_PASSWORD \
    --out "$X39_TMP/x39-app-latest.apk" "$X39_UNSIGNED"
unset X39_SIGNING_PASSWORD
"$X39_BT/apksigner" verify --verbose --print-certs "$X39_TMP/x39-app-latest.apk" > "$X39_TMP/FIRMA_APK.txt"
"$X39_BT/zipalign" -c -P 16 4 "$X39_TMP/x39-app-latest.apk"
(cd "$X39_TMP" && sha256sum x39-app-latest.apk > SHA256SUMS.txt && sha256sum -c SHA256SUMS.txt)
python3 - "$X39_DIST/download-x39-app.template.html" "$X39_TMP" "$X39_DIST/release-unsigned.json" <<'PY'
from pathlib import Path
import sys, json, re
plantilla, destino = Path(sys.argv[1]), Path(sys.argv[2])
version = json.loads(Path(sys.argv[3]).read_text())
hash_apk = (destino/'SHA256SUMS.txt').read_text().split()[0]
html = plantilla.read_text().replace('__X39_APK_SHA256__', hash_apk).replace('__X39_DOWNLOAD_BUTTON__', '<a class="x39-download" href="x39-app-latest.apk" download>Descargar APK firmado / Download signed APK</a>')
html = html.replace('__X39_VERSION_NAME__', version['versionName']).replace('__X39_VERSION_CODE__', str(version['versionCode']))
firma = (destino/'FIRMA_APK.txt').read_text()
cert = re.search(r'certificate SHA-256 digest: ([0-9a-fA-F]{64})', firma)
if not cert: raise SystemExit('X39: falta la huella del certificado verificado')
html = re.sub(r'(<code id="x39-signer-sha256">)[^<]*(</code>)', lambda m: m[1]+cert[1].lower()+m[2], html)
if '__X39_' in html: raise SystemExit('X39: quedan marcadores sin resolver en HTML')
(destino/'download-x39-app.html').write_text(html)
(destino/'index.html').write_text(html)
(destino/'ESTADO_FIRMA.txt').write_text('X39: APK firmado y verificado con apksigner. Comprueba la huella del certificado por un canal independiente.\n')
PY
# Descarta sellos del hash anterior; OpenTimestamps se intenta sobre las sumas nuevas.
rm -f -- "$X39_DIST/SHA256SUMS.txt.ots"
for X39_FILE in x39-app-latest.apk SHA256SUMS.txt FIRMA_APK.txt ESTADO_FIRMA.txt download-x39-app.html index.html; do
    install -m 0644 "$X39_TMP/$X39_FILE" "$X39_DIST/$X39_FILE"
done
if command -v ots >/dev/null 2>&1; then
    if (cd "$X39_DIST" && timeout 60 ots stamp SHA256SUMS.txt); then
        printf 'X39: OpenTimestamps ejecutado; conserva SHA256SUMS.txt.ots si se generó.\n'
    else
        printf 'X39: sello OTS pendiente. Reintenta: cd dist_web && ots stamp SHA256SUMS.txt\n' >&2
    fi
fi
printf 'X39: paquete firmado listo en %s\n' "$X39_DIST"
cat "$X39_DIST/SHA256SUMS.txt"
cat "$X39_DIST/FIRMA_APK.txt"
