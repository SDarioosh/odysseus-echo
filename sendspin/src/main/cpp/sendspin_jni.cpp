// JNI bridge between com.msp1974.sendspin.NativeSendspin and sendspin-cpp (player, metadata,
// controller, colour and - Odysseus fork - artwork roles).
//
// sendspin-cpp expects one thread - its "main loop" - to call start(), loop() and stop(), and the
// player update_* calls are only safe from there too. So that thread is owned here: it starts the
// client, runs loop() until told to stop, then stops it. Kotlin-facing mutations are queued and
// drained by it, and the few values Kotlin polls are mirrored into atomics. The one call made
// straight from another thread is PlayerRole::notify_audio_played(), which the library documents
// as thread-safe.
//
// Odysseus fork (sendspin-cpp v0.9): every connection is encrypted (Noise). The bridge is also the
// library's persistence provider, backed by BlobStore files in the app's private files dir: the
// device key (which the client id is derived from) and the paired-server records live there. It
// forwards the pairing events (code to show, operator window, result, trust) to Kotlin.
//

#include <jni.h>
#include <android/log.h>
#include <arpa/inet.h>
#include <dirent.h>
#include <netinet/in.h>
#include <pthread.h>
#include <sys/socket.h>
#include <unistd.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdio>
#include <functional>
#include <map>
#include <future>
#include <memory>
#include <mutex>
#include <optional>
#include <set>
#include <sstream>
#include <string>
#include <thread>
#include <vector>

#include "blob_store.h"
#include "sendspin/artwork_role.h"
#include "sendspin/client.h"
#include "sendspin/color_role.h"
#include "sendspin/controller_role.h"
#include "sendspin/metadata_role.h"
#include "sendspin/player_role.h"

