#include <stddef.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <stdint.h>
#include <unistd.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <sys/socket.h>
#include <sys/un.h>

#define AUDIO_SOCK      "polydroid_audio"
#define AUDIO_MAGIC     0x31414450u
#define PA_SAMPLE_S16LE 3
#define PA_SAMPLE_FLOAT32LE 5

typedef unsigned long snd_pcm_uframes_t;
typedef long snd_pcm_sframes_t;
typedef struct snd_pcm snd_pcm_t;
typedef struct snd_pcm_hw_params snd_pcm_hw_params_t;
typedef struct snd_pcm_sw_params snd_pcm_sw_params_t;
typedef struct snd_pcm_info snd_pcm_info_t;
typedef struct snd_pcm_status snd_pcm_status_t;
typedef struct snd_ctl snd_ctl_t;
typedef struct snd_ctl_card_info snd_ctl_card_info_t;
typedef unsigned long snd_pcm_chmap_query_t;
typedef unsigned long snd_pcm_chmap_t;

struct snd_pcm {
    int fd;
    unsigned int rate;
    int channels;
    int sample_bytes; /* derived from the requested ALSA format */
    int started;
    uint8_t carry[16];
    int carry_len;
    int carry_off;
    int nonblock;
};

struct snd_ctl { int dummy; };

static int audio_enabled(void) {
    /* POLYDROID_AUDIO is the generic gate (Vortex client);
       POLYDROID_POLYTORIA2 is kept for compatibility. */
    const char *a = getenv("POLYDROID_AUDIO");
    if (a && a[0] == '1') return 1;
    const char *f = getenv("POLYDROID_POLYTORIA2");
    return f && f[0] == '1';
}

static int connect_bridge(void) {
    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) return -1;
    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    addr.sun_path[0] = '\0';
    size_t n = strlen(AUDIO_SOCK);
    memcpy(addr.sun_path + 1, AUDIO_SOCK, n);
    socklen_t alen = (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1 + n);
    if (connect(fd, (struct sockaddr *)&addr, alen) != 0) { close(fd); return -1; }
    return fd;
}

static int write_all(int fd, const void *buf, size_t n) {
    const char *p = (const char *)buf;
    size_t off = 0;
    while (off < n) {
        ssize_t w = write(fd, p + off, n - off);
        if (w <= 0) return -1;
        off += (size_t)w;
    }
    return 0;
}

int snd_pcm_open(snd_pcm_t **pcm, const char *name, int stream, int mode) {
    (void)name; (void)stream; (void)mode;
    if (!pcm) return -1;
    snd_pcm_t *p = (snd_pcm_t *)calloc(1, sizeof(*p));
    if (!p) return -1;
    p->fd = -1;
    p->rate = 48000;
    p->channels = 2;
    *pcm = p;
    return 0;
}

int snd_pcm_close(snd_pcm_t *pcm) {
    if (pcm) { if (pcm->fd >= 0) close(pcm->fd); free(pcm); }
    return 0;
}


size_t snd_pcm_hw_params_sizeof(void) { return 1024; }
size_t snd_pcm_sw_params_sizeof(void) { return 1024; }
int snd_pcm_hw_params_any(snd_pcm_t *p, snd_pcm_hw_params_t *h) { (void)p; (void)h; return 0; }
int snd_pcm_hw_params_set_access(snd_pcm_t *p, snd_pcm_hw_params_t *h, int a) { (void)p; (void)h; (void)a; return 0; }
static int alsa_format_bytes(int f) {
    switch (f) {
        case 2: case 10: /* S16_LE / S16_BE */ return 2;
        default: /* f32, s32, everything else */ return 4;
    }
}

