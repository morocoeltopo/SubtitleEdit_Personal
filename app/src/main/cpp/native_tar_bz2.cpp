#include <jni.h>

#include <android/log.h>
#include <bzlib.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <cerrno>
#include <climits>
#include <cstdint>
#include <cstring>
#include <exception>
#include <fcntl.h>
#include <limits>
#include <memory>
#include <mutex>
#include <new>
#include <optional>
#include <string>
#include <sys/stat.h>
#include <time.h>
#include <unordered_map>
#include <utility>
#include <vector>
#include <unistd.h>

extern "C" void bz_internal_error(int error_code) {
    __android_log_assert(
        "libbzip2 internal assertion",
        "SubtitleEditModelArchive",
        "libbzip2 internal error: %d",
        error_code
    );
}

namespace {

constexpr size_t kTarBlockSize = 512;
constexpr size_t kIoBufferSize = 64 * 1024;
constexpr uint64_t kMaxMetadataSize = 1024 * 1024;
constexpr uint64_t kTarOverheadAllowance = 64ULL * 1024ULL * 1024ULL;
constexpr size_t kMaxPathLength = 4096;
constexpr size_t kMaxPathComponentLength = 255;
constexpr uint64_t kProgressIntervalMs = 200;
constexpr char kCancelledResult[] = "__SUBTITLEEDIT_NATIVE_CANCELLED__";

enum class OutcomeKind {
    kOk,
    kCancelled,
    kError,
    kCallbackException,
};

struct Outcome {
    OutcomeKind kind = OutcomeKind::kOk;
    std::string message;

    static Outcome Ok() { return {}; }

    static Outcome Cancelled() {
        return {OutcomeKind::kCancelled, {}};
    }

    static Outcome Error(std::string message) {
        return {OutcomeKind::kError, std::move(message)};
    }

    static Outcome CallbackException() {
        return {OutcomeKind::kCallbackException, {}};
    }

    bool ok() const { return kind == OutcomeKind::kOk; }
};

class UniqueFd {
public:
    UniqueFd() = default;
    explicit UniqueFd(int fd) : fd_(fd) {}
    ~UniqueFd() { reset(); }

    UniqueFd(const UniqueFd&) = delete;
    UniqueFd& operator=(const UniqueFd&) = delete;

    UniqueFd(UniqueFd&& other) noexcept : fd_(other.release()) {}

    UniqueFd& operator=(UniqueFd&& other) noexcept {
        if (this != &other) reset(other.release());
        return *this;
    }

    int get() const { return fd_; }
    explicit operator bool() const { return fd_ >= 0; }

    int release() {
        const int fd = fd_;
        fd_ = -1;
        return fd;
    }

    void reset(int fd = -1) {
        if (fd_ >= 0) close(fd_);
        fd_ = fd;
    }

private:
    int fd_ = -1;
};

std::string ErrnoMessage(const char* prefix) {
    const int error_number = errno;
    std::string message(prefix);
    message.append(": ");
    message.append(std::strerror(error_number));
    return message;
}

struct TaskContext {
    std::atomic<bool> cancelled{false};
    std::atomic<bool> running{false};
};

std::mutex g_task_mutex;
std::unordered_map<jlong, std::shared_ptr<TaskContext>> g_tasks;
uint64_t g_next_task_id = 1;

std::shared_ptr<TaskContext> FindTask(jlong task_id) {
    std::lock_guard<std::mutex> lock(g_task_mutex);
    const auto iterator = g_tasks.find(task_id);
    return iterator == g_tasks.end() ? nullptr : iterator->second;
}

jlong CreateTask() {
    auto task = std::make_shared<TaskContext>();
    std::lock_guard<std::mutex> lock(g_task_mutex);
    for (;;) {
        const jlong task_id = static_cast<jlong>(g_next_task_id);
        ++g_next_task_id;
        if (g_next_task_id > static_cast<uint64_t>(std::numeric_limits<jlong>::max())) {
            g_next_task_id = 1;
        }
        if (g_tasks.emplace(task_id, task).second) return task_id;
    }
}

void DestroyTask(jlong task_id) {
    std::lock_guard<std::mutex> lock(g_task_mutex);
    g_tasks.erase(task_id);
}

class RunningGuard {
public:
    explicit RunningGuard(std::shared_ptr<TaskContext> task) : task_(std::move(task)) {}
    ~RunningGuard() { task_->running.store(false, std::memory_order_release); }

private:
    std::shared_ptr<TaskContext> task_;
};

class UtfChars {
public:
    UtfChars(JNIEnv* env, jstring value) : env_(env), value_(value) {
        chars_ = value == nullptr ? nullptr : env_->GetStringUTFChars(value_, nullptr);
    }

    ~UtfChars() {
        if (chars_ != nullptr) env_->ReleaseStringUTFChars(value_, chars_);
    }

