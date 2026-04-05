// MinGW CRT compatibility shims for K/N LLVM linker.
// Provides symbols that lld cannot resolve from MinGW's import libraries.

#include <sys/stat.h>
#include <string.h>
#include <time.h>

// __timezone and __tzname: referenced as dllimport by ICU when compiled
// with __GLIBC__ misdetection. We provide non-dllimport definitions
// and corresponding __imp_ pointers for lld to resolve dllimport refs.
long __timezone_val = 0;
char* __tzname_val[2] = { 0, 0 };

// lld resolves __declspec(dllimport) X as __imp_X
long* __imp___timezone = &__timezone_val;
char** __imp___tzname = __tzname_val;

// stat variants - MinGW headers inline these with dllimport,
// which lld cannot resolve. Provide concrete implementations.
__attribute__((used))
int stat64i32(const char* path, struct _stat64i32* buf) {
    struct _stat64 st;
    int ret = _stat64(path, &st);
    if (ret == 0 && buf) {
        memset(buf, 0, sizeof(*buf));
        buf->st_dev = st.st_dev;
        buf->st_ino = st.st_ino;
        buf->st_mode = st.st_mode;
        buf->st_nlink = st.st_nlink;
        buf->st_uid = st.st_uid;
        buf->st_gid = st.st_gid;
        buf->st_rdev = st.st_rdev;
        buf->st_size = (_off_t)st.st_size;
        buf->st_atime = st.st_atime;
        buf->st_mtime = st.st_mtime;
        buf->st_ctime = st.st_ctime;
    }
    return ret;
}

__attribute__((used))
int stat32(const char* path, struct _stat32* buf) {
    struct _stat64 st;
    int ret = _stat64(path, &st);
    if (ret == 0 && buf) {
        memset(buf, 0, sizeof(*buf));
        buf->st_dev = st.st_dev;
        buf->st_ino = st.st_ino;
        buf->st_mode = st.st_mode;
        buf->st_nlink = st.st_nlink;
        buf->st_uid = st.st_uid;
        buf->st_gid = st.st_gid;
        buf->st_rdev = st.st_rdev;
        buf->st_size = (_off_t)st.st_size;
        buf->st_atime = (__time32_t)st.st_atime;
        buf->st_mtime = (__time32_t)st.st_mtime;
        buf->st_ctime = (__time32_t)st.st_ctime;
    }
    return ret;
}

__attribute__((used))
int stat32i64(const char* path, struct _stat32i64* buf) {
    struct _stat64 st;
    int ret = _stat64(path, &st);
    if (ret == 0 && buf) {
        memset(buf, 0, sizeof(*buf));
        buf->st_dev = st.st_dev;
        buf->st_ino = st.st_ino;
        buf->st_mode = st.st_mode;
        buf->st_nlink = st.st_nlink;
        buf->st_uid = st.st_uid;
        buf->st_gid = st.st_gid;
        buf->st_rdev = st.st_rdev;
        buf->st_size = st.st_size;
        buf->st_atime = (__time32_t)st.st_atime;
        buf->st_mtime = (__time32_t)st.st_mtime;
        buf->st_ctime = (__time32_t)st.st_ctime;
    }
    return ret;
}
