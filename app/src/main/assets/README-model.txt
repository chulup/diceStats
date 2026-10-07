# Drop the trained YOLO model here as: yolo26n-dice.onnx
#
# Export from Ultralytics:  yolo export model=yolo26n-dice.pt format=onnx opset=12 imgsz=640
# Contract (see YoloDetectionPipeline): RGB, 640x640 letterboxed input, pixels scaled 0..1.
# Classes d6-1..d6-6 (class id = value - 1; YoloDieDetector.CLASS_VALUES). Trained in ../training.
# A yolo26s/m model works too: rename its ONNX to this file name.
# When present, RecognitionModule selects YoloDieDetector automatically; when absent it falls
# back to ClassicalDieDetector. This placeholder just keeps the assets/ dir under version control.