    const char* get() const { return chars_; }

private:
    JNIEnv* env_;
    jstring value_;
    const char* chars_ = nullptr;
};

class CallbackBridge {
public:
    CallbackBridge(JNIEnv* env, jobject callback) : env_(env), callback_(callback) {
        jclass callback_class = env_->GetObjectClass(callback_);
        if (callback_class == nullptr) return;
        should_extract_ = env_->GetMethodID(
            callback_class,
            "shouldExtract",
            "(Ljava/lang/String;)Z"
        );
        if (should_extract_ == nullptr || env_->ExceptionCheck()) {
            env_->DeleteLocalRef(callback_class);
            return;
        }
        on_progress_ = env_->GetMethodID(callback_class, "onProgress", "(JJ)V");
        env_->DeleteLocalRef(callback_class);
    }

    bool valid() const {
        return should_extract_ != nullptr && on_progress_ != nullptr;
    }

    bool ShouldExtract(const std::string& file_name, bool* should_extract) {
        const bool is_ascii = std::all_of(
            file_name.begin(),
            file_name.end(),
            [](unsigned char value) { return value < 0x80U; }
        );
        if (!is_ascii) {
            *should_extract = false;
            return true;
        }
        jstring java_name = env_->NewStringUTF(file_name.c_str());
        if (java_name == nullptr) return false;
        const jboolean result = env_->CallBooleanMethod(callback_, should_extract_, java_name);
        env_->DeleteLocalRef(java_name);
        if (env_->ExceptionCheck()) return false;
        *should_extract = result == JNI_TRUE;
        return true;
    }

    bool OnProgress(uint64_t compressed_bytes, uint64_t total_bytes) {
        env_->CallVoidMethod(
            callback_,
            on_progress_,
            static_cast<jlong>(compressed_bytes),
            static_cast<jlong>(total_bytes)
        );
        return !env_->ExceptionCheck();
    }

private:
    JNIEnv* env_;
    jobject callback_;
    jmethodID should_extract_ = nullptr;
    jmethodID on_progress_ = nullptr;
};

enum class ReadKind {
    kData,
    kEnd,
    kCancelled,
    kError,
};

const char* Bzip2ErrorName(int error_code) {
    switch (error_code) {
        case BZ_SEQUENCE_ERROR: return "调用顺序错误";
        case BZ_PARAM_ERROR: return "参数错误";
        case BZ_MEM_ERROR: return "内存不足";
        case BZ_DATA_ERROR: return "数据或 CRC 校验失败";
        case BZ_DATA_ERROR_MAGIC: return "文件头不是有效的 bzip2 数据";
        case BZ_CONFIG_ERROR: return "原生 bzip2 配置不兼容";
        default: return "未知错误";
    }
}

class Bzip2Reader {
public:
    Bzip2Reader(
        int archive_fd,
        uint64_t compressed_size,
        uint64_t max_output_bytes,
        std::shared_ptr<TaskContext> task
    ) :
        archive_fd_(archive_fd),
        compressed_size_(compressed_size),
        max_output_bytes_(max_output_bytes),
        task_(std::move(task)) {}

    ~Bzip2Reader() {
        if (stream_initialized_) BZ2_bzDecompressEnd(&stream_);
    }

    ReadKind ReadSome(
        uint8_t* output,
        size_t output_capacity,
        size_t* output_size,
        std::string* error
    ) {
        *output_size = 0;
        if (output_capacity == 0 || output_capacity > std::numeric_limits<unsigned int>::max()) {
            *error = "原生解压读取缓冲区大小无效";
            return ReadKind::kError;
        }

        while (true) {
            if (task_->cancelled.load(std::memory_order_acquire)) return ReadKind::kCancelled;
            if (finished_) return ReadKind::kEnd;

            if (!stream_initialized_) {
                if (stream_.avail_in == 0) {
                    if (compressed_bytes_read_ == compressed_size_) {
                        finished_ = true;
                        return ReadKind::kEnd;
                    }
                    if (!FillInput(error)) return ReadKind::kError;
                }
                if (!StartStream(error)) return ReadKind::kError;
            }

            if (stream_.avail_in == 0 && compressed_bytes_read_ < compressed_size_) {
                if (!FillInput(error)) return ReadKind::kError;
            }

            stream_.next_out = reinterpret_cast<char*>(output);
            stream_.avail_out = static_cast<unsigned int>(output_capacity);
            const unsigned int input_before = stream_.avail_in;
            const int result = BZ2_bzDecompress(&stream_);
            const size_t produced = output_capacity - stream_.avail_out;

            if (output_bytes_ > max_output_bytes_ ||
                produced > max_output_bytes_ - output_bytes_) {
                *error = "压缩包解压内容超过安全限制";
                return ReadKind::kError;
            }
            output_bytes_ += produced;

            if (result == BZ_STREAM_END) {
                char* remaining_input = stream_.next_in;
                const unsigned int remaining_size = stream_.avail_in;
                const int end_result = BZ2_bzDecompressEnd(&stream_);
                std::memset(&stream_, 0, sizeof(stream_));
                stream_.next_in = remaining_input;
                stream_.avail_in = remaining_size;
                stream_initialized_ = false;
                if (end_result != BZ_OK) {
                    *error = "关闭 bzip2 解压流失败";
                    return ReadKind::kError;
                }
                if (compressed_bytes_read_ == compressed_size_ && remaining_size == 0) {
                    finished_ = true;
                }
                if (produced > 0) {
                    *output_size = produced;
                    return ReadKind::kData;
                }
                if (finished_) return ReadKind::kEnd;
                continue;
            }

            if (result != BZ_OK) {
                *error = std::string("bzip2 解压失败：") + Bzip2ErrorName(result);
                return ReadKind::kError;
            }

            if (produced > 0) {
                *output_size = produced;
                return ReadKind::kData;
            }

            if (stream_.avail_in == 0) {
                if (compressed_bytes_read_ == compressed_size_) {
                    *error = "bzip2 压缩包不完整或被截断";
                    return ReadKind::kError;
                }
                continue;
            }

            if (stream_.avail_in == input_before) {
                *error = "bzip2 解压器未能继续处理数据";
                return ReadKind::kError;
            }
        }
    }

