package com.dsh.sr;

import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private static final int REQ_PICK = 1001;
    private static final int MAX_OUT_SIDE = 4096;   // keeps the output bitmap sane

    static final class Model {
        final String label;
        final String asset;   // assets/models/<asset>.param|.bin
        final int scale;

        Model(String label, String asset, int scale) {
            this.label = label;
            this.asset = asset;
            this.scale = scale;
        }
    }

    private final List<Model> models = new ArrayList<>();
    private Spinner spinner;
    private Spinner capSpinner;
    private CheckBox gpuBox;
    private ImageView preview;
    private TextView status;
    private Button pickBtn, runBtn, saveBtn;
    private ProgressBar bar;

    private Bitmap source;
    private Bitmap result;
    private Uri pendingShare;
    private String loadedAsset = null;
    private int loadedScale = -1;
    private boolean loadedGpu = false;

    private static final String PREFS = "sr_prefs";

    /** True when a previous GPU run for this model never finished (crash). */
    private boolean gpuCrashed(String asset) {
        return getSharedPreferences(PREFS, 0).getBoolean("gpu_try_" + asset, false);
    }

    private void setGpuTrying(String asset, boolean v) {
        getSharedPreferences(PREFS, 0).edit().putBoolean("gpu_try_" + asset, v).commit();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        models.add(new Model("animevideo x2  (快)", "realesr-animevideov3-x2", 2));
        models.add(new Model("animevideo x3", "realesr-animevideov3-x3", 3));
        models.add(new Model("animevideo x4", "realesr-animevideov3-x4", 4));
        models.add(new Model("anime6B x4  (好)", "realesrgan-x4plus-anime", 4));

        spinner = (Spinner) findViewById(R.id.spinner);
        gpuBox = (CheckBox) findViewById(R.id.gpu);
        preview = (ImageView) findViewById(R.id.preview);
        status = (TextView) findViewById(R.id.status);
        pickBtn = (Button) findViewById(R.id.pick);
        runBtn = (Button) findViewById(R.id.run);
        saveBtn = (Button) findViewById(R.id.save);
        bar = (ProgressBar) findViewById(R.id.bar);

        List<String> labels = new ArrayList<>();
        for (Model m : models) labels.add(m.label);
        spinner.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, labels));

        capSpinner = (Spinner) findViewById(R.id.cap);
        capSpinner.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item,
                new String[]{
                        "输出长边 2048",
                        "输出长边 3072",
                        "输出长边 4096  （推荐）",
                        "输出长边 5120  （吃内存）"}));
        capSpinner.setSelection(2);

        pickBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pickImage(); }
        });
        runBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { start(); }
        });
        saveBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { save(); }
        });
        findViewById(R.id.diag).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { runDiag(); }
        });
        findViewById(R.id.cat).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showCats(); }
        });

        // Toggling the GPU switch is the user's way of retrying a model that
        // previously took the process down: clear the crash marks.
        gpuBox.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                if (on) {
                    getSharedPreferences(PREFS, 0).edit().clear().commit();
                    toast("已清除崩溃记录，将重新尝试 GPU");
                }
            }
        });

        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent != null && Intent.ACTION_SEND.equals(intent.getAction())) {
            pendingShare = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (pendingShare != null) loadSource(pendingShare);
        }
    }

    private void pickImage() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("image/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(Intent.createChooser(i, "选择图片"), REQ_PICK);
        } catch (Exception e) {
            toast("没有可用的图片选择器");
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_PICK && res == RESULT_OK && data != null && data.getData() != null) {
            loadSource(data.getData());
        }
    }

    private void loadSource(Uri uri) {
        try {
            // Only a hard safety cap here. The real budget depends on the model
            // that is actually used (cap / scale), and the model can still be
            // switched after picking, so that scaling happens in start().
            final int hardMax = 4096;

            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            InputStream is = getContentResolver().openInputStream(uri);
            BitmapFactory.decodeStream(is, null, bounds);
            if (is != null) is.close();

            int longest = Math.max(bounds.outWidth, bounds.outHeight);
            // inSampleSize can only halve, so use it just to stay above the cap.
            int sample = 1;
            while (longest / (sample * 2) >= hardMax) sample *= 2;

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            is = getContentResolver().openInputStream(uri);
            Bitmap bmp = BitmapFactory.decodeStream(is, null, opts);
            if (is != null) is.close();

            if (bmp == null) {
                toast("解码失败");
                return;
            }

            int bw = bmp.getWidth(), bh = bmp.getHeight();
            if (Math.max(bw, bh) > hardMax) {
                float f = (float) hardMax / Math.max(bw, bh);
                int nw = Math.max(1, Math.round(bw * f));
                int nh = Math.max(1, Math.round(bh * f));
                Bitmap scaled = Bitmap.createScaledBitmap(bmp, nw, nh, true);
                if (scaled != bmp) bmp.recycle();
                bmp = scaled;
            }

            source = bmp;
            result = null;
            preview.setImageBitmap(bmp);
            saveBtn.setEnabled(false);
            String note = Math.max(bounds.outWidth, bounds.outHeight) > hardMax
                    ? String.format("（原图 %dpx，超出安全上限，已缩到 %dpx）",
                            Math.max(bounds.outWidth, bounds.outHeight), hardMax)
                    : "";
            status.setText(String.format("已载入 %d × %d %s", bmp.getWidth(), bmp.getHeight(), note));
        } catch (Exception e) {
            toast("读取失败: " + e.getMessage());
        }
    }

    private Model current() {
        return models.get(Math.max(0, spinner.getSelectedItemPosition()));
    }

    /** Longest side allowed on the output bitmap. */
    private int capValue() {
        int pos = capSpinner == null ? 2 : capSpinner.getSelectedItemPosition();
        if (pos == 3) return 5120;
        if (pos == 2) return 4096;
        if (pos == 1) return 3072;
        return 2048;
    }

    private void start() {
        if (source == null) {
            toast("先选一张图");
            return;
        }
        final Model m = current();
        // Apply the output cap against the model used *now*, not the one that
        // happened to be selected when the image was picked.
        final int maxIn = Math.max(64, capValue() / m.scale);
        final Bitmap input;
        if (Math.max(source.getWidth(), source.getHeight()) > maxIn) {
            float f = (float) maxIn / Math.max(source.getWidth(), source.getHeight());
            int nw = Math.max(1, Math.round(source.getWidth() * f));
            int nh = Math.max(1, Math.round(source.getHeight() * f));
            input = Bitmap.createScaledBitmap(source, nw, nh, true);
        } else {
            input = source;
        }

        // A finished 4096px result is ~67MB. Holding it while a second model
        // is loaded is what pushes a lot of phones into a native crash.
        if (result != null) {
            preview.setImageDrawable(null);   // detach before recycle()
            result.recycle();
            result = null;
        }

        bar.setVisibility(View.VISIBLE);
        runBtn.setEnabled(false);
        pickBtn.setEnabled(false);
        status.setText("准备模型…");

        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    // The previous run's int[] (~69MB at 3600x4800) is only
                    // reachable until this method returns; collect it before
                    // the native side asks for a few more tens of MB.
                    System.gc();

                    final String[] paths = ensureModel(m);
                    if (paths == null) {
                        fail("模型文件缺失: " + m.asset);
                        return;
                    }
                    final int threads = Math.max(2, Runtime.getRuntime().availableProcessors());
                    boolean wantGpu = gpuBox.isChecked();
                    if (gpuBox.isChecked() && gpuCrashed(m.asset)) {
                        wantGpu = false;
                        ui("该模型上次在 GPU 上崩溃，本次强制 CPU（可重新勾选 GPU 重试）");
                    }
                    if (!m.asset.equals(loadedAsset) || m.scale != loadedScale
                            || loadedGpu != wantGpu) {
                        ui(wantGpu ? "载入模型（GPU）…" : "载入模型（CPU）…");
                        if (wantGpu) setGpuTrying(m.asset, true);
                        if (!NativeSR.load(paths[0], paths[1], m.scale, threads, wantGpu)) {
                            setGpuTrying(m.asset, false);
                            fail("模型载入失败: " + NativeSR.lastError());
                            return;
                        }
                        loadedAsset = m.asset;
                        loadedScale = m.scale;
                        loadedGpu = wantGpu;
                    }

                    final int w = input.getWidth(), h = input.getHeight();
                    ui(String.format("推理中… %d × %d → %d × %d", w, h, w * m.scale, h * m.scale));

                    int[] px = new int[w * h];
                    input.getPixels(px, 0, w, 0, 0, w, h);

                    long t0 = System.currentTimeMillis();
                    // 256 everywhere: at 128 the 16px context ring wastes 56%
                    // of every dispatch, at 256 it is down to 27%.
                    int[] out = NativeSR.upscale(px, w, h, 256);
                    final int inMean = meanLuma(px);
                    boolean bad = (out == null) || isBadFrame(out, inMean);

                    if (!bad && wantGpu) {
                        setGpuTrying(m.asset, false);   // ran clean, keep GPU
                    }

                    // A dark frame means the GPU backend computed garbage
                    // (fp16 on Mali does exactly this). Retry once on CPU.
                    if (bad && wantGpu) {
                        ui("GPU 输出异常，改用 CPU 重试…");
                        if (NativeSR.load(paths[0], paths[1], m.scale, threads, false)) {
                            loadedGpu = false;
                            setGpuTrying(m.asset, false);
                            out = NativeSR.upscale(px, w, h, 256);
                            bad = (out == null) || isBadFrame(out, inMean);
                        }
                    }
                    if (out == null) {
                        fail("推理失败: " + NativeSR.lastError());
                        return;
                    }
                    if (bad) {
                        fail(String.format("输出异常（亮度 %d，输入亮度 %d），模型或后端有问题",
                                meanLuma(out), inMean));
                        return;
                    }
                    final long dt = System.currentTimeMillis() - t0;

                    final Bitmap bmp = Bitmap.createBitmap(out, w * m.scale, h * m.scale,
                            Bitmap.Config.ARGB_8888);
                    out = null;   // drop the 69MB staging buffer before UI work
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            result = bmp;
                            preview.setImageBitmap(bmp);
                            saveBtn.setEnabled(true);
                            status.setText(String.format("完成 %d × %d，用时 %.1f 秒",
                                    bmp.getWidth(), bmp.getHeight(), dt / 1000.0));
                            bar.setVisibility(View.GONE);
                            runBtn.setEnabled(true);
                            pickBtn.setEnabled(true);
                        }
                    });
                } catch (OutOfMemoryError e) {
                    fail("内存不足，换小一点的图或 x2 模型");
                } catch (Throwable t) {
                    fail(t.getClass().getSimpleName() + ": " + t.getMessage());
                }
            }
        }, "sr-worker").start();
    }

    private void ui(final String s) {
        runOnUiThread(new Runnable() {
            @Override public void run() { status.setText(s); }
        });
    }

    private void fail(final String s) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                status.setText(s);
                bar.setVisibility(View.GONE);
                runBtn.setEnabled(true);
                pickBtn.setEnabled(true);
                toast(s);
            }
        });
    }

    /** Copy assets/models/<name>.{param,bin} into filesDir once. Returns {param, bin}. */
    private String[] ensureModel(Model m) {
        File dir = new File(getFilesDir(), "models");
        if (!dir.exists() && !dir.mkdirs()) return null;

        String[] out = new String[2];
        String[] exts = {".param", ".bin"};
        for (int i = 0; i < 2; i++) {
            String name = m.asset + exts[i];
            File f = new File(dir, name);
            if (!f.exists() || f.length() == 0) {
                try {
                    InputStream is = getAssets().open("models/" + name);
                    OutputStream os = new FileOutputStream(f);
                    byte[] buf = new byte[1 << 16];
                    int n;
                    while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
                    os.close();
                    is.close();
                } catch (Exception e) {
                    return null;
                }
            }
            out[i] = f.getAbsolutePath();
        }
        return out;
    }

    /** Runs the model on a synthetic gradient and reports the raw numbers. */
    private void runDiag() {
        // Free the big bitmap first: a second net + a 4096px bitmap is enough
        // to get the process killed on low-memory devices.
        if (result != null) {
            preview.setImageDrawable(null);
            result.recycle();
            result = null;
        }
        saveBtn.setEnabled(false);
        System.gc();

        final Model m = current();
        status.setText("自检中…");
        runBtn.setEnabled(false);
        new Thread(new Runnable() {
            @Override public void run() {
                String r = NativeSR.probe();   // reuse the loaded net
                if (r == null || r.startsWith("not loaded")) {
                    final String[] paths = ensureModel(m);
                    if (paths == null) {
                        fail("模型文件缺失: " + m.asset);
                        return;
                    }
                    r = NativeSR.diag(paths[0], paths[1], m.scale, gpuBox.isChecked());
                }
                final String report = r;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        runBtn.setEnabled(true);
                        status.setText(report);
                        try {
                            ClipboardManager cm = (ClipboardManager)
                                    getSystemService(CLIPBOARD_SERVICE);
                            if (cm != null) {
                                cm.setPrimaryClip(ClipData.newPlainText("sr-diag", report));
                            }
                            toast("自检结果已复制，可直接粘贴");
                        } catch (Exception ignored) {
                        }
                    }
                });
            }
        }, "sr-diag").start();
    }

    /** Average luma (0..255) over a sampled subset of the frame. */
    private static int meanLuma(int[] px) {
        if (px == null || px.length == 0) return 0;
        int step = Math.max(1, px.length / 4096);
        long sum = 0;
        int n = 0;
        for (int i = 0; i < px.length; i += step) {
            int c = px[i];
            sum += (((c >> 16) & 0xFF) * 299 + ((c >> 8) & 0xFF) * 587 + (c & 0xFF) * 114) / 1000;
            n++;
        }
        return n == 0 ? 0 : (int) (sum / n);
    }

    /**
     * A real upscale keeps roughly the brightness of its input. A nearly black
     * frame, or one far darker than the source, means the backend computed
     * garbage (fp16 on Mali does exactly this) rather than a valid result.
     */
    private static boolean isBadFrame(int[] out, int inMean) {
        int outMean = meanLuma(out);
        if (outMean < 4) return true;
        return inMean > 20 && outMean < inMean / 4;
    }

    /** The mystery button: a small dialog with the cat photos. */
    private void showCats() {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundResource(R.drawable.card_bg);
        int pad = dp(20);
        box.setPadding(pad, pad, pad, dp(8));

        TextView title = new TextView(this);
        title.setText(R.string.cat_message);
        title.setTextSize(16);
        title.setTextColor(0xFF2B2140);
        title.setPadding(0, 0, 0, dp(14));
        box.addView(title);

        int shown = 0;
        try {
            String[] files = getAssets().list("cats");
            if (files != null) {
                java.util.Arrays.sort(files);
                for (String name : files) {
                    String lower = name.toLowerCase(java.util.Locale.ROOT);
                    if (!lower.endsWith(".jpg") && !lower.endsWith(".jpeg")
                            && !lower.endsWith(".png")) {
                        continue;
                    }
                    InputStream is = getAssets().open("cats/" + name);
                    Bitmap bmp = BitmapFactory.decodeStream(is);
                    is.close();
                    if (bmp == null) continue;

                    ImageView iv = new ImageView(this);
                    iv.setImageBitmap(bmp);
                    iv.setAdjustViewBounds(true);
                    iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
                    lp.bottomMargin = dp(10);
                    box.addView(iv, lp);
                    shown++;
                }
            }
        } catch (Exception ignored) {
            // no photos bundled - the dialog still shows the message
        }

        if (shown == 0) {
            TextView hint = new TextView(this);
            hint.setText(R.string.cat_empty);
            hint.setTextSize(12);
            hint.setTextColor(0xFF7A6E8F);
            hint.setPadding(0, dp(4), 0, dp(8));
            box.addView(hint);
        }

        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);
        dialog.setContentView(scroll);
        dialog.setCanceledOnTouchOutside(true);
        dialog.show();

        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int width = (int) (getResources().getDisplayMetrics().widthPixels * 0.92f);
            int height = (int) (getResources().getDisplayMetrics().heightPixels * 0.85f);
            w.setLayout(width, height);
        }
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }

    private void save() {
        if (result == null) return;
        String name = "sr_" + System.currentTimeMillis() + ".png";
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
                v.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                v.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SR");
                Uri uri = getContentResolver()
                        .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
                if (uri == null) throw new IllegalStateException("insert failed");
                OutputStream os = getContentResolver().openOutputStream(uri);
                result.compress(Bitmap.CompressFormat.PNG, 100, os);
                os.close();
                toast("已保存到 相册/SR/" + name);
            } else {
                File pics = Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_PICTURES);
                File dir = new File(pics, "SR");
                if (!dir.exists()) dir.mkdirs();
                File f = new File(dir, name);
                FileOutputStream os = new FileOutputStream(f);
                result.compress(Bitmap.CompressFormat.PNG, 100, os);
                os.close();
                toast("已保存到 " + f.getAbsolutePath());
            }
        } catch (Exception e) {
            toast("保存失败: " + e.getMessage());
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
