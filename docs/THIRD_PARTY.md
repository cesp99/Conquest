# Third-party code and binaries

Conquest Code is GPL-3.0-or-later. This file records everything that comes
from somewhere else, where it came from, and under what licence — including
the components shipped as **compiled binaries**, which carry a source
obligation we intend to meet properly.

## Source vendored into this repository

| Component | Upstream | Version / commit | Licence | Where |
|---|---|---|---|---|
| Zed engine crates | [zed-industries/zed](https://github.com/zed-industries/zed) | `bc538de` | GPL-3.0-or-later, some Apache-2.0 | `core/vendor/` — see `core/vendor/VENDOR.md` |
| Termux `terminal-emulator`, `terminal-view` | [termux/termux-app](https://github.com/termux/termux-app) | `3df69d1` (v0.118.0) | GPL-3.0-only, with an Apache-2.0 heritage from [Android Terminal Emulator](https://github.com/jackpal/Android-Terminal-Emulator) | `vendor/` — see `vendor/VENDOR.md` |
| Zed themes (One Dark / One Light) | zed-industries/zed | `bc538de` | GPL-3.0-or-later | `app/src/main/assets/themes/` |

## Binaries shipped in the APK

These are **compiled by us** from published sources, by
`tools/build-proot.sh`, which fetches each tarball and verifies its SHA-256
before building. They appear in the `full` edition only.

| Binary | Source | Version | Licence |
|---|---|---|---|
| `libproot_exec.so` | [termux/proot](https://github.com/termux/proot) | v5.1.107.91 | GPL-2.0-or-later |
| (linked into the above) talloc | [samba.org](https://download.samba.org/pub/talloc/) | 2.4.2 | LGPL-3.0-or-later |

We use Termux's fork of proot rather than
[proot-me/proot](https://github.com/proot-me/proot) because upstream's
guests are killed with `SIGSYS` on current Android; the fork carries the
fixes.

**Local modifications**, applied by the build script and marked in the
source with `CONQUEST PATCH`:

- `src/extension/ashmem_memfd/ashmem_memfd.c` — add `#include <string.h>`.
  It uses `strcmp` and `memset` without declaring them, which clang 18 and
  later reject.

talloc is built from a hand-written `config.h` rather than its own `waf`
build system, which does not cross-compile comfortably; the values are in
`tools/build-proot.sh` and every one of them is true on Android's bionic.

### Source offer

The GPL requires that anyone receiving these binaries can get their exact
source. Running `tools/build-proot.sh` reproduces them from the upstream
tarballs named above, at the pinned versions, with the single patch listed
here. Anyone who wants the corresponding source can therefore obtain it
from those upstreams plus this repository; if you would rather receive a
tarball, open an issue and we will provide one.

## Downloaded at runtime

The `full` edition downloads a Debian base filesystem the first time you
ask for the Linux userland. It is **not** part of this project and is not
redistributed by us:

- **Debian** `stable-slim`, pulled from Debian's official container image
  and verified against the digest the registry publishes. Debian is a
  collection of works under many licences; see
  `/usr/share/doc/*/copyright` inside the installed rootfs.
- Anything you then install with `apt` comes from Debian's own
  repositories, under its own terms.

## Not used

- `termux-shared` — MIT with GPL subtrees, 26.7k lines, and unnecessary:
  the two terminal modules are self-contained.
