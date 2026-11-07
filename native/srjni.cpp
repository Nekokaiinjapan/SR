// srjni.cpp - Real-ESRGAN (ncnn) bridge for Android
//
// Java side passes ARGB int[] in, gets ARGB int[] out. Tiling keeps peak
// memory bounded; Vulkan is used when available, CPU otherwise.

#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <cstring>
#include <exception>
#include <fstream>
#include <memory>
#include <mutex>
#include <new>
#include <sstream>
#include <string>
#include <vector>

#include "gpu.h"
#include "net.h"

#define TAG "srjni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// libomp re-runs topology discovery on every new parallel region; on Android
// the big.LITTLE core set changes under it and the second round aborts inside
// __kmp_affinity_initialize. Disable affinity before the runtime starts.
extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM*, void*) {
    setenv("KMP_AFFINITY", "disabled", 1);
    setenv("OMP_WAIT_POLICY", "passive", 1);
    return JNI_VERSION_1_6;
}

namespace {

std::mutex g_mu;
std::unique_ptr<ncnn::Net> g_net;
std::string g_err;
bool g_loaded = false;
int  g_scale = 4;

const int PAD = 16;          // context overlap in pixels
const int MAX_TILE = 512;

inline void set_err(const char* msg) { g_err.assign(msg); }

}  // namespace

// Manual float->u8 conversion. Mat::to_pixels() proved unreliable with this
// ncnn build (correct float data, near-zero bytes), so do it by hand: the
// channel pointers are known to be valid, the probe reads them directly.
static void mat_to_rgb8(const ncnn::Mat& m, unsigned char* dst, int w, int h) {
    const float* c0 = m.channel(0);
    const float* c1 = m.c > 1 ? m.channel(1) : m.channel(0);
    const float* c2 = m.c > 2 ? m.channel(2) : m.channel(0);
    const int stride = m.w;

    for (int y = 0; y < h; ++y) {
        const float* r = c0 + static_cast<size_t>(y) * stride;
        const float* g = c1 + static_cast<size_t>(y) * stride;
        const float* b = c2 + static_cast<size_t>(y) * stride;
        unsigned char* d = dst + static_cast<size_t>(y) * w * 3;
        for (int x = 0; x < w; ++x) {
            float v0 = r[x] * 255.f;
            float v1 = g[x] * 255.f;
            float v2 = b[x] * 255.f;
            d[x * 3 + 0] = static_cast<unsigned char>(v0 < 0.f ? 0.f : (v0 > 255.f ? 255.f : v0 + 0.5f));
            d[x * 3 + 1] = static_cast<unsigned char>(v1 < 0.f ? 0.f : (v1 > 255.f ? 255.f : v1 + 0.5f));
            d[x * 3 + 2] = static_cast<unsigned char>(v2 < 0.f ? 0.f : (v2 > 255.f ? 255.f : v2 + 0.5f));
        }
    }
}

// Runs a tiny gradient through the net and reports the largest output value.
// Used to decide whether a given load configuration actually works, because a
// "successful" load can still yield all-zero weights with the wrong dtype.
static bool quick_probe(ncnn::Net& net, float& maxv, float& meanv) {
    maxv = 0.f;
    meanv = 0.f;
    const int W = 48, H = 48;
    std::vector<unsigned char> rgba(static_cast<size_t>(W) * H * 4);
    for (int y = 0; y < H; ++y) {
        for (int x = 0; x < W; ++x) {
            unsigned char* d = &rgba[(static_cast<size_t>(y) * W + x) * 4];
            d[0] = static_cast<unsigned char>(x * 5);
            d[1] = static_cast<unsigned char>(y * 5);
            d[2] = 128;
            d[3] = 255;
        }
    }

    ncnn::Mat in = ncnn::Mat::from_pixels(rgba.data(), ncnn::Mat::PIXEL_RGBA2RGB, W, H);
    for (int q = 0; q < in.c; ++q) {
        float* pp = in.channel(q);
        for (int i = 0; i < in.w * in.h; ++i) pp[i] *= (1.f / 255.f);
    }

    ncnn::Extractor ex = net.create_extractor();
    if (ex.input("data", in) != 0) return false;

    ncnn::Mat out;
    if (ex.extract("output", out) != 0) return false;

    ncnn::Mat m = out;
    if (m.elemsize == 2) {
        ncnn::Mat f32;
        ncnn::cast_float16_to_float32(m, f32, net.opt);
        m = f32;
    }
    if (m.elempack != 1) {
        ncnn::Mat unpacked;
        ncnn::convert_packing(m, unpacked, 1, net.opt);
        m = unpacked;
    }

    float mx = -1e30f;
    double sum = 0;
    size_t n = 0;
    for (int q = 0; q < m.c; ++q) {
        const float* pp = m.channel(q);
        for (int i = 0; i < m.w * m.h; ++i) {
            if (pp[i] > mx) mx = pp[i];
            sum += pp[i];
            ++n;
        }
    }
    maxv = mx;
    meanv = n ? static_cast<float>(sum / n) : 0.f;
    return true;
}

