Drop the three openWakeWord `.onnx` files here:

  melspectrogram.onnx     (shared, from the openWakeWord release)
  embedding_model.onnx    (shared, from the openWakeWord release)
  hey_vega.onnx           (your trained phrase model)

Without them the app still builds and runs — the wake word disables itself and the
rail mic button triggers the assistant instead.

See ../../cpp/README-native.md and docs/07-voice-implementation.md.
