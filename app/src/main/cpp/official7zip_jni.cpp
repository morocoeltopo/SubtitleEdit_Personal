#include <jni.h>

#include <cerrno>
#include <cstdint>
#include <cstdio>
#include <fcntl.h>
#include <pthread.h>
#include <signal.h>
#include <string>
#include <unistd.h>
#include <utility>
#include <vector>

#include "StdOutStream.h"

int Main2(int numArgs, char *args[]);
extern CStdOutStream *g_StdStream;
extern CStdOutStream *g_ErrStream;

namespace {

constexpr int kFailureExitCode = 2;

int MoveFdAboveStandardDescriptors(int fd) noexcept {
    if (fd < 0 || fd > STDERR_FILENO) return fd;

    int elevated;
    do {
        elevated = fcntl(fd, F_DUPFD_CLOEXEC, STDERR_FILENO + 1);
    } while (elevated < 0 && errno == EINTR);
    const int saved_errno = elevated < 0 ? errno : 0;
    close(fd);
    if (elevated < 0) errno = saved_errno;
    return elevated;
}

int DuplicateFdAboveStandardDescriptors(int fd) noexcept {
    int duplicate;
    do {
        duplicate = fcntl(fd, F_DUPFD_CLOEXEC, STDERR_FILENO + 1);
    } while (duplicate < 0 && errno == EINTR);
    return duplicate;
}

int OpenDirectoryForRestore(const char *path) noexcept {
    int fd;
    do {
        fd = open(path, O_PATH | O_DIRECTORY | O_CLOEXEC);
    } while (fd < 0 && errno == EINTR);
    return MoveFdAboveStandardDescriptors(fd);
}

int OpenCaptureFile(const char *path) noexcept {
    int fd;
    do {
        fd = open(path, O_CREAT | O_TRUNC | O_WRONLY | O_CLOEXEC, 0600);
    } while (fd < 0 && errno == EINTR);
    return MoveFdAboveStandardDescriptors(fd);
}

bool DuplicateFdTo(int source, int destination) noexcept {
    int result;
    do {
        result = dup2(source, destination);
    } while (result < 0 && errno == EINTR);
    return result >= 0;
}

bool ChangeDirectory(const char *path) noexcept {
    int result;
    do {
        result = chdir(path);
    } while (result < 0 && errno == EINTR);
    return result == 0;
}

bool RestoreDirectory(int fd) noexcept {
    int result;
    do {
        result = fchdir(fd);
    } while (result < 0 && errno == EINTR);
    return result == 0;
}

void CloseFd(int &fd) noexcept {
    if (fd < 0) return;
    const int descriptor = fd;
    fd = -1;
    close(descriptor);
}

void FlushStreamBestEffort(FILE *stream) noexcept {
    fflush(stream);
    clearerr(stream);
}

struct SavedDescriptor {
    int duplicate = -1;
    bool was_open = false;
    bool initialized = false;
};

bool SaveDescriptor(int descriptor, SavedDescriptor &saved) noexcept {
    int duplicate;
    do {
        duplicate = fcntl(descriptor, F_DUPFD_CLOEXEC, STDERR_FILENO + 1);
    } while (duplicate < 0 && errno == EINTR);
    if (duplicate >= 0) {
        saved.duplicate = duplicate;
        saved.was_open = true;
        saved.initialized = true;
        return true;
    }
    if (errno == EBADF) {
        saved.was_open = false;
        saved.initialized = true;
        return true;
    }
    return false;
}

bool CloseTargetDescriptor(int descriptor) noexcept {
    if (close(descriptor) == 0) return true;
    return errno == EBADF || errno == EINTR;
}

bool RestoreDescriptor(const SavedDescriptor &saved, int destination) noexcept {
    if (!saved.initialized) return true;
    return saved.was_open
        ? DuplicateFdTo(saved.duplicate, destination)
        : CloseTargetDescriptor(destination);
}

void AppendUtf8(std::string &destination, uint32_t code_point) {
    if (code_point <= 0x7F) {
        destination.push_back(static_cast<char>(code_point));
    } else if (code_point <= 0x7FF) {
        destination.push_back(static_cast<char>(0xC0 | (code_point >> 6)));
        destination.push_back(static_cast<char>(0x80 | (code_point & 0x3F)));
    } else if (code_point <= 0xFFFF) {
        destination.push_back(static_cast<char>(0xE0 | (code_point >> 12)));
        destination.push_back(static_cast<char>(0x80 | ((code_point >> 6) & 0x3F)));
        destination.push_back(static_cast<char>(0x80 | (code_point & 0x3F)));
    } else {
        destination.push_back(static_cast<char>(0xF0 | (code_point >> 18)));
        destination.push_back(static_cast<char>(0x80 | ((code_point >> 12) & 0x3F)));
        destination.push_back(static_cast<char>(0x80 | ((code_point >> 6) & 0x3F)));
        destination.push_back(static_cast<char>(0x80 | (code_point & 0x3F)));
    }
}

bool CopyJavaString(JNIEnv *env, jstring source, std::string &destination) {
    const jsize length = env->GetStringLength(source);
    const jchar *chars = env->GetStringChars(source, nullptr);
    if (!chars) return false;
    try {
        destination.clear();
        destination.reserve(static_cast<size_t>(length));
        for (jsize index = 0; index < length; ++index) {
            uint32_t code_point = chars[index];
            if (code_point >= 0xD800 && code_point <= 0xDBFF) {
                if (index + 1 < length) {
                    const uint32_t low = chars[index + 1];
                    if (low >= 0xDC00 && low <= 0xDFFF) {
                        code_point = 0x10000u +
                            ((code_point - 0xD800u) << 10) + (low - 0xDC00u);
                        ++index;
                    } else {
                        code_point = 0xFFFDu;
                    }
                } else {
                    code_point = 0xFFFDu;
                }
            } else if (code_point >= 0xDC00 && code_point <= 0xDFFF) {
                code_point = 0xFFFDu;
            }
            AppendUtf8(destination, code_point);
        }
    } catch (...) {
        env->ReleaseStringChars(source, chars);
        throw;
    }
    env->ReleaseStringChars(source, chars);
    return true;
}

class ScopedSevenZipStreams final {
public:
    ScopedSevenZipStreams() noexcept { Reset(); }
    ScopedSevenZipStreams(const ScopedSevenZipStreams &) = delete;
    ScopedSevenZipStreams &operator=(const ScopedSevenZipStreams &) = delete;

