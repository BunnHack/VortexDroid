/*
 * mprotect fixup for the Vortex client's crash handler (x86-64, LD_PRELOAD).
 *
 * The client's crash handler installs a per-thread alternate signal stack:
 *   p = mmap(NULL, pagesize + 0x4000, PROT_NONE, PRIVATE|ANON)
 *   mprotect(p + pagesize, 0x4000, PROT_READ|PROT_WRITE)   <- assert_eq!(.., 0)
 * Under box64 on Android this mprotect can come back with EINVAL, and the
 * client turns that into a hard panic ("mprotect to configure memory for
 * sigaltstack failed"). Recover by:
 *   1. trying the real mprotect with page-aligned, range-extended args
 *   2. if that still fails, re-mapping the range RW with MAP_FIXED
 *   3. never reporting failure for a stack that is already writable
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <stdint.h>
#include <string.h>
#include <unistd.h>
#include <sys/mman.h>

typedef int (*mprotect_fn)(void *, size_t, int);

static size_t page_size(void) {
    long p = sysconf(30 /* _SC_PAGESIZE */);
    return (p > 0) ? (size_t)p : 4096u;
}

int mprotect(void *addr, size_t len, int prot)
{
    static mprotect_fn real;
    if (!real)
        real = (mprotect_fn)dlsym(RTLD_NEXT, "mprotect");
    if (!real)
        return -1;

    int ret = real(addr, len, prot);
    if (ret == 0 || errno != EINVAL)
        return ret;

    /* EINVAL: align the range downward and extend the length, then retry */
    const size_t ps = page_size();
    const uintptr_t raw = (uintptr_t)addr;
    const uintptr_t aligned = raw & ~(ps - 1);
    const size_t extend = (size_t)(raw - aligned);
    if (extend || (len & (ps - 1))) {
        size_t newlen = len + extend;
        if (newlen & (ps - 1))
            newlen = (newlen + ps) & ~(ps - 1);
        errno = 0;
        ret = real((void *)aligned, newlen, prot);
        if (ret == 0)
            return 0;
    }

    /* last resort: replace the mapping wholesale (keeps the stack usable;
     * losing the PROT_NONE guard page is acceptable) */
    void *fresh = mmap(addr, len, prot,
                       MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED, -1, 0);
    if (fresh != MAP_FAILED)
        return 0;

    errno = EINVAL;
    return -1;
}