    uint64_t compressed_bytes_consumed() const {
        const uint64_t buffered = stream_.avail_in;
        return compressed_bytes_read_ >= buffered ? compressed_bytes_read_ - buffered : 0;
    }

private:
    bool FillInput(std::string* error) {
        if (stream_.avail_in != 0) return true;
        if (compressed_bytes_read_ >= compressed_size_) return true;

        const uint64_t remaining = compressed_size_ - compressed_bytes_read_;
        const size_t requested = static_cast<size_t>(
            std::min<uint64_t>(remaining, input_buffer_.size())
        );
        ssize_t bytes_read;
        do {
            bytes_read = read(archive_fd_, input_buffer_.data(), requested);
        } while (bytes_read < 0 && errno == EINTR);

        if (bytes_read < 0) {
            *error = ErrnoMessage("读取模型压缩包失败");
            return false;
        }
        if (bytes_read == 0) {
            *error = "模型压缩包在预期位置前结束";
            return false;
        }

        compressed_bytes_read_ += static_cast<uint64_t>(bytes_read);
        stream_.next_in = input_buffer_.data();
        stream_.avail_in = static_cast<unsigned int>(bytes_read);
        return true;
    }

    bool StartStream(std::string* error) {
        char* pending_input = stream_.next_in;
        const unsigned int pending_size = stream_.avail_in;
        std::memset(&stream_, 0, sizeof(stream_));
        const int result = BZ2_bzDecompressInit(&stream_, 0, 0);
        if (result != BZ_OK) {
            *error = std::string("初始化 bzip2 解压器失败：") + Bzip2ErrorName(result);
            return false;
        }
        stream_.next_in = pending_input;
        stream_.avail_in = pending_size;
        stream_initialized_ = true;
        return true;
    }

    int archive_fd_;
    uint64_t compressed_size_;
    uint64_t max_output_bytes_;
    std::shared_ptr<TaskContext> task_;
    std::array<char, kIoBufferSize> input_buffer_{};
    bz_stream stream_{};
    uint64_t compressed_bytes_read_ = 0;
    uint64_t output_bytes_ = 0;
    bool stream_initialized_ = false;
    bool finished_ = false;
};

uint64_t MonotonicMilliseconds() {
    timespec current_time{};
    if (clock_gettime(CLOCK_MONOTONIC, &current_time) != 0) return 0;
    return static_cast<uint64_t>(current_time.tv_sec) * 1000ULL +
        static_cast<uint64_t>(current_time.tv_nsec) / 1000000ULL;
}

class ProgressReporter {
public:
    ProgressReporter(
        CallbackBridge* callback,
        const Bzip2Reader* reader,
        uint64_t total_bytes
    ) : callback_(callback), reader_(reader), total_bytes_(total_bytes) {}

    Outcome Start() {
        last_report_at_ = MonotonicMilliseconds();
        last_reported_bytes_ = 0;
        return callback_->OnProgress(0, total_bytes_)
            ? Outcome::Ok()
            : Outcome::CallbackException();
    }

    Outcome MaybeReport(bool force = false) {
        const uint64_t now = MonotonicMilliseconds();
        if (!force && now >= last_report_at_ && now - last_report_at_ < kProgressIntervalMs) {
            return Outcome::Ok();
        }

        const uint64_t compressed_bytes = force
            ? total_bytes_
            : std::min(reader_->compressed_bytes_consumed(), total_bytes_);
        if (force || compressed_bytes != last_reported_bytes_) {
            if (!callback_->OnProgress(compressed_bytes, total_bytes_)) {
                return Outcome::CallbackException();
            }
            last_reported_bytes_ = compressed_bytes;
        }
        last_report_at_ = now;
        return Outcome::Ok();
    }

private:
    CallbackBridge* callback_;
    const Bzip2Reader* reader_;
    uint64_t total_bytes_;
    uint64_t last_report_at_ = 0;
    uint64_t last_reported_bytes_ = 0;
};

class TarStream {
public:
    TarStream(
        Bzip2Reader* reader,
        ProgressReporter* progress,
        std::shared_ptr<TaskContext> task
    ) : reader_(reader), progress_(progress), task_(std::move(task)) {}

