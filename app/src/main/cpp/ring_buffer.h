#pragma once

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <vector>

namespace bitperfect {

/**
 * Lock-free Single-Producer Single-Consumer (SPSC) byte ring buffer.
 * Used to pass bit-exact PCM frames from the Kotlin/MediaCodec/FLAC decoder
 * thread to the real-time USB isochronous / audio output thread without locks
 * or heap allocations on the audio thread.
 */
class RingBuffer {
public:
    explicit RingBuffer(size_t capacityBytes)
        : buffer_(capacityBytes), capacity_(capacityBytes) {}

    void reset() {
        readPos_.store(0, std::memory_order_release);
        writePos_.store(0, std::memory_order_release);
    }

    size_t capacity() const { return capacity_; }

    size_t availableRead() const {
        const size_t w = writePos_.load(std::memory_order_acquire);
        const size_t r = readPos_.load(std::memory_order_acquire);
        return (w >= r) ? (w - r) : (capacity_ - r + w);
    }

    size_t availableWrite() const {
        // Leave 1 byte sentinel to distinguish full vs empty
        return capacity_ - availableRead() - 1;
    }

    size_t write(const uint8_t* data, size_t len) {
        const size_t avail = availableWrite();
        const size_t toWrite = (len < avail) ? len : avail;
        if (toWrite == 0) return 0;

        const size_t w = writePos_.load(std::memory_order_relaxed);
        const size_t firstChunk = (capacity_ - w < toWrite) ? (capacity_ - w) : toWrite;
        std::memcpy(buffer_.data() + w, data, firstChunk);

        const size_t secondChunk = toWrite - firstChunk;
        if (secondChunk > 0) {
            std::memcpy(buffer_.data(), data + firstChunk, secondChunk);
        }

        writePos_.store((w + toWrite) % capacity_, std::memory_order_release);
        return toWrite;
    }

    size_t read(uint8_t* out, size_t len) {
        const size_t avail = availableRead();
        const size_t toRead = (len < avail) ? len : avail;
        if (toRead == 0) return 0;

        const size_t r = readPos_.load(std::memory_order_relaxed);
        const size_t firstChunk = (capacity_ - r < toRead) ? (capacity_ - r) : toRead;
        std::memcpy(out, buffer_.data() + r, firstChunk);

        const size_t secondChunk = toRead - firstChunk;
        if (secondChunk > 0) {
            std::memcpy(out + firstChunk, buffer_.data(), secondChunk);
        }

        readPos_.store((r + toRead) % capacity_, std::memory_order_release);
        return toRead;
    }

private:
    std::vector<uint8_t> buffer_;
    const size_t capacity_;
    alignas(64) std::atomic<size_t> readPos_{0};
    alignas(64) std::atomic<size_t> writePos_{0};
};

} // namespace bitperfect
