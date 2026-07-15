#include "utils/process_utils.h"

#include <cstdint>
#include <cstdio>
#include <limits>

namespace
{
    int g_failures = 0;

    void Check(bool condition, const char *expression, int line)
    {
        if (!condition)
        {
            std::fprintf(stderr, "FAIL line %d: %s\n", line, expression);
            ++g_failures;
        }
    }

#define CHECK(condition) Check((condition), #condition, __LINE__)

    void TestStrictPackageUidParsing()
    {
        constexpr const char *packages =
            "com.example.other 10123 0 /data/user/0/com.example.other default none 0 0\n"
            "com.echidna.app 10234 0 /data/user/0/com.echidna.app default none 0 0\n"
            "com.echidna.app.helper 10567 0 /data/user/0/helper default none 0 0\n";
        CHECK(echidna::utils::ParsePackageUid(packages, "com.echidna.app") == 10234);
        CHECK(echidna::utils::ParsePackageUid(packages, "com.echidna.missing") == -1);
        CHECK(echidna::utils::ParsePackageUid(
                  "com.echidna.app nope 0 /data/user/0/com.echidna.app\n",
                  "com.echidna.app") == -1);
        CHECK(echidna::utils::ParsePackageUid(
                  "com.echidna.app 10234 x\ncom.echidna.app 10235 x\n",
                  "com.echidna.app") == -1);
        CHECK(echidna::utils::ParsePackageUid(
                  "com.echidna.app 10234 x\ncom.echidna.app 10234 x\n",
                  "com.echidna.app") == -1);
        CHECK(echidna::utils::ParsePackageUid(
                  "com.echidna.app 4294967296 x\n",
                  "com.echidna.app") == -1);
        CHECK(echidna::utils::ParsePackageUid(packages, "") == -1);
    }

    void TestAndroidUserProjection()
    {
        CHECK(echidna::utils::PackageUidForTargetUser(10234, 1010001) == 1010234);
        CHECK(echidna::utils::PackageUidForTargetUser(10234, 10001) == 10234);
        CHECK(echidna::utils::PackageUidForTargetUser(1000, 10001) == -1);
        CHECK(echidna::utils::PackageUidForTargetUser(-1, 10001) == -1);
        CHECK(echidna::utils::PackageUidForTargetUser(10234, -1) == -1);
        CHECK(echidna::utils::PackageUidForTargetUser(
                  99999,
                  std::numeric_limits<int64_t>::max()) == -1);
    }

} // namespace

int main()
{
    TestStrictPackageUidParsing();
    TestAndroidUserProjection();
    if (g_failures != 0)
    {
        std::fprintf(stderr, "process_utils_test: %d failure(s)\n", g_failures);
        return 1;
    }
    std::fprintf(stderr, "process_utils_test: all checks passed\n");
    return 0;
}
