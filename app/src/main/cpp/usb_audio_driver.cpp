#include "usb_audio_driver.h"

#include <android/log.h>
#include <cerrno>
#include <cstring>
#include <linux/usbdevice_fs.h>
#include <linux/usb/ch9.h>
#include <sstream>
#include <sys/ioctl.h>
#include <sys/resource.h>
#include <unistd.h>

#define LOG_TAG "BitPerfectUSB"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace bitperfect {

namespace {
constexpr uint8_t USB_CLASS_AUDIO = 0x01;
constexpr uint8_t USB_SUBCLASS_AUDIOCONTROL = 0x01;
constexpr uint8_t USB_SUBCLASS_AUDIOSTREAMING = 0x02;
constexpr uint8_t USB_DT_CS_INTERFACE = 0x24;
constexpr uint8_t USB_DT_CS_ENDPOINT = 0x25;

// UAC 1.0 / 2.0 CS_INTERFACE subtypes
constexpr uint8_t UAC_AS_GENERAL = 0x01;
constexpr uint8_t UAC_FORMAT_TYPE = 0x02;
constexpr uint8_t UAC2_CLOCK_SOURCE = 0x0A;

constexpr size_t RING_BUFFER_BYTES = 2 * 1024 * 1024; // 2 MB PCM ring buffer
constexpr int NUM_ISO_URBS = 4;
constexpr int PACKETS_PER_URB = 8;
} // namespace

UsbAudioDriver::UsbAudioDriver()
    : ringBuffer_(std::make_unique<RingBuffer>(RING_BUFFER_BYTES)) {}

UsbAudioDriver::~UsbAudioDriver() {
    close();
}

bool UsbAudioDriver::open(int fd, const uint8_t* rawDescriptors, size_t descLen, bool isHighSpeed) {
    close();
    if (fd < 0 || rawDescriptors == nullptr || descLen == 0) {
        LOGE("Invalid fd or descriptor buffer");
        return false;
    }

    fd_ = fd;
    isHighSpeed_ = isHighSpeed;
    altSettings_.clear();
    claimedInterfaces_.clear();
    ringBuffer_->reset();

    if (!parseDescriptors(rawDescriptors, descLen)) {
        LOGE("No compatible USB Audio Streaming OUT interface found");
        fd_ = -1;
        return false;
    }

    LOGI("Parsed %zu USB Audio Streaming OUT alternate settings", altSettings_.size());
    return true;
}

void UsbAudioDriver::close() {
    stop();
    for (uint8_t iface : claimedInterfaces_) {
        releaseInterface(iface);
    }
    claimedInterfaces_.clear();
    altSettings_.clear();
    hasActiveSetting_ = false;
    fd_ = -1;
}

