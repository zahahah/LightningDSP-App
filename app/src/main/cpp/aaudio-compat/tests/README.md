# Native compatibility regression tests

Run from the app repository root:

```sh
clang++ -std=c++17 -Wall -Wextra -Werror -fsanitize=address,undefined app/src/main/cpp/aaudio-compat/tests/hook_test.cpp -o /tmp/ldsp-hook-test
/tmp/ldsp-hook-test
clang++ -std=c++17 -Wall -Wextra -Werror -fsanitize=address,undefined app/src/main/cpp/aaudio-compat/tests/got_rebind_test.cpp -o /tmp/ldsp-got-test
/tmp/ldsp-got-test
```

The host ELF declarations are a test-only ELF64 ABI shim for macOS. Android
builds use the NDK ELF declarations. Fixtures exercise bounds, malformed dynamic
metadata, SysV/GNU hashes, imported symbol identity, slot/page uniqueness, prior
import modifications, RELRO, writable data, protection failures, CAS conflicts,
rollback, and builder direction/performance observation guards.

The implementation changes only five AAudio import slots in an eligible user
library. It cannot observe dlsym calls or calls originating from other libraries;
missing direction/performance setter observations therefore remain unchanged.
All five eager-resolved imports must exist on one nonexecutable data page. Other
layouts and non-ARM64 ABIs intentionally do nothing. Prior GOT modifications are
rejected; this is not a general detector of third-party inline hooks.

A kernel that repeatedly refuses protection restoration cannot be made to
restore permissions by application code. Such a transaction never reports ready,
rolls back whenever possible, and permanently disables compatibility in that
process. No executable page is made writable.

Host tests do not prove emulator stability, latency, audibility, or compatibility
with every Vector/LSPosed release. Device verification is still required.
