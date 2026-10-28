# Third-party notices

The VoiceInput2PC source is MIT licensed. Third-party components retain their
own copyrights and licenses.

| Component | Relationship | License / upstream |
| --- | --- | --- |
| Tailcat | Go module and optional pinned Windows runtime | BSD-3-Clause, https://github.com/tailscale/tailcat |
| Tailscale/WireGuard dependencies | Transitive dependencies of Tailcat | See the exact Go module graph and upstream notices |
| PyInstaller | Windows packaging dependency | GPL-2.0-or-later with the PyInstaller exception, https://github.com/pyinstaller/pyinstaller |
| cryptography | Python runtime dependency | Apache-2.0 OR BSD-3-Clause, https://github.com/pyca/cryptography |
| Pillow, pystray, qrcode | Python runtime/build dependencies | See each pinned distribution's license metadata |
| Android Gradle/AndroidX dependencies | Android build/runtime dependencies | See Gradle dependency metadata and upstream notices |

Binary releases must carry the notices required by the exact dependency graph.
This file does not relicense third-party code and must be updated when bundled
components or versions change.
