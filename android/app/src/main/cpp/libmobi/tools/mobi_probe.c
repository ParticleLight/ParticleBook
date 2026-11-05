/*
 * mobi_probe.c - standalone libmobi CLI probe for Android arm64 (P0-5b).
 *
 * Usage: mobi_probe [--corrupt] <file.mobi|azw3>
 *
 * --corrupt copies the first half of the input to
 * /data/local/tmp/libmobi-p0/_probe_corrupt.azw3 and parses that copy.
 * Prints machine-readable key=value lines and exits 0 only when the full
 * load+parse path succeeds.
 */
#define _POSIX_C_SOURCE 200809L

#include <mobi.h>

#include <errno.h>
#include <inttypes.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

static double now_ms(void) {
    struct timespec ts;
    if (clock_gettime(CLOCK_MONOTONIC, &ts) != 0) {
        return -1.0;
    }
    return (double)ts.tv_sec * 1000.0 + (double)ts.tv_nsec / 1000000.0;
}

static void read_vmhwm(void) {
    FILE *f = fopen("/proc/self/status", "r");
    if (!f) {
        printf("vmhwm_kb=-1\n");
        return;
    }
    char line[256];
    while (fgets(line, sizeof(line), f)) {
        if (strncmp(line, "VmHWM:", 6) == 0) {
            char *p = line + 6;
            while (*p == ' ' || *p == '\t') p++;
            printf("vmhwm_kb=%s", p);
            fclose(f);
            return;
        }
    }
    fclose(f);
    printf("vmhwm_kb=-1\n");
}

static size_t sum_parts(const MOBIPart *p) {
    size_t total = 0;
    for (; p != NULL; p = p->next) {
        total += p->size;
    }
    return total;
}

static int count_parts(const MOBIPart *p) {
    int n = 0;
    for (; p != NULL; p = p->next) n++;
    return n;
}

static void print_html_head(const MOBIRawml *rawml, size_t maxlen) {
    const MOBIPart *p = rawml->flow ? rawml->flow : rawml->markup;
    printf("html_head=");
    if (!p || !p->data || p->size == 0) {
        printf("(none)\n");
        return;
    }
    size_t n = p->size < maxlen ? p->size : maxlen;
    for (size_t i = 0; i < n; i++) {
        unsigned char c = p->data[i];
        if (c >= 32 && c < 127) {
            putchar((int)c);
        } else {
            putchar('.');
        }
    }
    putchar('\n');
}

static int copy_prefix(const char *src, const char *dst, size_t limit) {
    FILE *in = fopen(src, "rb");
    if (!in) {
        fprintf(stderr, "open source failed: %s\n", strerror(errno));
        return -1;
    }
    FILE *out = fopen(dst, "wb");
    if (!out) {
        fprintf(stderr, "open target failed: %s\n", strerror(errno));
        fclose(in);
        return -1;
    }
    unsigned char buf[65536];
    size_t written = 0;
    while (written < limit) {
        size_t want = limit - written;
        if (want > sizeof(buf)) want = sizeof(buf);
        size_t got = fread(buf, 1, want, in);
        if (got == 0) break;
        if (fwrite(buf, 1, got, out) != got) {
            fprintf(stderr, "write failed\n");
            fclose(in);
            fclose(out);
            return -1;
        }
        written += got;
    }
    fclose(in);
    fclose(out);
    printf("corrupt_truncated_bytes=%zu\n", written);
    return 0;
}

int main(int argc, char **argv) {
    const char *input = NULL;
    int corrupt = 0;
    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "--corrupt") == 0) {
            corrupt = 1;
        } else if (!input) {
            input = argv[i];
        } else {
            fprintf(stderr, "unexpected argument: %s\n", argv[i]);
            return 2;
        }
    }
    if (!input) {
        fprintf(stderr, "usage: %s [--corrupt] <file>\n", argv[0]);
        return 2;
    }

    printf("probe=libmobi_device_probe\n");
    printf("libmobi_version=%s\n", mobi_version());

    struct stat st;
    if (stat(input, &st) != 0) {
        fprintf(stderr, "stat failed: %s\n", strerror(errno));
        return 3;
    }
    printf("file=%s\n", input);
    printf("file_size=%lld\n", (long long)st.st_size);
    printf("mode=%s\n", corrupt ? "corrupt-half" : "normal");

    const char *load_path = input;
    if (corrupt) {
        const char *tmp = "/data/local/tmp/libmobi-p0/_probe_corrupt.azw3";
        size_t limit = (size_t)st.st_size / 2;
        if (limit == 0) limit = 1;
        if (copy_prefix(input, tmp, limit) != 0) return 3;
        load_path = tmp;
        printf("corrupt_path=%s\n", tmp);
    }

    MOBIData *m = mobi_init();
    if (!m) {
        fprintf(stderr, "mobi_init failed\n");
        return 4;
    }

    double t0 = now_ms();
    MOBI_RET ret = mobi_load_filename(m, load_path);
    double load_ms = now_ms() - t0;
    printf("load_rc=%d\n", (int)ret);
    printf("load_ms=%.3f\n", load_ms);
    if (ret != MOBI_SUCCESS) {
        fprintf(stderr, "mobi_load_filename failed rc=%d\n", (int)ret);
        mobi_free(m);
        return 5;
    }

    printf("encrypted=%d\n", mobi_is_encrypted(m) ? 1 : 0);
    printf("kf8=%d\n", mobi_is_kf8(m) ? 1 : 0);
    printf("fileversion=%zu\n", mobi_get_fileversion(m));

    char *title = mobi_meta_get_title(m);
    char *author = mobi_meta_get_author(m);
    printf("title=%s\n", title ? title : "(null)");
    printf("author=%s\n", author ? author : "(null)");
    free(title);
    free(author);

    MOBIRawml *rawml = mobi_init_rawml(m);
    if (!rawml) {
        fprintf(stderr, "mobi_init_rawml failed\n");
        mobi_free(m);
        return 6;
    }

    t0 = now_ms();
    ret = mobi_parse_rawml(rawml, m);
    double parse_ms = now_ms() - t0;
    printf("parse_rc=%d\n", (int)ret);
    printf("parse_ms=%.3f\n", parse_ms);

    size_t flow_bytes = sum_parts(rawml->flow);
    size_t markup_bytes = sum_parts(rawml->markup);
    size_t resource_bytes = sum_parts(rawml->resources);
    printf("flow_parts=%d\n", count_parts(rawml->flow));
    printf("markup_parts=%d\n", count_parts(rawml->markup));
    printf("resource_parts=%d\n", count_parts(rawml->resources));
    printf("flow_bytes=%zu\n", flow_bytes);
    printf("markup_bytes=%zu\n", markup_bytes);
    printf("resource_bytes=%zu\n", resource_bytes);
    printf("rawml_bytes=%zu\n", flow_bytes + markup_bytes + resource_bytes);
    print_html_head(rawml, 256);
    read_vmhwm();

    mobi_free_rawml(rawml);
    mobi_free(m);

    if (ret != MOBI_SUCCESS) {
        fprintf(stderr, "mobi_parse_rawml failed rc=%d\n", (int)ret);
        return 7;
    }
    printf("ok=1\n");
    return 0;
}
