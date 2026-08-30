package dev.zippio;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Locale;

/** Bridges the Storage Access Framework and private temporary files. */
public final class StorageBridge {
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final String[] CHILD_COLUMNS = new String[]{
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
    };

    private StorageBridge() {
    }

    public static File newWorkDirectory(Context context, String prefix) throws IOException {
        File root = new File(context.getCacheDir(), "zippio-work");
        if (!root.exists() && !root.mkdirs()) {
            throw new IOException("一時フォルダを作成できません。");
        }
        File work = new File(root, prefix + "-" + System.nanoTime());
        if (!work.mkdirs()) {
            throw new IOException("一時フォルダを作成できません。");
        }
        return work;
    }

    /** Clears only the app-private work area left behind by a process that was killed. */
    public static void clearStaleWork(Context context) {
        deleteRecursively(new File(context.getCacheDir(), "zippio-work"));
    }

    public static void copyTreeToDirectory(Context context, Uri treeUri, File targetDirectory)
            throws IOException {
        copyTreeToDirectory(context, treeUri, targetDirectory, true);
    }

    public static void copyTreeToDirectory(
            Context context,
            Uri treeUri,
            File targetDirectory,
            boolean includeHiddenFiles
    ) throws IOException {
        if (!targetDirectory.exists() && !targetDirectory.mkdirs()) {
            throw new IOException("一時フォルダを作成できません。");
        }
        String treeId = DocumentsContract.getTreeDocumentId(treeUri);
        Uri root = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeId);
        copyDocumentChildren(context, root, targetDirectory, includeHiddenFiles);
    }

    /** Copies files chosen with ACTION_OPEN_DOCUMENT into one private source directory. */
    public static void copyUrisToDirectory(Context context, List<Uri> sourceUris, File targetDirectory)
            throws IOException {
        if (sourceUris == null || sourceUris.isEmpty()) {
            throw new IOException("圧縮するファイルを選んでください。");
        }
        if (!targetDirectory.exists() && !targetDirectory.mkdirs()) {
            throw new IOException("一時フォルダを作成できません。");
        }
        for (Uri uri : sourceUris) {
            throwIfInterrupted();
            String name = localName(displayName(context, uri, "ファイル"));
            copyUriToFile(context, uri, uniqueLocalFile(targetDirectory, name));
        }
    }

    public static void copyUriToFile(Context context, Uri source, File destination) throws IOException {
        File parent = destination.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("一時フォルダを作成できません。");
        }
        try (InputStream raw = context.getContentResolver().openInputStream(source)) {
            if (raw == null) {
                throw new IOException("選択したファイルを開けません。");
            }
            try (BufferedInputStream input = new BufferedInputStream(raw);
                 BufferedOutputStream output = new BufferedOutputStream(new FileOutputStream(destination))) {
                copy(input, output);
            }
        }
    }

    public static void copyFileToUri(Context context, File source, Uri destination) throws IOException {
        try (InputStream input = new BufferedInputStream(new FileInputStream(source));
             OutputStream raw = context.getContentResolver().openOutputStream(destination, "w")) {
            if (raw == null) {
                throw new IOException("保存先を開けません。");
            }
            try (BufferedOutputStream output = new BufferedOutputStream(raw)) {
                copy(input, output);
            }
        }
    }

    public static void copyDirectoryToTree(Context context, File sourceDirectory, Uri treeUri)
            throws IOException {
        String treeId = DocumentsContract.getTreeDocumentId(treeUri);
        Uri root = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeId);
        copyDirectoryChildren(context, sourceDirectory, root);
    }

    public static String displayName(Context context, Uri uri, String fallback) {
        try (Cursor cursor = context.getContentResolver().query(
                uri,
                new String[]{OpenableColumns.DISPLAY_NAME},
                null,
                null,
                null
        )) {
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                if (name != null && !name.trim().isEmpty()) {
                    return name;
                }
            }
        } catch (Exception ignored) {
            // Some document providers do not expose a display name for a tree root.
        }
        return fallback;
    }

    public static String treeDisplayName(Context context, Uri treeUri) {
        try {
            String treeId = DocumentsContract.getTreeDocumentId(treeUri);
            Uri root = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeId);
            return displayName(context, root, "ファイル");
        } catch (Exception ignored) {
            return "ファイル";
        }
    }

    public static void takePersistablePermission(Context context, Uri uri, int flags) {
        int readWriteFlags = flags & (android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                | android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        if (readWriteFlags == 0) {
            return;
        }
        try {
            context.getContentResolver().takePersistableUriPermission(uri, readWriteFlags);
        } catch (SecurityException ignored) {
            // Processing occurs immediately, so persistence is only a convenience.
        }
    }

    public static String safeFileStem(String name) {
        String stem = name == null ? "アーカイブ" : name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (stem.isEmpty()) {
            return "アーカイブ";
        }
        return stem.length() > 80 ? stem.substring(0, 80) : stem;
    }

    public static String safeFileName(String name, String fallback) {
        String result = safeFileStem(name);
        return result.isEmpty() ? fallback : result;
    }

    public static void deleteRecursively(File target) {
        if (target == null || !target.exists()) {
            return;
        }
        File[] children = target.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        // All targets originate in the app cache work directory.
        target.delete();
    }

    private static void copyDocumentChildren(
            Context context,
            Uri parentUri,
            File targetDirectory,
            boolean includeHiddenFiles
    )
            throws IOException {
        ContentResolver resolver = context.getContentResolver();
        String parentId = DocumentsContract.getDocumentId(parentUri);
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(parentUri, parentId);
        try (Cursor cursor = resolver.query(childrenUri, CHILD_COLUMNS, null, null, null)) {
            if (cursor == null) {
                throw new IOException("フォルダの内容を読み取れません。");
            }
            while (cursor.moveToNext()) {
                throwIfInterrupted();
                String documentId = cursor.getString(0);
                String displayName = cursor.getString(1);
                String mimeType = cursor.getString(2);
                if (!includeHiddenFiles && displayName != null && displayName.startsWith(".")) {
                    continue;
                }
                String safeName = localName(displayName);
                Uri childUri = DocumentsContract.buildDocumentUriUsingTree(parentUri, documentId);
                File childTarget = new File(targetDirectory, safeName);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType)) {
                    if (!childTarget.mkdirs() && !childTarget.isDirectory()) {
                        throw new IOException("一時フォルダを作成できません: " + safeName);
                    }
                    copyDocumentChildren(context, childUri, childTarget, includeHiddenFiles);
                } else {
                    copyUriToFile(context, childUri, childTarget);
                }
            }
        }
    }

    private static File uniqueLocalFile(File directory, String preferredName) {
        String base = preferredName;
        String extension = "";
        int dot = preferredName.lastIndexOf('.');
        if (dot > 0) {
            base = preferredName.substring(0, dot);
            extension = preferredName.substring(dot);
        }
        for (int index = 0; index < 10_000; index++) {
            File candidate = new File(directory,
                    index == 0 ? preferredName : base + " (" + index + ")" + extension);
            if (!candidate.exists()) {
                return candidate;
            }
        }
        return new File(directory, base + "-" + System.nanoTime() + extension);
    }

    private static void copyDirectoryChildren(Context context, File sourceDirectory, Uri parentUri)
            throws IOException {
        File[] children = sourceDirectory.listFiles();
        if (children == null) {
            throw new IOException("展開したファイルを読み取れません。");
        }
        for (File child : children) {
            throwIfInterrupted();
            String name = localName(child.getName());
            if (child.isDirectory()) {
                Uri childDirectory = createUniqueDocument(
                        context,
                        parentUri,
                        DocumentsContract.Document.MIME_TYPE_DIR,
                        name
                );
                copyDirectoryChildren(context, child, childDirectory);
            } else {
                Uri childDocument = createUniqueDocument(context, parentUri, mimeTypeFor(name), name);
                copyFileToUri(context, child, childDocument);
            }
        }
    }

    private static Uri createUniqueDocument(Context context, Uri parentUri, String mimeType, String preferredName)
            throws IOException {
        ContentResolver resolver = context.getContentResolver();
        String base = preferredName;
        String extension = "";
        int dot = preferredName.lastIndexOf('.');
        if (dot > 0 && !DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType)) {
            base = preferredName.substring(0, dot);
            extension = preferredName.substring(dot);
        }

        for (int index = 0; index < 10_000; index++) {
            String candidate = index == 0 ? preferredName : base + " (" + index + ")" + extension;
            if (childExists(resolver, parentUri, candidate)) {
                continue;
            }
            Uri created = DocumentsContract.createDocument(resolver, parentUri, mimeType, candidate);
            if (created != null) {
                return created;
            }
            throw new IOException("展開先にファイルを作成できません: " + candidate);
        }
        throw new IOException("同名ファイルが多すぎるため保存できません。");
    }

    private static boolean childExists(ContentResolver resolver, Uri parentUri, String name) throws IOException {
        String parentId = DocumentsContract.getDocumentId(parentUri);
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(parentUri, parentId);
        try (Cursor cursor = resolver.query(
                childrenUri,
                new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME},
                null,
                null,
                null
        )) {
            if (cursor == null) {
                throw new IOException("展開先フォルダを読み取れません。");
            }
            while (cursor.moveToNext()) {
                if (name.equals(cursor.getString(0))) {
                    return true;
                }
            }
            return false;
        }
    }

    private static String localName(String name) throws IOException {
        if (name == null || name.trim().isEmpty()
                || name.contains("/")
                || name.contains("\\")
                || ".".equals(name)
                || "..".equals(name)) {
            throw new IOException("安全でないファイル名です。");
        }
        return name;
    }

    private static String mimeTypeFor(String name) {
        String extension = MimeTypeMap.getFileExtensionFromUrl(name);
        if (extension != null && !extension.isEmpty()) {
            String type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                    extension.toLowerCase(Locale.ROOT)
            );
            if (type != null) {
                return type;
            }
        }
        return "application/octet-stream";
    }

    private static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        int count;
        while ((count = input.read(buffer)) != -1) {
            throwIfInterrupted();
            output.write(buffer, 0, count);
        }
    }

    private static void throwIfInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("操作を中止しました。");
        }
    }
}
