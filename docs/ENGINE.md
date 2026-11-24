# Android版やねうら王

上流: https://github.com/yaneurao/YaneuraOu
リビジョン: 3bd5141dfd50b3fc6abd9a9db7561d9338a883d0
版: YANEURAOU_ENGINE_MATERIAL (MaterialLv1)
Android ABI: arm64-v8a / NDK r23b
ライセンス: GPL-3.0 (LICENSE-GPL-3.0)

Gradleの prepareYaneuraOu タスクで固定リビジョンの上流ソースを取得し、NDK r23bのndk-buildでPIE実行ファイルを生成し、APKの lib/arm64-v8a/libshogi_engine.so に格納する。これはJNIライブラリではなく、拡張子を.soにしてNative Libraryディレクトリへ展開する実行形式。

UsiEngine.ktは ProcessBuilder で起動し、usi、setoption MultiPV=1..3、isready、position sfen、go movetime を送る。結果の1手目は合法手として検証して描画に渡す。

**NNUE評価関数は未搭載。** MaterialLv1は駒得中心の探索エンジンで、NNUE版と同等の評価精度ではない。NNUE版は別途評価関数モデルのライセンスとAndroidへの配布サイズ・メモリを確認する必要がある。

やねうら王はGPLv3。配布時は本アプリ・組み込み部を含む対応ソース、上流リビジョン、再現可能なビルド手順、GPL全文を提供する必要がある。配布条件の整合性確認と実機でのUSI起動テストは未完了。
