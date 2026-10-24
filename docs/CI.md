# GitHub Actions

`push`、`pull_request`、手動実行でAndroid CIを起動します。

## 実行内容

- Ubuntu 24.04、Temurin JDK 17、SDK Platform 35 / Build Tools 35.0.0。
- SHA-256で検証したGradle Wrapper 8.11.1を使用。
- sherpa-onnxのAAR（Maven Centralに無いため作者がHugging Faceで配布しているもの）は、ビルド時にダウンロードし、`app/build.gradle.kts`に固定したSHA-256と照合してから使います。
- `assembleDebug`、`assembleDebugAndroidTest`、`testDebugUnitTest`、`lintDebug`。
- Android 15（API 35、google_apis x86_64）のエミュレーターを起動し、`connectedDebugAndroidTest`で端末のコーデックを使う取り込み・M4A変換・AAC/MP3録音・音声処理と、音声認識・端末内AIのネイティブライブラリの読み込みを確認します（`instrumented`ジョブ、結果は`instrumented-reports`）。エミュレーターはSDKの`emulator`/`avdmanager`で直接起動し、追加のActionは使いません。
- arm64 APKの署名と16KBページ向けZIP配置を検証。
- APKとSHA-256一覧を`debug-apks`、テスト・Lint結果を`verification-reports`として保存（14日間）。
- Windowsのジョブでは、Wrapperの復元と`--version`の起動を確認します。

Actionsは公式のタグから取得したコミットSHAに固定しています。設定したトークン権限はリポジトリ内容の読み取りです。外部AIのモデル、APIキー、録音データはテストに要求しません。

## 画面のプレビュー

`bash gradlew :app:testDebugUnitTest --tests '*ScreenshotTest*' -Pscreenshots` で、主要な画面をサンプルデータで描画し、ライト/ダークのPNGを`build/screenshots/`に保存します（Robolectric）。指定しない通常の実行ではスキップします。

## APKの取得

[Actions](https://github.com/nekochan-cute/OpenRecorder/actions)から成功したAndroid CIを開き、Artifactsの`debug-apks`をダウンロードします。通常の最近のAndroid端末にはarm64-v8a版を使用します。

APKはdebug署名です。debug鍵は実行ごとに作られるため、別の実行のAPKを上書きインストールするには、一度アンインストールが必要です。

## Wrapperの格納形式

リポジトリでは公式Wrapper JARをBase64のテキストとして保持しています。`gradlew` / `gradlew.bat`は初回起動時にローカルへ復元し、保存したSHA-256と照合します。JARの中身は公式配布物のままです。生成したJARはGit管理対象外です。

POSIX環境では`base64`と`sha256sum`または`shasum`、WindowsではPowerShellを使用します。実行権限がない場合は`bash gradlew ...`から実行できます。
