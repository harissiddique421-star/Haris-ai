"""Independent reference outputs for android/tests/jvm (VoiceModelsCheck).

Silero VAD: the official ONNX model run in onnxruntime. Mel: librosa. GE2E speaker encoder:
the converted weights run through onnxruntime's own LSTM operator (not our Java code).

usage: python make_reference.py <silero_vad.onnx> <voice_encoder.safetensors> <out.json> <16 kHz wav>...
needs: pip install numpy onnx onnxruntime librosa
"""
import sys, json, struct, wave, numpy as np, onnxruntime as ort, librosa, onnx
from onnx import helper, TensorProto, numpy_helper
SILERO_ONNX, ENCODER, OUT, WAVS = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4:]
def read(f):
    w = wave.open(f); return np.frombuffer(w.readframes(w.getnframes()), dtype='<i2').astype(np.float32) / 32768
def st(path):
    f = open(path, 'rb'); n = struct.unpack('<Q', f.read(8))[0]; h = json.loads(f.read(n)); base = 8 + n; raw = open(path, 'rb').read()
    return {k: np.frombuffer(raw[base+v['data_offsets'][0]:base+v['data_offsets'][1]], dtype='<f4').reshape(v['shape']) for k, v in h.items() if k != '__metadata__'}
vad = ort.InferenceSession(SILERO_ONNX)
def silero(wav):
    state = np.zeros((2, 1, 128), np.float32); ctx = np.zeros((1, 64), np.float32); out = []
    for i in range(0, len(wav) - 511, 512):
        x = np.concatenate([ctx, wav[None, i:i+512]], axis=1)
        p, state = vad.run(None, {'input': x, 'state': state, 'sr': np.array(16000, dtype=np.int64)})
        ctx = x[:, -64:]; out.append(float(p[0, 0]))
    return out
W = st(ENCODER)
def lstm_graph():
    nodes = []; inits = []; prev = 'x'
    for l in range(3):
        def reorder(a):  # torch i,f,g,o -> onnx i,o,f,c
            i, f, g, o = np.split(a, 4, axis=0); return np.concatenate([i, o, f, g], axis=0)
        Wl = reorder(W[f'lstm.weight_ih_l{l}'])[None]; R = reorder(W[f'lstm.weight_hh_l{l}'])[None]
        B = np.concatenate([reorder(W[f'lstm.bias_ih_l{l}']), reorder(W[f'lstm.bias_hh_l{l}'])])[None]
        inits += [numpy_helper.from_array(Wl, f'W{l}'), numpy_helper.from_array(R, f'R{l}'), numpy_helper.from_array(B, f'B{l}')]
        nodes.append(helper.make_node('LSTM', [prev, f'W{l}', f'R{l}', f'B{l}'], [f'Y{l}', f'H{l}'], hidden_size=256))
        nodes.append(helper.make_node('Squeeze', [f'Y{l}', 'ax1'], [f'S{l}'])); prev = f'S{l}'
    inits.append(numpy_helper.from_array(np.array([1], np.int64), 'ax1'))
    inits += [numpy_helper.from_array(W['linear.weight'].T.copy(), 'LW'), numpy_helper.from_array(W['linear.bias'], 'LB')]
    nodes += [helper.make_node('Squeeze', ['H2', 'ax0'], ['h']), helper.make_node('MatMul', ['h', 'LW'], ['m']), helper.make_node('Add', ['m', 'LB'], ['a']), helper.make_node('Relu', ['a'], ['e'])]
    inits.append(numpy_helper.from_array(np.array([0], np.int64), 'ax0'))
    g = helper.make_graph(nodes, 'ge2e', [helper.make_tensor_value_info('x', TensorProto.FLOAT, [160, 'b', 40])], [helper.make_tensor_value_info('e', TensorProto.FLOAT, ['b', 256])], inits)
    m = helper.make_model(g, opset_imports=[helper.make_opsetid('', 17)], ir_version=9); return ort.InferenceSession(m.SerializeToString())
enc = lstm_graph()
def mel(wav): return librosa.feature.melspectrogram(y=wav, sr=16000, n_fft=400, hop_length=160, n_mels=40, pad_mode='reflect').astype(np.float32).T
def embed(wav):
    dbfs = 10 * np.log10(np.mean(wav ** 2)); ch = -30 - dbfs
    if ch > 0: wav = wav * 10 ** (ch / 20)
    n_frames = int(np.ceil((len(wav) + 1) / 160)); step = max(int(np.round((16000 / 1.3) / 160)), 1)
    sl = [(i, i + 160) for i in range(0, max(1, n_frames - 160 + step + 1), step)]
    cov = (len(wav) - sl[-1][0] * 160) / (160 * 160)
    if cov < 0.75 and len(sl) > 1: sl = sl[:-1]
    need = sl[-1][1] * 160
    if need > len(wav): wav = np.pad(wav, (0, need - len(wav)))
    m = mel(wav); parts = np.stack([m[a:b] for a, b in sl], axis=1)  # T,B,40
    e = enc.run(None, {'x': parts})[0]; e = e / np.linalg.norm(e, axis=1, keepdims=True)
    r = e.mean(0); return r / np.linalg.norm(r)
if __name__ == '__main__':
    out = {}
    for f in WAVS:
        w = read(f); out[f.split('/')[-1]] = {'vad': silero(w), 'mel0': mel(w)[:5].tolist(), 'embed': embed(w).tolist()}
    json.dump(out, open(OUT, 'w'))
    names = WAVS; E = [np.array(out[f.split('/')[-1]]['embed']) for f in names]
    for i, a in enumerate(names):
        print(a.split('/')[-1], ' '.join(f'{float(E[i] @ E[j]):.3f}' for j in range(len(names))))