namespace {

constexpr const char* TAG = "sendspin";

JavaVM* g_vm = nullptr;
pthread_key_t g_detach_key;

void detach_thread(void*) {
    if (g_vm != nullptr) g_vm->DetachCurrentThread();
}

// The library calls back from its own threads, which need attaching on first use and detaching
// when they exit.
JNIEnv* env_for_this_thread() {
    JNIEnv* env = nullptr;
    if (g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK) return env;
    JavaVMAttachArgs args{JNI_VERSION_1_6, "sendspin-native", nullptr};
    if (g_vm->AttachCurrentThread(&env, &args) != JNI_OK) return nullptr;
    pthread_setspecific(g_detach_key, env);
    return env;
}

// Returning to Java with an exception pending aborts the runtime, so callbacks swallow them.
bool clear_exception(JNIEnv* env) {
    if (!env->ExceptionCheck()) return false;
    env->ExceptionDescribe();
    env->ExceptionClear();
    return true;
}

std::string to_std_string(JNIEnv* env, jstring s) {
    if (s == nullptr) return {};
    const char* chars = env->GetStringUTFChars(s, nullptr);
    std::string out = chars != nullptr ? chars : "";
    if (chars != nullptr) env->ReleaseStringUTFChars(s, chars);
    return out;
}

// sendspin-cpp logs to stderr, which Android discards. Splice a pipe over fd 2 and forward each
// line to logcat. Process-wide, so done once. Lines look like "<severity> <tag>: <message>".
std::once_flag g_stderr_once;

void forward_stderr_to_logcat() {
    std::call_once(g_stderr_once, [] {
        int fds[2];
        if (pipe(fds) != 0) return;
        setvbuf(stderr, nullptr, _IOLBF, 0);
        dup2(fds[1], STDERR_FILENO);
        close(fds[1]);
        const int read_fd = fds[0];
        std::thread([read_fd] {
            char buf[1024];
            std::string pending;
            ssize_t n;
            while ((n = read(read_fd, buf, sizeof(buf))) > 0) {
                pending.append(buf, static_cast<size_t>(n));
                size_t eol;
                while ((eol = pending.find('\n')) != std::string::npos) {
                    const std::string line = pending.substr(0, eol);
                    pending.erase(0, eol + 1);
                    if (line.empty()) continue;
                    int prio = ANDROID_LOG_INFO;
                    switch (line[0]) {
                        case 'E': prio = ANDROID_LOG_ERROR; break;
                        case 'W': prio = ANDROID_LOG_WARN; break;
                        case 'D': prio = ANDROID_LOG_DEBUG; break;
                        case 'V': prio = ANDROID_LOG_VERBOSE; break;
                        default: break;
                    }
                    __android_log_write(prio, TAG, line.c_str());
                }
            }
        }).detach();
    });
}

jmethodID find_method(JNIEnv* env, jclass cls, const char* name, const char* sig) {
    jmethodID mid = env->GetMethodID(cls, name, sig);
    if (mid == nullptr) {
        clear_exception(env);
        __android_log_print(ANDROID_LOG_ERROR, TAG, "Missing callback %s%s", name, sig);
    }
    return mid;
}

// Names used on the Kotlin side for the controller commands the overlay sends and the server
// advertises.
const std::pair<const char*, sendspin::SendspinControllerCommand> kCommands[] = {
    {"play", sendspin::SendspinControllerCommand::PLAY},
    {"pause", sendspin::SendspinControllerCommand::PAUSE},
    {"stop", sendspin::SendspinControllerCommand::STOP},
    {"next", sendspin::SendspinControllerCommand::NEXT},
    {"previous", sendspin::SendspinControllerCommand::PREVIOUS},
    {"seek", sendspin::SendspinControllerCommand::SEEK},
    {"shuffle", sendspin::SendspinControllerCommand::SHUFFLE},
    {"unshuffle", sendspin::SendspinControllerCommand::UNSHUFFLE},
    {"repeat_off", sendspin::SendspinControllerCommand::REPEAT_OFF},
    {"repeat_one", sendspin::SendspinControllerCommand::REPEAT_ONE},
    {"repeat_all", sendspin::SendspinControllerCommand::REPEAT_ALL},
};

// Packs a colour as opaque ARGB, or 0 (fully transparent) when the server didn't provide it.
jint pack_color(const std::optional<sendspin::RgbColor>& color) {
    if (!color) return 0;
    const auto& c = *color;
    return static_cast<jint>(0xFF000000u | (static_cast<uint32_t>(c[0]) << 16) |
                             (static_cast<uint32_t>(c[1]) << 8) | static_cast<uint32_t>(c[2]));
}

const char* command_name(sendspin::SendspinControllerCommand command) {
    for (const auto& [name, value] : kCommands) {
        if (value == command) return name;
    }
    return nullptr;
}

std::optional<sendspin::SendspinControllerCommand> command_from_name(const std::string& name) {
    for (const auto& [n, value] : kCommands) {
        if (name == n) return value;
    }
    return std::nullopt;
}

jstring optional_jstring(JNIEnv* env, const std::optional<std::string>& s) {
    return s ? env->NewStringUTF(s->c_str()) : nullptr;
}

class Bridge final : public sendspin::PlayerRoleListener,
                     public sendspin::MetadataRoleListener,
                     public sendspin::ControllerRoleListener,
                     public sendspin::ColorRoleListener,
                     public sendspin::ArtworkRoleListener,
                     public sendspin::SendspinClientListener,
                     public sendspin::SendspinNetworkProvider,
                     public sendspin::SendspinPersistenceProvider {
public:
    std::unique_ptr<sendspin::SendspinClient> client;
    sendspin::PlayerRole* player = nullptr;
    sendspin::ControllerRole* controller = nullptr;
    sendspin::MetadataRole* metadata = nullptr;
    sendspin::ColorRole* color = nullptr;
    sendspin::ArtworkRole* artwork = nullptr;

    jobject callbacks = nullptr;  // global ref
    jmethodID on_audio_write_mid = nullptr;
    jmethodID on_stream_start_mid = nullptr;
    jmethodID on_stream_end_mid = nullptr;
    jmethodID on_volume_mid = nullptr;
    jmethodID on_mute_mid = nullptr;
    jmethodID on_connection_mid = nullptr;
    jmethodID on_metadata_mid = nullptr;
    jmethodID on_metadata_clear_mid = nullptr;
    jmethodID on_playback_state_mid = nullptr;
    jmethodID on_group_id_mid = nullptr;
    jmethodID on_controller_mid = nullptr;
    jmethodID on_colors_mid = nullptr;
    jmethodID on_artwork_image_mid = nullptr;
    jmethodID on_artwork_display_mid = nullptr;
    jmethodID on_artwork_clear_mid = nullptr;
    // Odysseus fork: encryption and pairing (sendspin-cpp v0.9).
    jmethodID on_trust_mid = nullptr;
    jmethodID on_pairing_code_mid = nullptr;
    jmethodID on_pairing_window_mid = nullptr;
    jmethodID on_pairing_result_mid = nullptr;

    // Odysseus fork: where the library's identity and pairing blobs are kept.
    std::unique_ptr<odysseus::BlobStore> store;

    // The client id the library derived from its key, copied once start() has run (read from
    // Kotlin's threads, so it's never touched again after that).
    std::string client_id;

    // Whether the connected server is one this device paired with (the last on_trust_changed);
    // such a server passes the preferred-server check from any address. Loop thread only.
    bool server_paired = false;

    std::thread loop_thread;
    std::atomic<bool> running{false};
    std::mutex queue_mutex;
    std::vector<std::function<void()>> queue;

    std::atomic<bool> connected{false};

    // Odysseus fork: set by nativeDestroy when the player is coming back (the app or its service
    // is stopping, not the owner switching Sendspin off). The loop thread then says goodbye with
    // `restart` instead of the library's `shutdown`. Music Assistant only keeps re-dialling a
    // manually configured address after `restart`; after `shutdown` it leaves reconnecting to
    // mDNS, which doesn't cross Tailscale, so the player stayed "not yet available".
    std::atomic<bool> goodbye_restart{false};

    uint16_t listen_port = 0;

    // Dotted IPv4 address of the only server allowed to connect; empty accepts any. Only touched
    // on the loop thread (changes are queued).
    std::string preferred_ip;

    // Id of the connected server once it has been confirmed to be at preferred_ip.
    std::string verified_id;
    std::chrono::steady_clock::time_point next_check{};

    // Odysseus fork: ids of servers allowed whatever address they connect from (set on the device,
    // never by HA). Lets the one Music Assistant reach the device over Tailscale, where it arrives
    // from its Tailscale address rather than the LAN address in preferred_ip.
    std::set<std::string> trusted_ids;

    // Server id to the address it was last seen connecting from; kept in memory only.
    std::map<std::string, std::string> id_to_ip;

    // The library doesn't expose where a connection came from, so find the remote IPv4 address
    // of every inbound socket on our listening port by asking the kernel about our own fds.
    std::vector<std::string> inbound_peers() const {
        std::vector<std::string> peers;
        DIR* dir = opendir("/proc/self/fd");
        if (dir == nullptr) return peers;
        while (const dirent* entry = readdir(dir)) {
            const int fd = atoi(entry->d_name);
            if (fd <= 0) continue;
            sockaddr_storage local{}, remote{};
            socklen_t local_len = sizeof(local), remote_len = sizeof(remote);
            if (getsockname(fd, reinterpret_cast<sockaddr*>(&local), &local_len) != 0 ||
                getpeername(fd, reinterpret_cast<sockaddr*>(&remote), &remote_len) != 0) {
                continue;
            }
            char text[INET_ADDRSTRLEN] = {};
            if (local.ss_family == AF_INET && remote.ss_family == AF_INET) {
                if (ntohs(reinterpret_cast<sockaddr_in*>(&local)->sin_port) != listen_port) continue;
                inet_ntop(AF_INET, &reinterpret_cast<sockaddr_in*>(&remote)->sin_addr, text,
                          sizeof(text));
            } else if (local.ss_family == AF_INET6 && remote.ss_family == AF_INET6) {
                auto* local6 = reinterpret_cast<sockaddr_in6*>(&local);
                auto* remote6 = reinterpret_cast<sockaddr_in6*>(&remote);
                if (ntohs(local6->sin6_port) != listen_port) continue;
                if (!IN6_IS_ADDR_V4MAPPED(&remote6->sin6_addr)) continue;
                inet_ntop(AF_INET, &remote6->sin6_addr.s6_addr[12], text, sizeof(text));
            } else {
                continue;
            }
            if (text[0] != '\0' &&
                std::find(peers.begin(), peers.end(), text) == peers.end()) {
                peers.emplace_back(text);
            }
        }
        closedir(dir);
        return peers;
    }

    enum class Verdict { UNDECIDED, ALLOW, REFUSE };

    // Whether the connected server [id] is the one at preferred_ip.
    Verdict check_server(const std::string& id) {
        if (trusted_ids.count(id) != 0) return Verdict::ALLOW;  // Odysseus fork
        // Odysseus fork: a paired server proved it holds this device's pairing record in the
        // handshake, so where it connects from (LAN or Tailscale) doesn't matter.
        if (server_paired) return Verdict::ALLOW;
        const auto peers = inbound_peers();
        if (peers.empty()) return Verdict::UNDECIDED;
        // One inbound peer is unambiguously the connected server, which also teaches us its
        // address for the times several servers are connected at once.
        if (peers.size() == 1) id_to_ip[id] = peers[0];
        const auto known = id_to_ip.find(id);
        if (known != id_to_ip.end()) {
            return known->second == preferred_ip ? Verdict::ALLOW : Verdict::REFUSE;
        }
        // Several peers and this server not seen alone yet: allow it if the preferred server is
        // among them, since there is no way to tell which one is active.
        return std::find(peers.begin(), peers.end(), preferred_ip) != peers.end()
                   ? Verdict::ALLOW
                   : Verdict::REFUSE;
    }

    void post(std::function<void()> fn) {
        std::lock_guard<std::mutex> lock(queue_mutex);
        queue.push_back(std::move(fn));
    }

    // Owns the library's main loop for the life of the bridge; see the file comment. Reports
    // whether start() succeeded through [started].
    void run_loop(std::promise<bool> started) {
        const bool ok = client->start();
        if (ok) client_id = client->client_id();
        started.set_value(ok);
        if (!ok) return;

        bool was_connected = false;
        while (running.load()) {
            std::vector<std::function<void()>> pending;
            {
                std::lock_guard<std::mutex> lock(queue_mutex);
                pending.swap(queue);
            }
            for (auto& fn : pending) fn();

            client->loop();

            // The library has no connection callback, so watch for the edge here.
            bool now_connected = client->is_connected();
            if (!now_connected) {
                verified_id.clear();
                server_paired = false;
            }
            if (now_connected && !preferred_ip.empty()) {
                // The library accepts whichever server connects first and has no hook to refuse
                // one earlier, so a server that isn't the preferred one is dropped as soon as its
                // hello names it, before it is reported as connected.
                const auto info = client->get_server_information();
                if (!info) {
                    now_connected = false;  // handshake details not in yet; check next tick
                } else if (info->server_id != verified_id) {
                    now_connected = false;
                    const auto now = std::chrono::steady_clock::now();
                    if (now >= next_check) {
                        next_check = now + std::chrono::milliseconds(200);
                        switch (check_server(info->server_id)) {
                            case Verdict::ALLOW:
                                verified_id = info->server_id;
                                now_connected = true;
                                break;
                            case Verdict::REFUSE:
                                __android_log_print(
                                    ANDROID_LOG_INFO, TAG,
                                    "refusing server '%s' (%s): not the preferred server %s or a trusted server id",
                                    info->name.c_str(), info->server_id.c_str(),
                                    preferred_ip.c_str());
                                client->disconnect(sendspin::SendspinGoodbyeReason::ANOTHER_SERVER);
                                break;
                            case Verdict::UNDECIDED:
                                break;
                        }
                    }
                }
            }
            connected.store(now_connected);
            if (now_connected != was_connected) {
                was_connected = now_connected;
                report_connection(now_connected);
            }
            std::this_thread::sleep_for(std::chrono::milliseconds(10));
        }
        if (goodbye_restart.load() && client->is_connected()) {
            // stop() always says `shutdown`, so say `restart` first and let the close land:
            // stop() skips a goodbye for a connection that is no longer open. Bounded, so a
            // stuck send can't hold up the service shutdown.
            __android_log_write(ANDROID_LOG_INFO, TAG, "saying goodbye with reason restart");
            client->disconnect(sendspin::SendspinGoodbyeReason::RESTART);
            const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(1);
            while (client->is_connected() && std::chrono::steady_clock::now() < deadline) {
                client->loop();
                std::this_thread::sleep_for(std::chrono::milliseconds(10));
            }
        }
        // Sends goodbyes, closes connections and fires the roles' clear callbacks.
        client->stop();
    }

    void report_connection(bool now_connected) {
        JNIEnv* env = env_for_this_thread();
        if (env == nullptr) return;
        jstring server_name = nullptr;
        if (now_connected) {
            if (auto info = client->get_server_information()) {
                server_name = env->NewStringUTF(info->name.c_str());
            }
        }
        env->CallVoidMethod(callbacks, on_connection_mid, static_cast<jboolean>(now_connected),
                            server_name);
        clear_exception(env);
        if (server_name != nullptr) env->DeleteLocalRef(server_name);
    }

    // --- PlayerRoleListener -------------------------------------------------

    // Fires on the library's sync thread and may block up to timeout_ms.
    size_t on_audio_write(uint8_t* data, size_t length, uint32_t timeout_ms) override {
        JNIEnv* env = env_for_this_thread();
        if (env == nullptr) return 0;
        jobject buffer = env->NewDirectByteBuffer(data, static_cast<jlong>(length));
        if (buffer == nullptr) {
            clear_exception(env);
            return 0;
        }
        const jint written =
            env->CallIntMethod(callbacks, on_audio_write_mid, buffer, static_cast<jint>(length),
                               static_cast<jint>(timeout_ms));
        env->DeleteLocalRef(buffer);
        if (clear_exception(env) || written < 0) return 0;
        return std::min(static_cast<size_t>(written), length);
    }

    void on_stream_start() override {
        const auto& params = player->get_current_stream_params();
        JNIEnv* env = env_for_this_thread();
        if (env == nullptr) return;
        env->CallVoidMethod(callbacks, on_stream_start_mid,
                            static_cast<jint>(params.sample_rate.value_or(48000)),
                            static_cast<jint>(params.channels.value_or(2)),
                            static_cast<jint>(params.bit_depth.value_or(16)));
        clear_exception(env);
    }

    void on_stream_end() override {
        JNIEnv* env = env_for_this_thread();
        if (env == nullptr) return;
        env->CallVoidMethod(callbacks, on_stream_end_mid);
        clear_exception(env);
    }

    void on_volume_changed(uint8_t volume) override {
        JNIEnv* env = env_for_this_thread();
        if (env == nullptr) return;
        env->CallVoidMethod(callbacks, on_volume_mid, static_cast<jint>(volume));
        clear_exception(env);
    }

    void on_mute_changed(bool muted) override {
        JNIEnv* env = env_for_this_thread();
        if (env == nullptr) return;
        env->CallVoidMethod(callbacks, on_mute_mid, static_cast<jboolean>(muted));
        clear_exception(env);
    }

    // --- MetadataRoleListener -----------------------------------------------

    // Delivers the full merged track state, not just what changed. Absent fields arrive as null
    // (strings) or -1 (numbers).
    void on_metadata(const sendspin::ServerMetadataStateObject& md) override {
        JNIEnv* env = env_for_this_thread();
        if (env == nullptr) return;
        jstring title = optional_jstring(env, md.title);
        jstring artist = optional_jstring(env, md.artist);
        jstring album = optional_jstring(env, md.album);
        jstring artwork_url = optional_jstring(env, md.artwork_url);
        const jlong progress = md.progress ? static_cast<jlong>(md.progress->track_progress) : -1;
        const jlong duration = md.progress ? static_cast<jlong>(md.progress->track_duration) : -1;
        const jint speed = md.progress ? static_cast<jint>(md.progress->playback_speed) : -1;
        env->CallVoidMethod(callbacks, on_metadata_mid, title, artist, album, artwork_url, progress,
                            duration, speed);
        clear_exception(env);
        for (jstring s : {title, artist, album, artwork_url}) {
            if (s != nullptr) env->DeleteLocalRef(s);
        }
    }

    void on_metadata_clear() override {
        JNIEnv* env = env_for_this_thread();
        if (env == nullptr) return;
        env->CallVoidMethod(callbacks, on_metadata_clear_mid);
        clear_exception(env);
    }

    // --- ControllerRoleListener ---------------------------------------------

    // Tells the overlay which transport buttons the server will honour right now, and the
    // shuffle and repeat modes it is in.
    void on_controller_state(const sendspin::ServerStateControllerObject& state) override {
        const char* repeat = state.repeat == sendspin::SendspinRepeatMode::ONE   ? "one"
                             : state.repeat == sendspin::SendspinRepeatMode::ALL ? "all"
                                                                                : "off";
        report_controller(state.supported_commands, state.shuffle, repeat);
    }

    void on_controller_state_clear() override { report_controller({}, false, "off"); }

    void report_controller(const std::vector<sendspin::SendspinControllerCommand>& commands,
                           bool shuffle, const char* repeat) {
        JNIEnv* env = env_for_this_thread();
        if (env == nullptr) return;
        std::string joined;
        for (const auto command : commands) {
            const char* name = command_name(command);
            if (name == nullptr) continue;
            if (!joined.empty()) joined.push_back(',');
            joined.append(name);
        }
        jstring list = env->NewStringUTF(joined.c_str());
        jstring repeat_str = env->NewStringUTF(repeat);
        env->CallVoidMethod(callbacks, on_controller_mid, list, static_cast<jboolean>(shuffle),
                            repeat_str);
        clear_exception(env);
        env->DeleteLocalRef(list);
        env->DeleteLocalRef(repeat_str);
    }

    // --- ColorRoleListener --------------------------------------------------

    // The palette the server derived from the track. Colours it doesn't give arrive as 0.
    void on_color(const sendspin::ServerColorStateObject& colors) override {
        report_colors(pack_color(colors.background_dark), pack_color(colors.background_light),
                      pack_color(colors.on_dark), pack_color(colors.on_light));
    }

    void on_color_clear() override { report_colors(0, 0, 0, 0); }

    void report_colors(jint background_dark, jint background_light, jint on_dark, jint on_light) {
        JNIEnv* env = env_for_this_thread();
        if (env == nullptr) return;
        env->CallVoidMethod(callbacks, on_colors_mid, background_dark, background_light, on_dark,
                            on_light);
        clear_exception(env);
    }

    // --- ArtworkRoleListener (Odysseus fork) --------------------------------

    // One slot (0, album art). The encoded image goes to Kotlin as it arrives (on the role's
    // decode thread), which decodes it; it is shown when on_image_display fires at the server's
    // timestamp, i.e. at the track change it belongs to.
    void on_image_decode(uint8_t slot, const uint8_t* data, size_t length,
                         sendspin::SendspinImageFormat /*format*/) override {
        if (slot != 0 || data == nullptr || length == 0 || length > kMaxArtworkBytes) return;
        JNIEnv* env = env_for_this_thread();
        if (env == nullptr) return;
        jbyteArray bytes = env->NewByteArray(static_cast<jsize>(length));
        if (bytes == nullptr) {
            clear_exception(env);
            return;
        }
        env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(length),
                                reinterpret_cast<const jbyte*>(data));
        env->CallVoidMethod(callbacks, on_artwork_image_mid, bytes);
        clear_exception(env);
        env->DeleteLocalRef(bytes);
    }

