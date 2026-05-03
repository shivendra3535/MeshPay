# UPI Offline Mesh

> A distributed offline payment system that enables UPI transactions without internet using Bluetooth-based packet propagation, hybrid encryption (RSA-2048 + AES-256-GCM), idempotency control, and TTL-based replay protection.

---

## What Is This?

Standard UPI apps like PhonePe or Google Pay require an active internet connection because every transaction must be verified with the bank server in real time. This project simulates an alternative architecture:

**Your phone → Nearby phones (Bluetooth mesh) → Bank server**

When your internet is down, your payment packet is encrypted on your device and broadcast to nearby phones via Bluetooth. Any phone in the mesh that has internet connectivity automatically forwards the packet to the bank. The bank decrypts it, validates the balance, and settles the transaction — exactly once, no matter how many phones tried to deliver it.

This is a **proof of concept** demonstrating distributed systems design, cryptographic security, and fault-tolerant transaction processing — not a production banking system.

---

## Architecture Overview

```
User Device (phone-alice)
        │
        │  Creates encrypted MeshPacket
        ▼
  [Bluetooth Mesh]
  ┌─────────────────────────────────────────┐
  │  phone-alice  →  phone-stranger1        │
  │       ↓               ↓                │
  │  phone-stranger2  phone-stranger3       │
  │       ↓                                │
  │  phone-bridge (has 4G) ← bridge node   │
  └─────────────────────────────────────────┘
        │
        │  HTTP POST /api/bridge/ingest
        ▼
  Bank Backend (Spring Boot)
        │
        ├── Hash ciphertext (SHA-256)
        ├── Idempotency gate (ConcurrentHashMap / Redis SETNX)
        ├── Decrypt (RSA-OAEP → AES-GCM)
        ├── Freshness check (TTL / signedAt)
        └── Settle (debit sender, credit receiver)
```

---

## Key Engineering Concepts

### 1. Hybrid Encryption (RSA-2048 + AES-256-GCM)

Payment data is sensitive. Sending raw UPI details through strangers' phones is dangerous. The solution uses the same encryption pattern as TLS, PGP, and Signal:

**Why not RSA alone?** RSA-2048 can only encrypt ~245 bytes. A payment instruction (JSON with VPA, amount, nonce, timestamp) can easily exceed that.

**Why not AES alone?** AES requires both parties to share the same secret key. How do you securely share the key over an untrusted mesh?

**Hybrid encryption combines both:**

```
Step 1: Generate a one-time AES-256 key for this packet
Step 2: Encrypt the payment JSON with AES-256-GCM (fast, authenticated)
Step 3: Encrypt the AES key with the bank's RSA-2048 public key
Step 4: Pack: [256 bytes encrypted AES key] + [12 bytes IV] + [ciphertext + 16 byte tag]
Step 5: Base64-encode and embed in MeshPacket.ciphertext
```

The intermediate phones carrying this packet see only random bytes. Even if they try to tamper with a single bit, AES-GCM's authentication tag causes decryption to fail on the server. The packet is rejected.

**Wire format:**
```
[ RSA-encrypted AES key (256 bytes) ][ GCM IV (12 bytes) ][ AES ciphertext + auth tag ]
```

### 2. Idempotency (Exactly-Once Settlement)

Multiple phones may reach internet connectivity at the same time, all holding the same packet. Without protection, the bank would debit the sender multiple times.

**Solution:** The server computes `SHA-256(ciphertext)` and uses it as the idempotency key. `ConcurrentHashMap.putIfAbsent()` is the atomic gate — even if 100 threads call it simultaneously, exactly one returns `true` (proceed) and the rest return `false` (duplicate, drop).

```
Thread A: putIfAbsent("a3f9c1...") → null  → SETTLE ✓
Thread B: putIfAbsent("a3f9c1...") → Instant → DUPLICATE_DROPPED
Thread C: putIfAbsent("a3f9c1...") → Instant → DUPLICATE_DROPPED
```

