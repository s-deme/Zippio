package dev.zippio;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.method.HideReturnsTransformationMethod;
import android.text.method.PasswordTransformationMethod;
import android.view.View;
import android.view.WindowInsets;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;

import java.io.File;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Entry point and user-facing workflow for private archive creation and extraction. */
public final class MainActivity extends Activity {
    private static final int REQUEST_SOURCE_DIRECTORY = 101;
    private static final int REQUEST_CREATE_ARCHIVE = 102;
    private static final int REQUEST_ARCHIVE = 103;
    private static final int REQUEST_DESTINATION_DIRECTORY = 104;
    private static final int REQUEST_SOURCE_FILES = 105;
    private static final int REQUEST_VERIFY_ARCHIVE = 106;

    private static final String PREFS_NAME = "zippio_options";
    private static final String PREF_FORMAT = "format";
    private static final String PREF_LEVEL = "compression_level";
    private static final String PREF_ROOT = "include_root";
    private static final String PREF_HIDDEN = "include_hidden";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainThread = new Handler(Looper.getMainLooper());

    private Spinner formatSpinner;
    private FrameLayout formatField;
    private Spinner compressionLevelSpinner;
    private FrameLayout compressionLevelField;
    private TextView compressionLevelLabel;
    private EditText passwordInput;
    private TextView passwordStrength;
    private CheckBox showPassword;
    private CheckBox includeRootFolder;
    private CheckBox includeHiddenFiles;
    private Button compressFolderButton;
    private Button compressFilesButton;
    private Button extractButton;
    private Button verifyButton;
    private Button cancelButton;
    private Button resetButton;
    private LinearLayout progressPanel;
    private ProgressBar progress;
    private TextView progressDetail;
    private TextView statusText;

    private SharedPreferences preferences;
    private Future<?> activeTask;
    private volatile boolean cancellationRequested;
    private boolean working;

    private ArchiveEngine.ArchiveFormat pendingFormat;
    private ArchiveEngine.CompressionProfile pendingCompressionProfile;
    private boolean pendingIncludeRoot;
    private boolean pendingIncludeHidden;
    private char[] pendingPassword;
    private Uri pendingArchiveUri;
    private Uri incomingArchiveUri;
    private File generatedWorkDirectory;
    private File generatedArchive;
    private String generatedArchiveMimeType;
    private File preparedArchiveWorkDirectory;
    private File preparedArchive;
    private Uri lastSavedArchiveUri;
    private String lastSavedArchiveMimeType;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        StorageBridge.clearStaleWork(this);
        setContentView(R.layout.activity_main);
        applySystemBarInsets(findViewById(R.id.root_scroll));

        formatSpinner = findViewById(R.id.format_spinner);
        formatField = findViewById(R.id.format_field);
        compressionLevelSpinner = findViewById(R.id.compression_level_spinner);
        compressionLevelField = findViewById(R.id.compression_level_field);
        compressionLevelLabel = findViewById(R.id.compression_level_label);
        passwordInput = findViewById(R.id.password_input);
        passwordStrength = findViewById(R.id.password_strength);
        showPassword = findViewById(R.id.show_password);
        includeRootFolder = findViewById(R.id.include_root_folder);
        includeHiddenFiles = findViewById(R.id.include_hidden_files);
        compressFolderButton = findViewById(R.id.compress_folder_button);
        compressFilesButton = findViewById(R.id.compress_files_button);
        extractButton = findViewById(R.id.extract_button);
        verifyButton = findViewById(R.id.verify_button);
        cancelButton = findViewById(R.id.cancel_button);
        resetButton = findViewById(R.id.reset_button);
        progressPanel = findViewById(R.id.progress_panel);
        progress = findViewById(R.id.progress);
        progressDetail = findViewById(R.id.progress_detail);
        statusText = findViewById(R.id.status_text);
        preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        formatSpinner.setAdapter(ArrayAdapter.createFromResource(
                this, R.array.archive_formats, R.layout.item_spinner_selected));
        ((ArrayAdapter<?>) formatSpinner.getAdapter()).setDropDownViewResource(
                R.layout.item_spinner_dropdown);
        compressionLevelSpinner.setAdapter(ArrayAdapter.createFromResource(
                this, R.array.compression_levels, R.layout.item_spinner_selected));
        ((ArrayAdapter<?>) compressionLevelSpinner.getAdapter()).setDropDownViewResource(
                R.layout.item_spinner_dropdown);

