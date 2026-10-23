#include "asr_transcript_postprocessor.h"

#include <cassert>
#include <vector>

using mobile_asr::normalize_transcript_text;
using mobile_asr::normalize_transcript_tokens;

int main() {
    // <嗯 |A> -> 嗯; the separator space is not transcript content.
    auto filler = normalize_transcript_tokens({529, 22900, 722, 593, 534, 531, 2});
    assert(filler.annotation_unwrapped);
    assert((filler.ids == std::vector<int>{22900, 2}));

    // Punctuation inside the payload is preserved.
    auto punctuated = normalize_transcript_tokens({529, 4527, 523, 593, 534, 531, 2});
    assert(punctuated.annotation_unwrapped);
    assert((punctuated.ids == std::vector<int>{4527, 523, 2}));

    // Inline and repeated annotations are normalized without losing the
    // surrounding transcript. This matches real microphone output.
    auto inline_result = normalize_transcript_tokens(
        {529, 22900, 523, 593, 534, 531, 17, 18,
         529, 4527, 523, 593, 534, 531, 19, 2});
    assert(inline_result.annotation_unwrapped);
    assert((inline_result.ids ==
            std::vector<int>{22900, 523, 17, 18, 4527, 523, 19, 2}));

    // Unknown class, incomplete frame, nested delimiters and oversized payload
    // are preserved instead of being hidden by a broad text regex.
    for (const auto& value : std::vector<std::vector<int>>{
             {529, 22900, 593, 535, 531, 2},
             {529, 22900, 593, 534, 2},
             {529, 22900, 529, 593, 534, 531, 2},
             {17, 529, 22900, 593, 535, 531, 18, 2},
             {529, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
              593, 534, 531, 2}}) {
        auto result = normalize_transcript_tokens(value);
        assert(!result.annotation_unwrapped);
        assert(result.ids == value);
    }
    auto inline_text = normalize_transcript_text(
        "<嗯，|A>我现在测试一下。就这样。还是会出来，你好，<嗯，|A>啊。");
    assert(inline_text.annotation_unwrapped);
    assert(inline_text.text == "嗯，我现在测试一下。就这样。还是会出来，你好，嗯，啊。");

    auto repeated_text = normalize_transcript_text("<嗯嗯嗯。 |A><嗯，|A>");
    assert(repeated_text.annotation_unwrapped);
    assert(repeated_text.text == "嗯嗯嗯。嗯，");

    for (const auto& text : std::vector<std::string>{
             "普通<符号>", "<内容|B>", "<内容|A", "<外<内|A>", "<>"}) {
        auto unchanged = normalize_transcript_text(text);
        assert(!unchanged.annotation_unwrapped);
        assert(unchanged.text == text);
    }
    return 0;
}