int snd_pcm_hw_params_set_format(snd_pcm_t *p, snd_pcm_hw_params_t *h, int f) {
    (void)h;
    if (p) p->sample_bytes = alsa_format_bytes(f);
    return 0;
}
int snd_pcm_hw_params_set_channels(snd_pcm_t *p, snd_pcm_hw_params_t *h, unsigned int ch) { (void)h; if (p) p->channels = (int)ch; return 0; }
int snd_pcm_hw_params_set_rate_near(snd_pcm_t *p, snd_pcm_hw_params_t *h, unsigned int *val, int *dir) { (void)h; (void)dir; if (p && val) p->rate = *val; return 0; }
int snd_pcm_hw_params_set_buffer_size_near(snd_pcm_t *p, snd_pcm_hw_params_t *h, snd_pcm_uframes_t *val) { (void)p; (void)h; (void)val; return 0; }
int snd_pcm_hw_params_set_period_size_near(snd_pcm_t *p, snd_pcm_hw_params_t *h, snd_pcm_uframes_t *val, int *dir) { (void)p; (void)h; (void)val; (void)dir; return 0; }
int snd_pcm_hw_params_set_periods_near(snd_pcm_t *p, snd_pcm_hw_params_t *h, unsigned int *val, int *dir) { (void)p; (void)h; (void)val; (void)dir; return 0; }
void snd_pcm_hw_params_free(snd_pcm_hw_params_t *h) { (void)h; }

/* symbols referenced by cpal via GLOB_DAT (must exist, even as stubs) */
snd_pcm_hw_params_t *snd_pcm_hw_params_malloc(void) { return calloc(1, 1024); }

/* ---- cpal ALSA-hostile surface: satisfy link-time relocations ---- */

int snd_pcm_hw_params_get_channels(const snd_pcm_hw_params_t *h, unsigned int *ch) { (void)h; if (ch) *ch = 2; return 0; }
int snd_pcm_hw_params_get_channels_min(const snd_pcm_hw_params_t *h, unsigned int *ch) { (void)h; if (ch) *ch = 1; return 0; }
int snd_pcm_hw_params_get_channels_max(const snd_pcm_hw_params_t *h, unsigned int *ch) { (void)h; if (ch) *ch = 2; return 0; }
int snd_pcm_hw_params_get_rate(const snd_pcm_hw_params_t *h, unsigned int *r, int *dir) { (void)h; (void)dir; if (r) *r = 48000; return 0; }
int snd_pcm_hw_params_get_rate_min(const snd_pcm_hw_params_t *h, unsigned int *r, int *dir) { (void)h; (void)dir; if (r) *r = 8000; return 0; }
int snd_pcm_hw_params_get_rate_max(const snd_pcm_hw_params_t *h, unsigned int *r, int *dir) { (void)h; (void)dir; if (r) *r = 48000; return 0; }
int snd_pcm_hw_params_test_rate(const snd_pcm_t *p, const snd_pcm_hw_params_t *h, unsigned int r, int dir) { (void)p; (void)h; (void)r; (void)dir; return 0; }
int snd_pcm_hw_params_set_rate(snd_pcm_t *p, snd_pcm_hw_params_t *h, unsigned int r, int dir) { (void)h; (void)dir; if (p) p->rate = r; return 0; }
int snd_pcm_hw_params_set_rate_resample(snd_pcm_t *p, snd_pcm_hw_params_t *h, unsigned int r) { (void)p; (void)h; (void)r; return 0; }
int snd_pcm_hw_params_test_format(const snd_pcm_t *p, const snd_pcm_hw_params_t *h, int f) { (void)p; (void)h; (void)f; return 0; }
int snd_pcm_hw_params_get_format(const snd_pcm_hw_params_t *h, int *f) { (void)h; if (f) *f = 2 /* SND_PCM_FORMAT_S16_LE */; return 0; }
int snd_pcm_hw_params_test_channels(const snd_pcm_t *p, const snd_pcm_hw_params_t *h, unsigned int c) { (void)p; (void)h; (void)c; return 0; }
int snd_pcm_hw_params_get_period_size(const snd_pcm_hw_params_t *h, snd_pcm_uframes_t *v, int *dir) { (void)h; (void)dir; if (v) *v = 512; return 0; }
int snd_pcm_hw_params_get_period_size_min(const snd_pcm_hw_params_t *h, snd_pcm_uframes_t *v, int *dir) { (void)h; (void)dir; if (v) *v = 64; return 0; }
int snd_pcm_hw_params_get_period_size_max(const snd_pcm_hw_params_t *h, snd_pcm_uframes_t *v, int *dir) { (void)h; (void)dir; if (v) *v = 16384; return 0; }
int snd_pcm_hw_params_set_period_size(snd_pcm_t *p, snd_pcm_hw_params_t *h, snd_pcm_uframes_t v, int dir) { (void)p; (void)h; (void)v; (void)dir; return 0; }
int snd_pcm_hw_params_get_buffer_size(const snd_pcm_hw_params_t *h, snd_pcm_uframes_t *v) { (void)h; if (v) *v = 2048; return 0; }
int snd_pcm_hw_params_get_buffer_size_min(const snd_pcm_hw_params_t *h, snd_pcm_uframes_t *v) { (void)h; if (v) *v = 128; return 0; }
int snd_pcm_hw_params_get_buffer_size_max(const snd_pcm_hw_params_t *h, snd_pcm_uframes_t *v) { (void)h; if (v) *v = 65536; return 0; }
int snd_pcm_hw_params_set_buffer_size(snd_pcm_t *p, snd_pcm_hw_params_t *h, snd_pcm_uframes_t v) { (void)p; (void)h; (void)v; return 0; }
int snd_pcm_hw_params_get_periods(const snd_pcm_hw_params_t *h, unsigned int *v, int *dir) { (void)h; (void)dir; if (v) *v = 4; return 0; }
int snd_pcm_hw_params_get_periods_min(const snd_pcm_hw_params_t *h, unsigned int *v, int *dir) { (void)h; (void)dir; if (v) *v = 1; return 0; }
int snd_pcm_hw_params_get_periods_max(const snd_pcm_hw_params_t *h, unsigned int *v, int *dir) { (void)h; (void)dir; if (v) *v = 64; return 0; }
int snd_pcm_hw_params_set_periods(snd_pcm_t *p, snd_pcm_hw_params_t *h, unsigned int v, int dir) { (void)p; (void)h; (void)v; (void)dir; return 0; }
int snd_pcm_hw_params_can_pause(const snd_pcm_hw_params_t *h) { (void)h; return 0; }
int snd_pcm_hw_params_can_resume(const snd_pcm_hw_params_t *h) { (void)h; return 0; }
int snd_pcm_hw_params_get_access(const snd_pcm_hw_params_t *h, int *a) { (void)h; if (a) *a = 3 /* RW_INTERLEAVED */; return 0; }
int snd_pcm_hw_params_get_sbits(const snd_pcm_hw_params_t *h) { (void)h; return 16; }
int snd_pcm_hw_params_current(snd_pcm_t *p, snd_pcm_hw_params_t *h) { (void)p; (void)h; return 0; }

