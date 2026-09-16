# Changelog

## 1.0.0

First release of AltHalt for Minecraft Java 26.2, Fabric Loader 0.19.5 or newer, and Java 25. Fabric API is not required.

### Features

- Per-server UUID locks stop accidental joins with the wrong account.
- Same-IP checks compare fresh public-IP lookups against account history shared across launcher profiles on the same OS account.
- Account-verified status pings and Multiplayer Refresh support providers' refresh verification while blocking unconfigured or mismatched accounts.
- The Multiplayer management menu lists recent blocks, known alts, server locks, and active exemptions.
- One-hour and permanent exemptions are scoped to the account, server, and failed rule; Same-IP exemptions additionally cover only the recorded conflicting addresses.
- Alt labels and server locks can be edited. Account and lock removal require confirmation, and exemptions can be revoked.
- Local state uses process locking and atomic writes, preserves existing IP history when upgrading, and blocks connections when history cannot be safely read or saved.

### Installation and upgrade

Place `althalt-1.0.0.jar` in the Fabric instance's `mods` directory and remove any previous AltHalt JAR. Open **Multiplayer > AltHalt** to configure server locks and manage records. Existing development-version history is preserved and migrated when necessary.

### Protection limits

History begins with attempts observed by AltHalt. Background pings check the UUID only; login attempts also check public IPs unless explicitly exempted. IP lookups contact ipify, and routing or VPN changes can make a server see a different address. Realms and mods that replace vanilla connection paths are outside this release's protection. AltHalt is an accident-prevention tool and does not guarantee anonymity.

### Validation

35 automated tests cover account locks, IP collisions, persistence, concurrent writes, history migration, exemption scope and expiry, and management changes. In-game smoke tests use simulated IP lookups and a local server fixture to exercise blocked and approved connections, status pings and refreshes, cancellation, management controls, exemptions, and removal confirmations. These checks do not establish live-server VPN behavior or anonymity.
