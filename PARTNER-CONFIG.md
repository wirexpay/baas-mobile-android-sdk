# Example configuration — the debug menu

This example is one binary that exercises **every SDK surface** against **any partner's data**. Nothing
is hard-coded: the **Config** tab is a per-field editor for the integration parameters and credentials, and
every screen reads it live. The same field set is the contract for the iOS, Android, React Native and Flutter
examples, so QA enters the same values everywhere; the Wallet screen additionally takes the estimation id and
the provider block, or fetches an estimate with your bearer.

## Fields

### Environment
| Field | What it is |
|---|---|
| `environment` | `test` or `production`. Maps to the SDK's `testMode`; the SDK holds the hosts itself. |

### Integration parameters (non-secret)
| Field | What it is | Who gives it |
|---|---|---|
| `chainId` | `X-Chain-Id` — the chain the partner operates on. For Trade, the main account's chain: 8453 Base, 5042 Arc | **Wirex**, per integration |
| `companyId` | `X-Company-Id` of the Trade tab's wallet-signature login | **Wirex**, per integration |
| `clientId` | `?client=` on the hosted pages (optional) | **Wirex** |
| `userEmail` / `userWallet` | identity headers `X-User-Email` / `X-User-Wallet` | partner (the signed-in user) |
| `cardId` | the card Reveal / Collect act on | partner (a provisioned card) |
| `cardholderName` | display name on Collect | partner |
| `walletAmount` / `walletCurrency` / `walletProvider` | the top-up quote the Wallet screens arm with | partner (from an estimate) |
| `baasApiUrl` | the BaaS API base URL (https) for the Wallet screen's **Fetch estimate** helper; pricing is the partner backend's job, the helper only verifies a bearer | **Wirex**, per environment |
| `returnUrl` | Custom Tab App Link — **https, verified for this app's package** (`assetlinks.json`) | partner |

### Secrets (memory only — never persisted, never logged)
| Field | What it is | In production it is |
|---|---|---|
| `bearer` | `getAccessToken` — Wirex bearer for the user | minted by the partner's auth |
| `actionToken` | `getActionToken` — step-up for card data / PIN | the user's **wallet signature**, exchanged by the partner's backend |
| `checkoutToken` | token mode — the short-lived top-up token | minted by the **partner's backend** |
| `ownerKey` | the Trade tab's signer: a **test user's** exported private key. The bearer comes from it through `POST /api/v1/user/authorize` in wallet-signature mode, and a pasted `bearer` wins | the user's own wallet signs; a real app never holds a key |

### Dev options
| Toggle | What it does |
|---|---|
| `Disable screen protection` | **DEV ONLY**: draws the revealed PAN/CVV on the iOS **Simulator** (the SDK's secure capture layer renders blank there). **Off by default** — leave off on a real device and in any shippable build; on it defeats PCI screen-capture protection. |

## Where the values come from

Three sources fill the config, and a higher one wins over a lower one.

| Order | Source | Notes |
|---|---|---|
| 1, highest | Env or a local file. Android: launch extras, `adb shell am start -e <key> <value>`, or `example/tools/trade-launch.sh` | Android reads the extras in **debug builds only**. A field set this way is read-only on the Config tab until the app restarts |
| 2 | The Config tab | `ownerKey` stays in memory only |
| 3 | A `wirexqa://seed?d=…` link | A link that carries `ownerKey` is refused whole. Links travel in chats and QR codes, and a key never expires the way a bearer does |

Two behaviours changed with this order:

- A value typed on the Config tab now wins over a seed link's. Before, the last one applied won.
- Credentials may now come as launch extras, in debug builds only. Before, no credential was read from an extra.

CI checkouts never have the local file, so no Firebase build carries a key.

## What each surface needs

| Surface | What to enter |
|---|---|
| **Reveal** (PAN/CVV/expiry/PIN) | environment, chainId, bearer, actionToken, cardId |
| **Collect** (tokenize a card) | environment, chainId, bearer |
| **3DS step-up** | environment; a top-up orchestrator URL (the 3DS screen takes it directly — the SDK makes no call and needs no credentials) |
| **Wallet — Apple Pay / Google Pay** | environment, chainId, bearer, walletProvider, walletAmount/Currency; paste the estimation id + provider config on the Wallet screen |
| **Wallet — token mode** | the above + a `checkoutToken` your backend minted |
| **Trade** (stocks and coins) | environment, chainId, companyId, ownerKey; or a pasted bearer with ownerKey. On `production` a trade is real money, and the tab refuses one worth more than 10 USD |

## Credentials in a real integration

The example lets QA **paste** `bearer` and `actionToken` so a device needs no backend or wallet. A real
partner implements the credential provider instead:

- `getAccessToken()` → a bearer from the partner's auth against Wirex BaaS.
- `getActionToken(scope)` → the **user's wallet signature** over the confirmation action (`cardData`, `pin`).
- `userEmail` / `userWallet` → the identity headers.

`WirexThreeDS.configure({ testMode })` takes **no** credentials: the host authenticates to obtain the
orchestrator URL and hands it over.
