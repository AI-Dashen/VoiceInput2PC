#pragma once
#include <cstdint>
#include <memory>
#include <string>

namespace mobile_asr {
class Engine {
public:
    explicit Engine(std::string model_dir);
    ~Engine();
    Engine(const Engine&) = delete;
    Engine& operator=(const Engine&) = delete;
    bool ready() const;
    const std::string& error() const;
    std::string recognize(const int16_t* pcm, size_t samples);
    void beginStream();
    void acceptStream(const int16_t* pcm, size_t samples);
    std::string finishStream();
    void cancelStream();
private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
};
}
