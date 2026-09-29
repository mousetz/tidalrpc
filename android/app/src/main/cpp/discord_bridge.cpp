#define DISCORDPP_IMPLEMENTATION
#include "discordpp.h"

#include <jni.h>
#include <algorithm>
#include <atomic>
#include <codecvt>
#include <locale>
#include <memory>
#include <string>

namespace {
std::unique_ptr<discordpp::Client> client;
std::atomic<int> result{0};
std::atomic<uint64_t> generation{0};

std::string utf8(JNIEnv *env, jstring value) {
    if (!value) return {};
    const jchar *chars = env->GetStringChars(value, nullptr);
    const jsize length = env->GetStringLength(value);
    std::u16string text(reinterpret_cast<const char16_t *>(chars), length);
    env->ReleaseStringChars(value, chars);
    return std::wstring_convert<std::codecvt_utf8_utf16<char16_t>, char16_t>{}.to_bytes(text);
}

std::string field(std::string value, const char *fallback) {
    if (value.empty()) value = fallback;
    if (value.size() == 1) value += " ";
    if (value.size() > 128) {
        size_t cut = 128;
        while (cut > 0 && (static_cast<unsigned char>(value[cut]) & 0xc0) == 0x80) --cut;
        value.resize(cut);
    }
    return value;
}
}  // namespace

extern "C" JNIEXPORT void JNICALL
Java_com_mousetz_tidalrpc_DiscordBridge_nativeStart(JNIEnv *, jobject, jlong appId) {
    if (!client) {
        client = std::make_unique<discordpp::Client>();
        client->SetApplicationId(static_cast<uint64_t>(appId));
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_mousetz_tidalrpc_DiscordBridge_nativeUpdate(
    JNIEnv *env, jobject, jstring title, jstring artist, jstring album,
    jstring largeImage, jstring smallImage, jlong startMs, jlong endMs,
    jstring url, jboolean showButtons) {
    if (!client) return;
    discordpp::Activity activity;
    activity.SetName("TIDAL");
    activity.SetType(discordpp::ActivityTypes::Listening);
    activity.SetDetails(field(utf8(env, title), "TIDAL track"));
    activity.SetState(field(utf8(env, artist), "Unknown artist"));

    discordpp::ActivityAssets assets;
    auto large = utf8(env, largeImage);
    assets.SetLargeImage(large.empty() ? "hightide_x1024" : large);
    assets.SetLargeText(field(utf8(env, album), "TIDAL"));
    auto small = utf8(env, smallImage);
    if (!small.empty()) {
        assets.SetSmallImage(small);
        assets.SetSmallText("TIDAL RPC");
    }
    activity.SetAssets(assets);

    if (startMs > 0) {
        discordpp::ActivityTimestamps time;
        time.SetStart(static_cast<uint64_t>(startMs));
        if (endMs > startMs) time.SetEnd(static_cast<uint64_t>(endMs));
        activity.SetTimestamps(time);
    }

    if (showButtons) {
        auto trackUrl = utf8(env, url);
        if (trackUrl.rfind("https://tidal.com/", 0) == 0) {
            discordpp::ActivityButton listen;
            listen.SetLabel("Listen on TIDAL");
            listen.SetUrl(trackUrl);
            activity.AddButton(listen);
        }
        discordpp::ActivityButton getApp;
        getApp.SetLabel("Get TIDAL RPC");
        getApp.SetUrl("https://github.com/mousetz/tidalrpc");
        activity.AddButton(getApp);
    }
    const auto request = ++generation;
    result = 0;
    client->UpdateRichPresence(activity, [request](discordpp::ClientResult response) {
        if (request == generation.load()) result = response.Successful() ? 1 : -1;
    });
}

extern "C" JNIEXPORT jint JNICALL
Java_com_mousetz_tidalrpc_DiscordBridge_nativePump(JNIEnv *, jobject) {
    discordpp::RunCallbacks();
    return result.load();
}

extern "C" JNIEXPORT void JNICALL
Java_com_mousetz_tidalrpc_DiscordBridge_nativeClear(JNIEnv *, jobject) {
    ++generation;
    if (client) client->ClearRichPresence();
    discordpp::RunCallbacks();
    client.reset();
    result = 0;
}