static jboolean load_impl(JNIEnv* env, jstring jparam, jstring jbin, jint scale,
                          jint threads, jboolean vulkan) {
    std::lock_guard<std::mutex> lk(g_mu);

    const char* p = env->GetStringUTFChars(jparam, nullptr);
    const char* b = env->GetStringUTFChars(jbin, nullptr);
    std::string sp(p ? p : ""), sb(b ? b : "");
    if (p) env->ReleaseStringUTFChars(jparam, p);
    if (b) env->ReleaseStringUTFChars(jbin, b);

    g_loaded = false;
    g_err.clear();

    // Keep one GPU instance for the whole process. destroy_gpu_instance()
    // leaves ncnn with a dangling VulkanDevice, so the next load_param()
    // segfaults inside VulkanDevice::is_valid(). Only the net is rebuilt.
    ncnn::create_gpu_instance();

    struct Cfg {
        bool gpu;
        bool fp16;
    };
    std::vector<Cfg> cfgs;
    if (vulkan && ncnn::get_gpu_count() > 0) {
        // fp32 first: fp16 on this Mali produces a very dark frame, which the
        // probe's mean check now rejects at load time.
        cfgs.push_back({true, false});
        cfgs.push_back({true, true});
    }
    cfgs.push_back({false, false});
    cfgs.push_back({false, true});

    for (size_t i = 0; i < cfgs.size(); ++i) {
        const Cfg c = cfgs[i];

        // Always a fresh Net. Reusing a cleared Net makes the next load_model()
        // abort inside OpenMP (__kmp_parallel_initialize) when switching from
        // one model to another.
        std::unique_ptr<ncnn::Net> net(new ncnn::Net);
        net->opt.num_threads = threads > 0 ? threads : 4;
        net->opt.use_vulkan_compute = c.gpu;
        net->opt.use_fp16_packed = c.fp16;
        net->opt.use_fp16_storage = c.fp16;
        net->opt.use_fp16_arithmetic = c.fp16;

        if (net->load_param(sp.c_str()) != 0 || net->load_model(sb.c_str()) != 0) {
            LOGE("cfg gpu=%d fp16=%d: load failed", (int)c.gpu, (int)c.fp16);
            continue;
        }

        float mx = 0.f, mv = 0.f;
        const bool probed = quick_probe(*net, mx, mv);
        LOGI("cfg gpu=%d fp16=%d: probe=%d max=%f mean=%f", (int)c.gpu, (int)c.fp16,
             (int)probed, mx, mv);

        // The probe gradient averages ~0.5; a faithful upscale stays in the
        // same ballpark. A dark mean means the backend produced garbage
        // (seen with fp16 on Mali), so reject the config instead of shipping
        // near-black images.
        if (probed && mx > 0.01f && mv > 0.10f && mv < 0.90f) {
            g_net = std::move(net);
            g_scale = scale > 0 ? scale : 4;
            g_loaded = true;
            LOGI("loaded with gpu=%d fp16=%d scale=%d threads=%d", (int)c.gpu,
                 (int)c.fp16, g_scale, threads);
            return JNI_TRUE;
        }
    }

    g_net.reset();
    set_err("model produced blank output with every backend (weights?)");
    LOGE("all configurations failed");
    return JNI_FALSE;
}

// Loading a second model can throw (bad_alloc while converting fp16 weights).
// Letting that cross the JNI boundary terminates the process, so trap it and
// report a normal failure instead.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_dsh_sr_NativeSR_load(JNIEnv* env, jclass, jstring jparam, jstring jbin,
                              jint scale, jint threads, jboolean vulkan) {
    try {
        return load_impl(env, jparam, jbin, scale, threads, vulkan);
    } catch (const std::bad_alloc&) {
        g_loaded = false;
        set_err("native out of memory while loading model");
    } catch (const std::exception& e) {
        g_loaded = false;
        g_err.assign(e.what());
    } catch (...) {
        g_loaded = false;
        set_err("native exception while loading model");
    }
    LOGE("load failed: %s", g_err.c_str());
    return JNI_FALSE;
}