        restoreOptions();
        bindUi();
        updateFormatDependentUi();
        updatePasswordHelper();
        setUiState(false, true);
        handleIncomingArchive(getIntent());
    }

    /** Keeps all controls clear of status, navigation, gesture and display-cutout areas. */
    private static void applySystemBarInsets(View root) {
        final int initialLeft = root.getPaddingLeft();
        final int initialTop = root.getPaddingTop();
        final int initialRight = root.getPaddingRight();
        final int initialBottom = root.getPaddingBottom();

        root.setOnApplyWindowInsetsListener((view, windowInsets) -> {
            int left;
            int top;
            int right;
            int bottom;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Insets safeInsets = windowInsets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                left = safeInsets.left;
                top = safeInsets.top;
                right = safeInsets.right;
                bottom = safeInsets.bottom;
            } else {
                left = windowInsets.getSystemWindowInsetLeft();
                top = windowInsets.getSystemWindowInsetTop();
                right = windowInsets.getSystemWindowInsetRight();
                bottom = windowInsets.getSystemWindowInsetBottom();
            }

            view.setPadding(
                    initialLeft + left,
                    initialTop + top,
                    initialRight + right,
                    initialBottom + bottom);
            return windowInsets;
        });
        root.requestApplyInsets();
    }

    private void bindUi() {
        compressFolderButton.setOnClickListener(view -> beginFolderCompression());
        compressFilesButton.setOnClickListener(view -> beginFileCompression());
        extractButton.setOnClickListener(view -> beginExtraction());
        verifyButton.setOnClickListener(view -> beginVerification());
        cancelButton.setOnClickListener(view -> confirmCancellation());
        resetButton.setOnClickListener(view -> resetOptions());
        showPassword.setOnCheckedChangeListener((button, checked) -> {
            passwordInput.setTransformationMethod(checked
                    ? HideReturnsTransformationMethod.getInstance()
                    : PasswordTransformationMethod.getInstance());
            passwordInput.setSelection(passwordInput.length());
        });
        formatSpinner.setOnItemSelectedListener(new SimpleItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                preferences.edit().putInt(PREF_FORMAT, position).apply();
                updateFormatDependentUi();
            }
        });
        compressionLevelSpinner.setOnItemSelectedListener(new SimpleItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                preferences.edit().putInt(PREF_LEVEL, position).apply();
            }
        });
        includeRootFolder.setOnCheckedChangeListener((button, checked) ->
                preferences.edit().putBoolean(PREF_ROOT, checked).apply());
        includeHiddenFiles.setOnCheckedChangeListener((button, checked) ->
                preferences.edit().putBoolean(PREF_HIDDEN, checked).apply());
        passwordInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence value, int start, int count, int after) {
                // No-op.
            }

            @Override
            public void onTextChanged(CharSequence value, int start, int before, int count) {
                updatePasswordHelper();
            }

            @Override
            public void afterTextChanged(Editable value) {
                // No-op.
            }
        });
    }

    private void restoreOptions() {
        formatSpinner.setSelection(preferences.getInt(PREF_FORMAT, 0));
        compressionLevelSpinner.setSelection(preferences.getInt(PREF_LEVEL, 0));
        includeRootFolder.setChecked(preferences.getBoolean(PREF_ROOT, true));
        includeHiddenFiles.setChecked(preferences.getBoolean(PREF_HIDDEN, false));
    }

    private void beginFolderCompression() {
        beginCompressionSelection(REQUEST_SOURCE_DIRECTORY, false);
    }

    private void beginFileCompression() {
        beginCompressionSelection(REQUEST_SOURCE_FILES, true);
    }

    private void beginCompressionSelection(int requestCode, boolean filesOnly) {
        incomingArchiveUri = null;
        clearPreparedArchive();
        captureCreateOptions();
        setUiState(false, false);
        Intent intent;
        if (filesOnly) {
            intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("*/*")
                    .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        } else {
            intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    .addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                    .addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        }
        startActivityForResult(intent, requestCode);
    }

    private void beginExtraction() {
        capturePassword();
        if (incomingArchiveUri != null) {
            prepareArchivePreview(incomingArchiveUri);
            return;
        }
        setUiState(false, false);
        startActivityForResult(archivePickerIntent(), REQUEST_ARCHIVE);
    }

    private void beginVerification() {
        capturePassword();
        if (incomingArchiveUri != null) {
            verifyArchive(incomingArchiveUri);
            return;
        }
        setUiState(false, false);
        startActivityForResult(archivePickerIntent(), REQUEST_VERIFY_ARCHIVE);
    }

    private Intent archivePickerIntent() {
        return new Intent(Intent.ACTION_OPEN_DOCUMENT)
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
        if (resultCode != RESULT_OK || data == null) {
            onPickerCancelled(requestCode);
            return;
        }

        if (requestCode == REQUEST_SOURCE_FILES) {
            List<Uri> selectedUris = selectedUris(data);
            if (selectedUris.isEmpty()) {
                onPickerCancelled(requestCode);
                return;
            }
            for (Uri uri : selectedUris) {
                StorageBridge.takePersistablePermission(this, uri, data.getFlags());
            }
            createArchiveFromUris(selectedUris);
            return;
        }

        Uri selectedUri = data.getData();
        if (selectedUri == null) {
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
            extractArchiveToTree(selectedUri);
        } else if (requestCode == REQUEST_VERIFY_ARCHIVE) {
            verifyArchive(selectedUri);
        }
    }

    private List<Uri> selectedUris(Intent data) {
        List<Uri> result = new ArrayList<>();
        if (data.getData() != null) {
            result.add(data.getData());
        }
        ClipData clipData = data.getClipData();
        if (clipData != null) {
            for (int index = 0; index < clipData.getItemCount(); index++) {
                Uri uri = clipData.getItemAt(index).getUri();
                if (uri != null && !result.contains(uri)) {
                    result.add(uri);
                }
            }
        }
        return result;
    }

    private void createArchiveFromTree(Uri sourceTreeUri) {
        final ArchiveOptions options = pendingCreateOptions();
        startWork("フォルダを読み込んでいます…", 8);
        activeTask = executor.submit(() -> {
            File work = null;
            try {
                work = StorageBridge.newWorkDirectory(this, "compress");
                String sourceName = StorageBridge.safeFileStem(
                        StorageBridge.treeDisplayName(this, sourceTreeUri));
                File source = new File(work, sourceName);
                StorageBridge.copyTreeToDirectory(this, sourceTreeUri, source, options.includeHidden);
                checkCancelled();
                postProgress("アーカイブを作成しています…", 52);
                File output = new File(work, sourceName + "." + options.format.extension);
                ArchiveEngine.create(source, output, options.format, options.password,
                        options.compressionProfile, options.includeRootFolder);
                postCreatedArchive(work, output, options.format);
                work = null;
            } catch (Exception error) {
                StorageBridge.deleteRecursively(work);
                postFailure(error);
            } finally {
                options.wipe();
            }
        });
    }

    private void createArchiveFromUris(List<Uri> sourceUris) {
        final ArchiveOptions options = pendingCreateOptions();
        startWork("選択したファイルを読み込んでいます…", 8);
        activeTask = executor.submit(() -> {
            File work = null;
            try {
                work = StorageBridge.newWorkDirectory(this, "compress-files");
                File source = new File(work, "選択したファイル");
                StorageBridge.copyUrisToDirectory(this, sourceUris, source);
                checkCancelled();
                postProgress("アーカイブを作成しています…", 52);
                File output = new File(work, "Zippio-選択したファイル." + options.format.extension);
                ArchiveEngine.create(source, output, options.format, options.password,
                        options.compressionProfile, options.includeRootFolder);
                postCreatedArchive(work, output, options.format);
                work = null;
            } catch (Exception error) {
                StorageBridge.deleteRecursively(work);
                postFailure(error);
            } finally {
                options.wipe();
            }
        });
    }

    private void postCreatedArchive(File work, File archive, ArchiveEngine.ArchiveFormat format) {
        mainThread.post(() -> {
            if (cancellationRequested) {
                StorageBridge.deleteRecursively(work);
                finishCancelled();
                return;
            }
            generatedWorkDirectory = work;
            generatedArchive = archive;
            generatedArchiveMimeType = format.mimeType;
            clearPassword();
            working = false;
            activeTask = null;
            setUiState(false, false);
            setStatus("圧縮が完了しました。保存先とファイル名を選んでください。");
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType(format.mimeType)
                    .putExtra(Intent.EXTRA_TITLE, archive.getName())
                    .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    .addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(intent, REQUEST_CREATE_ARCHIVE);
        });
    }

    private void saveGeneratedArchive(Uri destinationUri) {
        if (generatedArchive == null) {
            finishWithError(new IllegalStateException("保存するアーカイブが見つかりません。"));
            return;
        }
        final File archive = generatedArchive;
        startWork("アーカイブを保存しています…", 85);
        activeTask = executor.submit(() -> {
            try {
                StorageBridge.copyFileToUri(this, archive, destinationUri);
                checkCancelled();
                mainThread.post(() -> {
                    if (cancellationRequested) {
                        clearGeneratedArchive();
                        finishCancelled();
                        return;
                    }
                    lastSavedArchiveUri = destinationUri;
                    lastSavedArchiveMimeType = generatedArchiveMimeType;
                    clearGeneratedArchive();
                    finishSuccessfully("圧縮ファイルを保存しました。");
                    showArchiveSavedDialog();
                });
            } catch (Exception error) {
                mainThread.post(() -> {
                    clearGeneratedArchive();
                    postFailureOnMain(error);
                });
            }
        });
    }

    private void prepareArchivePreview(Uri archiveUri) {
        clearPreparedArchive();
        final char[] password = passwordCopy();
        startWork("アーカイブの内容と安全性を確認しています…", 18);
        activeTask = executor.submit(() -> {
            File work = null;
            try {
                work = StorageBridge.newWorkDirectory(this, "preview");
                String archiveName = StorageBridge.safeFileName(
                        StorageBridge.displayName(this, archiveUri, "アーカイブ"), "アーカイブ");
                File localArchive = new File(work, archiveName);
                StorageBridge.copyUriToFile(this, archiveUri, localArchive);
                checkCancelled();
                postProgress("アーカイブの内容を確認しています…", 64);
                ArchiveEngine.ArchiveInfo info = ArchiveEngine.inspect(localArchive, password);
                File completedWork = work;
                mainThread.post(() -> {
                    if (cancellationRequested) {
                        StorageBridge.deleteRecursively(completedWork);
                        finishCancelled();
                        return;
                    }
                    preparedArchiveWorkDirectory = completedWork;
                    preparedArchive = localArchive;
                    pendingArchiveUri = archiveUri;
                    working = false;
                    activeTask = null;
                    setUiState(false, false);
                    setStatus("内容を確認しました。解凍先を選んでください。");
                    showArchivePreview(archiveUri, localArchive, info);
                });
                work = null;
            } catch (Exception error) {
                StorageBridge.deleteRecursively(work);
                postFailure(error);
            } finally {
                wipe(password);
            }
        });
    }

    private void showArchivePreview(Uri archiveUri, File archive, ArchiveEngine.ArchiveInfo info) {
        long compressedBytes = archive.length();
        String message = getString(
                R.string.archive_preview_template,
                StorageBridge.displayName(this, archiveUri, archive.getName()),
                info.format,
                info.entryCount,
                info.fileCount,
                formatBytes(info.uncompressedBytes),
                formatBytes(compressedBytes),
                formatRatio(info.uncompressedBytes, compressedBytes)
        );
        if (!info.previewEntries.isEmpty()) {
            message += "\n\n" + getString(R.string.archive_preview_entries_title) + "\n"
                    + joinPreviewEntries(info.previewEntries);
            int remaining = info.entryCount - info.previewEntries.size();
            if (remaining > 0) {
                message += "\n" + getString(R.string.archive_preview_more_entries, remaining);
            }
        }
        if (info.encrypted) {
            message += "\n\n" + getString(R.string.archive_preview_encrypted);
        }
        if (info.needsCapacityWarning(compressedBytes)) {
            message += "\n\n" + getString(R.string.archive_preview_capacity_warning);
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.archive_preview_title)
                .setMessage(message)
                .setNegativeButton(R.string.cancel, (dialog, which) -> cancelPreparedArchive())
                .setPositiveButton(R.string.choose_destination,
                        (dialog, which) -> chooseExtractionDestination())
                .setOnCancelListener(dialog -> cancelPreparedArchive())
                .show();
    }

    private String joinPreviewEntries(List<String> entries) {
        StringBuilder result = new StringBuilder();
        for (String entry : entries) {
            if (result.length() > 0) {
                result.append('\n');
            }
            result.append("• ").append(entry);
        }
        return result.toString();
    }

    private void chooseExtractionDestination() {
        setStatus("解凍先フォルダを選んでください。");
        setUiState(false, false);
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                .addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                .addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_DESTINATION_DIRECTORY);
    }

    private void extractArchiveToTree(Uri destinationTreeUri) {
        if (preparedArchive == null || preparedArchiveWorkDirectory == null) {
            finishWithError(new IllegalStateException("解凍するアーカイブが見つかりません。"));
            return;
        }
        final char[] password = passwordCopy();
        final File work = preparedArchiveWorkDirectory;
        final File localArchive = preparedArchive;
        startWork("アーカイブを解凍しています…", 32);
        activeTask = executor.submit(() -> {
            try {
                File extraction = new File(work, "extracted");
                ArchiveEngine.extract(localArchive, extraction, password);
                checkCancelled();
                postProgress("選んだフォルダへ保存しています…", 78);
                StorageBridge.copyDirectoryToTree(this, extraction, destinationTreeUri);
                checkCancelled();
                mainThread.post(() -> {
                    if (cancellationRequested) {
                        clearPreparedArchive();
                        finishCancelled();
                        return;
                    }
                    clearPreparedArchive();
                    finishSuccessfully("解凍が完了しました。");
                });
            } catch (Exception error) {
                mainThread.post(() -> {
                    clearPreparedArchive();
                    postFailureOnMain(error);
                });
            } finally {
                wipe(password);
            }
        });
    }

    private void verifyArchive(Uri archiveUri) {
        final char[] password = passwordCopy();
        startWork("アーカイブを検証しています…", 12);
        activeTask = executor.submit(() -> {
            File work = null;
            try {
                work = StorageBridge.newWorkDirectory(this, "verify");
                String archiveName = StorageBridge.safeFileName(
                        StorageBridge.displayName(this, archiveUri, "アーカイブ"), "アーカイブ");
                File localArchive = new File(work, archiveName);
                StorageBridge.copyUriToFile(this, archiveUri, localArchive);
                checkCancelled();
                postProgress("アーカイブ構造を確認しています…", 38);
                ArchiveEngine.ArchiveInfo info = ArchiveEngine.inspect(localArchive, password);
                checkCancelled();
                postProgress("すべての項目を読み込んでいます…", 64);
                ArchiveEngine.extract(localArchive, new File(work, "verification"), password);
                checkCancelled();
                StorageBridge.deleteRecursively(work);
                mainThread.post(() -> {
                    if (cancellationRequested) {
                        finishCancelled();
                        return;
                    }
                    finishSuccessfully("アーカイブを検証しました。");
                    new AlertDialog.Builder(this)
                            .setTitle(R.string.verification_complete_title)
                            .setMessage(getString(
                                    R.string.verification_complete_message,
                                    StorageBridge.displayName(this, archiveUri, "アーカイブ"),
                                    info.entryCount,
                                    info.fileCount
                            ))
                            .setPositiveButton(R.string.close, null)
                            .show();
                });
                work = null;
            } catch (Exception error) {
                StorageBridge.deleteRecursively(work);
                postFailure(error);
            } finally {
                wipe(password);
            }
        });
    }

    private void showArchiveSavedDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.archive_saved_title)
                .setMessage(R.string.archive_saved_message)
                .setNegativeButton(R.string.close, null)
                .setPositiveButton(R.string.share, (dialog, which) -> shareLastArchive())
                .show();
    }

    private void shareLastArchive() {
        if (lastSavedArchiveUri == null) {
            setStatus("共有するアーカイブが見つかりません。");
            return;
        }
        Intent shareIntent = new Intent(Intent.ACTION_SEND)
                .setType(lastSavedArchiveMimeType == null
                        ? "application/octet-stream" : lastSavedArchiveMimeType)
                .putExtra(Intent.EXTRA_STREAM, lastSavedArchiveUri);
        shareIntent.setClipData(ClipData.newRawUri("アーカイブ", lastSavedArchiveUri));
        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(shareIntent, getString(R.string.share)));
    }

    private void onPickerCancelled(int requestCode) {
        if (requestCode == REQUEST_CREATE_ARCHIVE) {
            clearGeneratedArchive();
        } else if (requestCode == REQUEST_DESTINATION_DIRECTORY) {
            clearPreparedArchive();
        }
        clearPassword();
        setUiState(false, true);
        setStatus("操作をキャンセルしました。");
    }

    private void confirmCancellation() {
        if (!working || cancellationRequested) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.cancel_confirm_title)
                .setMessage(R.string.cancel_confirm_message)
                .setNegativeButton(R.string.continue_action, null)
                .setPositiveButton(R.string.stop, (dialog, which) -> requestCancellation())
                .show();
    }

    private void requestCancellation() {
        if (!working || cancellationRequested) {
            return;
        }
        cancellationRequested = true;
        cancelButton.setEnabled(false);
        setStatus(getString(R.string.operation_cancel_requested));
        progressDetail.setText(getString(R.string.operation_cancel_requested));
        if (activeTask != null) {
            activeTask.cancel(true);
        }
    }

    private void finishSuccessfully(String message) {
        pendingArchiveUri = null;
        incomingArchiveUri = null;
        clearPassword();
        finishWork();
        setStatus(message);
    }

    private void finishCancelled() {
        clearGeneratedArchive();
        clearPreparedArchive();
        pendingArchiveUri = null;
        clearPassword();
        finishWork();
        setStatus(getString(R.string.operation_cancelled));
    }

    private void finishWithError(Exception error) {
        pendingArchiveUri = null;
        clearPassword();
        finishWork();
        setStatus(getString(R.string.processing_failed,
                localizedErrorMessage(error)));
    }

    private void postFailure(Exception error) {
        mainThread.post(() -> postFailureOnMain(error));
    }

    private void postFailureOnMain(Exception error) {
        if (cancellationRequested || error instanceof InterruptedIOException
                || Thread.currentThread().isInterrupted()) {
            finishCancelled();
            return;
        }
        finishWithError(error);
    }

    private void startWork(String status, int initialProgress) {
        cancellationRequested = false;
        working = true;
        setUiState(true, false);
        setStatus(status);
        progress.setProgress(initialProgress);
        progressDetail.setText(status);
    }

    private void finishWork() {
        cancellationRequested = false;
        working = false;
        activeTask = null;
        setUiState(false, true);
    }

    private void postProgress(String status, int amount) {
        mainThread.post(() -> {
            if (!cancellationRequested && working) {
                setStatus(status);
                progressDetail.setText(status);
                progress.setProgress(amount);
            }
        });
    }

    private void checkCancelled() throws InterruptedIOException {
        if (cancellationRequested || Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("操作を中止しました。");
        }
    }

    private void clearGeneratedArchive() {
        StorageBridge.deleteRecursively(generatedWorkDirectory);
        generatedArchive = null;
        generatedWorkDirectory = null;
        generatedArchiveMimeType = null;
    }

    private void clearPreparedArchive() {
        StorageBridge.deleteRecursively(preparedArchiveWorkDirectory);
        preparedArchive = null;
        preparedArchiveWorkDirectory = null;
    }

    private void cancelPreparedArchive() {
        clearPreparedArchive();
        pendingArchiveUri = null;
        clearPassword();
        setUiState(false, true);
        setStatus("解凍をキャンセルしました。");
    }

    private void captureCreateOptions() {
        wipePendingPassword();
        pendingFormat = ArchiveEngine.ArchiveFormat.fromLabel(
                String.valueOf(formatSpinner.getSelectedItem()));
        pendingCompressionProfile = ArchiveEngine.CompressionProfile.fromLabel(
                String.valueOf(compressionLevelSpinner.getSelectedItem()));
        pendingIncludeRoot = includeRootFolder.isChecked();
        pendingIncludeHidden = includeHiddenFiles.isChecked();
        pendingPassword = passwordInput.getText().toString().toCharArray();
    }

    private ArchiveOptions pendingCreateOptions() {
        return new ArchiveOptions(
                pendingFormat == null ? ArchiveEngine.ArchiveFormat.ZIP : pendingFormat,
                pendingCompressionProfile == null
                        ? ArchiveEngine.CompressionProfile.NORMAL : pendingCompressionProfile,
                pendingIncludeRoot,
                pendingIncludeHidden,
                passwordCopy()
        );
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

    private void clearPassword() {
        wipePendingPassword();
        if (passwordInput != null) {
            passwordInput.setText("");
        }
    }

    private static void wipe(char[] value) {
        if (value != null) {
            Arrays.fill(value, '\u0000');
        }
    }

    private void updateFormatDependentUi() {
        boolean zip = ArchiveEngine.ArchiveFormat.fromLabel(
                String.valueOf(formatSpinner.getSelectedItem())) == ArchiveEngine.ArchiveFormat.ZIP;
        compressionLevelLabel.setText(zip
                ? R.string.compression_level : R.string.compression_level_7z);
        compressionLevelField.setVisibility(zip ? View.VISIBLE : View.GONE);
        compressionLevelSpinner.setEnabled(zip && formatSpinner.isEnabled() && !working);
        compressionLevelField.setEnabled(compressionLevelSpinner.isEnabled());
    }

    private void updatePasswordHelper() {
        int length = passwordInput.getText().length();
        if (length == 0) {
            passwordStrength.setText(R.string.password_note);
            passwordStrength.setTextColor(getColor(R.color.color_on_surface_variant));
        } else if (length < 12) {
            passwordStrength.setText(R.string.password_strength_short);
            passwordStrength.setTextColor(getColor(R.color.color_warning));
        } else {
            passwordStrength.setText(R.string.password_strength_good);
            passwordStrength.setTextColor(getColor(R.color.color_success));
        }
    }

    private void resetOptions() {
        if (working) {
            return;
        }
        preferences.edit()
                .remove(PREF_FORMAT)
                .remove(PREF_LEVEL)
                .remove(PREF_ROOT)
                .remove(PREF_HIDDEN)
                .apply();
        formatSpinner.setSelection(0);
        compressionLevelSpinner.setSelection(0);
        includeRootFolder.setChecked(true);
        includeHiddenFiles.setChecked(false);
        showPassword.setChecked(false);
        passwordInput.setText("");
        updateFormatDependentUi();
        setStatus("設定を初期値に戻しました。");
    }

    private void setUiState(boolean showProgress, boolean allowActions) {
        progressPanel.setVisibility(showProgress ? View.VISIBLE : View.GONE);
        compressFolderButton.setEnabled(allowActions);
        compressFilesButton.setEnabled(allowActions);
        extractButton.setEnabled(allowActions);
        verifyButton.setEnabled(allowActions);
        resetButton.setEnabled(allowActions);
        formatSpinner.setEnabled(allowActions);
        formatField.setEnabled(allowActions);
        includeRootFolder.setEnabled(allowActions);
        includeHiddenFiles.setEnabled(allowActions);
        passwordInput.setEnabled(allowActions);
        showPassword.setEnabled(allowActions);
        cancelButton.setEnabled(showProgress && !cancellationRequested);
        updateFormatDependentUi();
    }

    private void setStatus(String message) {
        statusText.setText(message);
    }

    /** Avoids exposing English library errors as the primary message in a Japanese UI. */
    private String localizedErrorMessage(Exception error) {
        String details = error.getMessage();
        if (details != null && containsJapanese(details)) {
            return details;
        }

        StringBuilder technicalDetails = new StringBuilder();
        Throwable current = error;
        while (current != null && technicalDetails.length() < 1_000) {
            String message = current.getMessage();
            if (message != null) {
                technicalDetails.append(' ').append(message);
            }
            current = current.getCause();
        }
        String lower = technicalDetails.toString().toLowerCase(Locale.ROOT);
        if (lower.contains("password") || lower.contains("encrypted")) {
            return getString(R.string.error_password);
        }
        if (lower.contains("crc") || lower.contains("corrupt")
                || lower.contains("invalid archive") || lower.contains("malformed")) {
            return getString(R.string.error_damaged_archive);
        }
        if (lower.contains("unsupported") || lower.contains("unknown compression")) {
            return getString(R.string.error_unsupported_archive);
        }
        if (lower.contains("no space") || lower.contains("disk full")
                || lower.contains("not enough space")) {
            return getString(R.string.error_not_enough_space);
        }
        if (lower.contains("permission") || lower.contains("access denied")
                || lower.contains("security exception")) {
            return getString(R.string.error_access);
        }
        if (lower.contains("not found") || lower.contains("does not exist")) {
            return getString(R.string.error_missing_file);
        }
        return getString(R.string.error_generic);
    }

    private boolean containsJapanese(String message) {
        return message.matches(".*[ぁ-んァ-ヶ一-龠々ー].*");
    }

    @Override
    public void onBackPressed() {
        if (working && !cancellationRequested) {
            confirmCancellation();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (activeTask != null) {
            activeTask.cancel(true);
        }
        executor.shutdownNow();
        if (isFinishing()) {
            clearGeneratedArchive();
            clearPreparedArchive();
        }
        wipePendingPassword();
        super.onDestroy();
    }

    @SuppressWarnings("deprecation")
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

    private String formatRatio(long expanded, long compressed) {
        if (compressed <= 0) {
            return "—";
        }
        return String.format(Locale.getDefault(), "%.1f", (double) expanded / compressed);
    }

    private abstract static class SimpleItemSelectedListener
            implements AdapterView.OnItemSelectedListener {
        @Override
        public void onNothingSelected(AdapterView<?> parent) {
            // No-op.
        }
    }

    private static final class ArchiveOptions {
        final ArchiveEngine.ArchiveFormat format;
        final ArchiveEngine.CompressionProfile compressionProfile;
        final boolean includeRootFolder;
        final boolean includeHidden;
        final char[] password;

        ArchiveOptions(
                ArchiveEngine.ArchiveFormat format,
                ArchiveEngine.CompressionProfile compressionProfile,
                boolean includeRootFolder,
                boolean includeHidden,
                char[] password
        ) {
            this.format = format;
            this.compressionProfile = compressionProfile;
            this.includeRootFolder = includeRootFolder;
            this.includeHidden = includeHidden;
            this.password = password;
        }

        void wipe() {
            MainActivity.wipe(password);
        }
    }
}