/* keep in sync with alsa-lib 1.2.x: sizeof(snd_pcm_status_t) == 144.
 * cpal asserts the runtime value is <= its compile-time STATUS_SIZE. */
size_t snd_pcm_status_sizeof(void) { return 144; }
int snd_pcm_status_malloc(snd_pcm_status_t **s) { if (!s) return -1; *s = calloc(1, 144); return *s ? 0 : -1; }
void snd_pcm_status_free(snd_pcm_status_t *s) { free(s); }
int snd_pcm_status(snd_pcm_t *p, snd_pcm_status_t *s) { (void)p; (void)s; return 0; }
void snd_pcm_status_get_htstamp(const snd_pcm_status_t *s, struct timespec *t) { (void)s; if (t) { t->tv_sec = 0; t->tv_nsec = 0; } }
void snd_pcm_status_get_trigger_htstamp(const snd_pcm_status_t *s, struct timespec *t) { (void)s; if (t) { t->tv_sec = 0; t->tv_nsec = 0; } }
long snd_pcm_status_get_delay(const snd_pcm_status_t *s) { (void)s; return 0; }
snd_pcm_uframes_t snd_pcm_status_get_avail(const snd_pcm_status_t *s) { (void)s; return 4096; }

size_t snd_pcm_info_sizeof(void) { return 256; }
int snd_pcm_info_malloc(snd_pcm_info_t **i) { if (!i) return -1; *i = calloc(1, 256); return *i ? 0 : -1; }
void snd_pcm_info_free(snd_pcm_info_t *i) { free(i); }
void snd_pcm_info_set_device(snd_pcm_info_t *i, unsigned int d) { (void)i; (void)d; }
void snd_pcm_info_set_subdevice(snd_pcm_info_t *i, unsigned int d) { (void)i; (void)d; }
void snd_pcm_info_set_stream(snd_pcm_info_t *i, int s) { (void)i; (void)s; }
const char *snd_pcm_info_get_name(const snd_pcm_info_t *i) { (void)i; return "polydroid"; }
unsigned int snd_pcm_info_get_devices_min(void) { return 0; }
int snd_pcm_info_is_substream(snd_pcm_info_t *i) { (void)i; return 1; }

