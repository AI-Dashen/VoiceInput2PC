#include "asr_transcript_postprocessor.h"

#include <algorithm>

namespace mobile_asr {
namespace {
constexpr int kEos = 2;
constexpr int kOpen = 529;       // <
constexpr int kClose = 531;      // >
constexpr int kPipe = 593;       // |
constexpr int kClassA = 534;     // A
constexpr int kSpace = 722;      // GPT-2 byte token U+0120 -> ASCII space
constexpr std::size_t kMaxPayloadTokens = 16;

bool find_a_annotation(const std::vector<int>& ids, std::size_t start,
                       std::size_t end, std::size_t* pipe) {
    if (start >= end || ids[start] != kOpen) return false;
    const std::size_t limit = std::min(end, start + 2 + kMaxPayloadTokens);
    for (std::size_t i = start + 1; i < limit; ++i) {
        if (ids[i] == kOpen || ids[i] == kClose) return false;
        if (ids[i] != kPipe) continue;
        if (i == start + 1 || i + 2 >= end || ids[i + 1] != kClassA ||
            ids[i + 2] != kClose) {
            return false;
        }
        *pipe = i;
        return true;
    }
    return false;
}
}  // namespace

TranscriptTokens normalize_transcript_tokens(const std::vector<int>& generated) {
    TranscriptTokens result{{}, false};
    result.ids.reserve(generated.size());
    const auto eos = std::find(generated.begin(), generated.end(), kEos);
    const std::size_t end = static_cast<std::size_t>(eos - generated.begin());

    for (std::size_t i = 0; i < end;) {
        std::size_t pipe = 0;
        if (!find_a_annotation(generated, i, end, &pipe)) {
            result.ids.push_back(generated[i++]);
            continue;
        }

        std::size_t payload_end = pipe;
        while (payload_end > i + 1 && generated[payload_end - 1] == kSpace) {
            --payload_end;
        }
        if (payload_end == i + 1) {
            result.ids.push_back(generated[i++]);
            continue;
        }

        result.ids.insert(result.ids.end(), generated.begin() + i + 1,
                          generated.begin() + payload_end);
        result.annotation_unwrapped = true;
        i = pipe + 3;
    }

    result.ids.insert(result.ids.end(), generated.begin() + end, generated.end());
    return result;
}

TranscriptText normalize_transcript_text(const std::string& decoded) {
    constexpr std::size_t kMaxPayloadBytes = 128;
    TranscriptText result{{}, false};
    result.text.reserve(decoded.size());

    for (std::size_t i = 0; i < decoded.size();) {
        if (decoded[i] != '<') {
            result.text.push_back(decoded[i++]);
            continue;
        }

        const std::size_t search_end =
            std::min(decoded.size(), i + 1 + kMaxPayloadBytes + 3);
        std::size_t pipe = std::string::npos;
        bool invalid = false;
        for (std::size_t j = i + 1; j < search_end; ++j) {
            if (decoded[j] == '<' || decoded[j] == '>') {
                invalid = true;
                break;
            }
            if (decoded[j] == '|') {
                pipe = j;
                break;
            }
        }

        if (invalid) {
            const std::size_t close = decoded.find('>', i + 1);
            if (close != std::string::npos) {
                result.text.append(decoded, i, close - i + 1);
                i = close + 1;
            } else {
                result.text.append(decoded, i, std::string::npos);
                i = decoded.size();
            }
            continue;
        }
        if (pipe == std::string::npos || pipe == i + 1 ||
            pipe + 2 >= decoded.size() || decoded[pipe + 1] != 'A' ||
            decoded[pipe + 2] != '>') {
            result.text.push_back(decoded[i++]);
            continue;
        }

        std::size_t payload_end = pipe;
        while (payload_end > i + 1 && decoded[payload_end - 1] == ' ') {
            --payload_end;
        }
        if (payload_end == i + 1) {
            result.text.push_back(decoded[i++]);
            continue;
        }

        result.text.append(decoded, i + 1, payload_end - (i + 1));
        result.annotation_unwrapped = true;
        i = pipe + 3;
    }
    return result;
}

}  // namespace mobile_asr