bool UsbAudioDriver::parseDescriptors(const uint8_t* desc, size_t len) {
    size_t offset = 0;

    uint8_t currentIface = 0;
    uint8_t currentAlt = 0;
    uint8_t currentClass = 0;
    uint8_t currentSubClass = 0;
    uint8_t currentProtocol = 0;

    uint8_t acInterface = 0;
    uint8_t firstClockSourceId = 0;

    UsbAudioAltSetting pending{};
    bool inAudioStreaming = false;
    bool hasFormat = false;
    bool hasEndpoint = false;

    auto commitPending = [&]() {
        if (inAudioStreaming && hasFormat && hasEndpoint && pending.endpointAddress != 0) {
            altSettings_.push_back(pending);
        }
        hasFormat = false;
        hasEndpoint = false;
    };

    while (offset + 2 <= len) {
        uint8_t bLength = desc[offset];
        uint8_t bType = desc[offset + 1];
        if (bLength < 2 || offset + bLength > len) {
            break;
        }

        if (bType == USB_DT_INTERFACE && bLength >= 9) {
            commitPending();
            currentIface = desc[offset + 2];
            currentAlt = desc[offset + 3];
            currentClass = desc[offset + 5];
            currentSubClass = desc[offset + 6];
            currentProtocol = desc[offset + 7];

            if (currentClass == USB_CLASS_AUDIO && currentSubClass == USB_SUBCLASS_AUDIOCONTROL) {
                acInterface = currentIface;
                inAudioStreaming = false;
            } else if (currentClass == USB_CLASS_AUDIO && currentSubClass == USB_SUBCLASS_AUDIOSTREAMING) {
                inAudioStreaming = true;
                pending = UsbAudioAltSetting{};
                pending.interfaceNumber = currentIface;
                pending.altSetting = currentAlt;
                pending.uacVersion = (currentProtocol == 0x20) ? 2 : 1;
                pending.acInterface = acInterface;
                pending.clockSourceId = firstClockSourceId;
            } else {
                inAudioStreaming = false;
            }
        } else if (bType == USB_DT_CS_INTERFACE && bLength >= 4) {
            uint8_t subtype = desc[offset + 2];
            if (currentClass == USB_CLASS_AUDIO && currentSubClass == USB_SUBCLASS_AUDIOCONTROL) {
                if (subtype == UAC2_CLOCK_SOURCE && bLength >= 8 && firstClockSourceId == 0) {
                    firstClockSourceId = desc[offset + 3];
                }
            } else if (inAudioStreaming) {
                if (subtype == UAC_AS_GENERAL && pending.uacVersion == 2 && bLength >= 16) {
                    // UAC 2.0 AS_GENERAL: bNrChannels at offset+10
                    pending.channels = desc[offset + 10];
                } else if (subtype == UAC_FORMAT_TYPE) {
                    if (pending.uacVersion == 1 && bLength >= 8) {
                        uint8_t formatType = desc[offset + 3];
                        if (formatType == 1) { // FORMAT_TYPE_I
                            pending.channels = desc[offset + 4];
                            pending.subframeSize = desc[offset + 5];
                            pending.bitResolution = desc[offset + 6];
                            uint8_t samFreqType = desc[offset + 7];
                            for (uint8_t i = 0; i < samFreqType && offset + 8 + (i + 1) * 3 <= offset + bLength; ++i) {
                                size_t p = offset + 8 + i * 3;
                                uint32_t sr = static_cast<uint32_t>(desc[p]) |
                                              (static_cast<uint32_t>(desc[p + 1]) << 8) |
                                              (static_cast<uint32_t>(desc[p + 2]) << 16);
                                pending.sampleRates.push_back(sr);
                            }
                            hasFormat = true;
                        }
                    } else if (pending.uacVersion == 2 && bLength >= 6) {
                        uint8_t formatType = desc[offset + 3];
                        if (formatType == 1) { // FORMAT_TYPE_I
                            pending.subframeSize = desc[offset + 4];
                            pending.bitResolution = desc[offset + 5];
                            hasFormat = true;
                        }
                    }
                }
            }
        } else if (bType == USB_DT_ENDPOINT && bLength >= 7 && inAudioStreaming) {
            uint8_t epAddr = desc[offset + 2];
            uint8_t bmAttr = desc[offset + 3];
            uint16_t maxPkt = static_cast<uint16_t>(desc[offset + 4]) |
                              (static_cast<uint16_t>(desc[offset + 5]) << 8);
            uint8_t interval = desc[offset + 6];

            // Isochronous (0x01) and OUT ((epAddr & 0x80) == 0)
            if ((bmAttr & 0x03) == 0x01 && (epAddr & 0x80) == 0) {
                pending.endpointAddress = epAddr;
                // Mask transaction multiplier bits (bits 10..0)
                pending.maxPacketSize = maxPkt & 0x07FF;
                pending.interval = interval > 0 ? interval : 1;
                hasEndpoint = true;
            }
        }

        offset += bLength;
    }

    commitPending();
    return !altSettings_.empty();
}

