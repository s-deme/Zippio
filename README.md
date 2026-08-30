# Zippio

広告・アカウント・通信を使わない、個人用 Android 圧縮・解凍アプリです。ファイルはすべて端末内の一時領域で処理され、完了・失敗・中止後に削除されます。

## 実装済みの製品要件

| 軸 | 実装 |
| --- | --- |
| 圧縮 | フォルダ全体または複数ファイルを ZIP / 7z へ圧縮。ZIP は「高速 / 標準 / 高圧縮」を選択可能。 |
| 構成 | 元フォルダ名を含めるか、隠しファイルを含めるかを選択可能。選択は次回起動時も復元（パスワードは保存しない）。 |
| 暗号化 | ZIP（AES）と 7z の作成、ZIP / 7z / RAR の解凍でパスワードを使用可能。表示切替と長さの目安を用意。 |
| 解凍前の判断 | 形式、項目数、ファイル数、展開後サイズ、圧縮元サイズ、展開倍率、先頭 8 項目を表示。大容量・大量ファイル・高倍率は注意を表示。 |
| 検証 | 一時領域に実際に展開して全項目を読めるか確認し、検証データを自動削除。 |
| ファイル連携 | Android 標準のファイル選択画面のみ使用。ファイル管理アプリの「開く」「共有」からアーカイブを受け取れる。作成後は共有シートも開ける。 |
| 安全性 | アーカイブ内の ../、絶対パス、不正な名前を展開前と展開時の双方で拒否。既存ファイルは上書きせず連番で保存。 |
| 長時間処理 | 工程別の進捗と、安全な中止要求を提供。中止時は一時ファイルを掃除。 |
| UI / アクセシビリティ | 圧縮・解凍を分離したカード、ラベル付き入力、48dp 以上の操作領域、状態テキスト、明確な確認・キャンセル導線。 |
| DX | 最小の Java 実装、ユニットテスト、署名・権限まで検証する GitHub Actions、依存関係を固定した Gradle 構成。 |

## 対応範囲

| 形式 | 圧縮 | 解凍 | パスワード |
| --- | --- | --- | --- |
| ZIP | 対応（AES） | 対応（PPMd を含む） | 対応 |
| 7z | 対応 | 対応 | 対応 |
| RAR | 非対応 | 対応 | 対応 |

RAR の作成は RAR 形式のライセンス／仕様上、アプリには含めません。分割アーカイブは対象外です。

PPMd（ZIP 圧縮方式 98）を含む ZIP は、解凍時に 7-Zip エンジンへ自動的に切り替えます。ZIP の作成方式は Deflate です。

## プライバシーと安全性

- ストレージの広範な権限は要求せず、Android の Storage Access Framework だけを使います。
- パスワードは設定にも履歴にも保存せず、処理の終了時に入力欄とメモリ上の作業コピーを消去します。
- 外部への送信、広告 SDK、アカウント、解析 SDK は含みません。
- 検証・圧縮・解凍に使う中間ファイルはアプリのキャッシュ内に限定します。前回異常終了時の作業領域も次回起動時に掃除します。

## Android Studio でのビルド

1. JDK 17 以降が同梱された最新の Android Studio でこのフォルダを開きます。
2. SDK Platform 35 と Android SDK Build-Tools 36.0.0 をインストールし、Gradle Sync を実行します。
3. testDebugUnitTest と lintDebug を実行し、端末またはエミュレータで Run を実行します。

## PowerShell でのローカルビルド

`build.ps1` は固定されたPC固有パスを持ちません。`ANDROID_SDK_ROOT`（または `ANDROID_HOME`）と `JAVA_HOME` を優先し、JDK 17 以降、SDK Platform 35、Build-Tools 36.0.0を使います。

```powershell
./build.ps1 -Mode Debug
```

検証済みAPKとSHA-256ファイルは既定で `dist/` に出力されます。出力先は `-OutputDirectory` または `ZIPPIO_OUTPUT_DIR` で変更できます。

```powershell
./build.ps1 -Mode Debug -OutputDirectory out/local
```

Releaseモードは、リポジトリ外にある固定keystoreと4つの署名環境変数がすべて設定されている場合だけ動作します。不足時はデバッグ鍵を生成・流用せず、ビルド開始前に失敗します。パスワードはコマンド引数へ渡さず、安全なシークレット管理手段から環境変数へ設定してください。

## GitHub Actions

### CI

`main` へのpushと `main` 宛てPull Requestでは、JDK 21とAndroid SDK Platform 35／Build-Tools 36.0.0を使って次を実行します。

- ユニットテスト、Lint、デバッグAPKのビルド
- `apksigner` によるAPK署名検証
- `aapt2` によるAPK内Manifestと不要権限の検査
- SHA-256チェックサムの照合
- `zippio-debug-<commit SHA>` というWorkflow Artifactの14日間保存

CIはRelease署名Secretsを参照せず、正式なGitHub Releaseを作成・更新しません。

### 正式Release

正式Releaseは `v*` タグがpushされた場合だけ作成されます。ワークフローは次の条件を満たさない限り公開しません。

- タグから先頭の `v` を除いた値と、`app/build.gradle` の `versionName` が完全一致する
- `versionCode` が正の整数である
- 同じタグのGitHub Releaseがまだ存在しない
- 固定keystoreによる署名とAPK署名検証が成功する
- APKに許可していないAndroid権限が含まれない
- APKと `zippio.apk.sha256` のSHA-256が一致する

成功時は、自動生成したRelease notesとともに最新の正式Releaseとして次を添付します。

- `zippio.apk`
- `zippio.apk.sha256`

既存Release、既存タグの移動・削除・上書きは行いません。個人アクセストークンは不要で、Releaseジョブに限定したGitHub標準の `GITHUB_TOKEN` を使用します。

## Release署名Secrets

リポジトリのGitHub Actions Secretsに以下を登録します。

| Secret | 内容 |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | 固定リリースkeystore全体をBase64化した値 |
| `ANDROID_KEYSTORE_PASSWORD` | keystoreのパスワード |
| `ANDROID_KEY_ALIAS` | 署名鍵のエイリアス |
| `ANDROID_KEY_PASSWORD` | 署名鍵のパスワード |

keystore本体や復元した一時鍵はコミットしません。ワークフローで復元した鍵は、ビルドの成功・失敗にかかわらず直後に削除されます。

> [!WARNING]
> リリースkeystoreを紛失すると、同じ署名を必要とする既存APKを更新できません。GitHub Secretsとは別に、暗号化した安全なオフラインバックアップを複数保管してください。

## バージョン更新と公開手順

1. `app/build.gradle` の `versionCode` を以前より大きい正の整数へ更新します。
2. 同じファイルの `versionName` を公開するタグ名から `v` を除いた値へ更新します。
3. CIが成功したコミットへ新しいタグを作成し、そのタグだけをpushします。

たとえば `v1.1.3` を公開する場合は、先に `versionCode 5`、`versionName '1.1.3'` へ編集してから次を実行します。

```bash
git add app/build.gradle
git commit -m "chore: prepare v1.1.3"
git push origin main
git tag -a v1.1.3 -m "Zippio v1.1.3"
git push origin v1.1.3
```

最後のタグpushがReleaseワークフローを開始します。公開済みタグは移動せず、修正が必要な場合は `versionCode` とバージョンを再度進めて新しいタグを作成します。
