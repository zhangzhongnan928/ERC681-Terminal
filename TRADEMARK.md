# Trademark and brand policy

The source code in this repository is licensed under the Apache License,
Version 2.0. **Trademarks and brand assets are not.** The Apache License
grants copyright and patent rights; section 6 expressly does not grant
trademark rights. This document states what is reserved.

## Ownership

The code copyright is held by Victor Zhang. The marks and brand assets
described below are the property of **OpenPasskey Pty Ltd (ACN 688 670
420)** and are reserved by that company.

## What is reserved

**Names and marks**

- OPK
- OPK Pay
- OPK Terminal
- OpenPasskey

**Brand assets in this repository**

- `ios/App/Resources/AppIcon.svg`
- `ios/App/Resources/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png`
- `android/app/src/main/res/mipmap-*/ic_launcher*`
- `store/assets/google-play/store-icon-512.png`
- `store/assets/google-play/feature-graphic.svg`
- `store/assets/google-play/feature-graphic-1024x500.png`
- All product screenshots under `store/assets/`

These files are present so the repository builds and so the published
build is reproducible and reviewable. Their presence is not a license to
use them.

## What you may do

- Use, modify, and distribute the code under the Apache License 2.0.
- State factually that your work is derived from, based on, or compatible
  with ERC-681 Terminal or the OPK protocol. Nominative, factual reference
  is fine.
- Implement the wire formats. The ERC-681 payment URI grammar, the
  `opk-terminal:provision` payload, and the conformance vectors in
  `conformance/` are specifications, not brand. Interoperate freely.

## What you may not do

- Ship a fork under the name OPK, OPK Pay, OPK Terminal, or OpenPasskey,
  or under any name confusingly similar to them.
- Ship a fork using the reserved icons, logo, or feature graphic, or a
  confusingly similar derivative of them.
- Publish to an app store in a way that suggests your build is the
  official OPK Terminal, or that it is endorsed by, affiliated with, or
  supported by OpenPasskey Pty Ltd.

**If you fork and distribute, replace the app name, application ID or
bundle identifier, and icon assets with your own.** The store listing must
make clear that your build is yours.

## Questions

Ask before assuming: [v@openpasskey.com](mailto:v@openpasskey.com).
Permission beyond this policy is generally available on request; the
reservation exists to prevent user confusion in app stores, not to
discourage forks.
