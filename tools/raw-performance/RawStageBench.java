import app.spektralab.raw.*;
import app.spektralab.engine.*;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Read-only stage timings of the installed APK in a separate Android runtime.
 * No captures, MediaStore writes, or changes to the installed application.
 * Run with the original file path and installed base.apk path. Pixel data stays on device.
 */
public final class RawStageBench {
    private static long start() { return System.nanoTime(); }
    private static double ms(long t) { return (System.nanoTime() - t) / 1e6; }
    private static void emit(String stage, double time, String info) {
        System.out.printf(Locale.ROOT, "stage=%s ms=%.3f %s%n", stage, time, info);
        System.out.flush();
    }
    public static void main(String[] args) throws Exception {
        System.out.println("runtime=" + System.getProperty("java.vm.name") + " cpus=" + Runtime.getRuntime().availableProcessors()
            + " maxHeapMiB=" + Runtime.getRuntime().maxMemory() / 1048576);
        if (args[0].equals("raw")) raw(args[1], args.length > 3 ? Integer.parseInt(args[3]) : 2);
        else if (args[0].equals("mapped")) mapped(args[1]);
        else if (args[0].equals("lut")) lut(args[2]);
        else if (args[0].equals("render")) render(args[1], args[2]);
        else if (args[0].equals("gain")) gain(args[1]);
        else throw new IllegalArgumentException("mode raw|lut|render|gain|mapped");
        System.out.println("BENCH_COMPLETE");
        System.exit(0);
    }
    private static void raw(String path, int repeats) throws Exception {
        for (int pass = 0; pass < repeats; pass++) {
            long t = start();
            byte[] bytes = Files.readAllBytes(Paths.get(path));
            emit("file_read", ms(t), "pass=" + pass + " bytes=" + bytes.length);
            t = start();
            RawImage img = DngReader.INSTANCE.read(bytes);
            emit("dng_parse", ms(t), "pass=" + pass + " success=" + (img != null));
            if (img == null) throw new IllegalStateException("DNG parse returned null");
            requireBayer(img);
            bytes = null;
            System.out.println("raw=" + img.getRawWidth() + "x" + img.getRawHeight() + " crop=" + img.getCropWidth() + "x" + img.getCropHeight()
                + " upright=" + img.getUprightWidth() + "x" + img.getUprightHeight() + " linear=" + img.getLinear()
                + " gainMaps=" + img.getGainMaps().size() + " noiseScale=" + img.getNoiseScale());
            int[] edges = {512, 640, 1600, 2048, 4096};
            for (int edge : edges) {
                boolean fast = edge != 2048 && edge != 4096;
                t = start();
                LinearImage out = RawDeveloper.INSTANCE.develop(img, edge, fast, new RawDeveloper.Options(true, false), ByteBuffer::allocateDirect);
                emit("develop_without_chroma", ms(t), "pass=" + pass + " edge=" + edge + " fast=" + fast + " output=" + out.getWidth() + "x" + out.getHeight());
                int longest = Math.max(img.getCropWidth(), img.getCropHeight());
                boolean bin = fast || longest / 2 >= edge;
                int k = Math.max(1, longest / 2 / edge);
                t = start();
                ChromaDenoise.INSTANCE.apply(out, img, bin ? k * k : 1f);
                emit("chroma", ms(t), "pass=" + pass + " edge=" + edge + " samples=" + (bin ? k * k : 1));
                float check = out.red(out.getWidth() / 2, out.getHeight() / 2);
                if (!Float.isFinite(check)) throw new IllegalStateException("nonfinite output");
                System.out.println("checksum pass=" + pass + " edge=" + edge + " sha256=" + checksum(out.getHalfRgba()));
                out = null;
                System.gc(); // outside measured regions; avoid retaining previous large outputs
            }
            img = null;
            System.gc();
        }
    }
    private static String checksum(ByteBuffer pixels) throws Exception {
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        ByteBuffer view = pixels.duplicate();
        view.clear();
        digest.update(view);
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", b));
        return hex.toString();
    }
    private static EngineData engine(ZipFile z) {
        return new EngineData(p -> {
            try {
                ZipEntry e = z.getEntry("assets/spektra/" + p);
                if (e == null) throw new FileNotFoundException(p);
                return z.getInputStream(e);
            } catch (IOException e) { throw new UncheckedIOException(e); }
        });
    }
    private static void lut(String apk) throws Exception {
        try (ZipFile z = new ZipFile(apk)) {
            long t = start();
            EngineData data = engine(z);
            emit("engine_data", ms(t), "");
            for (int size : new int[]{36, 48, 36, 48}) {
                t = start();
                LookSetup setup = LookSetup.Companion.create(Look.Companion.getDEFAULT(), data, size, null);
                emit("look_setup_uncached", ms(t), "size=" + size);
                t = start();
                LookSetup.Companion.create(Look.Companion.getDEFAULT(), data, size, setup.getOutputLut());
                emit("look_setup_reuse_lut", ms(t), "size=" + size);
            }
        }
    }
    private static Object call(Object target, String name, Object... args) throws Exception {
        for (java.lang.reflect.Method m : target.getClass().getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == args.length) return m.invoke(target, args);
        }
        throw new NoSuchMethodException(target.getClass().getName() + "." + name);
    }
    private static void requireBayer(RawImage img) {
        if (img.getLinear() || !img.getCfa().isBayer()) throw new IllegalArgumentException("This probe expects a Bayer camera DNG");
    }
    private static void mapped(String path) throws Exception {
        RawImage img = Objects.requireNonNull(DngReader.INSTANCE.read(Files.readAllBytes(Paths.get(path))));
        requireBayer(img);
        int longest = Math.max(img.getCropWidth(), img.getCropHeight());
        int k = Math.max(1, longest / 2 / 4096);
        float samples = longest / 2 >= 4096 ? k * k : 1f;
        for (int pass = 0; pass < 2; pass++) {
            long t = start();
            LinearImage out = RawDeveloper.INSTANCE.develop(img, 4096, false, new RawDeveloper.Options(true, false), size -> {
                try {
                    File f = File.createTempFile("spektra-raw-mapping-", ".bin", new File("/data/local/tmp"));
                    try (RandomAccessFile raf = new RandomAccessFile(f, "rw")) {
                        android.system.Os.posix_fallocate(raf.getFD(), 0, size.longValue());
                        return raf.getChannel().map(java.nio.channels.FileChannel.MapMode.READ_WRITE, 0, size);
                    } finally { f.delete(); }
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            emit("mapped_develop_without_chroma", ms(t), "pass=" + pass);
            t = start();
            ChromaDenoise.INSTANCE.apply(out, img, samples);
            emit("mapped_chroma", ms(t), "pass=" + pass);
            out = null;
            System.gc();
        }
    }
    private static void gain(String path) throws Exception {
        RawImage img = Objects.requireNonNull(DngReader.INSTANCE.read(Files.readAllBytes(Paths.get(path))));
        RawImage noGain = new RawImage(img.getRawWidth(), img.getRawHeight(), img.getData(), img.getCfa(), img.getBlack(), img.getWhite(),
            img.getCropX(), img.getCropY(), img.getCropWidth(), img.getCropHeight(), img.getWb(), img.getCamToSrgb(), img.getOrientation(),
            img.getBaselineExposure(), Collections.emptyList(), img.getMake(), img.getModel(), img.getNoiseScale(), img.getNoiseOffset(), img.getLinear(), img.getTags());
        // An ablation to attribute cost, NOT a quality-preserving optimization.
        for (RawImage input : new RawImage[]{img, noGain, img, noGain}) {
            long t = start();
            LinearImage out = RawDeveloper.INSTANCE.develop(input, 4096, false, new RawDeveloper.Options(true, false), ByteBuffer::allocateDirect);
            emit("develop_gain_ablation", ms(t), "gainMaps=" + input.getGainMaps().size() + " output=" + out.getWidth() + "x" + out.getHeight());
            out = null;
            System.gc();
        }
    }
    private static void render(String path, String apk) throws Exception {
        RawImage img = Objects.requireNonNull(DngReader.INSTANCE.read(Files.readAllBytes(Paths.get(path))));
        long t = start();
        LinearImage linear = RawDeveloper.INSTANCE.develop(img, 4096, false, new RawDeveloper.Options(), ByteBuffer::allocateDirect);
        emit("render_source_develop", ms(t), "output=" + linear.getWidth() + "x" + linear.getHeight());
        img = null;
        System.gc();
        try (ZipFile z = new ZipFile(apk)) {
            EngineData data = engine(z);
            Look look = Look.Companion.getDEFAULT();
            t = start();
            LookSetup setup = LookSetup.Companion.create(look, data, 48, null);
            emit("render_setup48", ms(t), "");
            t = start();
            Object egl = Class.forName("app.spektralab.gl.EglCore").getConstructor().newInstance();
            Object surface = call(egl, "createPbuffer", 16, 16);
            call(egl, "makeCurrent", surface);
            Object renderer = Class.forName("app.spektralab.gl.FilmRenderer").getConstructor().newInstance();
            Object worker = Class.forName("app.spektralab.render.GlWorker").getConstructor().newInstance();
            java.lang.reflect.Field rendererField = worker.getClass().getDeclaredField("renderer");
            rendererField.setAccessible(true);
            rendererField.set(worker, renderer);
            emit("egl_renderer_init", ms(t), "gpu=" + android.opengl.GLES30.glGetString(android.opengl.GLES30.GL_RENDERER));
            Class<?> srcClass = Class.forName("app.spektralab.data.SourceImage");
            Object src = srcClass.getConstructor(android.graphics.Bitmap.class, LinearImage.class, int.class, int.class, Closeable.class)
                .newInstance(null, linear, linear.getWidth(), linear.getHeight(), null);
            Object framingCompanion = Class.forName("app.spektralab.render.Framing").getField("Companion").get(null);
            for (int edge : new int[]{4096, 2048, 4096}) {
                Object framing = call(framingCompanion, "compute", linear.getWidth(), linear.getHeight(), look, edge, false);
                t = start();
                android.graphics.Bitmap bmp = (android.graphics.Bitmap) call(worker, "renderPhoto", src, setup, -0.066129066f, framing, null, 1, new float[]{0,0,0});
                emit("gpu_render_and_readback", ms(t), "edge=" + edge + " output=" + bmp.getWidth() + "x" + bmp.getHeight());
                for (android.graphics.Bitmap.CompressFormat format : new android.graphics.Bitmap.CompressFormat[]{android.graphics.Bitmap.CompressFormat.JPEG, android.graphics.Bitmap.CompressFormat.PNG}) {
                    final long[] count = {0};
                    OutputStream sink = new OutputStream() {
                        public void write(int b) { count[0]++; }
                        public void write(byte[] b, int off, int len) { count[0] += len; }
                    };
                    t = start();
                    boolean ok = bmp.compress(format, format == android.graphics.Bitmap.CompressFormat.JPEG ? 95 : 100, sink);
                    emit("encode", ms(t), "format=" + format + " edge=" + edge + " bytes=" + count[0] + " ok=" + ok + " sink=count_only");
                    if (!ok) throw new IllegalStateException("encode failed");
                }
                bmp.recycle();
            }
            call(renderer, "release");
            call(egl, "releaseSurface", surface);
            call(egl, "release");
        }
    }
}
