# Third-party notices

Pocket Pi includes or depends on the following third-party components. Their licenses apply to those components; the project's own code is MIT (see [LICENSE](LICENSE)).

| Component | License | Where |
| --- | --- | --- |
| Mermaid | MIT | `app/src/main/assets/mermaid.min.js` (bundled, unmodified) |
| AndroidX / Jetpack Compose / Material 3 | Apache-2.0 | Android dependencies |
| Kotlin standard library | Apache-2.0 | Android app |
| OkHttp / OkHttp SSE | Apache-2.0 | Android app |
| sshj | Apache-2.0 | Android app SSH/SFTP |
| net.i2p.crypto:eddsa | CC0-1.0 | Android app (Ed25519 provider) |
| Bouncy Castle | MIT-style | Android app (crypto provider) |
| SLF4J (nop binding) | MIT | Android app |
| JUnit 4 | EPL-1.0 | JVM unit tests |
| org.json | JSON License | JVM unit tests |
| Node.js built-ins | Node license | Bridge, mock bridge and scripts |

The Termux bridge itself has no runtime npm dependencies. The demo site uses no third-party fonts, scripts, images or analytics.

License texts and source links are available from each upstream project. If you redistribute the APK, keep the Mermaid license notice with the bundled asset.
