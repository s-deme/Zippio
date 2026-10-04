package dev.zippio;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;

/** Exercises the shipped engines on real Android page sizes using private fixtures. */
public final class ArchiveInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            if (!getTargetContext().getPackageName().endsWith(".validation"))
                throw new AssertionError("Validation application required");
            File root = Files.createTempDirectory(getTargetContext().getCacheDir().toPath(), "engine-").toFile();
            File source = new File(root, "source");
            if (!source.mkdir()) throw new AssertionError("source directory");
            byte[] payload = "page-size regression".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            Files.write(new File(source, "test.txt").toPath(), payload);
            int checks = 0;
            for (ArchiveEngine.ArchiveFormat format : ArchiveEngine.ArchiveFormat.values()) {
                File archive = new File(root, "roundtrip." + format.extension);
                ArchiveEngine.create(source, archive, format, null, ArchiveEngine.CompressionProfile.NORMAL, false);
                File output = new File(root, format.name());
                ArchiveEngine.extract(archive, output, null);
                if (!Arrays.equals(payload, Files.readAllBytes(new File(output, "test.txt").toPath())))
                    throw new AssertionError(format.name());
                checks++;
            }
            for (String name : new String[]{"ppmd.zip", "stored.rar"}) {
                File archive = new File(root, name);
                try (java.io.InputStream input = getContext().getAssets().open(name)) {
                    Files.copy(input, archive.toPath());
                }
                File output = new File(root, name + "-out");
                ArchiveEngine.extract(archive, output, null);
                try (java.util.stream.Stream<java.nio.file.Path> files = Files.walk(output.toPath())) {
                    if (!files.anyMatch(path -> Files.isRegularFile(path))) throw new AssertionError(name);
                }
                checks++;
            }
            result.putString("stream", "PASS: " + checks + " engine checks (ZIP, 7z, native PPMd ZIP, RAR)\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            result.putString("stream", "FAIL: " + android.util.Log.getStackTraceString(failure));
            finish(Activity.RESULT_CANCELED, result);
        }
    }
}
