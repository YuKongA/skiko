/**
 * ANGLE bridge for mingwX64.
 *
 * Dynamically loads ANGLE's libEGL.dll and provides:
 * - EGL display/context/surface creation (D3D11 backend)
 * - Skia DirectContext creation from ANGLE's GL interface
 * - Surface resize, swap buffers, cleanup
 *
 * Based on skiko/src/awtMain/cpp/windows/AngleRedrawer.cc
 */

#include <Windows.h>
#include <cstdio>
#include <cstdint>
#include <string>
#include <algorithm>

// EGL headers from Skia's bundled ANGLE
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <EGL/eglext_angle.h>

// Skia headers
#include "include/core/SkData.h"
#include "include/core/SkString.h"
#include "include/gpu/ganesh/GrContextOptions.h"
#include "ganesh/GrBackendSurface.h"
#include "ganesh/GrDirectContext.h"
#include "ganesh/gl/GrGLBackendSurface.h"
#include "ganesh/gl/GrGLDirectContext.h"
#include "ganesh/gl/GrGLAssembleInterface.h"

// GL constants we need (avoid dependency on internal Skia headers)
#ifndef GR_GL_FRAMEBUFFER_BINDING
#define GR_GL_FRAMEBUFFER_BINDING 0x8CA6
#endif
#ifndef GR_GL_RGBA8
#define GR_GL_RGBA8 0x8058
#endif

// Dynamically loaded EGL function pointers
static HMODULE g_eglLib = nullptr;
typedef EGLDisplay (EGLAPIENTRY *PFN_eglGetPlatformDisplayEXT)(EGLenum, void*, const EGLint*);
typedef EGLBoolean (EGLAPIENTRY *PFN_eglInitialize)(EGLDisplay, EGLint*, EGLint*);
typedef EGLBoolean (EGLAPIENTRY *PFN_eglChooseConfig)(EGLDisplay, const EGLint*, EGLConfig*, EGLint, EGLint*);
typedef EGLContext  (EGLAPIENTRY *PFN_eglCreateContext)(EGLDisplay, EGLConfig, EGLContext, const EGLint*);
typedef EGLSurface  (EGLAPIENTRY *PFN_eglCreateWindowSurface)(EGLDisplay, EGLConfig, EGLNativeWindowType, const EGLint*);
typedef EGLBoolean (EGLAPIENTRY *PFN_eglMakeCurrent)(EGLDisplay, EGLSurface, EGLSurface, EGLContext);
typedef EGLBoolean (EGLAPIENTRY *PFN_eglSwapBuffers)(EGLDisplay, EGLSurface);
typedef EGLBoolean (EGLAPIENTRY *PFN_eglSwapInterval)(EGLDisplay, EGLint);
typedef EGLBoolean (EGLAPIENTRY *PFN_eglDestroySurface)(EGLDisplay, EGLSurface);
typedef EGLBoolean (EGLAPIENTRY *PFN_eglDestroyContext)(EGLDisplay, EGLContext);
typedef EGLBoolean (EGLAPIENTRY *PFN_eglTerminate)(EGLDisplay);
typedef void*       (EGLAPIENTRY *PFN_eglGetProcAddress)(const char*);

typedef EGLBoolean (EGLAPIENTRY *PFN_eglQuerySurface)(EGLDisplay, EGLSurface, EGLint, EGLint*);

static PFN_eglGetPlatformDisplayEXT p_eglGetPlatformDisplayEXT = nullptr;
static PFN_eglQuerySurface           p_eglQuerySurface = nullptr;
static PFN_eglInitialize            p_eglInitialize = nullptr;
static PFN_eglChooseConfig           p_eglChooseConfig = nullptr;
static PFN_eglCreateContext          p_eglCreateContext = nullptr;
static PFN_eglCreateWindowSurface    p_eglCreateWindowSurface = nullptr;
static PFN_eglMakeCurrent            p_eglMakeCurrent = nullptr;
static PFN_eglSwapBuffers            p_eglSwapBuffers = nullptr;
static PFN_eglSwapInterval           p_eglSwapInterval = nullptr;
static PFN_eglDestroySurface         p_eglDestroySurface = nullptr;
static PFN_eglDestroyContext         p_eglDestroyContext = nullptr;
static PFN_eglTerminate              p_eglTerminate = nullptr;
static PFN_eglGetProcAddress         p_eglGetProcAddress = nullptr;

