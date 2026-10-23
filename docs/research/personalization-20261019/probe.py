"""Read-only structural and tokenization probe; never runs inference or changes model weights."""
from pathlib import Path
import importlib.util,json,struct
root=Path(__file__).resolve().parents[4]
assets=root/'voiceinput2pc-v08/android/app/src/main/assets/independent-asr'
audit=root/'analysis/doubao-ime-20261016/independent-runtime/tokenizer_asset_audit.py'
spec=importlib.util.spec_from_file_location('local_token_audit',audit)
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
index=json.loads((m.EVIDENCE/'flute-index.json').read_text())
raw,clear=m.decoded_member(index,'/asr/token/merges.txt')
ranks={tuple(row.split()):i for i,row in enumerate(clear.decode().splitlines())}
assert all(len(pair)==2 for pair in ranks)
lines=(assets/'vocab.txt').read_text().splitlines()
vocab={lines[i]:int(lines[i+1]) for i in range(0,len(lines),2)}
bs=list(range(33,127))+list(range(161,173))+list(range(174,256));cs=bs.copy();n=0
for b in range(256):
 if b not in bs:bs.append(b);cs.append(256+n);n+=1
byte_encoder={b:chr(c) for b,c in zip(bs,cs)};byte_decoder={c:b for b,c in byte_encoder.items()}
phrases=[]
for phrase in ['海口港','海南港航','万能输入法','本地识别','张晓明','OpenAI']:
 encoded=''.join(byte_encoder[b] for b in phrase.encode())
 pieces=m.bpe_pieces(encoded,ranks)
 ids=[vocab[p] for p in pieces]
 reverse=bytes(byte_decoder[c] for piece in pieces for c in piece).decode()
 assert reverse==phrase
 phrases.append({'phrase':phrase,'token_ids':ids,'token_count':len(ids),'byte_roundtrip':True})
with (assets/'decoder-runtime.bin').open('rb') as f:header=f.read(36)
magic=header[:8].decode();version,width,layers,positions,projections,norms,gelu=struct.unpack('<7I',header[8:])
cache=(root/'.runtime/mnn-build-3.6.1-clean/CMakeCache.txt').read_text()
engine=(root/'voiceinput2pc-v08/android/app/src/main/cpp/independent_asr_engine.cpp').read_text()
print(json.dumps({'status':'PASS_STRUCTURAL_TOKENIZATION_ONLY','engine':'MNN 3.6.1 + custom C++ decoder','decoder_header':{'magic':magic,'version':version,'width':width,'layers':layers,'positions':positions,'projections':projections,'norms':norms},'mnn_training_build_enabled':'MNN_BUILD_TRAIN:BOOL=ON' in cache,'greedy_selection_present':'std::max_element(logits,logits+kVocab)' in engine,'apk_has_merges':(assets/'merges.txt').exists(),'local_source_merges_available':True,'model_total_file_bytes':sum(p.stat().st_size for p in assets.iterdir() if p.is_file()),'personal_word_tokenization':phrases,'training_run':False,'asr_improvement_tested':False,'limits':['Byte-level BPE phrase encoding and roundtrip only; no full vendor regex pretokenizer equivalence claimed.','Phrase boundaries and alternate tokenization paths remain to be tested against actual generated tokens.','No audio recorded; no model or product file changed; no accuracy or latency improvement claimed.']},ensure_ascii=False,indent=2))
