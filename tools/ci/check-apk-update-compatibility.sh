#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 3 ]]; then
  echo "Usage: $0 <older-project-signed.apk> <newer-project-signed.apk> <debug-apk>" >&2
  exit 2
fi

older_apk=$1
newer_apk=$2
debug_apk=$3

cert_digest() {
  apksigner verify --print-certs "$1" \
    | sed -n 's/^Signer #1 certificate SHA-256 digest: //p' \
    | head -n 1
}

version_code() {
  apkanalyzer manifest version-code "$1"
}

older_cert=$(cert_digest "$older_apk")
newer_cert=$(cert_digest "$newer_apk")
debug_cert=$(cert_digest "$debug_apk")
[[ -n "$older_cert" && "$older_cert" == "$newer_cert" ]] || {
  echo "Project-signed APK certificates do not match" >&2
  exit 1
}
[[ -n "$debug_cert" && "$debug_cert" != "$newer_cert" ]] || {
  echo "Expected debug APK to have a different certificate" >&2
  exit 1
}

older_version=$(version_code "$older_apk")
newer_version=$(version_code "$newer_apk")
[[ "$older_version" =~ ^[0-9]+$ && "$newer_version" =~ ^[0-9]+$ ]] || {
  echo "Could not read APK version codes" >&2
  exit 1
}
(( newer_version > older_version )) || {
  echo "Newer APK version code ($newer_version) must exceed older ($older_version)" >&2
  exit 1
}

echo "Compatible update candidate: same SHA-256 signer $newer_cert; versionCode $older_version -> $newer_version."
echo "Rollback candidate: installing versionCode $older_version over $newer_version is a downgrade and Android rejects it without an explicit downgrade override."
echo "Certificate-mismatch candidate: debug signer differs; Android rejects an update across signing certificates."
echo "Metadata only: this check does not perform PackageInstaller/device installation or prove data retention."