    ~ScopedSevenZipStreams() { Reset(); }

private:
    static void Reset() noexcept {
        g_StdStream = &g_StdOut;
        g_ErrStream = &g_StdErr;
    }
};

class ScopedWorkingDirectory final {
public:
    ScopedWorkingDirectory() = default;
    ScopedWorkingDirectory(const ScopedWorkingDirectory &) = delete;
    ScopedWorkingDirectory &operator=(const ScopedWorkingDirectory &) = delete;

    ~ScopedWorkingDirectory() { Restore(); }

    bool ChangeTo(const char *directory) noexcept {
        saved_cwd_ = OpenDirectoryForRestore(".");
        if (saved_cwd_ < 0) return false;

        if (!ChangeDirectory(directory)) {
            CloseFd(saved_cwd_);
            return false;
        }
        changed_ = true;
        return true;
    }

    bool Restore() noexcept {
        bool restored = true;
        if (changed_) {
            if (RestoreDirectory(saved_cwd_)) {
                changed_ = false;
            } else {
                restored = false;
            }
        }
        if (!changed_) CloseFd(saved_cwd_);
        return restored;
    }

private:
    int saved_cwd_ = -1;
    bool changed_ = false;
};

class ScopedOutputCapture final {
public:
    ScopedOutputCapture() = default;
    ScopedOutputCapture(const ScopedOutputCapture &) = delete;
    ScopedOutputCapture &operator=(const ScopedOutputCapture &) = delete;

    ~ScopedOutputCapture() { Restore(); }

    bool Start(const char *path, int stdout_fd = -1) noexcept {
        if (!SaveDescriptor(STDOUT_FILENO, saved_out_)) return false;
        if (!SaveDescriptor(STDERR_FILENO, saved_err_)) {
            CloseFd(saved_out_.duplicate);
            return false;
        }
        if (saved_out_.was_open) FlushStreamBestEffort(stdout);
        if (saved_err_.was_open) FlushStreamBestEffort(stderr);

        if (stdout_fd >= 0) {
            if (!DuplicateFdTo(stdout_fd, STDOUT_FILENO)) {
                Restore();
                return false;
            }
            stdout_redirected_ = true;
        }

        if (path != nullptr) {
            capture_fd_ = OpenCaptureFile(path);
            if (capture_fd_ < 0) {
                Restore();
                return false;
            }
            if (!stdout_redirected_ && !DuplicateFdTo(capture_fd_, STDOUT_FILENO)) {
                Restore();
                return false;
            }
            stdout_redirected_ = true;
            if (!DuplicateFdTo(capture_fd_, STDERR_FILENO)) {
                Restore();
                return false;
            }
            stderr_redirected_ = true;
        }
        clearerr(stdout);
        clearerr(stderr);
        return true;
    }

