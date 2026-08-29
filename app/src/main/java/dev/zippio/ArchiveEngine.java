package dev.zippio;

import com.github.junrar.Archive;
import net.lingala.zip4j.exception.ZipException;
import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.model.FileHeader;
import net.lingala.zip4j.model.ZipParameters;
import net.lingala.zip4j.model.enums.CompressionLevel;
import net.lingala.zip4j.model.enums.CompressionMethod;
import net.lingala.zip4j.model.enums.EncryptionMethod;

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

/** Archive operations performed only on app-private temporary files. */
public final class ArchiveEngine {
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final long LARGE_ARCHIVE_BYTES = 1024L * 1024L * 1024L;
    private static final int MANY_FILES_THRESHOLD = 5_000;

    public enum ArchiveFormat {
        ZIP("ZIP", "zip", "application/zip"),
        SEVEN_Z("7z", "7z", "application/x-7z-compressed");

        final String label;
        final String extension;
        final String mimeType;

        ArchiveFormat(String label, String extension, String mimeType) {
            this.label = label;
            this.extension = extension;
            this.mimeType = mimeType;
        }

        public static ArchiveFormat fromLabel(String label) {
            return "7z".equalsIgnoreCase(label) ? SEVEN_Z : ZIP;
        }
    }

    private ArchiveEngine() {
    }

    /**
     * Returns a lightweight, safe-to-display summary before extraction begins.
     * Entry names are checked here as well as during extraction, so unsafe archives are
     * rejected before the user chooses a destination folder.
     */
    public static ArchiveInfo inspect(File archive, char[] password) throws Exception {
        String lowerName = archive.getName().toLowerCase();
        if (lowerName.endsWith(".zip")) {
            return inspectZip(archive, password);
        } else if (lowerName.endsWith(".7z")) {
            return inspect7z(archive, password);
        } else if (lowerName.endsWith(".rar")) {
            return inspectRar(archive, password);
        }
        throw new IOException("ZIP、7z、RAR のいずれかを選んでください。");
    }

    public static void create(
            File sourceDirectory,
            File destinationArchive,
            ArchiveFormat format,
            char[] password
    ) throws Exception {
        if (!sourceDirectory.isDirectory()) {
            throw new IOException("圧縮元フォルダを読み取れません。");
        }
        if (format == ArchiveFormat.ZIP) {
            createZip(sourceDirectory, destinationArchive, password);
        } else {
            create7z(sourceDirectory, destinationArchive, password);
        }
    }

    public static void extract(File archive, File destinationDirectory, char[] password) throws Exception {
        if (!destinationDirectory.exists() && !destinationDirectory.mkdirs()) {
            throw new IOException("展開先フォルダを作成できません。");
        }

        String lowerName = archive.getName().toLowerCase();
        if (lowerName.endsWith(".zip")) {
            extractZip(archive, destinationDirectory, password);
        } else if (lowerName.endsWith(".7z")) {
            extract7z(archive, destinationDirectory, password);
        } else if (lowerName.endsWith(".rar")) {
            extractRar(archive, destinationDirectory, password);
        } else {
            throw new IOException("ZIP、7z、RAR のいずれかを選んでください。");
        }
    }

    private static void createZip(File sourceDirectory, File destinationArchive, char[] password)
            throws Exception {
        ZipParameters parameters = new ZipParameters();
        parameters.setIncludeRootFolder(true);
        parameters.setCompressionMethod(CompressionMethod.DEFLATE);
        parameters.setCompressionLevel(CompressionLevel.NORMAL);
        if (hasPassword(password)) {
            parameters.setEncryptFiles(true);
            parameters.setEncryptionMethod(EncryptionMethod.AES);
        }

        ZipFile zip = hasPassword(password)
                ? new ZipFile(destinationArchive, password)
                : new ZipFile(destinationArchive);
        zip.addFolder(sourceDirectory, parameters);
    }

