/*
 * input_bridge.c —— 漢藝啟動器 · 触控输入桥（C 侧）
 *
 * 协议（与 net.hanyi.launcher.controls.InputBridge 的 native 方法一一对应）：
 *   event_t 固定 28 字节，无锁 SPSC 环形队列；UI 线程只做 push，绝不阻塞。
 *   kind：0 触摸(dispatch) | 1 鼠标移动 | 2 键盘 | 3 单击 | 4 滚轮 | 5 灵敏度 | 6 锁视角
 *         flags&1 => x/y 是绝对坐标（FCL·点击）；否则 dx/dy 是相对增量（ZL2·移动）
 *   文本走 0x7f 带外帧：[tag 1B][len 2B LE][utf8]
 *
 * 对端 = Amethyst / Zalith 的 jre_lwjgl3glfw input pipe 读取端。
 * 找不到对端时写 /dev/null，启动器不崩、游戏无效果，安静降级。
 */
#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <android/log.h>
#include <sys/socket.h>
#include <sys/un.h>

#define TAG "HanYi:Native"
#define LOG(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

typedef struct {
    uint8_t  kind, action, flags, pad;
    int16_t  button, key;
    float    x, y, dx, dy, fval;
} event_t;
_Static_assert(sizeof(event_t) == 28, "event_t must be 28 bytes");

#define Q_CAP 1024
static event_t q[Q_CAP];
static _Atomic uint32_t q_head = 0, q_tail = 0;
static int pipe_fd = -1;
static pthread_t worker;
static atomic_bool running = ATOMIC_VAR_INIT(0);

/* ---- 吞掉 unused-result，避免 -Werror 误伤（写管道本就不关心瞬时返回值） ---- */
static inline void xwrite(int fd, const void *b, size_t n) {
    const char *p = b;
    while (n > 0) {
        ssize_t w = write(fd, p, n);
        if (w > 0) { p += w; n -= (size_t)w; }
        else if (w < 0 && errno != EAGAIN && errno != EWOULDBLOCK) break;
        else usleep(1000);
    }
}

static int open_pipe(void) {
    const char *path = getenv("HANYI_INPUT_PIPE");
    if (path && *path) {
        struct sockaddr_un addr;
        int fd = socket(AF_UNIX, SOCK_STREAM, 0);
        if (fd >= 0) {
            memset(&addr, 0, sizeof(addr));
            addr.sun_family = AF_UNIX;
            snprintf(addr.sun_path + 1, sizeof(addr.sun_path) - 1, "%s", path);
            if (connect(fd, (struct sockaddr *)&addr, sizeof(addr.sun_family) + strlen(path) + 1) == 0) {
                fcntl(fd, F_SETFL, O_NONBLOCK);
                LOG("已连接输入管道 @%s", path);
                return fd;
            }
            close(fd);
        }
    }
    LOG("未连接 HANYI_INPUT_PIPE：触控事件将丢弃（接 jre_lwjgl3glfw 即生效）");
    return -1;
}

static void push(const event_t *e) {
    uint32_t h = atomic_load_explicit(&q_head, memory_order_acquire);
    uint32_t nxt = (h + 1) & (Q_CAP - 1);
    if (nxt == atomic_load_explicit(&q_tail, memory_order_acquire)) return; /* 满则丢一帧，保流畅 */
    q[h] = *e;
    atomic_store_explicit(&q_head, nxt, memory_order_release);
}
static int pop(event_t *e) {
    uint32_t t = atomic_load_explicit(&q_tail, memory_order_acquire);
    if (t == atomic_load_explicit(&q_head, memory_order_acquire)) return 0;
    *e = q[t];
    atomic_store_explicit(&q_tail, (t + 1) & (Q_CAP - 1), memory_order_release);
    return 1;
}

static void *write_loop(void *_) {
    event_t batch[16];
    while (atomic_load(&running)) {
        int n = 0;
        while (n < 16 && pop(&batch[n])) n++;
        if (n == 0) { usleep(8000); continue; }
        if (pipe_fd < 0) pipe_fd = open_pipe();
        if (pipe_fd >= 0) xwrite(pipe_fd, batch, (size_t)n * sizeof(event_t));
    }
    return NULL;
}