// ============= Shader persistent cache =============

static std::string getShaderCacheDir() {
    // Place the cache next to the executable, matching the compose-resources/ convention.
    // This keeps the cache per-application and portable.
    char buf[MAX_PATH];
    DWORD len = GetModuleFileNameA(nullptr, buf, MAX_PATH);
    if (len == 0 || len >= MAX_PATH) return "";
    std::string exePath(buf, len);
    auto sep = exePath.find_last_of("\\/");
    if (sep == std::string::npos) return "";
    std::string dir = exePath.substr(0, sep) + "\\shader_cache";
    CreateDirectoryA(dir.c_str(), nullptr);
    return dir;
}

static std::string keyToFileName(const SkData& key) {
    const uint8_t* bytes = (const uint8_t*)key.data();
    size_t len = key.size();
    std::string hex;
    hex.reserve(len * 2 + 6);
    for (size_t i = 0; i < len; i++) {
        char buf[3];
        snprintf(buf, 3, "%02x", bytes[i]);
        hex += buf;
    }
    hex += ".cache";
    return hex;
}

class FilePersistentCache : public GrContextOptions::PersistentCache {
    std::string cacheDir;
public:
    FilePersistentCache(const std::string& dir) : cacheDir(dir) {}

    sk_sp<SkData> load(const SkData& key) override {
        if (cacheDir.empty()) return nullptr;
        std::string path = cacheDir + "\\" + keyToFileName(key);
        FILE* f = fopen(path.c_str(), "rb");
        if (!f) return nullptr;
        fseek(f, 0, SEEK_END);
        long size = ftell(f);
        if (size <= 0) { fclose(f); return nullptr; }
        fseek(f, 0, SEEK_SET);
        sk_sp<SkData> data = SkData::MakeUninitialized(size);
        fread(data->writable_data(), 1, size, f);
        fclose(f);
        return data;
    }

    void store(const SkData& key, const SkData& data,
               const SkString& /*description*/) override {
        if (cacheDir.empty()) return;
        std::string path = cacheDir + "\\" + keyToFileName(key);
        FILE* f = fopen(path.c_str(), "wb");
        if (!f) return;
        fwrite(data.data(), 1, data.size(), f);
        fclose(f);
    }
};

static FilePersistentCache* getShaderCache() {
    static FilePersistentCache cache(getShaderCacheDir());
    return &cache;
}

// ============= ANGLE EGL blob cache (EGL_ANDROID_blob_cache) =============

static void EGLAPIENTRY angleBlobCacheSet(const void* key, EGLsizeiANDROID keySize,
                                          const void* value, EGLsizeiANDROID valueSize) {
    auto* cache = getShaderCache();
    auto keyData = SkData::MakeWithoutCopy(key, keySize);
    auto valData = SkData::MakeWithoutCopy(value, valueSize);
    SkString desc;
    cache->store(*keyData, *valData, desc);
}

static EGLsizeiANDROID EGLAPIENTRY angleBlobCacheGet(const void* key, EGLsizeiANDROID keySize,
                                                     void* value, EGLsizeiANDROID valueSize) {
    auto* cache = getShaderCache();
    auto keyData = SkData::MakeWithoutCopy(key, keySize);
    sk_sp<SkData> cached = cache->load(*keyData);
    if (!cached) return 0;
    if (value && (EGLsizeiANDROID)cached->size() <= valueSize) {
        memcpy(value, cached->data(), cached->size());
    }
    return (EGLsizeiANDROID)cached->size();
}

typedef void (EGLAPIENTRY *PFN_eglSetBlobCacheFuncsANDROID)(
    EGLDisplay dpy,
    void (EGLAPIENTRY *set)(const void*, EGLsizeiANDROID, const void*, EGLsizeiANDROID),
    EGLsizeiANDROID (EGLAPIENTRY *get)(const void*, EGLsizeiANDROID, void*, EGLsizeiANDROID));

