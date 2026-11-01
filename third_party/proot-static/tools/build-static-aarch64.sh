#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ARCH="${ARCH:-aarch64}"
API="${API:-21}"
OUT="${OUT:-/tmp/termux-proot-official/build-${ARCH}}"
REPO_ARTIFACT_DIR="${REPO_ARTIFACT_DIR:-$ROOT_DIR/dist/${ARCH}}"
NDK="${NDK:-$HOME/Android/Sdk/ndk/21.1.6352462}"
TALLOC_VERSION="2.4.2"
TALLOC_SOURCE="$ROOT_DIR/third_party/talloc-${TALLOC_VERSION}"
SRC_DIR="$ROOT_DIR/src"

if [[ "$ARCH" != "aarch64" ]]; then
	echo "Only ARCH=aarch64 is currently supported." >&2
	exit 1
fi

if [[ ! -d "$NDK" ]]; then
	echo "Android NDK not found at $NDK" >&2
	exit 1
fi

if [[ ! -d "$TALLOC_SOURCE" ]]; then
	echo "Bundled talloc source not found at $TALLOC_SOURCE" >&2
	exit 1
fi

TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-$(uname -m)/bin"
CC="$TOOLCHAIN/${ARCH}-linux-android${API}-clang"
CXX="$TOOLCHAIN/${ARCH}-linux-android${API}-clang++"
TALLOC_LD="$TOOLCHAIN/ld.lld"
AR="$TOOLCHAIN/llvm-ar"
RANLIB="$TOOLCHAIN/llvm-ranlib"
STRIP="$TOOLCHAIN/llvm-strip"
OBJCOPY="$TOOLCHAIN/llvm-objcopy"
OBJDUMP="$TOOLCHAIN/llvm-objdump"

for tool in "$CC" "$CXX" "$TALLOC_LD" "$AR" "$RANLIB" "$STRIP" "$OBJCOPY" "$OBJDUMP"; do
	if [[ ! -x "$tool" ]]; then
		echo "Required tool not found: $tool" >&2
		exit 1
	fi
done

STATIC_ROOT="$OUT/static-root"
PACKAGE_ROOT="$OUT/root"

mkdir -p "$OUT" "$STATIC_ROOT/include" "$STATIC_ROOT/lib" "$PACKAGE_ROOT/bin" "$PACKAGE_ROOT/libexec/proot"
mkdir -p "$REPO_ARTIFACT_DIR"

TALLOC_WORKDIR="$(mktemp -d "$OUT/talloc-work.XXXXXX")"
cp -a "$TALLOC_SOURCE/." "$TALLOC_WORKDIR/"
PYTHON3_BIN="$(command -v python3 || true)"
if [[ -z "$PYTHON3_BIN" ]]; then
	echo "python3 is required to build bundled talloc." >&2
	exit 1
fi

mkdir -p "$TALLOC_WORKDIR/tool-shims"
printf '#!/bin/sh\n/bin/false\n' > "$TALLOC_WORKDIR/tool-shims/pkg-config"
chmod +x "$TALLOC_WORKDIR/tool-shims/pkg-config"
ln -sf "$PYTHON3_BIN" "$TALLOC_WORKDIR/tool-shims/python"

cat > "$TALLOC_WORKDIR/cross-answers.txt" <<'EOF'
Checking uname sysname type: "Linux"
Checking uname machine type: "dontcare"
Checking uname release type: "dontcare"
Checking uname version type: "dontcare"
Checking simple C program: OK
rpath library support: OK
-Wl,--version-script support: FAIL
Checking getconf LFS_CFLAGS: OK
Checking for large file support without additional flags: OK
Checking for -D_FILE_OFFSET_BITS=64: OK
Checking for -D_LARGE_FILES: OK
Checking correct behavior of strtoll: OK
Checking for working strptime: OK
Checking for C99 vsnprintf: OK
Checking for HAVE_SHARED_MMAP: OK
Checking for HAVE_MREMAP: OK
Checking for HAVE_INCOHERENT_MMAP: OK
Checking for HAVE_SECURE_MKSTEMP: OK
Checking getconf large file support flags work: OK
Checking for HAVE_IFACE_IFCONF: FAIL
EOF

(
	cd "$TALLOC_WORKDIR"

	export AR
	export CC
	export CXX
	export LD="$TALLOC_LD"
	export RANLIB
	export STRIP
	export OBJCOPY
	export OBJDUMP
	export PATH="$TALLOC_WORKDIR/tool-shims:$PATH"

	./configure \
		"--prefix=$STATIC_ROOT" \
		--disable-rpath \
		--disable-python \
		--cross-compile \
		--cross-answers=cross-answers.txt

	PYTHONHASHSEED=1 PATH="$TALLOC_WORKDIR/tool-shims:$PATH" \
		./buildtools/bin/waf build --targets=talloc

	"$AR" rcs "$STATIC_ROOT/lib/libtalloc.a" bin/default/talloc*.o
	cp -f talloc.h "$STATIC_ROOT/include/talloc.h"
)

(
	cd "$SRC_DIR"

	make distclean >/dev/null 2>&1 || true

	export CC
	export CXX
	export LD="$CC"
	export AR
	export RANLIB
	export STRIP
	export OBJCOPY
	export OBJDUMP
	export CFLAGS="-I$STATIC_ROOT/include -Werror=implicit-function-declaration"
	export LDFLAGS="-static -L$STATIC_ROOT/lib -Wl,-z,noexecstack -Wl,-z,max-page-size=16384"

	make V=1 \
		BINDIR="$PACKAGE_ROOT/bin" \
		PROOT_UNBUNDLE_LOADER="$PACKAGE_ROOT/libexec/proot" \
		install

	"$STRIP" "$PACKAGE_ROOT/bin/proot"
	"$STRIP" "$PACKAGE_ROOT/libexec/proot/loader"
	if [[ -f "$PACKAGE_ROOT/libexec/proot/loader32" ]]; then
		"$STRIP" "$PACKAGE_ROOT/libexec/proot/loader32"
	fi
)

install -m 0755 "$PACKAGE_ROOT/bin/proot" "$REPO_ARTIFACT_DIR/proot"
install -m 0755 "$PACKAGE_ROOT/libexec/proot/loader" "$REPO_ARTIFACT_DIR/loader"
if [[ -f "$PACKAGE_ROOT/libexec/proot/loader32" ]]; then
	install -m 0755 "$PACKAGE_ROOT/libexec/proot/loader32" "$REPO_ARTIFACT_DIR/loader32"
fi

echo "Built artifacts:"
echo "  $PACKAGE_ROOT/bin/proot"
echo "  $PACKAGE_ROOT/libexec/proot/loader"
if [[ -f "$PACKAGE_ROOT/libexec/proot/loader32" ]]; then
	echo "  $PACKAGE_ROOT/libexec/proot/loader32"
fi
echo "  $STATIC_ROOT/lib/libtalloc.a"
echo "Repo-local copies:"
echo "  $REPO_ARTIFACT_DIR/proot"
echo "  $REPO_ARTIFACT_DIR/loader"
if [[ -f "$REPO_ARTIFACT_DIR/loader32" ]]; then
	echo "  $REPO_ARTIFACT_DIR/loader32"
fi
