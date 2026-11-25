#pragma once

#include <atomic>
#include <cstdint>
#include <memory>
#include <pthread.h>
#include <string>
#include <vector>

#include "ring_buffer.h"

namespace bitperfect {

struct UsbAudioAltSetting {
    uint8_t interfaceNumber = 0;
    uint8_t altSetting = 0;
    uint8_t uacVersion = 1;       // 1 = UAC 1.0, 2 = UAC 2.0
    uint8_t acInterface = 0;
    uint8_t clockSourceId = 0;    // UAC 2.0 Clock Source ID
    uint8_t endpointAddress = 0;  // Isochronous OUT endpoint
    uint16_t maxPacketSize = 0;
    uint8_t interval = 1;         // bInterval
    uint8_t channels = 2;
    uint8_t subframeSize = 2;     // bytes per sample slot (2, 3, or 4)
    uint8_t bitResolution = 16;   // valid bits (16, 24, 32)
    std::vector<uint32_t> sampleRates; // empty if continuous/UAC2 clock-driven
};

/**
 * Direct Userspace USB Audio Class (UAC 1.0 & 2.0) Driver over Linux usbdevfs.
 * Bypasses Android's AudioFlinger / AudioMixer completely to deliver bit-exact
 * PCM samples directly to an external USB DAC.
 */
class UsbAudioDriver {
public:
    UsbAudioDriver();
    ~UsbAudioDriver();

    bool open(int fd, const uint8_t* rawDescriptors, size_t descLen, bool isHighSpeed);
    void close();

    bool configure(uint32_t sampleRate, uint8_t bitDepth, uint8_t channels);
    bool start();
    void stop();

    size_t writePcm(const uint8_t* data, size_t len);
    int getBufferFillPercent() const;
    std::string getDeviceSummary() const;
    bool isBitPerfectActive() const { return streaming_.load(std::memory_order_acquire); }

private:
    static void* streamingThreadEntry(void* arg);
    void streamingLoop();

    bool parseDescriptors(const uint8_t* desc, size_t len);
    bool claimInterface(uint8_t iface);
    bool releaseInterface(uint8_t iface);
    bool setInterface(uint8_t iface, uint8_t alt);
    bool setSampleRate(const UsbAudioAltSetting& setting, uint32_t sampleRate);

    int fd_ = -1;
    bool isHighSpeed_ = true;
    std::vector<UsbAudioAltSetting> altSettings_;
    UsbAudioAltSetting activeSetting_;
    bool hasActiveSetting_ = false;
    uint32_t activeSampleRate_ = 44100;

    std::vector<uint8_t> claimedInterfaces_;
    std::unique_ptr<RingBuffer> ringBuffer_;

    std::atomic<bool> streaming_{false};
    std::atomic<bool> stopRequested_{false};
    pthread_t streamThread_{};
    bool threadCreated_ = false;
};

} // namespace bitperfect