    Outcome ReadExactly(uint8_t* destination, size_t size) {
        size_t offset = 0;
        while (offset < size) {
            size_t bytes_read = 0;
            std::string error;
            const ReadKind result = reader_->ReadSome(
                destination + offset,
                size - offset,
                &bytes_read,
                &error
            );
            if (result == ReadKind::kCancelled) return Outcome::Cancelled();
            if (result == ReadKind::kError) return Outcome::Error(std::move(error));
            if (result == ReadKind::kEnd) {
                return Outcome::Error("TAR 数据提前结束，压缩包可能不完整");
            }
            offset += bytes_read;
            Outcome progress_result = progress_->MaybeReport();
            if (!progress_result.ok()) return progress_result;
        }
        return Outcome::Ok();
    }

    Outcome ReadPayload(uint64_t size, int output_fd, std::string* capture = nullptr) {
        if (capture != nullptr) {
            capture->clear();
            capture->reserve(static_cast<size_t>(size));
        }

        uint64_t remaining = size;
        while (remaining > 0) {
            const size_t chunk_size = static_cast<size_t>(
                std::min<uint64_t>(remaining, buffer_.size())
            );
            Outcome read_result = ReadExactly(buffer_.data(), chunk_size);
            if (!read_result.ok()) return read_result;

            if (capture != nullptr) {
                capture->append(reinterpret_cast<const char*>(buffer_.data()), chunk_size);
            }
            if (output_fd >= 0) {
                Outcome write_result = WriteAll(output_fd, buffer_.data(), chunk_size);
                if (!write_result.ok()) return write_result;
            }
            remaining -= chunk_size;
        }

        const uint64_t remainder = size % kTarBlockSize;
        const size_t padding = remainder == 0
            ? 0
            : static_cast<size_t>(kTarBlockSize - remainder);
        return padding == 0 ? Outcome::Ok() : ReadExactly(buffer_.data(), padding);
    }

    Outcome DrainZeroTail() {
        while (true) {
            size_t bytes_read = 0;
            std::string error;
            const ReadKind result = reader_->ReadSome(
                buffer_.data(),
                buffer_.size(),
                &bytes_read,
                &error
            );
            if (result == ReadKind::kCancelled) return Outcome::Cancelled();
            if (result == ReadKind::kError) return Outcome::Error(std::move(error));
            if (result == ReadKind::kEnd) return Outcome::Ok();

            for (size_t index = 0; index < bytes_read; ++index) {
                if (buffer_[index] != 0) {
                    return Outcome::Error("TAR 结束标记后包含非零数据");
                }
            }
            Outcome progress_result = progress_->MaybeReport();
            if (!progress_result.ok()) return progress_result;
        }
    }

private:
    Outcome WriteAll(int output_fd, const uint8_t* data, size_t size) {
        size_t offset = 0;
        while (offset < size) {
            if (task_->cancelled.load(std::memory_order_acquire)) return Outcome::Cancelled();
            ssize_t bytes_written;
            do {
                bytes_written = write(output_fd, data + offset, size - offset);
            } while (bytes_written < 0 && errno == EINTR);
            if (bytes_written < 0) return Outcome::Error(ErrnoMessage("写入模型文件失败"));
            if (bytes_written == 0) return Outcome::Error("写入模型文件时未能继续写入数据");
            offset += static_cast<size_t>(bytes_written);
        }
        return Outcome::Ok();
    }

