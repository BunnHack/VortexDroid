/*
 * glibc compat stubs (x86-64), LD_PRELOADed.
 *
 * Provides glibc-only symbols that the Vortex client resolves via
 * R_X86_64_GLOB_DAT but bionic (via box64's wrapped libc) does not export.
 * A NULL GLOB_DAT entry crashes on first indirect call, so these must exist.
 */
#define _GNU_SOURCE
#include <stdlib.h>
#include <string.h>
#include <resolv.h>

const char *gnu_get_libc_version(void) { return "2.35"; }
const char *gnu_get_auxv(unsigned long type) { (void)type; return NULL; }

int __res_init(void) { return 0; }
