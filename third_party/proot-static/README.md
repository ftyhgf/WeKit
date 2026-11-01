proot
=====
[![Travis build status](https://travis-ci.org/termux/proot.svg?branch=master)](https://travis-ci.org/termux/proot)

This is a copy of [the PRoot project](https://github.com/proot-me/PRoot/) with patches applied to work better under [Termux](https://termux.com).

Build
=====

This repo can build a static Android `proot` plus its companion loaders with a
bundled `talloc` source tree, so no external `talloc` checkout is required.

Prerequisites
-------------

- Android NDK installed under `$HOME/Android/Sdk/ndk`
- `python3`
- standard build tools: `make`, `git`, `readelf`

Bundled dependency:

- `third_party/talloc-2.4.2`

Default Build
-------------

Run from the repo root:

```bash
tools/build-static-aarch64.sh
```

By default this uses:

- NDK: `$HOME/Android/Sdk/ndk/21.1.6352462`
- scratch output: `/tmp/termux-proot-official/build-aarch64`
- repo-local copies: `./dist/aarch64`

Build With A Specific NDK
-------------------------

Example with the newer installed NDK:

```bash
NDK=$HOME/Android/Sdk/ndk/28.2.13676358 \
tools/build-static-aarch64.sh
```

Optional Overrides
------------------

Override the scratch build directory:

```bash
OUT=/tmp/termux-proot-official/build-aarch64-ndk28 \
tools/build-static-aarch64.sh
```

Override the repo-local copy location:

```bash
REPO_ARTIFACT_DIR=$PWD/dist/aarch64 \
tools/build-static-aarch64.sh
```

Output Files
------------

The script always copies the final binaries into the repo-local artifact
directory. With defaults, the files are:

- `dist/aarch64/proot`
- `dist/aarch64/loader`
- `dist/aarch64/loader32`

The scratch build tree also contains:

- `root/bin/proot`
- `root/libexec/proot/loader`
- `root/libexec/proot/loader32`
- `static-root/lib/libtalloc.a`

Verified Toolchains
-------------------

These local NDK versions were tested successfully:

- `21.1.6352462`
- `28.2.13676358`

The build logic enables `--rosegment` automatically when the selected linker
supports it.

Notes
-----

- `dist/` is gitignored.
- The generated `proot`, `loader`, and `loader32` are statically linked.