    void on_image_display(uint8_t slot, uint32_t /*lateness_ms*/) override {
        if (slot != 0) return;
        JNIEnv* env = env_for_this_thread();
        if (env == nullptr) return;
        env->CallVoidMethod(callbacks, on_artwork_display_mid);
        clear_exception(env);
    }

    void on_image_clear(uint8_t slot) override {
        if (slot != 0) return;
        JNIEnv* env = env_for_this_thread();
        if (env == nullptr) return;
        env->CallVoidMethod(callbacks, on_artwork_clear_mid);
        clear_exception(env);
    }

    static constexpr size_t kMaxArtworkBytes = 4 * 1024 * 1024;

    // --- SendspinClientListener ---------------------------------------------

    void on_group_update(const sendspin::GroupUpdateObject& group) override {
        if (!group.playback_state && !group.group_id) return;
        JNIEnv* env = env_for_this_thread();
        if (env == nullptr) return;
        // Updates are deltas: either field may be absent.
        if (group.group_id) {
            jstring id = env->NewStringUTF(group.group_id->c_str());
            env->CallVoidMethod(callbacks, on_group_id_mid, id);
            clear_exception(env);
            env->DeleteLocalRef(id);
        }
        if (group.playback_state) {
            const bool playing = *group.playback_state == sendspin::SendspinPlaybackState::PLAYING;
            env->CallVoidMethod(callbacks, on_playback_state_mid, static_cast<jboolean>(playing));
            clear_exception(env);
        }
    }

