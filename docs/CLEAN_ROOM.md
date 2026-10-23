# 独立実装の開発記録

作成日: 2026-10-23

## このプロジェクトで採用した手順

1. 利用者が指定した目的: ColorOS 16/17のレコーダーの機能をOSSで再現すること。
2. 公開された公式説明から、外部に現れる機能の概要を確認。
3. 本プロジェクトの機能仕様・データ形式・画面・実装を独自に作成。
4. APKの逆コンパイル結果、smali、メーカーのソース、内部API、抽出した画像/文字列/XML/ネイティブライブラリは参照していません。
5. 今回は実機によるColorOS 16/17の網羅的な操作観察を実施していません。観察済みという記録や、完全互換という表示は行いません。

これは開発過程の記録であり、法的判断・権利非侵害の保証・第三者監査済み証明ではありません。

## 参照した一次資料

| 資料 | 確認した内容 |
| --- | --- |
| [OPPO ColorOS 16 AI紹介](https://www.oppo.com/en/newsroom/stories/oppo-find-x9-series-coloros-16-ai/) | リアルタイム文字起こし、話者識別、自動タイトル・要約、用途別テンプレート、音声/文章/要約の共有 |
| [OPPO日本 ColorOS 16](https://www.oppo.com/jp/coloros16/) | 日本向け紹介における文字起こし・話者識別・議事録 |
| [ColorOS 17公式](https://www.coloros.com/version/coloros17/) | 対象バージョンの公式ページ。レコーダー個別差分は未確定 |
| [Androidのマイク用フォアグラウンドサービス](https://developer.android.com/about/versions/14/changes/fgs-types-required) | 権限、サービス種別、バックグラウンドからの開始制限 |
| [AudioRecord](https://developer.android.com/reference/android/media/AudioRecord) | 公開されたPCM取得・デバイス希望指定API |
| [Vosk Android](https://alphacephei.com/vosk/android) | Android向け音声認識ライブラリ |
| [Voskモデル一覧](https://alphacephei.com/vosk/models) | モデルの対象言語、サイズ、ライセンス |
| [Android Gradle Plugin 8.9](https://developer.android.com/build/releases/agp-8-9-0-release-notes) | Gradle 8.11.1、JDK 17、Build Tools 35.0.0の対応関係 |

ColorOS 14/14.1の公式マニュアルは、この会話の初期の機能整理で参照しました。16/17での搭載状況を証明する資料として扱っていません。

## 今後の観察記録の書式

| 項目 | 記録内容 |
| --- | --- |
| 環境 | 端末名、地域、ColorOS版、レコーダー版 |
| 入力 | 音声、設定値、操作手順 |
| 観察結果 | 保存形式、表示結果、時刻、編集後の長さなど |
| 根拠 | 自分で記録した操作動画、スクリーンショット、公開マニュアルの該当箇所 |
| 採用する仕様 | どの外部動作を再現するか |
| 未確定 | 内部方式の推測や、未確認の端末差 |

逆コンパイルを伴う調査を新しく行う場合は、解析資料の扱いと実装担当へ渡す仕様の範囲を別途定義します。現行コードへ解析断片を混ぜない運用が必要です。