bool UsbAudioDriver::claimInterface(uint8_t iface) {
    for (uint8_t claimed : claimedInterfaces_) {
        if (claimed == iface) return true;
    }

    // Detach kernel driver (snd-usb-audio) if attached
    struct usbdevfs_ioctl disconnectCmd{};
    disconnectCmd.ifno = iface;
    disconnectCmd.ioctl_code = USBDEVFS_DISCONNECT;
    disconnectCmd.data = nullptr;
    ioctl(fd_, USBDEVFS_IOCTL, &disconnectCmd);

    int ifaceNum = iface;
    if (ioctl(fd_, USBDEVFS_CLAIMINTERFACE, &ifaceNum) < 0) {
        LOGE("USBDEVFS_CLAIMINTERFACE(%u) failed: %s", iface, strerror(errno));
        return false;
    }
    claimedInterfaces_.push_back(iface);
    return true;
}

bool UsbAudioDriver::releaseInterface(uint8_t iface) {
    int ifaceNum = iface;
    return ioctl(fd_, USBDEVFS_RELEASEINTERFACE, &ifaceNum) >= 0;
}

bool UsbAudioDriver::setInterface(uint8_t iface, uint8_t alt) {
    struct usbdevfs_setinterface setIf{};
    setIf.interface = iface;
    setIf.altsetting = alt;
    if (ioctl(fd_, USBDEVFS_SETINTERFACE, &setIf) < 0) {
        LOGE("USBDEVFS_SETINTERFACE(iface=%u, alt=%u) failed: %s", iface, alt, strerror(errno));
        return false;
    }
    return true;
}

bool UsbAudioDriver::setSampleRate(const UsbAudioAltSetting& setting, uint32_t sampleRate) {
    if (setting.uacVersion == 2 && setting.clockSourceId != 0) {
        uint8_t data[4] = {
            static_cast<uint8_t>(sampleRate & 0xFF),
            static_cast<uint8_t>((sampleRate >> 8) & 0xFF),
            static_cast<uint8_t>((sampleRate >> 16) & 0xFF),
            static_cast<uint8_t>((sampleRate >> 24) & 0xFF),
        };
        struct usbdevfs_ctrltransfer ctrl{};
        ctrl.bRequestType = 0x21; // Host-to-device, Class, Interface
        ctrl.bRequest = 0x01;     // CUR
        ctrl.wValue = 0x0100;     // CS_SAM_FREQ_CONTROL << 8
        ctrl.wIndex = (static_cast<uint16_t>(setting.clockSourceId) << 8) | setting.acInterface;
        ctrl.wLength = 4;
        ctrl.timeout = 1000;
        ctrl.data = data;

        if (ioctl(fd_, USBDEVFS_CONTROL, &ctrl) < 0) {
            LOGW("UAC2 clock setSampleRate(%u) warning: %s", sampleRate, strerror(errno));
            return false;
        }
        return true;
    }

    // UAC 1.0 endpoint sample rate control
    uint8_t data[3] = {
        static_cast<uint8_t>(sampleRate & 0xFF),
        static_cast<uint8_t>((sampleRate >> 8) & 0xFF),
        static_cast<uint8_t>((sampleRate >> 16) & 0xFF),
    };
    struct usbdevfs_ctrltransfer ctrl{};
    ctrl.bRequestType = 0x22; // Host-to-device, Class, Endpoint
    ctrl.bRequest = 0x01;     // SET_CUR
    ctrl.wValue = 0x0100;     // SAMPLING_FREQ_CONTROL << 8
    ctrl.wIndex = setting.endpointAddress;
    ctrl.wLength = 3;
    ctrl.timeout = 1000;
    ctrl.data = data;

    if (ioctl(fd_, USBDEVFS_CONTROL, &ctrl) < 0) {
        LOGW("UAC1 endpoint setSampleRate(%u) warning: %s", sampleRate, strerror(errno));
        return false;
    }
    return true;
}

