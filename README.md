# Offline UPI over a Bluetooth Mesh (Java 21 · Spring Boot 3)

Proof of concept: send a UPI payment **with no internet** on your phone. The payment is sealed
for the bank, gossiped phone-to-phone over Bluetooth, and uploaded by whichever nearby phone
has mobile data (a "bridge").

```
 Pratyush (offline) ──BT── Manoj (offline) ──BT── Ajay (4G) ──BT── Kiran (4G)
        └──────────BT── Sunita (4G) ──────BT────────┘
                              │ any bridge with internet
                              ▼
                        Bank server  →  decrypt → freshness → claim → settle
```

## Run

```bash
mvn spring-boot:run
```

Open http://localhost:8085 and try: **Seal & broadcast → Gossip round ×3 → Bridges upload to bank**,
then the three attack buttons. Click a phone to toggle its mobile data.

Accounts and the bank ledger are stored in an in-memory **H2** database via **Spring Data JPA**.
Browse them at http://localhost:8085/h2-console with JDBC URL `jdbc:h2:mem:upimesh`, user `sa`,
empty password:

```sql
SELECT id, LEFT(packet_id, 8) pkt, bridge_id, amount, outcome FROM ledger ORDER BY id;
SELECT vpa, balance, version FROM accounts;
```

```bash
mvn test
```

## The three challenges and how they're solved

| Problem | Solution | Where |
|---|---|---|
| **Strangers relay your payment** — they must not read PIN/amount or change it | **Hybrid encryption**: AES-256-GCM encrypts the payload (fast; GCM auth tag detects a single flipped bit), RSA-2048-OAEP wraps the one-time AES key so only the bank can open it | `crypto/HybridCrypto.java` |
| **3 bridges upload the same packet** — must not debit ₹300 | **Idempotency**: bank hashes the ciphertext itself (never trusts the relay's id) and does an atomic `ConcurrentHashMap.putIfAbsent`. First claim wins, rest are `DUPLICATE_DROPPED` | `bank/IdempotencyStore.java`, `bank/BankService.java` |
| **Manoj saves the packet and replays it tomorrow** | Same idempotency key catches it, **plus** a `signedAt` timestamp sealed *inside* the ciphertext — anything older than 24h is `REJECTED_EXPIRED`. That window also lets the bank safely evict old idempotency keys | `BankService.ingest`, `evictExpiredKeys` |

Gossip itself uses a **hop TTL** (decremented per Bluetooth hop) and per-device "seen" sets so a
packet doesn't flood the mesh forever or bounce back into a bridge after upload.

## Persistence

- `Account` and `LedgerEntry` are JPA entities. Debit, credit and the `SETTLED` ledger row commit in **one transaction**, so money can never be half-moved.
- `Account` carries a `@Version` column (optimistic locking) as defense in depth; the settlement lock means it never actually has to fire.
- Every upload is recorded, including duplicates and rejections, so the ledger is a full audit trail.
- If settlement throws (for example, the DB is down), the transaction rolls back **and the idempotency claim is released**, so the next bridge's copy can still settle (test `failedSettlementReleasesClaimSoTheNextCopyCanStillSettle`).

## Bugs fixed compared with the original video repo

| Original | Here |
|---|---|
| `pinHash` is packed into the packet but never verified, and the bank public key is public, so anyone can forge a payment from any account | PIN is checked inside the bank → `REJECTED_BAD_PIN` |
| Hash is claimed *before* decrypt/settle; if settlement throws (for example, an `@Version` conflict when two payments from the same sender settle in parallel) the claim stays and the payment is lost forever | Claim only after decrypt + freshness; settlement is serialized; claim is released on failure (test `differentPaymentsFromSameSenderSettlingConcurrentlyAreAllApplied`) |
| Tampered/stale packets consume idempotency slots | They're rejected before the claim |
| Keys evicted at `now - 24h`, ignoring the 5-minute clock skew it allows | Eviction cutoff is `now - maxAge - skew` |

## Interview talking points

- Why the hop TTL is *outside* the ciphertext (relays must decrement it) but the time TTL is *inside* (relays must not be able to refresh it).
- Why the bank recomputes the packet id from the bytes: a relay could otherwise rewrite the id to dodge dedupe (see test `relayCannotDodgeDedupeByRewritingPacketIdOrTtl`).
- Check order: decrypt → freshness → claim → settle. Tampered junk never consumes idempotency state.
- `flushBridges()` really does upload on parallel virtual threads behind a start latch, so the race is real; `threeBridgesUploadingConcurrentlySettleExactlyOnce` proves it.
- What a production version needs: real BLE transport, Redis `SET NX` (shared across bank instances) instead of an in-memory map, a real database instead of in-memory H2, HSM for keys and PIN verification, sender-side signatures for non-repudiation, `SELECT … FOR UPDATE` row locks instead of one global lock, and a way to tell the offline sender that the payment settled.

## Layout

```
client/PacketFactory     sender's phone: builds + seals the payment (public key only)
crypto/                  HybridCrypto, BankKeyStore (private key never leaves), Hashing
mesh/MeshSimulator       devices, Bluetooth range graph, gossip rounds, parallel bridge upload
bank/BankService         decrypt, freshness, idempotency claim, transactional settlement
bank/Account, model/LedgerEntry + repositories   JPA entities on H2
web/MeshController       REST API + attack demos
static/index.html        dashboard
```

H2 runs in memory, so everything resets on restart; demo PINs are in `BankService.reset()`.
