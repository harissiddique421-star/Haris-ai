# Extracts the weights of the official Silero VAD v5 16 kHz ONNX model (silero_vad_16k_op15.onnx, MIT)
# usage: python silero_onnx_to_safetensors.py silero_vad/data/silero_vad_16k_op15.onnx silero_vad_16k.safetensors   (from pip download silero-vad==6.2.3)
# into safetensors with the names used by SileroVad.java.
import sys, json, struct, numpy as np, onnx
from onnx import numpy_helper
m = onnx.load(sys.argv[1]); I = {i.name: numpy_helper.to_array(i) for i in m.graph.initializer}
names = {'stft_conv.weight': 'model.stft.forward_basis_buffer',
  'conv1.weight': 'model.encoder.0.reparam_conv.weight', 'conv1.bias': 'model.encoder.0.reparam_conv.bias',
  'conv2.weight': 'model.encoder.1.reparam_conv.weight', 'conv2.bias': 'model.encoder.1.reparam_conv.bias',
  'conv3.weight': 'model.encoder.2.reparam_conv.weight', 'conv3.bias': 'model.encoder.2.reparam_conv.bias',
  'conv4.weight': 'model.encoder.3.reparam_conv.weight', 'conv4.bias': 'model.encoder.3.reparam_conv.bias',
  'lstm_cell.weight_ih': 'model.decoder.rnn.weight_ih', 'lstm_cell.weight_hh': 'model.decoder.rnn.weight_hh',
  'lstm_cell.bias_ih': 'model.decoder.rnn.bias_ih', 'lstm_cell.bias_hh': 'model.decoder.rnn.bias_hh',
  'final_conv.weight': 'model.decoder.decoder.2.weight', 'final_conv.bias': 'model.decoder.decoder.2.bias'}
header = {}; blobs = []; off = 0
for k, src in names.items():
    b = np.ascontiguousarray(I[src], dtype='<f4').tobytes()
    header[k] = {'dtype': 'F32', 'shape': list(I[src].shape), 'data_offsets': [off, off + len(b)]}; blobs.append(b); off += len(b)
header['__metadata__'] = {'source': 'silero-vad 6.2.3 silero_vad_16k_op15.onnx (MIT)'}
h = json.dumps(header, separators=(',', ':')).encode(); h += b' ' * ((8 - len(h) % 8) % 8)
with open(sys.argv[2], 'wb') as o:
    o.write(struct.pack('<Q', len(h))); o.write(h); [o.write(b) for b in blobs]
