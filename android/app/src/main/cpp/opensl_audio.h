#ifndef OPENSL_AUDIO_H
#define OPENSL_AUDIO_H

#include <SLES/OpenSLES.h>
#include <SLES/OpenSLES_Android.h>
#include <cstdint>
#include <mutex>
#include <queue>
#include <vector>

/**
 * OpenSL ES 音频播放器（PCM 16bit 44100Hz 双声道）。
 * 内部维护 PCM 数据块队列，回调驱动匀速播放。
 */
class OpenSLAudioPlayer {
public:
    OpenSLAudioPlayer();
    ~OpenSLAudioPlayer();

    bool init();          // 初始化引擎 + 播放器
    void destroy();       // 释放全部资源
    int  writePcm(const uint8_t* data, uint32_t len);
    void pause();         // 暂停播放
    void resume();        // 恢复播放

private:
    static void bufferQueueCallback(SLAndroidSimpleBufferQueueItf bq, void* context);
    void enqueueNextBlock();
    void enqueueSilence(); // 缓冲区为空时填充静音，保持播放器不停止

    SLObjectItf engineObj_;
    SLEngineItf engine_;
    SLObjectItf outputMixObj_;
    SLObjectItf playerObj_;
    SLPlayItf player_;
    SLAndroidSimpleBufferQueueItf bufferQueue_;

    std::mutex mtx_;
    std::queue<std::vector<uint8_t>> pending_;
    bool inited_;
    bool paused_;
    uint8_t silence_[1024];
};

#endif // OPENSL_AUDIO_H
