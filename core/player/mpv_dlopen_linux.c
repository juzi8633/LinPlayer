//go:build linux && !android

// Linux 上 libmpv 走运行时 dlopen(TODO L1),不进 DT_NEEDED。
// 发行版之间 soname 分裂(Ubuntu 22.04 只有 .so.1,新发行版只有 .so.2),链死哪个都有一半机器起不来;
// 而且进程加载时就失败的话,UI 连「请装 libmpv」都说不出来。
// 符号设成 hidden:同名 mpv_* 一旦进动态符号表,就有被 libmpv 内部调用按 ELF 符号插入绑回这里、转圈递归的风险。

#include <dlfcn.h>
#include <pthread.h>
#include <stdint.h>
#include <stddef.h>

typedef struct lp_render_param lp_render_param;

static void *lib;
static pthread_once_t once = PTHREAD_ONCE_INIT;

static void load(void) {
    static const char *names[] = {"libmpv.so.2", "libmpv.so.1", "libmpv.so"};
    for (size_t i = 0; i < sizeof names / sizeof names[0] && !lib; i++)
        lib = dlopen(names[i], RTLD_NOW | RTLD_LOCAL);
}

static void *sym(const char *name) {
    pthread_once(&once, load);
    return lib ? dlsym(lib, name) : NULL;
}

#define H __attribute__((visibility("hidden")))
#define FWD(ret, name, fail, params, args) \
    H ret name params { ret (*f) params = (ret (*) params)sym(#name); return f ? f args : fail; }
#define FWDV(name, params, args) \
    H void name params { void (*f) params = (void (*) params)sym(#name); if (f) f args; }

// 缺库时 mpv_create 返回 NULL,调用方就此报错,后面这些不会再被调到
FWD(void *, mpv_create, NULL, (void), ())
FWD(int, mpv_initialize, -1, (void *h), (h))
FWD(int, mpv_set_option_string, -1, (void *h, const char *n, const char *v), (h, n, v))
FWD(int, mpv_set_property_string, -1, (void *h, const char *n, const char *v), (h, n, v))
FWD(int, mpv_command, -1, (void *h, const char **a), (h, a))
FWD(char *, mpv_get_property_string, NULL, (void *h, const char *n), (h, n))
FWDV(mpv_free, (void *p), (p))
FWD(void *, mpv_wait_event, NULL, (void *h, double t), (h, t))
FWDV(mpv_terminate_destroy, (void *h), (h))
FWD(int, mpv_request_log_messages, -1, (void *h, const char *l), (h, l))
FWD(int, mpv_render_context_create, -1, (void **o, void *h, lp_render_param *p), (o, h, p))
FWD(int, mpv_render_context_render, -1, (void *c, lp_render_param *p), (c, p))
FWD(uint64_t, mpv_render_context_update, 0, (void *c), (c))
FWDV(mpv_render_context_report_swap, (void *c), (c))
FWDV(mpv_render_context_free, (void *c), (c))
FWD(long long, mpv_get_time_us, 0, (void *h), (h))

// get_info 按值传结构体,宏里写不了前向声明,单写
struct lp_render_param { int type; void *data; };
H int mpv_render_context_get_info(void *c, lp_render_param p) {
    int (*f)(void *, lp_render_param) = (int (*)(void *, lp_render_param))sym("mpv_render_context_get_info");
    return f ? f(c, p) : -1;
}

// mpv_get_time_ns 是 client API 2.1 才有的;.so.1 上退回微秒换算
H long long mpv_get_time_ns(void *h) {
    long long (*f)(void *) = (long long (*)(void *))sym("mpv_get_time_ns");
    return f ? f(h) : mpv_get_time_us(h) * 1000;
}
