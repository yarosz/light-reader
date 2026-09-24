# Literata for body text, Akkurat for chrome

Book text is set in Literata (OFL; Google Play Books' reading face: true italics, screen-first), bundled
as four static files (Regular, Italic, Bold, BoldItalic, about 1 MB) with the OFL text shown in About.
Everything else (Shelf, top bar, controls) uses Light's system typeface, Akkurat, so the Tool still looks
like LightOS. There is no typeface setting.

Deliberately different from the obvious "use the platform font", on two grounds:

1. **Reading texture:** Literata is a text serif drawn for long-form reading; Akkurat is a grotesque drawn
   for interface work.
2. **Emulator fidelity:** the emulator has no Akkurat (it renders Roboto). Bundling the body face makes
   emulator measurements (characters per line, line height, layout time, hyphenation cost) valid for the
   font we ship.

Verified on retail hardware (LightOS 582): Akkurat does ship true italics (`AkkuratLLTT-` Regular, Italic,
Bold, BoldItalic, Light, LightItalic, Thin, ThinItalic, Black, BlackItalic), so an earlier concern that its
italic might be synthetic doesn't apply. Light's plugin accepts `res/font` (verified in N1).

**Revisit if** Light's review asks tools to use the system typeface. The body typeface is a single
constant, so that switch is one line.

## Considered Options

- Akkurat for body text, deciding at hardware testing: leaves every typographic measurement provisional
  until the last milestone.
- Source Serif 4: the alternative if Literata's texture looks wrong on the LP3 panel.
