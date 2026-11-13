#!/usr/bin/env python3
"""Compares voice engines on FRIDAY's Voice ID test recordings (run by .github/workflows/voice-engines.yml).

Speaker ID: each model makes the owner's profile from the five enrollment recordings (normalised mean
embedding), then scores every test recording whole and in 1.2 s windows (hop 0.6 s), the same
protocol as android/tests/jvm/com/myraa/mobile/SpeakerCompare.java prints for the current GE2E
voiceprint. Scores from different models are not on the same scale, so the comparison uses
  - gap: lowest owner score minus highest other score (positive = a threshold separates them all),
  - EER: equal error rate over the 1.2 s windows (lower is better).

Wake word: sherpa-onnx keyword spotting on synthetic speech (a Piper TTS voice, clean and with the
TV recordings mixed in). The repository has no real "Wake up FRIDAY" recordings, so this only shows
whether the engine can hear the phrases, not how it does on the owner's voice.

args: --fixtures DIR --models DIR --baseline FILE --out FILE
"""
import argparse, glob, json, os, sys, wave
import numpy as np


def read_wav(path):
    with wave.open(path, "rb") as w:
        assert w.getsampwidth() == 2 and w.getnchannels() == 1, path
        rate = w.getframerate()
        data = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float32) / 32768
    if rate != 16000:
        idx = np.arange(0, len(data), rate / 16000)
        data = np.interp(idx, np.arange(len(data)), data).astype(np.float32)
    return data


def cosine(a, b):
    return float(np.dot(a, b) / (np.linalg.norm(a) * np.linalg.norm(b) + 1e-9))


def eer(owner, other):
    best = (1.0, None)
    for t in sorted(set(owner + other)):
        far = sum(s >= t for s in other) / len(other)
        frr = sum(s < t for s in owner) / len(owner)
        d = abs(far - frr)
        if d < best[0]:
            best = (d, (far + frr) / 2)
    return best[1]


def summarise(name, scores):
    own_w = [s["score"] for s in scores if s["who"] == "owner" and s["kind"] == "window"]
    oth_w = [s["score"] for s in scores if s["who"] == "other" and s["kind"] == "window"]
    own_all = [s["score"] for s in scores if s["who"] == "owner"]
    oth_all = [s["score"] for s in scores if s["who"] == "other"]
    return {
        "model": name,
        "owner_min": min(own_all), "owner_mean": float(np.mean(own_all)),
        "other_max": max(oth_all), "other_mean": float(np.mean(oth_all)),
        "gap": min(own_all) - max(oth_all),
        "window_eer": eer(own_w, oth_w),
        "windows": len(own_w) + len(oth_w),
    }


NAMES = ["owner_test_1", "owner_test_2", "friend_1", "friend_2", "tv_1", "tv_2", "tv_3"]


def speaker_scores(sherpa_onnx, model_path, fx):
    ext = sherpa_onnx.SpeakerEmbeddingExtractor(
        sherpa_onnx.SpeakerEmbeddingExtractorConfig(model=model_path, num_threads=2))

    def embed(wav):
        s = ext.create_stream()
        s.accept_waveform(sample_rate=16000, waveform=wav)
        s.input_finished()
        if not ext.is_ready(s):
            return None
        return np.array(ext.compute(s), dtype=np.float32)

    prints = [embed(read_wav(os.path.join(fx, f"owner_enroll_{i}.wav"))) for i in range(1, 6)]
    profile = np.mean([p / np.linalg.norm(p) for p in prints], axis=0)
    out = []
    for n in NAMES:
        w = read_wav(os.path.join(fx, n + ".wav"))
        who = "owner" if n.startswith("owner") else "other"
        parts = [("whole", w)] + [("window", w[s:s + 19200]) for s in range(0, len(w) - 19200 + 1, 9600)]
        for kind, part in parts:
            e = embed(part)
            if e is not None:
                out.append({"file": n, "who": who, "kind": kind, "score": cosine(e, profile)})
    return out


def find_one(root, pattern):
    hits = sorted(glob.glob(os.path.join(root, "**", pattern), recursive=True))
    return hits[0] if hits else None


