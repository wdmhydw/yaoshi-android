#include "live_progress.hpp"
#include "tokenizer.hpp"
#include "subtitle.hpp"
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <string>

namespace mt {
static const Tokenizer* active_tokenizer=nullptr;
static std::string progress_path, last_payload, decoded;
static size_t decoded_count=0;
static std::chrono::steady_clock::time_point last_update;
void android_progress_begin(const Tokenizer* tokenizer) {
    const char* path=std::getenv("MTD_PROGRESS_FILE");
    active_tokenizer=tokenizer;progress_path=path?path:"";last_payload.clear();decoded.clear();decoded_count=0;
    last_update=std::chrono::steady_clock::time_point{};
}
void android_progress_tokens(const std::vector<int32_t>& ids) {
    if (!active_tokenizer || progress_path.empty()) return;
    const auto now=std::chrono::steady_clock::now();
    if (now-last_update<std::chrono::milliseconds(120)) return;
    last_update=now;
    // BPE tokens can end halfway through a UTF-8 character. Accumulate bytes and
    // publish only complete codepoints, decoding each token once per chunk.
    if(ids.size()<decoded_count){decoded.clear();decoded_count=0;}
    decoded+=active_tokenizer->decode(std::vector<int32_t>(ids.begin()+decoded_count,ids.end()));
    decoded_count=ids.size();
    size_t valid=0;
    while(valid<decoded.size()){
        const auto lead=static_cast<unsigned char>(decoded[valid]);
        size_t width=lead<0x80?1:(lead&0xe0)==0xc0?2:(lead&0xf0)==0xe0?3:(lead&0xf8)==0xf0?4:1;
        if(valid+width>decoded.size())break;
        valid+=width;
    }
    std::string text=decoded.substr(0,valid),escaped;
    for(unsigned char ch:text){
        if(ch=='"'||ch=='\\'){escaped+='\\';escaped+=char(ch);}
        else if(ch<0x20){char b[7];std::snprintf(b,sizeof(b),"\\u%04x",ch);escaped+=b;}
        else escaped+=char(ch);
    }
    auto segments=subtitle_segments_from_transcript(text,false);
    // Provisional text is a separate channel, never a saved segment with a
    // fabricated end timestamp. Java extracts the current unfinished sentence.
    std::string payload="{\"segments\":"+to_json(segments)+",\"transcript\":\""+escaped+"\"}";
    if (payload==last_payload) return;
    std::string temporary=progress_path+".part";
    FILE* file=std::fopen(temporary.c_str(),"wb");
    if (!file) return;
    bool wrote=std::fwrite(payload.data(),1,payload.size(),file)==payload.size();
    int closed=std::fclose(file);
    if (wrote && closed==0 && std::rename(temporary.c_str(),progress_path.c_str())==0) last_payload=payload;
}
}