static jintArray upscale_impl(JNIEnv* env, jintArray jsrc, jint w, jint h,
                              jint tile) {
    std::lock_guard<std::mutex> lk(g_mu);

    if (!g_loaded || !g_net) {
        set_err("model not loaded");
        return nullptr;
    }
    if (w <= 0 || h <= 0) {
        set_err("bad input size");
        return nullptr;
    }

    // ---- ARGB int[] -> RGBA byte buffer -------------------------------- //
    jint* src = env->GetIntArrayElements(jsrc, nullptr);
    if (!src) {
        set_err("cannot lock input array");
        return nullptr;
    }
    std::vector<unsigned char> rgba(static_cast<size_t>(w) * h * 4);
    for (int i = 0, n = w * h; i < n; ++i) {
        const unsigned int c = static_cast<unsigned int>(src[i]);
        unsigned char* d = &rgba[static_cast<size_t>(i) * 4];
        d[0] = static_cast<unsigned char>((c >> 16) & 0xFF);  // R
        d[1] = static_cast<unsigned char>((c >> 8) & 0xFF);   // G
        d[2] = static_cast<unsigned char>(c & 0xFF);          // B
        d[3] = 255;
    }
    env->ReleaseIntArrayElements(jsrc, src, JNI_ABORT);

    const int ow = w * g_scale;
    const int oh = h * g_scale;

    jintArray jdst = env->NewIntArray(static_cast<jsize>(ow) * oh);
    if (!jdst) {
        set_err("out of memory allocating output");
        return nullptr;
    }
    jint* dst = env->GetIntArrayElements(jdst, nullptr);
    if (!dst) {
        set_err("cannot lock output array");
        return nullptr;
    }

    const int ts = std::max(32, std::min(static_cast<int>(tile), MAX_TILE));

    std::vector<unsigned char> obuf;
    int done = 0, total = 0;
    for (int y = 0; y < h; y += ts)
        for (int x = 0; x < w; x += ts) total++;

    bool ok = true;
    for (int y0 = 0; y0 < h && ok; y0 += ts) {
        for (int x0 = 0; x0 < w && ok; x0 += ts) {
            const int y1 = std::min(y0 + ts, h);
            const int x1 = std::min(x0 + ts, w);

            const int ey0 = std::max(0, y0 - PAD), ey1 = std::min(h, y1 + PAD);
            const int ex0 = std::max(0, x0 - PAD), ex1 = std::min(w, x1 + PAD);
            const int ew = ex1 - ex0, eh = ey1 - ey0;

            // from_pixels() walks the buffer assuming rows are exactly `ew`
            // wide, but our rows are `w` wide. Copy the patch into a
            // contiguous buffer first, otherwise the tile is read skewed.
            std::vector<unsigned char> patch(static_cast<size_t>(ew) * eh * 4);
            for (int yy = 0; yy < eh; ++yy) {
                std::memcpy(patch.data() + static_cast<size_t>(yy) * ew * 4,
                            rgba.data() + (static_cast<size_t>(ey0 + yy) * w + ex0) * 4,
                            static_cast<size_t>(ew) * 4);
            }

            ncnn::Mat in = ncnn::Mat::from_pixels(patch.data(), ncnn::Mat::PIXEL_RGBA2RGB,
                                                  ew, eh);
            // Scale explicitly instead of substract_mean_normalize(nullptr, ...):
            // passing a null mean is ambiguous across ncnn builds.
            for (int q = 0; q < in.c; ++q) {
                float* pp = in.channel(q);
                for (int i = 0; i < in.w * in.h; ++i) pp[i] *= (1.f / 255.f);
            }

            ncnn::Extractor ex = g_net->create_extractor();
            ex.input("data", in);

            ncnn::Mat out;
            if (ex.extract("output", out) != 0) {
                set_err("inference failed (out of memory?)");
                ok = false;
                break;
            }

            // Normalise: fp16 -> fp32, packed -> elempack 1, then to_pixels.
            ncnn::Mat m = out;
            if (m.elemsize == 2) {
                ncnn::Mat f32;
                ncnn::cast_float16_to_float32(m, f32, g_net->opt);
                m = f32;
            }
            if (m.elempack != 1) {
                ncnn::Mat unpacked;
                ncnn::convert_packing(m, unpacked, 1, g_net->opt);
                m = unpacked;
            }
            if (m.elemsize != 4 || m.elempack != 1 || m.w < ew * g_scale ||
                m.h < eh * g_scale) {
                set_err("unexpected output layout from model");
                ok = false;
                break;
            }

            const int cw = std::min(m.w, ew * g_scale);
            const int ch = std::min(m.h, eh * g_scale);
            obuf.resize(static_cast<size_t>(cw) * ch * 3);
            mat_to_rgb8(m, obuf.data(), cw, ch);

            const int stride_in = cw * 3;
            for (int yy = 0; yy < (y1 - y0) * g_scale; ++yy) {
                const int sy = (y0 - ey0) * g_scale + yy;
                if (sy >= ch) break;
                const unsigned char* row = obuf.data() + static_cast<size_t>(sy) * stride_in +
                                          static_cast<size_t>(x0 - ex0) * g_scale * 3;
                jint* drow = dst + static_cast<size_t>(y0 * g_scale + yy) * ow + x0 * g_scale;
                const int n = std::min((x1 - x0) * g_scale, cw - (x0 - ex0) * g_scale);
                for (int xx = 0; xx < n; ++xx) {
                    drow[xx] = static_cast<jint>(0xFF000000u) |
                               (static_cast<jint>(row[xx * 3 + 0]) << 16) |
                               (static_cast<jint>(row[xx * 3 + 1]) << 8) |
                               static_cast<jint>(row[xx * 3 + 2]);
                }
            }

            if (++done % 4 == 0) LOGI("tile %d/%d", done, total);
        }
    }

    // A blank frame means the backend produced nothing usable; report it so
    // the Java side can retry on CPU instead of showing a black image.
    if (ok) {
        size_t nonzero = 0;
        const size_t total_px = static_cast<size_t>(ow) * oh;
        for (size_t i = 0; i < total_px; i += 1013) {
            if ((dst[i] & 0x00FFFFFF) != 0) nonzero++;
        }
        if (nonzero == 0) {
            set_err("empty output (gpu backend produced a blank frame)");
            ok = false;
        }
    }

    env->ReleaseIntArrayElements(jdst, dst, 0);

    if (!ok) {
        env->DeleteLocalRef(jdst);
        return nullptr;
    }
    LOGI("done %dx%d -> %dx%d (%d tiles)", w, h, ow, oh, total);
    return jdst;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_dsh_sr_NativeSR_lastError(JNIEnv* env, jclass) {
    return env->NewStringUTF(g_err.c_str());
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_dsh_sr_NativeSR_upscale(JNIEnv* env, jclass, jintArray jsrc, jint w, jint h,
                                 jint tile) {
    try {
        return upscale_impl(env, jsrc, w, h, tile);
    } catch (const std::bad_alloc&) {
        set_err("native out of memory");
    } catch (const std::exception& e) {
        g_err.assign(e.what());
    } catch (...) {
        set_err("native exception");
    }
    LOGE("upscale failed: %s", g_err.c_str());
    return nullptr;
}

// ---------------------------------------------------------------------------
// diagnostics: run one 64x64 gradient and report the raw float statistics
// ---------------------------------------------------------------------------
static long file_size(const std::string& path) {
    std::ifstream f(path.c_str(), std::ios::binary | std::ios::ate);
    if (!f) return -1;
    return static_cast<long>(f.tellg());
}

static std::string run_once(const std::string& sp, const std::string& sb, bool use_gpu,
                            bool use_fp16) {
    std::ostringstream os;

    ncnn::Net net;
    net.opt.num_threads = 4;
    net.opt.use_fp16_packed = use_fp16;
    net.opt.use_fp16_storage = use_fp16;
    net.opt.use_fp16_arithmetic = use_fp16;
    net.opt.use_vulkan_compute = use_gpu;

    const int r1 = net.load_param(sp.c_str());
    const int r2 = net.load_model(sb.c_str());
    os << "load=" << r1 << "/" << r2;
    if (r1 != 0 || r2 != 0) return os.str();

    const int W = 64, H = 64;
    std::vector<unsigned char> rgba(static_cast<size_t>(W) * H * 4);
    for (int y = 0; y < H; ++y) {
        for (int x = 0; x < W; ++x) {
            unsigned char* d = &rgba[(static_cast<size_t>(y) * W + x) * 4];
            d[0] = static_cast<unsigned char>(x * 4);
            d[1] = static_cast<unsigned char>(y * 4);
            d[2] = static_cast<unsigned char>((x + y) * 2);
            d[3] = 255;
        }
    }

    ncnn::Mat in = ncnn::Mat::from_pixels(rgba.data(), ncnn::Mat::PIXEL_RGBA2RGB, W, H);
    for (int q = 0; q < in.c; ++q) {
        float* pp = in.channel(q);
        for (int i = 0; i < in.w * in.h; ++i) pp[i] *= (1.f / 255.f);
    }
    os << " in=" << in.w << "x" << in.h;

    ncnn::Extractor ex = net.create_extractor();
    ex.input("data", in);
    ncnn::Mat out;
    const int r3 = ex.extract("output", out);
    os << " ex=" << r3;
    if (r3 != 0) return os.str();
    os << " out=" << out.w << "x" << out.h << " es=" << out.elemsize
       << " pk=" << out.elempack;

    ncnn::Mat m = out;
    if (m.elemsize == 2) {
        ncnn::Mat f32;
        ncnn::cast_float16_to_float32(m, f32, net.opt);
        m = f32;
    }
    if (m.elempack != 1) {
        ncnn::Mat unpacked;
        ncnn::convert_packing(m, unpacked, 1, net.opt);
        m = unpacked;
    }

    float mn = 1e30f, mx = -1e30f;
    double sum = 0;
    size_t n = 0;
    for (int q = 0; q < m.c; ++q) {
        const float* pp = m.channel(q);
        for (int i = 0; i < m.w * m.h; ++i) {
            const float v = pp[i];
            if (v < mn) mn = v;
            if (v > mx) mx = v;
            sum += v;
            ++n;
        }
    }
    os << " raw[min=" << mn << " max=" << mx << " mean=" << (n ? sum / n : 0.0) << "]";

    std::vector<unsigned char> px(static_cast<size_t>(m.w) * m.h * 3, 0);
    mat_to_rgb8(m, px.data(), m.w, m.h);
    unsigned int a = 255, b = 0;
    double s2 = 0;
    for (size_t i = 0; i < px.size(); ++i) {
        if (px[i] < a) a = px[i];
        if (px[i] > b) b = px[i];
        s2 += px[i];
    }
    os << " rgb[mean=" << (px.empty() ? 0.0 : s2 / px.size()) << " max=" << b << "]";
    return os.str();
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_dsh_sr_NativeSR_probe(JNIEnv* env, jclass) {
    std::lock_guard<std::mutex> lk(g_mu);
    std::ostringstream os;
    if (!g_loaded || !g_net) {
        os << "not loaded";
    } else {
        float mx = 0.f, mv = 0.f;
        const bool okp = quick_probe(*g_net, mx, mv);
        os << "probe=" << (int)okp << " max=" << mx << " mean=" << mv << " vk="
           << (int)g_net->opt.use_vulkan_compute << " f16="
           << (int)g_net->opt.use_fp16_storage;
    }
    return env->NewStringUTF(os.str().c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_dsh_sr_NativeSR_diag(JNIEnv* env, jclass, jstring jparam, jstring jbin,
                              jint scale, jboolean vulkan) {
    std::ostringstream os;

    const char* p = env->GetStringUTFChars(jparam, nullptr);
    const char* b = env->GetStringUTFChars(jbin, nullptr);
    std::string sp(p ? p : ""), sb(b ? b : "");
    if (p) env->ReleaseStringUTFChars(jparam, p);
    if (b) env->ReleaseStringUTFChars(jbin, b);

    os << "scale=" << scale << " psize=" << file_size(sp) << " bsize=" << file_size(sb);

    try {
        const int gpu = ncnn::get_gpu_count();
        os << " gpu=" << gpu
           << " | f32: " << run_once(sp, sb, false, false)
           << " | f16: " << run_once(sp, sb, false, true);
        if (gpu > 0 && vulkan) os << " | gpu: " << run_once(sp, sb, true, false);
    } catch (const std::exception& e) {
        os << " EXC=" << e.what();
    } catch (...) {
        os << " EXC=unknown";
    }

    return env->NewStringUTF(os.str().c_str());
}
