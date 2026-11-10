#pragma once
#include <cstdint>
#include <vector>
namespace mt {
class Tokenizer;
void android_progress_begin(const Tokenizer* tokenizer);
void android_progress_tokens(const std::vector<int32_t>& ids);
}