static void setupAngleBlobCache(EGLDisplay display) {
    if (!g_eglLib) return;
    auto fn = (PFN_eglSetBlobCacheFuncsANDROID)GetProcAddress(g_eglLib, "eglSetBlobCacheFuncsANDROID");
    if (fn) {
        fn(display, angleBlobCacheSet, angleBlobCacheGet);
    }
}

/**
 * Pre-window EGL initialization state.
 * Created by angle_preinit_display() before any HWND exists, using a screen DC.
 * Consumed by angle_attach_window() which transfers ownership to AngleDevice.
 */
struct AnglePreInit {
    EGLDisplay display;
    EGLContext context;
    EGLConfig  surfaceConfig;
    HDC        screenDC;
    bool       consumed;

    AnglePreInit() : display(EGL_NO_DISPLAY), context(EGL_NO_CONTEXT),
        surfaceConfig(nullptr), screenDC(nullptr), consumed(false) {}

    ~AnglePreInit() {
        if (consumed) return;  // Ownership transferred to AngleDevice
        if (EGL_NO_CONTEXT != context && p_eglDestroyContext) {
            p_eglDestroyContext(display, context);
        }
        if (EGL_NO_DISPLAY != display && p_eglTerminate) {
            p_eglTerminate(display);
        }
        if (screenDC) {
            ReleaseDC(NULL, screenDC);
        }
    }
};

struct AngleDevice {
    HWND window;
    HDC device;
    EGLDisplay display;
    EGLContext context;
    EGLSurface surface;
    EGLConfig surfaceConfig;
    sk_sp<const GrGLInterface> backendContext;
    GrDirectContext* grContext;
    EGLint currentSwapInterval;
    EGLint surfaceWidth;
    EGLint surfaceHeight;

    AngleDevice() : window(nullptr), device(nullptr),
        display(EGL_NO_DISPLAY), context(EGL_NO_CONTEXT),
        surface(EGL_NO_SURFACE), surfaceConfig(nullptr), grContext(nullptr),
        currentSwapInterval(-1), surfaceWidth(0), surfaceHeight(0) {}

    ~AngleDevice() {
        if (grContext) {
            grContext->abandonContext();
            grContext->unref();
            grContext = nullptr;
        }
        backendContext.reset(nullptr);
        if (EGL_NO_CONTEXT != context && p_eglDestroyContext) {
            p_eglDestroyContext(display, context);
        }
        if (EGL_NO_SURFACE != surface && p_eglDestroySurface) {
            p_eglDestroySurface(display, surface);
        }
        if (EGL_NO_DISPLAY != display && p_eglTerminate) {
            p_eglTerminate(display);
        }
        if (device && window) {
            ReleaseDC(window, device);
        }
    }
};

