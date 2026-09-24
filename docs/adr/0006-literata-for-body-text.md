# Literata for body text, Akkurat for chrome

Book text is set in Literata (OFL; Google Play Books' reading face: true italics, screen-first), bundled
as four static files (Regular, Italic, Bold, BoldItalic, about 1 MB) with the OFL text shown in About.
Everything else (Shelf, top bar, controls) uses Light's system typeface, Akkurat, so the Tool still looks
like LightOS. There is no typeface setting.

Deliberately different from the obvious "use the platform font": Akkurat is a grotesque drawn for
interface work, its italic on retail LightOS can't be verified without hardware, and the emulator has no
Akkurat at all (it renders Roboto). Bundling the body face makes emulator measurements (characters per
line, line height, the 300 ms layout bar, hyphenation cost) valid for the font we ship.

N1 must first prove that Light's build plugin accepts `res/font`. If it rejects font resources, fall
back to Akkurat with synthetic italic and supersede this ADR.

## Considered Options

- Akkurat for body text, deciding at hardware testing: leaves every typographic measurement provisional
  until the last milestone.
- Source Serif 4: the alternative if Literata's texture looks wrong on the LP3 panel.
