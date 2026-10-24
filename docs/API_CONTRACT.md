# 外部AI APIの契約

要約と文字起こしは、OpenAI互換のHTTPSエンドポイントを呼び出します。各サービスのベースURLは次のとおりです。

| サービス | ベースURL |
| --- | --- |
| OpenRouter | `https://openrouter.ai/api/v1` |
| Google AI Studio | `https://generativelanguage.googleapis.com/v1beta/openai` |
| OpenAI | `https://api.openai.com/v1` |
| Groq | `https://api.groq.com/openai/v1` |
| その他 | 設定したURL |

要約は `<ベースURL>/chat/completions`、文字起こしは `<ベースURL>/audio/transcriptions`、モデル一覧は `<ベースURL>/models` です。

## 音声から文章・話者ラベル

`POST <設定した文字起こしの完全なURL>`

`multipart/form-data`:

| フィールド | 値 |
| --- | --- |
| `file` | `recording.wav` / `audio/wav`、音声ファイル全体 |
| `response_format` | `verbose_json` |
| `language` | 設定が空欄なら省略。それ以外は設定値（既定`ja`） |
| `model` | 設定が空欄なら省略。それ以外は設定値 |

キーが設定された場合のみ`Authorization: Bearer <key>`を付加します。

成功応答の例:

```json
{
  "text": "今日の議題を確認します。はい。",
  "segments": [
    {"start": 0.4, "end": 2.2, "text": "今日の議題を確認します。", "speaker": "SPEAKER_00"},
    {"start": 2.5, "end": 3.0, "text": "はい。", "speaker": "SPEAKER_01"}
  ]
}
```

時刻の単位は秒です。`speaker`は省略可能です。`segments`がない場合は`text`を録音全体の1区間として取り込み、細かな時刻対応は利用できません。話者分離はサーバー側の処理です。

サーバーが別の`response_format`、独自の認証、別形式の話者配列を要求する場合は、そのままでは動作しません。アダプターの追加が必要です。最大アップロード容量はサーバー側の制限を受けます。アプリは音声を自動分割しません。

## 文章から要約

`POST <設定した要約の完全なURL>`、`Content-Type: application/json`。

要求の構造:

```json
{
  "model": "configured-model",
  "stream": false,
  "messages": [
    {"role": "system", "content": "用途別の日本語整理指示"},
    {"role": "user", "content": "[00:00] SPEAKER_00: 今日の議題を確認します。"}
  ]
}
```

応答は`choices[0].message.content`から取得します。最初の行をタイトル、残りを要約として保存します。JSONモード・ツール呼び出しは要求しません。モデルによって先頭行やMarkdownの扱いは異なるため、出力を確認してください。

## 通信動作

- HTTPS、標準の証明書検証、自動リダイレクト無効。
- 接続20秒、読み取り5分、1要求全体10分のタイムアウト。
- 応答の上限8MiB。JSON以外、HTTPエラー、不正なタイムスタンプはエラー表示。
- APIキー、音声、文字起こしをログへ出力しません。
- 失敗した要求は再送しません。

## OpenRouterのログイン

[OAuth PKCE](https://openrouter.ai/docs/use-cases/oauth-pkce)を使います。

1. `code_verifier`（48バイトの乱数）と、そのSHA-256の`code_challenge`、`state`を作ります。
2. 端末のループバック（`127.0.0.1`）の空きポートで待ち受け、`https://openrouter.ai/auth?callback_url=http://localhost:<port>/callback&code_challenge=…&code_challenge_method=S256&state=…` をブラウザで開きます。
3. コールバックの`state`を照合し、`code`を受け取ります。待ち受けは5分で終了します。
4. `POST https://openrouter.ai/api/v1/auth/keys` に `code`・`code_verifier`・`code_challenge_method` を送り、応答の`key`をAPIキーとして保存します。