    // --- Pairing (Odysseus fork, sendspin-cpp v0.9) --------------------------
    // All of these fire on the loop thread (inside client->loop() or client->stop()).

    // Which pre-shared key the server's handshake used: a pairing record (paired) or not.
    void on_trust_changed(sendspin::ConnectionTrust trust) override {
        server_paired = trust == sendspin::ConnectionTrust::USER;
        // A server still waiting on the preferred-server check is checked again at once. One
        // already let through stays (clearing verified_id would report a disconnect mid-song
        // at the rekey that follows a pairing).
        next_check = {};
        JNIEnv* env = env_for_this_thread();
        if (env == nullptr) return;
        env->CallVoidMethod(callbacks, on_trust_mid, static_cast<jboolean>(server_paired));
        clear_exception(env);
    }

    // The six-digit code for the server's pairing attempt; null clears it.
    void on_display_pairing_code(const std::string& code,
                                 sendspin::SendspinPairingCodeFormat format) override {
        if (format != sendspin::SendspinPairingCodeFormat::DIGITS) return;  // only DIGITS offered
        report_pairing_code(code.c_str());
    }

    void on_clear_pairing_code() override { report_pairing_code(nullptr); }

    void report_pairing_code(const char* code) {
        JNIEnv* env = env_for_this_thread();
        if (env == nullptr) return;
        jstring value = code != nullptr ? env->NewStringUTF(code) : nullptr;
        env->CallVoidMethod(callbacks, on_pairing_code_mid, value);
        clear_exception(env);
        if (value != nullptr) env->DeleteLocalRef(value);
    }