snd_pcm_sframes_t snd_pcm_avail(snd_pcm_t *p) { (void)p; return 4096; }
snd_pcm_sframes_t snd_pcm_avail_update(snd_pcm_t *p) { (void)p; return 4096; }
int snd_pcm_pause(snd_pcm_t *p, int e) { (void)p; (void)e; return 0; }
int snd_pcm_resume(snd_pcm_t *p) { (void)p; return 0; }
snd_pcm_sframes_t snd_pcm_forward(snd_pcm_t *p, snd_pcm_uframes_t f) { (void)p; return (snd_pcm_sframes_t)f; }
int snd_pcm_delay(snd_pcm_t *p, snd_pcm_sframes_t *d) { (void)p; if (d) *d = 0; return 0; }
snd_pcm_sframes_t snd_pcm_bytes_to_frames(snd_pcm_t *p, ssize_t b) { (void)p; return (snd_pcm_sframes_t)(b / 4); }
ssize_t snd_pcm_frames_to_bytes(snd_pcm_t *p, snd_pcm_sframes_t f) { (void)p; return (ssize_t)f * 4; }
int snd_pcm_link(snd_pcm_t *a, snd_pcm_t *b) { (void)a; (void)b; return 0; }
int snd_pcm_unlink(snd_pcm_t *p) { (void)p; return 0; }
int snd_pcm_poll_descriptors_count(snd_pcm_t *p) { (void)p; return 1; }
int snd_pcm_poll_descriptors(snd_pcm_t *p, struct pollfd *pfds, unsigned int space) {
    (void)p;
    if (!pfds || space < 1) return -1;
    pfds[0].fd = 1;
    pfds[0].events = POLLOUT;
    return 1;
}
int snd_pcm_poll_descriptors_revents(snd_pcm_t *p, struct pollfd *pfds, unsigned int nfds, unsigned short *revents) {
    (void)p;
    if (!pfds || !revents || nfds < 1) return -1;
    *revents = pfds[0].revents & POLLOUT;
    return 0;
}
int snd_pcm_mmap_begin(snd_pcm_t *p, void **a, snd_pcm_uframes_t *o, snd_pcm_uframes_t *f) { (void)p; if (a) *a = NULL; (void)o; if (f) *f = 0; return 0; }
snd_pcm_sframes_t snd_pcm_mmap_commit(snd_pcm_t *p, snd_pcm_uframes_t o, snd_pcm_uframes_t f) { (void)p; (void)o; return (snd_pcm_sframes_t)f; }
int snd_pcm_mmap(snd_pcm_t *p, int i, void **a) { (void)p; (void)i; if (a) *a = NULL; return 0; }
int snd_pcm_munmap(snd_pcm_t *p, int i) { (void)p; (void)i; return 0; }
int snd_pcm_set_params(snd_pcm_t *p, int f, int a, unsigned int ch, unsigned int r, int s, snd_pcm_uframes_t l) {
    (void)a; (void)s; (void)l;
    if (p) { p->channels = (int)ch; p->rate = r; p->sample_bytes = alsa_format_bytes(f); }
    return 0;
}
int snd_pcm_get_params(snd_pcm_t *p, snd_pcm_uframes_t *buf, snd_pcm_uframes_t *per) {
    if (buf) *buf = 2048; if (per) *per = 512; (void)p; return 0;
}

/* ---- control devices (cpal enumerates them, hand it one virtual card) ---- */

int snd_ctl_open(snd_ctl_t **c, const char *name, int mode) { (void)name; (void)mode; if (!c) return -1; *c = calloc(1, sizeof(snd_ctl_t)); return *c ? 0 : -1; }
int snd_ctl_close(snd_ctl_t *c) { free(c); return 0; }
size_t snd_ctl_card_info_sizeof(void) { return 128; }
int snd_ctl_card_info_malloc(snd_ctl_card_info_t **i) { if (!i) return -1; *i = calloc(1, 128); return *i ? 0 : -1; }
void snd_ctl_card_info_free(snd_ctl_card_info_t *i) { free(i); }
void snd_ctl_card_info_clear(snd_ctl_card_info_t *i) { if (i) memset(i, 0, 128); }
int snd_ctl_card_info(snd_ctl_t *c, snd_ctl_card_info_t *i) { (void)c; (void)i; return 0; }
const char *snd_ctl_card_info_get_name(const snd_ctl_card_info_t *i) { (void)i; return "PolyDroid Audio"; }
const char *snd_ctl_card_info_get_id(const snd_ctl_card_info_t *i) { (void)i; return "polydroid"; }
const char *snd_ctl_card_info_get_mixername(const snd_ctl_card_info_t *i) { (void)i; return "PolyDroid Audio"; }
int snd_ctl_pcm_next_device(snd_ctl_t *c, int *d) { (void)c; if (d) *d = -1; return 0; }
int snd_ctl_pcm_info(snd_ctl_t *c, snd_pcm_info_t *i) { (void)c; (void)i; return -ENOENT; }
int snd_card_next(int *card) { if (card) *card = -1; return 0; }
int snd_config_update_free_global(void) { return 0; }
void snd_config_update_free(void *p) { (void)p; }

