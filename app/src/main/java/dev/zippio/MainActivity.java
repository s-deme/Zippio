package dev.zippio;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
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
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowInsets;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
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
    private static final int REQUEST_NOTIFICATION_PERMISSION = 107;

    private static final String NOTIFICATION_CHANNEL_ID = "archive_progress";
    private static final int ACTIVE_OPERATION_NOTIFICATION_ID = 2001;
    private static final int OUTCOME_NOTIFICATION_ID = 2002;

    private static final String PREFS_NAME = "zippio_options";
    private static final String PREF_FORMAT = "format";
    private static final String PREF_LEVEL = "compression_level";
    private static final String PREF_ROOT = "include_root";
    private static final String PREF_HIDDEN = "include_hidden";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainThread = new Handler(Looper.getMainLooper());

    private ScrollView rootScroll;
    private Button extractModeButton;
    private Button compressModeButton;
    private Spinner formatSpinner;
    private FrameLayout formatField;
    private Spinner compressionLevelSpinner;
    private FrameLayout compressionLevelField;
    private TextView compressionLevelLabel;
    private TextView formatHelpText;
    private EditText passwordInput;
    private TextView passwordStrength;
    private ImageButton showPasswordButton;
    private EditText extractPasswordInput;
    private ImageButton extractShowPasswordButton;
    private CheckBox includeRootFolder;
    private CheckBox includeHiddenFiles;
    private Button compressFolderButton;
    private Button compressFilesButton;
    private Button compressExecuteButton;
    private Button compressChangeButton;
    private Button compressClearButton;
    private Button zipFormatButton;
    private Button sevenZFormatButton;
    private Button advancedToggleButton;
    private Button shareButton;
    private Button compressAgainButton;
    private Button extractButton;
    private Button extractPasswordToggle;
    private Button extractAgainButton;
    private Button cancelButton;
    private Button resetButton;
    private Button errorRecoveryButton;
    private Button errorDismissButton;
    private Button enableNotificationsButton;
    private LinearLayout progressPanel;
    private LinearLayout extractContent;
    private LinearLayout compressContent;
    private LinearLayout extractWorkflowPanel;
    private LinearLayout extractResultPanel;
    private LinearLayout compressWorkflowPanel;
    private LinearLayout compressResultPanel;
    private LinearLayout compressSourceActions;
    private LinearLayout compressSourcePanel;
    private LinearLayout compressSettingsPanel;
    private LinearLayout advancedSettingsPanel;
    private LinearLayout extractPasswordPanel;
    private LinearLayout errorPanel;
    private LinearLayout notificationNotice;
    private ProgressBar progress;
    private TextView progressDetail;
    private TextView statusText;
    private TextView errorText;
    private TextView compressSourceSummary;

    private SharedPreferences preferences;
    private Future<?> activeTask;
    private volatile boolean cancellationRequested;
    private boolean working;
    private boolean notificationPermissionRequestInFlight;

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
    private Uri selectedSourceTreeUri;
    private final List<Uri> selectedSourceFileUris = new ArrayList<>();
    private SourceKind selectedSourceKind = SourceKind.NONE;
    private Uri pendingDestinationTreeUri;
    private OperationContext operationContext = OperationContext.NONE;
    private Runnable recoveryAction;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        StorageBridge.clearStaleWork(this);
        setContentView(R.layout.activity_main);
        rootScroll = findViewById(R.id.root_scroll);
        applySystemBarInsets(rootScroll);
        createNotificationChannel();

        extractModeButton = findViewById(R.id.extract_mode_button);
        compressModeButton = findViewById(R.id.compress_mode_button);
        formatSpinner = findViewById(R.id.format_spinner);
        formatField = findViewById(R.id.format_field);
        compressionLevelSpinner = findViewById(R.id.compression_level_spinner);
        compressionLevelField = findViewById(R.id.compression_level_field);
        compressionLevelLabel = findViewById(R.id.compression_level_label);
        formatHelpText = findViewById(R.id.format_help_text);
        passwordInput = findViewById(R.id.password_input);
        passwordStrength = findViewById(R.id.password_strength);
        showPasswordButton = findViewById(R.id.show_password_button);
        extractPasswordInput = findViewById(R.id.extract_password_input);
        extractShowPasswordButton = findViewById(R.id.extract_show_password_button);
        includeRootFolder = findViewById(R.id.include_root_folder);
        includeHiddenFiles = findViewById(R.id.include_hidden_files);
        compressFolderButton = findViewById(R.id.compress_folder_button);
        compressFilesButton = findViewById(R.id.compress_files_button);
        compressExecuteButton = findViewById(R.id.compress_execute_button);
        compressChangeButton = findViewById(R.id.compress_change_button);
        compressClearButton = findViewById(R.id.compress_clear_button);
        zipFormatButton = findViewById(R.id.zip_format_button);
        sevenZFormatButton = findViewById(R.id.seven_z_format_button);
        advancedToggleButton = findViewById(R.id.advanced_toggle_button);
        shareButton = findViewById(R.id.share_button);
        compressAgainButton = findViewById(R.id.compress_again_button);
        extractButton = findViewById(R.id.extract_button);
        extractPasswordToggle = findViewById(R.id.extract_password_toggle);
        extractAgainButton = findViewById(R.id.extract_again_button);
        cancelButton = findViewById(R.id.cancel_button);
        resetButton = findViewById(R.id.reset_button);
        errorRecoveryButton = findViewById(R.id.error_recovery_button);
        errorDismissButton = findViewById(R.id.error_dismiss_button);
        enableNotificationsButton = findViewById(R.id.enable_notifications_button);
        progressPanel = findViewById(R.id.progress_panel);
        extractContent = findViewById(R.id.extract_content);
        compressContent = findViewById(R.id.compress_content);
        extractWorkflowPanel = findViewById(R.id.extract_workflow_panel);
        extractResultPanel = findViewById(R.id.extract_result_panel);
        compressWorkflowPanel = findViewById(R.id.compress_workflow_panel);
        compressResultPanel = findViewById(R.id.compress_result_panel);
        compressSourceActions = findViewById(R.id.compress_source_actions);
        compressSourcePanel = findViewById(R.id.compress_source_panel);
        compressSettingsPanel = findViewById(R.id.compress_settings_panel);
        advancedSettingsPanel = findViewById(R.id.advanced_settings_panel);
        extractPasswordPanel = findViewById(R.id.extract_password_panel);
        errorPanel = findViewById(R.id.error_panel);
        notificationNotice = findViewById(R.id.notification_notice);
        progress = findViewById(R.id.progress);
        progressDetail = findViewById(R.id.progress_detail);
        statusText = findViewById(R.id.status_text);
        errorText = findViewById(R.id.error_text);
        compressSourceSummary = findViewById(R.id.compress_source_summary);
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
        selectMode(Mode.EXTRACT);
        updateFormatDependentUi();
        updatePasswordHelper();
        setUiState(false, true);
        updateCompressionSelectionUi();
        updateNotificationNotice();
        clearStatus();
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

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
        );
        channel.setDescription(getString(R.string.notification_channel_description));
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private void postOperationNotification(String status, int progressAmount) {
        if (!canPostNotifications()) {
            return;
        }
        Notification notification = new Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_zippio)
                .setContentTitle(getString(R.string.notification_processing_title))
                .setContentText(status)
                .setStyle(new Notification.BigTextStyle().bigText(status))
                .setContentIntent(notificationContentIntent())
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setProgress(100, progressAmount, false)
                .build();
        getSystemService(NotificationManager.class).notify(
                ACTIVE_OPERATION_NOTIFICATION_ID,
                notification
        );
    }

    private void postOutcomeNotification(int titleResource, String message) {
        if (!canPostNotifications()) {
            return;
        }
        Notification notification = new Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_zippio)
                .setContentTitle(getString(titleResource))
                .setContentText(message)
                .setStyle(new Notification.BigTextStyle().bigText(message))
                .setContentIntent(notificationContentIntent())
                .setCategory(Notification.CATEGORY_STATUS)
                .setAutoCancel(true)
                .build();
        getSystemService(NotificationManager.class).notify(OUTCOME_NOTIFICATION_ID, notification);
    }

    private PendingIntent notificationContentIntent() {
        Intent intent = new Intent(this, MainActivity.class)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }

    private void cancelOperationNotification() {
        getSystemService(NotificationManager.class).cancel(ACTIVE_OPERATION_NOTIFICATION_ID);
    }

    private boolean canPostNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        return getSystemService(NotificationManager.class).areNotificationsEnabled();
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || notificationPermissionRequestInFlight
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        notificationPermissionRequestInFlight = true;
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                REQUEST_NOTIFICATION_PERMISSION);
    }

    private void bindUi() {
        extractModeButton.setOnClickListener(view -> selectMode(Mode.EXTRACT));
        compressModeButton.setOnClickListener(view -> selectMode(Mode.COMPRESS));
        compressFolderButton.setOnClickListener(view -> beginFolderCompression());
        compressFilesButton.setOnClickListener(view -> beginFileCompression());
        compressExecuteButton.setOnClickListener(view -> executeCompression());
        compressChangeButton.setOnClickListener(view -> changeCompressionSelection());
        compressClearButton.setOnClickListener(view -> clearCompressionSelection());
        zipFormatButton.setOnClickListener(view -> {
            formatSpinner.setSelection(0);
            updateFormatDependentUi();
        });
        sevenZFormatButton.setOnClickListener(view -> {
            formatSpinner.setSelection(1);
            updateFormatDependentUi();
        });
        advancedToggleButton.setOnClickListener(view -> toggleAdvancedSettings());
        showPasswordButton.setOnClickListener(
                view -> togglePasswordVisibility(passwordInput, showPasswordButton));
        extractShowPasswordButton.setOnClickListener(
                view -> togglePasswordVisibility(extractPasswordInput, extractShowPasswordButton));
        extractPasswordToggle.setOnClickListener(view ->
                showExtractionPasswordPanel(extractPasswordPanel.getVisibility() != View.VISIBLE));
        extractButton.setOnClickListener(view -> beginExtraction());
        extractAgainButton.setOnClickListener(view -> restartExtractionFlow());
        shareButton.setOnClickListener(view -> shareLastArchive());
        compressAgainButton.setOnClickListener(view -> restartCompressionFlow());
        cancelButton.setOnClickListener(view -> confirmCancellation());
        resetButton.setOnClickListener(view -> resetOptions());
        errorRecoveryButton.setOnClickListener(view -> {
            Runnable action = recoveryAction;
            clearError();
            if (action != null) {
                action.run();
            }
        });
        errorDismissButton.setOnClickListener(view -> clearError());
        enableNotificationsButton.setOnClickListener(view -> requestNotificationPermissionIfNeeded());

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

    /** Keeps the primary extraction path separate from compression-only choices. */
    private void selectMode(Mode mode) {
        boolean extracting = mode == Mode.EXTRACT;
        extractContent.setVisibility(extracting ? View.VISIBLE : View.GONE);
        compressContent.setVisibility(extracting ? View.GONE : View.VISIBLE);
        updateModeButton(extractModeButton, extracting,
                R.string.mode_extract, R.string.mode_extract_selected);
        updateModeButton(compressModeButton, !extracting,
                R.string.mode_compress, R.string.mode_compress_selected);
        if (!working) {
            clearStatus();
            clearError();
        }
    }

    private void updateModeButton(
            Button button,
            boolean selected,
            int unselectedDescription,
            int selectedDescription
    ) {
        button.setSelected(selected);
        button.setBackgroundResource(selected
                ? R.drawable.button_primary : R.drawable.button_secondary);
        button.setTextColor(getColor(selected
                ? R.color.button_primary_text : R.color.button_secondary_text));
        button.setContentDescription(getString(selected ? selectedDescription : unselectedDescription));
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
        clearError();
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

    private void executeCompression() {
        if (!hasCompressionSource()) {
            showError(
                    "圧縮するファイルまたはフォルダを選んでください。",
                    R.string.retry_select_file,
                    this::beginFileCompression);
            return;
        }
        captureCreateOptions();
        setUiState(false, false);
        if (selectedSourceKind == SourceKind.FOLDER && selectedSourceTreeUri != null) {
            createArchiveFromTree(selectedSourceTreeUri);
        } else if (selectedSourceKind == SourceKind.FILES && !selectedSourceFileUris.isEmpty()) {
            createArchiveFromUris(new ArrayList<>(selectedSourceFileUris));
        }
    }

    private boolean hasCompressionSource() {
        return (selectedSourceKind == SourceKind.FOLDER && selectedSourceTreeUri != null)
                || (selectedSourceKind == SourceKind.FILES && !selectedSourceFileUris.isEmpty());
    }

    private void changeCompressionSelection() {
        if (selectedSourceKind == SourceKind.FOLDER) {
            beginFolderCompression();
        } else {
            beginFileCompression();
        }
    }

    private void clearCompressionSelection() {
        selectedSourceTreeUri = null;
        selectedSourceFileUris.clear();
        selectedSourceKind = SourceKind.NONE;
        updateCompressionSelectionUi();
        clearStatus();
        clearError();
    }

    private void updateCompressionSelectionUi() {
        boolean selected = hasCompressionSource();
        compressSourceActions.setVisibility(selected ? View.GONE : View.VISIBLE);
        compressSourcePanel.setVisibility(selected ? View.VISIBLE : View.GONE);
        compressSettingsPanel.setVisibility(selected ? View.VISIBLE : View.GONE);
        compressExecuteButton.setEnabled(selected && !working);

        if (!selected) {
            compressSourceSummary.setText("");
            return;
        }
        if (selectedSourceKind == SourceKind.FOLDER) {
            String name = StorageBridge.treeDisplayName(this, selectedSourceTreeUri);
            compressSourceSummary.setText(getString(R.string.selected_folder, name));
        } else if (selectedSourceFileUris.size() == 1) {
            Uri uri = selectedSourceFileUris.get(0);
            String name = StorageBridge.displayName(this, uri, "ファイル");
            compressSourceSummary.setText(getString(R.string.selected_file_single, name));
        } else {
            compressSourceSummary.setText(
                    getString(R.string.selected_files_count, selectedSourceFileUris.size()));
        }
    }

    private void beginExtraction() {
        capturePassword();
        Uri archiveUri = pendingArchiveUri != null ? pendingArchiveUri : incomingArchiveUri;
        if (archiveUri != null) {
            prepareArchivePreview(archiveUri);
            return;
        }
        setUiState(false, false);
        startActivityForResult(archivePickerIntent(), REQUEST_ARCHIVE);
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
            selectedSourceTreeUri = null;
            selectedSourceFileUris.clear();
            selectedSourceFileUris.addAll(selectedUris);
            selectedSourceKind = SourceKind.FILES;
            setUiState(false, true);
            updateCompressionSelectionUi();
            clearStatus();
            clearError();
            return;
        }

        Uri selectedUri = data.getData();
        if (selectedUri == null) {
            onPickerCancelled(requestCode);
            return;
        }
        StorageBridge.takePersistablePermission(this, selectedUri, data.getFlags());

        if (requestCode == REQUEST_SOURCE_DIRECTORY) {
            selectedSourceFileUris.clear();
            selectedSourceTreeUri = selectedUri;
            selectedSourceKind = SourceKind.FOLDER;
            setUiState(false, true);
            updateCompressionSelectionUi();
            clearStatus();
            clearError();
        } else if (requestCode == REQUEST_CREATE_ARCHIVE) {
            saveGeneratedArchive(selectedUri);
        } else if (requestCode == REQUEST_ARCHIVE) {
            incomingArchiveUri = null;
            pendingArchiveUri = selectedUri;
            prepareArchivePreview(selectedUri);
        } else if (requestCode == REQUEST_DESTINATION_DIRECTORY) {
            pendingDestinationTreeUri = selectedUri;
            extractArchiveToTree(selectedUri);
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_NOTIFICATION_PERMISSION) {
            return;
        }
        notificationPermissionRequestInFlight = false;
        updateNotificationNotice();
        if (working && canPostNotifications()) {
            postOperationNotification(progressDetail.getText().toString(), progress.getProgress());
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
        operationContext = OperationContext.COMPRESSION;
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
        operationContext = OperationContext.COMPRESSION;
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
            cancelOperationNotification();
            setUiState(false, false);
            setStatus("圧縮が完了しました。保存先とファイル名を選んでください。");
            launchArchiveSavePicker();
        });
    }

    private void launchArchiveSavePicker() {
        if (generatedArchive == null) {
            finishWithError(new IllegalStateException("保存するアーカイブが見つかりません。"));
            return;
        }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(generatedArchiveMimeType == null
                        ? "application/octet-stream" : generatedArchiveMimeType)
                .putExtra(Intent.EXTRA_TITLE, generatedArchive.getName())
                .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                .addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_CREATE_ARCHIVE);
    }

    private void saveGeneratedArchive(Uri destinationUri) {
        if (generatedArchive == null) {
            finishWithError(new IllegalStateException("保存するアーカイブが見つかりません。"));
            return;
        }
        operationContext = OperationContext.SAVE_ARCHIVE;
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
                mainThread.post(() -> postFailureOnMain(error));
            }
        });
    }

    private void prepareArchivePreview(Uri archiveUri) {
        clearPreparedArchive();
        operationContext = OperationContext.PREVIEW_ARCHIVE;
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
                    cancelOperationNotification();
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
        View content = LayoutInflater.from(this).inflate(R.layout.dialog_archive_preview, null);
        TextView fileName = content.findViewById(R.id.preview_file_name);
        TextView summary = content.findViewById(R.id.preview_summary);
        TextView encryption = content.findViewById(R.id.preview_encryption);
        LinearLayout warningPanel = content.findViewById(R.id.preview_warning_panel);
        TextView warning = content.findViewById(R.id.preview_warning);
        TextView entries = content.findViewById(R.id.preview_entries);
        Button detailsButton = content.findViewById(R.id.preview_details_button);
        TextView details = content.findViewById(R.id.preview_details);

        fileName.setText(StorageBridge.displayName(this, archiveUri, archive.getName()));
        summary.setText(getString(
                R.string.archive_preview_summary,
                info.format,
                info.fileCount,
                formatBytes(info.uncompressedBytes)));
        encryption.setText(info.encrypted
                ? R.string.archive_preview_encrypted
                : R.string.archive_preview_not_encrypted);
        encryption.setTextColor(getColor(info.encrypted
                ? R.color.color_warning : R.color.color_on_surface_variant));

        if (info.previewEntries.isEmpty()) {
            entries.setText(R.string.archive_preview_no_entries);
        } else {
            String previewText = joinPreviewEntries(info.previewEntries);
            int remaining = info.entryCount - info.previewEntries.size();
            if (remaining > 0) {
                previewText += "\n" + getString(R.string.archive_preview_more_entries, remaining);
            }
            entries.setText(previewText);
        }

        if (info.needsCapacityWarning(compressedBytes)) {
            warningPanel.setVisibility(View.VISIBLE);
            warning.setText(R.string.archive_preview_capacity_warning);
        }

        details.setText(getString(
                R.string.archive_preview_technical,
                info.entryCount,
                info.fileCount,
                formatBytes(compressedBytes),
                formatBytes(info.uncompressedBytes),
                formatRatio(info.uncompressedBytes, compressedBytes)));
        detailsButton.setOnClickListener(view -> {
            boolean opening = details.getVisibility() != View.VISIBLE;
            details.setVisibility(opening ? View.VISIBLE : View.GONE);
            detailsButton.setText(opening
                    ? R.string.archive_preview_hide_details
                    : R.string.archive_preview_details);
        });

        boolean passwordRequired = info.encrypted && extractPasswordInput.length() == 0;
        if (info.encrypted) {
            showExtractionPasswordPanel(true);
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(R.string.archive_preview_title)
                .setView(content)
                .setNegativeButton(R.string.cancel, (dialog, which) -> cancelPreparedArchive())
                .setOnCancelListener(dialog -> cancelPreparedArchive());

        if (passwordRequired) {
            builder.setPositiveButton(R.string.enter_password_action, (dialog, which) -> {
                setUiState(false, true);
                showExtractionPasswordPanel(true);
                setStatus(getString(R.string.extract_password_required));
                extractPasswordInput.requestFocus();
            });
        } else {
            builder.setPositiveButton(R.string.choose_destination,
                    (dialog, which) -> chooseExtractionDestination());
        }
        builder.show();
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
        pendingDestinationTreeUri = destinationTreeUri;
        operationContext = OperationContext.EXTRACT_ARCHIVE;
        final char[] password = passwordCopy();
        final File work = preparedArchiveWorkDirectory;
        final File localArchive = preparedArchive;
        startWork("アーカイブを解凍しています…", 32);
        activeTask = executor.submit(() -> {
            try {
                File extraction = new File(work, "extracted");
                StorageBridge.deleteRecursively(extraction);
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
                    showExtractionResult();
                });
            } catch (Exception error) {
                mainThread.post(() -> postFailureOnMain(error));
            } finally {
                wipe(password);
            }
        });
    }

    private void showArchiveSavedDialog() {
        compressWorkflowPanel.setVisibility(View.GONE);
        compressResultPanel.setVisibility(View.VISIBLE);
        clearStatus();
        clearError();
    }

    private void showExtractionResult() {
        extractWorkflowPanel.setVisibility(View.GONE);
        extractResultPanel.setVisibility(View.VISIBLE);
        clearStatus();
        clearError();
    }

    private void restartCompressionFlow() {
        lastSavedArchiveUri = null;
        lastSavedArchiveMimeType = null;
        compressResultPanel.setVisibility(View.GONE);
        compressWorkflowPanel.setVisibility(View.VISIBLE);
        passwordInput.setText("");
        advancedSettingsPanel.setVisibility(View.GONE);
        advancedToggleButton.setText(R.string.advanced_settings);
        clearCompressionSelection();
    }

    private void restartExtractionFlow() {
        extractResultPanel.setVisibility(View.GONE);
        extractWorkflowPanel.setVisibility(View.VISIBLE);
        pendingArchiveUri = null;
        incomingArchiveUri = null;
        pendingDestinationTreeUri = null;
        extractPasswordInput.setText("");
        showExtractionPasswordPanel(false);
        clearStatus();
        clearError();
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
        setUiState(false, true);
        if (requestCode == REQUEST_SOURCE_DIRECTORY || requestCode == REQUEST_SOURCE_FILES) {
            clearStatus();
            return;
        }
        if (requestCode == REQUEST_CREATE_ARCHIVE) {
            showError(
                    "保存先の選択をキャンセルしました。作成済みのアーカイブはまだ保存できます。",
                    R.string.retry_destination,
                    this::launchArchiveSavePicker);
            return;
        }
        if (requestCode == REQUEST_DESTINATION_DIRECTORY) {
            showError(
                    "解凍先の選択をキャンセルしました。内容確認済みのアーカイブは保持しています。",
                    R.string.retry_destination,
                    this::chooseExtractionDestination);
            return;
        }
        if (requestCode == REQUEST_ARCHIVE) {
            pendingArchiveUri = null;
            clearPassword();
            clearStatus();
        }
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
        postOutcomeNotification(R.string.notification_complete_title, message);
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
        OperationContext failedContext = operationContext;
        String message = localizedErrorMessage(error);
        boolean passwordRelated = isPasswordRelated(error);
        finishWork();
        clearStatus();

        if (passwordRelated && (failedContext == OperationContext.PREVIEW_ARCHIVE
                || failedContext == OperationContext.EXTRACT_ARCHIVE)) {
            showExtractionPasswordPanel(true);
        }

        switch (failedContext) {
            case PREVIEW_ARCHIVE:
                showError(
                        message,
                        passwordRelated ? R.string.retry_password : R.string.retry_preview,
                        () -> {
                            capturePassword();
                            if (pendingArchiveUri != null) {
                                prepareArchivePreview(pendingArchiveUri);
                            } else {
                                beginExtraction();
                            }
                        });
                break;
            case COMPRESSION:
                showError(message, R.string.retry, this::executeCompression);
                break;
            case SAVE_ARCHIVE:
                showError(message, R.string.retry_destination, this::launchArchiveSavePicker);
                break;
            case EXTRACT_ARCHIVE:
                showError(
                        message,
                        passwordRelated ? R.string.retry_password : R.string.retry_destination,
                        () -> {
                            capturePassword();
                            if (passwordRelated && pendingDestinationTreeUri != null) {
                                extractArchiveToTree(pendingDestinationTreeUri);
                            } else {
                                chooseExtractionDestination();
                            }
                        });
                break;
            default:
                showError(message, 0, null);
                break;
        }
        postOutcomeNotification(R.string.notification_failed_title, message);
    }

    private boolean isPasswordRelated(Exception error) {
        Throwable current = error;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                String lower = message.toLowerCase(Locale.ROOT);
                if (lower.contains("password") || lower.contains("encrypted")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private void showError(String message, int actionResource, Runnable action) {
        recoveryAction = action;
        errorText.setText(message);
        errorRecoveryButton.setVisibility(action == null ? View.GONE : View.VISIBLE);
        if (action != null && actionResource != 0) {
            errorRecoveryButton.setText(actionResource);
        }
        errorPanel.setVisibility(View.VISIBLE);
        errorPanel.post(() -> rootScroll.smoothScrollTo(
                0,
                Math.max(0, errorPanel.getTop()
                        - getResources().getDimensionPixelSize(R.dimen.space_sm))));
    }

    private void clearError() {
        recoveryAction = null;
        errorPanel.setVisibility(View.GONE);
        errorText.setText("");
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
        clearError();
        cancellationRequested = false;
        working = true;
        setUiState(true, false);
        setStatus(status);
        progress.setProgress(initialProgress);
        progressDetail.setText(status);
        postOperationNotification(status, initialProgress);
        progressPanel.post(() -> rootScroll.smoothScrollTo(0,
                Math.max(0, progressPanel.getTop()
                        - getResources().getDimensionPixelSize(R.dimen.space_sm))));
    }

    private void finishWork() {
        cancellationRequested = false;
        working = false;
        activeTask = null;
        operationContext = OperationContext.NONE;
        cancelOperationNotification();
        setUiState(false, true);
    }

    private void postProgress(String status, int amount) {
        mainThread.post(() -> {
            if (!cancellationRequested && working) {
                setStatus(status);
                progressDetail.setText(status);
                progress.setProgress(amount);
                postOperationNotification(status, amount);
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
        incomingArchiveUri = null;
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
        pendingPassword = extractPasswordInput.getText().toString().toCharArray();
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
        if (extractPasswordInput != null) {
            extractPasswordInput.setText("");
        }
        if (extractPasswordPanel != null && extractPasswordToggle != null) {
            showExtractionPasswordPanel(false);
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
        formatHelpText.setText(zip ? R.string.format_zip_help : R.string.format_7z_help);
        updateChoiceButton(zipFormatButton, zip);
        updateChoiceButton(sevenZFormatButton, !zip);
    }

    private void updateChoiceButton(Button button, boolean selected) {
        button.setSelected(selected);
        button.setBackgroundResource(selected
                ? R.drawable.button_primary : R.drawable.button_secondary);
        button.setTextColor(getColor(selected
                ? R.color.button_primary_text : R.color.button_secondary_text));
    }

    private void toggleAdvancedSettings() {
        boolean opening = advancedSettingsPanel.getVisibility() != View.VISIBLE;
        advancedSettingsPanel.setVisibility(opening ? View.VISIBLE : View.GONE);
        advancedToggleButton.setText(opening
                ? R.string.hide_advanced_settings : R.string.advanced_settings);
        updateFormatDependentUi();
    }

    private void togglePasswordVisibility(EditText input, ImageButton button) {
        boolean showing = input.getTransformationMethod()
                instanceof HideReturnsTransformationMethod;
        input.setTransformationMethod(showing
                ? PasswordTransformationMethod.getInstance()
                : HideReturnsTransformationMethod.getInstance());
        button.setImageResource(showing
                ? R.drawable.ic_visibility : R.drawable.ic_visibility_off);
        button.setContentDescription(getString(showing
                ? R.string.show_password : R.string.hide_password));
        input.setSelection(input.length());
    }

    private void showExtractionPasswordPanel(boolean show) {
        extractPasswordPanel.setVisibility(show ? View.VISIBLE : View.GONE);
        extractPasswordToggle.setVisibility(show ? View.GONE : View.VISIBLE);
    }

    private void updateNotificationNotice() {
        boolean shouldExplain = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED;
        notificationNotice.setVisibility(shouldExplain ? View.VISIBLE : View.GONE);
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
        passwordInput.setText("");
        advancedSettingsPanel.setVisibility(View.GONE);
        advancedToggleButton.setText(R.string.advanced_settings);
        updateFormatDependentUi();
        setStatus("詳細設定を初期値に戻しました。");
    }

    private void setUiState(boolean showProgress, boolean allowActions) {
        progressPanel.setVisibility(showProgress ? View.VISIBLE : View.GONE);
        extractModeButton.setEnabled(allowActions);
        compressModeButton.setEnabled(allowActions);
        compressFolderButton.setEnabled(allowActions);
        compressFilesButton.setEnabled(allowActions);
        compressChangeButton.setEnabled(allowActions);
        compressClearButton.setEnabled(allowActions);
        compressExecuteButton.setEnabled(allowActions && hasCompressionSource());
        zipFormatButton.setEnabled(allowActions);
        sevenZFormatButton.setEnabled(allowActions);
        advancedToggleButton.setEnabled(allowActions);
        extractButton.setEnabled(allowActions);
        extractPasswordToggle.setEnabled(allowActions);
        extractPasswordInput.setEnabled(allowActions);
        extractShowPasswordButton.setEnabled(allowActions);
        resetButton.setEnabled(allowActions);
        formatSpinner.setEnabled(allowActions);
        formatField.setEnabled(allowActions);
        includeRootFolder.setEnabled(allowActions);
        includeHiddenFiles.setEnabled(allowActions);
        passwordInput.setEnabled(allowActions);
        showPasswordButton.setEnabled(allowActions);
        shareButton.setEnabled(allowActions);
        compressAgainButton.setEnabled(allowActions);
        extractAgainButton.setEnabled(allowActions);
        enableNotificationsButton.setEnabled(!showProgress);
        cancelButton.setEnabled(showProgress && !cancellationRequested);
        updateFormatDependentUi();
    }

    private void setStatus(String message) {
        if (message == null || message.isEmpty()) {
            clearStatus();
            return;
        }
        statusText.setText(message);
        statusText.setVisibility(View.VISIBLE);
    }

    private void clearStatus() {
        statusText.setText("");
        statusText.setVisibility(View.GONE);
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
        cancelOperationNotification();
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
        selectMode(Mode.EXTRACT);
        incomingArchiveUri = archiveUri;
        pendingArchiveUri = archiveUri;
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

    private enum Mode {
        EXTRACT,
        COMPRESS
    }

    private enum SourceKind {
        NONE,
        FILES,
        FOLDER
    }

    private enum OperationContext {
        NONE,
        COMPRESSION,
        SAVE_ARCHIVE,
        PREVIEW_ARCHIVE,
        EXTRACT_ARCHIVE
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
