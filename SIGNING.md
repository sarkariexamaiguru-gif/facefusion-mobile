# Signing

This repo builds an **unsigned** release APK (see `.github/workflows/build-apk.yml`). To install
it, and to update an already-installed copy without uninstalling, sign it with the project's
release keystore.

Android identifies an app by its application id **and its signing certificate**. A build signed
with a different key cannot update an installed one — Android forces an uninstall first, which
wipes the app's private data (the downloaded models). So every build that must update an existing
install has to be signed with the **same** keystore.

- Keystore: `ff-release.jks` (never committed)
- Key alias: `facefusion`
- Certificate: RSA 4096, SHA-256 `E4:33:4C:24:16:F5:A6:7F:9F:AE:DC:19:0B:B3:72:89:A1:B3:93:20:D3:F8:48:6B:7D:AD:22:AA:1F:B8:2F:2C`
- Passwords: kept out of the repo. Use `keystore.properties` locally or repo secrets in CI.

## Sign a built APK

```sh
zipalign -p -f 4 app-release-unsigned.apk aligned.apk
apksigner sign --ks ff-release.jks --ks-key-alias facefusion \
  --ks-pass pass:YOUR_STORE_PASS --key-pass pass:YOUR_KEY_PASS --out signed.apk aligned.apk
apksigner verify --print-certs signed.apk
```

Or use `sign.sh`:

```sh
KEYSTORE=ff-release.jks KS_PASS=... KEY_ALIAS=facefusion KEY_PASS=... ./sign.sh app-release-unsigned.apk signed.apk
```

## Sign inside CI

Add repository secrets, then write `keystore.properties` before the Gradle step and Gradle signs
the release build for you:

- `KEYSTORE_BASE64` = output of `base64 -w0 ff-release.jks`
- `KEYSTORE_PASSWORD`, `KEY_ALIAS` (`facefusion`), `KEY_PASSWORD`

```yaml
- name: Decode keystore
  env:
    KS_B64: ${{ secrets.KEYSTORE_BASE64 }}
  run: |
    if [ -n "$KS_B64" ]; then
      echo "$KS_B64" | base64 -d > work/android/ff-release.jks
      printf 'storeFile=ff-release.jks\nstorePassword=%s\nkeyAlias=%s\nkeyPassword=%s\n' \
        "${{ secrets.KEYSTORE_PASSWORD }}" "${{ secrets.KEY_ALIAS }}" "${{ secrets.KEY_PASSWORD }}" \
        > work/android/keystore.properties
    fi
```

Keep `ff-release.jks` backed up and out of the repository.
