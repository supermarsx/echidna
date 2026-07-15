#include "runtime/profile_sync_protocol.h"

#include <algorithm>
#include <charconv>
#include <cmath>
#include <cstdlib>
#include <initializer_list>
#include <limits>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <unordered_set>
#include <utility>
#include <vector>

namespace
{
    constexpr size_t kMaxJsonDepth = 64;
    constexpr size_t kMaxJsonNodes = 65536;

    enum class JsonType
    {
        kNull,
        kBool,
        kNumber,
        kString,
        kArray,
        kObject,
    };

    struct JsonValue
    {
        JsonType type{JsonType::kNull};
        size_t begin{0};
        size_t end{0};
        bool bool_value{false};
        std::string text;
        std::vector<JsonValue> array;
        std::vector<std::pair<std::string, JsonValue>> object;
    };

    class JsonError final : public std::runtime_error
    {
    public:
        explicit JsonError(const std::string &message) : std::runtime_error(message) {}
    };

    bool ValidateUtf8(std::string_view input)
    {
        size_t index = 0;
        while (index < input.size())
        {
            const uint8_t lead = static_cast<uint8_t>(input[index]);
            if (lead <= 0x7f)
            {
                ++index;
                continue;
            }

            size_t length = 0;
            uint32_t codepoint = 0;
            uint32_t minimum = 0;
            if ((lead & 0xe0u) == 0xc0u)
            {
                length = 2;
                codepoint = lead & 0x1fu;
                minimum = 0x80;
            }
            else if ((lead & 0xf0u) == 0xe0u)
            {
                length = 3;
                codepoint = lead & 0x0fu;
                minimum = 0x800;
            }
            else if ((lead & 0xf8u) == 0xf0u)
            {
                length = 4;
                codepoint = lead & 0x07u;
                minimum = 0x10000;
            }
            else
            {
                return false;
            }
            if (index + length > input.size())
            {
                return false;
            }
            for (size_t offset = 1; offset < length; ++offset)
            {
                const uint8_t continuation = static_cast<uint8_t>(input[index + offset]);
                if ((continuation & 0xc0u) != 0x80u)
                {
                    return false;
                }
                codepoint = (codepoint << 6u) | (continuation & 0x3fu);
            }
            if (codepoint < minimum || codepoint > 0x10ffffu ||
                (codepoint >= 0xd800u && codepoint <= 0xdfffu))
            {
                return false;
            }
            index += length;
        }
        return true;
    }

    void AppendUtf8(uint32_t codepoint, std::string *output)
    {
        if (codepoint <= 0x7fu)
        {
            output->push_back(static_cast<char>(codepoint));
        }
        else if (codepoint <= 0x7ffu)
        {
            output->push_back(static_cast<char>(0xc0u | (codepoint >> 6u)));
            output->push_back(static_cast<char>(0x80u | (codepoint & 0x3fu)));
        }
        else if (codepoint <= 0xffffu)
        {
            output->push_back(static_cast<char>(0xe0u | (codepoint >> 12u)));
            output->push_back(static_cast<char>(0x80u | ((codepoint >> 6u) & 0x3fu)));
            output->push_back(static_cast<char>(0x80u | (codepoint & 0x3fu)));
        }
        else
        {
            output->push_back(static_cast<char>(0xf0u | (codepoint >> 18u)));
            output->push_back(static_cast<char>(0x80u | ((codepoint >> 12u) & 0x3fu)));
            output->push_back(static_cast<char>(0x80u | ((codepoint >> 6u) & 0x3fu)));
            output->push_back(static_cast<char>(0x80u | (codepoint & 0x3fu)));
        }
    }

    class JsonParser
    {
    public:
        explicit JsonParser(std::string_view input) : input_(input) {}

        JsonValue parse()
        {
            if (!ValidateUtf8(input_))
            {
                throw JsonError("payload is not valid UTF-8");
            }
            skipWhitespace();
            JsonValue value = parseValue(0);
            skipWhitespace();
            if (position_ != input_.size())
            {
                fail("unexpected trailing JSON bytes");
            }
            return value;
        }

    private:
        [[noreturn]] void fail(const char *message) const
        {
            throw JsonError(std::string(message) + " at byte " +
                            std::to_string(position_));
        }

