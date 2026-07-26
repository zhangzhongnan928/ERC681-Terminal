# App Store screenshots

## Build-14 merchant-core simulator candidates

The merchant-core sets under `build-14-core/` were captured on 26 July 2026 from the OPK Terminal
`0.1.12` (`CURRENT_PROJECT_VERSION 14`) simulator app whose exact source and screenshot assets are
recorded by commit `477817a59ecce16319fc13023fbe575e88ebd9a6`.

- iPhone set: 5 portrait PNGs at 1320 × 2868, captured on an iPhone 17 Pro Max simulator,
  iOS 26.5.
- iPad set: 5 portrait PNGs at 2064 × 2752, captured on an iPad Pro 13-inch (M5) simulator,
  iOS 26.5.
- Format: 8-bit RGB PNG, no alpha.
- Clean dedicated UI-test reruns passed on both devices. Every exported file is byte-identical to
  its kept non-failure XCTest attachment.
- Sequence: genuine first-run Checkout, empty History, settlement safety, Terminal Setup, and
  Privacy/Support. No operator wallet, merchant provisioning, invoice, payment, or settlement was
  created.
- The images were visually inspected together after the clean reruns. They contain no notification,
  alert, keyboard, clipped content, personal data, or fabricated ready state.
- On iPad, screens 4 and 5 are intentionally byte-identical because the full Terminal Setup screen
  already includes Privacy and Support without scrolling. Upload screen 4 only; the duplicate is
  retained as capture evidence.
- These are truthful build-14 simulator candidates, not signed-archive evidence. Compare them with
  the eventual signed App Store archive before replacing the uploaded build-13 set.

### Build-14 merchant-core SHA-256

```text
41ffa09bf0d5b0f034e2933f58e69478abfc1bd236475df37b18d34c0747d196  build-14-core/iphone-6.9/01-first-run-checkout.png
c2ebf527c663bc8d9e445fe399d93cdc4e54e50ba5e9ce9107c480ac49f5df1e  build-14-core/iphone-6.9/02-history.png
b0246babfdc8330303e08d08d12ffdea92a5045a6b1664caddf12ae80b0e0870  build-14-core/iphone-6.9/03-settlement.png
d056d0e52194ba7e0a5c8c1c50e9fa189336e81c7d5b8ff108a495967f867bb2  build-14-core/iphone-6.9/04-terminal-setup.png
c0e0764f662821cee0ebe4206c85d07de05dd0fff796f8b40a6a2dae0b529603  build-14-core/iphone-6.9/05-privacy-support.png
fcf2decef8fad540bbe21e3c6e03fb6ce3ac795e67e86db8f9f31ac83a2fdf6d  build-14-core/ipad-13/01-first-run-checkout.png
d1da0b1e33bd79063b4eeb09be222ead885ad92a383fe88ee2e248987002d1bd  build-14-core/ipad-13/02-history.png
07e8cee0ec912e08b32095808c1a26a1ce34912ccde15159ec712319af91520d  build-14-core/ipad-13/03-settlement.png
72fa2329e0e86bb76ada943a7470572382a86bdf429e8ed3716f610de5a06d3b  build-14-core/ipad-13/04-terminal-setup.png
72fa2329e0e86bb76ada943a7470572382a86bdf429e8ed3716f610de5a06d3b  build-14-core/ipad-13/05-privacy-support.png
```

## Build-14 product-tour simulator candidates

The candidate sets under `build-14/` were captured on 26 July 2026 from the OPK Terminal
`0.1.12` (`CURRENT_PROJECT_VERSION 14`) simulator app built from source revision
`477817a59ecce16319fc13023fbe575e88ebd9a6`.

- iPhone set: 5 portrait PNGs at 1320 × 2868, captured on an iPhone 17 Pro Max simulator,
  iOS 26.5.
- iPad set: 5 portrait PNGs at 2064 × 2752, captured on an iPad Pro 13-inch (M5) simulator,
  iOS 26.5.
- Format: 8-bit RGB PNG, no alpha.
- The dedicated `testCaptureOfflineReviewerDemoStoreScreenshots` UI test passed independently on
  both devices. The iPad set's five attachments all came from one passing run.
- The test forbids live bootstrap. Screen 1 shows the cold-launch choice; screens 2–5 show the
  isolated, in-memory offline product tour with the persistent
  `OFFLINE DEMO · BASE SEPOLIA TESTNET · SIMULATED · NO NETWORK · NO REAL FUNDS` banner.
- Sequence: cold-launch choice, simulated checkout waiting, simulated checkout paid, in-memory
  demo history, and disabled settlement preview.
