/*
 * libX11-xcb replacement (x86-64)
 *
 * The AppImage's libX11 is built with the Xlib-XCB transport integrated:
 * _XConnectXCB allocates a 0x70-byte XCBInfo block and stores it at
 * display+0xa30; the first field of that block is the xcb_connection_t*.
 * There is no separate libX11-xcb.so shipped, but winit's x11-dl dlopens
 * libX11-xcb.so.1 and resolves XGetXCBConnection/XSetEventQueueOwner from
 * it, so this stub provides both by reading the display structure directly
 * (same ABI/layout as the loaded x86 libX11 - verified against the binary).
 */
#define _GNU_SOURCE
#include <stdint.h>

#define XLIB_XCB_DISPLAY_XCBINFO_OFF 0xa30 /* struct _XDisplay field holding XCBInfo* */
#define XCB_INFO_CONNECTION_OFF      0x00 /* XCBInfo.connection */

void *XGetXCBConnection(void *dpy)
{
    if (!dpy)
        return (void *)0;
    void **info = *(void ***)((char *)dpy + XLIB_XCB_DISPLAY_XCBINFO_OFF);
    if (!info)
        return (void *)0;
    return *(void **)((char *)info + XCB_INFO_CONNECTION_OFF);
}

enum { XLIB_XCB_QUEUE_OWNER_XLIB = 0, XLIB_XCB_QUEUE_OWNER_XCB = 1 };

void XSetEventQueueOwner(void *dpy, int owner)
{
    (void)dpy;
    (void)owner; /* event queue stays with Xlib in this build */
}