    bool Restore() noexcept {
        bool restored = true;
        if (stdout_redirected_) {
            FlushStreamBestEffort(stdout);
        }
        if (stderr_redirected_) {
            FlushStreamBestEffort(stderr);
        }

        if (stdout_redirected_) {
            if (RestoreDescriptor(saved_out_, STDOUT_FILENO)) {
                stdout_redirected_ = false;
            } else {
                restored = false;
            }
        }
        if (stderr_redirected_) {
            if (RestoreDescriptor(saved_err_, STDERR_FILENO)) {
                stderr_redirected_ = false;
            } else {
                restored = false;
            }
        }

        if (!stdout_redirected_) CloseFd(saved_out_.duplicate);
        if (!stderr_redirected_) CloseFd(saved_err_.duplicate);
        CloseFd(capture_fd_);
        clearerr(stdout);
        clearerr(stderr);
        return restored;
    }

private:
    SavedDescriptor saved_out_;
    SavedDescriptor saved_err_;
    int capture_fd_ = -1;
    bool stdout_redirected_ = false;
    bool stderr_redirected_ = false;
};

// A broken pipe must be reported as EPIPE to 7-Zip instead of terminating the
// hosting Android process when the TAR consumer stops early.
class ScopedSigpipeBlock final {
public:
    ScopedSigpipeBlock() noexcept {
        sigemptyset(&blocked_);
        sigaddset(&blocked_, SIGPIPE);
        if (pthread_sigmask(SIG_BLOCK, &blocked_, &previous_) == 0) active_ = true;
    }

    ScopedSigpipeBlock(const ScopedSigpipeBlock &) = delete;
    ScopedSigpipeBlock &operator=(const ScopedSigpipeBlock &) = delete;

    ~ScopedSigpipeBlock() noexcept {
        if (!active_) return;
        // A SIGPIPE generated while blocked remains pending. Consume it before
        // restoring the caller's mask, otherwise it could be delivered later.
        if (!sigismember(&previous_, SIGPIPE)) {
            timespec timeout{};
            sigtimedwait(&blocked_, nullptr, &timeout);
        }
        pthread_sigmask(SIG_SETMASK, &previous_, nullptr);
    }

private:
    sigset_t blocked_{};
    sigset_t previous_{};
    bool active_ = false;
};

}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_subtitleedit_util_OfficialSevenZip_duplicateFdAboveStandardNative(
    JNIEnv *, jclass, jint fd) {
    if (fd < 0) return -1;
    return DuplicateFdAboveStandardDescriptors(fd);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_subtitleedit_util_OfficialSevenZip_executeNative(
    JNIEnv *env, jclass, jobjectArray arguments, jstring capture_path,
    jstring working_directory, jint stdout_fd) {
    try {
        if (!arguments) return kFailureExitCode;
        const jsize count = env->GetArrayLength(arguments);
        std::vector<std::string> values;
        values.reserve(static_cast<size_t>(count) + 1);
        values.emplace_back("subtitleedit-7zz");
        for (jsize i = 0; i < count; ++i) {
            auto value = static_cast<jstring>(env->GetObjectArrayElement(arguments, i));
            if (!value) return kFailureExitCode;
            try {
                std::string text;
                if (!CopyJavaString(env, value, text)) {
                    env->DeleteLocalRef(value);
                    return kFailureExitCode;
                }
                values.emplace_back(std::move(text));
            } catch (...) {
                env->DeleteLocalRef(value);
                throw;
            }
            env->DeleteLocalRef(value);
        }

        std::string capture;
        const bool capture_requested = capture_path != nullptr;
        if (capture_requested && !CopyJavaString(env, capture_path, capture)) {
            return kFailureExitCode;
        }
        std::string directory;
        const bool directory_requested = working_directory != nullptr;
        if (directory_requested && !CopyJavaString(env, working_directory, directory)) {
            return kFailureExitCode;
        }

        std::vector<char *> argv;
        argv.reserve(values.size());
        for (auto &value : values) argv.push_back(value.data());

        ScopedSevenZipStreams seven_zip_streams;
        ScopedWorkingDirectory working_directory_scope;
        if (directory_requested && !working_directory_scope.ChangeTo(directory.c_str())) {
            return kFailureExitCode;
        }

        ScopedOutputCapture output_capture;
        const bool output_requested = stdout_fd >= 0;
        if ((capture_requested || output_requested) &&
            !output_capture.Start(capture_requested ? capture.c_str() : nullptr, stdout_fd)) {
            working_directory_scope.Restore();
            return kFailureExitCode;
        }

        ScopedSigpipeBlock sigpipe_block;
        int result = Main2(static_cast<int>(argv.size()), argv.data());
        const bool output_restored = output_capture.Restore();
        const bool directory_restored = working_directory_scope.Restore();
        if (!output_restored || !directory_restored) return kFailureExitCode;
        return result;
    } catch (...) {
        return kFailureExitCode;
    }
}
