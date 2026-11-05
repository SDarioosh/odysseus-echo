// Host test for odysseus::BlobStore (the Sendspin bridge's persistence storage). No framework;
// from the repo root:
//   c++ -std=c++20 -Wall -Wextra -I sendspin/src/main/cpp -o "$TMPDIR/blob_store_test" sendspin/src/test/cpp/blob_store_test.cpp
//   "$TMPDIR/blob_store_test"
#include "blob_store.h"

#include <sys/stat.h>
#include <unistd.h>

#include <cstdio>
#include <cstdlib>
#include <string>
#include <vector>

static int failures = 0;
#define CHECK(cond)                                                            \
    do {                                                                       \
        if (!(cond)) {                                                         \
            std::fprintf(stderr, "FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond); \
            ++failures;                                                        \
        }                                                                      \
    } while (0)

int main() {
    const char* tmp = std::getenv("TMPDIR");
    std::string base = std::string(tmp ? tmp : "/tmp") + "/blob_store_test_XXXXXX";
    std::vector<char> templ(base.begin(), base.end());
    templ.push_back('\0');
    CHECK(mkdtemp(templ.data()) != nullptr);
    const std::string root(templ.data());
    const std::string dir = root + "/sendspin";

    odysseus::BlobStore store(dir);

    // Nothing stored yet; the directory is created on the first save.
    CHECK(!store.load("keypair").has_value());

    std::vector<uint8_t> key(32);
    for (size_t i = 0; i < key.size(); ++i) key[i] = static_cast<uint8_t>(i * 7);
    CHECK(store.save("keypair", key.data(), key.size()));
    CHECK(store.commit());
    auto loaded = store.load("keypair");
    CHECK(loaded.has_value() && *loaded == key);

    // Owner-only file in an owner-only directory, no temp file left behind.
    struct stat st{};
    CHECK(stat((dir + "/keypair").c_str(), &st) == 0 && (st.st_mode & 0777) == 0600);
    CHECK(stat(dir.c_str(), &st) == 0 && (st.st_mode & 0777) == 0700);
    CHECK(access((dir + "/keypair.tmp").c_str(), F_OK) != 0);

    // Overwrite in place, including a zeroed record slot (how the library frees one).
    std::vector<uint8_t> zeros(64, 0);
    CHECK(store.save("rec_0", zeros.data(), zeros.size()));
    std::vector<uint8_t> rec(64, 0xAB);
    CHECK(store.save("rec_0", rec.data(), rec.size()));
    loaded = store.load("rec_0");
    CHECK(loaded.has_value() && *loaded == rec);

    // Empty blob round-trips as empty, not absent.
    CHECK(store.save("rec_order", nullptr, 0));
    loaded = store.load("rec_order");
    CHECK(loaded.has_value() && loaded->empty());

    // Keys that could escape the directory or aren't the library's shape are refused.
    for (const char* bad : {"", "../keypair", "a/b", ".hidden", "key.tmp", "with space"}) {
        CHECK(!odysseus::BlobStore::valid_key(bad));
        CHECK(!store.save(bad, key.data(), key.size()));
        CHECK(!store.load(bad).has_value());
    }
    CHECK(access((root + "/keypair").c_str(), F_OK) != 0);

    // Oversized blobs are refused on save, and an oversized file isn't loaded.
    std::vector<uint8_t> big(odysseus::BlobStore::kMaxBlobBytes + 1, 1);
    CHECK(!store.save("big", big.data(), big.size()));
    FILE* f = std::fopen((dir + "/big").c_str(), "wb");
    CHECK(f != nullptr);
    if (f) {
        std::fwrite(big.data(), 1, big.size(), f);
        std::fclose(f);
    }
    CHECK(!store.load("big").has_value());

    // A fresh store over the same directory sees the same data (survives a restart).
    odysseus::BlobStore again(dir);
    loaded = again.load("keypair");
    CHECK(loaded.has_value() && *loaded == key);

    // A store whose directory can't be created refuses saves.
    odysseus::BlobStore nowhere(root + "/missing/parent/dir");
    CHECK(!nowhere.save("keypair", key.data(), key.size()));
    CHECK(odysseus::BlobStore("").ensure_dir() == false);

    std::string cleanup = "rm -rf -- '" + root + "'";
    if (std::system(cleanup.c_str()) != 0) std::fprintf(stderr, "cleanup failed\n");

    if (failures == 0) std::printf("blob_store_test: all checks passed\n");
    return failures == 0 ? 0 : 1;
}
