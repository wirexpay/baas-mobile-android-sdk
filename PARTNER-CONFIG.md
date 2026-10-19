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
| `chainId` | `X-Chain-Id` — the chain the partner operates on | **Wirex**, per integration |
| `clientId` | `?client=` on the hosted pages (optional) | **Wirex** |
| `userEmail` / `userWallet` | identity headers `X-User-Email` / `X-User-Wallet` | partner (the signed-in user) |
| `cardId` | the card Reveal / Collect act on | partner (a provisioned card) |
| `cardholderName` | display name on Collect | partner |
| `walletAmount` / `walletCurrency` / `walletProvider` | the top-up quote the Wallet screens arm with | partner (from an estimate) |
| `returnUrl` | Custom Tab App Link — **https, verified for this app's package** (`assetlinks.json`) | partner |

### Secrets (memory only — never persisted, never logged)
| Field | What it is | In production it is |
|---|---|---|
| `bearer` | `getAccessToken` — Wirex bearer for the user | minted by the partner's auth |
| `actionToken` | `getActionToken` — step-up for card data / PIN | the user's **wallet signature**, exchanged by the partner's backend |
| `checkoutToken` | token mode — the short-lived top-up token | minted by the **partner's backend** |

### Dev options
| Toggle | What it does |
|---|---|
| `Disable screen protection` | **DEV ONLY**: draws the revealed PAN/CVV on the iOS **Simulator** (the SDK's secure capture layer renders blank there). **Off by default** — leave off on a real device and in any shippable build; on it defeats PCI screen-capture protection. |

## What each surface needs

| Surface | What to enter |
|---|---|
| **Reveal** (PAN/CVV/expiry/PIN) | environment, chainId, bearer, actionToken, cardId |
| **Collect** (tokenize a card) | environment, chainId, bearer |
| **3DS step-up** | environment; a top-up orchestrator URL (the 3DS screen takes it directly — the SDK makes no call and needs no credentials) |
| **Wallet — Apple Pay / Google Pay** | environment, chainId, bearer, walletProvider, walletAmount/Currency; paste the estimation id + provider config on the Wallet screen |
| **Wallet — token mode** | the above + a `checkoutToken` your backend minted |

## Credentials in a real integration

The example lets QA **paste** `bearer` and `actionToken` so a device needs no backend or wallet. A real
partner implements the credential provider instead:

- `getAccessToken()` → a bearer from the partner's auth against Wirex BaaS.
- `getActionToken(scope)` → the **user's wallet signature** over the confirmation action (`cardData`, `pin`).
- `userEmail` / `userWallet` → the identity headers.

`WirexThreeDS.configure({ testMode })` takes **no** credentials: the host authenticates to obtain the
orchestrator URL and hands it over.
