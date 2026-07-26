# Google Play phone screenshots — signed AAB live first run

These five screenshots were captured on 26 July 2026 from the live merchant-terminal path in the
signed build 14 Google Play candidate. They do not use the offline product tour and do not depict
a provisioned terminal, a real payment, or a fabricated merchant configuration.

The exact release code and these screenshot PNGs are recorded by source/assets commit
`477817a59ecce16319fc13023fbe575e88ebd9a6`.

## AAB and installed-package provenance

- Google Play AAB:
  `artifacts/android/v0.1.12/OPK-Terminal-v0.1.12-build14-play-signed.aab`
- AAB SHA-256:
  `d6ed150884e21f1491ed9dba68bf249af77be9cd95989b265eb8ef7c6e896b76`
- Temporary device-specific APKS archive:
  `/tmp/opk-aab-install.gdDOJI/OPK-Terminal-build14.apks`
- APKS SHA-256:
  `a2d83a7da59f3dc68ee6ad0c7764e4b1e8ad72b1a2c30d4a1ceac2b9ac48529f`
- Application ID: `com.openpasskey.terminal`
- Installed version: `0.1.12` (`versionCode` 14)
- Installed SDK declaration: minimum SDK 26, target SDK 36
- Capture device: `sdk_gphone64_arm64` API 36 emulator, device ID `emulator-5556`
- Capture frame: 1440 × 2560 at 288 dpi

Bundletool 1.18.1 built the APKS archive with `build-apks`, the exact AAB above,
`--connected-device`, and `--device-id=emulator-5556`. Signing inputs were supplied externally and
are intentionally not recorded here. Bundletool then installed that archive with `install-apks`
and `--device-id=emulator-5556`.

The installed package contained the four device-specific splits below. Their bytes and SHA-256
hashes match the corresponding entries extracted from the APKS archive:

```text
314de74e302a7de9a029a538492e2fa50f3eaca7b014b634ad3ef9ce6475d978  base.apk / base-master.apk
b742094e18e01451d0643df08b48b10b74321ba9bde48607ab9cb999229d3701  split_config.arm64_v8a.apk / base-arm64_v8a.apk
b159c3987bd9942fd29b10b9d4ccb2370e5a64be8ac4b9e22fd1f3eb923baf10  split_config.en.apk / base-en.apk
e004b49ce207681d9379f02bb17577de4e8610b2c610e14ecc021129d701f7e9  split_config.xxhdpi.apk / base-xxhdpi.apk
```

The base split verifies with Android APK Signature Schemes v2 and v3. Its signer-certificate
SHA-256 is:
`CF:7D:C1:A7:CA:2A:17:67:23:64:67:6C:0E:96:36:E5:81:47:C6:E3:AB:7F:31:29:6C:32:D3:E7:B3:D6:9A:FE`.

## Capture state and image processing

The product tour was closed and **Set up / open terminal** was selected. The terminal was left in
its genuine first-run state: no operator wallet was created, no merchant provisioning was applied,
no invoice or payment was created, and no settlement was prepared or submitted. For screenshot 4,
the wallet-creation review dialog was opened but **Authenticate & create** was not pressed; the
dialog was canceled after capture.

The status bar was normalized to 10:00 with full Wi-Fi and battery indicators through Android
SystemUI demo commands. No app content was changed. Android produced opaque RGBA screenshots;
FFmpeg removed only the opaque alpha channel. The images were not cropped, resized, stretched,
framed, or given marketing overlays.

All five final files are 1440 × 2560 portrait, 8-bit RGB PNGs with no alpha.

## Sequence and represented states

1. `01-checkout-setup-required.png`
   - Live Checkout blocked at first run with **Create terminal wallet** and
     **Finish terminal setup**.
2. `02-empty-payment-history.png`
   - Genuine empty Payment History explaining where created ERC-681 requests and observed
     payments will appear.
3. `03-settlement-safety.png`
   - Genuine empty Settle screen with the simulation, review, device-authentication, confirmed
     receipt, and bounded reconciliation safety copy.
4. `04-protected-wallet-review.png`
   - Reversible pre-authentication review explaining Android Keystore protection and that the
     merchant passkey is never stored on the terminal.
5. `05-terminal-setup-privacy-support.png`
   - First-run Settings screen showing setup step 1 of 2, the protected-wallet action, and the
     accessible Privacy Policy and Support links.

## SHA-256

```text
8a56838da23a7742d2a309aeed6efacd8fb5e8679022e110bbeb4dffeeed3d62  01-checkout-setup-required.png
04b5e9923e238059ce570866b9e007f7d90865d1a6998209a972e66f1ef5c537  02-empty-payment-history.png
e359273e92b2ef422f1482a1a75c336f7afeb0b0fc6e752be96cc5a97a353e2b  03-settlement-safety.png
e18ef6a73462cae75829a97747630274c53b4aab0563dbf2742757602bd887e2  04-protected-wallet-review.png
78633ebaea3e6a272d3665a1326c6af28ed4ad9668b189e1a2be4a6416b4f2de  05-terminal-setup-privacy-support.png
```
