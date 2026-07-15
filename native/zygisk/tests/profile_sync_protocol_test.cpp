#include "runtime/profile_sync_protocol.h"

#include <cstdio>
#include <string>
#include <string_view>

namespace
{
    int g_failures = 0;

    void Check(bool condition, const char *expression, int line, std::string_view message)
    {
        if (!condition)
        {
            std::fprintf(stderr,
                         "FAIL line %d: %s (%.*s)\n",
                         line,
                         expression,
                         static_cast<int>(message.size()),
                         message.data());
            ++g_failures;
        }
    }

#define CHECK(condition, message) Check((condition), #condition, __LINE__, (message))

    std::string Envelope(std::string_view overrides = {})
    {
        std::string json =
            R"({"schemaVersion":2,"generation":7,)"
            R"("profiles":{"default":{"id":"default","modules":[],"engine":{}},)"
            R"("bound":{"id":"bound","modules":[],"engine":{}}},)"
            R"("defaultProfileId":"default",)"
            R"("appBindings":{"com.example.app":"bound"},)"
            R"("whitelist":{"com.example.app":true},)"
            R"("captureOwners":{"com.example.app":"zygisk"},)"
            R"("control":{"masterEnabled":true,"bypass":false,)"
            R"("panicUntilEpochMs":0,"sidetoneEnabled":false,)"
            R"("sidetoneGainDb":0.0,"engineMode":"native_first"}})";
        if (!overrides.empty())
        {
            json = std::string(overrides);
        }
        return json;
    }

    bool Decode(std::string_view payload,
                std::string_view process,
                echidna::runtime::DecodedProfileSnapshot *snapshot,
                std::string *error,
                uint64_t now_ms = 1000)
    {
        return echidna::runtime::DecodeProfileSyncV2(
            payload, process, now_ms, snapshot, error);
    }

    void TestValidSelectionAndAdmission()
    {
        echidna::runtime::DecodedProfileSnapshot snapshot;
        std::string error;
        CHECK(Decode(Envelope(), "com.example.app:capture", &snapshot, &error), error);
        CHECK(snapshot.generation == 7, "generation must decode");
        CHECK(snapshot.nativeProcessAdmitted(), "base policy must admit colon process");
        CHECK(snapshot.profile_id == "bound", "base package binding must select bound profile");
        CHECK(snapshot.preset_json.find("\"id\":\"bound\"") != std::string::npos,
              "selected raw preset must be process-local bound profile");
    }

    void TestExactOverridesAndOwnerHandshake()
    {
        const std::string exact_deny =
            R"({"schemaVersion":2,"generation":8,)"
            R"("profiles":{"default":{"modules":[],"engine":{}}},)"
            R"("defaultProfileId":"default","appBindings":{},)"
            R"("whitelist":{"com.example.app":true,"com.example.app:capture":false},)"
            R"("captureOwners":{"com.example.app":"zygisk"},)"
            R"("control":{"masterEnabled":true,"bypass":false,"panicUntilEpochMs":0,)"
            R"("sidetoneEnabled":false,"sidetoneGainDb":0.0,)"
            R"("engineMode":"native_first"}})";
        echidna::runtime::DecodedProfileSnapshot snapshot;
        std::string error;
        CHECK(Decode(exact_deny, "com.example.app:capture", &snapshot, &error), error);
        CHECK(!snapshot.process_whitelisted, "exact false must override base true");
        CHECK(!snapshot.nativeProcessAdmitted(), "valid deny must remain inert");

        const std::string owner_override =
            R"({"schemaVersion":2,"generation":9,)"
            R"("profiles":{"default":{"modules":[],"engine":{}}},)"
            R"("defaultProfileId":"default","appBindings":{},)"
            R"("whitelist":{"com.example.app":true},)"
            R"("captureOwners":{"com.example.app":"zygisk",)"
            R"("com.example.app:capture":"lsposed"},)"
            R"("control":{"masterEnabled":true,"bypass":false,"panicUntilEpochMs":0,)"
            R"("sidetoneEnabled":false,"sidetoneGainDb":0.0,)"
            R"("engineMode":"native_first"}})";
        CHECK(Decode(owner_override, "com.example.app:capture", &snapshot, &error), error);
        CHECK(snapshot.process_whitelisted, "owner override must not alter whitelist");
        CHECK(snapshot.capture_owner == echidna::runtime::CaptureOwner::kLsposed,
              "exact LSPosed owner must override base Zygisk owner");
        CHECK(!snapshot.nativeProcessAdmitted(), "owner mismatch must block native processing");

        const std::string no_owner =
            R"({"schemaVersion":2,"generation":10,)"
            R"("profiles":{"default":{"modules":[],"engine":{}}},)"
            R"("defaultProfileId":"default","appBindings":{},)"
            R"("whitelist":{"com.example.app":true},"captureOwners":{},)"
            R"("control":{"masterEnabled":true,"bypass":false,"panicUntilEpochMs":0,)"
            R"("sidetoneEnabled":false,"sidetoneGainDb":0.0,)"
            R"("engineMode":"native_first"}})";
        CHECK(Decode(no_owner, "com.example.app", &snapshot, &error), error);
        CHECK(!snapshot.nativeProcessAdmitted(), "absent owner must fail closed");
    }