static bool loadEglLibrary() {
    if (g_eglLib) return true;

    g_eglLib = LoadLibraryA("libEGL.dll");
    if (!g_eglLib) {
        fprintf(stderr, "[angle] Failed to load libEGL.dll\n");
        return false;
    }

    #define LOAD_EGL(name) \
        p_##name = (PFN_##name)GetProcAddress(g_eglLib, #name); \
        if (!p_##name) { fprintf(stderr, "[angle] Failed to load " #name "\n"); goto fail; }

    LOAD_EGL(eglQuerySurface)
    LOAD_EGL(eglInitialize)
    LOAD_EGL(eglChooseConfig)
    LOAD_EGL(eglCreateContext)
    LOAD_EGL(eglCreateWindowSurface)
    LOAD_EGL(eglMakeCurrent)
    LOAD_EGL(eglSwapBuffers)
    LOAD_EGL(eglSwapInterval)
    LOAD_EGL(eglDestroySurface)
    LOAD_EGL(eglDestroyContext)
    LOAD_EGL(eglTerminate)
    LOAD_EGL(eglGetProcAddress)
    #undef LOAD_EGL

    p_eglGetPlatformDisplayEXT = (PFN_eglGetPlatformDisplayEXT)
        GetProcAddress(g_eglLib, "eglGetPlatformDisplayEXT");
    if (!p_eglGetPlatformDisplayEXT) {
        fprintf(stderr, "[angle] Failed to load eglGetPlatformDisplayEXT\n");
        goto fail;
    }

    return true;

fail:
    FreeLibrary(g_eglLib);
    g_eglLib = nullptr;
    return false;
}

static bool initAngleSurface(AngleDevice* dev, EGLint width, EGLint height) {
    // Skip rebuild if surface already exists at the requested size
    if (EGL_NO_SURFACE != dev->surface && dev->surfaceWidth == width && dev->surfaceHeight == height) {
        return true;
    }

    const EGLint surfaceAttribs[] = {
        EGL_FIXED_SIZE_ANGLE, EGL_TRUE,
        EGL_WIDTH, width,
        EGL_HEIGHT, height,
        EGL_NONE, EGL_NONE
    };
    if (EGL_NO_SURFACE != dev->surface) {
        p_eglDestroySurface(dev->display, dev->surface);
    }
    dev->surface = p_eglCreateWindowSurface(dev->display, dev->surfaceConfig, dev->window, surfaceAttribs);
    if (EGL_NO_SURFACE == dev->surface) {
        dev->surfaceWidth = 0;
        dev->surfaceHeight = 0;
        return false;
    }
    if (!p_eglMakeCurrent(dev->display, dev->surface, dev->surface, dev->context)) {
        return false;
    }
    dev->surfaceWidth = width;
    dev->surfaceHeight = height;

    return true;
}

// ============= Exported C functions for Kotlin/Native =============

extern "C" {

int angle_preload_egl() {
    return loadEglLibrary() ? 1 : 0;
}

int64_t angle_preinit_display() {
    if (!loadEglLibrary()) return 0;

    AnglePreInit* pre = new AnglePreInit();
    pre->screenDC = GetDC(NULL);  // Screen DC, no HWND needed

    static const EGLint displayAttribs[] = {
        EGL_PLATFORM_ANGLE_TYPE_ANGLE, EGL_PLATFORM_ANGLE_TYPE_D3D11_ANGLE,
        EGL_NONE, EGL_NONE
    };
    pre->display = p_eglGetPlatformDisplayEXT(EGL_PLATFORM_ANGLE_ANGLE, pre->screenDC, displayAttribs);
    if (pre->display == EGL_NO_DISPLAY) {
        fprintf(stderr, "[angle] preinit: could not get display\n");
        delete pre;
        return 0;
    }

    EGLint majorVersion, minorVersion;
    if (!p_eglInitialize(pre->display, &majorVersion, &minorVersion)) {
        fprintf(stderr, "[angle] preinit: could not initialize display\n");
        delete pre;
        return 0;
    }

    // Set up ANGLE blob cache for shader compilation caching
    setupAngleBlobCache(pre->display);

    static const EGLint configAttribs[] = {
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
        EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
        EGL_NONE, EGL_NONE
    };
    EGLint numConfigs;
    if (!p_eglChooseConfig(pre->display, configAttribs, &pre->surfaceConfig, 1, &numConfigs)) {
        fprintf(stderr, "[angle] preinit: could not choose config\n");
        delete pre;
        return 0;
    }

    static const EGLint contextAttribs[] = {
        EGL_CONTEXT_MAJOR_VERSION, 3, EGL_CONTEXT_MINOR_VERSION, 0,
        EGL_NONE, EGL_NONE
    };
    pre->context = p_eglCreateContext(pre->display, pre->surfaceConfig, nullptr, contextAttribs);
    if (pre->context == EGL_NO_CONTEXT) {
        fprintf(stderr, "[angle] preinit: could not create context\n");
        delete pre;
        return 0;
    }

    return (int64_t)(intptr_t)pre;
}

int64_t angle_attach_window(int64_t preInitPtr, int64_t hwndPtr) {
    AnglePreInit* pre = (AnglePreInit*)(intptr_t)preInitPtr;
    HWND hwnd = (HWND)(intptr_t)hwndPtr;

    AngleDevice* dev = new AngleDevice();
    dev->window = hwnd;
    dev->device = GetDC(hwnd);
    dev->display = pre->display;
    dev->context = pre->context;
    dev->surfaceConfig = pre->surfaceConfig;

    // Mark pre-init as consumed so destructor doesn't free transferred resources
    pre->consumed = true;
    ReleaseDC(NULL, pre->screenDC);
    pre->screenDC = nullptr;
    delete pre;

    // Create surface at actual window size
    RECT clientRect;
    GetClientRect(hwnd, &clientRect);
    EGLint initW = clientRect.right - clientRect.left;
    EGLint initH = clientRect.bottom - clientRect.top;
    if (initW <= 0) initW = 1;
    if (initH <= 0) initH = 1;
    if (!initAngleSurface(dev, initW, initH)) {
        fprintf(stderr, "[angle] attach: could not create window surface\n");
        delete dev;
        return 0;
    }

    // GL interface and DirectContext created lazily via angle_make_context()
    return (int64_t)(intptr_t)dev;
}

void angle_dispose_preinit(int64_t preInitPtr) {
    AnglePreInit* pre = (AnglePreInit*)(intptr_t)preInitPtr;
    delete pre;  // Destructor handles cleanup if not consumed
}

int64_t angle_create_device(int64_t hwndPtr) {
    HWND hwnd = (HWND)(intptr_t)hwndPtr;
    if (!loadEglLibrary()) return 0;

    AngleDevice* dev = new AngleDevice();
    dev->window = hwnd;
    dev->device = GetDC(hwnd);

    // Get ANGLE EGL display with D3D11 backend
    static const EGLint displayAttribs[] = {
        EGL_PLATFORM_ANGLE_TYPE_ANGLE, EGL_PLATFORM_ANGLE_TYPE_D3D11_ANGLE,
        EGL_NONE, EGL_NONE
    };
    dev->display = p_eglGetPlatformDisplayEXT(EGL_PLATFORM_ANGLE_ANGLE, dev->device, displayAttribs);
    if (dev->display == EGL_NO_DISPLAY) {
        fprintf(stderr, "[angle] Could not get display\n");
        delete dev;
        return 0;
    }

    EGLint majorVersion, minorVersion;
    if (!p_eglInitialize(dev->display, &majorVersion, &minorVersion)) {
        fprintf(stderr, "[angle] Could not initialize display\n");
        delete dev;
        return 0;
    }

    // Set up ANGLE blob cache for shader compilation caching
    setupAngleBlobCache(dev->display);

    // Choose config: RGBA8, OpenGL ES 3.0
    static const EGLint configAttribs[] = {
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
        EGL_RED_SIZE, 8,
        EGL_GREEN_SIZE, 8,
        EGL_BLUE_SIZE, 8,
        EGL_ALPHA_SIZE, 8,
        EGL_NONE, EGL_NONE
    };
    EGLint numConfigs;
    if (!p_eglChooseConfig(dev->display, configAttribs, &dev->surfaceConfig, 1, &numConfigs)) {
        fprintf(stderr, "[angle] Could not choose config\n");
        delete dev;
        return 0;
    }

    // Create OpenGL ES 3.0 context
    static const EGLint contextAttribs[] = {
        EGL_CONTEXT_MAJOR_VERSION, 3,
        EGL_CONTEXT_MINOR_VERSION, 0,
        EGL_NONE, EGL_NONE
    };
    dev->context = p_eglCreateContext(dev->display, dev->surfaceConfig, nullptr, contextAttribs);
    if (dev->context == EGL_NO_CONTEXT) {
        fprintf(stderr, "[angle] Could not create context\n");
        delete dev;
        return 0;
    }

    // Create initial surface at actual window size (avoids wasted 1x1 surface cycle)
    RECT clientRect;
    GetClientRect(hwnd, &clientRect);
    EGLint initW = clientRect.right - clientRect.left;
    EGLint initH = clientRect.bottom - clientRect.top;
    if (initW <= 0) initW = 1;
    if (initH <= 0) initH = 1;
    if (!initAngleSurface(dev, initW, initH)) {
        fprintf(stderr, "[angle] Could not create initial surface\n");
        delete dev;
        return 0;
    }

    // GL interface and DirectContext are created lazily on first draw
    // via angle_make_context(), matching the AWT deferred pattern.

    return (int64_t)(intptr_t)dev;
}

int64_t angle_get_context(int64_t devicePtr) {
    AngleDevice* dev = (AngleDevice*)(intptr_t)devicePtr;
    return dev->grContext ? (int64_t)(intptr_t)dev->grContext : 0;
}

int64_t angle_make_context(int64_t devicePtr) {
    AngleDevice* dev = (AngleDevice*)(intptr_t)devicePtr;

    if (dev->grContext) {
        return (int64_t)(intptr_t)dev->grContext;
    }

    // Assemble GL interface from ANGLE's eglGetProcAddress
    if (!dev->backendContext) {
        sk_sp<const GrGLInterface> glInterface(GrGLMakeAssembledInterface(
            nullptr,
            [](void*, const char name[]) -> GrGLFuncPtr {
                return (GrGLFuncPtr)p_eglGetProcAddress(name);
            }));
        if (!glInterface) {
            fprintf(stderr, "[angle] Could not create GL interface\n");
            return 0;
        }
        dev->backendContext = glInterface;
    }

    // Create Skia DirectContext
    // TODO: Enable persistent shader cache once ANGLE blob cache compatibility is resolved.
    // Skia's PersistentCache with kBackendBinary causes rendering corruption with ANGLE
    // because ANGLE's GL program binaries are not compatible with glProgramBinary reloading.
    // The proper approach is EGL_ANDROID_blob_cache via eglSetBlobCacheFuncsANDROID.
    sk_sp<GrDirectContext> ctx = GrDirectContexts::MakeGL(dev->backendContext);
    if (!ctx) {
        fprintf(stderr, "[angle] Could not create DirectContext\n");
        return 0;
    }
    dev->grContext = ctx.release();

    return (int64_t)(intptr_t)dev->grContext;
}

void angle_make_current(int64_t devicePtr) {
    AngleDevice* dev = (AngleDevice*)(intptr_t)devicePtr;
    p_eglMakeCurrent(dev->display, dev->surface, dev->surface, dev->context);
}

int angle_resize_surface(int64_t devicePtr, int width, int height) {
    AngleDevice* dev = (AngleDevice*)(intptr_t)devicePtr;

    if (!initAngleSurface(dev, width, height)) {
        return -1;
    }

    // Reset Skia's GL state after surface change
    if (dev->grContext) {
        dev->grContext->resetContext();
    }

    // Set viewport
    typedef void (*PFN_glViewport)(int, int, int, int);
    typedef void (*PFN_glGetIntegerv)(unsigned int, int*);
    auto glViewport = (PFN_glViewport)p_eglGetProcAddress("glViewport");
    auto glGetIntegerv = (PFN_glGetIntegerv)p_eglGetProcAddress("glGetIntegerv");

    if (glViewport) glViewport(0, 0, width, height);

    int fbId = 0;
    if (glGetIntegerv) glGetIntegerv(GR_GL_FRAMEBUFFER_BINDING, &fbId);

    return fbId;
}

void angle_swap_buffers(int64_t devicePtr, int vsync) {
    AngleDevice* dev = (AngleDevice*)(intptr_t)devicePtr;
    EGLint desired = vsync ? 1 : 0;
    if (dev->currentSwapInterval != desired) {
        p_eglSwapInterval(dev->display, desired);
        dev->currentSwapInterval = desired;
    }
    p_eglSwapBuffers(dev->display, dev->surface);
}

void angle_dispose(int64_t devicePtr) {
    AngleDevice* dev = (AngleDevice*)(intptr_t)devicePtr;
    p_eglMakeCurrent(dev->display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    delete dev;
}

} // extern "C"