        void skipWhitespace()
        {
            while (position_ < input_.size())
            {
                const char value = input_[position_];
                if (value != ' ' && value != '\t' && value != '\r' && value != '\n')
                {
                    return;
                }
                ++position_;
            }
        }

        char consume()
        {
            if (position_ >= input_.size())
            {
                fail("unexpected end of JSON");
            }
            return input_[position_++];
        }

        void expect(char expected)
        {
            if (consume() != expected)
            {
                fail("unexpected JSON token");
            }
        }

        JsonValue parseValue(size_t depth)
        {
            if (depth > kMaxJsonDepth)
            {
                fail("JSON nesting exceeds limit");
            }
            if (++nodes_ > kMaxJsonNodes)
            {
                fail("JSON node count exceeds limit");
            }
            skipWhitespace();
            if (position_ >= input_.size())
            {
                fail("missing JSON value");
            }
            const size_t begin = position_;
            JsonValue value;
            switch (input_[position_])
            {
            case '{':
                value = parseObject(depth + 1);
                break;
            case '[':
                value = parseArray(depth + 1);
                break;
            case '"':
                value.type = JsonType::kString;
                value.text = parseString();
                break;
            case 't':
                parseLiteral("true");
                value.type = JsonType::kBool;
                value.bool_value = true;
                break;
            case 'f':
                parseLiteral("false");
                value.type = JsonType::kBool;
                value.bool_value = false;
                break;
            case 'n':
                parseLiteral("null");
                value.type = JsonType::kNull;
                break;
            default:
                value = parseNumber();
                break;
            }
            value.begin = begin;
            value.end = position_;
            return value;
        }

        void parseLiteral(std::string_view literal)
        {
            if (input_.substr(position_, literal.size()) != literal)
            {
                fail("invalid JSON literal");
            }
            position_ += literal.size();
        }

        uint32_t parseHexQuad()
        {
            if (position_ + 4 > input_.size())
            {
                fail("incomplete Unicode escape");
            }
            uint32_t value = 0;
            for (size_t index = 0; index < 4; ++index)
            {
                const char digit = input_[position_++];
                value <<= 4u;
                if (digit >= '0' && digit <= '9')
                {
                    value |= static_cast<uint32_t>(digit - '0');
                }
                else if (digit >= 'a' && digit <= 'f')
                {
                    value |= static_cast<uint32_t>(digit - 'a' + 10);
                }
                else if (digit >= 'A' && digit <= 'F')
                {
                    value |= static_cast<uint32_t>(digit - 'A' + 10);
                }
                else
                {
                    fail("invalid Unicode escape");
                }
            }
            return value;
        }

        std::string parseString()
        {
            expect('"');
            std::string result;
            while (position_ < input_.size())
            {
                const unsigned char value = static_cast<unsigned char>(consume());
                if (value == '"')
                {
                    return result;
                }
                if (value < 0x20u)
                {
                    fail("unescaped control byte in JSON string");
                }
                if (value != '\\')
                {
                    result.push_back(static_cast<char>(value));
                    continue;
                }

                const char escape = consume();
                switch (escape)
                {
                case '"':
                case '\\':
                case '/':
                    result.push_back(escape);
                    break;
                case 'b':
                    result.push_back('\b');
                    break;
                case 'f':
                    result.push_back('\f');
                    break;
                case 'n':
                    result.push_back('\n');
                    break;
                case 'r':
                    result.push_back('\r');
                    break;
                case 't':
                    result.push_back('\t');
                    break;
                case 'u':
                {
                    uint32_t codepoint = parseHexQuad();
                    if (codepoint >= 0xd800u && codepoint <= 0xdbffu)
                    {
                        if (position_ + 2 > input_.size() || input_[position_] != '\\' ||
                            input_[position_ + 1] != 'u')
                        {
                            fail("unpaired high surrogate");
                        }
                        position_ += 2;
                        const uint32_t low = parseHexQuad();
                        if (low < 0xdc00u || low > 0xdfffu)
                        {
                            fail("invalid low surrogate");
                        }
                        codepoint = 0x10000u + ((codepoint - 0xd800u) << 10u) +
                                    (low - 0xdc00u);
                    }
                    else if (codepoint >= 0xdc00u && codepoint <= 0xdfffu)
                    {
                        fail("unpaired low surrogate");
                    }
                    AppendUtf8(codepoint, &result);
                    break;
                }
                default:
                    fail("invalid JSON string escape");
                }
            }
            fail("unterminated JSON string");
        }

