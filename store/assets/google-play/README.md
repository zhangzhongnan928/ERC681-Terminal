# Google Play listing artwork

These files are deterministic exports for the OPK Terminal Google Play listing. They use the
approved geometry and two-colour palette from `ios/App/Resources/AppIcon.svg`; the feature graphic
adds only the accurate listing copy “OPK Terminal” and “Base Sepolia merchant testnet terminal.”
It contains no live or fabricated payment QR.

## Files

| File | Purpose | Validated properties |
| --- | --- | --- |
| `store-icon-512.png` | Google Play store icon | 512 × 512, 8-bit RGBA PNG, alpha present and fully opaque, 3,608 bytes |
| `feature-graphic-1024x500.png` | Google Play feature graphic | 1024 × 500, 8-bit RGB PNG, no alpha, 19,284 bytes |
| `feature-graphic.svg` | Editable feature-graphic source | 1024 × 500 SVG; only `#1A1A2E` and `#00D4AA` are specified |
| `phone-build14-signed-api36/*.png` | Signed build-14 phone screenshots | Five 1440 × 2560 8-bit RGB PNGs, no alpha; exact signed `0.1.12` version-code-14 APK |

The store icon is a pixel-identical RGB rendering of the approved source icon at 512 × 512. Its
alpha channel is deliberately retained for Play's 32-bit PNG requirement, with every alpha sample
set to 255. The feature graphic has an opaque `#1A1A2E` background and no alpha channel.

The signed build-14 screenshot set has its capture provenance, truthful state descriptions, alt
text, conversion checks, and SHA-256 hashes in `phone-build14-signed-api36/README.md`. Existing
`phone/` images are preserved as historical build-13 captures and were not overwritten.

## Rebuild

These release exports were rendered with librsvg 2.62.2 and FFmpeg 7.1.1:

```sh
rsvg-convert -w 512 -h 512 -o /tmp/store-icon-rgb.png ios/App/Resources/AppIcon.svg
ffmpeg -hide_banner -loglevel error -y -i /tmp/store-icon-rgb.png \
  -frames:v 1 -pix_fmt rgba store/assets/google-play/store-icon-512.png

rsvg-convert -w 1024 -h 500 -o /tmp/feature-graphic-rgba.png \
  store/assets/google-play/feature-graphic.svg
ffmpeg -hide_banner -loglevel error -y -i /tmp/feature-graphic-rgba.png \
  -frames:v 1 -pix_fmt rgb24 store/assets/google-play/feature-graphic-1024x500.png
```

The editable feature graphic uses Arial for the listing copy. Review the raster export, rather
than depending on another host's font substitution, before uploading a regenerated file.

## SHA-256

```text
488ad80dadba9c1dc199dfc7f54fada43d37ac98232a81fb5cebb1cd81987553  ios/App/Resources/AppIcon.svg
cf53f6614f507ffbdc25abdec9cc7ce38c69df46ec235ccb4580a0cbd3acdbf2  store/assets/google-play/feature-graphic.svg
e602123b9f83cf74df31704b360ada8710e736ef58a4ba164dc1c8d2e9a95a27  store/assets/google-play/store-icon-512.png
9cef5091572c484686b5d10781925224ccc07ab32d4a0432391c8ab53fa92d62  store/assets/google-play/feature-graphic-1024x500.png
```
