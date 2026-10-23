#pragma once

#include <string>
#include <vector>

namespace mobile_asr {

struct TranscriptTokens {
    std::vector<int> ids;
    bool annotation_unwrapped = false;
};

struct TranscriptText {
    std::string text;
    bool annotation_unwrapped = false;
};

/**
 * Unwrap the model's inline A annotations.
 *
 * The model can emit: <payload |A>. Those delimiters are ordinary lexical
 * token IDs, not members of add_special_tokens.txt, so a generic
 * "skip special tokens" decoder cannot remove them. We only accept the
 * exact token grammar below wherever it occurs in the generated transcript.
 * Incomplete sequences, nested delimiters and unknown classes are preserved.
 */
TranscriptTokens normalize_transcript_tokens(const std::vector<int>& generated);

/**
 * Apply the same exact annotation grammar after BPE decoding. This second
 * layer is required because a tokenizer can encode delimiters together with
 * adjacent whitespace or punctuation instead of using the standalone IDs.
 */
TranscriptText normalize_transcript_text(const std::string& decoded);

}  // namespace mobile_asr