**Why hash the ciphertext and not the `packetId`?** A malicious intermediate can rewrite the `packetId` field (it's in plaintext). They cannot forge a valid ciphertext for a different payload. Two honest copies of the same packet always have identical ciphertexts, and therefore identical hashes.

In production, this cache would be Redis with `SETNX + TTL` — same semantics, distributed across instances.

### 3. Replay Attack Prevention (TTL + Freshness Window)

A replay attack is when someone saves your encrypted payment packet and resends it days later. Without protection, the bank would process it again.

**Two layers of protection:**

**TTL (Time To Live):** Each `MeshPacket` has a `ttl` field that intermediate devices decrement on each hop. Packets at TTL 0 are not forwarded further. This bounds how long a packet lives in the mesh.

**Freshness window:** The `PaymentInstruction` (inside the encrypted blob) contains `signedAt` — the epoch millisecond when the sender's phone created the packet. The server checks:

```java
long ageSeconds = (Instant.now().toEpochMilli() - instruction.getSignedAt()) / 1000;
if (ageSeconds > maxAgeSeconds) → reject as stale_packet
if (ageSeconds < -300) → reject as future_dated (clock skew tolerance)
```

Even if an attacker captures and replays a valid ciphertext after 24 hours, the server rejects it because `signedAt` is too old.

### 4. Gossip Protocol (Bluetooth Mesh Propagation)

In a real deployment, phones exchange packets over BLE (Bluetooth Low Energy) GATT characteristics when they come into proximity. This project simulates that with `MeshSimulatorService`.

Each `VirtualDevice` holds packets it has seen. In each gossip round:
- Every device shares every packet it holds with every other device
- TTL is decremented per hop
- `putIfAbsent` on `packetId` ensures no device processes the same packet twice in the mesh layer

```
Round 0: phone-alice holds [pkt-abc]
Round 1: phone-stranger1, phone-stranger2 now hold [pkt-abc] (TTL decremented)
Round 2: phone-bridge (4G) now holds [pkt-abc] → uploads to bank
```

### 5. Optimistic Locking (Concurrent Balance Safety)

The `Account` entity uses JPA's `@Version` annotation. If two threads somehow both pass the idempotency gate and attempt to debit the same account, the second write throws `OptimisticLockException` rather than silently corrupting the balance.

```java
@Version
private Long version; // JPA increments this on every UPDATE
```

This is defense in depth. The idempotency layer should always catch duplicates first, but the database is the final safety net.

### 6. Database-Level Uniqueness

The `Transaction` table has a unique index on `packetHash`:

```java
@Index(name = "idx_packet_hash", columnList = "packetHash", unique = true)
```

If both the idempotency cache and the optimistic lock somehow fail (cache eviction race, multi-node deployment without Redis), the database constraint throws a `DataIntegrityViolationException`. Three independent layers, zero double-spends.

---

## Project Structure

```
src/main/java/com/demo/upimesh/
├── config/
│   └── AppConfig.java              # Enables @Scheduled for idempotency cache cleanup
├── controller/
│   ├── ApiController.java          # All REST endpoints
│   └── DashboardController.java    # Serves the Thymeleaf dashboard at /
├── crypto/
│   ├── HybridCryptoService.java    # RSA-OAEP + AES-GCM encrypt/decrypt
│   └── ServerKeyHolder.java        # RSA-2048 keypair (generated on startup)
├── model/
│   ├── Account.java                # JPA entity: VPA, balance, @Version
│   ├── AccountRepository.java
│   ├── MeshPacket.java             # The over-the-wire format (packetId, ttl, ciphertext)
│   ├── PaymentInstruction.java     # Decrypted payload (VPAs, amount, nonce, signedAt)
│   ├── Transaction.java            # Permanent ledger record
│   └── TransactionRepository.java
└── service/
    ├── BridgeIngestionService.java # Full server-side pipeline (hash → dedup → decrypt → settle)
    ├── DemoService.java            # Seeds accounts; simulates sender phone encryption
    ├── IdempotencyService.java     # ConcurrentHashMap gate with TTL eviction
    ├── MeshSimulatorService.java   # Virtual devices + gossip rounds
    ├── SettlementService.java      # @Transactional debit/credit
    └── VirtualDevice.java          # Simulated phone node
```

---

## API Reference

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/api/server-key` | Fetch server's RSA public key (sender phones cache this) |
| `POST` | `/api/demo/send` | Create and inject an encrypted packet into the mesh |
| `GET` | `/api/mesh/state` | View all devices, packet counts, idempotency cache size |
| `POST` | `/api/mesh/gossip` | Run one Bluetooth gossip round |
| `POST` | `/api/mesh/flush` | All bridge nodes upload packets to backend (parallel, tests idempotency) |
| `POST` | `/api/mesh/reset` | Clear mesh state and idempotency cache |
| `POST` | `/api/bridge/ingest` | **Production endpoint** — bridge node uploads a MeshPacket |
| `GET` | `/api/accounts` | List all accounts and balances |
| `GET` | `/api/transactions` | Last 20 transactions |

---

## Running the Project

**Prerequisites:** Java 17+, Maven

```bash
# Clone and build
git clone https://github.com/your-username/upi-offline-mesh.git
cd upi-offline-mesh
mvn spring-boot:run
```

| URL | Description |
|-----|-------------|
| `http://localhost:8080` | Live simulation dashboard |
| `http://localhost:8080/h2-console` | H2 in-memory database console |

**H2 console settings:**
- JDBC URL: `jdbc:h2:mem:upimesh`
- Username: `sa`
- Password: *(empty)*

---

## Demo Walkthrough

1. **Open the dashboard** at `http://localhost:8080`
2. **Step 1 — Create Packet:** Select sender (alice@demo), receiver (bob@demo), enter amount and PIN, click "Create + Inject Packet". The packet is encrypted on the "sender phone" before entering the mesh.
3. **Step 2 — Gossip:** Click "Run Gossip Round". Watch the packet propagate from phone-alice through the stranger devices toward phone-bridge.
4. **Step 3 — Flush:** Click "Flush Bridge Nodes". phone-bridge (the only device with 4G) uploads the packet to the backend. The idempotency gate ensures it settles exactly once.
5. **Observe:** Account balances update. The transaction ledger records the settlement with bridge node ID and hop count.

**To test duplicate suppression:** Inject one packet, run gossip until multiple devices hold it, then flush. You will see one `SETTLED` and multiple `DUPLICATE_DROPPED` results in the activity log.

---

## Configuration

`src/main/resources/application.properties`

```properties
# How long to remember a packet hash (idempotency window)
upi.mesh.idempotency-ttl-seconds=86400

# Maximum age of a packet's signedAt timestamp (replay protection)
upi.mesh.packet-max-age-seconds=86400
```

---

## Security Design Decisions

| Decision | Reason |
|----------|--------|
| Hash `ciphertext`, not `packetId`, as idempotency key | `packetId` is plaintext and can be rewritten by malicious intermediaries |
| AES-GCM (authenticated encryption) | Any single-bit tampering causes decryption to fail — no separate HMAC needed |
| RSA-OAEP with SHA-256 | Resists chosen-ciphertext attacks; stronger than PKCS#1 v1.5 padding |
| `nonce` inside `PaymentInstruction` | Two identical payments (same sender, receiver, amount) produce different ciphertexts and different idempotency keys |
| `signedAt` freshness check with clock-skew tolerance | Prevents replay beyond the max age window; ±5 minutes tolerance for device clock drift |
| `@Version` optimistic locking | Database-level guard against balance corruption if idempotency cache is evicted |
| Unique DB index on `packetHash` | Third and final layer against double-settlement in multi-node deployments |

---

## Testing

```bash
mvn test
```

**`IdempotencyConcurrencyTest`** — the primary test suite:

- `singlePacketDeliveredByThreeBridgesSettlesExactlyOnce` — Fires 3 threads simultaneously at the same packet. Asserts `settled=1`, `duplicates=2`, and that Alice's balance decreased by exactly ₹100 once.
- `tamperedCiphertextIsRejected` — Flips a byte in the ciphertext. Asserts `outcome=INVALID`.
- `encryptDecryptRoundTrip` — Asserts that a `PaymentInstruction` survives a full RSA+AES encrypt/decrypt cycle with all fields intact.

---

## Production Considerations

This project is a proof of concept. A production deployment would require:

- **Key management:** RSA private key in an HSM or KMS (AWS KMS, HashiCorp Vault), not generated in-memory on startup
- **Idempotency cache:** Redis with `SETNX + TTL` for distributed multi-instance deployments
- **Transport layer:** Real BLE GATT implementation on Android, not simulated virtual devices
- **PIN verification:** Actual UPI PIN validation against bank-held hashes (NPCI integration)
- **Audit trail:** Immutable transaction log for RBI compliance and fraud investigation
- **Network range:** Bluetooth range ~10m; real mesh would require multi-hop routing over longer distances
- **Regulatory approval:** Offline financial transactions require explicit RBI authorization

---

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Backend framework | Java 17, Spring Boot |
| Database | H2 (in-memory, swap for PostgreSQL in production) |
| ORM | Spring Data JPA / Hibernate |
| Encryption | RSA-2048 (OAEP-SHA256), AES-256-GCM, SHA-256 |
| Frontend | Thymeleaf, vanilla JS, SVG mesh visualization |
| Testing | JUnit 5, Spring Boot Test |
| Build | Maven |

---

## Resume Description

> Built a distributed offline payment system using Java and Spring Boot that enables UPI transactions without internet using Bluetooth-based packet propagation, hybrid encryption (RSA-2048 + AES-256-GCM), idempotency control with ConcurrentHashMap/putIfAbsent, and TTL-based replay protection — demonstrating distributed systems design, cryptographic security, and fault-tolerant transaction processing.

---

## Interview Talking Points

**Q: How does the system prevent double-charging if multiple phones deliver the same packet?**

The server computes `SHA-256(ciphertext)` as the idempotency key. `ConcurrentHashMap.putIfAbsent()` is atomic — even if 100 threads call it at the same instant, exactly one wins and proceeds to settlement. The rest are dropped as duplicates. In production this maps to Redis `SETNX`. There are two additional fallback layers: JPA `@Version` optimistic locking and a unique database index on `packetHash`.

**Q: How do you prevent someone from intercepting and replaying the encrypted packet?**

Two mechanisms: (1) The `signedAt` timestamp inside the encrypted payload is checked against a maximum age window on the server. A packet replayed after 24 hours is rejected as `stale_packet`. (2) Each packet contains a UUID `nonce` inside the encrypted payload. Even if everything else is identical, the nonce changes the ciphertext, which changes the idempotency key — so it would only be processed once anyway.

**Q: Can a malicious intermediate modify the transaction amount?**

No. The `PaymentInstruction` (containing the amount) is inside the AES-GCM ciphertext, which is inside the RSA-encrypted AES key. AES-GCM is authenticated encryption — any modification to the ciphertext, even a single bit, causes decryption to throw an exception. The outer `packetId` and `ttl` fields are plaintext and modifiable, but they are not trusted by the server for settlement decisions.

---

## License

MIT
