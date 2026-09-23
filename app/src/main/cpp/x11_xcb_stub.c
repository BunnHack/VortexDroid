/*
 * libX11-xcb stub (x86-64)
 *
 * The ARM64 (Termux) libX11.so.6 used via BOX64_EMULATED_LIBS depends on
 * libX11-xcb.so (unversioned Termux name). The x86 Vortex client loads its
 * own libX11-xcb.so.1 from the AppImage, but box64 must also satisfy the
 * ARM64 lib's DT_NEEDED first. This stub provides the x86 soname
 * libX11-xcb.so.1, exporting XGetXCBConnection/XSetEventQueueOwner via
 * dlsym passthrough to the loaded real libX11.
 */
#define _GNU_SOURCE
#include <dlfcn.h>

typedef void XID;
typedef XID *XPointer;

void *XGetXCBConnection(void *dpy) {
    static void *(*real)(void *);
    if (!real) {
        real = (void *(*)(void *))dlsym(RTLD_NEXT, "XGetXCBConnection");
        if (!real) return (void *)0;
    }
    return real(dpy);
}

void XSetEventQueueOwner(void *dpy, int owner) {
    static void (*real)(void *, int);
    if (!real) {
        real = (void (*)(void *, int))dlsym(RTLD_NEXT, "XSetEventQueueOwner");
        if (!real) return;
    }
    real(dpy, owner);
}
