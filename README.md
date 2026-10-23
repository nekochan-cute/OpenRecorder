# Open Recorder

[![Android CI](https://github.com/nekochan-cute/OpenRecorder/actions/workflows/android.yml/badge.svg)](https://github.com/nekochan-cute/OpenRecorder/actions/workflows/android.yml)

ColorOS 16/17のレコーダーにある主要な用途を、Androidの公開APIとOSSで独立実装するプロジェクトです。仮称はOpen Recorderです。OPPOとの提携・公式互換性を示す名称ではありません。

現在の版は `0.1.0-dev` です。完全な機能同等性を達成した版ではありません。

## 実装した機能

| 分野 | 内容 |
| --- | --- |
| 録音 | 16bit PCM WAV、16/48kHz、モノラル/ステレオ、一時停止・再開、通知から停止、画面消灯中の継続 |
| 入力 | 入力デバイスの希望指定、実際の入力名表示、VOICE_RECOGNITION入力、対応端末のNoiseSuppressor |
| 保全 | 逐次保存、約1秒ごとのヘッダー更新と同期、次回起動時の中断ファイル回収 |
| 再生 | 波形、シーク、10秒移動、0.5〜2倍速、Media3による無音スキップ |
| マーク | 録音中/再生中の時刻付きメモ、写真添付、位置への移動、削除、一覧共有 |
| 編集 | 範囲を残す/範囲を削除、別ファイルへの保存、残ったマークと文章の時刻移動 |
| 管理 | 名前・フォルダー、お気に入り、音声名/マーク/文章/話者/要約の検索、ごみ箱と復元・完全削除 |
| 文字起こし | VoskモデルZIPの取り込み、端末内の録音中/録音後認識、指定APIによる録音後認識、文章・話者名の修正 |
| 話者ラベル | APIの`segments[].speaker`取り込み、個別修正、一括名前変更 |
| 要約 | Chat Completions互換API、自動タイトル、会議/講義/インタビューテンプレート |
| 共有 | WAV、文字起こしTXT、SRT字幕、要約Markdown、マーク一覧TXT |

端末のノイズ抑制はColorOS独自の「AI人声強調」と同じ処理ではありません。入力デバイスの希望指定は、実際のルーティングを保証するAPIではありません。

## 未実装・制約

- 端末内の自動話者分離、端末内LLM要約、翻訳。
- AAC/M4A/MP3の録音・取り込み、音声結合、後処理による音声強調・音量正規化。
- ColorOSの指向性録音・独自DSP・クラウドとの同一品質、ColorOS内の他アプリとの専用連携。
- APKの内部解析による機能抽出は行っていません。17固有の詳細な差分、端末/地域による機能差は未確認です。
- モデルは同梱していません。APIサーバー、APIキー、学習済みモデルの配布・ホスティングは含みません。
- 日本語の句読点復元は未搭載です。Voskの日本語出力には空白が含まれる場合があります。
- 長時間録音の自動分割は未搭載です。WAVの約4GiB上限で保存・停止します。48kHzモノラルは約5.76MB/分、ステレオは約11.52MB/分です。
- 音声が60,000文字を超える場合は要約要求を拒否します。外部APIへの自動分割送信は未搭載です。
- UIは日本語です。バックグラウンド再生用のメディア通知は未搭載です。
- 音声はアプリ専用領域にあります。アプリのアンインストールで削除されます。残す録音は書き出してください。

## ビルド

Android 10以上。ビルドにはJDK 17、Android SDK Platform 35、Build Tools 35.0.0を使用します。

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Windowsは`gradlew.bat`を使用してください。Android Studioからこのフォルダーを開けます。SDKが検出されない場合は`local.properties`に自身の`sdk.dir`を設定してください。

出力は`app/build/outputs/apk/debug/`にあります。通常の最近のAndroid端末では`app-arm64-v8a-debug.apk`を使用します。armeabi-v7a・x86_64・x86は別APKとして生成します。debug版は開発用署名です。リリース署名鍵は含みません。

## 端末内文字起こし

1. [Vosk公式モデル一覧](https://alphacephei.com/vosk/models)から小型モデルのZIPを取得します。
2. 日本語の候補は`vosk-model-small-ja-0.22`（公式一覧では48MB、Apache-2.0）です。
3. アプリの「設定」→「モデルZIPを取り込む」でZIPを選びます。
4. 録音設定でリアルタイム認識を有効にするか、保存した録音の「文字起こし」から実行します。

モデルZIPは展開先の逸脱、ファイル数、展開容量を検査し、読み込みに成功してから既存モデルを置き換えます。精度・速度・消費メモリーは実機測定が必要です。

リアルタイム認識のキューが満杯になった場合、認識を停止して音声の保存を継続します。保存後に録音後認識を実行できます。リアルタイムの文章は正常停止時に保存されるため、プロセス終了では音声から再認識してください。

## API設定

API機能を実行すると、設定したサーバーへ音声または文章が送信されます。送信前にアプリで確認します。端末内の録音・編集・Vosk認識ではAPIに通信しません。

- 「文字起こしの完全なURL」には、multipart/form-dataを受け取るエンドポイントを登録します。
- 「要約の完全なURL」には、Chat Completions形式のエンドポイントを登録します。
- HTTPSが必要です。キーなしの自前サーバーに対応します。自動リダイレクトは追従しません。
- APIキーと設定はAndroid KeystoreのAES-GCM鍵で暗号化します。OSバックアップは無効です。
- 標準のTLS検証を使用します。自己署名証明書の無条件受け入れは実装していません。
- APIごとの契約・料金・モデル・対応形式はサービス提供元の仕様に従います。

具体的な要求/応答形式は[API_CONTRACT.md](docs/API_CONTRACT.md)に記載しています。

CIのAPK・テスト結果は[Actions](https://github.com/nekochan-cute/OpenRecorder/actions)で取得できます。[CIの構成とWrapperの格納形式](docs/CI.md)に詳細を記載しています。

## 開発資料

- [機能仕様と受け入れ条件](docs/SPECIFICATION.md)
- [独立実装の参照資料と開発記録](docs/CLEAN_ROOM.md)
- [検証結果・実機確認表](docs/VALIDATION.md)
- [第三者ライセンス](THIRD_PARTY_NOTICES.md)

## ライセンス

本プロジェクトのコードはApache License 2.0です。依存ライブラリと別途導入するモデルには、それぞれのライセンスが適用されます。
