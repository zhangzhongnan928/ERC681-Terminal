# Google Play upload signing

Google Play requires a signed Android App Bundle. OPK Terminal deliberately keeps the upload
keystore and its passwords outside source control.

## One-time owner setup

1. Confirm the Google Play developer account and legal publisher before creating an upload key.
2. Create a dedicated Play upload key in a password-protected PKCS12 or JKS keystore outside this
   repository, then back it up in the organisation's credential vault. Store the shared store/key
   password in a separate external file containing only that password.
3. Copy `android/key.properties.example` to the ignored `android/key.properties`, replace every
   placeholder, and set the keystore, password file, and properties file to mode `600`.
4. Enrol the public certificate as the app's upload certificate when Play Console requests it.

Do not use the Android debug key, the Play-managed app-signing key, or a keystore/password file
checked into the repository. Do not put the password directly in `key.properties` or pass it as a
command-line or Gradle `-P` value.

## Build

From `android/`:

```sh
./gradlew --no-daemon --rerun-tasks \
  :app:lintRelease \
  :app:bundleRelease \
  -PopkPlaySigning=true
```

The explicit flag is required. Without it, Gradle never reads `key.properties` and the ordinary
release build remains unsigned.

## Verify

```sh
jarsigner -verify -verbose -certs \
  app/build/outputs/bundle/release/app-release.aab

keytool -printcert -jarfile \
  app/build/outputs/bundle/release/app-release.aab

keytool -list -v \
  -keystore /absolute/path/outside/repository/opk-terminal-upload.p12 \
  -storetype PKCS12 \
  -alias opk-terminal-upload
```

Enter the keystore password only at the interactive prompt. The AAB certificate and keystore
certificate must have the same SHA-256 fingerprint.