    Bzip2Reader* reader_;
    ProgressReporter* progress_;
    std::shared_ptr<TaskContext> task_;
    std::array<uint8_t, kIoBufferSize> buffer_{};
};

bool IsZeroBlock(const std::array<uint8_t, kTarBlockSize>& block) {
    return std::all_of(block.begin(), block.end(), [](uint8_t value) { return value == 0; });
}

bool ParseTarNumber(const uint8_t* bytes, size_t length, uint64_t* value) {
    if (length == 0) return false;
    if ((bytes[0] & 0x80U) != 0) {
        if ((bytes[0] & 0x40U) != 0) return false;
        uint64_t parsed = bytes[0] & 0x7FU;
        for (size_t index = 1; index < length; ++index) {
            if (parsed > (std::numeric_limits<uint64_t>::max() >> 8U)) return false;
            parsed = (parsed << 8U) | bytes[index];
        }
        *value = parsed;
        return true;
    }

    size_t index = 0;
    while (index < length && (bytes[index] == 0 || bytes[index] == ' ')) ++index;
    uint64_t parsed = 0;
    bool found_digit = false;
    for (; index < length; ++index) {
        const uint8_t current = bytes[index];
        if (current == 0 || current == ' ') {
            for (size_t tail = index; tail < length; ++tail) {
                if (bytes[tail] != 0 && bytes[tail] != ' ') return false;
            }
            break;
        }
        if (current < '0' || current > '7') return false;
        const uint8_t digit = current - '0';
        if (parsed > (std::numeric_limits<uint64_t>::max() - digit) / 8U) return false;
        parsed = parsed * 8U + digit;
        found_digit = true;
    }
    *value = found_digit ? parsed : 0;
    return true;
}

bool HeaderChecksumIsValid(const std::array<uint8_t, kTarBlockSize>& header) {
    uint64_t stored_checksum = 0;
    if (!ParseTarNumber(header.data() + 148, 8, &stored_checksum)) return false;

    uint64_t unsigned_sum = 0;
    int64_t signed_sum = 0;
    for (size_t index = 0; index < header.size(); ++index) {
        const uint8_t value = index >= 148 && index < 156 ? ' ' : header[index];
        unsigned_sum += value;
        signed_sum += static_cast<int8_t>(value);
    }
    return stored_checksum == unsigned_sum ||
        (signed_sum >= 0 && stored_checksum == static_cast<uint64_t>(signed_sum));
}

std::string HeaderString(const uint8_t* bytes, size_t length) {
    size_t string_length = 0;
    while (string_length < length && bytes[string_length] != 0) ++string_length;
    return std::string(reinterpret_cast<const char*>(bytes), string_length);
}

std::string HeaderPath(const std::array<uint8_t, kTarBlockSize>& header) {
    std::string name = HeaderString(header.data(), 100);
    const bool has_posix_ustar_magic =
        std::memcmp(header.data() + 257, "ustar", 5) == 0 && header[262] == 0;
    if (!has_posix_ustar_magic) return name;

    std::string prefix = HeaderString(header.data() + 345, 155);
    if (prefix.empty()) return name;
    if (name.empty()) return prefix;
    prefix.push_back('/');
    prefix.append(name);
    return prefix;
}

bool ParseDecimalUint64(const std::string& value, uint64_t* parsed) {
    if (value.empty()) return false;
    uint64_t result = 0;
    for (const unsigned char current : value) {
        if (current < '0' || current > '9') return false;
        const uint8_t digit = current - '0';
        if (result > (std::numeric_limits<uint64_t>::max() - digit) / 10U) return false;
        result = result * 10U + digit;
    }
    *parsed = result;
    return true;
}

struct PaxValues {
    bool path_specified = false;
    std::optional<std::string> path;
    bool size_specified = false;
    std::optional<uint64_t> size;
    bool sparse = false;

