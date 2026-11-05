// Odysseus fork: the storage behind the bridge's SendspinPersistenceProvider (sendspin-cpp v0.9+).
//
// sendspin-cpp keeps its identity and trust in a few small fixed-size blobs: the device's static
// X25519 private key (`keypair`, which the client id is derived from), its Pairing PSK, one record
// per paired server (`rec_<n>`) and their order, the last server that played and the output delay
// (see sendspin/persistence_keys.h). They are secrets, so each is one file in a directory that
// only the app can read (the app's private files dir), written owner-only (0600):
//
//   - write to `<key>.tmp`, fsync, rename over `<key>`: a crash leaves the old or the new blob,
//     never half of one;
//   - commit() fsyncs the directory so the renames survive a power cut.
//
// The library calls every method on its main loop thread, so there is no locking here. Keys come
// from the library (at most 12 characters, letters, digits and '_'); anything else is refused so
// a key can never name a path outside the directory. Plain POSIX + C++, no JNI, so it builds and
// is tested on the host too (sendspin/src/test/cpp/blob_store_test.cpp).

#pragma once

#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

#include <cerrno>
#include <cstdint>
#include <cstdio>
#include <optional>
#include <string>
#include <utility>
#include <vector>

namespace odysseus {

class BlobStore {
public:
    // Largest blob the library writes is RECORD_ORDER (max_pairing_records, at most 255 bytes);
    // anything much bigger on disk isn't ours.
    static constexpr size_t kMaxBlobBytes = 4096;

    explicit BlobStore(std::string dir) : dir_(std::move(dir)) {}

    const std::string& dir() const { return dir_; }

    // Creates the directory (0700) if needed. False if it doesn't exist afterwards.
    bool ensure_dir() const {
        if (dir_.empty()) return false;
        if (mkdir(dir_.c_str(), 0700) != 0 && errno != EEXIST) return false;
        struct stat st{};
        return stat(dir_.c_str(), &st) == 0 && S_ISDIR(st.st_mode);
    }

    static bool valid_key(const std::string& key) {
        if (key.empty() || key.size() > 32) return false;
        for (const char c : key) {
            const bool ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
                            (c >= '0' && c <= '9') || c == '_';
            if (!ok) return false;
        }
        return true;
    }

    std::optional<std::vector<uint8_t>> load(const std::string& key) const {
        if (!valid_key(key)) return std::nullopt;
        const int fd = open(path(key).c_str(), O_RDONLY | O_CLOEXEC);
        if (fd < 0) return std::nullopt;
        std::vector<uint8_t> out;
        uint8_t buf[512];
        bool ok = true;
        for (;;) {
            const ssize_t n = read(fd, buf, sizeof(buf));
            if (n < 0) {
                if (errno == EINTR) continue;
                ok = false;
                break;
            }
            if (n == 0) break;
            out.insert(out.end(), buf, buf + n);
            if (out.size() > kMaxBlobBytes) {
                ok = false;
                break;
            }
        }
        close(fd);
        if (!ok) return std::nullopt;
        return out;
    }

    bool save(const std::string& key, const uint8_t* data, size_t len) {
        if (!valid_key(key) || (data == nullptr && len != 0) || len > kMaxBlobBytes) return false;
        if (!ensure_dir()) return false;
        const std::string final_path = path(key);
        const std::string tmp_path = final_path + ".tmp";
        const int fd = open(tmp_path.c_str(), O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
        if (fd < 0) return false;
        bool ok = fchmod(fd, 0600) == 0;
        size_t done = 0;
        while (ok && done < len) {
            const ssize_t n = write(fd, data + done, len - done);
            if (n < 0) {
                if (errno == EINTR) continue;
                ok = false;
                break;
            }
            done += static_cast<size_t>(n);
        }
        if (ok && fsync(fd) != 0) ok = false;
        if (close(fd) != 0) ok = false;
        if (ok && rename(tmp_path.c_str(), final_path.c_str()) != 0) ok = false;
        if (!ok) {
            unlink(tmp_path.c_str());
            return false;
        }
        dirty_ = true;
        return true;
    }

    // Makes the renames durable. True when there was nothing to do.
    bool commit() {
        if (!dirty_) return true;
        const int fd = open(dir_.c_str(), O_RDONLY | O_DIRECTORY | O_CLOEXEC);
        if (fd < 0) return false;
        const bool ok = fsync(fd) == 0;
        close(fd);
        if (ok) dirty_ = false;
        return ok;
    }

private:
    std::string path(const std::string& key) const { return dir_ + "/" + key; }

    std::string dir_;
    bool dirty_ = false;
};

}  // namespace odysseus