        JsonValue parseNumber()
        {
            const size_t begin = position_;
            if (input_[position_] == '-')
            {
                ++position_;
            }
            if (position_ >= input_.size())
            {
                fail("incomplete JSON number");
            }
            if (input_[position_] == '0')
            {
                ++position_;
                if (position_ < input_.size() && input_[position_] >= '0' &&
                    input_[position_] <= '9')
                {
                    fail("leading zero in JSON number");
                }
            }
            else
            {
                if (input_[position_] < '1' || input_[position_] > '9')
                {
                    fail("invalid JSON number");
                }
                while (position_ < input_.size() && input_[position_] >= '0' &&
                       input_[position_] <= '9')
                {
                    ++position_;
                }
            }
            if (position_ < input_.size() && input_[position_] == '.')
            {
                ++position_;
                if (position_ >= input_.size() || input_[position_] < '0' ||
                    input_[position_] > '9')
                {
                    fail("invalid JSON fraction");
                }
                while (position_ < input_.size() && input_[position_] >= '0' &&
                       input_[position_] <= '9')
                {
                    ++position_;
                }
            }
            if (position_ < input_.size() &&
                (input_[position_] == 'e' || input_[position_] == 'E'))
            {
                ++position_;
                if (position_ < input_.size() &&
                    (input_[position_] == '+' || input_[position_] == '-'))
                {
                    ++position_;
                }
                if (position_ >= input_.size() || input_[position_] < '0' ||
                    input_[position_] > '9')
                {
                    fail("invalid JSON exponent");
                }
                while (position_ < input_.size() && input_[position_] >= '0' &&
                       input_[position_] <= '9')
                {
                    ++position_;
                }
            }

            JsonValue value;
            value.type = JsonType::kNumber;
            value.text = std::string(input_.substr(begin, position_ - begin));
            return value;
        }

        JsonValue parseArray(size_t depth)
        {
            expect('[');
            JsonValue value;
            value.type = JsonType::kArray;
            skipWhitespace();
            if (position_ < input_.size() && input_[position_] == ']')
            {
                ++position_;
                return value;
            }
            while (true)
            {
                value.array.push_back(parseValue(depth));
                skipWhitespace();
                const char delimiter = consume();
                if (delimiter == ']')
                {
                    return value;
                }
                if (delimiter != ',')
                {
                    fail("expected comma or array terminator");
                }
                skipWhitespace();
            }
        }

        JsonValue parseObject(size_t depth)
        {
            expect('{');
            JsonValue value;
            value.type = JsonType::kObject;
            std::unordered_set<std::string> keys;
            skipWhitespace();
            if (position_ < input_.size() && input_[position_] == '}')
            {
                ++position_;
                return value;
            }
            while (true)
            {
                if (position_ >= input_.size() || input_[position_] != '"')
                {
                    fail("object key must be a string");
                }
                std::string key = parseString();
                if (!keys.insert(key).second)
                {
                    fail("duplicate JSON object key");
                }
                skipWhitespace();
                expect(':');
                JsonValue member = parseValue(depth);
                value.object.emplace_back(std::move(key), std::move(member));
                skipWhitespace();
                const char delimiter = consume();
                if (delimiter == '}')
                {
                    return value;
                }
                if (delimiter != ',')
                {
                    fail("expected comma or object terminator");
                }
                skipWhitespace();
            }
        }

        std::string_view input_;
        size_t position_{0};
        size_t nodes_{0};
    };

    const JsonValue *FindMember(const JsonValue &object, std::string_view key)
    {
        if (object.type != JsonType::kObject)
        {
            return nullptr;
        }
        const auto found = std::find_if(object.object.begin(),
                                        object.object.end(),
                                        [key](const auto &entry)
                                        { return entry.first == key; });
        return found == object.object.end() ? nullptr : &found->second;
    }

    bool SetError(std::string *error, std::string message)
    {
        if (error != nullptr)
        {
            *error = std::move(message);
        }
        return false;
    }