static void ensure_worker(void) {
    if (atomic_exchange(&running, 1)) return;
    pthread_attr_t a; pthread_attr_init(&a);
    pthread_attr_setdetachstate(&a, PTHREAD_CREATE_DETACHED);
    pipe_fd = open_pipe();
    pthread_create(&worker, &a, write_loop, NULL);
    pthread_attr_destroy(&a);
}

static void ev_zero(event_t *e) { memset(e, 0, sizeof(*e)); }

JNIEXPORT void JNICALL Java_net_hanyi_launcher_controls_InputBridge_nativeDispatch(
        JNIEnv *env, jclass c, jint action, jfloat x, jfloat y, jint btn) {
    (void)env; (void)c;
    event_t e; ev_zero(&e);
    e.kind = 0; e.action = (uint8_t)action; e.button = (int16_t)btn; e.x = x; e.y = y;
    push(&e); ensure_worker();
}

JNIEXPORT void JNICALL Java_net_hanyi_launcher_controls_InputBridge_nativeMoveAbs(
        JNIEnv *env, jclass c, jfloat x, jfloat y) {
    (void)env; (void)c;
    event_t e; ev_zero(&e);
    e.kind = 1; e.flags = 1; e.x = x; e.y = y;   /* 绝对 warp */
    push(&e); ensure_worker();
}

JNIEXPORT void JNICALL Java_net_hanyi_launcher_controls_InputBridge_nativePan(
        JNIEnv *env, jclass c, jfloat dx, jfloat dy) {
    (void)env; (void)c;
    event_t e; ev_zero(&e);
    e.kind = 1; e.flags = 0; e.dx = dx; e.dy = dy; /* 相对增量 */
    push(&e); ensure_worker();
}

JNIEXPORT void JNICALL Java_net_hanyi_launcher_controls_InputBridge_nativeKey(
        JNIEnv *env, jclass c, jint key, jboolean down) {
    (void)env; (void)c;
    event_t e; ev_zero(&e);
    e.kind = 2; e.action = down ? 1 : 0; e.key = (int16_t)key;
    push(&e); ensure_worker();
}

JNIEXPORT void JNICALL Java_net_hanyi_launcher_controls_InputBridge_nativeText(
        JNIEnv *env, jclass c, jstring txt) {
    (void)c;
    if (!txt) return;
    const char *s = (*env)->GetStringUTFChars(env, txt, NULL);
    if (!s) return;
    uint16_t len = (uint16_t)strlen(s);
    if (pipe_fd < 0) pipe_fd = open_pipe();
    if (pipe_fd >= 0) {
        char hdr[3]; hdr[0] = (char)0x7f; hdr[1] = (char)(len & 0xff); hdr[2] = (char)(len >> 8);
        xwrite(pipe_fd, hdr, 3); xwrite(pipe_fd, s, len);
    }
    (*env)->ReleaseStringUTFChars(env, txt, s);
}

JNIEXPORT void JNICALL Java_net_hanyi_launcher_controls_InputBridge_nativeClick(
        JNIEnv *env, jclass c, jint btn) {
    (void)env; (void)c;
    event_t e; ev_zero(&e);
    e.kind = 3; e.button = (int16_t)btn;
    push(&e); ensure_worker();
}

JNIEXPORT void JNICALL Java_net_hanyi_launcher_controls_InputBridge_nativeScroll(
        JNIEnv *env, jclass c, jfloat dy) {
    (void)env; (void)c;
    event_t e; ev_zero(&e);
    e.kind = 4; e.fval = dy;
    push(&e); ensure_worker();
}

JNIEXPORT void JNICALL Java_net_hanyi_launcher_controls_InputBridge_nativeSetSens(
        JNIEnv *env, jclass c, jfloat s) {
    (void)env; (void)c;
    event_t e; ev_zero(&e);
    e.kind = 5; e.fval = s;
    push(&e); ensure_worker();
}

JNIEXPORT void JNICALL Java_net_hanyi_launcher_controls_InputBridge_nativeToggleLookLock(
        JNIEnv *env, jclass c) {
    (void)env; (void)c;
    event_t e; ev_zero(&e);
    e.kind = 6;
    push(&e); ensure_worker();
}

__attribute__((visibility("default")))
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)vm; (void)reserved;
    ensure_worker();
    return JNI_VERSION_1_6;
}
