package dev.zippio;

import net.sf.sevenzipjbinding.ArchiveFormat;
import net.sf.sevenzipjbinding.ExtractOperationResult;
import net.sf.sevenzipjbinding.IInArchive;
import net.sf.sevenzipjbinding.ISequentialOutStream;
import net.sf.sevenzipjbinding.PropID;
import net.sf.sevenzipjbinding.SevenZip;
import net.sf.sevenzipjbinding.SevenZipException;
import net.sf.sevenzipjbinding.impl.RandomAccessFileInStream;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;

/**
 * Reads ZIP entries whose compression method is not implemented by zip4j, notably PPMd.
 *
 * <p>The bundled Android 7-Zip binding contains the PPMd decoder. It is intentionally used
 * only as the fallback for ZIP methods zip4j does not know, keeping the existing ZIP path for
 * standard and AES ZIP archives.</p>
 */
final class PpmdZipSupport {
    private static boolean initialized;

    private PpmdZipSupport() {
    }

    static ArchiveEngine.ArchiveInfo inspect(File file, char[] password) throws Exception {
        try (ArchiveHandle archive = open(file, password)) {
            int entries = archive.input.getNumberOfItems();
            ArchiveEngine.ArchiveSummary summary = new ArchiveEngine.ArchiveSummary();
            boolean encrypted = false;

            for (int index = 0; index < entries; index++) {
                String name = archive.input.getStringProperty(index, PropID.PATH);
                summary.addEntry(
                        name,
                        isDirectory(archive.input, index),
                        numberProperty(archive.input, index, PropID.SIZE)
                );
                encrypted |= Boolean.TRUE.equals(archive.input.getProperty(index, PropID.ENCRYPTED));
            }
            return summary.toArchiveInfo("ZIP (PPMd)", encrypted);
        }
    }

    static void extract(File file, File destinationDirectory, char[] password) throws Exception {
        try (ArchiveHandle archive = open(file, password)) {
            int entries = archive.input.getNumberOfItems();
            Entry[] archiveEntries = new Entry[entries];

            // Validate every destination before the first file is written.
            for (int index = 0; index < entries; index++) {
                String name = archive.input.getStringProperty(index, PropID.PATH);
                archiveEntries[index] = new Entry(
                        index,
                        name,
                        isDirectory(archive.input, index),
                        ArchiveEngine.safeDestination(destinationDirectory, name)
                );
            }

            String passwordString = passwordString(password);
            for (Entry entry : archiveEntries) {
                ArchiveEngine.ensureExtractionDirectory(
                        entry.destination,
                        entry.name,
                        entry.directory
                );
                if (entry.directory) {
                    continue;
                }

                try (OutputStream output = new BufferedOutputStream(
                        new FileOutputStream(entry.destination))) {
                    ExtractOperationResult result = archive.input.extractSlow(
                            entry.index,
                            new OutputStreamAdapter(output),
                            passwordString
                    );
                    if (result != ExtractOperationResult.OK) {
                        throw new IOException("PPMd ZIP を展開できません: " + result);
                    }
                }
            }
        }
    }

    private static synchronized void ensureInitialized() throws IOException {
        if (initialized) {
            return;
        }
        try {
            System.loadLibrary("7-Zip-JBinding");
            SevenZip.initLoadedLibraries();
            initialized = true;
        } catch (Throwable error) {
            throw new IOException("PPMd ZIP デコーダを初期化できません。", error);
        }
    }

    private static ArchiveHandle open(File file, char[] password) throws Exception {
        ensureInitialized();
        RandomAccessFile randomAccessFile = new RandomAccessFile(file, "r");
        RandomAccessFileInStream stream = new RandomAccessFileInStream(randomAccessFile);
        try {
            String passwordString = passwordString(password);
            IInArchive input = passwordString == null
                    ? SevenZip.openInArchive(ArchiveFormat.ZIP, stream)
                    : SevenZip.openInArchive(ArchiveFormat.ZIP, stream, passwordString);
            return new ArchiveHandle(input, stream);
        } catch (Exception error) {
            try {
                stream.close();
            } catch (IOException closeError) {
                error.addSuppressed(closeError);
            }
            throw error;
        }
    }

    private static boolean isDirectory(IInArchive archive, int index) throws SevenZipException {
        return Boolean.TRUE.equals(archive.getProperty(index, PropID.IS_FOLDER));
    }

    private static long numberProperty(IInArchive archive, int index, PropID property)
            throws SevenZipException {
        Object value = archive.getProperty(index, property);
        return value instanceof Number ? ((Number) value).longValue() : 0;
    }

    private static String passwordString(char[] password) {
        return password == null || password.length == 0 ? null : new String(password);
    }

    private static final class ArchiveHandle implements AutoCloseable {
        final IInArchive input;
        final RandomAccessFileInStream stream;

        ArchiveHandle(IInArchive input, RandomAccessFileInStream stream) {
            this.input = input;
            this.stream = stream;
        }

        @Override
        public void close() throws IOException {
            IOException failure = null;
            try {
                input.close();
            } catch (IOException error) {
                failure = error;
            }
            try {
                stream.close();
            } catch (IOException error) {
                if (failure == null) {
                    failure = error;
                } else {
                    failure.addSuppressed(error);
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }

    private static final class Entry {
        final int index;
        final String name;
        final boolean directory;
        final File destination;

        Entry(int index, String name, boolean directory, File destination) {
            this.index = index;
            this.name = name;
            this.directory = directory;
            this.destination = destination;
        }
    }

    private static final class OutputStreamAdapter implements ISequentialOutStream {
        private final OutputStream output;

        OutputStreamAdapter(OutputStream output) {
            this.output = output;
        }

        @Override
        public int write(byte[] data) throws SevenZipException {
            try {
                output.write(data);
                return data.length;
            } catch (IOException error) {
                throw new SevenZipException(error);
            }
        }
    }
}