    void MergeFrom(const PaxValues& other) {
        if (other.path_specified) {
            path_specified = true;
            path = other.path;
        }
        if (other.size_specified) {
            size_specified = true;
            size = other.size;
        }
        sparse = sparse || other.sparse;
    }
};

Outcome ParsePaxRecords(const std::string& data, PaxValues* values) {
    size_t position = 0;
    while (position < data.size()) {
        const size_t space = data.find(' ', position);
        if (space == std::string::npos || space == position) {
            return Outcome::Error("PAX 扩展头格式无效");
        }

        uint64_t record_length = 0;
        if (!ParseDecimalUint64(data.substr(position, space - position), &record_length) ||
            record_length == 0 || record_length > data.size() - position) {
            return Outcome::Error("PAX 扩展头记录长度无效");
        }
        const size_t record_end = position + static_cast<size_t>(record_length);
        if (record_end <= space + 1 || data[record_end - 1] != '\n') {
            return Outcome::Error("PAX 扩展头记录不完整");
        }

        const size_t equals = data.find('=', space + 1);
        if (equals == std::string::npos || equals == space + 1 || equals >= record_end - 1) {
            return Outcome::Error("PAX 扩展头缺少键值");
        }
        const std::string key = data.substr(space + 1, equals - space - 1);
        const std::string value = data.substr(equals + 1, record_end - equals - 2);
        if (key == "path") {
            values->path_specified = true;
            if (value.empty()) {
                values->path.reset();
            } else {
                values->path = value;
            }
        } else if (key == "size") {
            values->size_specified = true;
            if (value.empty()) {
                values->size.reset();
            } else {
                uint64_t parsed_size = 0;
                if (!ParseDecimalUint64(value, &parsed_size)) {
                    return Outcome::Error("PAX 文件大小字段无效");
                }
                values->size = parsed_size;
            }
        } else if (key.rfind("GNU.sparse", 0) == 0 || key == "SCHILY.realsize") {
            // 稀疏元数据按保守策略处理：任何非空稀疏键都会拒绝该条目。
            // 空值不能清除同一 PAX 头或全局头中其他已出现的稀疏键。
            values->sparse = values->sparse || !value.empty();
        }
        position = record_end;
    }
    return Outcome::Ok();
}

std::string TrimLongMetadataName(const std::string& data) {
    const size_t null_position = data.find('\0');
    std::string result = data.substr(0, null_position);
    while (!result.empty() && (result.back() == '\n' || result.back() == '\r')) {
        result.pop_back();
    }
    return result;
}

struct NormalizedPath {
    std::vector<std::string> components;
};

bool IsAsciiLetter(char value) {
    return (value >= 'a' && value <= 'z') || (value >= 'A' && value <= 'Z');
}

Outcome NormalizeArchivePath(
    const std::string& archive_path,
    bool allow_empty,
    NormalizedPath* normalized
) {
    if (archive_path.size() > kMaxPathLength) {
        return Outcome::Error("压缩包中的文件路径过长");
    }

    std::string path = archive_path;
    for (char& current : path) {
        const unsigned char byte = static_cast<unsigned char>(current);
        if (byte == 0 || byte < 0x20U || byte == 0x7FU) {
            return Outcome::Error("压缩包中的文件路径包含控制字符");
        }
        if (current == '\\') current = '/';
    }
    if (!path.empty() && path.front() == '/') {
        return Outcome::Error("压缩包包含绝对路径");
    }

    normalized->components.clear();
    size_t position = 0;
    while (position <= path.size()) {
        const size_t separator = path.find('/', position);
        const size_t end = separator == std::string::npos ? path.size() : separator;
        const std::string component = path.substr(position, end - position);
        if (!component.empty() && component != ".") {
            if (component == "..") return Outcome::Error("压缩包包含路径穿越内容");
            if (component.size() > kMaxPathComponentLength) {
                return Outcome::Error("压缩包中的路径分段过长");
            }
            if (normalized->components.empty() && component.size() >= 2 &&
                IsAsciiLetter(component[0]) && component[1] == ':') {
                return Outcome::Error("压缩包包含 Windows 绝对路径");
            }
            normalized->components.push_back(component);
        }
        if (separator == std::string::npos) break;
        position = separator + 1;
    }

    if (normalized->components.empty() && !allow_empty) {
        return Outcome::Error("压缩包包含空文件路径");
    }
    return Outcome::Ok();
}

Outcome OpenOutputFile(
    int output_root_fd,
    const std::vector<std::string>& components,
    UniqueFd* output_file
) {
    if (components.empty()) return Outcome::Error("模型文件路径为空");

    UniqueFd current_directory(fcntl(output_root_fd, F_DUPFD_CLOEXEC, 0));
    if (!current_directory) return Outcome::Error(ErrnoMessage("复制模型目录句柄失败"));

    for (size_t index = 0; index + 1 < components.size(); ++index) {
        const std::string& component = components[index];
        if (mkdirat(current_directory.get(), component.c_str(), 0755) != 0 && errno != EEXIST) {
            return Outcome::Error(ErrnoMessage("创建模型子目录失败"));
        }
        UniqueFd next_directory(openat(
            current_directory.get(),
            component.c_str(),
            O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW
        ));
        if (!next_directory) {
            return Outcome::Error(ErrnoMessage("打开模型子目录失败"));
        }
        current_directory = std::move(next_directory);
    }

    UniqueFd destination(openat(
        current_directory.get(),
        components.back().c_str(),
        O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC | O_NOFOLLOW,
        0644
    ));
    if (!destination) return Outcome::Error(ErrnoMessage("创建模型文件失败"));

    struct stat destination_stat {};
    if (fstat(destination.get(), &destination_stat) != 0) {
        return Outcome::Error(ErrnoMessage("校验模型文件失败"));
    }
    if (!S_ISREG(destination_stat.st_mode) || destination_stat.st_nlink != 1) {
        return Outcome::Error("模型输出目标不是安全的普通文件");
    }
    *output_file = std::move(destination);
    return Outcome::Ok();
}

class TarExtractor {
public:
    TarExtractor(
        TarStream* stream,
        ProgressReporter* progress,
        CallbackBridge* callback,
        int output_root_fd,
        int max_entries,
        uint64_t max_extracted_bytes
    ) :
        stream_(stream),
        progress_(progress),
        callback_(callback),
        output_root_fd_(output_root_fd),
        max_entries_(max_entries),
        max_extracted_bytes_(max_extracted_bytes) {}

