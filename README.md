# AltHalt

A **client-side Fabric mod for Minecraft Java 26.2** that checks your account and public IP before connecting to a multiplayer server. Requires Java 25 and Fabric Loader 0.19.5 or newer. Fabric API is not required.

## Install and configure

1. Build with `./gradlew build` (Windows: `./gradlew.bat build`) using JDK 25.
2. Copy `build/libs/althalt-1.0.0.jar` into the Fabric instance's `mods` directory, replacing the previous AltHalt JAR. Prebuilt JARs are available from [GitHub Releases](https://github.com/GoodrichDev/AltHalt/releases). Do not install the sources JAR.
3. Open **Multiplayer**, select a saved server, click **AltHalt** at the top right, then **Selected server**. Use **Add lock** to enter another address, including one used with Direct Connect.
4. Enter the allowed account UUID, or click **Use current account**, then **Save UUID lock**.
5. Join normally. AltHalt checks the UUID, obtains fresh public IP information, compares the history, saves the new history, and only then starts the connection.

Switch accounts through your launcher or account manager. AltHalt does not sign into, store credentials for, or switch Minecraft accounts.

## Rules

- **UUID Locked:** each server address and port permits one configured UUID by default. Unconfigured servers are blocked unless explicitly exempted. Changing a lock does not erase either account's history.
- **Same-IP:** an IP recorded for one UUID cannot be used by another UUID, **across all servers**. A UUID may reuse its own IPs and accumulate new ones. IPs are reserved before the connection, so even a subsequent failed login retains its history.
- **Fail closed:** unavailable IP lookups, malformed responses, changing local interfaces, corrupt history, storage errors, and concurrent access prevent login and show a warning. Explicit scoped exemptions can override UUID, Same-IP, or IP-lookup failures; storage and invalid-account errors cannot be exempted.
- **Account-verified background pings:** servers whose saved UUID matches your current account use normal status pings and Multiplayer **Refresh**, allowing providers' refresh verification to work. Unconfigured servers, UUID mismatches, and unreadable history keep pings blocked. Pings check the UUID only and expose your current IP to that server; the full Same-IP check still runs before login. LAN discovery remains vanilla.

The standard connection entry point covers saved servers, Direct Connect, LAN joins, Quick Play, and server transfers. Transfers are checked against the **destination address**, not the originating server's saved information. A destination needs its own lock. Case, a trailing DNS dot, and omitted default port normalize to the same server key; aliases and different ports need separate locks.

## Management menu

Open **Multiplayer > AltHalt** to browse four paginated tabs:

- **Blocks:** the 100 most recent distinct blocks, with time, account, server, reason, and whether it was a login or background ping. Repeated blocks are grouped with an attempt count. Open a row to grant **Exempt 1 hour** or **Exempt forever**, then retry manually. Recent block tracking begins with this version.
- **Alts:** all known account records, with the currently logged-in UUID marked **Current**, plus lock and IP counts. Open an alt to inspect its recorded addresses, edit its local display label, or remove it. **Add alt** registers a UUID and optional label; UUIDs identify accounts and remain fixed when editing a label. This is a local registry, not an online-presence list or account switcher.
- **Locks:** inspect, edit (including the address or assigned UUID), or remove each server lock. Removing a lock revokes all exemptions for that address and blocks it until reconfigured, while retaining account IP history.
- **Exemptions:** inspect active one-hour and permanent exemptions and revoke them at any time. Expiry is enforced on every check, including after restarting Minecraft. Reopen the tab to refresh the displayed list.

Exemptions always apply to **one account, one server, and one failed rule**. A Same-IP exemption additionally covers only the conflicting IP addresses in that block; other conflicting addresses still block. The addresses are still recorded for both accounts, even after the exemption expires. A UUID exemption also permits that account's background pings; changing the server lock revokes its UUID exemptions. An IP-lookup exemption only permits joining if the lookup fails: it cannot compare or record an unknown IP, and a successful lookup still runs Same-IP checks.

Removing an alt requires an in-game confirmation because it erases that account's IP history, its server locks, associated exemptions, and its recent blocks. Forgotten addresses no longer protect that alt from reuse. Cancelling the confirmation keeps the records intact.

## Storage and privacy

State is stored in **`~/.althalt/state.json`** (`%USERPROFILE%\.althalt\state.json` on Windows), shared by launcher profiles using the same OS home directory. It contains server locks, account UUIDs, names and labels, recorded IP addresses, recent blocks, and scoped exemptions. It contains no access tokens or passwords. It is a sensitive local file; protect its backups accordingly.

Writes use a process lock, flush the new file, and atomically replace the old one. A damaged file is never silently reset. Back it up before manual changes; restore a known-good backup if damaged. Deleting the file removes protection history. Different computers or OS users do not automatically share history. Existing version-1 history is read and upgraded to version 2 on the next saved change, preserving locks and IPs. Older AltHalt releases cannot read the upgraded format. If storage is unavailable, a block may also be unable to save its recent-history entry; the connection remains blocked.

## What IP protection can and cannot establish

AltHalt uses fresh direct HTTPS requests to [ipify](https://www.ipify.org/): `api.ipify.org` for IPv4 and `api6.ipify.org` when an active interface has a global IPv6 address. Both required checks must succeed. IPv4 service is required; IPv6-only networks are currently blocked. Requests have timeouts, redirects are rejected, and IP responses are parsed as literals without DNS. Only the network request and an AltHalt user-agent reach ipify; account UUIDs, names, server addresses, and history are not sent to it.

**This is an accident-prevention tool, not an anonymity guarantee.** History only includes attempts checked by AltHalt since installation; it cannot discover accounts' prior IP use. An external IP service cannot prove the address a specific Minecraft server sees. VPN split routing, per-destination routing, SOCKS proxies, NAT66, multiple egress routes, or changes after the check can produce a different address. The mod does not monitor an established connection or change your network routing. IPv6 checks use exact addresses, not shared prefixes.

Realms uses a separate connection path and is outside this version's protection. Mods that replace vanilla connection code or change account identity during login are also outside the supported scope. Removing or disabling AltHalt removes its checks. Use it in every launcher profile you want protected.

## Development and verification

The build follows [Fabric's Minecraft 26.2 toolchain](https://fabricmc.net/2026/06/15/262.html), with pinned Loom and Gradle versions.

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-25'
.\gradlew.bat test build
# Optional: launch an isolated Minecraft client for in-game smoke checks.
.\gradlew.bat -PsmokeTest runSmoke
```

Unit tests cover UUID locks, IP conflicts across servers and restarts, canonical IPv6 and IPv4-mapped addresses, history preservation, malformed files, storage failure, simultaneous claims, migration, scoped exemptions, exact one-hour expiry, revocation, and management edits/removals. The smoke test uses an isolated temporary store, simulated IP lookups, and a loopback listener to check connection gates, account-verified pings/refreshes, and management tabs, exemption grants/revocation, account/lock editing, and confirmed or cancelled removals. Blocked attempts must never reach the listener. It writes `build/smoke-run/smoke-result.txt` and screenshots, then exits. Smoke test classes are excluded from the release JAR.

Before a release, also manually test a successful connection with a disposable account and a controlled server, a server transfer to both configured and unconfigured destinations, IP lookup failure, and VPN/network changes. No real-server anonymity claim follows from the local tests.
