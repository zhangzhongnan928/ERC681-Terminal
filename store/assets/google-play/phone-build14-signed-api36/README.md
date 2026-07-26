# Google Play phone screenshots — signed build 14

These five screenshots were captured on 26 July 2026 from the exact signed release APK:

- Source: `android/app/build/outputs/apk/release/app-release.apk`
- Application ID: `com.openpasskey.terminal`
- Version: `0.1.12` (`versionCode` 14)
- APK SHA-256:
  `4947b5691dd45300280970c98052103eed60b8fa06d676d2c2f587b922a44f05`
- Signer-certificate SHA-256:
  `CF:7D:C1:A7:CA:2A:17:67:23:64:67:6C:0E:96:36:E5:81:47:C6:E3:AB:7F:31:29:6C:32:D3:E7:B3:D6:9A:FE`
- Capture device: `sdk_gphone64_arm64` API 36 emulator at 1440 × 2560 and 288 dpi

The signed APK was installed with `adb install -r`, and Android's installed-package report
confirmed version code 14 and version name 0.1.12 before capture. The status bar was normalized to
10:00 with full Wi-Fi and battery indicators through Android's SystemUI demo commands. No app
content was modified.

Android produced 8-bit RGBA screencaptures with opaque alpha. FFmpeg converted them losslessly to
8-bit RGB PNGs by removing only the opaque alpha channel. RGB frame hashes were compared before
and after conversion. The images were not cropped, stretched, upscaled, framed, or given marketing
overlays.

All five final files are 1440 × 2560 portrait (9:16), 8-bit RGB PNGs with no alpha. They satisfy
Google Play's 320–3840 pixel and 2:1 requirements and its recommendation to provide at least four
portrait app screenshots at 1080 × 1920 or greater. The historical build-13 phone captures remain
unchanged in `../phone/`.

## Sequence and alt text

1. `01-cold-launch-reviewer-choice.png`
   - Cold-launch choice before any live-terminal component is opened.
   - Alt text: `OPK Terminal launch screen offering live merchant setup or an isolated offline reviewer demo.`
2. `02-offline-demo-checkout-waiting.png`
   - In-memory sample checkout with the persistent simulation banner, 1.00 USDC, Waiting state,
     and locally rendered dummy ERC-681 QR.
   - Alt text: `Offline Base Sepolia demo showing a simulated 1 USDC checkout, Waiting status, and dummy ERC-681 QR.`
3. `03-offline-demo-checkout-paid.png`
   - The same local sample after the explicit simulation action changes Waiting to Paid.
   - Alt text: `Offline Base Sepolia demo showing a locally simulated 1 USDC payment marked Paid.`
4. `04-offline-demo-history-paid.png`
   - In-memory history preview, clearly labeled as never saved.
   - Alt text: `Offline demo history showing a simulated paid Base Sepolia sample that is never saved.`
5. `05-offline-demo-settlement-disabled.png`
   - Preview-only settlement details using fixed dummy addresses; the settlement action is visibly
     disabled and no authentication, signing, RPC, or broadcast is available.
   - Alt text: `Offline settlement preview with dummy addresses and the settlement action disabled.`

The persistent banner in screenshots 2–5 states:
`OFFLINE DEMO · BASE SEPOLIA TESTNET · SIMULATED · NO NETWORK · NO REAL FUNDS`.
These screenshots do not depict a live payment, live token, real balance, or available settlement.

## SHA-256

```text
1419c2a55f7bf02542ec346735e5b52fa4365fbb383db9fb8b8e54851a360d45  01-cold-launch-reviewer-choice.png
41083942eacdae8bd86574c3574a62c397a6fb40ae9e1277b87cf3b26802678b  02-offline-demo-checkout-waiting.png
63ff750226996ddf7f29f5e9f704d5eeaa93f4009f8aea400ca6bf3f8131aa79  03-offline-demo-checkout-paid.png
71656a1d05b8d9b562241ec2c828c027020dfcfb2596e1b1b6e036092e5d72ca  04-offline-demo-history-paid.png
5605d65d799285c058d0af20032b97fbf37c433df9ea7c4017212c75867b554c  05-offline-demo-settlement-disabled.png
```