    Outcome Extract() {
        int zero_block_count = 0;
        while (true) {
            std::array<uint8_t, kTarBlockSize> header{};
            Outcome read_header = stream_->ReadExactly(header.data(), header.size());
            if (!read_header.ok()) return read_header;

            if (IsZeroBlock(header)) {
                ++zero_block_count;
                if (zero_block_count >= 2) {
                    Outcome drain_result = stream_->DrainZeroTail();
                    if (!drain_result.ok()) return drain_result;
                    return progress_->MaybeReport(true);
                }
                continue;
            }
            zero_block_count = 0;

            if (!HeaderChecksumIsValid(header)) {
                return Outcome::Error("TAR 文件头校验失败");
            }
            if (entry_count_ >= static_cast<uint64_t>(max_entries_)) {
                return Outcome::Error("压缩包文件数量超过安全限制");
            }
            ++entry_count_;

            uint64_t header_size = 0;
            if (!ParseTarNumber(header.data() + 124, 12, &header_size)) {
                return Outcome::Error("TAR 文件大小字段无效");
            }
            const char type = static_cast<char>(header[156]);

            if (type == 'x' || type == 'g' || type == 'L' || type == 'K') {
                if (header_size > kMaxMetadataSize) {
                    return Outcome::Error("TAR 扩展元数据过大");
                }
                Outcome account_result = AccountBytes(header_size);
                if (!account_result.ok()) return account_result;

                std::string metadata;
                Outcome metadata_result = stream_->ReadPayload(header_size, -1, &metadata);
                if (!metadata_result.ok()) return metadata_result;

                if (type == 'L') {
                    pending_long_name_ = TrimLongMetadataName(metadata);
                } else if (type != 'K') {
                    PaxValues parsed_values;
                    Outcome pax_result = ParsePaxRecords(metadata, &parsed_values);
                    if (!pax_result.ok()) return pax_result;
                    if (type == 'g') {
                        global_pax_.MergeFrom(parsed_values);
                    } else {
                        local_pax_.MergeFrom(parsed_values);
                    }
                }
                continue;
            }

            PaxValues effective_pax = global_pax_;
            effective_pax.MergeFrom(local_pax_);
            std::string entry_path = pending_long_name_.has_value()
                ? *pending_long_name_
                : HeaderPath(header);
            if (effective_pax.path.has_value()) entry_path = *effective_pax.path;
            const uint64_t entry_size = effective_pax.size.value_or(header_size);

            local_pax_ = PaxValues{};
            pending_long_name_.reset();

            if (effective_pax.sparse || type == 'S') {
                return Outcome::Error("暂不支持 TAR 稀疏文件");
            }
            Outcome account_result = AccountBytes(entry_size);
            if (!account_result.ok()) return account_result;

            const bool is_directory = type == '5';
            NormalizedPath normalized_path;
            Outcome path_result = NormalizeArchivePath(
                entry_path,
                is_directory,
                &normalized_path
            );
            if (!path_result.ok()) return path_result;

            const bool is_regular_file = type == '\0' || type == '0' || type == '7';
            if (!is_regular_file) {
                Outcome skip_result = stream_->ReadPayload(entry_size, -1);
                if (!skip_result.ok()) return skip_result;
                continue;
            }

            bool should_extract = false;
            if (!callback_->ShouldExtract(normalized_path.components.back(), &should_extract)) {
                return Outcome::CallbackException();
            }

            UniqueFd output_file;
            if (should_extract) {
                Outcome open_result = OpenOutputFile(
                    output_root_fd_,
                    normalized_path.components,
                    &output_file
                );
                if (!open_result.ok()) return open_result;
            }

            Outcome payload_result = stream_->ReadPayload(
                entry_size,
                output_file ? output_file.get() : -1
            );
            if (!payload_result.ok()) return payload_result;
        }
    }

private:
    Outcome AccountBytes(uint64_t entry_size) {
        if (accounted_entry_bytes_ > max_extracted_bytes_ ||
            entry_size > max_extracted_bytes_ - accounted_entry_bytes_) {
            return Outcome::Error("压缩包解压大小超过安全限制");
        }
        accounted_entry_bytes_ += entry_size;
        return Outcome::Ok();
    }

