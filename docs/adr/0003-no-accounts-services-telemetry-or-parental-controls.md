# No accounts, hosted services, telemetry, or parental controls

The Tool has no accounts and no maintainer-hosted service, and it sends no telemetry. It makes network
calls only to fetch a Catalogue and download a Book. Reading works fully offline. There are no parental
controls, PINs, or locked sources. Curation works by removing Catalogues (including the shipped ones) and
adding your own. Moving reading data between phones works by export/import of a local file, not by sync.

These are trust promises to people who chose a Light Phone to escape attention-harvesting software, and a
promise like this is hard to take back once made. The phone itself is the parental control; building
controls into the Tool would turn it into a kids' product and burden its adult audience.

## Considered Options

- Cloud sync with accounts: a service to run, a liability, and against Light's no-accounts ethos. If sync is
  ever demanded, adopt an existing self-hostable protocol (KOReader's progress sync) instead.
- Parental PIN / locked Catalogues: a slippery slope for little gain on a parent-managed phone.