    // An attempt is waiting for the operator to allow pairing on the device (after too many
    // wrong codes); confirm_pairing_window() lets it through.
    void on_open_pairing_window() override { report_pairing_window(true); }

    void on_close_pairing_window() override { report_pairing_window(false); }

    void report_pairing_window(bool open) {
        JNIEnv* env = env_for_this_thread();
        if (env == nullptr) return;
        env->CallVoidMethod(callbacks, on_pairing_window_mid, static_cast<jboolean>(open));
        clear_exception(env);
    }

    void on_pairing_started(const std::string& server_id) override {
        report_pairing_result(0, server_id, nullptr);
    }

    void on_pairing_succeeded(const std::string& server_id) override {
        report_pairing_result(1, server_id, nullptr);
    }

    void on_pairing_failed(const std::string& server_id,
                           sendspin::SendspinPairAbortReason reason) override {
        report_pairing_result(2, server_id, abort_reason_name(reason));
    }

    static const char* abort_reason_name(sendspin::SendspinPairAbortReason reason) {
        switch (reason) {
            case sendspin::SendspinPairAbortReason::ATTEMPT_TIMEOUT: return "timeout";
            case sendspin::SendspinPairAbortReason::CONCURRENT_ATTEMPT: return "concurrent_attempt";
            case sendspin::SendspinPairAbortReason::METHOD_NOT_SUPPORTED: return "method_not_supported";
            case sendspin::SendspinPairAbortReason::PAIRING_CODE_MISMATCH: return "code_mismatch";
            case sendspin::SendspinPairAbortReason::USER_CANCELLED: return "cancelled";
            case sendspin::SendspinPairAbortReason::UNKNOWN: break;
        }
        return "unknown";
    }

