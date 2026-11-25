# Tiny board CNN v1 (2026-11-25)

- Shared for Wars and Quest; 32x32 continuous grayscale darkness, 29 classes.
- Conv 6x5x5 stride 2, conv 6x3x3 stride 2, max pool, dense 96->29.
- 3299 trainable parameters; symmetric signed int8 stored weights with float32 scales; 3327 decoded bytes (4437 bytes as the text asset including newline). Kotlin expands once to float and runs CPU inference; this is not an INT8-accelerated runtime.
- Confidence acceptance: softmax >= 0.75 and top-two gap >= 0.25. These are heuristic scores, not calibrated probabilities. Uncertain squares remain red. Hand recognition and position inventory checks are unchanged.
- A centered scan first; up to four +/-2 px cardinal corrections only if needed. No 81-offset CNN sweep.

## Training and evaluation

Python 3 / PyTorch 2.10.0+cpu, NumPy and Pillow. See `tools/train_board_cnn.py`. All 85 prior binary templates plus synthetic translations/scaling/brightness/noise and selected grayscale cells from the two 691x1536 user screenshots. Alternating duplicate-class cells within each screen were held out (36 Wars, 33 Quest); this is NOT an independent-board test. Unique pieces and their screenshot context may be represented in training. No original screenshots or identifying UI are committed. The training script's optional local arguments require those exact labelled screenshots.

Template-only first experiment (40 epochs): Wars 79/81, Quest 80/81 after quantization. Final 85-epoch experiment: 81/81 each (all accepted with the configured thresholds at the annotated board geometry), held-out cells 36/36 and 33/33. With synthetic grayscale rendered from the training templates, 85/85 top-1 correct, 84/85 accepted. This is a training-distribution check, not independent validation.

Offset probes on these same boards: +/-2 px cardinal shifts: Wars 78-81 accepted correct and zero accepted wrong, Quest 81 accepted correct and zero accepted wrong. At diagonal +/-4 px, confident wrong classes were observed. Keep manual board calibration aligned; the model can still make mistakes. More independent replay positions and device timing are needed before considering the approach robust. The original template code remains available for regression tests.

The packaged model has SHA-256 (base64 text file including newline): 69500585e3376ecd1560f76e40694b4b3e14f5858d106c47e632cc96238fb420.
