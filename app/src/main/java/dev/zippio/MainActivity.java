package dev.zippio;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.HideReturnsTransformationMethod;
import android.text.method.PasswordTransformationMethod;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;

import java.io.File;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Entry point and user-facing workflow for archive creation and extraction. */
public final class MainActivity extends Activity {
    private static final int REQUEST_SOURCE_DIRECTORY = 101;
    private static final int REQUEST_CREATE_ARCHIVE = 102;
    private static final int REQUEST_ARCHIVE = 103;
    private static final int REQUEST_DESTINATION_DIRECTORY = 104;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainThread = new Handler(Looper.getMainLooper());

    private Spinner formatSpinner;
    private EditText passwordInput;
    private CheckBox showPassword;
    private Button compressButton;
    private Button extractButton;
    private ProgressBar progress;
    private TextView statusText;

    private ArchiveEngine.ArchiveFormat pendingFormat;
    private char[] pendingPassword;
    private Uri pendingArchiveUri;
    private Uri incomingArchiveUri;
    private File generatedWorkDirectory;
    private File generatedArchive;
    private File preparedArchiveWorkDirectory;
    private File preparedArchive;
    private boolean working;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        formatSpinner = findViewById(R.id.format_spinner);
        passwordInput = findViewById(R.id.password_input);
        showPassword = findViewById(R.id.show_password);
        compressButton = findViewById(R.id.compress_button);
        extractButton = findViewById(R.id.extract_button);
        progress = findViewById(R.id.progress);
        statusText = findViewById(R.id.status_text);

        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(
                this,
                R.array.archive_formats,
                android.R.layout.simple_spinner_item
        );
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        formatSpinner.setAdapter(adapter);

        compressButton.setOnClickListener(view -> beginCompression());
        extractButton.setOnClickListener(view -> beginExtraction());
        showPassword.setOnCheckedChangeListener((button, checked) -> {
            passwordInput.setTransformationMethod(checked
                    ? HideReturnsTransformationMethod.getInstance()
                    : PasswordTransformationMethod.getInstance());
            passwordInput.setSelection(passwordInput.length());
        });