    TarStream* stream_;
    ProgressReporter* progress_;
    CallbackBridge* callback_;
    int output_root_fd_;
    int max_entries_;
    uint64_t max_extracted_bytes_;
    uint64_t entry_count_ = 0;
    uint64_t accounted_entry_bytes_ = 0;
    PaxValues global_pax_;
    PaxValues local_pax_;
    std::optional<std::string> pending_long_name_;
};

Outcome ExtractArchive(
    const std::shared_ptr<TaskContext>& task,
    const char* archive_path,
    const char* output_path,
    int max_entries,
    uint64_t max_extracted_bytes,
    CallbackBridge* callback
) {
    if (task->cancelled.load(std::memory_order_acquire)) return Outcome::Cancelled();
    if (max_entries <= 0 || max_extracted_bytes == 0 ||
        max_extracted_bytes > static_cast<uint64_t>(std::numeric_limits<jlong>::max())) {
        return Outcome::Error("原生解压安全限制参数无效");
    }

    UniqueFd archive_fd(open(archive_path, O_RDONLY | O_CLOEXEC | O_NOFOLLOW));
    if (!archive_fd) return Outcome::Error(ErrnoMessage("打开模型压缩包失败"));

    struct stat archive_stat {};
    if (fstat(archive_fd.get(), &archive_stat) != 0) {
        return Outcome::Error(ErrnoMessage("读取模型压缩包信息失败"));
    }
    if (!S_ISREG(archive_stat.st_mode) || archive_stat.st_size <= 0) {
        return Outcome::Error("模型压缩包不是有效的普通文件");
    }
    const uint64_t compressed_size = static_cast<uint64_t>(archive_stat.st_size);
    if (compressed_size > static_cast<uint64_t>(std::numeric_limits<jlong>::max())) {
        return Outcome::Error("模型压缩包文件过大");
    }

    UniqueFd output_root_fd(open(
        output_path,
        O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW
    ));
    if (!output_root_fd) return Outcome::Error(ErrnoMessage("打开模型解压目录失败"));

    const uint64_t max_tar_output = max_extracted_bytes >
        std::numeric_limits<uint64_t>::max() - kTarOverheadAllowance
        ? std::numeric_limits<uint64_t>::max()
        : max_extracted_bytes + kTarOverheadAllowance;
    Bzip2Reader reader(
        archive_fd.get(),
        compressed_size,
        max_tar_output,
        task
    );
    ProgressReporter progress(callback, &reader, compressed_size);
    Outcome start_result = progress.Start();
    if (!start_result.ok()) return start_result;

    TarStream tar_stream(&reader, &progress, task);
    TarExtractor extractor(
        &tar_stream,
        &progress,
        callback,
        output_root_fd.get(),
        max_entries,
        max_extracted_bytes
    );
    return extractor.Extract();
}

jstring NewResultString(JNIEnv* env, const std::string& message) {
    return env->NewStringUTF(message.c_str());
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_subtitleedit_util_NativeTarBz2Extractor_nativeCreateTask(
    JNIEnv* env,
    jobject /* instance */
) {
    try {
        return CreateTask();
    } catch (const std::exception& exception) {
        jclass error_class = env->FindClass("java/lang/OutOfMemoryError");
        if (error_class != nullptr) env->ThrowNew(error_class, exception.what());
        return 0;
    } catch (...) {
        jclass error_class = env->FindClass("java/lang/OutOfMemoryError");
        if (error_class != nullptr) env->ThrowNew(error_class, "无法创建原生解压任务");
        return 0;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_subtitleedit_util_NativeTarBz2Extractor_nativeExtract(
    JNIEnv* env,
    jobject /* instance */,
    jlong task_id,
    jstring archive_path,
    jstring output_path,
    jint max_entries,
    jlong max_extracted_bytes,
    jobject callback
) {
    const std::shared_ptr<TaskContext> task = FindTask(task_id);
    if (task == nullptr) return NewResultString(env, "原生解压任务不存在或已释放");
    if (task->running.exchange(true, std::memory_order_acq_rel)) {
        return NewResultString(env, "原生解压任务正在运行");
    }
    RunningGuard running_guard(task);

    if (archive_path == nullptr || output_path == nullptr || callback == nullptr) {
        return NewResultString(env, "原生解压参数不能为空");
    }

    UtfChars archive_chars(env, archive_path);
    UtfChars output_chars(env, output_path);
    if (archive_chars.get() == nullptr || output_chars.get() == nullptr) return nullptr;

    CallbackBridge callback_bridge(env, callback);
    if (!callback_bridge.valid()) {
        if (env->ExceptionCheck()) return nullptr;
        return NewResultString(env, "原生解压回调接口不完整");
    }

    Outcome result;
    try {
        result = ExtractArchive(
            task,
            archive_chars.get(),
            output_chars.get(),
            max_entries,
            max_extracted_bytes > 0 ? static_cast<uint64_t>(max_extracted_bytes) : 0,
            &callback_bridge
        );
    } catch (const std::bad_alloc&) {
        result = Outcome::Error("原生解压内存不足");
    } catch (const std::exception& exception) {
        result = Outcome::Error(std::string("原生解压异常：") + exception.what());
    } catch (...) {
        result = Outcome::Error("原生解压发生未知异常");
    }

    if (env->ExceptionCheck() || result.kind == OutcomeKind::kCallbackException) return nullptr;
    if (result.kind == OutcomeKind::kOk) return nullptr;
    if (result.kind == OutcomeKind::kCancelled) {
        return env->NewStringUTF(kCancelledResult);
    }
    return NewResultString(env, result.message);
}

extern "C" JNIEXPORT void JNICALL
Java_com_subtitleedit_util_NativeTarBz2Extractor_nativeCancel(
    JNIEnv* /* env */,
    jobject /* instance */,
    jlong task_id
) {
    const std::shared_ptr<TaskContext> task = FindTask(task_id);
    if (task != nullptr) task->cancelled.store(true, std::memory_order_release);
}

extern "C" JNIEXPORT void JNICALL
Java_com_subtitleedit_util_NativeTarBz2Extractor_nativeDestroyTask(
    JNIEnv* /* env */,
    jobject /* instance */,
    jlong task_id
) {
    DestroyTask(task_id);
}
