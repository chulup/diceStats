# Trained models go here (not in git, see .gitignore). Built in ../training; copies under /ai/runs/.
#
# Contract (YoloDetectionPipeline / ValueClassifierPipeline): ONNX opset 12, RGB, pixels scaled 0..1.
#   Detectors: 640x640 letterboxed input.   Classifiers: 224x224 crop input, softmax [1,6] output.
#
# Assets recognized (RecognitionModule; absent ones are skipped):
#   yolo26m-dice.onnx       one-stage, classes d6-1..d6-6 (value = class id + 1)   /ai/runs/dice-v3/yolo26m-*/
#   yolo26s-die.onnx        one-class detector "die"                            /ai/runs/dice-two-stage/yolo26s-*/weights/best.onnx
#   yolo26n-die.onnx        one-class detector "die"                            /ai/runs/dice-two-stage/yolo26n-*/weights/best.onnx
#   yolo26s-cls-value.onnx  value classifier 1..6                               /ai/runs/dice-two-stage/yolo26s-cls-*/weights/best.onnx
#   yolo26n-cls-value.onnx  value classifier 1..6                               /ai/runs/dice-two-stage/yolo26n-cls-*/weights/best.onnx
# With all five present, taken photos use the 2-of-3 consensus (design-records/2026-10-08-...).
# With no model, recognition falls back to ClassicalDieDetector.