    bool RequireOnlyMembers(const JsonValue &object,
                            std::initializer_list<std::string_view> allowed,
                            std::string_view path,
                            std::string *error)
    {
        if (object.type != JsonType::kObject)
        {
            return SetError(error, std::string(path) + " must be an object");
        }
        for (const auto &[key, value] : object.object)
        {
            (void)value;
            if (std::find(allowed.begin(), allowed.end(), key) == allowed.end())
            {
                return SetError(error,
                                std::string(path) + " contains unknown field '" + key + "'");
            }
        }
        return true;
    }

    const JsonValue *RequireMember(const JsonValue &object,
                                   std::string_view key,
                                   std::string *error)
    {
        const JsonValue *member = FindMember(object, key);
        if (member == nullptr)
        {
            SetError(error, "missing required field '" + std::string(key) + "'");
        }
        return member;
    }

    bool ParseUnsigned(const JsonValue &value, uint64_t *output)
    {
        if (value.type != JsonType::kNumber || value.text.empty() ||
            value.text.front() < '0' || value.text.front() > '9')
        {
            return false;
        }
        if (!std::all_of(value.text.begin(), value.text.end(), [](char digit)
                         { return digit >= '0' && digit <= '9'; }))
        {
            return false;
        }
        uint64_t parsed = 0;
        const auto result = std::from_chars(value.text.data(),
                                            value.text.data() + value.text.size(),
                                            parsed);
        if (result.ec != std::errc{} || result.ptr != value.text.data() + value.text.size())
        {
            return false;
        }
        *output = parsed;
        return true;
    }

    bool ParseNonNegativeSigned64(const JsonValue &value, uint64_t *output)
    {
        uint64_t parsed = 0;
        if (!ParseUnsigned(value, &parsed) ||
            parsed > static_cast<uint64_t>(std::numeric_limits<int64_t>::max()))
        {
            return false;
        }
        *output = parsed;
        return true;
    }

    bool IsFiniteNumber(const JsonValue &value)
    {
        if (value.type != JsonType::kNumber)
        {
            return false;
        }
        char *end = nullptr;
        const double parsed = std::strtod(value.text.c_str(), &end);
        return end == value.text.c_str() + value.text.size() && std::isfinite(parsed);
    }

    bool IsAsciiAlphaNumeric(char value)
    {
        return (value >= 'a' && value <= 'z') || (value >= 'A' && value <= 'Z') ||
               (value >= '0' && value <= '9');
    }

    bool IsValidProfileId(std::string_view value)
    {
        if (value.empty() || value.size() > echidna::runtime::kProfileSyncMaxProfileIdBytes)
        {
            return false;
        }
        return std::all_of(value.begin(), value.end(), [](char character)
                           {
                               return IsAsciiAlphaNumeric(character) || character == '.' ||
                                      character == '_' || character == '-';
                           });
    }

    bool IsValidProcessName(std::string_view value, bool allow_colon)
    {
        if (value.empty() || value.size() > echidna::runtime::kProfileSyncMaxProcessNameBytes)
        {
            return false;
        }
        return std::all_of(value.begin(), value.end(), [allow_colon](char character)
                           {
                               return IsAsciiAlphaNumeric(character) || character == '.' ||
                                      character == '_' || (allow_colon && character == ':');
                           });
    }

    std::string_view BasePackage(std::string_view process)
    {
        const size_t separator = process.find(':');
        return separator == std::string_view::npos ? process : process.substr(0, separator);
    }

    const JsonValue *ResolveExactThenBase(const JsonValue &object, std::string_view process)
    {
        if (const JsonValue *exact = FindMember(object, process))
        {
            return exact;
        }
        const std::string_view base = BasePackage(process);
        return base == process ? nullptr : FindMember(object, base);
    }

    bool ValidateStructuredPreset(const JsonValue &preset)
    {
        const JsonValue *modules = FindMember(preset, "modules");
        const JsonValue *engine = FindMember(preset, "engine");
        return preset.type == JsonType::kObject && modules != nullptr && engine != nullptr &&
               modules->type == JsonType::kArray && engine->type == JsonType::kObject;
    }

} // namespace