    // stage: 0 started, 1 succeeded, 2 failed ([reason] set).
    void report_pairing_result(jint stage, const std::string& server_id, const char* reason) {
        JNIEnv* env = env_for_this_thread();
        if (env == nullptr) return;
        jstring id = env->NewStringUTF(server_id.c_str());
        jstring why = reason != nullptr ? env->NewStringUTF(reason) : nullptr;
        env->CallVoidMethod(callbacks, on_pairing_result_mid, stage, id, why);
        clear_exception(env);
        env->DeleteLocalRef(id);
        if (why != nullptr) env->DeleteLocalRef(why);
    }

    // --- SendspinPersistenceProvider (Odysseus fork) -------------------------
    // Called on the loop thread only. Byte-for-byte storage; the library owns the format.

    std::optional<std::vector<uint8_t>> load_blob(const std::string& key) override {
        if (!store) return std::nullopt;
        return store->load(key);
    }

    bool save_blob(const std::string& key, const uint8_t* data, size_t len) override {
        if (!store) return false;
        const bool ok = store->save(key, data, len);
        if (!ok) __android_log_print(ANDROID_LOG_WARN, TAG, "could not store '%s'", key.c_str());
        return ok;
    }

    bool commit() override { return !store || store->commit(); }

    // --- SendspinNetworkProvider --------------------------------------------

    // Android gives no cheap "network ready" signal here, and the server socket binds all
    // interfaces, so there is nothing useful to gate on.
    bool is_network_ready() override { return true; }
};