    void TestGlobalControlGates()
    {
        const std::string panic =
            R"({"schemaVersion":2,"generation":11,)"
            R"("profiles":{"default":{"modules":[],"engine":{}}},)"
            R"("defaultProfileId":"default","appBindings":{},)"
            R"("whitelist":{"com.example.app":true},)"
            R"("captureOwners":{"com.example.app":"zygisk"},)"
            R"("control":{"masterEnabled":true,"bypass":false,"panicUntilEpochMs":2000,)"
            R"("sidetoneEnabled":false,"sidetoneGainDb":0.0,)"
            R"("engineMode":"native_first"}})";
        echidna::runtime::DecodedProfileSnapshot snapshot;
        std::string error;
        CHECK(Decode(panic, "com.example.app", &snapshot, &error, 1999), error);
        CHECK(!snapshot.global_hooks_enabled, "active panic deadline must disable globally");
        CHECK(Decode(panic, "com.example.app", &snapshot, &error, 2000), error);
        CHECK(snapshot.global_hooks_enabled, "expired panic deadline must restore base state");

        std::string compatibility = panic;
        const std::string old_control =
            "\"panicUntilEpochMs\":2000";
        compatibility.replace(compatibility.find(old_control),
                              old_control.size(),
                              "\"panicUntilEpochMs\":0");
        const std::string native_mode = "\"engineMode\":\"native_first\"";
        compatibility.replace(compatibility.find(native_mode),
                              native_mode.size(),
                              "\"engineMode\":\"compatibility\"");
        CHECK(Decode(compatibility, "com.example.app", &snapshot, &error), error);
        CHECK(!snapshot.global_hooks_enabled, "compatibility mode must disable native hooks");
    }

    void ExpectRejected(std::string payload, std::string_view expected_error)
    {
        echidna::runtime::DecodedProfileSnapshot snapshot;
        std::string error;
        CHECK(!Decode(payload, "com.example.app", &snapshot, &error),
              "unsafe envelope unexpectedly accepted");
        CHECK(error.find(expected_error) != std::string::npos, error);
    }

    void TestMalformedAndUnknownRejection()
    {
        ExpectRejected("{", "object key");
        ExpectRejected(
            R"({"schemaVersion":2,"schemaVersion":2})", "duplicate JSON object key");
        ExpectRejected(
            R"({"schemaVersion":2,"generation":1,"profiles":{},)"
            R"("defaultProfileId":"x","appBindings":{},"whitelist":{},)"
            R"("captureOwners":{},"control":{},"surprise":true})",
            "unknown field");
        ExpectRejected(
            R"({"schemaVersion":3,"generation":1,"profiles":{},)"
            R"("defaultProfileId":"x","appBindings":{},"whitelist":{},)"
            R"("captureOwners":{},"control":{}})",
            "schemaVersion");

        std::string invalid_utf8 = Envelope();
        invalid_utf8.insert(1, 1, static_cast<char>(0xc0));
        ExpectRejected(std::move(invalid_utf8), "UTF-8");

        std::string invalid_surrogate = Envelope();
        invalid_surrogate.replace(invalid_surrogate.find("default"),
                                  std::string("default").size(),
                                  "bad\\uD800");
        ExpectRejected(std::move(invalid_surrogate), "surrogate");
    }

    void TestReferenceAndTypeRejection()
    {
        std::string dangling_default = Envelope();
        const size_t default_value = dangling_default.find("\"defaultProfileId\":\"default\"");
        dangling_default.replace(default_value,
                                 std::string("\"defaultProfileId\":\"default\"").size(),
                                 "\"defaultProfileId\":\"missing\"");
        ExpectRejected(std::move(dangling_default), "defaultProfileId");

        std::string dangling_binding = Envelope();
        const size_t bound_value = dangling_binding.find("\"com.example.app\":\"bound\"");
        dangling_binding.replace(bound_value,
                                 std::string("\"com.example.app\":\"bound\"").size(),
                                 "\"com.example.app\":\"missing\"");
        ExpectRejected(std::move(dangling_binding), "appBinding");

        std::string bad_owner = Envelope();
        bad_owner.replace(bad_owner.find("\"zygisk\""),
                          std::string("\"zygisk\"").size(),
                          "\"both\"");
        ExpectRejected(std::move(bad_owner), "capture owner");

        std::string fractional_generation = Envelope();
        fractional_generation.replace(fractional_generation.find("\"generation\":7"),
                                      std::string("\"generation\":7").size(),
                                      "\"generation\":7.0");
        ExpectRejected(std::move(fractional_generation), "generation");

        std::string unknown_control = Envelope();
        const size_t control_end = unknown_control.rfind("}}");
        unknown_control.insert(control_end, ",\"hooksEnabled\":true");
        ExpectRejected(std::move(unknown_control), "unknown field");

        std::string missing_engine_mode = Envelope();
        const std::string engine_field = ",\"engineMode\":\"native_first\"";
        missing_engine_mode.erase(missing_engine_mode.find(engine_field),
                                  engine_field.size());
        ExpectRejected(std::move(missing_engine_mode), "engineMode");
    }