bool UsbAudioDriver::configure(uint32_t sampleRate, uint8_t bitDepth, uint8_t channels) {
    if (fd_ < 0 || altSettings_.empty()) return false;

    const bool wasStreaming = streaming_.load(std::memory_order_acquire);
    if (wasStreaming) {
        stop();
    }

    const UsbAudioAltSetting* best = nullptr;
    int bestScore = -1;

    for (const auto& alt : altSettings_) {
        if (alt.channels != channels) continue;
        int score = 0;
        if (alt.bitResolution == bitDepth) {
            score += 100;
        } else if (alt.bitResolution > bitDepth) {
            // Bit-exact zero-padded container (e.g., 16-bit inside 24/32-bit slot)
            score += 50;
        } else {
            continue;
        }

        if (alt.sampleRates.empty()) {
            score += 20;
        } else {
            bool foundSr = false;
            for (uint32_t sr : alt.sampleRates) {
                if (sr == sampleRate) {
                    foundSr = true;
                    break;
                }
            }
            if (foundSr) score += 40;
        }

        if (score > bestScore) {
            bestScore = score;
            best = &alt;
        }
    }

    if (best == nullptr) {
        LOGE("No matching USB alt setting for %u Hz / %u-bit / %u ch", sampleRate, bitDepth, channels);
        return false;
    }

    if (!claimInterface(best->acInterface) || !claimInterface(best->interfaceNumber)) {
        return false;
    }

    if (!setInterface(best->interfaceNumber, best->altSetting)) {
        return false;
    }

    setSampleRate(*best, sampleRate);

    activeSetting_ = *best;
    activeSampleRate_ = sampleRate;
    hasActiveSetting_ = true;
    ringBuffer_->reset();

    LOGI("Configured USB DAC: iface=%u alt=%u ep=0x%02x %uHz %u-bit (%u-byte slot) %uch",
         activeSetting_.interfaceNumber, activeSetting_.altSetting,
         activeSetting_.endpointAddress, activeSampleRate_,
         activeSetting_.bitResolution, activeSetting_.subframeSize,
         activeSetting_.channels);

    return true;
}

bool UsbAudioDriver::start() {
    if (fd_ < 0 || !hasActiveSetting_) return false;
    if (streaming_.load(std::memory_order_acquire)) return true;

    stopRequested_.store(false, std::memory_order_release);
    streaming_.store(true, std::memory_order_release);

    if (pthread_create(&streamThread_, nullptr, &UsbAudioDriver::streamingThreadEntry, this) != 0) {
        LOGE("Failed to create USB streaming thread");
        streaming_.store(false, std::memory_order_release);
        return false;
    }
    threadCreated_ = true;
    return true;
}

void UsbAudioDriver::stop() {
    if (threadCreated_) {
        stopRequested_.store(true, std::memory_order_release);
        pthread_join(streamThread_, nullptr);
        threadCreated_ = false;
    }
    streaming_.store(false, std::memory_order_release);
}

size_t UsbAudioDriver::writePcm(const uint8_t* data, size_t len) {
    if (!ringBuffer_ || data == nullptr || len == 0) return 0;
    return ringBuffer_->write(data, len);
}

int UsbAudioDriver::getBufferFillPercent() const {
    if (!ringBuffer_) return 0;
    return static_cast<int>((ringBuffer_->availableRead() * 100) / ringBuffer_->capacity());
}

std::string UsbAudioDriver::getDeviceSummary() const {
    if (!hasActiveSetting_) return "USB DAC (No configurado)";
    std::ostringstream oss;
    oss << "UAC" << static_cast<int>(activeSetting_.uacVersion)
        << " • " << activeSampleRate_ << " Hz / "
        << static_cast<int>(activeSetting_.bitResolution) << "-bit";
    return oss.str();
}

void* UsbAudioDriver::streamingThreadEntry(void* arg) {
    auto* self = static_cast<UsbAudioDriver*>(arg);
    setpriority(PRIO_PROCESS, 0, -19);
    self->streamingLoop();
    return nullptr;
}

