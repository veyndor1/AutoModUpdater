# Bundled UI assets

- day.gif / night.gif: original pixel woodland artwork generated with the built-in
  Imagegen tool on 2026-10-14. Source atlas: design/assets/woodland-source.png.
  Exact prompt: design/assets/banner-prompt.txt.
  Packaging and ambient animation: scripts/BuildWoodlandBanner.java.
  Each theme is 640 x 214, 72 frames, 80 ms per frame; a seamless 5.76-second loop.
- day.png / night.png: static atlas crops for decoding fallback.
- Veyndor: local typographic wordmark with a pixel V, not a supplied official logo.
- manrope-regular.ttf / manrope-semibold.ttf: https://github.com/googlefonts/manrope/tree/master/fonts/ttf
  Supplied with OFL-Manrope.txt (SIL Open Font License).

Former third-party shared Minecraft GIFs are replaced. Artwork and fonts load from
this JAR without runtime network requests. No extracted Terraria game assets are used.