    private static ArchiveInfo inspectZip(File archive, char[] password) throws Exception {
        try {
            ZipFile zip = hasPassword(password)
                    ? new ZipFile(archive, password)
                    : new ZipFile(archive);
            List<FileHeader> headers = zip.getFileHeaders();
            int files = 0;
            long uncompressedBytes = 0;
            boolean encrypted = false;
            for (FileHeader header : headers) {
                validateArchiveEntryName(header.getFileName());
                if (!header.isDirectory()) {
                    files++;
                    uncompressedBytes = addSize(uncompressedBytes, header.getUncompressedSize());
                }
                encrypted |= header.isEncrypted();
            }
            return new ArchiveInfo("ZIP", headers.size(), files, uncompressedBytes, encrypted);
        } catch (ZipException error) {
            if (error.getType() != ZipException.Type.UNKNOWN_COMPRESSION_METHOD) {
                throw error;
            }
            return PpmdZipSupport.inspect(archive, password);
        }
    }

    private static ArchiveInfo inspect7z(File archive, char[] password) throws Exception {
        int entries = 0;
        int files = 0;
        long uncompressedBytes = 0;
        try (SevenZFile input = hasPassword(password)
                ? new SevenZFile(archive, password)
                : new SevenZFile(archive)) {
            SevenZArchiveEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                entries++;
                validateArchiveEntryName(entry.getName());
                if (!entry.isDirectory()) {
                    files++;
                    uncompressedBytes = addSize(uncompressedBytes, entry.getSize());
                }
            }
        }
        // Commons Compress does not expose per-entry 7z encryption metadata. Avoid claiming
        // encryption merely because the user supplied a password.
        return new ArchiveInfo("7z", entries, files, uncompressedBytes, false);
    }

    private static ArchiveInfo inspectRar(File archive, char[] password) throws Exception {
        String passwordString = hasPassword(password) ? new String(password) : null;
        int files = 0;
        long uncompressedBytes = 0;
        try (Archive input = passwordString == null
                ? new Archive(archive)
                : new Archive(archive, passwordString)) {
            List<com.github.junrar.rarfile.FileHeader> headers = input.getFileHeaders();
            for (com.github.junrar.rarfile.FileHeader entry : headers) {
                validateArchiveEntryName(entry.getFileName());
                if (!entry.isDirectory()) {
                    files++;
                    uncompressedBytes = addSize(uncompressedBytes, entry.getFullUnpackSize());
                }
            }
            return new ArchiveInfo(
                    "RAR",
                    headers.size(),
                    files,
                    uncompressedBytes,
                    input.isEncrypted()
            );
        }
    }

    private static void create7z(File sourceDirectory, File destinationArchive, char[] password)
            throws Exception {
        try (SevenZOutputFile output = hasPassword(password)
                ? new SevenZOutputFile(destinationArchive, password)
                : new SevenZOutputFile(destinationArchive)) {
            addTo7z(output, sourceDirectory, sourceDirectory.getName());
        }
    }

    private static void addTo7z(SevenZOutputFile output, File item, String entryName)
            throws IOException {
        SevenZArchiveEntry entry = output.createArchiveEntry(item, entryName);
        output.putArchiveEntry(entry);
        if (item.isFile()) {
            try (BufferedInputStream input = new BufferedInputStream(new FileInputStream(item))) {
                copyTo7z(input, output);
            }
        }
        output.closeArchiveEntry();

        if (item.isDirectory()) {
            File[] children = item.listFiles();
            if (children == null) {
                throw new IOException("フォルダを読み取れません: " + item.getName());
            }
            for (File child : children) {
                addTo7z(output, child, entryName + "/" + child.getName());
            }
        }
    }

    private static void extractZip(File archive, File destinationDirectory, char[] password)
            throws Exception {
        try {
            ZipFile zip = hasPassword(password)
                    ? new ZipFile(archive, password)
                    : new ZipFile(archive);
            List<FileHeader> headers = zip.getFileHeaders();
            for (FileHeader header : headers) {
                safeDestination(destinationDirectory, header.getFileName());
            }
            zip.extractAll(destinationDirectory.getAbsolutePath());
        } catch (ZipException error) {
            if (error.getType() != ZipException.Type.UNKNOWN_COMPRESSION_METHOD) {
                throw error;
            }
            PpmdZipSupport.extract(archive, destinationDirectory, password);
        }
    }

    private static void extract7z(File archive, File destinationDirectory, char[] password)
            throws Exception {
        try (SevenZFile input = hasPassword(password)
                ? new SevenZFile(archive, password)
                : new SevenZFile(archive)) {
            SevenZArchiveEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                File output = safeDestination(destinationDirectory, entry.getName());
                if (entry.isDirectory()) {
                    if (!output.exists() && !output.mkdirs()) {
                        throw new IOException("フォルダを作成できません: " + entry.getName());
                    }
                    continue;
                }
                File parent = output.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    throw new IOException("フォルダを作成できません: " + entry.getName());
                }
                try (BufferedOutputStream stream = new BufferedOutputStream(new FileOutputStream(output))) {
                    copyFrom7z(input, stream);
                }
            }
        }
    }

    private static void extractRar(File archive, File destinationDirectory, char[] password)
            throws Exception {
        String passwordString = hasPassword(password) ? new String(password) : null;
        try (Archive input = passwordString == null
                ? new Archive(archive)
                : new Archive(archive, passwordString)) {
            for (com.github.junrar.rarfile.FileHeader entry : input.getFileHeaders()) {
                File output = safeDestination(destinationDirectory, entry.getFileName());
                if (entry.isDirectory()) {
                    if (!output.exists() && !output.mkdirs()) {
                        throw new IOException("フォルダを作成できません: " + entry.getFileName());
                    }
                    continue;
                }
                File parent = output.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    throw new IOException("フォルダを作成できません: " + entry.getFileName());
                }
                try (OutputStream stream = new BufferedOutputStream(new FileOutputStream(output))) {
                    input.extractFile(entry, stream);
                }
            }
        }
    }

    /** Blocks zip-slip style names before any archive library writes to disk. */
    static File safeDestination(File root, String archiveName) throws IOException {
        String normalized = validateArchiveEntryName(archiveName);

        File target = new File(root, normalized);
        String rootPath = root.getCanonicalPath();
        String targetPath = target.getCanonicalPath();
        if (!targetPath.startsWith(rootPath + File.separator)) {
            throw new IOException("展開先の外へ書き込もうとする項目を拒否しました。");
        }
        return target;
    }

    static String validateArchiveEntryName(String archiveName) throws IOException {
        if (archiveName == null) {
            throw new IOException("名前のないアーカイブ項目があります。");
        }
        String normalized = archiveName.replace('\\', '/');
        if (normalized.isEmpty()
                || normalized.startsWith("/")
                || normalized.startsWith("~")
                || normalized.indexOf('\u0000') >= 0) {
            throw new IOException("安全でないアーカイブ項目名です。");
        }
        String[] parts = normalized.split("/");
        for (String part : parts) {
            if (part.isEmpty() || ".".equals(part) || "..".equals(part)) {
                throw new IOException("安全でないアーカイブ項目名です: " + archiveName);
            }
        }
        return normalized;
    }

    private static boolean hasPassword(char[] password) {
        return password != null && password.length > 0;
    }

    private static long addSize(long total, long size) {
        if (size <= 0) {
            return total;
        }
        return Long.MAX_VALUE - total < size ? Long.MAX_VALUE : total + size;
    }

    /** Immutable archive metadata used for the extraction confirmation screen. */
    public static final class ArchiveInfo {
        public final String format;
        public final int entryCount;
        public final int fileCount;
        public final long uncompressedBytes;
        public final boolean encrypted;

        ArchiveInfo(String format, int entryCount, int fileCount, long uncompressedBytes,
                    boolean encrypted) {
            this.format = format;
            this.entryCount = entryCount;
            this.fileCount = fileCount;
            this.uncompressedBytes = uncompressedBytes;
            this.encrypted = encrypted;
        }

        public boolean needsCapacityWarning(long compressedBytes) {
            if (entryCount > MANY_FILES_THRESHOLD || uncompressedBytes >= LARGE_ARCHIVE_BYTES) {
                return true;
            }
            return compressedBytes > 0
                    && uncompressedBytes >= 100L * 1024L * 1024L
                    && uncompressedBytes / compressedBytes >= 100;
        }
    }

    private static void copy(java.io.InputStream input, java.io.OutputStream output) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        int count;
        while ((count = input.read(buffer)) != -1) {
            output.write(buffer, 0, count);
        }
    }

    private static void copyTo7z(java.io.InputStream input, SevenZOutputFile output) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        int count;
        while ((count = input.read(buffer)) != -1) {
            output.write(buffer, 0, count);
        }
    }

    private static void copyFrom7z(SevenZFile input, java.io.OutputStream output) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        int count;
        while ((count = input.read(buffer)) != -1) {
            output.write(buffer, 0, count);
        }
    }
}
