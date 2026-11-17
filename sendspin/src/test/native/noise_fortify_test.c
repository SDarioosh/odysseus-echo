// Odysseus fork: regression test for the FORTIFY abort in noise-c's noise_pattern_expand
// (memchr told to scan 62 bytes of a ~10-byte static pattern). bionic's __memchr_chk aborts the
// whole process for that, which killed the app whenever Music Assistant opened an encrypted
// Sendspin connection. Built for the device by run_noise_fortify_test.sh and run over adb,
// because only bionic's fortified memchr shows the bug.
#include <noise/protocol.h>
#include <stdio.h>

int main(void) {
    // The suite sendspin-cpp uses (src/crypto/constants.h), both roles, plus every base pattern
    // with a modifier, so any other short pattern array is covered too.
    static const char *names[] = {
        "Noise_KKpsk2_25519_ChaChaPoly_SHA256",
        "Noise_NN_25519_ChaChaPoly_SHA256", "Noise_NK_25519_ChaChaPoly_SHA256",
        "Noise_KN_25519_ChaChaPoly_SHA256", "Noise_KK_25519_ChaChaPoly_SHA256",
        "Noise_XX_25519_ChaChaPoly_SHA256", "Noise_IK_25519_ChaChaPoly_SHA256",
        "Noise_N_25519_ChaChaPoly_SHA256",  "Noise_K_25519_ChaChaPoly_SHA256",
        "Noise_X_25519_ChaChaPoly_SHA256",  "Noise_NNpsk0_25519_ChaChaPoly_SHA256",
        "Noise_XXfallback_25519_ChaChaPoly_SHA256",
    };
    static const int roles[] = { NOISE_ROLE_INITIATOR, NOISE_ROLE_RESPONDER };
    int failures = 0;
    for (size_t i = 0; i < sizeof(names) / sizeof(names[0]); ++i) {
        for (size_t r = 0; r < 2; ++r) {
            NoiseHandshakeState *hs = NULL;
            int err = noise_handshakestate_new_by_name(&hs, names[i], roles[r]);
            if (err != NOISE_ERROR_NONE) {
                char msg[64];
                noise_strerror(err, msg, sizeof(msg));
                printf("FAIL %s role=%d: %s\n", names[i], roles[r], msg);
                ++failures;
            }
            if (hs) noise_handshakestate_free(hs);
        }
    }
    printf(failures ? "noise fortify test: %d FAILED\n" : "noise fortify test: OK%.0d\n", failures);
    return failures ? 1 : 0;
}
