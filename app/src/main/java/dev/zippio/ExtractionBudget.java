package dev.zippio;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/** One operation's actual output counters and ownership of temporary files. */
final class ExtractionBudget implements AutoCloseable {
    static final long FILE_LIMIT = 4L * 1024 * 1024 * 1024;
    static final long TOTAL_LIMIT = 16L * 1024 * 1024 * 1024;
    static final int ENTRY_LIMIT = 100_000;
    static final long DICTIONARY_LIMIT = 64L * 1024 * 1024;
    // Decoder bookkeeping is additional to the 64 MiB dictionary itself.
    static final int MEMORY_LIMIT_KIB = 65 * 1024;
    static final long FREE_RESERVE = 256L * 1024 * 1024;

    private final File root;
    private final long fileLimit;
    private final long totalLimit;
    private final int entryLimit;
    private final long reserve;
    private final List<File> created = new ArrayList<>();
    private int entries;
    private long total;
    private boolean committed;

    ExtractionBudget(File root) {
        this(root, FILE_LIMIT, TOTAL_LIMIT, ENTRY_LIMIT, FREE_RESERVE);
    }

    // Small ceilings let regression tests exercise real streams without writing GiB.
    ExtractionBudget(File root, long fileLimit, long totalLimit, int entryLimit, long reserve) {
        this.root = root;
        this.fileLimit = fileLimit;
        this.totalLimit = totalLimit;
        this.entryLimit = entryLimit;
        this.reserve = reserve;
    }

    void entry() throws IOException {
        checkCancellation();
        if (entries >= entryLimit) throw new IOException("展開項目数の上限を超えました。");
        entries++;
    }

    void directory(File directory) throws IOException {
        checkCancellation();
        if (directory == null) return;
        if (directory.exists()) {
            if (!directory.isDirectory() || Files.isSymbolicLink(directory.toPath()))
                throw new IOException("安全でない展開先です。");
            return;
        }
        directory(directory.getParentFile());
        Files.createDirectory(directory.toPath());
        created.add(directory);
    }

    OutputStream output(File file) throws IOException {
        directory(file.getParentFile());
        checkCancellation();
        OutputStream output = Files.newOutputStream(file.toPath(),
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        created.add(file);
        return new OutputStream() {
            private long bytes;

            @Override public void write(int value) throws IOException {
                byte[] one = {(byte) value};
                write(one, 0, 1);
            }

            @Override public void write(byte[] data, int offset, int count) throws IOException {
                if (offset < 0 || count < 0 || offset > data.length - count)
                    throw new IndexOutOfBoundsException();
                checkCancellation();
                if (count > fileLimit - bytes)
                    throw new IOException("単体ファイルの展開上限を超えました。");
                if (count > totalLimit - total)
                    throw new IOException("アーカイブ全体の展開上限を超えました。");
                if (root.getUsableSpace() < reserve + count)
                    throw new IOException("展開後に必要な空き容量を確保できません。");
                output.write(data, offset, count);
                bytes += count;
                total += count;
            }

            @Override public void flush() throws IOException { output.flush(); }
            @Override public void close() throws IOException { output.close(); }
        };
    }

    void commit() throws IOException {
        checkCancellation();
        committed = true;
    }

    @Override public void close() throws IOException {
        if (committed) return;
        IOException failure = null;
        for (int index = created.size() - 1; index >= 0; index--) {
            try { Files.deleteIfExists(created.get(index).toPath()); }
            catch (IOException error) {
                if (failure == null) failure = error; else failure.addSuppressed(error);
            }
        }
        if (failure != null) throw failure;
    }

    static void checkCancellation() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted())
            throw new InterruptedIOException("操作を中止しました。");
    }
}