- The images were visually inspected at capture time. They contain fixed dummy demo data and no
  live RPC, signing, broadcast, production data, or personal data.
- These are truthful build-14 simulator candidates, not signed-archive evidence. Preserve the
  uploaded build-13 assets below and compare these screens with the eventual signed App Store
  archive before replacing anything in App Store Connect.

### Build-14 SHA-256

```text
895e4bef25459f01d6728677092079ce6ec78dcb5eb294abd106034e6f398cd2  build-14/iphone-6.9/01-cold-launch-choice.png
51508a88b55c2601edc4697831671faec360c67f47111bc878fab6fcb09ecba3  build-14/iphone-6.9/02-offline-demo-waiting.png
30a73209bfec67bfad953b03d71b33d1464a9cabf3be57121abec299ffc8aac9  build-14/iphone-6.9/03-offline-demo-paid.png
7408ccb64ceef7198508b818c59eccb7cb5cbaffd97cc9d850b5bd4902f73b29  build-14/iphone-6.9/04-offline-demo-history.png
93ed6bc0b3b9837c429c0368f81b77cfcb1902c19197da0e753fb386412084c2  build-14/iphone-6.9/05-offline-demo-settlement.png
e058433c453f81602f6e4b2b4392b98f6c3b0081fd09ead313d7d0237b52a9e0  build-14/ipad-13/01-cold-launch-choice.png
5b7f0c0b664f04a81c87d7ad93784c49ef5a0c83da7318d3589ecb14ca6626ec  build-14/ipad-13/02-offline-demo-waiting.png
88e2c368d699cded677db41591af33dc867cbfba78088dcf1e0d1cbe62d53a21  build-14/ipad-13/03-offline-demo-paid.png
b4f449491146de955853d8a6e19407ed3d8c88afa3253f03195989c20c57eec7  build-14/ipad-13/04-offline-demo-history.png
6ad783991cb6a04f6990406afd0dabb9596aec2355c8d8fe0036a95d5a6eabf2  build-14/ipad-13/05-offline-demo-settlement.png
```

## Uploaded build-13 historical sets

These screenshots were captured on 24 July 2026 from the OPK Terminal `0.1.12`
(`CURRENT_PROJECT_VERSION 13`) simulator product generated from the then-current source snapshot.

- iPhone set: 5 portrait PNGs at 1320 × 2868, captured on an iPhone 16 Pro Max simulator.
- iPad set: 4 portrait PNGs at 2064 × 2752, captured on an iPad Pro 13-inch (M4), iOS 18.4.
- Format: 8-bit RGB PNG, no alpha.
- Content: genuine first-run Checkout, History, Settlement, and Terminal Setup screens. No
  production data, personal data, fabricated provisioning, or live payment QR is shown.
- The iPhone screenshot UI test passed. On the iOS 18.4 iPad runtime, the XCTest runner failed to
  launch with `NSPOSIXErrorDomain code 1`; the already-built app product was installed directly,
  launched successfully, and captured through Simulator instead.
- These captures predate the build-14 cold-launch choice. Preserve their build-13 provenance; they
  may depict live-terminal feature screens but must not be described as build-14 first-run
  captures.
- Recheck or recapture every screen against the archived build-14 App Store candidate before
  console submission.

### Historical build-13 SHA-256

```text
cc268cb910482565c760a6c7f8a512884de49207f28e3b25d157cb42656eac20  iphone-6.9/01-first-run-checkout.png
ad1d5f38125b5ab761a0a05e2e4190ee7afbb59e9531dfe68f329ee59c6b5550  iphone-6.9/02-history.png
8ca679981b58ba00e5d6eee5c95f528064606689dd3ed5b09e4df17ad1623c56  iphone-6.9/03-settlement.png
93d2268a63897a4a7376c25c8041cd66acbbb23e4ae182716352fe29c6e49734  iphone-6.9/04-terminal-setup.png
42c78f3ece6bdfe70f5ac877dee778089fd16280a03f71e0d82fc38e3b9883d4  iphone-6.9/05-privacy-support.png
14a87342bb5409bddaf7223a132975b03017a987f0f73cf10e178a22f1c7e6cf  ipad-13/01-first-run-checkout.png
632fcfccee0be8a1741e98ec17836f5481c18dea12be51fc48f87797adfde23c  ipad-13/02-empty-history.png
ae341115546f2db2d7c12c448a64ee79d626f014143304463014ba2282f63286  ipad-13/03-empty-settlement.png
00072287aab5b308eb9ee3a9ec186d012a6f6e2671f7cb02c7e6e1251c9b5b79  ipad-13/04-terminal-setup.png
```
