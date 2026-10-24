import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CPP = ROOT / "android/app/src/main/cpp/independent_asr_engine.cpp"
DECODER = ROOT / "android/app/src/main/cpp/independent_decoder_runtime.cpp"

class LongDictationContractTests(unittest.TestCase):
    def test_segment_budget_stays_below_decoder_cache(self):
        engine = CPP.read_text()
        decoder = DECODER.read_text()
        generated = int(re.search(r"kGeneratedTokenLimit=(\d+)", engine).group(1))
        chunks = int(re.search(r"kAudioChunksPerSegment=(\d+)", engine).group(1))
        cache = int(re.search(r"MAXTOK=(\d+)", decoder).group(1))
        # prompt + 2 tokens per audio block + suffix + generated text
        self.assertLessEqual(3 + chunks * 2 + 3 + generated, cache)
        self.assertLessEqual(chunks * 320, 15000)

    def test_long_sessions_roll_both_encoder_and_decoder(self):
        source = CPP.read_text()
        self.assertIn("if(stream_segment_chunks>=kAudioChunksPerSegment)roll_decoder_segment();", source)
        self.assertIn("stream_encoder_remove=encoder_meta.previous", source)
        self.assertIn("std::fill(stream_s0.begin(),stream_s0.end(),0.0f)", source)
        self.assertIn("decoder->reset()", source)
        self.assertIn("stream_completed_texts.push_back(finish_decoder_segment())", source)
        self.assertIn("kMaxFrontendFrames=18432", source)

    def test_cancel_drops_all_segment_state(self):
        source = CPP.read_text()
        cancel = source[source.index("void cancel_stream()"):source.index("std::string run(")]
        self.assertIn("stream_completed_texts.clear()", cancel)
        self.assertIn("stream_segment_chunks=0", cancel)

if __name__ == "__main__":
    unittest.main()
