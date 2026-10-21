#include "opensl_audio.h"
#include <android/log.h>

#define LOG_TAG "OpenSL_Audio"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

OpenSLAudioPlayer::OpenSLAudioPlayer()
    : engineObj_(nullptr), engine_(nullptr), outputMixObj_(nullptr),
      playerObj_(nullptr), player_(nullptr), bufferQueue_(nullptr),
      inited_(false), paused_(false) {
    for (auto& b : silence_) b = 0;
}

OpenSLAudioPlayer::~OpenSLAudioPlayer() {
    destroy();
}

bool OpenSLAudioPlayer::init() {
    if (inited_) return true;

    SLresult ret;
    // 1. 创建引擎
    ret = slCreateEngine(&engineObj_, 0, nullptr, 0, nullptr, nullptr);
    if (ret != SL_RESULT_SUCCESS) { LOGE("slCreateEngine fail %d", ret); return false; }
    (*engineObj_)->Realize(engineObj_, SL_BOOLEAN_FALSE);
    (*engineObj_)->GetInterface(engineObj_, SL_IID_ENGINE, &engine_);

    // 2. 输出混音
    ret = (*engine_)->CreateOutputMix(engine_, &outputMixObj_, 0, nullptr, nullptr);
    if (ret != SL_RESULT_SUCCESS) { LOGE("CreateOutputMix fail %d", ret); return false; }
    (*outputMixObj_)->Realize(outputMixObj_, SL_BOOLEAN_FALSE);

    // 3. PCM 配置：16bit 44100 双声道 小端
    SLDataLocator_AndroidSimpleBufferQueue locBufQ = {
        SL_DATALOCATOR_ANDROIDSIMPLEBUFFERQUEUE, 1
    };
    SLDataFormat_PCM formatPcm = {
        SL_DATAFORMAT_PCM,
        2,                       // numChannels
        SL_SAMPLERATE_44100,     // samplesPerSec
        SL_PCMSAMPLEFORMAT_FIXED_16,
        SL_PCMSAMPLEFORMAT_FIXED_16,
        SL_SPEAKER_FRONT_LEFT | SL_SPEAKER_FRONT_RIGHT,
        SL_BYTEORDER_LITTLEENDIAN
    };
    SLDataSource src = { &locBufQ, &formatPcm };
    SLDataLocator_OutputMix locOutMix = { SL_DATALOCATOR_OUTPUTMIX, outputMixObj_ };
    SLDataSink sink = { &locOutMix, nullptr };

    const SLInterfaceID ids[] = { SL_IID_ANDROIDSIMPLEBUFFERQUEUE };
    const SLboolean req[] = { SL_BOOLEAN_TRUE };

    ret = (*engine_)->CreateAudioPlayer(engine_, &playerObj_, &src, &sink,
                                        sizeof(ids) / sizeof(ids[0]), ids, req);
    if (ret != SL_RESULT_SUCCESS) { LOGE("CreateAudioPlayer fail %d", ret); return false; }
    (*playerObj_)->Realize(playerObj_, SL_BOOLEAN_FALSE);
    (*playerObj_)->GetInterface(playerObj_, SL_IID_PLAY, &player_);
    (*playerObj_)->GetInterface(playerObj_, SL_IID_ANDROIDSIMPLEBUFFERQUEUE, &bufferQueue_);

    (*bufferQueue_)->RegisterCallback(bufferQueue_, bufferQueueCallback, this);

    inited_ = true;
    paused_ = false;
    (*player_)->SetPlayState(player_, SL_PLAYSTATE_PLAYING);
    enqueueSilence(); // 预热，避免启动无声
    LOGD("OpenSL audio init OK, 44100 16bit stereo");
    return true;
}

void OpenSLAudioPlayer::destroy() {
    if (playerObj_) {
        (*playerObj_)->Destroy(playerObj_);
        playerObj_ = nullptr;
    }
    if (outputMixObj_) {
        (*outputMixObj_)->Destroy(outputMixObj_);
        outputMixObj_ = nullptr;
    }
    if (engineObj_) {
        (*engineObj_)->Destroy(engineObj_);
        engineObj_ = nullptr;
    }
    player_ = nullptr;
    bufferQueue_ = nullptr;
    inited_ = false;
    paused_ = false;
    std::lock_guard<std::mutex> lk(mtx_);
    while (!pending_.empty()) pending_.pop();
}

int OpenSLAudioPlayer::writePcm(const uint8_t* data, uint32_t len) {
    if (!inited_ || data == nullptr || len == 0) return -1;
    // 限制缓冲总量，防止车机内存无限增长
    {
        std::lock_guard<std::mutex> lk(mtx_);
        if (pending_.size() >= 64) {
            // 丢弃最老的数据块（简单拥塞控制）
            pending_.pop();
        }
        std::vector<uint8_t> block(data, data + len);
        pending_.push(std::move(block));
    }
    enqueueNextBlock();
    return 0;
}

void OpenSLAudioPlayer::pause() {
    if (player_) (*player_)->SetPlayState(player_, SL_PLAYSTATE_PAUSED);
    paused_ = true;
}

void OpenSLAudioPlayer::resume() {
    if (player_) (*player_)->SetPlayState(player_, SL_PLAYSTATE_PLAYING);
    paused_ = false;
    enqueueNextBlock();
}

void OpenSLAudioPlayer::bufferQueueCallback(SLAndroidSimpleBufferQueueItf bq, void* context) {
    auto* self = static_cast<OpenSLAudioPlayer*>(context);
    self->enqueueNextBlock();
}

void OpenSLAudioPlayer::enqueueNextBlock() {
    if (!inited_ || bufferQueue_ == nullptr) return;
    std::vector<uint8_t> block;
    {
        std::lock_guard<std::mutex> lk(mtx_);
        if (!pending_.empty()) {
            block = std::move(pending_.front());
            pending_.pop();
        }
    }
    if (!block.empty()) {
        (*bufferQueue_)->Enqueue(bufferQueue_, block.data(), block.size());
    } else {
        enqueueSilence();
    }
}

void OpenSLAudioPlayer::enqueueSilence() {
    if (bufferQueue_ == nullptr) return;
    (*bufferQueue_)->Enqueue(bufferQueue_, silence_, sizeof(silence_));
}