void UsbAudioDriver::streamingLoop() {
    const uint32_t bytesPerFrame = activeSetting_.channels * activeSetting_.subframeSize;
    const uint32_t framesPerSecond = activeSampleRate_;
    const uint32_t subframesPerSecond = isHighSpeed_ ? 8000 : 1000;
    const uint32_t packetInterval = (activeSetting_.interval >= 1 && activeSetting_.interval <= 4)
                                        ? (1u << (activeSetting_.interval - 1))
                                        : 1u;
    const uint32_t packetsPerSec = subframesPerSecond / packetInterval;

    const size_t urbHeaderSize = sizeof(struct usbdevfs_urb) +
                                 PACKETS_PER_URB * sizeof(struct usbdevfs_iso_packet_desc);
    const size_t maxUrbBufferSize = PACKETS_PER_URB * activeSetting_.maxPacketSize;

    std::vector<std::vector<uint8_t>> urbStructs(NUM_ISO_URBS, std::vector<uint8_t>(urbHeaderSize, 0));
    std::vector<std::vector<uint8_t>> urbBuffers(NUM_ISO_URBS, std::vector<uint8_t>(maxUrbBufferSize, 0));

    uint64_t frameAccumulator = 0;

    auto prepareAndSubmitUrb = [&](int idx) -> bool {
        auto* urb = reinterpret_cast<struct usbdevfs_urb*>(urbStructs[idx].data());
        std::memset(urb, 0, urbHeaderSize);
        urb->type = USBDEVFS_URB_TYPE_ISO;
        urb->endpoint = activeSetting_.endpointAddress;
        urb->flags = USBDEVFS_URB_ISO_ASAP;
        urb->buffer = urbBuffers[idx].data();
        urb->number_of_packets = PACKETS_PER_URB;

        size_t totalBytes = 0;
        for (int p = 0; p < PACKETS_PER_URB; ++p) {
            frameAccumulator += framesPerSecond;
            uint32_t framesThisPacket = static_cast<uint32_t>(frameAccumulator / packetsPerSec);
            frameAccumulator %= packetsPerSec;

            uint32_t packetBytes = framesThisPacket * bytesPerFrame;
            if (packetBytes > activeSetting_.maxPacketSize) {
                packetBytes = activeSetting_.maxPacketSize;
            }

            urb->iso_frame_desc[p].length = packetBytes;
            totalBytes += packetBytes;
        }

        urb->buffer_length = static_cast<int>(totalBytes);

        // Read bit-exact PCM from lock-free ring buffer; pad with digital silence if underrun
        size_t readBytes = ringBuffer_->read(urbBuffers[idx].data(), totalBytes);
        if (readBytes < totalBytes) {
            std::memset(urbBuffers[idx].data() + readBytes, 0, totalBytes - readBytes);
        }

        if (ioctl(fd_, USBDEVFS_SUBMITURB, urb) < 0) {
            LOGE("USBDEVFS_SUBMITURB failed: %s", strerror(errno));
            return false;
        }
        return true;
    };

    int activeUrbs = 0;
    for (int i = 0; i < NUM_ISO_URBS; ++i) {
        if (prepareAndSubmitUrb(i)) {
            ++activeUrbs;
        }
    }

    while (!stopRequested_.load(std::memory_order_acquire) && activeUrbs > 0) {
        struct usbdevfs_urb* reaped = nullptr;
        if (ioctl(fd_, USBDEVFS_REAPURB, &reaped) < 0) {
            if (errno == EINTR || errno == EAGAIN) continue;
            LOGE("USBDEVFS_REAPURB failed: %s", strerror(errno));
            break;
        }

        if (stopRequested_.load(std::memory_order_acquire)) {
            --activeUrbs;
            break;
        }

        int idx = -1;
        for (int i = 0; i < NUM_ISO_URBS; ++i) {
            if (reinterpret_cast<void*>(reaped) == urbStructs[i].data()) {
                idx = i;
                break;
            }
        }
        if (idx >= 0) {
            if (!prepareAndSubmitUrb(idx)) {
                --activeUrbs;
            }
        }
    }

    // Discard any in-flight URBs on stop
    for (int i = 0; i < NUM_ISO_URBS; ++i) {
        ioctl(fd_, USBDEVFS_DISCARDURB, urbStructs[i].data());
    }
    streaming_.store(false, std::memory_order_release);
}

} // namespace bitperfect