    void TestSizeAndCountBounds()
    {
        std::string oversized(echidna::runtime::kProfileSyncMaxEnvelopeBytes + 1, ' ');
        ExpectRejected(std::move(oversized), "byte length");

        std::string large_preset =
            R"({"schemaVersion":2,"generation":1,"profiles":{"default":)";
        large_preset += R"({"modules":[],"engine":{},"padding":")";
        large_preset.append(echidna::runtime::kProfileSyncMaxPresetBytes, 'x');
        large_preset +=
            R"("}},"defaultProfileId":"default","appBindings":{},)"
            R"("whitelist":{},"captureOwners":{},)"
            R"("control":{"masterEnabled":true,"bypass":false,"panicUntilEpochMs":0,)"
            R"("sidetoneEnabled":false,"sidetoneGainDb":0.0,)"
            R"("engineMode":"native_first"}})";
        ExpectRejected(std::move(large_preset), "256 KiB");

        std::string too_many =
            R"({"schemaVersion":2,"generation":1,"profiles":{"default":)"
            R"({"modules":[],"engine":{}}},"defaultProfileId":"default",)"
            R"("appBindings":{},"whitelist":{)";
        for (size_t index = 0; index < 257; ++index)
        {
            if (index != 0)
            {
                too_many.push_back(',');
            }
            too_many += "\"p" + std::to_string(index) + "\":false";
        }
        too_many +=
            R"(},"captureOwners":{},)"
            R"("control":{"masterEnabled":true,"bypass":false,"panicUntilEpochMs":0,)"
            R"("sidetoneEnabled":false,"sidetoneGainDb":0.0,)"
            R"("engineMode":"native_first"}})";
        ExpectRejected(std::move(too_many), "whitelist");
    }

    void TestGenerationDecisions()
    {
        using echidna::runtime::EvaluateGeneration;
        using echidna::runtime::GenerationDecision;
        CHECK(EvaluateGeneration(7, "payload", 0, {}) == GenerationDecision::kAccept,
              "first generation must be accepted");
        CHECK(EvaluateGeneration(8, "next", 7, "payload") == GenerationDecision::kAccept,
              "strict increase must be accepted");
        CHECK(EvaluateGeneration(6, "old", 7, "payload") ==
                  GenerationDecision::kRejectRollback,
              "rollback must reject");
        CHECK(EvaluateGeneration(7, "payload", 7, "payload") ==
                  GenerationDecision::kDuplicate,
              "exact same generation bytes must be idempotent");
        CHECK(EvaluateGeneration(7, "different", 7, "payload") ==
                  GenerationDecision::kRejectConflict,
              "same generation with different bytes must reject without hashes");
    }

    void TestSigned64BitIntegerBounds()
    {
        echidna::runtime::DecodedProfileSnapshot snapshot;
        std::string error;

        std::string maximum_generation = Envelope();
        maximum_generation.replace(maximum_generation.find("\"generation\":7"),
                                   std::string("\"generation\":7").size(),
                                   "\"generation\":9223372036854775807");
        CHECK(Decode(maximum_generation, "com.example.app", &snapshot, &error), error);
        CHECK(snapshot.generation == 9223372036854775807ULL,
              "Kotlin Long maximum generation must be accepted exactly");

        std::string oversized_generation = Envelope();
        oversized_generation.replace(oversized_generation.find("\"generation\":7"),
                                     std::string("\"generation\":7").size(),
                                     "\"generation\":9223372036854775808");
        ExpectRejected(std::move(oversized_generation), "signed 64-bit");

        std::string maximum_panic = Envelope();
        maximum_panic.replace(maximum_panic.find("\"panicUntilEpochMs\":0"),
                              std::string("\"panicUntilEpochMs\":0").size(),
                              "\"panicUntilEpochMs\":9223372036854775807");
        CHECK(Decode(maximum_panic, "com.example.app", &snapshot, &error), error);
        CHECK(!snapshot.global_hooks_enabled,
              "maximum representable future panic deadline must remain active");

        std::string oversized_panic = Envelope();
        oversized_panic.replace(oversized_panic.find("\"panicUntilEpochMs\":0"),
                                std::string("\"panicUntilEpochMs\":0").size(),
                                "\"panicUntilEpochMs\":9223372036854775808");
        ExpectRejected(std::move(oversized_panic), "signed 64-bit");
    }

} // namespace

int main()
{
    TestValidSelectionAndAdmission();
    TestExactOverridesAndOwnerHandshake();
    TestGlobalControlGates();
    TestMalformedAndUnknownRejection();
    TestReferenceAndTypeRejection();
    TestSizeAndCountBounds();
    TestGenerationDecisions();
    TestSigned64BitIntegerBounds();

    if (g_failures != 0)
    {
        std::fprintf(stderr, "profile_sync_protocol_test: %d failure(s)\n", g_failures);
        return 1;
    }
    std::fprintf(stderr, "profile_sync_protocol_test: all checks passed\n");
    return 0;
}
