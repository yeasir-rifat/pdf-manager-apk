#!/usr/bin/env bash
# Makes the permanent release keystore ONCE and prints the four values to put into GitHub Secrets.
# Keep release.jks and the passwords somewhere safe (a password manager). If you lose them,
# nobody can install your updates over the old app any more.
set -euo pipefail

KEYSTORE="${1:-release.jks}"
ALIAS="${2:-pdfmanager}"

if [ -e "$KEYSTORE" ]; then
  echo "$KEYSTORE already exists. Not overwriting it." >&2
  exit 1
fi

read -r -s -p "Choose a keystore password (at least 6 characters): " PASS; echo
read -r -s -p "Repeat it: " PASS2; echo
[ "$PASS" = "$PASS2" ] || { echo "Passwords differ." >&2; exit 1; }

keytool -genkeypair -v -keystore "$KEYSTORE" -alias "$ALIAS" \
  -keyalg RSA -keysize 4096 -validity 36500 \
  -storepass "$PASS" -keypass "$PASS" \
  -dname "CN=PDF Manager, O=PDF Manager, C=BD"

echo
echo "Done. Now open GitHub > Settings > Secrets and variables > Actions and add:"
echo "  KEYSTORE_PASSWORD = (the password you typed)"
echo "  KEY_PASSWORD      = (the same password)"
echo "  KEY_ALIAS         = $ALIAS"
echo "  KEYSTORE_BASE64   = (the long text below)"
echo
if base64 --help 2>&1 | grep -q -- '-w'; then base64 -w 0 "$KEYSTORE"; else base64 "$KEYSTORE" | tr -d '\n'; fi
echo
echo
echo "Never commit $KEYSTORE to git."
