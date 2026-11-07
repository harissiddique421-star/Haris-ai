# Converts Resemblyzer's pretrained.pt (legacy torch serialization) into safetensors, without torch.
# usage: python resemblyzer_to_safetensors.py resemblyzer/pretrained.pt voice_encoder.safetensors   (from pip download resemblyzer==0.1.4)
import sys, pickle, json, struct, numpy as np, collections
src, dst = sys.argv[1], sys.argv[2]
f = open(src, 'rb')
class Storage:
    def __init__(self, key, dtype): self.key, self.dtype, self.data = key, dtype, None
DT = {'FloatStorage': np.float32}
storages = {}; tensors = []
class U(pickle.Unpickler):
    def find_class(self, mod, name):
        if mod == 'torch._utils' and name == '_rebuild_tensor_v2':
            def rebuild(storage, offset, size, stride, *a):
                t = (storage, offset, tuple(size), tuple(stride)); tensors.append(t); return t
            return rebuild
        if mod == 'torch' and name in DT: return name
        if mod == 'collections' and name == 'OrderedDict': return collections.OrderedDict
        return super().find_class(mod, name)
    def persistent_load(self, pid):
        typ, key, loc, n, view = pid[0], pid[2], pid[3], pid[4], pid[5]
        assert pid[0] == 'storage'
        s = storages.setdefault(key, Storage(key, DT[pid[1]])); return s
for _ in range(3): pickle.load(f)  # magic, protocol, sys info
obj = U(f).load()
keys = pickle.load(f)
for k in keys:
    n = struct.unpack('<q', f.read(8))[0]
    s = storages[k]; s.data = np.frombuffer(f.read(n * 4), dtype='<f4')
sd = obj.get('model_state', obj)
header = {}; blobs = []; off = 0
for k, (st, offset, size, stride) in sd.items():
    raw = st.data
    a = np.lib.stride_tricks.as_strided(raw[offset:], shape=size, strides=[s * 4 for s in stride]).copy()
    a = np.ascontiguousarray(a, dtype='<f4'); b = a.tobytes()
    header[k] = {'dtype': 'F32', 'shape': list(a.shape), 'data_offsets': [off, off + len(b)]}; blobs.append(b); off += len(b)
    print(k, a.shape)
header['__metadata__'] = {'source': 'Resemblyzer 0.1.4 pretrained.pt (Apache-2.0), GE2E speaker encoder'}
h = json.dumps(header, separators=(',', ':')).encode(); h += b' ' * ((8 - len(h) % 8) % 8)
with open(dst, 'wb') as o:
    o.write(struct.pack('<Q', len(h))); o.write(h); [o.write(b) for b in blobs]