        handleIncomingArchive(getIntent());
    }

    private void beginCompression() {
        incomingArchiveUri = null;
        clearPreparedArchive();
        pendingFormat = ArchiveEngine.ArchiveFormat.fromLabel(
                String.valueOf(formatSpinner.getSelectedItem())
        );
        capturePassword();
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                .addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                .addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_SOURCE_DIRECTORY);
    }

    private void beginExtraction() {
        capturePassword();
        if (incomingArchiveUri != null) {
            prepareArchivePreview(incomingArchiveUri);
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                        "application/zip",
                        "application/x-zip-compressed",
                        "application/x-7z-compressed",
                        "application/vnd.rar",
                        "application/x-rar-compressed"
                })
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_ARCHIVE);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingArchive(intent);
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Uri selectedUri = data == null ? null : data.getData();
        if (resultCode != RESULT_OK || selectedUri == null) {
            onPickerCancelled(requestCode);
            return;
        }
        StorageBridge.takePersistablePermission(this, selectedUri, data.getFlags());

        if (requestCode == REQUEST_SOURCE_DIRECTORY) {
            createArchiveFromTree(selectedUri);
        } else if (requestCode == REQUEST_CREATE_ARCHIVE) {
            saveGeneratedArchive(selectedUri);
        } else if (requestCode == REQUEST_ARCHIVE) {
            pendingArchiveUri = selectedUri;
            prepareArchivePreview(selectedUri);
        } else if (requestCode == REQUEST_DESTINATION_DIRECTORY) {
            extractArchiveToTree(pendingArchiveUri, selectedUri);
        }
    }

    private void createArchiveFromTree(Uri sourceTreeUri) {
        setUiState(true, false);
        setStatus("フォルダを読み込み、圧縮しています…");
        final ArchiveEngine.ArchiveFormat format = pendingFormat;
        final char[] password = passwordCopy();
        executor.execute(() -> {
            File work = null;
            try {
                work = StorageBridge.newWorkDirectory(this, "compress");
                String sourceName = StorageBridge.safeFileStem(
                        StorageBridge.treeDisplayName(this, sourceTreeUri)
                );
                File source = new File(work, sourceName);
                StorageBridge.copyTreeToDirectory(this, sourceTreeUri, source);

                File output = new File(work, sourceName + "." + format.extension);
                ArchiveEngine.create(source, output, format, password);
                File completedWork = work;
                mainThread.post(() -> promptArchiveDestination(completedWork, output, format));
            } catch (Exception error) {
                StorageBridge.deleteRecursively(work);
                mainThread.post(() -> finishWithError(error));
            } finally {
                wipe(password);
            }
        });
    }

    private void promptArchiveDestination(
            File work,
            File archive,
            ArchiveEngine.ArchiveFormat format
    ) {
        generatedWorkDirectory = work;
        generatedArchive = archive;
        setUiState(false, false);
        setStatus("圧縮が完了しました。保存先とファイル名を選んでください。");
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(format.mimeType)
                .putExtra(Intent.EXTRA_TITLE, archive.getName())
                .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                .addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_CREATE_ARCHIVE);
    }

    private void saveGeneratedArchive(Uri destinationUri) {
        if (generatedArchive == null) {
            finishWithError(new IllegalStateException("保存するアーカイブが見つかりません。"));
            return;
        }
        setUiState(true, false);
        setStatus("アーカイブを保存しています…");
        final File archive = generatedArchive;
        executor.execute(() -> {
            try {
                StorageBridge.copyFileToUri(this, archive, destinationUri);
                mainThread.post(() -> {
                    clearGeneratedArchive();
                    finishSuccessfully("圧縮ファイルを保存しました。");
                });
            } catch (Exception error) {
                mainThread.post(() -> {
                    clearGeneratedArchive();
                    finishWithError(error);
                });
            }
        });
    }

    private void chooseExtractionDestination() {
        setStatus("解凍先フォルダを選んでください。");
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                .addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                .addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_DESTINATION_DIRECTORY);
    }

    private void prepareArchivePreview(Uri archiveUri) {
        clearPreparedArchive();
        setUiState(true, false);
        setStatus("アーカイブの内容と安全性を確認しています…");
        final char[] password = passwordCopy();
        executor.execute(() -> {
            File work = null;
            try {
                work = StorageBridge.newWorkDirectory(this, "preview");
                String archiveName = StorageBridge.safeFileName(
                        StorageBridge.displayName(this, archiveUri, "archive"),
                        "archive"
                );
                File localArchive = new File(work, archiveName);
                StorageBridge.copyUriToFile(this, archiveUri, localArchive);
                ArchiveEngine.ArchiveInfo info = ArchiveEngine.inspect(localArchive, password);
                File completedWork = work;
                mainThread.post(() -> showArchivePreview(archiveUri, completedWork, localArchive, info));
            } catch (Exception error) {
                StorageBridge.deleteRecursively(work);
                mainThread.post(() -> finishWithError(error));
            } finally {
                wipe(password);
            }
        });
    }

    private void showArchivePreview(
            Uri archiveUri,
            File work,
            File archive,
            ArchiveEngine.ArchiveInfo info
    ) {
        preparedArchiveWorkDirectory = work;
        preparedArchive = archive;
        pendingArchiveUri = archiveUri;
        setUiState(false, false);
        setStatus("内容を確認しました。解凍先を選んでください。");

        String message = getString(
                R.string.archive_preview_template,
                StorageBridge.displayName(this, archiveUri, archive.getName()),
                info.format,
                info.entryCount,
                info.fileCount,
                formatBytes(info.uncompressedBytes)
        );
        if (info.encrypted) {
            message += "\n\n" + getString(R.string.archive_preview_encrypted);
        }
        if (info.needsCapacityWarning(archive.length())) {
            message += "\n\n" + getString(R.string.archive_preview_capacity_warning);
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.archive_preview_title)
                .setMessage(message)
                .setNegativeButton(R.string.cancel, (dialog, which) -> cancelPreparedArchive())
                .setPositiveButton(R.string.choose_destination, (dialog, which) -> chooseExtractionDestination())
                .setOnCancelListener(dialog -> cancelPreparedArchive())
                .show();
    }

    private void extractArchiveToTree(Uri archiveUri, Uri destinationTreeUri) {
        if (archiveUri == null || preparedArchive == null) {
            finishWithError(new IllegalStateException("解凍するアーカイブが見つかりません。"));
            return;
        }
        setUiState(true, false);
        setStatus("アーカイブを解凍しています…");
        final char[] password = passwordCopy();
        final File work = preparedArchiveWorkDirectory;
        final File localArchive = preparedArchive;
        executor.execute(() -> {
            try {
                File extraction = new File(work, "extracted");
                ArchiveEngine.extract(localArchive, extraction, password);
                StorageBridge.copyDirectoryToTree(this, extraction, destinationTreeUri);
                mainThread.post(() -> {
                    clearPreparedArchive();
                    finishSuccessfully("解凍が完了しました。");
                });
            } catch (Exception error) {
                mainThread.post(() -> {
                    clearPreparedArchive();
                    finishWithError(error);
                });
            } finally {
                wipe(password);
            }
        });
    }

    private void onPickerCancelled(int requestCode) {
        if (requestCode == REQUEST_CREATE_ARCHIVE) {
            clearGeneratedArchive();
        }
        if (requestCode == REQUEST_DESTINATION_DIRECTORY) {
            clearPreparedArchive();
        }
        if (requestCode == REQUEST_SOURCE_DIRECTORY
                || requestCode == REQUEST_ARCHIVE
                || requestCode == REQUEST_DESTINATION_DIRECTORY
                || requestCode == REQUEST_CREATE_ARCHIVE) {
            pendingArchiveUri = null;
            wipePendingPassword();
            setUiState(false, true);
            setStatus("操作をキャンセルしました。");
        }
    }

    private void finishSuccessfully(String message) {
        pendingArchiveUri = null;
        incomingArchiveUri = null;
        wipePendingPassword();
        setUiState(false, true);
        setStatus(message);
    }

    private void finishWithError(Exception error) {
        pendingArchiveUri = null;
        wipePendingPassword();
        setUiState(false, true);
        String details = error.getMessage();
        if (details == null || details.trim().isEmpty()) {
            details = "ファイルを処理できませんでした。";
        }
        setStatus("処理できませんでした: " + details);
    }

    private void clearGeneratedArchive() {
        StorageBridge.deleteRecursively(generatedWorkDirectory);
        generatedArchive = null;
        generatedWorkDirectory = null;
    }

    private void clearPreparedArchive() {
        StorageBridge.deleteRecursively(preparedArchiveWorkDirectory);
        preparedArchive = null;
        preparedArchiveWorkDirectory = null;
    }

    private void cancelPreparedArchive() {
        clearPreparedArchive();
        pendingArchiveUri = null;
        wipePendingPassword();
        setUiState(false, true);
        setStatus("解凍をキャンセルしました。");
    }

    private void capturePassword() {
        wipePendingPassword();
        pendingPassword = passwordInput.getText().toString().toCharArray();
    }

    private char[] passwordCopy() {
        return pendingPassword == null ? null : Arrays.copyOf(pendingPassword, pendingPassword.length);
    }

    private void wipePendingPassword() {
        wipe(pendingPassword);
        pendingPassword = null;
    }

    private static void wipe(char[] value) {
        if (value != null) {
            Arrays.fill(value, '\u0000');
        }
    }

    private void setUiState(boolean working, boolean allowActions) {
        this.working = working;
        progress.setVisibility(working ? View.VISIBLE : View.GONE);
        compressButton.setEnabled(allowActions);
        extractButton.setEnabled(allowActions);
        formatSpinner.setEnabled(allowActions);
        passwordInput.setEnabled(allowActions);
        showPassword.setEnabled(allowActions);
    }

    private void setStatus(String message) {
        statusText.setText(message);
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        if (isFinishing()) {
            clearGeneratedArchive();
            clearPreparedArchive();
        }
        wipePendingPassword();
        super.onDestroy();
    }

    private void handleIncomingArchive(Intent intent) {
        if (intent == null || working) {
            return;
        }
        Uri archiveUri = null;
        if (Intent.ACTION_VIEW.equals(intent.getAction())) {
            archiveUri = intent.getData();
        } else if (Intent.ACTION_SEND.equals(intent.getAction())) {
            archiveUri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        }
        if (archiveUri == null) {
            return;
        }
        StorageBridge.takePersistablePermission(this, archiveUri, intent.getFlags());
        incomingArchiveUri = archiveUri;
        setStatus(getString(R.string.archive_received));
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        String[] units = {"KB", "MB", "GB", "TB"};
        double value = bytes;
        int unit = -1;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format(Locale.getDefault(), "%.1f %s", value, units[unit]);
    }
}