Bridge* bridge_from(jlong handle) { return reinterpret_cast<Bridge*>(handle); }

}  // namespace

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void*) {
    g_vm = vm;
    pthread_key_create(&g_detach_key, detach_thread);
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jlong JNICALL Java_com_msp1974_sendspin_NativeSendspin_nativeCreate(
    JNIEnv* env, jobject, jobject callbacks, jstring storage_dir, jstring name,
    jstring product_name, jstring manufacturer, jstring software_version, jint server_port,
    jint audio_buffer_capacity, jintArray formats, jboolean unpaired_access) {
    forward_stderr_to_logcat();

    auto bridge = std::make_unique<Bridge>();

    jclass cls = env->GetObjectClass(callbacks);
    bridge->on_audio_write_mid = find_method(env, cls, "onAudioWrite", "(Ljava/nio/ByteBuffer;II)I");
    bridge->on_stream_start_mid = find_method(env, cls, "onStreamStart", "(III)V");
    bridge->on_stream_end_mid = find_method(env, cls, "onStreamEnd", "()V");
    bridge->on_volume_mid = find_method(env, cls, "onVolumeChanged", "(I)V");
    bridge->on_mute_mid = find_method(env, cls, "onMuteChanged", "(Z)V");
    bridge->on_connection_mid =
        find_method(env, cls, "onConnectionChanged", "(ZLjava/lang/String;)V");
    bridge->on_metadata_mid = find_method(
        env, cls, "onMetadata",
        "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;JJI)V");
    bridge->on_metadata_clear_mid = find_method(env, cls, "onMetadataCleared", "()V");
    bridge->on_playback_state_mid = find_method(env, cls, "onPlaybackStateChanged", "(Z)V");
    bridge->on_group_id_mid = find_method(env, cls, "onGroupChanged", "(Ljava/lang/String;)V");
    bridge->on_controller_mid =
        find_method(env, cls, "onControllerState", "(Ljava/lang/String;ZLjava/lang/String;)V");
    bridge->on_colors_mid = find_method(env, cls, "onColors", "(IIII)V");
    bridge->on_artwork_image_mid = find_method(env, cls, "onArtworkImage", "([B)V");
    bridge->on_artwork_display_mid = find_method(env, cls, "onArtworkDisplay", "()V");
    bridge->on_artwork_clear_mid = find_method(env, cls, "onArtworkClear", "()V");
    bridge->on_trust_mid = find_method(env, cls, "onTrustChanged", "(Z)V");
    bridge->on_pairing_code_mid = find_method(env, cls, "onPairingCode", "(Ljava/lang/String;)V");
    bridge->on_pairing_window_mid = find_method(env, cls, "onPairingWindow", "(Z)V");
    bridge->on_pairing_result_mid =
        find_method(env, cls, "onPairingResult", "(ILjava/lang/String;Ljava/lang/String;)V");
    env->DeleteLocalRef(cls);
    if (bridge->on_metadata_mid == nullptr || bridge->on_metadata_clear_mid == nullptr ||
        bridge->on_playback_state_mid == nullptr || bridge->on_group_id_mid == nullptr ||
        bridge->on_controller_mid == nullptr ||
        bridge->on_colors_mid == nullptr || bridge->on_artwork_image_mid == nullptr ||
        bridge->on_artwork_display_mid == nullptr || bridge->on_artwork_clear_mid == nullptr ||
        bridge->on_audio_write_mid == nullptr || bridge->on_stream_start_mid == nullptr ||
        bridge->on_stream_end_mid == nullptr || bridge->on_volume_mid == nullptr ||
        bridge->on_mute_mid == nullptr || bridge->on_connection_mid == nullptr ||
        bridge->on_trust_mid == nullptr || bridge->on_pairing_code_mid == nullptr ||
        bridge->on_pairing_window_mid == nullptr || bridge->on_pairing_result_mid == nullptr) {
        return 0;
    }
    // Without its key store the device would get a new identity, and forget every pairing, on
    // each start, so refuse to run rather than do that silently.
    bridge->store = std::make_unique<odysseus::BlobStore>(to_std_string(env, storage_dir));
    if (!bridge->store->ensure_dir()) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "no key store directory");
        return 0;
    }
    bridge->callbacks = env->NewGlobalRef(callbacks);

    sendspin::SendspinClientConfig config;
    // The client id is no longer chosen here: v0.9 derives it from the device key.
    config.name = to_std_string(env, name);
    config.product_name = to_std_string(env, product_name);
    config.manufacturer = to_std_string(env, manufacturer);
    config.software_version = to_std_string(env, software_version);
    config.server_port = static_cast<uint16_t>(server_port);
    // Pairing by a six-digit code the device shows for each attempt (the Pairing PSK method is
    // always offered as well). No static code: there is no label to print one on.
    config.pairing_code_out_channels = {sendspin::SendspinPairingCodeChannel::DISPLAY};
    config.pairing_code_formats = {sendspin::SendspinPairingCodeFormat::DIGITS};
    bridge->listen_port = config.server_port;
    bridge->client = std::make_unique<sendspin::SendspinClient>(std::move(config));

    // formats: four ints per entry - codec (0 flac, 1 opus, 2 pcm), channels, sample rate, bit
    // depth - in the order they are offered to the server.
    sendspin::PlayerRoleConfig player_config;
    const jsize formats_len = env->GetArrayLength(formats);
    jint* f = env->GetIntArrayElements(formats, nullptr);
    for (jsize i = 0; i + 3 < formats_len; i += 4) {
        sendspin::AudioSupportedFormatObject fmt;
        switch (f[i]) {
            case 0: fmt.codec = sendspin::SendspinCodecFormat::FLAC; break;
            case 1: fmt.codec = sendspin::SendspinCodecFormat::OPUS; break;
            default: fmt.codec = sendspin::SendspinCodecFormat::PCM; break;
        }
        fmt.channels = static_cast<uint8_t>(f[i + 1]);
        fmt.sample_rate = static_cast<uint32_t>(f[i + 2]);
        fmt.bit_depth = static_cast<uint8_t>(f[i + 3]);
        player_config.audio_formats.push_back(fmt);
    }
    env->ReleaseIntArrayElements(formats, f, JNI_ABORT);
    player_config.audio_buffer_capacity = static_cast<size_t>(audio_buffer_capacity);

    bridge->player = &bridge->client->add_player(std::move(player_config));
    bridge->player->set_listener(bridge.get());
    bridge->player->set_output_delay_adjustable(true);

    bridge->controller = &bridge->client->add_controller();
    bridge->controller->set_listener(bridge.get());
    bridge->metadata = &bridge->client->add_metadata();
    bridge->metadata->set_listener(bridge.get());
    bridge->color = &bridge->client->add_color();
    bridge->color->set_listener(bridge.get());
    // Odysseus fork: album art pushed by the server, sized for the overlay's artwork (up to
    // 168 dp) on the Echo's screen. JPEG keeps it small.
    sendspin::ArtworkRoleConfig artwork_config;
    sendspin::ImageSlotPreference album;
    album.source = sendspin::SendspinImageSource::ALBUM;
    album.format = sendspin::SendspinImageFormat::JPEG;
    album.width = 320;
    album.height = 320;
    artwork_config.preferred_formats.push_back(album);
    bridge->artwork = &bridge->client->add_artwork(std::move(artwork_config));
    bridge->artwork->set_listener(bridge.get());
    bridge->client->set_listener(bridge.get());
    bridge->client->set_network_provider(bridge.get());
    bridge->client->set_persistence_provider(bridge.get());
    // Off by default in the library; the app keeps the owner's choice (see SendspinPlayer).
    bridge->client->set_unpaired_access_enabled(unpaired_access == JNI_TRUE);

    return reinterpret_cast<jlong>(bridge.release());
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_msp1974_sendspin_NativeSendspin_nativeStart(
    JNIEnv*, jobject, jlong handle) {
    Bridge* bridge = bridge_from(handle);
    if (bridge == nullptr || bridge->running.load()) return JNI_FALSE;
    bridge->running.store(true);
    std::promise<bool> started;
    std::future<bool> result = started.get_future();
    bridge->loop_thread = std::thread(
        [bridge, p = std::move(started)]() mutable { bridge->run_loop(std::move(p)); });
    if (!result.get()) {
        __android_log_write(ANDROID_LOG_ERROR, TAG, "client start failed");
        bridge->running.store(false);
        bridge->loop_thread.join();
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL Java_com_msp1974_sendspin_NativeSendspin_nativeDestroy(
    JNIEnv* env, jobject, jlong handle, jboolean restarting) {
    Bridge* bridge = bridge_from(handle);
    if (bridge == nullptr) return;
    bridge->goodbye_restart.store(restarting == JNI_TRUE);
    // The loop thread stops the client (goodbyes included) on its way out.
    if (bridge->running.exchange(false) && bridge->loop_thread.joinable()) {
        bridge->loop_thread.join();
    }
    if (bridge->callbacks != nullptr) env->DeleteGlobalRef(bridge->callbacks);
    delete bridge;
}

extern "C" JNIEXPORT void JNICALL Java_com_msp1974_sendspin_NativeSendspin_nativeNotifyAudioPlayed(
    JNIEnv*, jobject, jlong handle, jint frames, jlong timestamp_us) {
    Bridge* bridge = bridge_from(handle);
    if (bridge == nullptr || bridge->player == nullptr) return;
    bridge->player->notify_audio_played(static_cast<uint32_t>(frames), timestamp_us);
}

extern "C" JNIEXPORT void JNICALL Java_com_msp1974_sendspin_NativeSendspin_nativeUpdateVolume(
    JNIEnv*, jobject, jlong handle, jint volume) {
    Bridge* bridge = bridge_from(handle);
    if (bridge == nullptr) return;
    const auto value = static_cast<uint8_t>(std::clamp(static_cast<int>(volume), 0, 100));
    bridge->post([bridge, value] { bridge->player->update_volume(value); });
}

extern "C" JNIEXPORT void JNICALL Java_com_msp1974_sendspin_NativeSendspin_nativeUpdateMuted(
    JNIEnv*, jobject, jlong handle, jboolean muted) {
    Bridge* bridge = bridge_from(handle);
    if (bridge == nullptr) return;
    const bool value = muted == JNI_TRUE;
    bridge->post([bridge, value] { bridge->player->update_muted(value); });
}

// Sends a transport command ("play", "pause", "next", ...) to the server. [arg] is the target
// position in ms for "seek". False if the command is unknown.
extern "C" JNIEXPORT jboolean JNICALL Java_com_msp1974_sendspin_NativeSendspin_nativeSendCommand(
    JNIEnv* env, jobject, jlong handle, jstring command, jlong arg) {
    Bridge* bridge = bridge_from(handle);
    if (bridge == nullptr || bridge->controller == nullptr) return JNI_FALSE;
    const auto parsed = command_from_name(to_std_string(env, command));
    if (!parsed) return JNI_FALSE;
    bridge->post([bridge, command = *parsed, arg] {
        sendspin::ClientCommandControllerObject obj;
        obj.command = command;
        if (command == sendspin::SendspinControllerCommand::SEEK) {
            obj.position_ms = static_cast<uint32_t>(std::max<jlong>(arg, 0));
        }
        bridge->controller->send_command(obj);
    });
    return JNI_TRUE;
}

// Sets the IPv4 address of the only server allowed to connect; blank allows any. A connected
// server at another address is dropped on the loop's next pass.
extern "C" JNIEXPORT void JNICALL Java_com_msp1974_sendspin_NativeSendspin_nativeSetPreferredServer(
    JNIEnv* env, jobject, jlong handle, jstring ip) {
    Bridge* bridge = bridge_from(handle);
    if (bridge == nullptr) return;
    std::string value = to_std_string(env, ip);
    bridge->post([bridge, value = std::move(value)] {
        bridge->preferred_ip = value;
        bridge->verified_id.clear();
        bridge->next_check = {};
    });
}

// Odysseus fork: sets the ids of servers allowed from any address, separated by commas or
// whitespace; blank trusts none. Changing it re-checks a connected server.
extern "C" JNIEXPORT void JNICALL Java_com_msp1974_sendspin_NativeSendspin_nativeSetTrustedServerIds(
    JNIEnv* env, jobject, jlong handle, jstring ids) {
    Bridge* bridge = bridge_from(handle);
    if (bridge == nullptr) return;
    std::string value = to_std_string(env, ids);
    std::replace(value.begin(), value.end(), ',', ' ');
    std::set<std::string> parsed;
    std::istringstream stream(value);
    for (std::string id; stream >> id;) parsed.insert(id);
    bridge->post([bridge, parsed = std::move(parsed)] {
        bridge->trusted_ids = parsed;
        bridge->verified_id.clear();
        bridge->next_check = {};
    });
}

// Odysseus fork: whether servers that haven't paired may play (thread-safe in the library). Turning
// it off drops an unpaired server that is playing; turning it on reconnects idle unpaired ones.
extern "C" JNIEXPORT void JNICALL Java_com_msp1974_sendspin_NativeSendspin_nativeSetUnpairedAccess(
    JNIEnv*, jobject, jlong handle, jboolean enabled) {
    Bridge* bridge = bridge_from(handle);
    if (bridge == nullptr) return;
    bridge->client->set_unpaired_access_enabled(enabled == JNI_TRUE);
}

// Odysseus fork: the operator allowed pairing on the device (thread-safe in the library). With no
// attempt waiting it opens a 5-minute window for the next one.
extern "C" JNIEXPORT void JNICALL Java_com_msp1974_sendspin_NativeSendspin_nativeConfirmPairingWindow(
    JNIEnv*, jobject, jlong handle) {
    Bridge* bridge = bridge_from(handle);
    if (bridge == nullptr) return;
    bridge->client->confirm_pairing_window();
}

extern "C" JNIEXPORT void JNICALL Java_com_msp1974_sendspin_NativeSendspin_nativeCancelPairingWindow(
    JNIEnv*, jobject, jlong handle) {
    Bridge* bridge = bridge_from(handle);
    if (bridge == nullptr) return;
    bridge->client->cancel_pairing_window();
}

// Odysseus fork: the id Music Assistant knows this player by (base64url of the device's public
// key; not a secret). Empty before start.
extern "C" JNIEXPORT jstring JNICALL Java_com_msp1974_sendspin_NativeSendspin_nativeClientId(
    JNIEnv* env, jobject, jlong handle) {
    Bridge* bridge = bridge_from(handle);
    return env->NewStringUTF(bridge != nullptr ? bridge->client_id.c_str() : "");
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_msp1974_sendspin_NativeSendspin_nativeIsConnected(
    JNIEnv*, jobject, jlong handle) {
    Bridge* bridge = bridge_from(handle);
    return bridge != nullptr && bridge->connected.load() ? JNI_TRUE : JNI_FALSE;
}

// CLOCK_MONOTONIC in microseconds - the clock domain notify_audio_played() timestamps use, and the
// same one System.nanoTime() and AudioTimestamp.nanoTime read.
extern "C" JNIEXPORT jlong JNICALL Java_com_msp1974_sendspin_NativeSendspin_nativeMonotonicTimeUs(
    JNIEnv*, jobject) {
    const auto now = std::chrono::steady_clock::now().time_since_epoch();
    return std::chrono::duration_cast<std::chrono::microseconds>(now).count();
}

extern "C" JNIEXPORT void JNICALL Java_com_msp1974_sendspin_NativeSendspin_nativeSetLogLevel(
    JNIEnv*, jobject, jint level) {
    sendspin::SendspinClient::set_log_level(static_cast<sendspin::LogLevel>(level));
}
