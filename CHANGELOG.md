# Changelog

## 0.3.15
- 3-D Secure: `dangerouslyDisableScreenProtection` on `WirexThreeDS`, the same switch Reveal and Collect have. Set, no `FLAG_SECURE` is applied while a challenge is on screen, so automated UI tests and device clouds can see the issuer's page. Off by default; never ship a build with it set.

## 0.3.14
- Reveal: honour `dangerouslyDisableScreenProtection` for a row that is attached before its session is bound. A declarative host (a Flutter platform view, or a placeholder row shown before the session loads) attaches the view with no session, so it secured the window by default; binding a session that disabled protection now drops that flag instead of leaving the window `FLAG_SECURE`.
- Reveal: a card field redacts its own `toString()` / debug output, so a value cannot leak through a logging interceptor or a debugger's variable pane. Matches the iOS `RevealPresentation`.

## 0.3.13

Reveal
- A row masked when the app leaves the foreground keeps your reveal request and draws again on return, as long as the session still holds the value. Past the session's 120 s lifetime the row stays masked until you load again.
- A hidden segmented field (PIN) shows the masked placeholder's glyph in every cell instead of empty boxes.
- Segmented cells (PIN) are `controlMinHeight` tall and share the row's width equally, the same geometry as on iOS.
- Card entry no longer leaks through a screen capture of the hosting window; the reveal copy button is drawn and sized correctly.
- Reveal: PIN cells fill the row at `controlMinHeight` height in a host box of exactly that height: a blank label collapses and a field without a copy control gives the copy slot to the value.
- Reveal: new string `maskedSegmentGlyph`, drawn in every PIN cell while masked (default `*`; pass `•` for dots).

## 0.3.7

- 3-D Secure: presents the card issuer's authentication page a top-up asks for and reports the outcome.
- Wallet top-up: token mode. The checkout can execute the top-up itself and report executing / executed / failed instead of handing back a token. Standalone launch helpers build the checkout URL and parse the return link for hosts that open the checkout in a browser tab; the return link is matched at a path boundary.
- Privacy manifests ship with the iOS artifacts.

## 0.3.1

First public release.

- Card reveal: shows a cardholder their own card number, expiry, CVV and PIN inside the host app.
- Card collect: takes a card the user is entering and returns a token.
- Wallet top-up: hosts the Wirex checkout with Google Pay.
- One environment model for every surface: `production` and `test`, selected with `testMode`.
