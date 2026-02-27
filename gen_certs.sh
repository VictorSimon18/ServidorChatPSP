#!/usr/bin/env bash
# =============================================================================
# gen_certs.sh — Genera los certificados TLS para ServidorChat y ClienteChat
# =============================================================================
#
# REQUISITOS: keytool (incluido en cualquier JDK, debe estar en el PATH)
#
# SALIDA:
#   certs/keystore.jks   → keystore del servidor (clave privada + certificado)
#   certs/servidor.cer   → certificado público del servidor (formato PEM)
#   certs/truststore.jks → truststore del cliente (solo el certificado público)
#
# INSTRUCCIONES:
#   1. Ejecuta este script UNA SOLA VEZ desde la raíz de ServidorChatPSP:
#          bash gen_certs.sh
#   2. Copia certs/truststore.jks al proyecto ClienteChatPSP:
#          cp certs/truststore.jks ../ClienteChatPSP/certs/truststore.jks
#      (el script lo hace automáticamente si el directorio existe)
#   3. ¡NO subas los archivos .jks a git! (ya están en .gitignore)
#
# NOTA: El certificado solo es válido para 'localhost' y '127.0.0.1'.
#       Si necesitas conectar desde otra máquina, añade tu IP a la variable
#       SAN_EXTRA y vuelve a ejecutar el script.
# =============================================================================

set -euo pipefail

# ── Configuración ─────────────────────────────────────────────────────────────
ALIAS="servidor-chat"
KEYSTORE_PASSWORD="changeit"
VALIDITY=3650   # días (≈ 10 años)

KEYSTORE_FILE="certs/keystore.jks"
CERT_FILE="certs/servidor.cer"
TRUSTSTORE_FILE="certs/truststore.jks"

# Subject del certificado
DNAME="CN=localhost, OU=ChatPSP, O=Universidad, L=Madrid, ST=Madrid, C=ES"

# SANs: cubre localhost y 127.0.0.1 para desarrollo local
# Añade más entradas si necesitas conectar desde otra red, p.ej.:
#   SAN="DNS:localhost,DNS:mi-servidor,IP:127.0.0.1,IP:192.168.1.10"
SAN="DNS:localhost,IP:127.0.0.1"

# Ruta al ClienteChatPSP (para copiar el truststore automáticamente)
CLIENTE_CERTS="../ClienteChatPSP/certs"

# ── Comprobaciones previas ───────────────────────────────────────────────────
if ! command -v keytool &> /dev/null; then
    echo "ERROR: 'keytool' no encontrado. Asegúrate de que el JDK está en el PATH."
    exit 1
fi

# ── Preparar directorio ───────────────────────────────────────────────────────
mkdir -p certs

# Eliminar archivos anteriores para que keytool no pregunte por sobreescribir
rm -f "$KEYSTORE_FILE" "$CERT_FILE" "$TRUSTSTORE_FILE"

echo "================================================================"
echo " Generando certificado TLS autofirmado para ServidorChat"
echo "================================================================"

# ── PASO 1: Generar par RSA-2048 + certificado autofirmado ────────────────────
echo ""
echo "[1/3] Generando keystore del servidor..."
keytool -genkeypair \
    -alias        "$ALIAS" \
    -keyalg       RSA \
    -keysize      2048 \
    -sigalg       SHA256withRSA \
    -validity     "$VALIDITY" \
    -keystore     "$KEYSTORE_FILE" \
    -storepass    "$KEYSTORE_PASSWORD" \
    -keypass      "$KEYSTORE_PASSWORD" \
    -dname        "$DNAME" \
    -ext          "SAN=$SAN"
echo "    [OK] $KEYSTORE_FILE"

# ── PASO 2: Exportar el certificado público en formato PEM ───────────────────
echo ""
echo "[2/3] Exportando certificado público..."
keytool -exportcert \
    -alias     "$ALIAS" \
    -keystore  "$KEYSTORE_FILE" \
    -storepass "$KEYSTORE_PASSWORD" \
    -file      "$CERT_FILE" \
    -rfc
echo "    [OK] $CERT_FILE"

# ── PASO 3: Crear truststore del cliente con el certificado del servidor ──────
echo ""
echo "[3/3] Creando truststore del cliente..."
keytool -importcert \
    -alias     "$ALIAS" \
    -file      "$CERT_FILE" \
    -keystore  "$TRUSTSTORE_FILE" \
    -storepass "$KEYSTORE_PASSWORD" \
    -noprompt
echo "    [OK] $TRUSTSTORE_FILE"

# ── PASO 4 (opcional): Copiar truststore al proyecto cliente ──────────────────
echo ""
if [ -d "$(dirname "$CLIENTE_CERTS")" ]; then
    mkdir -p "$CLIENTE_CERTS"
    cp "$TRUSTSTORE_FILE" "$CLIENTE_CERTS/truststore.jks"
    echo "[AUTO] Truststore copiado a $CLIENTE_CERTS/truststore.jks"
else
    echo "[INFO] No se encontró $CLIENTE_CERTS."
    echo "       Copia manualmente: cp $TRUSTSTORE_FILE <ruta-cliente>/certs/truststore.jks"
fi

echo ""
echo "================================================================"
echo " Certificados generados correctamente en certs/"
echo "================================================================"
echo ""
echo "  Servidor usa : $KEYSTORE_FILE   (clave privada + certificado)"
echo "  Cliente usa  : $TRUSTSTORE_FILE (solo certificado público)"
echo ""
echo "  Válido para  : $SAN"
echo "  Contraseña   : $KEYSTORE_PASSWORD  (configurable con -Dssl.keystore.password=...)"
echo ""
echo "  IMPORTANTE: Los archivos .jks están en .gitignore. No los subas a git."
echo ""