namespace echidna::runtime
{
    bool DecodeProfileSyncV2(std::string_view payload,
                             std::string_view process_name,
                             uint64_t now_epoch_ms,
                             DecodedProfileSnapshot *snapshot,
                             std::string *error)
    {
        if (snapshot == nullptr)
        {
            return SetError(error, "snapshot output is null");
        }
        *snapshot = {};
        if (payload.empty() || payload.size() > kProfileSyncMaxEnvelopeBytes)
        {
            return SetError(error, "envelope byte length is outside the allowed range");
        }
        if (!IsValidProcessName(process_name, true))
        {
            return SetError(error, "cached process name is invalid");
        }

        JsonValue root;
        try
        {
            root = JsonParser(payload).parse();
        }
        catch (const JsonError &parse_error)
        {
            return SetError(error, parse_error.what());
        }
        catch (const std::exception &parse_error)
        {
            return SetError(error, std::string("JSON parser failure: ") + parse_error.what());
        }

        if (!RequireOnlyMembers(root,
                                {"schemaVersion",
                                 "generation",
                                 "profiles",
                                 "defaultProfileId",
                                 "appBindings",
                                 "whitelist",
                                 "captureOwners",
                                 "control"},
                                "root",
                                error))
        {
            return false;
        }

        const JsonValue *schema = RequireMember(root, "schemaVersion", error);
        const JsonValue *generation = RequireMember(root, "generation", error);
        const JsonValue *profiles = RequireMember(root, "profiles", error);
        const JsonValue *default_profile = RequireMember(root, "defaultProfileId", error);
        const JsonValue *bindings = RequireMember(root, "appBindings", error);
        const JsonValue *whitelist = RequireMember(root, "whitelist", error);
        const JsonValue *owners = RequireMember(root, "captureOwners", error);
        const JsonValue *control = RequireMember(root, "control", error);
        if (schema == nullptr || generation == nullptr || profiles == nullptr ||
            default_profile == nullptr || bindings == nullptr || whitelist == nullptr ||
            owners == nullptr || control == nullptr)
        {
            return false;
        }

        uint64_t schema_version = 0;
        if (!ParseUnsigned(*schema, &schema_version) || schema_version != 2)
        {
            return SetError(error, "schemaVersion must be integer 2");
        }
        if (!ParseNonNegativeSigned64(*generation, &snapshot->generation) ||
            snapshot->generation == 0)
        {
            return SetError(error, "generation must be a positive signed 64-bit integer");
        }
        if (profiles->type != JsonType::kObject || profiles->object.empty() ||
            profiles->object.size() > kProfileSyncMaxEntries)
        {
            return SetError(error, "profiles must contain 1..256 entries");
        }

        std::unordered_map<std::string, const JsonValue *> profile_by_id;
        profile_by_id.reserve(profiles->object.size());
        for (const auto &[id, preset] : profiles->object)
        {
            if (!IsValidProfileId(id))
            {
                return SetError(error, "invalid profile id '" + id + "'");
            }
            if (!ValidateStructuredPreset(preset))
            {
                return SetError(error, "profile '" + id + "' is not a structured preset");
            }
            if (preset.end < preset.begin || preset.end - preset.begin > kProfileSyncMaxPresetBytes)
            {
                return SetError(error, "profile '" + id + "' exceeds 256 KiB");
            }
            profile_by_id.emplace(id, &preset);
        }
        if (default_profile->type != JsonType::kString ||
            !IsValidProfileId(default_profile->text) ||
            profile_by_id.find(default_profile->text) == profile_by_id.end())
        {
            return SetError(error, "defaultProfileId must reference a profile in the envelope");
        }

        if (bindings->type != JsonType::kObject ||
            bindings->object.size() > kProfileSyncMaxEntries)
        {
            return SetError(error, "appBindings must be an object with at most 256 entries");
        }
        for (const auto &[package_name, profile] : bindings->object)
        {
            if (!IsValidProcessName(package_name, false) || profile.type != JsonType::kString ||
                !IsValidProfileId(profile.text) ||
                profile_by_id.find(profile.text) == profile_by_id.end())
            {
                return SetError(error, "invalid or dangling appBinding for '" + package_name + "'");
            }
        }

        if (whitelist->type != JsonType::kObject ||
            whitelist->object.size() > kProfileSyncMaxEntries)
        {
            return SetError(error, "whitelist must be an object with at most 256 entries");
        }
        for (const auto &[process, allowed] : whitelist->object)
        {
            if (!IsValidProcessName(process, true) || allowed.type != JsonType::kBool)
            {
                return SetError(error, "invalid whitelist entry for '" + process + "'");
            }
        }

        if (owners->type != JsonType::kObject || owners->object.size() > kProfileSyncMaxEntries)
        {
            return SetError(error, "captureOwners must be an object with at most 256 entries");
        }
        for (const auto &[process, owner] : owners->object)
        {
            if (!IsValidProcessName(process, true) || owner.type != JsonType::kString ||
                (owner.text != "zygisk" && owner.text != "lsposed"))
            {
                return SetError(error, "invalid capture owner for '" + process + "'");
            }
        }

        if (!RequireOnlyMembers(*control,
                                {"masterEnabled",
                                 "bypass",
                                 "panicUntilEpochMs",
                                 "sidetoneEnabled",
                                 "sidetoneGainDb",
                                 "engineMode"},
                                "control",
                                error))
        {
            return false;
        }
        const JsonValue *master_enabled = RequireMember(*control, "masterEnabled", error);
        const JsonValue *bypass = RequireMember(*control, "bypass", error);
        const JsonValue *panic_until = RequireMember(*control, "panicUntilEpochMs", error);
        const JsonValue *sidetone = RequireMember(*control, "sidetoneEnabled", error);
        const JsonValue *gain = RequireMember(*control, "sidetoneGainDb", error);
        const JsonValue *mode = RequireMember(*control, "engineMode", error);
        if (master_enabled == nullptr || bypass == nullptr || panic_until == nullptr ||
            sidetone == nullptr || gain == nullptr || mode == nullptr)
        {
            return false;
        }
        if (master_enabled->type != JsonType::kBool || bypass->type != JsonType::kBool)
        {
            return SetError(error, "masterEnabled and bypass must be booleans");
        }
        uint64_t panic_until_ms = 0;
        if (!ParseNonNegativeSigned64(*panic_until, &panic_until_ms))
        {
            return SetError(error,
                            "panicUntilEpochMs must be a non-negative signed 64-bit integer");
        }
        if (sidetone->type != JsonType::kBool)
        {
            return SetError(error, "sidetoneEnabled must be a boolean");
        }
        if (!IsFiniteNumber(*gain))
        {
            return SetError(error, "sidetoneGainDb must be finite");
        }
        if (mode->type != JsonType::kString ||
            (mode->text != "native_first" && mode->text != "low_latency" &&
             mode->text != "compatibility"))
        {
            return SetError(error, "engineMode is invalid");
        }

        snapshot->global_hooks_enabled =
            master_enabled->bool_value && !bypass->bool_value &&
            (panic_until_ms == 0 || now_epoch_ms >= panic_until_ms) &&
            mode->text != "compatibility";

        if (const JsonValue *allowed = ResolveExactThenBase(*whitelist, process_name))
        {
            snapshot->process_whitelisted = allowed->bool_value;
        }
        if (const JsonValue *owner = ResolveExactThenBase(*owners, process_name))
        {
            snapshot->capture_owner = owner->text == "zygisk" ? CaptureOwner::kZygisk
                                                               : CaptureOwner::kLsposed;
        }

        const std::string_view package_name = BasePackage(process_name);
        snapshot->profile_id = default_profile->text;
        if (const JsonValue *binding = FindMember(*bindings, package_name))
        {
            snapshot->profile_id = binding->text;
        }
        const JsonValue *selected_profile = profile_by_id.at(snapshot->profile_id);
        snapshot->preset_json =
            std::string(payload.substr(selected_profile->begin,
                                       selected_profile->end - selected_profile->begin));
        if (error != nullptr)
        {
            error->clear();
        }
        return true;
    }

    GenerationDecision EvaluateGeneration(uint64_t generation,
                                          std::string_view payload,
                                          uint64_t previous_generation,
                                          std::string_view previous_payload)
    {
        if (previous_generation == 0 || generation > previous_generation)
        {
            return GenerationDecision::kAccept;
        }
        if (generation < previous_generation)
        {
            return GenerationDecision::kRejectRollback;
        }
        return payload == previous_payload ? GenerationDecision::kDuplicate
                                           : GenerationDecision::kRejectConflict;
    }

} // namespace echidna::runtime