/* ---- channel maps (minimal) ---- */
snd_pcm_chmap_query_t **snd_pcm_query_chmaps(snd_pcm_t *p) { (void)p; return NULL; }
void snd_pcm_free_chmaps(snd_pcm_chmap_query_t **m) { (void)m; }

int snd_pcm_hw_params(snd_pcm_t *p, snd_pcm_hw_params_t *h) {
    (void)h;
    if (!p) return -1;
    if (!audio_enabled()) return 0;
    if (p->fd < 0) p->fd = connect_bridge();
    if (p->fd >= 0 && !p->started) {
        uint8_t hdr[12];
        uint32_t magic = AUDIO_MAGIC, rate = p->rate;
        memcpy(hdr + 0, &magic, 4);
        memcpy(hdr + 4, &rate, 4);
        hdr[8] = (uint8_t)(p->channels > 0 ? p->channels : 2);
        /* report the actual negotiated format so the bridge decodes
         * correctly (PA_SAMPLE_S16LE=3, PA_SAMPLE_FLOAT32LE=5) */
        hdr[9] = (p->sample_bytes == 4) ? PA_SAMPLE_FLOAT32LE : PA_SAMPLE_S16LE;
        hdr[10] = 0; hdr[11] = 0;
        if (write_all(p->fd, hdr, sizeof(hdr)) == 0) {
            p->started = 1;
            int sndbuf = 8192;
            setsockopt(p->fd, SOL_SOCKET, SO_SNDBUF, &sndbuf, sizeof(sndbuf));
            int fl = fcntl(p->fd, F_GETFL, 0);
            if (fl >= 0) fcntl(p->fd, F_SETFL, fl | O_NONBLOCK);
        } else {
            fprintf(stderr, "polydroid: audio header send failed, audio off\n");
            close(p->fd); p->fd = -1;
        }
    }
    return 0;
}

int snd_pcm_sw_params_current(snd_pcm_t *p, snd_pcm_sw_params_t *s) { (void)p; (void)s; return 0; }
int snd_pcm_sw_params_set_avail_min(snd_pcm_t *p, snd_pcm_sw_params_t *s, snd_pcm_uframes_t v) { (void)p; (void)s; (void)v; return 0; }
int snd_pcm_sw_params_set_start_threshold(snd_pcm_t *p, snd_pcm_sw_params_t *s, snd_pcm_uframes_t v) { (void)p; (void)s; (void)v; return 0; }
int snd_pcm_sw_params(snd_pcm_t *p, snd_pcm_sw_params_t *s) { (void)p; (void)s; return 0; }
int snd_pcm_sw_params_malloc(snd_pcm_sw_params_t **s) { if (!s) return -1; *s = calloc(1, 512); return *s ? 0 : -1; }
void snd_pcm_sw_params_free(snd_pcm_sw_params_t *s) { free(s); }
int snd_pcm_sw_params_set_tstamp_mode(snd_pcm_t *p, snd_pcm_sw_params_t *s, unsigned int m) { (void)p; (void)s; (void)m; return 0; }
int snd_pcm_sw_params_set_tstamp_type(snd_pcm_t *p, snd_pcm_sw_params_t *s, unsigned int t) { (void)p; (void)s; (void)t; return 0; }
void snd_pcm_sw_params_get_avail_min(const snd_pcm_sw_params_t *s, snd_pcm_uframes_t *v) { (void)s; if (v) *v = 512; }
void snd_pcm_sw_params_get_start_threshold(const snd_pcm_sw_params_t *s, snd_pcm_uframes_t *v) { (void)s; if (v) *v = 1; }

