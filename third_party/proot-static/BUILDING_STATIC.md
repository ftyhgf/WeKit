# Building Static Termux PRoot

This file is kept as a compatibility entry point.

The current build instructions live in `BUILD.md`.

Quick start:

```bash
tools/build-static-aarch64.sh
```

Repo-local output binaries are copied to:

- `dist/aarch64/proot`
- `dist/aarch64/loader`
- `dist/aarch64/loader32`

Scratch build output defaults to:

- `/tmp/termux-proot-official/build-aarch64`
