#pragma once
#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace mobile_asr {
class IndependentDecoderRuntime {
public:
 explicit IndependentDecoderRuntime(const std::string& asset_path);
 bool ready() const;
 const std::string& error() const;
 void reset();
 std::vector<float> append(const float* embeddings, std::size_t tokens);
private:
 struct Projection { std::uint32_t rows,width,groups; const std::uint8_t* packed; std::size_t packed_n; const float* alpha; std::size_t alpha_n; const float* bias; std::size_t bias_n; };
 struct Norm { float epsilon; const float* gamma; const float* beta; };
 struct LayerState { std::vector<float> key,value; };
 std::vector<std::uint8_t> bytes_;
 std::vector<Projection> projections_;
 std::vector<Norm> norms_;
 const float* rope_cos_=nullptr;
 const float* rope_sin_=nullptr;
 const float* gelu_table_=nullptr;
 std::vector<LayerState> states_;
 std::size_t tokens_=0;
 bool ready_=false;
 std::string error_;
};
}