/* wait until the bridge socket can accept more data (blocking semantics) */
static int wait_writable(snd_pcm_t *p) {
    struct pollfd pfd;
    pfd.fd = p->fd;
    pfd.events = POLLOUT;
    for (;;) {
        int r = poll(&pfd, 1, 2000);
        if (r > 0) return 0;
        if (r == 0) return -1; /* 2s timeout */
        if (errno != EINTR) return -1;
    }
}

snd_pcm_sframes_t snd_pcm_writei(snd_pcm_t *p, const void *buf, snd_pcm_uframes_t frames) {
    if (!p) return -1;
    if (p->fd < 0 || !buf || !frames)
        return (snd_pcm_sframes_t)frames;
    int ch = (p->channels > 0 ? p->channels : 2);
    int bps = (p->sample_bytes > 0 ? p->sample_bytes : 2);
    int fsz = ch * bps;
    if (fsz > (int)sizeof(p->carry)) fsz = sizeof(p->carry);

    /* flush any partial frame carried over first */
    while (p->carry_len) {
        ssize_t w = send(p->fd, p->carry + p->carry_off, p->carry_len, MSG_NOSIGNAL);
        if (w < 0) {
            if (errno == EAGAIN || errno == EWOULDBLOCK) {
                if (p->nonblock) return -EAGAIN;
                if (wait_writable(p) != 0) { close(p->fd); p->fd = -1; return (snd_pcm_sframes_t)frames; }
                continue;
            }
            close(p->fd); p->fd = -1;
            return (snd_pcm_sframes_t)frames;
        }
        p->carry_off += (int)w;
        p->carry_len -= (int)w;
        if (!p->carry_len) p->carry_off = 0;
    }

    size_t want = (size_t)frames * (size_t)fsz;
    size_t done = 0;
    while (done < want) {
        ssize_t s = send(p->fd, (const char *)buf + done, want - done, MSG_NOSIGNAL);
        if (s < 0) {
            if (errno == EAGAIN || errno == EWOULDBLOCK) {
                if (p->nonblock) break;
                if (wait_writable(p) != 0) { close(p->fd); p->fd = -1; return (snd_pcm_sframes_t)frames; }
                continue;
            }
            close(p->fd); p->fd = -1;
            return (snd_pcm_sframes_t)frames;
        }
        done += (size_t)s;
    }
    size_t full = done / (size_t)fsz;
    size_t rem = done % (size_t)fsz;
    if (rem) {
        p->carry_len = fsz - (int)rem;
        p->carry_off = 0;
        memcpy(p->carry, (const char *)buf + full * fsz + rem, (size_t)p->carry_len);
        full += 1;
    }
    if (!full && p->nonblock) return -EAGAIN;
    if (full > frames) full = frames; /* never report more frames than asked */
    return (snd_pcm_sframes_t)full;
}

snd_pcm_sframes_t snd_pcm_readi(snd_pcm_t *p, void *buf, snd_pcm_uframes_t frames) { (void)p; (void)buf; return -1; }

int snd_pcm_recover(snd_pcm_t *p, int err, int silent) { (void)p; (void)err; (void)silent; return 0; }
int snd_pcm_prepare(snd_pcm_t *p) { (void)p; return 0; }
int snd_pcm_drain(snd_pcm_t *p) { (void)p; return 0; }
int snd_pcm_drop(snd_pcm_t *p) { (void)p; return 0; }
int snd_pcm_start(snd_pcm_t *p) { (void)p; return 0; }
int snd_pcm_nonblock(snd_pcm_t *p, int n) { if (p) p->nonblock = n; return 0; }
int snd_pcm_wait(snd_pcm_t *p, int ms) { (void)p; (void)ms; return 1; }

int snd_device_name_hint(int card, const char *iface, void ***hints) {
    (void)card; (void)iface;
    static void *empty[1] = { NULL };
    if (hints) *hints = empty;
    return 0;
}
char *snd_device_name_get_hint(const void *hint, const char *id) { (void)hint; (void)id; return NULL; }
int snd_device_name_free_hint(void **hints) { (void)hints; return 0; }

const char *snd_strerror(int e) { (void)e; return "polydroid alsa"; }
const char *snd_asoundlib_version(void) { return "1.2.0"; }