def wake_word(sherpa_onnx, models, fx):
    kws_dir = os.path.join(models, "kws")
    tts_dir = os.path.join(models, "tts")
    enc = find_one(kws_dir, "encoder*.int8.onnx") or find_one(kws_dir, "encoder*.onnx")
    dec = find_one(kws_dir, "decoder*.onnx")
    joi = find_one(kws_dir, "joiner*.int8.onnx") or find_one(kws_dir, "joiner*.onnx")
    tokens = find_one(kws_dir, "tokens.txt")
    bpe = find_one(kws_dir, "bpe.model")
    vits = find_one(tts_dir, "*.onnx")
    if not all([enc, dec, joi, tokens, bpe, vits]):
        return {"error": "wake word or TTS model files not found"}

    phrases = ["WAKE UP FRIDAY", "HEY FRIDAY", "FRIDAY WAKE UP"]
    lines = sherpa_onnx.text2token(phrases, tokens=tokens, tokens_type="bpe", bpe_model=bpe)
    kw_file = os.path.join(models, "keywords.txt")
    with open(kw_file, "w") as f:
        for toks, p in zip(lines, phrases):
            f.write(" ".join(toks) + " @" + p.replace(" ", "_") + "\n")

    tts = sherpa_onnx.OfflineTts(sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(
        vits=sherpa_onnx.OfflineTtsVitsModelConfig(
            model=vits, tokens=find_one(tts_dir, "tokens.txt"),
            data_dir=os.path.dirname(find_one(tts_dir, "phontab") or "")),
        num_threads=2)))

    def say(text, speed=1.0):
        a = tts.generate(text, sid=0, speed=speed)
        wav = np.array(a.samples, dtype=np.float32)
        if a.sample_rate != 16000:
            idx = np.arange(0, len(wav), a.sample_rate / 16000)
            wav = np.interp(idx, np.arange(len(wav)), wav).astype(np.float32)
        return wav

    kws = sherpa_onnx.KeywordSpotter(tokens=tokens, encoder=enc, decoder=dec, joiner=joi,
                                     num_threads=2, keywords_file=kw_file, provider="cpu",
                                     max_active_paths=4, keywords_score=1.0, keywords_threshold=0.25)

    def heard(wav):
        s = kws.create_stream()
        s.accept_waveform(16000, np.concatenate([np.zeros(8000, np.float32), wav, np.zeros(16000, np.float32)]))
        s.input_finished()
        found = []
        while kws.is_ready(s):
            kws.decode_stream(s)
            r = kws.get_result(s)
            if r:
                found.append(r)
                if hasattr(kws, "reset_stream"):
                    kws.reset_stream(s)
        return found

    tv = np.concatenate([read_wav(os.path.join(fx, f"tv_{i}.wav")) for i in range(1, 4)])

    def with_tv(wav, snr_db):
        noise = np.resize(tv, len(wav))
        scale = np.sqrt(np.mean(wav ** 2) / (np.mean(noise ** 2) + 1e-9)) / (10 ** (snr_db / 20))
        return (wav + noise * scale).astype(np.float32)

    tests = []
    for p, speed in [("Wake up Friday", 1.0), ("Wake up Friday", 1.2), ("Hey Friday", 1.0), ("Friday, wake up", 1.0)]:
        w = say(p, speed)
        tests.append({"say": p, "speed": speed, "noise": "none", "expect": True, "heard": heard(w)})
        tests.append({"say": p, "speed": speed, "noise": "TV at 10 dB below", "expect": True, "heard": heard(with_tv(w, 10))})
    for p in ["What day is it today", "Wake me up at six on Friday", "Hey, open WhatsApp", "Play some music"]:
        tests.append({"say": p, "speed": 1.0, "noise": "none", "expect": False, "heard": heard(say(p))})
    tests.append({"say": "(TV recordings only)", "speed": 1.0, "noise": "-", "expect": False, "heard": heard(tv)})
    for t in tests:
        t["ok"] = bool(t["heard"]) == t["expect"]
    return {"model": os.path.basename(os.path.dirname(enc)), "tests": tests}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--fixtures", required=True)
    ap.add_argument("--models", required=True)
    ap.add_argument("--baseline", required=True)
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    import sherpa_onnx

    rows, errors = [], []
    base = json.load(open(a.baseline))
    rows.append(summarise(base["model"], base["scores"]))
    for path in sorted(glob.glob(os.path.join(a.models, "speaker", "*.onnx"))):
        name = os.path.basename(path)
        try:
            rows.append(summarise(name, speaker_scores(sherpa_onnx, path, a.fixtures)))
        except Exception as e:  # one model failing must not hide the others
            errors.append(f"{name}: {e}")
    try:
        wake = wake_word(sherpa_onnx, a.models, a.fixtures)
    except Exception as e:
        wake = {"error": str(e)}

    md = ["## Speaker ID on the Voice ID test recordings", "",
          "| Model | Owner lowest | Others highest | Gap (higher is better) | EER on 1.2 s windows (lower is better) |",
          "|---|---|---|---|---|"]
    for r in rows:
        md.append(f"| {r['model']} | {r['owner_min']:.3f} | {r['other_max']:.3f} | {r['gap']:+.3f} | {r['window_eer']*100:.1f}% ({r['windows']} windows) |")
    for e in errors:
        md.append(f"\nFailed: {e}")
    md += ["", "## Wake word (sherpa-onnx keyword spotting, synthetic TTS speech)", ""]
    if "error" in wake:
        md.append(f"Not run: {wake['error']}")
    else:
        md.append(f"Model: {wake['model']}. Passed {sum(t['ok'] for t in wake['tests'])} of {len(wake['tests'])}.")
        md += ["", "| Said | Speed | Noise | Should wake | Heard | OK |", "|---|---|---|---|---|---|"]
        for t in wake["tests"]:
            md.append(f"| {t['say']} | {t['speed']} | {t['noise']} | {'yes' if t['expect'] else 'no'} | {', '.join(t['heard']) or '-'} | {'yes' if t['ok'] else 'NO'} |")
    text = "\n".join(md) + "\n"
    print(text)
    with open(a.out, "w") as f:
        f.write(text)
    json.dump({"speaker": rows, "errors": errors, "wake": wake}, open(a.out + ".json", "w"), indent=2)


if __name__ == "__main__":
    sys.exit(main())
